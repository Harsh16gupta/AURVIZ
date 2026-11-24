# AURVIZ — on-device personal agent (Android)

Voice/text commands → agent loop → tools (Gmail, web, memory, schedules).
Per-sensitive-call Confirmation gate; scheduled runs are read-only and hold
sends as in-app approvals. Room-only memory; no cloud memory provider.

## Compose UI (Midnight Dark / Daylight Light)

6 screens plus a `SideDrawer`, no new Gradle dependencies:

- **Onboarding** — first-run intro with large touch targets and hairline
  cards, then into the app.
- **Command** — scrollable `Interaction` feed (one record per past
  command: prompt, recalled-Memory chips, Plan steps, response, emails,
  web preview, retry notice, settled Confirmation receipts), live
  ticking `ThinkingIndicator`, working Stop/Cancel, unified
  `ConfirmationCard` with inline editable email fields, AI provider
  sheet (Auto/Default, deepseek, meta), suggestion cards,
  1-tap Markdown `Session` transcript export/share.
- **Browser** — Back/Forward/Reload, 2dp load progress, full-height
  WebView, inspector tray (Page · Text · Links) with Save to Memory.
- **Schedules** — natural-language creator with preview card, grouped
  Schedule cards (human cadence + live `"Fires in Xh Ym"` countdown,
  never raw monospace cron),
  Enable/Disable toggles, Run Now, 2-tap armed delete, held-write
  approvals, Recent Runs history.
- **Memory** — search + category/source filter pills, edit sheet, delete
  with 4 s Tap-to-Undo.
- **Settings** — System/Dark/Light theme, haptics toggle, Gmail OAuth
  (least-privilege scopes) + LLM keys, Confirmation rules
  (`send_email`: Enforced, no bypass), 1-tap System Diagnostics,
  permissions, `Plan`/`Tool`/`Recall`
  telemetry + provider/Interaction summary.
- **SideDrawer** — `Sessions` list with search and `New Session`,
  per-Session transcript hydration on cold start, plus voice-entry and
  Schedules launcher shortcuts.

### Native capabilities

- **System Diagnostics** — 1-tap report in Settings (key flags,
  exact-alarm state, scheduler state).
- **Share Sheet target** — the app accepts `ACTION_SEND` text/plain and
  routes the shared text into the Command composer.
- **App Shortcuts** (`shortcuts.xml`) — launcher shortcuts for voice
  command, new Session, and Schedules.
- **Markdown `Session` export** — 1-tap share/copy of the current
  Session transcript as Markdown.
- **Release hardening** — `app/proguard-rules.pro` (Tink, Room v6,
  WorkManager, OkHttp/Jsoup) wired into the `release` build type.

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
LLM_DEFAULT_PROVIDER=deepseek
DEEPSEEK_API_KEY=
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-flash
META_API_KEY=
META_BASE_URL=https://api.meta.ai/v1
META_MODEL=muse-spark-1.3
GOOGLE_OAUTH_CLIENT_ID=
GOOGLE_OAUTH_CLIENT_SECRET=
```

Debug builds compile these keys into `BuildConfig`. **Release builds always
compile with empty API-key fields** — release users paste DeepSeek/Meta keys in
Settings, stored encrypted in `TokenVault` on-device (ticket 36).

## Build

- Debug: `./gradlew :app:assembleDebug`
- Release (empty keys, verified ticket 37): `./gradlew :app:assembleRelease`
  → `app/build/outputs/apk/release/app-release-unsigned.apk` (~6.7 MB).
  Release `BuildConfig` fields `DEEPSEEK_API_KEY`, `META_API_KEY` are all `""`; OAuth IDs are `""` unless provided.

## Test

- `export ANDROID_HOME=$HOME/Library/Android/sdk`
- `./gradlew :app:testDebugUnitTest` — 626 tests, green
  (52 test classes incl. `PerfTimingsTest` benchmark).
- Device proof that still needs a human pass: `docs/SCHEDULING_TEST.md`;
  timing medians + optimization: `docs/PERF_TIMINGS.md`.

## Docs

- `ARCHITECTURE.md` — Mermaid data/control flow + the six system topics.
- `docs/adr/` — decisions 0001–0005 (Room-only, exact-alarm scheduler,
  per-call confirmation, OpenAI-compatible providers, hand-rolled loop).
- `SECURITY.md`, `TEST_PLAN.md`, `docs/VALIDATION_LOG.md`, `GLOSSARY.md`.

## Incomplete features (honest list)

- **#32 memory embeddings: cut.** Retrieval is keyword + importance/recency
  boosts with an offline FTS fallback (`Memory.kt`). No embedding column,
  no cosine ranking, no embedding cache.
- **DeepSeek + Meta wired** (ADR-0004 re-amended 2026-11-23, issue #46):
  DeepSeek (`deepseek-flash`) is the default with Meta
  (`muse-spark-1.3`) fallback over the shared OpenAI-compatible client.
  Keys live in `TokenVault` + Settings; the Command screen provider sheet
  switches the override at runtime (`null` = Auto/Default).
- **No TTS replies** (P2, cut per gaps §5).
- **On-device scheduling proof + profiler screenshot** need a human device
  pass (`docs/SCHEDULING_TEST.md`, `docs/PERF_TIMINGS.md`).
