package com.aurviz.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

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

// ADR-0006: 256-bit (32-byte) SQLCipher database passphrase persisted in
// EncryptedSharedPreferences under DB_PASSPHRASE_PREF_KEY.
internal const val DB_PASSPHRASE_PREF_KEY = "db_passphrase_b64"
internal const val DB_PASSPHRASE_BYTES = 32

// ADR-0006: Opt-in Mem0 sync config. OFF by default — Room stays primary.
const val DEFAULT_MEM0_BASE_URL = "https://api.mem0.ai"

data class Mem0SyncConfig(
    val enabled: Boolean = false,
    val baseUrl: String = DEFAULT_MEM0_BASE_URL,
    val apiKey: String = "",
    val userId: String = "aurviz_user",
) {
    val isActive: Boolean
        get() = enabled && baseUrl.isNotBlank() && apiKey.isNotBlank()
}

internal fun defaultSecureRandomBytes(size: Int): ByteArray =
    ByteArray(size).also { SecureRandom().nextBytes(it) }

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

    // ADR-0006: 256-bit (32-byte) SQLCipher database passphrase persisted in
    // EncryptedSharedPreferences. Generated once via SecureRandom and reused
    // on every launch; never wiped by clearGmail() or clearLlmKeys().
    @Synchronized
    fun getOrCreateDbPassphrase(
        randomBytes: (Int) -> ByteArray = ::defaultSecureRandomBytes,
    ): ByteArray {
        val existing = prefs.getString(DB_PASSPHRASE_PREF_KEY, null)
        if (!existing.isNullOrBlank()) {
            val decoded = try {
                java.util.Base64.getUrlDecoder().decode(existing)
            } catch (_: IllegalArgumentException) {
                null
            }
            if (decoded != null && decoded.size == DB_PASSPHRASE_BYTES) {
                return decoded
            }
        }
        val fresh = randomBytes(DB_PASSPHRASE_BYTES)
        require(fresh.size == DB_PASSPHRASE_BYTES) { "DB passphrase must be $DB_PASSPHRASE_BYTES bytes" }
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(fresh)
        prefs.edit().putString(DB_PASSPHRASE_PREF_KEY, encoded).apply()
        return fresh.copyOf()
    }

    fun saveMem0SyncConfig(config: Mem0SyncConfig) {
        prefs.edit()
            .putString("mem0_enabled", if (config.enabled) "true" else "false")
            .putString("mem0_base_url", config.baseUrl.trim().ifBlank { DEFAULT_MEM0_BASE_URL })
            .putString("mem0_api_key", config.apiKey.trim())
            .putString("mem0_user_id", config.userId.trim().ifBlank { "aurviz_user" })
            .apply()
    }

    fun loadMem0SyncConfig(): Mem0SyncConfig =
        Mem0SyncConfig(
            enabled = prefs.getString("mem0_enabled", "false") == "true",
            baseUrl = prefs.getString("mem0_base_url", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MEM0_BASE_URL,
            apiKey = prefs.getString("mem0_api_key", null).orEmpty(),
            userId = prefs.getString("mem0_user_id", null)?.takeIf { it.isNotBlank() } ?: "aurviz_user",
        )

    fun clearMem0SyncConfig() {
        prefs.edit()
            .remove("mem0_enabled")
            .remove("mem0_base_url")
            .remove("mem0_api_key")
            .remove("mem0_user_id")
            .apply()
    }

    // Maps local Room id ("room_12") -> remote Mem0 UUID ("27777b1c-...")
    // so subsequent PUT/DELETE calls target the exact server-assigned UUID.
    fun saveMem0RemoteId(localId: String, remoteId: String) {
        if (localId.isBlank() || remoteId.isBlank()) return
        prefs.edit().putString("mem0_id_$localId", remoteId.trim()).apply()
    }

    fun loadMem0RemoteId(localId: String): String? =
        prefs.getString("mem0_id_$localId", null)?.takeIf { it.isNotBlank() }

    fun removeMem0RemoteId(localId: String) {
        if (localId.isBlank()) return
        prefs.edit().remove("mem0_id_$localId").apply()
    }
}
