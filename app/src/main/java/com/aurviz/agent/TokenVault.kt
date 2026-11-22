package com.aurviz.agent

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

// H4: Gmail tokens live in encrypted storage, not plain memory.
// The phone encrypts them with its own key (AES256) — a reboot keeps
// the login, and no other app (or a curious backup) can read them.
// TokenStore itself stays plain + unit-tested; this vault only moves
// its three fields to and from the encrypted file.

class TokenVault(context: Context) {
    private val prefs =
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "tokens",
            MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
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
}
