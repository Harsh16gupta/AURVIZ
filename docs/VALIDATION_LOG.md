# Validation log

Every case where the AI was wrong and how it was caught. Add an entry with file:line evidence before moving on.

## 2026-11-23 — deep-review fix batch (against `8ec669f`, 11 commits)

- **Fail-open default broke two loop tests after the fix, not before.**
  Changing `AgentLoop` default confirm to `Rejected`
  (`app/src/main/java/com/aurviz/agent/AgentLoop.kt:97`) turned
  `multiStepLoopMatchesToolCallIds` and `loopTimelineShowsExecutedTools`
  red — both script a gated `create_schedule` and relied on the old
  auto-approve. Fixed by passing explicit `Approved` in those tests
  (`app/src/test/java/com/aurviz/agent/AgentLoopTest.kt:67,199`); the new
  `ApprovalGateTest.defaultConfirmerIsFailClosed` pins the fail-closed default.
- **Per-call deadline check was too strict on the first call.** Checking the
  total budget before every batched call broke
  `totalTimeoutAbortsToFailedKeepingFinishedSteps` (expected 1 finished step,
  got 0): the LLM turn itself had consumed the budget and the first Tool
  still ran. Scoped the check to `callIndex > 0`
  (`app/src/main/java/com/aurviz/agent/AgentLoop.kt:152-160`) — a slow first
  Tool/Confirmation cannot start another call, matching the review wording;
  `secondCallInBatchDoesNotStartAfterTotalTimeout` pins it.
- **Room `@Query` is CLASS-retention: reflection sees null.** The FTS test
  first read the DAO annotation via reflection and failed on
  `assertTrue(ann != null)`. Rewrote as a source assertion with the
  `moduleDir()` walk used by the manifest tests
  (`app/src/test/java/com/aurviz/agent/MemoryTest.kt:226-241`).
- **Commit message said FTS5, code uses FTS4.** Fixed by amend before pushing
  (`ffc9369`).
- **Stale RUNNING recovery needs the pid, but `find()` hides it.** The
  `ScheduleRun` record carries no pid, so reclaim goes through a new
  `resetStaleRunning` returning the existing pid (marked FAILED for the
  record) instead of deleting the row
  (`app/src/main/java/com/aurviz/agent/ScheduledRun.kt:212-224`,
  `app/src/main/java/com/aurviz/agent/ScheduleDb.kt:184-191`).
- **Not fixed here (no device attached):** the scheduled-run device exercise,
  profiler capture, and live Gmail/LLM calls. `docs/SCHEDULING_TEST.md` and
  `docs/PERF_TIMINGS.md` record the green unit subset plus the human-pass
  method instead of fabricating on-device results.

## 2026-11-23 — P0 approval binding fix (deep-review finding verified, against `8ec669f`)

- **One replaceable queue let card A release call B.** `AgentViewModel.kt:855`
  held a single `pendingDecisions` replaced per `runLoopCommand` (`:887`),
  `CommandScreen.kt:84-92` sent approve/reject with no call id, and the
  Composer stayed live (`:123-129`) so a second command overwrote the queue.
  Fixed with `ApprovalGate` (`app/src/main/java/com/aurviz/agent/ApprovalGate.kt`):
  per-call one-shot queue bound to (runId, callId), stale ids ignored,
  double-tap dropped, single-flight runs.
- **Default confirmer was fail-open.** `AgentLoop.kt:97` defaulted to
  `Approved`; headless already used `Rejected` (`ScheduledRun.kt:112`).
  Changed to `Rejected`; `AgentLoopTest` gated cases now pass explicit
  `Approved` (`app/src/test/java/com/aurviz/agent/AgentLoopTest.kt:67,199`).
  New `ApprovalGateTest` (6 tests) pins stale-callback, cross-run,
  double-tap, single-flight, fail-closed default, and two-call two-approval
  paths. Full suite: 312 tests / 30 classes, 0 failures.

## 2026-11-23 — ADR/code drift found during grill (against HEAD `3fcfbff`)

- **ADR-0002 said periodic work; code uses one-time work.** `docs/adr/0002-workmanager-alarm-scheduling.md` claimed "WorkManager periodic work", but `enqueueRoutineWork` (`app/src/main/java/com/aurviz/agent/ScheduleWork.kt:40-51`) builds a `OneTimeWorkRequest` with `enqueueUniqueWork` — no periodic builder anywhere in `app/src`. Fixed by ADR-0002 rewrite.
- **ADR-0003 said whole-Plan confirmation; decisions require per-tool-call.** `docs/adr/0003-gmail-oauth-confirmation.md` required "explicit whole-Plan Confirmation before any send", but a plan cannot be approved before later steps exist, and the code had no registry gate at all. Fixed by ADR-0003 amendment.
- **ADR-0001 hybrid vs Room-only shipped path.** `docs/adr/0001-supermemory-room-hybrid.md` described Supermemory behind `MemoryRepository`, but the only wiring is `RoomMemoryStore` (`app/src/main/java/com/aurviz/agent/ui/AgentViewModel.kt:72`); Supermemory appears only in a comment (`app/src/main/java/com/aurviz/agent/Memory.kt:5`) and an unread sample key. Fixed by ADR-0001 amendment.
- **ADR-0004 promised picker UI and DeepSeek/Meta keys; code has neither.** `docs/adr/0004-openai-compatible-llm.md` promised an in-app picker and Meta key wiring, but `ProviderPicker.kt:3-4` notes the screen stays unwired and no `META_*` BuildConfig field exists. Fixed by ADR-0004 amendment (config-only, Groq fallback).
- **Stale comment: "memory-only for now".** `app/src/main/java/com/aurviz/agent/Email.kt:37` says credentials are memory-only, but `TokenVault` (EncryptedSharedPreferences) exists and `AgentViewModel.kt:75` loads tokens from it. Comment predates the vault.
- **ADR-0004 said dropped providers are unwired, but DeepSeek still is.**
  `docs/adr/0004-openai-compatible-llm.md` declares Groq the single fallback
  with DeepSeek "unwired", but `ProviderPicker.kt:112-113,141-146` still reads
  `BuildConfig.DEEPSEEK_API_KEY/_BASE_URL` and `LlmProvider.kt:234-238` still
  serves a `"deepseek"` branch, and `app/build.gradle.kts` still emits the
  debug fields. Caught during ticket-37 doc pass (`grep DEEPSEEK app/src`).
  Not removed in ticket 37 (prod behavior freeze); recorded for the
  follow-up to either rewire the ADR or delete the branch.

## 2026-11-23 — ticket 37 submission proof (against `f251cf2`)

- **No device attached, so no device results are claimed.** `adb devices`
  lists nothing in this environment; `docs/SCHEDULING_TEST.md` records the
  green unit subset and the exact human-pass method instead of fabricating
  the 2-minute/kill/screen-off/force-idle/reboot run. Profiler screenshot
  likewise recorded as method-only in `docs/PERF_TIMINGS.md`.
- **Timing medians are JVM-measured, not device SLOs.** `PerfTimingsTest`
  reports 10-run medians (recall 1.348 ms, prompt 0.027 ms, registry
  0.878 ms over 500 synthetic rows) with the raw runs in stdout; the
  top-k trim comparison (~50× render, ~100× prompt size) is the before/after
  optimization. STT / first-token / end-to-end stay human-pass items.
- **Sample had dead keys and missed OAuth IDs.** `local.properties.sample`
  carried `SUPERMEMORY_API_KEY` / `META_LLAMA_API_KEY` (referenced nowhere
  in `app/src` or `app/build.gradle.kts`) and lacked the
  `GOOGLE_OAUTH_CLIENT_ID/SECRET` the build reads. Cleaned to match the
  ADRs; DeepSeek lines kept and marked legacy because the debug
  `BuildConfig` + provider branches still read them.

## 2026-11-23 — draft grill answers checked (against HEAD `903f011`)

- **Draft B3 called `TIME_SET` handling and receiver registration unverified.** Both are present: `shouldRescheduleOnSystemAction` handles `Intent.ACTION_TIME_CHANGED` (`app/src/main/java/com/aurviz/agent/ScheduleAlarm.kt:110-113`), and `BootReceiver` subscribes to `BOOT_COMPLETED`, `TIMEZONE_CHANGED`, and `TIME_SET` (`app/src/main/AndroidManifest.xml:36-45`). Only the physical alarm/Doze/reboot proof remains unverified.
- **Draft NG-1 analysis misstated the legacy test.** `allToolSchemasExposeEmailPlusThreeScheduleTools` calls `allToolSchemas()` directly and checks that four names are present; it does not inspect an HTTP request, assert an exact count, or assert that other tools are absent (`app/src/test/java/com/aurviz/agent/LlmProviderTest.kt:116-125`). Keeping the helper therefore need not make this test fail; the fix needs a request-path schema test instead of relying on that claim.
- **Draft Q3 undercounted `headless` usage.** The report has one descriptive use plus the `HeadlessChatLlm` identifier (`docs/RESEARCH_PATTERNS.md:103,187`), not three descriptive occurrences. Production has 16 textual matches across five files, including background-web loading where the term is appropriate. Scope the terminology cleanup to scheduled-run prose rather than claiming a global three-comment replacement.

## 2026-11-23 — P0 opaque approval proposal ID on `integration/agent-loop`

- **Fix:** `ApprovalGate.openGate` now returns `GateTicket(proposalId, queue)` with a UUID nonce; `resolve(proposalId, decision)` is the only release path — the Tool call id is recorded for introspection only (`app/src/main/java/com/aurviz/agent/ApprovalGate.kt:27-31,86-124`). ViewModel approve/reject echo the nonce from `pendingApprovalProposalId` with no tap-time `currentRunId` read (`app/src/main/java/com/aurviz/agent/ui/AgentViewModel.kt:825-848`); the card renders only when the nonce is present and keys edit fields on it (`app/src/main/java/com/aurviz/agent/ui/screens/CommandScreen.kt:86-107,173-189`). Killed the recycled-fallback-id cross-run approve (`call_s0_0` regenerated every run, old tap + new run previously shared the id string).
- **Tests:** updated 3 existing gate tests to the ticket API; added `proposalIdsAreUniqueOpaqueAndUnrelatedToCallId`, `staleNonceCannotReleaseRecycledFallbackId`, `resolveAfterFinishRunFails` (`app/src/test/java/com/aurviz/agent/ApprovalGateTest.kt:19-105`). Suite: 325 tests / 30 suites, 0 failures (`./gradlew :app:testDebugUnitTest --rerun-tasks`). Marker + secret scans clean.
