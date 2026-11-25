package com.aira.agent

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File
import java.io.FileInputStream
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

// ADR-0006: at-rest SQLCipher encryption for aira.db plus safe one-time
// plaintext -> SQLCipher migration for existing installs.

const val AIRA_DB_NAME = "aira.db"

// Standard 16-byte SQLite3 plaintext magic header: "SQLite format 3" followed
// by a NUL byte. An encrypted SQLCipher database begins with random salt
// bytes, never this header.
val SQLITE_PLAINTEXT_HEADER: ByteArray =
    "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

fun isPlaintextSqliteHeader(header: ByteArray): Boolean {
    if (header.size < SQLITE_PLAINTEXT_HEADER.size) return false
    for (i in SQLITE_PLAINTEXT_HEADER.indices) {
        if (header[i] != SQLITE_PLAINTEXT_HEADER[i]) return false
    }
    return true
}

fun isPlaintextSqliteFile(dbFile: File): Boolean {
    if (!dbFile.exists() || !dbFile.isFile || dbFile.length() < SQLITE_PLAINTEXT_HEADER.size) {
        return false
    }
    val buffer = ByteArray(SQLITE_PLAINTEXT_HEADER.size)
    return try {
        FileInputStream(dbFile).use { input ->
            val read = input.read(buffer)
            read == SQLITE_PLAINTEXT_HEADER.size && isPlaintextSqliteHeader(buffer)
        }
    } catch (_: Exception) {
        false
    }
}

/**
 * Migrates an existing plaintext `aira.db` into a SQLCipher-encrypted
 * database, atomically replacing the plaintext file (removing stale
 * -wal/-shm sidecars) while preserving every row, including autoincrement
 * pids (Schedule alarm ids and pending-approval deep links are derived
 * from pids, so they must survive verbatim).
 *
 * Method: Room-to-Room copy through DAOs. The plaintext file is opened
 * with a plain (unencrypted) Room instance, all rows are snapshotted to
 * memory, the file is replaced with a fresh encrypted database keyed by
 * [passphrase], and the snapshot is written back. No raw SQLCipher SQL is
 * involved at all: attached aliases stay visible only on the connection
 * that ran the attach (pooled connections hide them from the very next
 * statement — three upgrade launches died with "unknown database
 * encrypted"), key bytes can never be embedded in SQL text, and DAOs are
 * the one seam both sides share, enforcing the schema on each end.
 *
 * Never throws: any failure restores the safety backup (copied before
 * anything destructive) so the file is plaintext again and the next launch
 * retries; the current launch then proceeds and Room queries fail soft
 * (empty UI, never a crash). If even the backup is gone, Room creates a
 * fresh encrypted database. Startup can never crash-loop.
 */
fun migratePlaintextDatabaseIfNeeded(context: Context, dbFile: File, dbName: String, passphrase: ByteArray) {
    if (!isPlaintextSqliteFile(dbFile)) return
    val parent = dbFile.parentFile ?: return
    var backup: File? = null
    try {
        backup = plaintextBackupFile(dbFile)
        dbFile.copyTo(backup)
        val snapshot = readPlaintextSnapshot(context, dbName)
        if (!dbFile.delete() && dbFile.exists()) {
            throw IllegalStateException("could not remove plaintext db before rekey")
        }
        File(parent, "${dbFile.name}-wal").delete()
        File(parent, "${dbFile.name}-shm").delete()
        writeEncryptedSnapshot(context, dbName, passphrase, snapshot)
        Log.i(
            "AppDbCipher",
            "Plaintext migration done:" +
                " memories=${snapshot.memories.size}" +
                " schedules=${snapshot.schedules.size}" +
                " runs=${snapshot.runs.size}" +
                " approvals=${snapshot.approvals.size}" +
                " interactions=${snapshot.interactions.size}",
        )
    } catch (e: Exception) {
        try {
            Log.e("AppDbCipher", "Plaintext migration failed; restoring backup", e)
        } catch (_: Exception) {
        }
        try {
            val safe = backup
            if (safe != null && safe.exists() && !isPlaintextSqliteFile(dbFile)) {
                File(parent, "${dbFile.name}-wal").delete()
                File(parent, "${dbFile.name}-shm").delete()
                safe.copyTo(dbFile, overwrite = true)
            }
        } catch (_: Exception) {
        }
    }
}

// In-memory copy of every user table. FTS content is derived (Room's sync
// triggers rebuild it on insert), so it is never copied directly.
data class AppDbSnapshot(
    val memories: List<MemoryRow>,
    val schedules: List<ScheduleRow>,
    val runs: List<ScheduleRunRow>,
    val approvals: List<PendingApprovalRow>,
    val interactions: List<InteractionRow>,
)

fun readAppDbSnapshot(db: AppDb): AppDbSnapshot =
    AppDbSnapshot(
        memories = db.memories().listAll(),
        schedules = db.schedules().listAll(),
        runs = db.runs().listAll(),
        approvals = db.approvals().listAll(),
        interactions = db.interactions().listRecent(Int.MAX_VALUE),
    )

// Explicit pids round-trip: Room inserts the provided non-zero pid for
// autoGenerate keys, so "sched_<pid>" alarm ids and "approval_<pid>"
// deep links stay valid after migration.
fun writeAppDbSnapshot(db: AppDb, snapshot: AppDbSnapshot) {
    snapshot.memories.forEach { db.memories().insert(it) }
    snapshot.schedules.forEach { db.schedules().insert(it) }
    snapshot.runs.forEach { db.runs().insertIgnore(it) }
    snapshot.approvals.forEach { db.approvals().insert(it) }
    snapshot.interactions.forEach { db.interactions().insert(it) }
}

// Plain (unencrypted) Room open of the existing file: schema migrations
// run here, so a v1..v6 plaintext file is brought to v7 before the read.
// allowMainThreadQueries only on this throwaway migration instance — the
// file is small (one startup copy) and production instances stay strict.
fun readPlaintextSnapshot(context: Context, dbName: String): AppDbSnapshot {
    val plain =
        Room.databaseBuilder(context, AppDb::class.java, dbName)
            .addMigrations(*AppMigrations)
            .allowMainThreadQueries()
            .build()
    try {
        return readAppDbSnapshot(plain)
    } finally {
        try {
            plain.close()
        } catch (_: Exception) {
        }
    }
}

// Fresh encrypted database plus row-count proof before returning: a wrong
// key or a short write fails here while the backup still exists.
fun writeEncryptedSnapshot(context: Context, dbName: String, passphrase: ByteArray, snapshot: AppDbSnapshot) {
    val encrypted =
        Room.databaseBuilder(context, AppDb::class.java, dbName)
            .addMigrations(*AppMigrations)
            .openHelperFactory(SupportOpenHelperFactory(passphrase.copyOf()))
            .allowMainThreadQueries()
            .build()
    try {
        writeAppDbSnapshot(encrypted, snapshot)
        val check = readAppDbSnapshot(encrypted)
        require(
            check.memories.size == snapshot.memories.size &&
                check.schedules.size == snapshot.schedules.size &&
                check.runs.size == snapshot.runs.size &&
                check.approvals.size == snapshot.approvals.size &&
                check.interactions.size == snapshot.interactions.size,
        ) { "row count mismatch after migration" }
    } finally {
        try {
            encrypted.close()
        } catch (_: Exception) {
        }
    }
}

// Quarantine name for a plaintext file that failed migration: first
// `<name>.plaintext-backup`, then suffixed `-2`, `-3`, ... so a repeated
// failure never overwrites an earlier backup.
fun plaintextBackupFile(dbFile: File): File {
    val parent = dbFile.parentFile ?: return File("${dbFile.path}.plaintext-backup")
    val first = File(parent, "${dbFile.name}.plaintext-backup")
    if (!first.exists()) return first
    var n = 2
    while (true) {
        val next = File(parent, "${dbFile.name}.plaintext-backup-$n")
        if (!next.exists()) return next
        n += 1
    }
}

/**
 * Attaches SQLCipher's [SupportOpenHelperFactory] to a Room builder after
 * ensuring the native `sqlcipher` library is loaded and any pre-existing
 * plaintext `aira.db` has been migrated in place.
 */
// One-time rename from the pre-AIRA database file: installs that still
// carry `aurviz.db` get it moved to the new name (plus -wal/-shm sidecars)
// so no user data is lost. Fresh installs skip this entirely.
fun carryOverLegacyDatabaseFile(context: Context, dbName: String) {
    if (dbName != AIRA_DB_NAME) return
    val current = context.getDatabasePath(AIRA_DB_NAME)
    if (current.exists()) return
    val legacy = context.getDatabasePath("aurviz.db")
    if (!legacy.exists()) return
    legacy.renameTo(current)
    File(current.parent, "aurviz.db-wal").renameTo(File(current.parent, "${current.name}-wal"))
    File(current.parent, "aurviz.db-shm").renameTo(File(current.parent, "${current.name}-shm"))
}

fun <T : RoomDatabase> RoomDatabase.Builder<T>.configureEncryptedRoom(
    context: Context,
    tokenVault: TokenVault,
    dbName: String = AIRA_DB_NAME,
): RoomDatabase.Builder<T> {
    System.loadLibrary("sqlcipher")
    val passphrase = tokenVault.getOrCreateDbPassphrase()
    carryOverLegacyDatabaseFile(context, dbName)
    val dbFile = context.getDatabasePath(dbName)
    migratePlaintextDatabaseIfNeeded(context, dbFile, dbName, passphrase)
    return openHelperFactory(SupportOpenHelperFactory(passphrase))
}
