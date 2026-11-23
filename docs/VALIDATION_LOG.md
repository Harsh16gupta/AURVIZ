# Validation log

Every case where the AI was wrong and how it was caught. Add an entry with file:line evidence before moving on.

## 2026-11-23 — Issue #51 session context V1 (`agent/session-context-51`)

- **ViewModel missed import broke compile.** First build failed with
  unresolved `appendSessionTurn` in `AgentViewModel.kt:1312,1335` — the
  ViewModel lives in `com.aurviz.agent.ui` while the helper lives in
  `com.aurviz.agent`. Fixed with explicit
  `import com.aurviz.agent.appendSessionTurn`; suite green after.
- **Glossary avoided Session, issue requires it.** `GLOSSARY.md:29` said
  Interaction avoids Session, but #51 is session-scoped. Added Session
  term (sequence of Interactions sharing one transcript) instead of
  fighting the wording in code review.
- Tests: new `SessionContextTest` (5 tests: history carried, cold start,
  identity grounding, compaction priority, tool-body drop). Full
  `:app:testDebugUnitTest` green.

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

## 2026-11-23 — §9 blocker batch on `integration/agent-loop` (7 fixes)

- **Reject-disarms (consumer, not contract).** Reject/timeout stay `Success(cancelled_by_user)`; `AgentEvent.StepDone` now carries `approved` + `effectiveArgs` (`AgentLoop.kt:175`), and `loopScheduleSideEffect(name, approved, result)` requires approved Success (`Schedule.kt`). ViewModel consumes the event (`AgentViewModel.kt:705`). Tests: `ScheduleTest.loopCreateArmsAndCancelDisarmsOnlyOnApprovedSuccess`, `AgentLoopTest.stepDoneCarriesRejectedApprovalFlag/stepDoneCarriesEditedArgsThatRan`.
- **MIME CRLF.** `executeSendEmail` rejects CR/LF in To/Subject (retryable Failure, no transport touch); bodies stay multiline (`Email.kt`). Tests in `EmailTest` (to/subject rejected, multiline body sends).
- **Deadline remaining-budget.** Approval wait capped at `totalBudget - elapsed` via pure `approvalWaitMs` (`AgentViewModel.kt`), wired into the confirm poll; immediate TimedOut on exhausted budget. Tested in `ViewModelLoopWiringTest`.
- **Disclosure-on-failure.** `retryLine()` and `scheduledSummary()` name tried hosts when no provider answered instead of going silent (`AgentUiState.kt:90-94`, `ScheduledRun.kt`). Tests in `ViewModelLoopWiringTest` + `ProviderPinningTest`.
- **Notification ID/channel split.** Foreground-service note gets its own channel (`routine_running`, LOW) and ID (`routineForegroundNotificationId`); results keep `routines`/DEFAULT + `routineNotificationId` — previously the first-created LOW channel pinned results silent (`ScheduleWork.kt:33-54`, `Schedule.kt`).
- **Alarm recovery.** Reclaim is one conditional UPDATE (exactly one winner) re-asserting the RUNNING lease instead of terminal FAILED (`ScheduleDb.kt:125-131,178-185`); InMemory mirrors under lock. Worker catch-all returns `Result.retry()` on the same fireTime (idempotent) instead of terminal failure (`ScheduleWork.kt:107-114`). Tests: lease semantics in `ScheduledRunTest` (incl. Room-adapter fake).
- **Capability bring-forward (39/40).** `FallbackChatLlm`/`HeadlessChatLlm` take the run's advertised `tools` (default `allToolSchemas()` keeps existing tests green); foreground passes `registry.toolSchemas()`, worker passes `scheduledRegistry(full).toolSchemas()` so scheduled reads (search/read/list/get_page_text) are actually visible to the model. Wire-tested in `ChatRolesWireTest.advertisedToolsComeFromTheRegistryView`.
- **Re-verify:** 336 tests / 30 suites, 0 failures; `:app:assembleRelease` APK built; `:app:lintDebug` 0 errors (24 warnings — 3 ModifierParameter pre-existing incl. untouched files); marker + secret scans clean. Device checks still outstanding (no adb device): API-30 expedited, Android 12+ revoke/grant, v4→v5 upgrade, #37 profiler.
- **Caught own mistake:** two `edit` calls silently dropped a doc comment / test header (oldString matched across a Unicode dash boundary); repaired and re-read before committing. `edit` with multi-line oldString needs a post-edit read check — done for all fixes above.

## 2026-11-23 — 3-bracket landing (PRs #47/#48/#49/#50, closes #38)

- **#48 merged into the wrong base.** `gh pr merge 48` landed bracket B into
  `merge/A-reviewed-core` (`946dbc1`, parents `aeb2234`+`f251cf2`) instead of
  main — its base was the A branch. Caught on re-verify: `git log
  origin/main` showed only `aafba48` (#47) while
  `origin/merge/A-reviewed-core` sat at `946dbc1`. Fixed without rewriting any
  `merge/*` SHA: opened `main ← merge/A-reviewed-core` (#50) and merged it
  (`e1c79b5`), so main holds A+B; #49's diff then shrank to the C-only
  28 commits (`git rev-list --count origin/main..origin/merge/C-deltas`).
  Never mark a stacked landing done before `git log origin/main` shows the
  content.
- **#38 needed no close — GitHub auto-closed it as MERGED.** Once #49 landed
  (`25809c7`), every commit on `integration/agent-loop` was contained in
  main, so GitHub marked #38 merged (`mergeCommit 25809c7`) and rejected the
  manual close. The superseding comment (links to #47/#48/#49/#50) was posted
  first. End state verified: `origin/main` =
  `25809c7` (#49) → `e1c79b5` (#50) → `946dbc1` (#48) → `aafba48` (#47).
- **Device-test discipline that mattered:** `am force-stop` cancels alarms
  (`pi_cancelled`) and voids a kill test — swipe-kill only; one B2 attempt
  was lost to this and re-run. Fires kept ≥3 min apart (Doze quota).
- Follow-ups live outside the stack: #46 (needs ADR ruling, untouched), #51
  (session-scoped conversational context + "myself" grounding).

## 2026-11-23 — DeepSeek re-wire port (issue #46, reference `8ec669f`)

- **Verification gate passed, suspicion was wrong.** `deepseek-flash` looked
  like a wrong model ID (legacy IDs are `deepseek-chat`/`deepseek-reasoner`),
  but current DeepSeek docs list `deepseek-flash` (served by
  DeepSeek-V4.1-Flash-1017) on OpenAI base `api.deepseek.com` with tool calls;
  Meta docs list `muse-spark-1.3` on OpenAI base `api.meta.ai/v1` with tool
  calling. Both fit the shared `OpenAiChatProvider` (`baseUrl + "/chat/completions"`).
  Caught by web check before wiring instead of assuming.
- **#46 sketch == `8ec669f` 1:1, but `8ec669f` is NOT in main**
  (`merge-base --is-ancestor` = NOT IN MAIN, only on `integration/ng-batch`).
  Ported manually onto fresh `land/deepseek-rewire` from main — never
  resurrected the stale branch. Dropped stale `README` drift note and
  `RESEARCH_PATTERNS`/`VALIDATION_LOG` hunks from the reference.
- **Main had no `defaultCandidates` order tests.** Bracket C unwire left only
  `droppedDeepseekNameFallsBackToGemini` (`app/src/test/java/com/aurviz/agent/ProviderPickerTest.kt:37-43`);
  the re-wire adds `deepseekName`/`metaName` + 3 order tests
  (preferred → deepseek → meta → gemini → groq, blank → gemini).

## 2026-11-23 — NG-3 landing (cherry-pick `0fa0f45` onto main)

- **Only the docs hunk conflicted.** Code hunks (`ChatTimeline.kt` +Plan,
  `Planner.kt`/`PlannerTest.kt` deletions, `AgentViewModel.kt` comment)
  applied cleanly; `docs/VALIDATION_LOG.md` conflict resolved by keeping
  main's version and writing this fresh entry instead of the stale hunk.
- **Re-verified dead before deleting:** `grep planCommand|PlannerOutcome|
  missingSlots|slotQuestion app/src` empty after the pick; single `Plan`
  definition at `ChatTimeline.kt:11` (`PlanCard.kt:20` is unrelated
  `PlanPrice`); `getImportantEmails` untouched (live registry tool).

## 2026-11-23 — NG-7 landing (cherry-pick `6fb9a10` onto main)

- **Applied cleanly, no conflicts** (`Email.kt` +7/−2, `EmailTest.kt` +88).
  Old code called `api.listInbox()` twice (two full scans); new code
  partitions a single scan. Re-verified against main: `readImportant`
  is the only `listInbox` double-call site.

## 2026-11-23 — NG-4 landing (RedTeamTest from `8d583a5` onto main)

- **Tests-only pick, no prod change.** Took only
  `app/src/test/java/com/aurviz/agent/RedTeamTest.kt` (+249) from the
  reference commit; dropped its stale `TEST_PLAN.md` count hunk and
  `VALIDATION_LOG.md` hunk (both refreshed below for the current tree).
- **Prompt-injection red-team path (per repo rule for send-path-adjacent
  changes):** EchoLeak-variant cases pin 3 sinks — no send execution, no
  exfil link in rendered answer text, no exfil data in scheduled
  summary/notification. Dummy markers only (`evil.example`, RFC-2606).

## 2026-11-23 — review-findings landing (from `6036d0e` onto main)

- **Two of four hunks ported as-is** (`ChatTimeline.kt` NG-3 slot-drop
  comment, `RedTeamTest.kt` headless→scheduled comment wording) — blob
  hashes match the reference exactly (`1ed434b`, `3276ee4`).
- **`RegistrySchemasRequestTest.kt` hunk dropped: file is deleted in
  main.** Bracket C folded its coverage into `ChatRolesWireTest`
  (NG-1, `213f367`); `grep RegistrySchemasRequest app/src` is empty, so
  the test-rename has no target. Kept deleted (`git rm`). Surviving
  `headless*` test names (`ChatRolesWireTest.kt:88`,
  `ScheduledRunTest.kt:361`) and `HeadlessChatLlm` stay — renaming those
  is a wider terminology job, out of scope here.
- **Stale `VALIDATION_LOG.md` hunk dropped, not ported.** It claims
  `TokenVault`/`LlmKeys` carry Gemini/Groq only — false since the
  DeepSeek re-wire (`7e8f558`). Replaced by this entry.
- **Unexplained `HANDOFF.md` worktree edit discarded.** A
  "Draft DeepSeek Re-wiring Plan" variant appeared in the worktree that
  neither HEAD nor `6036d0e` contains; restored HEAD's version and
  excluded it from the commit (HANDOFF is scratch, not source of truth).

## 2026-11-24 — Issue #66 T1 foreground judge for sends (`main`, `e99a897`)

- **Bulk-count test first drafted against the wrong seam.** Assumed a
  6-address `to` string through `executeGated` would reach
  `DefaultSendPolicy`'s `maxRecipients` branch
  (`app/src/main/java/com/aurviz/agent/Email.kt:308`), but `draftFrom`
  wraps `to` as a single-element list
  (`app/src/main/java/com/aurviz/agent/ToolRegistry.kt:326`), so any
  multi-address string denies earlier as malformed — the count branch is
  reachable only via direct `EmailDraft` construction. Wrote the test at
  that level instead (`DefaultSendPolicy.check` Deny plus
  `executeSendEmail(..., approved = true)` Failure, zero sends;
  `app/src/test/java/com/aurviz/agent/ApprovalCardTest.kt:158`).
- Tests: 2 new (`policyBlocksHeaderInjectionEvenWhenGateApproved`,
  `policyBlocksBulkRecipientCountEvenWhenApproved`). Full
  `:app:testDebugUnitTest` green (344 tests, 0 failures). No red-green
  cycle: the guards pre-existed (`Email.kt:373-378`, `Email.kt:303-314`),
  so the tests pin behavior rather than drive new code.

## 2026-11-24 — Issue #68 allow-always Preference (`main`)

- **Wildcard grant reached the store.** First `InMemoryAllowAlwaysStore.allow`
  persisted any non-blank string, so `allow("*")` landed in the grant set
  (`app/src/main/java/com/aurviz/agent/AllowAlways.kt`). Eligibility still
  rejected it (not plausible), but a direct `isAllowed` consumer would have
  honored a blanket. Caught by
  `blankAndWildcardAreNeverStoredOrEligible`
  (`app/src/test/java/com/aurviz/agent/AllowAlwaysTest.kt`): `allow` now
  persists only plausible addresses, and eligibility re-checks. 14/14 green.
- **`allowAlwaysStore` declared after `init`.** `init` hydrates the store
  from prefs, so the property must precede it — compile error
  (`AgentViewModel.kt:227,235`) caught at build; moved above `init`.
- Tests: new `AllowAlwaysTest` (14 tests: allow, revoke/immediate, policy
  over allow, no blanket, loop parity). Full `:app:testDebugUnitTest`
  green (366 tests, 0 failures) plus `:app:assembleDebug`.
- Note: auto-approve without suspending contradicts ADR-0003's letter
  ("suspends the loop until the UI returns approve, edit, or reject") —
  permitted by spec #65 Q5; ADR amendment lands in #70, not here.

## 2026-11-24 — Issue #67 T2 scheduled hold plus bounded creation consent (`main`)

- **Working tree carried uncommitted work beyond the handoff list.**
  `HANDOFF.md §7` listed only `GLOSSARY.md`, `HANDOFF.md`,
  `architecture-deep-dive.html`, `docs/AURVIZ_EVOLUTION_PLAN.md`, but the
  tree also held ticket-1/2 edits (`Schedule.kt`, `ScheduleAlarm.kt`,
  `ScheduleDb.kt`, `AgentViewModel.kt`, new `ScheduleIds.kt`, later also
  `ScheduleWork.kt`, `ScheduleRuntime.kt` appearing mid-session). A full
  `cleanTestDebugUnitTest` exposed it: `AgentViewModel.kt:1044` referenced
  `onApprovedWrite`/`ScheduleSideEffect` without an import, so the clean
  build failed while the incremental build stayed green on cache. Verified
  #67 in isolation by stashing the foreign paths, then restoring them
  untouched — the #67 commit holds only `ScheduledRun.kt`,
  `ScheduledConsentTest.kt`, and this log.
- **Foreign hunk inside the ticket file.** `ScheduledRun.kt:46` carried the
  ticket-1 codec (`ScheduleIds.approvalId(nextId)`) interleaved with the
  #67 hunks, making the ticket depend on the uncommitted `ScheduleIds.kt`.
  Reverted that one line to HEAD (`"approval_$nextId"`) so #67 compiles
  and passes standalone; ticket-1 keeps its hunk on its own path.
- Tests: new `ScheduledConsentTest` (18 tests: allowlist reads, held
  writes, exact-match run, recipient/bulk/subject/body drift defer, policy
  deny defer, schedule-write never consented, cross-schedule isolation,
  double-fire + double-approve exactly-once, grant validation,
  cron/timezone edges, expired-token reconnect, revoke). Full
  `:app:testDebugUnitTest` green (384 tests, 0 failures). No UI touched,
  so no `assembleDebug` per the ticket rule.
- Note: the bounded exact-match exception contradicts ADR-0002 ("never
  executed") and ADR-0003 ("never holds the send tool") — flagged in
  `ScheduledRun.kt` per AGENTS.md; the amendment lands in #70, not here.

## 2026-11-24 — Issue #70 T5 terminology, receipts, and decision record close-out (`main`)

- **Gate-refusal string capitalized under an old assertion.** Canonicalizing
  `ToolRegistry.kt:166` to "requires Confirmation: confirm, edit, or reject…"
  broke `directExecuteRefusesGatedToolWithoutRunning`
  (`app/src/test/java/com/aurviz/agent/ConfirmationGateTest.kt:130,135`),
  which asserted lowercase `contains("confirmation")`. Caught on the first
  full-suite run (1 failure / 399); updated the assertion to the canonical
  `contains("Confirmation")` instead of reverting the string. Suite green
  after: 399 tests, 0 failures.
- **Case-sensitive receipt assert in the new test.** `scheduledAttemptIsHeld`
  (`app/src/test/java/com/aurviz/agent/ConfirmationReceiptTest.kt`) asserted
  `detail.contains("nothing ran yet")` against "Nothing ran yet." — failed
  on the capital N. Fixed with `lowercase()` before contains; 11/11 green.
- **Tree again held foreign uncommitted work beyond the handoff list**
  (ticket-1/2 paths, same set as #67 plus mid-session additions). Verified
  #70 in isolation by stashing those paths and restoring them untouched —
  the #70 commit holds only the files below. Residual known drift, logged
  not silently kept: code-internal `ApprovalDecision` / `ApprovalGate` /
  `NeedsApproval` / `ApprovalCard` / `approvalCardFor` identifiers stay
  (rename would churn ~15 test files against concurrent edits);
  user-visible + docs + comments now read Confirmation/PendingApproval.
  Follow-ups outside this ticket: `ScheduleWork.kt:222` "Approval needed"
  notification (foreign-modified file), Confirmation-card/held-list UI
  wiring to `receiptFor*` (ViewModel concurrently modified), a GLOSSARY
  Receipt term (GLOSSARY has pre-existing uncommitted mods — untouched).
- Tests: new `ConfirmationReceiptTest` (11 tests: allowed ×3, card/edited
  approved, denied ×3 with reason, held ×2, all-five explainer). `RedTeamTest`
  +4 (evil-forward via the real `runScheduledRoutine`, evil-with-consent
  still held, memory-poisoning hold → HELD receipt, foreground deny → DENIED
  receipt). Full `:app:testDebugUnitTest` green (399 tests, 0 failures).
  No Compose changes, so no `assembleDebug` per the ticket rule.
- ADR-0003 amended with the bounded exact-match exception (creation-time
  consent record, fire-time revalidation, drift defers); ADR-0002 given a
  one-line pointer to it. Both replace the absolute "never executes"
  wording instead of silently overriding it.
