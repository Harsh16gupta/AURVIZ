package com.aurviz.agent

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

// Ticket 36 (23): user-entered LLM keys live in TokenVault (EncryptedSharedPreferences
// in production); release APKs carry no embedded keys, so blank-everywhere must stay
// blank. TokenVault takes the SharedPreferences as a seam: production passes the
// encrypted store, tests pass this in-memory fake — same class, same key names.
class LlmKeysTest {

    private fun vault() = TokenVault(FakePrefs())

    @Test
    fun userKeysRoundTripThroughVault() {
        val vault = vault()
        vault.saveLlmKeys(LlmKeys(geminiKey = "user-gemini", groqKey = "user-groq", deepseekKey = "user-deepseek", metaKey = "user-meta"))
        assertEquals(LlmKeys(geminiKey = "user-gemini", groqKey = "user-groq", deepseekKey = "user-deepseek", metaKey = "user-meta"), vault.loadLlmKeys())
    }

    @Test
    fun emptyVaultLoadsBlankKeys() {
        assertEquals(LlmKeys(), vault().loadLlmKeys())
    }

    @Test
    fun savingAgainReplacesOldKeys() {
        val vault = vault()
        vault.saveLlmKeys(LlmKeys(geminiKey = "old", groqKey = "old"))
        vault.saveLlmKeys(LlmKeys(geminiKey = "new", groqKey = ""))
        assertEquals(LlmKeys(geminiKey = "new", groqKey = ""), vault.loadLlmKeys())
    }

    @Test
    fun clearKeysRemovesOnlyLlmKeys() {
        val vault = vault()
        val tokens = TokenStore()
        tokens.gmailAccessToken = "gmail-token"
        vault.saveFrom(tokens)
        vault.saveLlmKeys(LlmKeys(geminiKey = "user-gemini", groqKey = "user-groq"))
        vault.clearLlmKeys()
        assertEquals(LlmKeys(), vault.loadLlmKeys())
        val reloaded = TokenStore()
        vault.loadInto(reloaded)
        assertEquals("gmail-token", reloaded.gmailAccessToken)
    }

    @Test
    fun revokeGmailPathKeepsLlmKeys() {
        // Ticket 36 SPEC breach: revokeGmail() called vault.clear(), wiping
        // the whole prefs file incl. user-entered LLM keys. The revoke path
        // must remove only the three Gmail fields.
        val vault = vault()
        val tokens = TokenStore()
        tokens.gmailAccessToken = "gmail-access"
        tokens.gmailRefreshToken = "gmail-refresh"
        tokens.gmailAccount = "user@example.com"
        vault.saveFrom(tokens)
        vault.saveLlmKeys(LlmKeys(geminiKey = "user-gemini", groqKey = "user-groq"))
        vault.clearGmail()
        assertEquals(LlmKeys(geminiKey = "user-gemini", groqKey = "user-groq"), vault.loadLlmKeys())
        val reloaded = TokenStore()
        vault.loadInto(reloaded)
        assertEquals(null, reloaded.gmailAccessToken)
        assertEquals(null, reloaded.gmailRefreshToken)
        assertEquals(null, reloaded.gmailAccount)
    }

    @Test
    fun userKeysOverrideBuildConfigKeys() {
        val base =
            ProviderSettings(
                geminiKey = "build-gemini",
                geminiModel = "gemini-2.5-flash",
                groqKey = "build-groq",
                groqModel = "llama-3.1-8b-instant",
            )
        val effective = effectiveSettings(base, LlmKeys(geminiKey = "user-gemini", groqKey = ""))
        assertEquals("user-gemini", effective.geminiKey)
        assertEquals("build-groq", effective.groqKey)
        assertEquals("gemini-2.5-flash", effective.geminiModel)
    }

    @Test
    fun blankUserKeysFallBackToBuildConfig() {
        val base = ProviderSettings(geminiKey = "build-gemini", groqKey = "build-groq")
        val effective = effectiveSettings(base, LlmKeys())
        assertEquals("build-gemini", effective.geminiKey)
        assertEquals("build-groq", effective.groqKey)
    }

    @Test
    fun releaseWithNoKeysStaysKeyless() {
        // Release BuildConfig fields are empty strings; with no user keys entered
        // the effective settings must stay blank so planWithFallback throws
        // "No provider with API key" instead of calling with a baked-in key.
        val effective = effectiveSettings(ProviderSettings(), LlmKeys())
        assertEquals("", effective.geminiKey)
        assertEquals("", effective.groqKey)
    }
}

// In-memory SharedPreferences: same interface production's encrypted store
// implements, so the vault logic under test is identical.
private class FakePrefs : SharedPreferences {
    private val data = mutableMapOf<String, String?>()
    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, String?>()
        private var clearAll = false
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }
        override fun remove(key: String): SharedPreferences.Editor {
            pending[key] = null
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
