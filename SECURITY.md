# Aurviz Security Notes

## API keys

- Debug builds may read LLM keys from `local.properties` (git-ignored, see
  `local.properties.sample`). Never commit real keys.
- Release builds compile with **empty** API-key `BuildConfig` fields. Release
  users enter Gemini/Groq keys in Settings; they are persisted in `TokenVault`
  and override `BuildConfig` per provider at call time.
- OAuth client ID/secret stay in `BuildConfig` from `local.properties`.

## Encrypted storage (TokenVault)

Gmail tokens and user-entered LLM keys live in one `EncryptedSharedPreferences`
file (`androidx.security:security-crypto:1.1.0`), backed by the Android Keystore
(`MasterKey` AES256-GCM; AES256-SIV key encryption + AES256-GCM value
encryption). Key values never enter Compose/UI state — Settings shows only
set/not-set flags and clears fields after saving.

## `security-crypto` deprecation status (checked 2026-11-23)

`androidx.security:security-crypto` reached stable **1.1.0** but Google
**deprecated every API it ships** (`EncryptedSharedPreferences`, `MasterKey`,
`EncryptedFile` included) in that release, with no further releases planned;
new Keystore work is splitting into separate `androidx.security` artifacts and
Google ships no official migration guide. The 1.1.0 implementation still works
and remains the encrypted-at-rest store for this app; the known Keystore
initialization crashes on some Android 9–10 devices (upstream issue 176215143)
are accepted as a fallback-to-reconnect risk. Revisit when Google publishes a
replacement path — do not downgrade to unencrypted storage in the meantime.
