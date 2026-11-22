# AURVIZ — on-device personal agent (Android)

Voice/text commands → agent loop → tools (Gmail, web, memory, schedules).
Per-sensitive-call Confirmation gate; scheduled runs are read-only and hold
sends as in-app approvals. Room-only memory; no cloud memory provider.

## Setup

1. Install Android Studio (or the SDK + JDK 17+). Set:
   `export ANDROID_HOME=$HOME/Library/Android/sdk`
2. `cp local.properties.sample local.properties` and fill in your own keys
   (debug builds only — **never commit `local.properties`**; it is git-ignored).
3. Google OAuth client (Gmail): Google Cloud Console → APIs & Services →
   Credentials → Create **OAuth client ID** → application type **Desktop app**.
   Enable the Gmail API. Add the two IDs to `local.properties`:
   `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET`.
   - The app uses PKCE (S256) with a loopback redirect; sign-in runs from
     Settings → Gmail sign-in finishes in the browser tab, then paste back.
   - Scopes are least-privilege: `gmail.send` + `gmail.readonly`.
   - **Testing-status note:** an OAuth app in Testing shows the unverified-app
     screen and its refresh tokens expire after ~7 days — reconnect in Settings.
4. `./gradlew :app:assembleDebug`, install, open Settings first (Gmail +
   LLM keys + battery/alarms prompts).

## `local.properties.sample`

```properties
LLM_DEFAULT_PROVIDER=gemini
GEMINI_API_KEY=
GEMINI_MODEL=gemini-2.5-flash
GROQ_API_KEY=
GROQ_MODEL=llama-3.1-8b-instant
GOOGLE_OAUTH_CLIENT_ID=
GOOGLE_OAUTH_CLIENT_SECRET=
```

Debug builds compile these keys into `BuildConfig`. **Release builds always
compile with empty API-key fields** — release users paste Gemini/Groq keys in
Settings, stored encrypted in `TokenVault` on-device (ticket 36).

## Build

- Debug: `./gradlew :app:assembleDebug`
- Release (empty keys, verified ticket 37): `./gradlew :app:assembleRelease`
  → `app/build/outputs/apk/release/app-release-unsigned.apk` (~6.7 MB).
  Release `BuildConfig` fields `GEMINI_API_KEY`, `GROQ_API_KEY`,
  `DEEPSEEK_API_KEY` are all `""`; OAuth IDs are `""` unless provided.

## Test

- `export ANDROID_HOME=$HOME/Library/Android/sdk`
- `./gradlew :app:testDebugUnitTest` — 294 tests, green on ticket 37
  (26 test classes + `PerfTimingsTest` benchmark).
- Device proof that still needs a human pass: `docs/SCHEDULING_TEST.md`;
  timing medians + optimization: `docs/PERF_TIMINGS.md`.

## Docs

- `ARCHITECTURE.md` — Mermaid data/control flow + the six system topics.
- `docs/adr/` — decisions 0001–0005 (Room-only, exact-alarm scheduler,
  per-call confirmation, Gemini+Groq, hand-rolled loop).
- `SECURITY.md`, `TEST_PLAN.md`, `docs/VALIDATION_LOG.md`, `GLOSSARY.md`.

## Incomplete features (honest list)

- **#32 memory embeddings: cut.** Retrieval is keyword + importance/recency
  boosts with an offline FTS fallback (`Memory.kt`). No embedding column,
  no cosine ranking, no embedding cache.
- **DeepSeek fallback still wired in code** (`ProviderPicker.kt`,
  `LlmProvider.kt`) although ADR-0004 declares Groq the single fallback —
  recorded as drift in `docs/VALIDATION_LOG.md`, not removed in this ticket.
- **No TTS replies; no per-schedule run-now button** (P2, cut per gaps §5).
- **No provider picker UI** — provider choice is config-only per ADR-0004.
- **On-device scheduling proof + profiler screenshot** need a human device
  pass (`docs/SCHEDULING_TEST.md`, `docs/PERF_TIMINGS.md`).
