package com.aira.agent

import android.content.SharedPreferences
import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket #114 (ADR-0006): aira.db at-rest SQLCipher encryption. TokenVault
// generates/persists the 256-bit passphrase; AppDbCipher detects plaintext
// headers and wires SQLCipher into every Room open site.
class AppDbCipherTest {

    private fun vaultWithBacking(backing: CipherFakePrefs = CipherFakePrefs()): Pair<TokenVault, CipherFakePrefs> =
        Pair(TokenVault(backing), backing)

    @Test
    fun vaultGeneratesAndPersists256BitDbPassphrase() {
        val (vault, _) = vaultWithBacking()
        val first = vault.getOrCreateDbPassphrase()
        assertEquals(DB_PASSPHRASE_BYTES, first.size)
        val second = vault.getOrCreateDbPassphrase()
        assertEquals(DB_PASSPHRASE_BYTES, second.size)
        assertTrue("passphrase must persist across calls", first.contentEquals(second))
    }

    @Test
    fun clearGmailAndClearLlmKeysPreserveDbPassphrase() {
        val (vault, _) = vaultWithBacking()
        val before = vault.getOrCreateDbPassphrase()
        vault.clearGmail()
        vault.clearLlmKeys()
        val after = vault.getOrCreateDbPassphrase()
        assertTrue("db passphrase must survive clearGmail/clearLlmKeys", before.contentEquals(after))
    }

    @Test
    fun corruptStoredPassphraseRegeneratesCleanly() {
        val (vault, backing) = vaultWithBacking()
        // Invalid Base64 content must be replaced with a fresh 256-bit key.
        backing.edit().putString(DB_PASSPHRASE_PREF_KEY, "!!!not-valid-base64!!!").apply()
        val first = vault.getOrCreateDbPassphrase()
        assertEquals(DB_PASSPHRASE_BYTES, first.size)
        // The regenerated key is what gets persisted.
        val stored = backing.getString(DB_PASSPHRASE_PREF_KEY, null)
        assertTrue(first.contentEquals(java.util.Base64.getUrlDecoder().decode(stored)))
        // Wrong-length but valid Base64 must also be replaced.
        val short = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(4) { 1 })
        backing.edit().putString(DB_PASSPHRASE_PREF_KEY, short).apply()
        val second = vault.getOrCreateDbPassphrase()
        assertEquals(DB_PASSPHRASE_BYTES, second.size)
        assertFalse("wrong-length entry must be overwritten", backing.getString(DB_PASSPHRASE_PREF_KEY, null) == short)
        // Regenerated key persists.
        assertTrue(second.contentEquals(vault.getOrCreateDbPassphrase()))
    }

    @Test
    fun isPlaintextSqliteFileDetectsMagicHeaderAndRejectsEncryptedOrShortFiles() {
        val dir = Files.createTempDirectory("appdbcipher").toFile()
        try {
            // Missing file -> false.
            assertFalse(isPlaintextSqliteFile(File(dir, "missing.db")))
            // Short file (< 16 bytes) -> false without throwing.
            val short = File(dir, "short.db")
            short.writeBytes(ByteArray(8) { it.toByte() })
            assertFalse(isPlaintextSqliteFile(short))
            // Plaintext SQLite magic header + padding -> true.
            val plain = File(dir, "plain.db")
            plain.writeBytes(SQLITE_PLAINTEXT_HEADER + ByteArray(100) { 0 })
            assertTrue(isPlaintextSqliteFile(plain))
            // Random cipher salt bytes (never the magic header) -> false.
            val encrypted = File(dir, "encrypted.db")
            encrypted.writeBytes(ByteArray(32) { (it * 31 + 7).toByte() })
            assertFalse(isPlaintextSqliteFile(encrypted))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun allDatabaseOpenSitesWireConfigureEncryptedRoom() {
        val viewModel = File(moduleDir(), "src/main/java/com/aira/agent/ui/AgentViewModel.kt").readText()
        val scheduleWork = File(moduleDir(), "src/main/java/com/aira/agent/ScheduleWork.kt").readText()
        val proguard = File(moduleDir(), "proguard-rules.pro").readText()
        // The plaintext builder chains must stay intact (test pins).
        assertTrue(viewModel.contains("Room.databaseBuilder(application, AppDb::class.java, \"aira.db\")"))
        assertTrue(scheduleWork.contains("Room.databaseBuilder(applicationContext, AppDb::class.java, \"aira.db\")"))
        assertTrue(viewModel.contains(".addMigrations(*AppMigrations)"))
        assertTrue(scheduleWork.contains(".addMigrations(*AppMigrations)"))
        // Every open site chains the SQLCipher helper.
        assertTrue(viewModel.contains(".configureEncryptedRoom("))
        val scheduleWorkSites = scheduleWork.split(".configureEncryptedRoom(").size - 1
        assertEquals("ScheduleWorker + RescheduleWorker must both wire SQLCipher", 2, scheduleWorkSites)
        // Release R8 must keep SQLCipher's JNI entry points.
        assertTrue(proguard.contains("-keep class net.zetetic.database.**"))
    }

    @Test
    fun migrationCopiesThroughDaosNotNativeSql() {
        // Mechanism pin: the migration is a Room-to-Room DAO copy — the
        // only native-SQL tricks (attach alias, copy function, rekey call,
        // KEY literal) all died on-device (pool-hidden alias, logic-error
        // form, pool-reconfigure rekey failure). DAOs are pool-safe.
        val cipher = File(moduleDir(), "src/main/java/com/aira/agent/AppDbCipher.kt").readText()
        assertTrue(cipher.contains("readAppDbSnapshot("))
        assertTrue(cipher.contains("writeAppDbSnapshot("))
        assertTrue(cipher.contains("allowMainThreadQueries()"))
        assertFalse(cipher.contains("ATTACH DATABASE"))
        assertFalse(cipher.contains("sqlcipher_export"))
        assertFalse(cipher.contains("changePassword("))
        assertFalse(cipher.contains("KEY ?"))
    }

    @Test
    fun snapshotRoundTripPreservesRowsAndPids() {
        // Alarm ids ("sched_<pid>") and held-write links ("approval_<pid>")
        // derive from autoincrement pids: the copy must keep them verbatim.
        val source = FakeAppDb()
        source.memoryDao.rows +=
            MemoryRow(pid = 4, text = "A", source = "you told me", category = "general", importance = 1)
        source.memoryDao.rows +=
            MemoryRow(pid = 9, text = "B", source = "you told me", category = "general", importance = 0)
        source.scheduleDao.rows += ScheduleRow(pid = 7, instruction = "check mail", hour = 9, minute = 0)
        source.runDao.rows +=
            ScheduleRunRow(pid = 3, scheduleId = "sched_7", fireTime = 11L, status = "RAN", error = null, result = "ok", durationMs = 5L)
        source.approvalDao.rows +=
            PendingApprovalRow(pid = 5, scheduleId = "sched_7", toolName = "send_email", argsJson = "{}", reason = "r", createdAt = 9L)
        source.interactionDao.rows +=
            InteractionRow(pid = 12, sessionId = "s_1", command = "hi", responseText = "yo", provider = null, timestampMs = 77L, failed = false)

        val snapshot = readAppDbSnapshot(source)
        val target = FakeAppDb()
        writeAppDbSnapshot(target, snapshot)

        assertEquals(source.memoryDao.rows, target.memoryDao.rows)
        assertEquals(source.scheduleDao.rows, target.scheduleDao.rows)
        assertEquals(source.runDao.rows, target.runDao.rows)
        assertEquals(source.approvalDao.rows, target.approvalDao.rows)
        assertEquals(source.interactionDao.rows, target.interactionDao.rows)
        assertEquals(listOf(4L, 9L), target.memoryDao.rows.map { it.pid })
        assertEquals(7L, target.scheduleDao.rows.single().pid)
    }

    @Test
    fun plaintextBackupFileNeverOverwritesEarlierBackup() {
        val dir = Files.createTempDirectory("appdbcipher-backup").toFile()
        try {
            val dbFile = File(dir, "aira.db")
            dbFile.writeBytes(ByteArray(0))
            assertEquals(File(dir, "aira.db.plaintext-backup"), plaintextBackupFile(dbFile))
            File(dir, "aira.db.plaintext-backup").writeBytes(ByteArray(0))
            assertEquals(File(dir, "aira.db.plaintext-backup-2"), plaintextBackupFile(dbFile))
            File(dir, "aira.db.plaintext-backup-2").writeBytes(ByteArray(0))
            assertEquals(File(dir, "aira.db.plaintext-backup-3"), plaintextBackupFile(dbFile))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun migrationKeysCopyWithoutEmbeddingKeyMaterial() {
        // Regression pin for the launch crash-loop: the first migration ran
        // the copy through an attach alias with a bound KEY placeholder and
        // every upgrade launch died with "unknown database encrypted".
        // The migration must copy Room-to-Room through DAOs — never by
        // smuggling the key through SQL text.
        val cipher = File(moduleDir(), "src/main/java/com/aira/agent/AppDbCipher.kt").readText()
        assertFalse(cipher.contains("KEY ?"))
        assertFalse(cipher.contains("AS encrypted KEY"))
        assertFalse(cipher.contains("sqlcipher_export('main'"))
        assertTrue(cipher.contains("readAppDbSnapshot("))
    }

    private fun moduleDir(): File {
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            if (File(dir, "src/main/AndroidManifest.xml").exists()) return dir
            dir = dir.parentFile ?: throw AssertionError("Could not locate app module dir from user.dir")
        }
    }
}

// In-memory SharedPreferences for TokenVault under test (same seam as
// LlmKeysTest's fake: production passes EncryptedSharedPreferences).
private class CipherFakePrefs : SharedPreferences {
    private val data = mutableMapOf<String, String?>()
    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, String?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            removals.remove(key)
            pending[key] = value
            return this
        }
        override fun remove(key: String): SharedPreferences.Editor {
            pending.remove(key)
            removals.add(key)
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clearAll) {
                data.clear()
                clearAll = false
            }
            for (key in removals) data.remove(key)
            removals.clear()
            data.putAll(pending)
            pending.clear()
        }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this
    }
    override fun getString(key: String, defValue: String?): String? =
        if (data.containsKey(key)) data[key] else defValue
    override fun edit(): SharedPreferences.Editor = FakeEditor()
    override fun getAll(): MutableMap<String, *> = data.toMutableMap()
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = defValue
    override fun getLong(key: String, defValue: Long): Long = defValue
    override fun getFloat(key: String, defValue: Float): Float = defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
    override fun contains(key: String): Boolean = data.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
}

// List-backed DAO fakes so the Room-to-Room snapshot copy is a real
// behavioral test on the JVM (no database needed): rows in, same rows out.
private class FakeMemoryDao : MemoryDao {
    val rows = mutableListOf<MemoryRow>()
    override fun listAll(): List<MemoryRow> = rows.toList()
    override fun searchFts(query: String): List<MemoryRow> = rows.toList()
    override fun insert(row: MemoryRow): Long {
        rows += row
        return row.pid
    }
    override fun deleteById(pid: Long) {
        rows.removeAll { it.pid == pid }
    }
    override fun markUsed(pid: Long, nowMs: Long) = Unit
}

private class FakeScheduleDao : ScheduleDao {
    val rows = mutableListOf<ScheduleRow>()
    override fun listAll(): List<ScheduleRow> = rows.toList()
    override fun insert(row: ScheduleRow): Long {
        rows += row
        return row.pid
    }
    override fun deleteById(pid: Long) {
        rows.removeAll { it.pid == pid }
    }
}

private class FakeScheduleRunDao : ScheduleRunDao {
    val rows = mutableListOf<ScheduleRunRow>()
    override fun insertIgnore(row: ScheduleRunRow): Long {
        rows += row
        return row.pid
    }
    override fun find(scheduleId: String, fireTime: Long): ScheduleRunRow? = null
    override fun listAll(): List<ScheduleRunRow> = rows.toList()
    override fun finish(pid: Long, status: String, error: String?, result: String?, durationMs: Long, provider: String?, planMs: Long, toolMs: Long, recallMs: Long) = Unit
    override fun findPid(scheduleId: String, fireTime: Long): Long? = null
    override fun reclaimStaleRunning(scheduleId: String, fireTime: Long, nowMs: Long, ttlMs: Long): Int = 0
}

private class FakePendingApprovalDao : PendingApprovalDao {
    val rows = mutableListOf<PendingApprovalRow>()
    override fun insert(row: PendingApprovalRow): Long {
        rows += row
        return row.pid
    }
    override fun findById(pid: Long): PendingApprovalRow? = rows.find { it.pid == pid }
    override fun listAll(): List<PendingApprovalRow> = rows.toList()
    override fun deleteById(pid: Long): Int = if (rows.removeAll { it.pid == pid }) 1 else 0
}

private class FakeInteractionDao : InteractionDao {
    val rows = mutableListOf<InteractionRow>()
    override fun insert(row: InteractionRow): Long {
        rows += row
        return row.pid
    }
    override fun listRecent(limit: Int): List<InteractionRow> = rows.sortedByDescending { it.timestampMs }.take(limit)
    override fun prune(keep: Int) = Unit
    override fun clearAll() {
        rows.clear()
    }
    override suspend fun listBySession(sessionId: String): List<InteractionRow> = rows.filter { it.sessionId == sessionId }
    override suspend fun deleteSession(sessionId: String) {
        rows.removeAll { it.sessionId == sessionId }
    }
}

private class FakeAppDb : AppDb() {
    val memoryDao = FakeMemoryDao()
    val scheduleDao = FakeScheduleDao()
    val runDao = FakeScheduleRunDao()
    val approvalDao = FakePendingApprovalDao()
    val interactionDao = FakeInteractionDao()
    override fun memories(): MemoryDao = memoryDao
    override fun schedules(): ScheduleDao = scheduleDao
    override fun runs(): ScheduleRunDao = runDao
    override fun approvals(): PendingApprovalDao = approvalDao
    override fun interactions(): InteractionDao = interactionDao
    override fun clearAllTables() = Unit
    override fun createInvalidationTracker(): InvalidationTracker = InvalidationTracker(this)
    override fun createOpenHelper(config: DatabaseConfiguration): SupportSQLiteOpenHelper {
        val handler = { _: Any, method: java.lang.reflect.Method, _: Array<out Any>? ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        }
        return java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SupportSQLiteOpenHelper::class.java),
            handler,
        ) as SupportSQLiteOpenHelper
    }
}
