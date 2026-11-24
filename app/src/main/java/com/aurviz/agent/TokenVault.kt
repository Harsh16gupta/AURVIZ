package com.aurviz.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

// H4: Gmail tokens live in encrypted storage, not plain memory.
// The phone encrypts them with its own key (AES256) — a reboot keeps
// the login, and no other app (or a curious backup) can read them.
// TokenStore itself stays plain + unit-tested; this vault only moves
// its three fields to and from the encrypted file.

// Ticket 36: user-entered LLM keys (Gemini, DeepSeek, Meta — the ADR-0004 set) live
// in the same encrypted file. Release APKs carry no embedded keys
// (BuildConfig key fields are empty in release builds), so these vault
// entries are the only key source on a release install.
data class LlmKeys(
    val geminiKey: String = "",
    val deepseekKey: String = "",
    val metaKey: String = "",
)

class TokenVault(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "tokens",
            MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ),
    )

    fun loadInto(tokens: TokenStore) {
        tokens.gmailAccessToken = prefs.getString("access", null)
        tokens.gmailRefreshToken = prefs.getString("refresh", null)
        tokens.gmailAccount = prefs.getString("account", null)
    }

    fun saveFrom(tokens: TokenStore) {
        prefs.edit()
            .putString("access", tokens.gmailAccessToken)
            .putString("refresh", tokens.gmailRefreshToken)
            .putString("account", tokens.gmailAccount)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    // Ticket 36 SPEC fix: the Gmail-revoke path must remove ONLY the three
    // Gmail fields. clear() wipes the whole prefs file incl. user-entered
    // LLM keys — revokeGmail() must call this, never clear().
    fun clearGmail() {
        prefs.edit()
            .remove("access")
            .remove("refresh")
            .remove("account")
            .apply()
    }

    fun saveLlmKeys(keys: LlmKeys) {
        prefs.edit()
            .putString("llm_gemini_key", keys.geminiKey)
            .putString("llm_deepseek_key", keys.deepseekKey)
            .putString("llm_meta_key", keys.metaKey)
            .apply()
    }

    fun loadLlmKeys(): LlmKeys =
        LlmKeys(
            geminiKey = prefs.getString("llm_gemini_key", null).orEmpty(),
            deepseekKey = prefs.getString("llm_deepseek_key", null).orEmpty(),
            metaKey = prefs.getString("llm_meta_key", null).orEmpty(),
        )

    fun clearLlmKeys() {
        prefs.edit()
            .remove("llm_gemini_key")
            .remove("llm_deepseek_key")
            .remove("llm_meta_key")
            // Drop the legacy Groq entry for users upgrading from older builds.
            .remove("llm_groq_key")
            .apply()
    }
}
