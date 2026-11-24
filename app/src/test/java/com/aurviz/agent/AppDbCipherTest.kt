package com.aurviz.agent

import android.content.SharedPreferences
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket #114 (ADR-0006): aurviz.db at-rest SQLCipher encryption. TokenVault
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
        val viewModel = File(moduleDir(), "src/main/java/com/aurviz/agent/ui/AgentViewModel.kt").readText()
        val scheduleWork = File(moduleDir(), "src/main/java/com/aurviz/agent/ScheduleWork.kt").readText()
        val proguard = File(moduleDir(), "proguard-rules.pro").readText()
        // The plaintext builder chains must stay intact (test pins).
        assertTrue(viewModel.contains("Room.databaseBuilder(application, AppDb::class.java, \"aurviz.db\")"))
        assertTrue(scheduleWork.contains("Room.databaseBuilder(applicationContext, AppDb::class.java, \"aurviz.db\")"))
        assertTrue(viewModel.contains(".addMigrations(*AppMigrations)"))
        assertTrue(scheduleWork.contains(".addMigrations(*AppMigrations)"))
        // Every open site chains the SQLCipher helper.
        assertTrue(viewModel.contains(".configureEncryptedRoom("))
        val scheduleWorkSites = scheduleWork.split(".configureEncryptedRoom(").size - 1
        assertEquals("ScheduleWorker + RescheduleWorker must both wire SQLCipher", 2, scheduleWorkSites)
        // Release R8 must keep SQLCipher's JNI entry points.
        assertTrue(proguard.contains("-keep class net.zetetic.database.**"))
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
