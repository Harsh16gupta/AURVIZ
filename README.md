# AURVIZ

Local-first Android AI agent: voice/text commands → gated tool execution (Gmail, web, memory, schedules) over DeepSeek-default + Meta-fallback LLMs.

[![CI](https://img.shields.io/badge/CI-check.yml-blue)](.github/workflows/check.yml) ![tests](https://img.shields.io/badge/tests-659%20passed%20%2F%2055%20classes-green) ![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple) ![compileSdk](https://img.shields.io/badge/compileSdk-35-lightgrey)

Demo · Architecture · Security · Performance · Scheduling Proof
`TBD — add link here` · [ARCHITECTURE.md](ARCHITECTURE.md) · [SECURITY.md](SECURITY.md) · [docs/PERF_TIMINGS.md](docs/PERF_TIMINGS.md) · [docs/SCHEDULING_TEST.md](docs/SCHEDULING_TEST.md)

## Core Capabilities

| Capability | What it does |
|---|---|
| Voice-to-Action | Voice/text commands parsed into plans and executed through the confirmation gate. See `VoiceInput.kt`, `CommandPlanUi.kt` under `com.aurviz.agent`. |
| Personal Memory | Room-only source of truth (+FTS), SQLCipher-encrypted, opt-in Mem0 mirror. `Memory.kt`, `MemoryDb.kt` (`MemoryStore`), `MemoryReconciler.kt`, `Mem0Sync.kt`. |
| Real-World Tools | 13 tools across Gmail / schedules / memory / web (below). Mutating calls gate per-call; everything else runs direct. |
| Scheduled Tasks | Exact alarm → expedited unique WorkManager worker → headless agent loop. `ScheduleAlarm.kt` → `ScheduleWork.kt` → `ScheduleRuntime.kt`. |

13 active tools (`ToolRegistry.kt:240-278` wires gmail → schedule → memory → web):

| Area | Tools |
|---|---|
| Gmail (`GmailTools.kt`) | `search_emails`, `read_email`, `send_email` |
| Schedules (`ScheduleTools.kt`) | `create_schedule`, `list_schedules`, `cancel_schedule` |
| Memory (`MemoryTools.kt`) | `save_memory`, `search_memory` |
| Web (`WebTools.kt`) | `open_url`, `get_page_text`, `click`, `scroll`, `extract` |

`requiresConfirmation=true` on exactly 3: `send_email`, `create_schedule`, `cancel_schedule`. All others `false`. Scheduled runs are fail-closed to a 5-read allowlist (`ScheduledRun.kt:257-264`): `search_emails`, `read_email`, `get_page_text`, `search_memory`, `list_schedules` — sends/writes become `PendingApproval` rows, never executions.

## Architecture Overview

Full data/control flow: [ARCHITECTURE.md](ARCHITECTURE.md) · diagram source: [docs/architecture.mmd](docs/architecture.mmd).

- **Per-call confirmation gate, not whole-plan pre-approval.** `ToolRegistry.kt:129-234` (`execute` gate-refusal, `executeGated`) + `ApprovalGate.kt` → `ApprovalCard.kt` / `ui/components/ConfirmationCard.kt`. Each gated tool emits a `ConfirmationReceipt`; `AllowAlways.kt` scopes standing approval. — [0003-gmail-oauth-confirmation.md](docs/adr/0003-gmail-oauth-confirmation.md)
- **Local-first encrypted memory.** Room is the sole persisted memory path; Mem0 is an opt-in mirror, Supermemory dropped 2026-11-23. — [0001-supermemory-room-hybrid.md](docs/adr/0001-supermemory-room-hybrid.md) · [0006-local-first-memory-upgrades-and-opt-in-mem0-sync.md](docs/adr/0006-local-first-memory-upgrades-and-opt-in-mem0-sync.md)
- **Exact-alarm → Worker scheduler.** `ScheduleAlarm.kt` fires the exact alarm; an expedited unique Worker (`ScheduleWork.kt`) runs the headless loop (`ScheduleRuntime.kt`). Receivers do no DB work on raw threads. — [0002-workmanager-alarm-scheduling.md](docs/adr/0002-workmanager-alarm-scheduling.md)
- **Two providers, DeepSeek-default + Meta-fallback.** Exactly 2 (`ProviderPicker.kt:125-148`, `LlmProvider.kt:247-252`): `deepseek` (default) + `meta`. Gemini/Groq removed; only traces are legacy Groq-key cleanup and a gemini→deepseek pref mapping. — [0004-openai-compatible-llm.md](docs/adr/0004-openai-compatible-llm.md)
- **Hand-rolled loop, no framework.** `AgentViewModel` orchestrates → `AgentLoop` + `ToolRegistry` gate → adapters (`gmailTools`, `scheduleTools`, `memoryTools`, `webTools`) backed by `GmailApi` / `ScheduleStore` / `MemoryStore` / `WebBridge` seams. — [0005-agent-loop-hand-rolled.md](docs/adr/0005-agent-loop-hand-rolled.md)

Glossary (12 canonical terms): [GLOSSARY.md](GLOSSARY.md). Agent boundaries and contribution rules: [AGENTS.md](AGENTS.md).

## Quick Start & Build

Prerequisites: **Java 17** · Android SDK with **compileSdk 35** (`minSdk 26`, `targetSdk 35`) · AGP 8.7.3 · Kotlin 2.0.21.

```bash
cp local.properties.sample local.properties   # then fill in keys; local.properties is git-ignored
./gradlew :app:testDebugUnitTest               # 659 unit tests
./gradlew :app:installDebug                    # install to attached device/emulator
./gradlew :app:assembleDebug :app:assembleRelease
```

Keys (`local.properties.sample`, 21 lines): debug `BuildConfig` carries `DEEPSEEK_API_KEY` / `META_API_KEY`; **release compiles both as `""`** — release users enter keys in Settings → `TokenVault` (EncryptedSharedPreferences). `MEM0_API_KEY` is commented out (optional, opt-in mirror only). Defaults: `LLM_DEFAULT_PROVIDER=deepseek`, `DEEPSEEK_MODEL=deepseek-flash`, `META_MODEL=muse-spark-1.3`. DeepSeek requires a paid key. Never commit keys.

APK note: `app-release-unsigned.apk` is **unsigned** — graders install `app-debug.apk` directly or sign the release themselves.

Key deps: Compose BOM 2024.10.01 · activity-compose 1.9.3 · Room 2.6.1 · WorkManager 2.9.0 · security-crypto 1.1.0 · SQLCipher 4.6.1 · OkHttp 4.12.0 · Jsoup 1.17.2. Version: `0.1-t1`.

## Verification & Performance

| Check | Result | Proof |
|---|---|---|
| Unit tests | **659 tests / 55 classes / 0 failures, 0 errors, 0 skipped** (parsed `app/build/test-results/testDebugUnitTest/*.xml`, 2026-11-25) | [TEST_PLAN.md](TEST_PLAN.md) |
| On-device scheduling | Exact-alarm → Worker chain exercised on hardware | [docs/SCHEDULING_TEST.md](docs/SCHEDULING_TEST.md) |
| Performance | JVM medians per ticket 37; human device pass + profiler screenshot pending | [docs/PERF_TIMINGS.md](docs/PERF_TIMINGS.md) |
| CI | Secret-scan grep → `cleanTestDebugUnitTest` → `testDebugUnitTest` → `assembleDebug` → `lintDebug` (ubuntu-latest, Temurin Java 17) | [.github/workflows/check.yml](.github/workflows/check.yml) |

Status: P0 loop / tools / gate / persistence / scheduler / providers / UI = DONE per `gaps.md` §1 (`gaps.md` at repo root). Pending: human device passes, memory embeddings cut (#32), no TTS (P2 cut). Browser evidence: [docs/BROWSER_TEST.md](docs/BROWSER_TEST.md).

## Security & Privacy

- No secrets in git: keys live in git-ignored `local.properties` / `TokenVault`; CI runs a secret-scan grep (`sk-live|AIza|xox|ghp_|AKIA`) before tests.
- `TokenVault` = Keystore + EncryptedSharedPreferences; SQLCipher encrypts the Room DBs (`AppDbCipher.kt`).
- Fail-closed gates: `send_email` requires per-call confirmation; scheduled runs allowlist to 5 reads, sends/writes degrade to `PendingApproval`.
- Details: [SECURITY.md](SECURITY.md). Gmail OAuth + per-call confirmation: [0003-gmail-oauth-confirmation.md](docs/adr/0003-gmail-oauth-confirmation.md).

## AI Development Tools Disclosure

- AI tools were directed via written specs and ADRs ([docs/adr/](docs/adr/)), not open-ended generation; repo rules in [AGENTS.md](AGENTS.md) constrained UI/Agent/Tools/Memory/Scheduler boundaries.
- Output was audited against code: [docs/PRODUCTION_READINESS_AUDIT.md](docs/PRODUCTION_READINESS_AUDIT.md).
- Validation is JUnit (659 tests) plus physical-device runs, logged with file:line evidence in [docs/VALIDATION_LOG.md](docs/VALIDATION_LOG.md).
- Every ticket ships unit tests (fake-LLM loop, tool-error recovery, max-step abort, reject path; red-team for send-path; cron/tz edges for scheduler); one ticket per commit.

## Project Structure

98 `.kt` files under `com.aurviz.agent` (+ `ui/` subpackage):

```
app/src/main/java/com/aurviz/agent/
  AgentLoop.kt  ChatFallback.kt  ProviderPicker.kt  LlmProvider.kt
  ToolRegistry.kt  ApprovalGate.kt  ApprovalCard.kt  ConfirmationReceipt.kt  AllowAlways.kt
  GmailTools.kt  ScheduleTools.kt  MemoryTools.kt  WebTools.kt  ToolLabels.kt
  Memory.kt  MemoryDb.kt  MemoryReconciler.kt  Mem0Sync.kt  AppDbCipher.kt  TokenVault.kt
  Schedule.kt  ScheduleDb.kt  ScheduleAlarm.kt  ScheduleWork.kt  ScheduleRuntime.kt  ScheduledRun.kt  ScheduleIds.kt
  GmailHttp.kt  Email.kt  LoopbackAuth.kt
  Web.kt  WebViewBridge.kt  WebBackground.kt  BrowserChromeUi.kt
  VoiceInput.kt  BatteryDoze.kt  MainActivity.kt  Session.kt  InteractionDb.kt
  CommandPlanUi.kt  ChatTimeline.kt
  ui/
    AgentViewModel.kt  AgentUiState.kt  AgentNav.kt
    screens/       # Browser, Command, Memory, Onboarding, Schedules, Settings
    sheets/        # EmailConfirmation, ForgetMemory, VoiceCommand
    components/    # ConfirmationCard + 41 more
    theme/         # 2 files
app/src/test/     # 55 test classes, 659 tests
docs/adr/         # 0001..0006 (see Architecture Overview for exact filenames)
```

Build config: `build.gradle.kts` (AGP 8.7.3, Kotlin 2.0.21) · `app/build.gradle.kts` (compileSdk/targetSdk 35, minSdk 26, `0.1-t1`, Java 17).
