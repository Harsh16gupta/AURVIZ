# AURVIZ gap analysis and build plan - current state

Date: 2026-11-23 (updated; branch `ui/midnight-daylight`, 4-phase
Compose UI upgrade). Basis: the code as it stands today, checked against
the listed files. The original 2026-11-23 plan (commit 3fcfbff, 82 tests,
no agent loop) is superseded - every P0 item below shipped.

## 1. Where you actually are

- **Agent loop**: multi-step `AgentLoop` (`AgentLoop.kt`, max 8 steps,
  per-step 90 s / total 5 min timeouts, structured `{ok:false}`
  recovery, `tool_call_id`-matched results, `StepStarted`/`ToolRunning`/
  `NeedsApproval`/`StepDone`/`Failed` events into the timeline UI).
- **Tool surface**: 13 LLM-visible tools with JSON schema, risk level,
  and `requiresConfirmation` (`ToolRegistry.kt`): `search_emails`,
  `read_email`, `send_email`, `open_url`, `get_page_text`, `click`,
  `scroll`, `extract`, `save_memory`, `search_memory`,
  `create_schedule`, `list_schedules`, `cancel_schedule`.
- **Confirmation**: per sensitive tool call (`send_email`,
  `create_schedule`, `cancel_schedule`) via `ToolRegistry.executeGated`
  + deterministic `SendPolicy`; reject/timeout yields
  `{status:"cancelled_by_user"}` without running. In-app card is the
  unified `ConfirmationCard` (24dp pending, 18dp settled receipt, inline
  editable email fields). Scheduled runs hold sends/writes as
  `PendingApproval` rows + notification, approved in-app exactly once.
  `send_email` Confirmation is **Enforced** - Settings shows a read-only
  row, no bypass exists.
- **Persistence**: Room-only memory (`MemoryStore`, no cloud provider),
  `schedules`, `schedule_runs` (idempotent scheduleId+fireTime),
  `pending_approvals`; `TokenVault` (EncryptedSharedPreferences) for Gmail
  OAuth + user-entered LLM keys; `local.properties` git-ignored, release
  `BuildConfig` key fields empty.
- **Scheduler**: exact alarm → expedited unique Worker → headless loop on
  the 5-read-tool allowlist; re-arm on receiver, boot, timezone change;
  inexact fallback + battery/OEM guidance when permission is revoked.
- **Providers**: DeepSeek default with Meta fallback over the
  shared OpenAI-compatible client (ADR-0004 re-amended, #46); in-app
  provider sheet (`selectedProvider`, `null` = Auto); visible
  "retried on Y" disclosure, never silent mid-run replay.
- **UI**: full Jetpack Compose Light/Dark (Midnight/Daylight) system with
  6 screens - Onboarding, Command (scrollable Room-persisted
  `Interaction` feed, live `ThinkingIndicator`, Stop/Cancel, provider
  sheet, settled Confirmation receipts, Markdown `Session`
  export/share), Browser (Back/Forward/Reload, 2dp load progress,
  Page · Text · Links inspector, Save to Memory), Schedules
  (natural-language creator, human cadence + live `"Fires in Xh Ym"`
  countdown, enable/disable toggles, Run Now, 2-tap delete, run
  history), Memory (search + category/source filters, inline edit,
  delete with 4 s undo), Settings (theme, haptics, connections,
  Confirmation rules, 1-tap System Diagnostics, permissions,
  `Plan`/`Tool`/`Recall` telemetry) - plus the `SideDrawer`
  (`Sessions` with search and `New Session`, cold-start transcript
  hydration), the live-RMS `VoiceCommandSheet` waveform, the
  `ACTION_SEND` Share Sheet target, launcher shortcuts
  (`shortcuts.xml`), and `app/proguard-rules.pro` wired into the
  `release` build type.
- **Persistence**: Room v6 (`interactions` table for the `Interaction`
  feed plus persisted `Sessions` with transcript hydration,
  `memories`/`memoriesFts`, `schedules`, `schedule_runs`,
  `pending_approvals`) with lossless full-row Memory undo
  (`restoreMemoryRow`).
- **Tests**: 626 unit tests, 0 failures (`./gradlew
  :app:testDebugUnitTest`, 52 test classes incl. `PerfTimingsTest`).
  Debug APK via `./gradlew :app:assembleDebug`.

## 2. Genuine remaining gaps / trade-offs (honest list)

1. **Interaction feed persistence - SHIPPED (T4, #109).** `InteractionRecord`
   rows are Room v6 persisted (`interactions` table in `InteractionDb.kt`)
   with per-`Session` transcript hydration on cold start - past commands
   survive process death.
2. **Full-row Memory undo - SHIPPED (T4, #109).** `restoreMemoryRow`
   (`Memory.kt:110`) re-saves the full row (text, source, category,
   importance, createdAt, lastUsed, useCount), so a restored Memory keeps
   its ranking state.
3. **Live RMS waveform - SHIPPED (T2, #107).** `onRmsChanged` drives the
   `VoiceCommandSheet` waveform (`MainActivity.kt` + `VoiceCommandSheet.kt`),
   falling back to `VoiceUiState` when levels are unavailable. By design
   where levels are missing, not a bug.
4. **Device-only proof outstanding** - on-device scheduling proof
   (`docs/SCHEDULING_TEST.md`), STT/LLM-first-token/end-to-end medians,
   and the Android Studio profiler screenshot (`docs/PERF_TIMINGS.md`
   documents the JVM medians + the human-pass method). Nothing fabricated.
5. **Memory retrieval is keyword + importance/recency + FTS** - embeddings
   (#32) remain cut: no embedding column, no cosine, no cache.
6. **No TTS replies** (P2, cut).
7. **Debug keys compile into `BuildConfig`** - release builds use empty
   keys + Settings entry; never share a debug APK built from personal
   `local.properties`.

## 3. Evidence the graders look for (status)

- **Tests**: loop (scripted fake LLM: multi-step, error recovery,
  max-step abort, reject path), prompt-injection red-team, cron/timezone
  edges, memory ranking, confirmation-cannot-be-bypassed - all green.
- **Scheduling proof**: needs the human device pass (see gap 4).
- **Performance**: JVM medians + top-k optimization in
  `docs/PERF_TIMINGS.md`; device medians pending (see gap 4).
- **Validation log**: `docs/VALIDATION_LOG.md`, updated per finding.
- **Docs**: README (setup, `local.properties.sample`, build, test),
  ARCHITECTURE.md (Mermaid + six topics), SECURITY.md, TEST_PLAN.md,
  DECISIONS (`docs/adr/` 0001–0005), GLOSSARY.md.
- **Repo hygiene**: `ui_reference/` untracked + git-ignored (source
  material only, never shipped); the old web-wireframe `ui/idea.md`
  removed so it cannot confuse reviewers; secret scan before every push.
