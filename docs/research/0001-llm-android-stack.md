# LLM + Android Stack Research (AURVIZ)

- Date: 2026-11-22
- Scope: primary sources only (official docs, first-party API references). Each claim links its source.
- Note: model names, prices, and numeric limits rotate; re-verify numeric limits on the linked live pages before hard-coding them.

## 1. Gemini API via OpenAI-compatible endpoint

Base URL + auth:

- Base URL is `https://generativelanguage.googleapis.com/v1beta/openai/`; chat endpoint is `POST .../v1beta/openai/chat/completions` ([source](https://ai.google.dev/gemini-api/docs/openai)).
- Auth is a Gemini API key from Google AI Studio, sent as `api_key="GEMINI_API_KEY"` in SDK clients or `Authorization: Bearer $GEMINI_API_KEY` for REST ([source](https://ai.google.dev/gemini-api/docs/openai)).
- Drop-in usage: set the OpenAI client's `api_key` + `base_url` and pick a `model=` such as a Gemini Flash model ([source](https://ai.google.dev/gemini-api/docs/openai)).
- `GET .../v1beta/openai/models` lists models; `GET .../v1beta/openai/models/{id}` retrieves one ([source](https://ai.google.dev/gemini-api/docs/openai)).
- Gemini-only features (thinking config, cached content, grounding, safety settings) are passed via `extra_body.google.*` ([source](https://ai.google.dev/gemini-api/docs/openai)).

Tool/function calling:

- Function calling is supported through the OpenAI-compat layer with the standard `tools: [{type: "function", function: {name, description, parameters}}]` + `tool_choice: "auto"` shape ([source](https://ai.google.dev/gemini-api/docs/openai)).
- Streaming (`stream: true`) and structured output (`response_format` / `beta.chat.completions.parse`) are supported ([source](https://ai.google.dev/gemini-api/docs/openai)).

Free tier + limits:

- A Free tier exists: free input/output tokens on select models, AI Studio access; free-tier prompts may be used to improve Google products, paid-tier prompts are not ([source](https://ai.google.dev/gemini-api/docs/pricing)).
- Flash-class models (e.g. `gemini-3.8-flash`, `gemini-3.6-flash`) list "Free of charge" input/output on the pricing page; frontier models (Gemini 4 Carbon/Argon/Neon) are paid-only ([source](https://ai.google.dev/gemini-api/docs/pricing)).
- Rate limits are per project across RPM / TPM / RPD and reset daily at midnight Pacific; exact per-model numbers live on the AI Studio rate-limit page and change with usage tier ([source](https://ai.google.dev/gemini-api/docs/rate-limits)).
- New accounts start on Free; linking a billing account + prepay (min $5) moves to paid tiers with higher limits ([source](https://ai.google.dev/gemini-api/docs/billing)).

Android/Kotlin notes:

- The compat doc shows Python, TypeScript, and raw REST/cURL only — there is no first-party Kotlin SDK for this endpoint; on Android use any HTTP client (OkHttp/Retrofit/Ktor) against the REST endpoint with base URL + key ([source](https://ai.google.dev/gemini-api/docs/openai)).
- Implication for AURVIZ: one OpenAI-shaped HTTP client with BuildConfig-supplied `baseUrl` + key covers Gemini; keep keys out of source per Android API-key guidance (Gradle secrets plugin, Keystore) ([source](https://developer.android.com/privacy-and-security/security-tips)).

## 2. Groq OpenAI-compatible API (Llama/Meta models)

Base URL + auth:

- Base URL is `https://api.groq.com/openai/v1` with `api_key` = Groq API key from console ([source](https://console.groq.com/docs/openai)).
- Models list endpoint: `GET https://api.groq.com/openai/v1/models` ([source](https://console.groq.com/docs/models)).
- Currently hosted Llama-family production models include `llama-3.1-8b-instant` and `llama-3.3-70b-versatile` (currently listed as Enterprise / Contact Sales); also hosted: `openai/gpt-oss-20b`, `openai/gpt-oss-120b`, `qwen/qwen3.8-27b`, `meta-models/muse-spark-open` (preview/enterprise) — model availability rotates, verify on the models page ([source](https://console.groq.com/docs/models)).

Tool/function calling:

- Standard OpenAI `tools` array + `tool_calls` response + `role: "tool"` result messages with matching `tool_call_id` ([source](https://console.groq.com/docs/tool-use/overview)).
- All hosted models support tool use; parallel tool use supported on Llama 3.1/3.3 models but NOT on `gpt-oss-20b/120b` (single-tool-call loop only) ([source](https://console.groq.com/docs/tool-use/overview)).
- Unsupported OpenAI fields return 400: `logprobs`, `logit_bias`, `top_logprobs`, `messages[].name`, and `N` must equal 1; `temperature: 0` is coerced to `1e-8` ([source](https://console.groq.com/docs/openai)).

Free tier + limits:

- A Free tier exists alongside the pay-as-you-go Developer tier; downgrade/upgrade is self-serve and Developer unlocks higher token limits, Flex/Batch, spend limits ([source](https://console.groq.com/docs/billing-faqs)).
- Limits are enforced per organization on RPM/RPD/TPM/TPD (+ audio ASH/ASD, ITPM/OTPM); exact numbers are on the console Limits page; over-limit returns `429` with `retry-after` and `x-ratelimit-*` headers ([source](https://console.groq.com/docs/rate-limits)).

Android/Kotlin notes:

- Official SDKs are Python (`pip install groq`) and JS/TS (`groq-sdk`) only; community Dart/Flutter exists but is unverified — no official Kotlin SDK, so Android should use the OpenAI-compat REST endpoint via OkHttp/Retrofit ([source](https://console.groq.com/docs/libraries)).

## 3. DeepSeek OpenAI-compatible API

Base URL + auth:

- OpenAI-format base URL is `https://api.deepseek.com` (chat endpoint `POST /chat/completions`); Anthropic-format base is `https://api.deepseek.com/anthropic` ([source](https://api-docs.deepseek.com/)).
- Auth is a Bearer API key from the DeepSeek platform (`Authorization: Bearer ${DEEPSEEK_API_KEY}`) ([source](https://api-docs.deepseek.com/)).
- Current model names are `deepseek-flash` (served by DeepSeek-V4.1-Flash-1017) and `deepseek-v4-pro` (DeepSeek-V4-Pro-0813) — legacy `deepseek-v4-flash` names still accepted but retired; expect names to keep rotating ([source](https://api-docs.deepseek.com/)).

Tool/function calling:

- Tool Calls use the OpenAI `tools` function format via the Chat Completion API ([source](https://api-docs.deepseek.com/guides/tool_calls)).
- Supported in both thinking and non-thinking modes (disable thinking with `extra_body: {"thinking": {"type": "disabled"}}`); a `strict` beta mode (`base_url https://api.deepseek.com/beta`, `strict: true`, all object props `required`, `additionalProperties: false`) enforces schema adherence ([source](https://api-docs.deepseek.com/guides/tool_calls)).
- Chat Completions does NOT support inserting model-un-generated tool calls mid-conversation (use Anthropic/Responses APIs for that) ([source](https://api-docs.deepseek.com/guides/tool_calls)).

Free tier + limits:

- No free tier is documented — billing is pay-as-you-go per 1M tokens with peak/off-peak rates (off-peak = half price; peak 01:00–04:00 and 06:00–10:00 UTC Mon–Fri excl. CN holidays) ([source](https://api-docs.deepseek.com/quick_start/pricing)).
- Throttling is by per-account concurrency (currently `deepseek-flash` 2500, `deepseek-v4-pro` 500); excess returns HTTP 429; optional `user_id` isolates KV-cache/scheduling per end user ([source](https://api-docs.deepseek.com/quick_start/rate_limit)).
- Context length 1M tokens, max output up to 384K; Vision on Flash only; JSON output + tool calls on both models ([source](https://api-docs.deepseek.com/quick_start/pricing)).

Android/Kotlin notes:

- Same as Groq: OpenAI-compatible REST, no first-party Kotlin SDK — reuse the single AURVIZ OpenAI-shaped HTTP client, only `baseUrl`/model/key change.

## 4. Gmail API OAuth2 on Android

Scopes (least privilege first):

- For AURVIZ send-only: `https://www.googleapis.com/auth/gmail.send` (Sensitive — send on your behalf) is the narrowest send scope; `gmail.compose` (Restricted — manage drafts + send) and `gmail.modify` (Restricted — read/compose/send) are broader; avoid `https://mail.google.com/` (full mailbox incl. permanent delete) ([source](https://developers.google.com/gmail/api/auth/scopes)).
- Restricted scopes require restricted-scope verification + security assessment if server-stored; unverified apps show an "unverified app" screen ([source](https://developers.google.com/gmail/api/auth/scopes)).
- `users.messages.send` requires one of `gmail.send` / `gmail.compose` / `gmail.modify` / `mail.google.com` ([source](https://developers.google.com/gmail/api/reference/rest/v1/users.messages/send)).

Send endpoint + message format:

- `POST https://gmail.googleapis.com/gmail/v1/users/{userId}/messages/send` (`userId = "me"` for the authed user; upload URI variant for media uploads) ([source](https://developers.google.com/gmail/api/reference/rest/v1/users.messages/send)).
- Body is a `Message` resource whose `raw` field is an RFC 2822 MIME message encoded as base64URL; the official Java sample builds it with `MimeMessage` → `ByteArrayOutputStream` → `Base64.encodeBase64URLSafeString` → `message.setRaw(...)` → `service.users().messages().send("me", message).execute()` ([source](https://developers.google.com/gmail/api/guides/sending)).

Android OAuth2 + credential storage:

- Use the Android authorization guide flow (OAuth 2.0 for native/installed apps): authorization request to `https://accounts.google.com/o/oauth2/v2/auth` with PKCE (`code_verifier`/`code_challenge`, S256 recommended), loopback-IP redirect is DEPRECATED on mobile — use the Android-app redirect alternative; exchange code at `https://oauth2.googleapis.com/token`; refresh tokens valid until revoked; store tokens in secure long-lived storage ([source](https://developers.google.com/identity/protocols/oauth2/native-app)).
- For mobile, Google points at the Android authorization guide (`developer.android.com/identity/authorization`) rather than embedded WebViews/loopback ([source](https://developers.google.com/identity/protocols/oauth2/native-app)).
- Android security guidance: prefer Credential Manager for sign-in, `AccountManager` for multi-app cloud auth (verify caller via `checkSignatures`), otherwise `KeyStore`; never hard-code secrets — use the secrets Gradle plugin + Android Keystore, request minimal scopes, use short-lived tokens ([source](https://developer.android.com/privacy-and-security/security-tips)).
- Thread replies: match `Subject` headers and follow RFC 2822 `References`/`In-Reply-To` ([source](https://developers.google.com/gmail/api/guides/sending)).

## 5. Supermemory REST API

Base URL + auth:

- Base URL `https://api.supermemory.ai` (self-host: own instance URL, e.g. `http://localhost:6767`); every request carries `Authorization: Bearer sm_...` from the developer console ([source](https://supermemory.ai/docs/api-reference/overview.md)).
- Scoped (least-privilege) keys: `POST /auth/scoped-key` with `containerTag` (+ optional `containerTags`, `name`, `expiresInDays` 1–365, `rateLimitMax`); delete via `DELETE /auth/scoped-key/{KEY_ID}` ([source](https://supermemory.ai/docs/authentication.md)).

Endpoints (v5; namespace in path — legacy v3/v4 is deprecated, shutdown 2026-12-31):

- Add: `POST /ns/{namespace}/document` `{content, id?, metadata?, taskType?, dreaming?}` → `{id, status: "queued"}`; file upload variant `POST /ns/{namespace}/document/file` (multipart, 50 MB max); `taskType: "superrag"` = index-only at 5× cheaper/token vs default `"memory"` extraction; `dreaming: "instant"` processes immediately (else dynamic batching can leave fresh namespaces empty for minutes) ([source](https://supermemory.ai/docs/ingestion/add-memories.md)).
- Search: `POST /ns/{namespace}/search` `{query, searchMode: hybrid|memories|chunks (default hybrid), limit (default 10, max 100), threshold (default 0.3), rerank?, rewriteQuery?, filter?, include?}` — results carry either `memory` or `chunk` + `similarity` ([source](https://supermemory.ai/docs/recall/search.md)).
- Profile: `POST /ns/{namespace}/profile` (optional `{filter, buckets}`) → `{profile: {static[], dynamic[] ([YYYY-MM-DD]-prefixed), buckets{}}}` — no query param; pair with `search` for query-specific recall ([source](https://supermemory.ai/docs/recall/user-profiles.md)).
- Forget (exact): `DELETE /ns/{namespace}/memories` `{ids: 1–500}` → `{count, matches[{id, memory}], errors[]}` — HTTP success ≠ all IDs changed, check `errors` ([source](https://supermemory.ai/docs/recall/memory-operations.md)).
- Forget (semantic): `DELETE /ns/{namespace}/memories/semantic` `{query (1–2000 chars), dryRun (required)}` — always preview with `dryRun: true` first, then forget the returned IDs exactly (re-running apply can drift); max 100/batch, repeat preview→apply ([source](https://supermemory.ai/docs/recall/memory-operations.md)).
- No direct create/update memory routes: ingest documents (with stable `id`s) and memories follow; use `documents.update` for replacement ([source](https://supermemory.ai/docs/recall/memory-operations.md)).

Namespace (containerTag) rules:

- v5 `namespace` == v3/v4 `containerTag`; one namespace per request, scoped in URL (`/ns/{namespace}/...`), never in body; query-string params on add/search return 400 ([sources](https://supermemory.ai/docs/concepts/container-tags.md) and [migration note](https://supermemory.ai/docs/recall/search.md)).
- Rules: ≤100 chars, pattern `^[a-zA-Z0-9_:-]+$` (no spaces/slashes); auto-created on first write; each maps to its own vector index (strict isolation); convention: `user_{id}`, `project_{id}`, hierarchical `org:{o}:user:{u}` ([source](https://supermemory.ai/docs/concepts/container-tags.md)).
- Manage via `namespaces.list/get/update/delete`; delete with `?moveTo=` consolidates; deletes need org admin/owner ([source](https://supermemory.ai/docs/concepts/container-tags.md)).

## 6. Android: Room FTS + WorkManager periodic + AlarmManager exact + SpeechRecognizer

Room FTS:

- Annotate entity with `@Fts3`/`@Fts4`/`@Fts5` (+ `@Entity`); FTS tables must use `@PrimaryKey @ColumnInfo(name = "rowid") val id: Long` (INTEGER `rowid`) ([source](https://developer.android.com/training/data-storage/room/defining-data)).
- Tokenizer via `FtsOptions` (`TOKENIZER_SIMPLE`, `TOKENIZER_PORTER`, `TOKENIZER_UNICODE61`); `@Entity(indices = ...)` / unique `@Index` for non-FTS columns ([source](https://developer.android.com/training/data-storage/room/defining-data)).

WorkManager periodic:

- `PeriodicWorkRequestBuilder<Worker>(repeatInterval, unit)`; **minimum repeat interval 15 min** (same as JobScheduler); optional flex window at end of interval; constraints (network/charging/idle/storage) gate runs — a missed window delays/skips that run ([source](https://developer.android.com/topic/libraries/architecture/workmanager/how-to/define-work)).
- Scheduled work persists in an internal SQLite DB and is **rescheduled across reboots by the system**; use `enqueueUniquePeriodicWork` + `ExistingPeriodicWorkPolicy` for idempotent schedules — no manual BOOT_COMPLETED reschedule needed for WorkManager itself ([source](https://developer.android.com/topic/libraries/architecture/workmanager/basics)).
- Do NOT use WorkManager for exact timing: it uses JobScheduler internally and **does not run in Doze** ([source](https://developer.android.com/training/monitoring-device-state/doze-standby)).

AlarmManager exact:

- `setExact()` / `setExactAndAllowWhileIdle()` / `setAlarmClock()`; from API 19 `set()` is inexact; `setAndAllowWhileIdle`/`setExactAndAllowWhileIdle` fire in Doze but **≤ ~1×/9 min per app** ([sources](https://developer.android.com/reference/android/app/AlarmManager) and [Doze guide](https://developer.android.com/training/monitoring-device-state/doze-standby)).
- Apps targeting API 31+ need `SCHEDULE_EXACT_ALARM` manifest permission + user grant in Special app access (`canScheduleExactAlarms()`); use exact alarms for user-facing features only; prefer inexact/`WorkManager` otherwise ([source](https://developer.android.com/about/versions/12/behavior-changes-12#exact-alarm-permission)).
- Alarms (except `setAlarmClock`) are deferred to the next Doze maintenance window; use FCM high-priority for user-visible nudges ([source](https://developer.android.com/training/monitoring-device-state/doze-standby)).
- For AURVIZ: exact alarms only for user-visible reminders; everything deferrable goes to WorkManager; battery-optimization exemption requires an acceptable core-function use case ([source](https://developer.android.com/training/monitoring-device-state/doze-standby)).

SpeechRecognizer:

- Entry: `createSpeechRecognizer(context)` (+ `createOnDeviceSpeechRecognizer` API 31+, guarded by `isOnDeviceRecognitionAvailable`); always `setRecognitionListener` first, call from main thread, `destroy()` when done; needs `RECORD_AUDIO`; API 30+ requires `<queries>` manifest entry for `RecognitionService` ([source](https://developer.android.com/reference/android/speech/SpeechRecognizer)).
- Results via `RESULTS_RECOGNITION` (`ArrayList<String>`, first = most likely) + `CONFIDENCE_SCORES`; errors include `ERROR_NO_MATCH/ERROR_SPEECH_TIMEOUT/ERROR_RECOGNIZER_BUSY/ERROR_NETWORK*` ([source](https://developer.android.com/reference/android/speech/SpeechRecognizer)).
- Streams audio to remote servers — **not for continuous recognition** (battery/bandwidth); prefer on-device or chunked utterances for an agent loop ([source](https://developer.android.com/reference/android/speech/SpeechRecognizer)).

## Out of scope note

- WebView automation was named in the brief's intro but had no numbered topic; it was not researched here. If needed, start from the Android WebView security guidance (same source as §4's security-tips link) plus the `WebView`/`addJavascriptInterface` reference docs.
