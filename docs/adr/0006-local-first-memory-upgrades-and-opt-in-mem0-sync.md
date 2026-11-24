# Local-first Memory upgrades and opt-in Mem0 Sync Adapter

Status: accepted 2026-11-24 — amends ADR-0001 (Room-only Memory remains primary; adds source-moment provenance, SQLCipher at-rest encryption, on-device Mem0-style fact reconciliation, and an opt-in Mem0 sync decorator verified against Mem0 Platform v1/v3 & self-hosted OSS REST contracts).

## Context

ADR-0001 established Room (`RoomMemoryStore` over `aurviz.db` with `memories` + FTS4 `memoriesFts`) as AURVIZ's primary on-device `MemoryStore`, achieving ~1.3ms local recall with offline prefix-term FTS fallback and zero mandatory third-party cloud dependency.

As AURVIZ evolves toward richer long-term personalization and enterprise deployment, four concrete architectural gaps remain in the v6 `Memory` subsystem:

1. **Flat provenance (`source` only):** `Memory` rows only record `"you told me"` or `"auto-saved"`. They lack structured source-moment provenance (`originId`, `originSnippet`, `originTimestamp`) linking a remembered fact back to the specific `Interaction`, `Session`, or moment that produced it.
2. **Plaintext database at rest:** While OAuth and LLM keys live in `TokenVault` (`EncryptedSharedPreferences`, AES-256-GCM) and `allowBackup="false"` blocks cloud extraction, `aurviz.db` itself is opened as unencrypted SQLite on disk.
3. **Exact-match-only deduplication:** `rememberFact` only deduplicates when `text.trim().lowercase()` and `source` are identical. Contradictory or updated facts (e.g., `"my boss is Priya"` followed later by `"my boss is now Rahul"`) accumulate as competing rows instead of reconciling via an `ADD / UPDATE / NOOP (NONE)` lifecycle.
4. **No cross-device or private-cloud sync seam:** Enterprise and multi-device users need optional synchronization with self-hosted or cloud Mem0 instances without sacrificing ~1.3ms local reads, offline availability, or privacy by default.

## Decision

We upgrade the `Memory` subsystem through four backward-compatible, vertical layers while keeping `RoomMemoryStore` as the default, local-first engine:

1. **Room Remains Primary (~1.3ms local recall):**
   `RoomMemoryStore` and `Memory.kt`'s pure ranking engine (keyword overlap → `importance` → recency `max(lastUsed, createdAt)` → insertion order, with offline SQLite FTS4 `memoriesFts MATCH` fallback) remain the sole primary read path. No network call ever sits on the synchronous recall path.

2. **Source-Moment Provenance (Additive Room Migration v6 → v7):**
   We extend `Memory`, `MemoryRow`, `MemoryStore.save`, `rememberFact`, and `restoreMemoryRow` with three trailing defaulted nullable fields: `originId: String? = null`, `originSnippet: String? = null`, and `originTimestamp: Long? = null`. `MIGRATION_6_7` adds these three nullable columns to `memories` without rebuilding `memoriesFts` (which indexes `text` only). Default parameters keep all existing callers and tests source-compatible.

3. **At-Rest Database Encryption with SQLCipher:**
   We encrypt `aurviz.db` at rest using SQLCipher (`net.zetetic:sqlcipher-android` + `androidx.sqlite:sqlite-ktx`). `TokenVault` generates and persists a 256-bit (32-byte) random passphrase via `SecureRandom` (Base64-encoded in `EncryptedSharedPreferences` under `"db_passphrase_b64"`, preserved across `clearGmail()` and `clearLlmKeys()`). A centralized `configureEncryptedRoom(context, tokenVault)` helper checks any pre-existing `aurviz.db` file header for the 16-byte SQLite plaintext magic header (`"SQLite format 3\u0000"`) and performs a safe, atomic one-time `sqlcipher_export` migration before opening Room with `SupportOpenHelperFactory(passphraseBytes)`.

4. **On-Device Mem0-Style Fact Reconciliation (`ADD / UPDATE / NOOP`):**
   We bring Mem0's core fact lifecycle (`DEFAULT_UPDATE_MEMORY_PROMPT` in `mem0/configs/prompts.py`) on-device via `MemoryReconciler`. Before writing a fact, `MemoryReconciler` queries top-K candidate rows from `MemoryStore` (combining `searchFts(toFtsQuery(fact))` and `recallFor(store, fact)`), and asks `ChatLlmProvider` (when online and configured) to return a structured JSON decision. The parser accepts both our single-decision envelope (`{"action":"ADD"|"UPDATE"|"NOOP"|"NONE", "targetId":"...", "text":"..."}`) and Mem0's canonical `DEFAULT_UPDATE_MEMORY_PROMPT` envelope (`{"memory":[{"id":"...","text":"...","event":"ADD"|"UPDATE"|"NONE","old_memory":"..."}]}`):
   - `ADD`: insert a new `Memory` row.
   - `UPDATE(targetId, text)`: supersede an existing candidate row while preserving its accumulated ranking metadata (`useCount`, `max(importance)`, earliest `createdAt`, refreshed `lastUsed`, and latest provenance). To respect the prompt-injection poison guard, an untrusted `"auto-saved"` write is never allowed to overwrite or delete a trusted `"you told me"` row (downgrading to `ADD` with `source = "auto-saved"`).
   - `NOOP(targetId)` (or Mem0's `"NONE"`): fact is already captured; return the existing `Memory` without creating a duplicate.
   When offline, unconfigured, or on any LLM/JSON error, `MemoryReconciler` fails open to `rememberFact`'s deterministic case- and whitespace-insensitive exact-match deduplication.

5. **Opt-In Mem0 Sync Adapter (`SyncingMemoryStore` Decorator):**
   We introduce `SyncingMemoryStore(local: MemoryStore, remote: Mem0Client?, ...)` implementing `MemoryStore`.
   - **OFF by default:** Controlled by `Mem0SyncConfig(enabled: Boolean = false, baseUrl: String = DEFAULT_MEM0_BASE_URL, apiKey: String = "", userId: String = "aurviz_user")` stored in `TokenVault`.
   - **Cloud (`api.mem0.ai`) & Self-Hosted OSS (`server/main.py`) Protocol Compatibility:**
     - **Authentication headers:** Mem0 Platform Cloud requires `Authorization: Token <apiKey>`, whereas the self-hosted Mem0 FastAPI server (`server/auth.py`) authenticates via `X-API-Key: <apiKey>` (or `Authorization: Bearer <jwt>`). `HttpMem0Client` sends `X-API-Key: <apiKey>`, `Accept: application/json`, and `Content-Type: application/json` on every request, and uses `Authorization: Token <apiKey>` for `api.mem0.ai` (or `Authorization: Bearer <apiKey>` for custom self-hosted base URLs) so both cloud and self-hosted servers authenticate seamlessly.
     - **REST endpoints & request payloads:**
       - **Create (`addMemory`):** Platform Cloud uses `POST /v1/memories/` (or `/v3/memories/add/`) while self-hosted OSS uses `POST /memories`. Request payload sends `user_id`, `messages: [{"role": "user", "content": memory.text}]`, `infer: false` (since `MemoryReconciler` has already extracted and reconciled the atomic fact on-device), and `metadata` (`local_id`, `source`, `category`, `importance`, `created_at`, plus optional `origin_id`, `origin_snippet`, `origin_timestamp`). Both return `{"results": [{"id": "<remote_uuid>", "data": {"memory": "..."}, "event": "ADD"}]}`.
       - **Local ↔ Remote ID mapping:** Mem0 assigns its own server-side UUID (`results[0].id`, e.g. `"27777b1c-ff8d-4779-bf45-158a80033367"`) rather than using `room_<pid>` as the primary URL segment. `TokenVault` (or an injected `Mem0IdMap`) persists the `localId ("room_<pid>") -> remoteId ("<uuid>")` mapping so subsequent updates (`PUT /v1/memories/{remoteId}/` or `PUT /memories/{remoteId}` with `{"text": "...", "metadata": {...}}`) and deletes (`DELETE /v1/memories/{remoteId}/` or `DELETE /memories/{remoteId}`) target the exact Mem0 UUID.
   - **Local-first reads:** `list()`, `searchFts()`, and `markUsed()` always execute directly against `local` (`RoomMemoryStore`) with zero network latency.
   - **Best-effort non-blocking write/delete mirroring:** `save()` and `delete()` commit to `local` immediately and return the local `Memory`; when `remote` is configured and enabled, mutations mirror asynchronously (fire-and-forget, catching all exceptions) so network errors never block the UI or break offline mode.

## Security & Boundary Invariants Preserved

- **Untrusted-run poison guard (`sanitizeMemoryCallForUntrustedRun`):** Any `save_memory` call after an external read (`search_emails`, `read_email`, `open_url`, `get_page_text`, `click`, `scroll`, `extract`) is forced to `source = "auto-saved"` before reaching `ToolRegistry.executeGated`, `MemoryReconciler`, or `MemoryStore`.
- **Scheduled-run isolation:** `save_memory` remains excluded from `SCHEDULED_RUN_ALLOWLIST`; unattended `Schedule` runs cannot mutate `Memory` and instead record a `PendingApproval` row.
- **Delimited prompt boundary (ADR-0005):** Recalled `Memory` items are rendered exclusively inside `[memories] ... [/memories]` in the system prompt via `renderMemorySection`, never concatenated into user messages.
- **No secrets in code:** SQLCipher passphrases and optional Mem0 credentials live exclusively in `TokenVault` (`EncryptedSharedPreferences`), never in source code, `BuildConfig`, or UI state.
