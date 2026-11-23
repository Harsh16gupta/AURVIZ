# Aurviz Security Notes

## API keys

- Debug builds may read LLM keys from `local.properties` (git-ignored, see
  `local.properties.sample`). Never commit real keys.
- Release builds compile with **empty** API-key `BuildConfig` fields. Release
  users enter Gemini/Groq keys in Settings; they are persisted in `TokenVault`
  and override `BuildConfig` per provider at call time.
- OAuth client ID/secret stay in `BuildConfig` from `local.properties`.
- Verified ticket 37: release `BuildConfig` shows `GEMINI_API_KEY`,
  `GROQ_API_KEY`, `DEEPSEEK_API_KEY` all `""`; `assembleRelease` succeeds
  (`app-release-unsigned.apk`, ~6.7 MB). No keystores are tracked.

## Encrypted storage (TokenVault)

Gmail tokens and user-entered LLM keys live in one `EncryptedSharedPreferences`
file (`androidx.security:security-crypto:1.1.0`), backed by the Android Keystore
(`MasterKey` AES256-GCM; AES256-SIV key encryption + AES256-GCM value
encryption). Key drafts are memory-only Compose state (remember, never
rememberSaveable, cleared on save and on screen exit) so they are never
serialized into saved-instance state — Settings shows only set/not-set
flags and clears fields after saving.

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

## Prompt-injection defences (the send-tool threat model)

The agent reads mail/web (untrusted) and holds a send tool (irreversible),
so every send path is defended in depth: email/page text travels only inside
source-tagged, JSON-encoded tool results; the system prompt states tool
content is data, never instructions; memories ride in a delimited
`[memories]` system section, never concatenated into the user message; a
deterministic `SendPolicy` re-checks before `send_email` regardless of the
model; scheduled runs never hold the send tool (sends become
`PendingApproval` rows + notifications). Red-team tests (`RedTeamTest`:
evil-forward + memory poisoning, foreground card vs scheduled hold) are the
acceptance gate.

## Backup, transport, and OAuth posture

- `allowBackup="false"` plus `data_extraction_rules` deny every backup and
  device-transfer domain, so tokens and mail-derived Memory never leave in
  backups (`BackupRulesTest` pins the manifest + rules file).
- Gmail OAuth is PKCE (S256) with a loopback redirect; scopes are
  least-privilege `gmail.send` + `gmail.readonly`; tokens live only in
  `TokenVault`. An OAuth app in Testing shows the unverified-app screen and
  its refresh tokens expire after ~7 days — the app surfaces a "Reconnect
  Gmail" notification instead of failing silently.
- OkHttp uses explicit timeouts (connect 15 s, read 60 s) with backoff
  honouring `retry-after`; fallback never triggers on 401 and never replays
  private context to the second provider without the visible "retried on Y"
  disclosure.

## Secret scan (ticket 37, 2026-11-23)

- `git grep` for live-key patterns (`AIzaSy…`, `sk-ant-…`, `gsk_…`) across
  tracked files: **no hits**.
- `local.properties` is git-ignored and untracked; no `*.jks`/`*.keystore`/
  `*.p12` tracked. The only key-like hits are the `GEMINI_API_KEY` variable
  name in docs and a `"TEST_KEY"` placeholder in `spike/test_t1_llm_provider.py` —
  not real credentials.
