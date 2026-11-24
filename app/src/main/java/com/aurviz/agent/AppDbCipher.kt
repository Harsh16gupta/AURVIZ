package com.aurviz.agent

import android.content.Context
import androidx.room.RoomDatabase
import java.io.File
import java.io.FileInputStream
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

// ADR-0006: at-rest SQLCipher encryption for aurviz.db plus safe one-time
// plaintext -> SQLCipher migration for existing installs.

const val AURVIZ_DB_NAME = "aurviz.db"

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
 * If [dbFile] is an existing unencrypted SQLite database, exports its contents
 * into a temporary SQLCipher-encrypted database using [passphrase] and atomically
 * replaces the plaintext file (removing stale -wal/-shm sidecars).
 */
fun migratePlaintextDatabaseIfNeeded(dbFile: File, passphrase: ByteArray) {
    if (!isPlaintextSqliteFile(dbFile)) return
    val parent = dbFile.parentFile ?: return
    val tempEncrypted = File(parent, "${dbFile.name}.sqlcipher.tmp")
    if (tempEncrypted.exists()) tempEncrypted.delete()

    val plainDb = SQLiteDatabase.openDatabase(
        dbFile.absolutePath,
        ByteArray(0),
        null,
        SQLiteDatabase.OPEN_READWRITE,
        null,
        null,
    )
    try {
        // Flush any pending WAL frames into the main plaintext file before export.
        plainDb.rawQuery("PRAGMA wal_checkpoint(FULL);", null).use { cursor ->
            if (cursor.moveToFirst()) Unit
        }
        val escapedPath = tempEncrypted.absolutePath.replace("'", "''")
        plainDb.rawExecSQL("ATTACH DATABASE '$escapedPath' AS encrypted KEY ?;", arrayOf<Any>(passphrase))
        plainDb.rawQuery("SELECT sqlcipher_export('encrypted');", null).use { cursor ->
            if (cursor.moveToFirst()) Unit
        }
        plainDb.rawExecSQL("DETACH DATABASE encrypted;")
    } finally {
        plainDb.close()
    }

    File(parent, "${dbFile.name}-wal").delete()
    File(parent, "${dbFile.name}-shm").delete()
    if (!dbFile.delete() || !tempEncrypted.renameTo(dbFile)) {
        tempEncrypted.copyTo(dbFile, overwrite = true)
        tempEncrypted.delete()
    }
}

/**
 * Attaches SQLCipher's [SupportOpenHelperFactory] to a Room builder after
 * ensuring the native `sqlcipher` library is loaded and any pre-existing
 * plaintext `aurviz.db` has been migrated in place.
 */
fun <T : RoomDatabase> RoomDatabase.Builder<T>.configureEncryptedRoom(
    context: Context,
    tokenVault: TokenVault,
    dbName: String = AURVIZ_DB_NAME,
): RoomDatabase.Builder<T> {
    System.loadLibrary("sqlcipher")
    val passphrase = tokenVault.getOrCreateDbPassphrase()
    val dbFile = context.getDatabasePath(dbName)
    migratePlaintextDatabaseIfNeeded(dbFile, passphrase)
    return openHelperFactory(SupportOpenHelperFactory(passphrase))
}
