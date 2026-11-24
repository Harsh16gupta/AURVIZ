# Validation log

Every case where the AI was wrong and how it was caught. Add an entry with file:line evidence before moving on.

## 2026-11-24 — Issue #70 T5 close-out: both audit gaps wired (`main`)

- **Caught own mistake: first AgentUiState edit orphaned the class doc.**
  The helper insert replaced the `InteractionRecord` doc comment with the
  `rememberAddressFor` body, leaving the class head mangled
  (`AgentUiState.kt`, helper lines inside the data-class parens). Caught on
  immediate post-edit re-read; repaired by restoring the class head first
  and appending the helper after the class close. Lesson holds: multi-line
  edits need a post-edit read before moving on.
- **Settled receipts are optimistic at tap time, exact at record time.**
  `approvePendingSend`/`approvePendingSendEdited` stamp CARD/EDITED receipts
  (`AgentViewModel.kt:1353,1363`) from the decision + effective args before
  the gate resolves — same optimism as the old stamp (Approved at tap), now
  in canonical language. `approveEmail` stamps from the real gated execution
  (`:705`), which also fixes a mis-stamp: policy-denied sends previously
  recorded `ConfirmationStatus.Approved`; they now record Denied via the
  receipt outcome (`:711`). `rejectPendingSend`/`rejectEmail` stamp the
  synthetic cancelled_by_user DENIED (`:1374-1378`, `:726`).
- **Draft-deny cards lose the Remember Preference button, by construction.**
  The grant now derives from executed-step args
  (`rememberAddressFor`, `AgentUiState.kt:199-207`); a denied draft never
  executed, and `rejectEmail` records `steps = emptyList()`
  (`AgentViewModel.kt:728-734`) so a stale timeline can't offer a wrong
  address. Loop-path and direct-send approved cards keep the grant via the
  effective-args step (edited address included). No test pinned the old
  draft-deny grant; accepted tradeoff, flagged here.
- **Held rows lose per-recipient To/Subject/Body detail, per the audit.**
  `SchedulesScreen.kt:282-287` now renders `receiptForHeld(held)` title +
  detail (tool named, nothing-ran-yet), replacing the `approvalCardFor`
  derivation. The stored `held.reason` line below the card is kept as the
  schedule-context line. No model changes: `ConfirmationReceipt.kt` mapping
  + `ConfirmationReceiptTest.kt` matrix untouched.
- **Copy sweep extras beyond the audit list:** `EmailConfirmationSheet.kt:71`
  "before approving", `Email.kt:381` gate-refusal error "was not approved",
  and `docs/SCHEDULING_TEST.md:85` button name — all user-visible, same
  rule. Left untouched: ADR-0003:7 "approve, edit, or reject" prose (bullet 3
  verified — do not touch), historical logs, and all code-internal
  `ApprovalDecision` / `ApprovalGate` / `NeedsApproval` / `pending_approvals`
  identifiers per ADR 0003:22-25.
- Tests: 6 new `rememberAddressFor` pins in `CommandPlanUiTest.kt`
  (legacy prefix, receipt-title fallback, edited address, first-of-many,
  no-send null, non-address null) written red-first, green after. Full
  `:app:testDebugUnitTest --rerun-tasks` green (526 tests, 0 failures — up 6
  from 520). Secret scan clean on the diff.

## 2026-11-24 — Issue #70 T5 audit: two genuine gaps, ticket left open (`main`)

- **Bullet 3 verifies; the #67 audit claim holds.** `docs/adr/0002-workmanager-alarm-scheduling.md:8-11`
  carries the bounded exact-match exception (recipient set + template +
  owning Schedule, fire-time revalidated, drift defers), and the
  `docs/adr/0003-gmail-oauth-confirmation.md:9-25` amendment covers every
  element (exact recipient set + subject/body template scoped to owning
  `ScheduledSendConsent`, headless fire-time re-validation + SendPolicy,
  drift/policy-deny → `PendingApproval`, writes always defer, expired
  credentials → Reconnect prompt). No amendment delta needed.
- **Bullet 4 verifies on both paths.** `RedTeamTest.kt` pins
  `evilForwardForegroundYieldsCardNeverSend` (card-never-sends),
  `evilForwardScheduledYieldsPendingApprovalNeverSend` (hold-never-executes),
  plus the same pair for Memory poisoning
  (`memoryPoisoningForegroundYieldsCardNeverSend`,
  `memoryPoisoningScheduledYieldsPendingApprovalNeverSend`) and
  routine-runner/receipt-mapping variants. `PromptShapingTest.kt` pins the
  framing (tool-content-is-data, delimited `[memories]`, source tags).
- **Genuine gap 1 (bullet 1): GLOSSARY has no PendingApproval entry.**
  `GLOSSARY.md` defines `Confirmation` but `grep -i pendingapproval`
  returns nothing, and the Confirmation definition itself reads
  "Explicit user approval required ... until the user approves, edits, or
  rejects" (`GLOSSARY.md:40`) — the retired noun in the canonical
  definition. The #65 "Glossary already updated" claim is false on this
  tree, and user-visible "Approval" copy remains: `ScheduleWork.kt:237-238`
  notification ("Approval needed" / "need your approval"),
  `SchedulesScreen.kt:272` `Section("Approvals")`, button labels
  `CommandScreen.kt:676,707`, `SchedulesScreen.kt:299`,
  `ConfirmationCard.kt:47`, settled `ConfirmationCard.kt:82`
  ("Approved ·"), status lines `AgentViewModel.kt:755-756,802,1105,1112,1115,1420,1453`.
  Code identifiers (`ApprovalDecision` / `ApprovalGate` / `NeedsApproval`)
  correctly stay per the `0003:22-25` amendment — no renames.
- **Genuine gap 2 (bullet 2): receipts unrendered.** The five-outcome model
  (`ConfirmationReceipt.kt:17-23`) and `ConfirmationReceiptTest.kt` matrix
  (ALLOWED x3, CARD-APPROVED, EDITED-APPROVED, DENIED x3, HELD x2, plus
  `everyOutcomeExplainsItself`, each asserting no "approv" outside
  "pendingapproval") are done and green — but `grep receiptFor*` finds
  zero callers in `app/src/main`, and the file header admits wiring "is a
  UI follow-up": `ConfirmationCard` + held list
  (`SchedulesScreen.kt:282-299`) still render the old
  `ConfirmationStatus`/title/detail, not `receipt.title/detail`.
- Ticket #70 left open; no code changed, so no `:app:testDebugUnitTest`
  re-run (docs-only commit). Secret scan clean on the diff.

## 2026-11-24 — Issue #69 T4 unschedule fast-path Confirmation (`main`)

- **The audit was right; the fix mirrors the create preview.** The #69
  audit (98aae02) left one genuine gap: `handleUnschedule` disarmed on a
  typed/voice command with no Confirmation. Fixed by preview-gating it
  exactly like `handleScheduleCreate`: resolve via pure helpers, publish
  a Stop? card, disarm only on approve.
- **Missing imports cascade into a misleading type error.** Wiring
  `handleUnschedule` to `findSchedule`/`normalizeScheduleId`/
  `formatUnschedulePreview` failed `:app:compileDebugKotlin` with
  `Unresolved reference` plus a bogus `Any vs Schedule?` mismatch at
  `AgentViewModel.kt:1205` — the mismatch was error-recovery fallout,
  not a real type problem. Fixed with three explicit imports
  (`AgentViewModel.kt`, same explicit-import idiom as the other
  schedule helpers).
- **Screen inline-delete keeps its own confirm; no double-gate.**
  `SchedulesScreen.kt:223-239` already confirms Delete/Keep per row, so
  `unschedule(id)` (`AgentViewModel.kt`, screen path) still disarms
  immediately while the command path (`handleUnschedule`) previews.
  The two paths are documented at both sites so a later reader does not
  "unify" them into a double-confirm.
- Tests: 4 new pure-helper tests in `ScheduleTest.kt` (prefix
  normalize, bare/prefixed find, unknown → null, preview names
  id/instruction/time) written red-first, green after. Full
  `:app:testDebugUnitTest --rerun-tasks` green (520 tests, 0 failures —
  up 4 from 516). Secret scan clean on the diff.
- Review drove two cleanups: `findSchedule` → `findScheduleByRawId`
  (states the normalize + bare/prefixed contract), shared time shape
  `formatScheduleTime` for both previews, and one `disarmSchedule`
  helper for the approve + screen paths. Disputed and kept with
  precedent: no timeout on the manual card (same as `approveSchedule`),
  no edit step (cancel target fully determined; reject + retype is the
  edit path), screen inline row keeps its own Delete/Keep (explicit
  per-item Confirmation, not a second mechanism for the same entry
  point), `unschedule(id)` silent on unknown (post-confirm lost race,
  list already fresh).
- Glossary check: Schedule / Confirmation used per `GLOSSARY.md`;
  `cancel_schedule` toolLabel on the card names the Tool being
  confirmed. No ADR conflict (per-call Confirmation for schedule
  writes is #65/ADR-0003 direction).

## 2026-11-24 — Issue #69 audit: one genuine gap, ticket left OPEN (`main`)

- Audited #69 (T4 schedule writes + voice parity through judge) against the
  tree before writing code. Bullets 2-4 fully satisfied; bullet 1 PARTIALLY
  satisfied — the `unschedule` typed/voice command path disarms with no
  Confirmation. Ticket left OPEN; gap reported on the issue, nothing
  implemented pending scope sign-off.
- Bullet 1 (create/cancel require Confirmation; no silent arm/disarm):
  SATISFIED for the judge path — `ScheduleTools.kt:30-32`
  `create_schedule` MEDIUM + `requiresConfirmation`, `:100-102` same for
  `cancel_schedule`; kernel refuses gated Tools ungated
  (`ToolRegistry.kt:159-173`); loop routes every call through
  `executeGated` (`AgentLoop.kt:186-189`); typed create fast path is
  preview-gated (`AgentViewModel.kt:991-1036` `pendingSchedule` +
  `approveSchedule`/`rejectSchedule`); screen delete is inline-confirmed
  (`SchedulesScreen.kt:87,212-245` `confirmDeleteId`, added in #89).
  MISSING: `handleUnschedule` (`AgentViewModel.kt:1184-1200`) deletes the
  row plus cancels alarm/work (`:1193-1195`) with no Confirmation of any
  kind — reachable from typed text (`:529`) AND voice transcript (voice
  routes into the same `sendCommand`, `AgentNav.kt:467-471`), so a
  misrecognized "unschedule 2" silently disarms a routine. Every other
  destructive path (email send, schedule create, forget, screen delete)
  has a confirm step; this is the lone exception.
- Bullet 2 (voice parity): SATISFIED — voice transcript enters the single
  entry point (`AgentNav.kt:467-471` `vm.sendCommand(text)`), voice wins
  over typed (`VoiceInput.kt:38-39` `resolveCommandInput`), blank
  transcripts can't confirm (`AgentUiState.kt:86-87` `canConfirmVoice`).
  Tests: `ScheduleWriteConfirmTest.kt:132-146` voice drives identical
  gated outcomes, `:149-158` voice reject; `VoiceInputTest.kt:42-56`
  blank-voice fallback; `VoiceSheetStateTest.kt:38-40` confirm guard.
- Bullet 3 (reject/timeout without side effects): SATISFIED for the judge
  path — `ToolRegistry.kt:204-217` reject/timeout return
  `cancelled_by_user` without running; `Schedule.kt:161`
  `onApprovedWrite` returns `None` unless approved Success;
  `AgentViewModel.kt:1150-1182` side-effect sync acts only on approved
  Success (effective args, never the original call). Tests:
  `ScheduleWriteConfirmTest.kt:35-57` create reject/timeout arm nothing,
  `:60-83` cancel reject/timeout leave armed, `:86-96` approve disarms.
- Bullet 4 (approve/reject/timeout x create/cancel x typed/voice):
  SATISFIED at the gate seam — `ScheduleWriteConfirmTest.kt` whole file:
  create reject/timeout (`:35-57`), cancel reject/timeout/approve
  (`:60-96`), loop NeedsApproval for both (`:123-129` typed,
  `:132-146` voice-identical, `:149-158` voice reject); plus
  `ScheduleToolsTest.kt` timeout cases and `ApprovalGateTest.kt`
  fail-closed default confirmer. (The `unschedule` fast path has no
  approve/reject/timeout at all — same gap as bullet 1.)
- Fix sketch (not implemented): mirror the create preview — publish a
  `pendingUnschedule` + preview line in `handleUnschedule`, add
  `approveUnschedule`/`rejectUnschedule`, render in `CommandScreen`/
  `SchedulesScreen`; JVM tests for approve/reject/timeout-equivalent
  (reject path) on the fast path. Needs UX sign-off first since it adds
  a confirm step to an explicit command.
- Tests: full `:app:testDebugUnitTest --rerun-tasks` green (516 tests /
  48 files, 0 failures/errors), secret scan clean. No implementation
  commit beyond this log entry; #69 stays OPEN.

## 2026-11-24 — Issue #68 audit: already-implemented-but-open (`main`)

- Audited #68 (T3 allow-always Preference per recipient) against the tree
  before writing code: all 4 acceptance bullets already satisfied, no
  genuine gaps, no code changes. Prior art confirmed by behavior, not
  assumed: store + eligibility + Settings revoke path all traced end to end.
- Per-recipient scope, Settings revoke with immediate effect:
  `AllowAlways.kt:13-24` per-recipient doc, `:26-50`
  `InMemoryAllowAlwaysStore` (normalized set; allow refuses blank /
  wildcard / implausible `:36-43`; revoke `:46-48`), `:55-75`
  normalization + `allowAlwaysEligible` (exactly one plausible recipient).
  `AgentViewModel.kt:177-181` the confirm hook reads the same live
  instance, so allow/revoke apply to the next decision with no restart;
  `:234-246` `allowAlwaysAllow`/`allowAlwaysRevoke` mutate store +
  persist via `UiPrefs` + update state synchronously. Persistence
  `Haptics.kt:83-89`; Settings UI `SettingsScreen.kt:315-352` (revoke rows
  "takes effect immediately" `:328`, add field `:338-351`) wired in
  `AgentNav.kt:193-194`.
- Allowed skips card, unknown still asks: `AgentViewModel.kt:1348-1356`
  (`allowAlwaysEligible` returns `Approved` with no `openGate`; otherwise
  the card path `:1356-1373`). Grant path from settled receipts:
  `CommandScreen.kt:508-526` + `ConfirmationCard.kt:146-168` Remember
  Preference button. Tests: `AllowAlwaysTest.kt:56-77` eligible runs with
  no card shown, `:89-107` unknown held with card shown, `:205-234` loop
  auto-approves listed + holds unlisted.
- Bulk / evil-forward still denied: `AllowAlways.kt:6-8` doc (policy keeps
  the final word), `AgentViewModel.kt:1349-1351` hook comment;
  multi-recipient never eligible `AllowAlways.kt:70-71`; the gated path
  always re-runs `SendPolicy` (`ToolRegistry.kt:228-234` +
  `Email.kt:385-391`). Tests: `AllowAlwaysTest.kt:131-143`
  evil-forward denied when allowed, `:145-157` header-injection denied
  when allowed.
- Unit-test bullet: allow (`AllowAlwaysTest.kt:41-53`), revoke immediate +
  case-insensitive (`:111-127`, immediacy via shared live instance
  `AgentViewModel.kt:177-181`), policy-overrides-allow (`:131-157`), no
  blanket allow-all (`:159-201` multi-recipient / blank+wildcard /
  malformed / non-send never eligible).
- Tests: full `:app:testDebugUnitTest --rerun-tasks` green (516 tests /
  48 files, 0 failures/errors), secret scan clean. Closed #68 with audit
  comment, no implementation commit beyond this log entry.

## 2026-11-24 — Issue #67 audit: already-implemented-but-open (`main`)

- Audited #67 (T2 scheduled hold + bounded creation consent) against the
  tree before writing code: all 5 acceptance bullets already satisfied, no
  genuine gaps, no code changes.
- Reads run unattended; sends/schedule writes hold except exact-match
  consent: `ScheduledRun.kt:255-262` allowlist, `:406-444` `scheduledRegistry`
  read-only view (consent-aware `send_email` `:417-432`), `:530-539`
  headless confirmer (reads allow, send only via `isConsentedSend`),
  `:201-211` exact-match + `SendPolicy` gate. Tests:
  `ScheduledConsentTest.kt:51-78` reads unattended, `:83-114` writes held,
  `ScheduledRunTest.kt:63-85,98-151` allowlist + held sends.
- Drift defers: `ScheduledRun.kt:157-163` `consentMatches`,
  `:173-177` `consentPolicyAllows`, `:423-430` executor re-check,
  `:555-565` drift saved as `PendingApproval`. Tests:
  `ScheduledConsentTest.kt:147-169` recipient, `:172-196` bulk,
  `:199-238` subject/body, `:241-263` policy, `:267-317` schedule-write /
  cross-schedule scope.
- Held approve/reject exactly-once: `ScheduledRun.kt:75-86` claim-first
  approve, `:89-94` reject, `:54-57` in-memory atomic `take`,
  `ScheduleDb.kt:239-244` Room DELETE-count guard. Tests:
  `PendingApprovalTest.kt:76-97` approve + double-approve, `:110-141`
  reject + schedule-write doubled, `ScheduledConsentTest.kt:342-366`
  drift-held double-approve.
- Idempotent per fire time + reconnect: `ScheduledRun.kt:335-341`
  claim, `:595-607` `DuplicateSkipped`, `ScheduleDb.kt:172-185` IGNORE
  idempotency, `ScheduleWork.kt:152-164` unique REPLACE work,
  `:380-395` `isGmailAuthFailure`, `:473-489` Reconnect note,
  `:551-574` `AUTH_ERROR`. Tests: `ScheduledRunTest.kt:186-212`
  double-fire, `:256-268,335-357,401-455` store/Room/stale-lease,
  `:218-234` expired token; `ScheduledConsentTest.kt:322-338,439-466`
  consented double-fire + expired-with-consent.
- Unit-test bullet: read allowlist, writes-held, exact-match, drift,
  double-approve, cron/timezone edges all present —
  `ScheduleTest.kt:76-136` `nextFireTime`/timezone/`emptyDays` plus
  `ScheduledConsentTest.kt:403-434` consent-across-rearm.
- ADR check (#65 requirement): bounded-consent amendment already in
  `docs/adr/0002-workmanager-alarm-scheduling.md:7-11` and
  `docs/adr/0003-gmail-oauth-confirmation.md:9-25` — part of #67's scope,
  no separate ticket needed. Stale pointer noted, not fixed (audit-only
  scope): `ScheduledRun.kt:102-104` still says the amendment "lands in
  #70" though the ADR is already amended — comment-only drift, no
  functional gap. Glossary: Confirmation / PendingApproval / Schedule used
  canonically; no ADR conflict.
- Tests: full `:app:testDebugUnitTest --rerun-tasks` green (516 tests /
  48 files, 0 failures/errors), secret scan clean. Closed #67 with audit
  comment, no implementation commit beyond this log entry.

## 2026-11-24 — V2.1 drawer footer still clipped on gesture-nav device (PR #97 follow-up)

- **Wrong assumption: `navigationBarsPadding()` suffices for the drawer footer.**
  Shipped `Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()`
  in `SideDrawer.kt:62`, but on the tester's Realme (gesture navigation,
  `navigation_mode=2`) `WindowInsets.navigationBars.bottom` reads ~0, so the
  64.dp profile footer rendered half off-screen — confirmed by the user's
  screenshot and reproduced via `adb exec-out screencap` with the drawer open.
  Fixed with a gesture-aware bottom inset —
  `WindowInsets.navigationBars.union(WindowInsets.systemGestures).only(Bottom)`
  plus an 8.dp floor (`SideDrawer.kt:61-74`) — and re-verified with a second
  device screenshot showing the full footer above the bottom edge. Source guard
  added: `V2ShellPartATest` drawer test now asserts `systemGestures` is present.
- Tests: `testDebugUnitTest` green (516 tests, 0 failures); `assembleDebug`
  exit 0; release-installed on-device via `adb install -r` and screenshot-verified.

## 2026-11-24 — Issue #84 LLM seam 3/3 single-shot plan path (`main`)

- **Exhausted-vs-raw exception type on terminal failure.** After deleting
  `planWithFallback`, the `noSleepWhenNothingLeftToTry` and
  `authErrorStopsWithoutRetrying` tests still caught `LlmException`, but the
  messages fallback wraps terminal failures in `FallbackExhausted`
  (`ProviderPicker.kt`), so the raw `LlmException` never escapes. Caught by
  reasoning about the new throw sites before running: both tests now catch
  `FallbackExhausted` and assert on `tried` + `(cause as LlmException).kind`
  (`ProviderPickerTest.kt`). Suite green after.
- **NamedProvider type change breaks the ChatLlmProvider fake.**
  `NamedProvider.provider` is now `StructuredLlmProvider`
  (`ProviderPicker.kt`), so `ScheduledRunTest:363` passing a
  `FakeLlmProvider` (loop-seam `ChatLlmProvider.chat`, no `chatMessages`)
  failed `:app:compileDebugUnitTestKotlin` with argument-type mismatch.
  Fixed by giving that test its own inline `StructuredLlmProvider` double
  that records messages and returns the scripted answer
  (`ScheduledRunTest.kt:360-372`); the loop fake keeps its name and seam.
- **Scope check on leftover single-shot shapes.** `buildToolsRequestBody`
  (single user-text request builder) stays: only `PromptShapingTest` uses
  it to pin system-prompt framing, and production never called it (HTTP
  path always used `chatMessages`). `flattenChatMessages` stays as the
  renderer behind `renderLoopPrompt` (`AgentViewModel.kt:142`); its comment
  no longer claims a plan-only-provider purpose (`LlmProvider.kt:132`).
  Deleted: `LlmProvider` interface + `OpenAiChatProvider.plan()` +
  `FakeLlmProvider.plan()` + `planWithFallback` + flatten branch +
  `Scripted(structured)` flag. Zero bare `LlmProvider` / `.plan(` / exact
  `planWithFallback(` refs in `app/src` ( Dodge-substring grep verified).
- Tests: full `:app:testDebugUnitTest --rerun-tasks` green (454 tests,
  0 failures — down one from 455: the deleted
  `legacyPlanOnlyProviderStillWorksThroughMessagesFallback` pinned the
  removed flatten branch). AgentLoop multi-turn coverage untouched and
  green (`toolErrorIsStructuredSoModelRecovers`,
  `maxStepsAbortEmitsFailedWithPartialTimeline`,
  `rejectedConfirmationIsNotExecuted` in `AgentLoopTest`). Secret scan
  clean on the diff. Glossary check: Task Execution / Schedule /
  Confirmation / PendingApproval per `GLOSSARY.md`; no ADR conflict
  (messages-only seam is #81's decided contract).

## 2026-11-24 — Issue #83 LLM seam 2/3 contract shim names (`main`)

- **Substring grep over-counts the old names.** Plain
  `FallbackChatLlm|HeadlessChatLlm` matches all 19 `PinnedFallbackChatLlm`
  sites, so it cannot prove deletion. Caught by inspecting the hit list
  (`ChatFallback.kt:16`, `AgentViewModel.kt:1196`,
  `ScheduleWork.kt:125,132`, plus pinning/wire tests) — every hit carries
  the `Pinned` prefix. Negative check
  `grep -v PinnedFallbackChatLlm` returns zero, which is the real gate.
- **Shim deletion orphans imports and `open`.** Removing `FallbackChatLlm`
  (`AgentViewModel.kt:149-152`) left `import org.json.JSONArray`
  (`AgentViewModel.kt:95`) unused, and removing `HeadlessChatLlm`
  (`ScheduledRun.kt:431-434`) left `import org.json.JSONArray`
  (`ScheduledRun.kt:3`) unused; `open` on `PinnedFallbackChatLlm`
  (`ChatFallback.kt:16`) no longer has subclasses. Fixed by deleting both
  imports and sealing the class. `:app:compileDebugKotlin` +
  `:app:compileDebugUnitTestKotlin` green after.
- **Secret-scan literal vs BuildConfig reference.** Naive key-pattern grep
  flags `LlmProvider.kt:256` (`BuildConfig.META_API_KEY`) — a vault-backed
  reference, not a literal. Caught by reading the line; diff-content scan
  (`git diff -- app/src` for added key literals) is clean, which is the
  correct gate.
- Tests: full `:app:testDebugUnitTest` green (455 tests, 0 failures,
  0 errors). Foreground (`AgentViewModel.kt:1196`) and Worker
  (`ScheduleWork.kt:125,132`) construct `PinnedFallbackChatLlm` directly;
  same candidates order, same injected Tool views, no behavior change.
  Glossary check: Task Execution / Schedule / Confirmation / PendingApproval
  used per `GLOSSARY.md`; no ADR conflict (shim deletion is #81's decided
  follow-up).

## 2026-11-24 — Issue #82 LLM seam 1/3 expand (`main`)

- **Open property with private setter does not compile.** First
  `ChatFallback.kt:23` declared `override var lastProvider … private
  set` on the `open class PinnedFallbackChatLlm`; `:app:compileDebugKotlin`
  failed with "Private setters for open properties are prohibited".
  Fixed by making the interface contract read-only (`TriedChatLlm.tried:
  List` + `lastProvider: String?` at `ChatFallback.kt:11-14`) with private
  mutable backing (`seen`, `lastName`) and getter-only overrides —
  the Q5 tweak the user asked for, now enforced by construction.
- **Interface cast lagged the class move.** Collapsing
  `ProviderPinningTest` to `PinnedFallbackChatLlm` turned
  `scheduledFailureStillDisclosesTried` red (empty `providersTried`):
  `runScheduledRoutine` still cast to the concrete `HeadlessChatLlm`
  (`ScheduledRun.kt:565-566`), which the unified instance is not.
  Fixed by casting to `TriedChatLlm`; suite green after.
- **No behavior drift by construction.** Prod delta is move-only: the
  pin-first + union-tried + `FallbackExhausted` unwrap moved verbatim
  into `PinnedFallbackChatLlm.chat()` (`ChatFallback.kt:27-39`);
  `FallbackChatLlm` (`AgentViewModel.kt`) and `HeadlessChatLlm`
  (`ScheduledRun.kt:431`) are one-line subclasses; `renderLoopPrompt`
  delegates to `flattenChatMessages` (`LlmProvider.kt:137`). Shims and the
  single-shot path delete in #83/#84.
- Tests: full `:app:testDebugUnitTest --rerun-tasks` green (455 tests,
  0 failures). Secret scan clean on `app/src/main` + `app/src/test`.
  Foreign uncommitted work (`GLOSSARY.md`, `HANDOFF.md`, untracked docs)
  parked, not committed — the commit holds only the ticket files + log.

## 2026-11-24 — Issue #80 T3 thin Worker behind one fire seam (`main`)

- **Dirty-tree build failed on the T3 seam before any test ran.**
  `ScheduleWork.kt:67` called `buildRoutineChat(full)` (1 arg) against the
  3-param `buildRoutineChat(schedule, scheduleStore, db)` definition
  (`ScheduleWork.kt:122`), so `:app:compileDebugKotlin` failed with
  argument-type mismatch. Caught by compiling before testing; fixed by
  thinning the adapter to `buildRoutineChat(full: ToolRegistry)`
  (`ScheduleWork.kt:125-136`), which reuses the already-built registry
  instead of rebuilding Room stores inside the chat builder.
- **FireOutcome dropped the notification wording and blocked count.**
  First draft carried only `status/nextArm/run`, so the Worker recomputed
  the note via `buildResultNotification(schedule, run.result)` — losing the
  "(N request(s) need your Confirmation)" suffix plus retry disclosure, and
  hardcoding blocked=1 for every BLOCKED_WRITE. Caught on re-read of
  `ScheduledRun.kt:587-600` (resultText holds answer/error only; the suffix
  lives in the notification). Fixed by carrying
  `notification + blocked: List<PendingApproval>` through `FireOutcome`
  (`ScheduleRuntime.kt:11-17,34-35`); the Worker posts
  `outcome.notification` and `outcome.blocked.size` verbatim
  (`ScheduleWork.kt:78-86`).
- **Foreign uncommitted work parked, not committed.** Same condition as
  #67/#70/#72-#76: `GLOSSARY.md`, `HANDOFF.md` plus
  `architecture-deep-dive.html` / `docs/AURVIZ_EVOLUTION_PLAN.md` carry
  unrelated mods — untouched here; the commit holds only
  `ScheduleRuntime.kt`, `ScheduleWork.kt`, `ScheduleRuntimeTest.kt`, and
  this log.
- Tests: new `ScheduleRuntimeTest` (10 tests: double-delivery no-second-LLM,
  held-write, evil-forward red-team via fire, stale-reclaim single-winner +
  fresh-duplicate skip, missing-schedule null-arm, transport-throw FAILED
  with arm, Asia/Kolkata wall-clock re-arm, pure-runtime no-android-imports,
  Worker-delegates-to-fire, receivers-enqueue-only). Full
  `:app:testDebugUnitTest` green (455 tests, 0 failures; 445 at #79 + 10
  new). Secret scan clean on touched files (only pre-existing
  TokenVault/BuildConfig refs and test dummy strings).
- Glossary check: Schedule / PendingApproval / Confirmation / Task Execution
  used per `GLOSSARY.md`; no ADR conflict (fire seam + read-only allowlist
  + re-arm are #77's decided design).

## 2026-11-24 — Issue #76 legacy getImportantEmails deletion (`main`)

- **First AgentLoopTest edit targeted lines the replaceAll had already migrated.**
  Two `edit` calls with `replaceAll: true` converted every
  `call("call_N", "getImportantEmails", mapOf("max_results" to 5))`
  line first (`AgentLoopTest.kt:61,242,285,322-323` and the c1/c2 batch
  at `:389-390`); the follow-up edit for the error-recovery test then
  failed to match because its `call_2` line was already
  `search_emails`. Caught by the edit-tool mismatch error; re-read
  `AgentLoopTest.kt:87-96` and repaired with a comment-only edit
  (`Missing max_results` → `Missing query` plus the `call_1` line).
  Post-edit grep confirms the migration.
- **The negative-absence assertion was itself the last legacy reference.**
  First draft of `registryViewExposesAllAdapterTools`
  (`LlmProviderTest.kt:136`) pinned
  `assertTrue(!names.contains("getImportantEmails"))`, which keeps the
  deleted name in test sources and violates the ticket's zero-reference
  criterion by construction. Removed the line: absence is already pinned
  without naming it by the exact 13-Tool set assertion in
  `ToolRegistryTest.toolSchemasExposeAllToolsForLlm`
  (`ToolRegistryTest.kt:217`). Final `grep getImportantEmails app/src`
  is empty.
- **No behavior drift by construction.** Prod delta is pure deletion:
  the 38-line `getImportantEmails` registration block removed from
  `defaultToolRegistry` (`ToolRegistry.kt:256-293`), one entry dropped
  from `SCHEDULED_RUN_ALLOWLIST` (`ScheduledRun.kt:255-263`, now 5
  reads), `FakeLlmProvider` default script re-canned to the search Tool
  (`LlmProvider.kt:268`: `search_emails` with `query`
  `"is:important is:unread"`, `max` 5 — the Gmail-query-syntax
  equivalent of the legacy "unread important" fetch). The `readImportant`
  helper (`Email.kt:38`) stays: it is domain logic covered by
  `EmailTest`, not the Tool wrapper; the ticket deletes the Tool only.
  Fixture migration is mechanical (`max_results` → `query`+`max`) at
  every scripted call site across `AgentLoopTest` (9 sites including the
  max-step, null-id, timeout, and batch-timeout paths),
  `ToolRegistryTest` (schema/validation/exact-set pins),
  `LlmProviderTest` (request-build, wire-parse, canned-default, view
  pins), `ProviderPickerTest`, `ChatTimelineTest`,
  `PromptShapingTest` (renamed to `searchEmailsRowsCarrySourceTags`),
  and `ScheduledRunTest` (5-entry allowlist).
- **Foreign uncommitted work parked, not committed.** Same condition as
  #72/#73/#74/#75: seven tracked schedule-path edits plus four
  untracked files stashed/parked in /tmp before the test step and
  restored untouched after — the commit holds only the three prod
  files, the seven test files, and this log.
- Tests: full `:app:testDebugUnitTest --rerun-tasks` green (430 tests,
  0 failures; same count as #75, all migrated in place). Secret scan
  clean on all ten touched files.
  `spike/` and `docs/RESEARCH_PATTERNS.md` still name the legacy Tool;
  out of scope — the ticket's zero-reference criterion covers main and
  test sources (`app/src`).

## 2026-11-24 — Issue #75 schema-seam collapse to registry toolSchemas (`main`)

- **Scheduled wire test asserted the wrong contract.** First draft of
  `scheduledAdvertisedToolsComeFromTheScheduledRegistryView`
  (`ChatRolesWireTest.kt:143-162`) asserted the wire tools equal
  `SCHEDULED_RUN_ALLOWLIST` (6 reads). Full suite failed 1/430:
  expected the allowlist but was all 14 names. Re-read of
  `ScheduledRun.kt:372-419` showed `scheduledRegistry()` registers every
  Tool by name — non-allowlisted entries keep name/schema but are forced
  through the gate with neutered executors, so attempts become
  PendingApproval rows. Fixed the test to pin the real contract: wire
  names == `scheduled.toolSchemas()` names == `full.names()`, plus
  `send_email.requiresConfirmation` and allowlist-subset assertions.
  Re-run green (430 tests, 0 failures).
- **No behavior drift by construction.** Schema bodies moved verbatim
  into owning adapters: three Schedule bodies from `LlmProvider.kt:44-84`
  into `ScheduleTools.kt:10-90` (inline `JSONObject`, same
  names/descriptions/required sets); two Memory bodies from
  `Memory.kt:197-225` into `MemoryTools.kt:12-64` (deleted from
  `Memory.kt`, which now imports no JSON); legacy demo body from
  `LlmProvider.kt:27-38` into `ToolRegistry.kt:255-276` (kept until #76
  deletes the Tool). Deleted `demoToolSchema`, `scheduleToolSchema`,
  `listSchedulesToolSchema`, `cancelScheduleToolSchema`, `allToolSchemas`
  (`LlmProvider.kt:26-91`); `OpenAiChatProvider.plan()` now sends an
  empty tools array (`LlmProvider.kt:228-239`) since production uses the
  `chatMessages()` path. All three `tools` defaults that pointed at the
  hardcoded list now default to empty and require explicit injection:
  `planWithFallbackMessages` (`ProviderPicker.kt:93-98`),
  `HeadlessChatLlm` (`ScheduledRun.kt:436-441`), `FallbackChatLlm`
  (`AgentViewModel.kt:171-174`); both production sites already inject the
  view (`AgentViewModel.kt:1257`, `ScheduleWork.kt:91-96`). Grep over
  `app/src` confirms zero remaining references to the deleted builders.
- **Foreign uncommitted work parked, not committed.** Same condition as
  #67/#70/#72/#73/#74: an unrelated in-progress schedule refactor (tracked
  edits + untracked `ScheduleIds.kt`/`ScheduleRuntime.kt`) was stashed
  before the work and stays stashed — the commit holds only the schema
  collapse, its tests, and this log.
- Tests: updated `LlmProviderTest` (registry-view pins replacing the four
  deleted-builder tests, plus a no-hardcoded-default pin), extended
  `ChatRolesWireTest` (scheduled-view wire pin alongside the existing
  foreground pin). Full `:app:testDebugUnitTest` green (430 tests, 0
  failures; 428 at #74 + 2 new). Secret scan clean (no key/secret
  patterns in touched files).

## 2026-11-24 — Issue #74 Memory + Web adapter extraction (`main`)

- **No behavior drift by construction.** The two Memory builders moved
  verbatim from `ToolRegistry.kt:291-346` to `MemoryTools.kt:10-62`
  (`saveMemoryTool`, `searchMemoryTool` plus the `memoryTools()` list);
  the five Web builders moved verbatim from `ToolRegistry.kt:353-552`
  to `WebTools.kt:14-140` (`openUrlTool`, `getPageTextTool` with its
  background fallback, `clickTool`, `scrollTool`, `extractTool` plus the
  `webTools()` list and the `registerWebTools` wrapper). `defaultToolRegistry`
  now registers the `memoryTools()` list (`ToolRegistry.kt:293-295`) and the
  `webTools()` list (`ToolRegistry.kt:301-303`) instead of inline blocks.
  Existing pins unchanged: `MemoryTest` (save dedupe, ranked recall,
  FTS fallback), `WebToolsTest` (five schemas, allowlist, timeout/
  load-failed retryable), `WebBackgroundTest` (headless serves scheduled
  runs, foreground bridge wins), `ScheduledRunTest`/`ScheduledConsentTest`
  (read-only allowlist, held writes).
- **Foreign uncommitted work parked, not committed.** Same condition as
  #67/#70/#72/#73: seven tracked schedule-path edits plus four untracked
  files stashed/parked in /tmp before the test step and restored untouched
  after — the commit holds only `MemoryTools.kt`, `WebTools.kt`,
  `ToolRegistry.kt`, `MemoryToolsTest.kt`, `WebAdapterTest.kt`, and this log.
- Tests: new `MemoryToolsTest` (7 tests: adapter order, LOW/ungated flags,
  save+ranked recall through the kernel seam, blank-source default,
  max_results trim, offline non-retryable Failure, schema view) and new
  `WebAdapterTest` (9 tests: adapter order, LOW/ungated flags, open+text
  and click/scroll/extract through the kernel seam, background fallback
  serving scheduled runs, foreground-only retryable failures otherwise,
  missing bridge+loader retryable Failure, blocked non-retryable, schema
  view). Full `:app:testDebugUnitTest` green (428 tests, 0 failures;
  412 at #73 + 16 new). Secret scan clean on all touched files.
- **Review follow-up (code-review skill).** Standards: no hard violations;
  one judgement-call smell (Speculative Generality on a `registerWebTools`
  back-compat wrapper kept in the new file) — removed, so the default
  builder registers the `webTools()` list directly like the other three
  adapters; full suite re-run green (428, 0 failures). Spec: the "kernel
  still owns domain logic" finding points at the legacy `demoToolSchema`
  email block (`ToolRegistry.kt:255-283`), which #71 explicitly defers to
  the standalone deletion in #76 — out of scope here, not a #74 gap.

## 2026-11-24 — Issue #73 Schedule adapter extraction (`main`)

- **Second edit mangled a line it should have deleted.** While removing
  the leftover cancel block, an intermediate edit changed
  `ToolRegistry.kt:351` to `name = cancelFn.getString("name"),
  description = ...` on one line instead of deleting the block. Caught on
  re-read before compiling; the follow-up edit deleted
  `ToolRegistry.kt:348-370` outright, leaving only
  `registerWebTools(registry, webBridge, backgroundWebLoader)` after the
  memory blocks. Post-edit read verified the seam.
- **Test asserted on the wrong receiver.** First `ScheduleToolsTest`
  draft wrote `gated.error` after `gated.result as ToolResult.Failure`
  (`ScheduleToolsTest.kt:134-136`), which fails compile since `GatedExecution`
  carries no `error`. Fixed to `val failure = gated.result as
  ToolResult.Failure` before asserting `contains("sched_999")`,
  `contains("list_schedules")`, and `retryable`.
- **No behavior drift by construction.** The three Tool builders moved
  verbatim from `ToolRegistry.kt:285-344,403-426` to `ScheduleTools.kt`
  (`createScheduleTool`, `listSchedulesTool`, `cancelScheduleTool` plus the
  `scheduleTools()` list); `defaultToolRegistry` now registers the
  `scheduleTools()` list (`ToolRegistry.kt:285-289`) instead of three
  direct calls. Existing pins unchanged: `ScheduleWriteConfirmTest`
  (reject/timeout disarm nothing), `SchedulerHardeningTest`
  (cron/timezone edges), `ScheduledConsentTest`/`ScheduledRunTest`
  (read-only allowlist, held writes).
- **Foreign uncommitted work parked, not committed.** Same condition as
  #67/#70/#72: seven tracked schedule-path edits plus four untracked
  files stashed/parked in /tmp before the red step and restored untouched
  after — the commit holds only `ScheduleTools.kt`, `ToolRegistry.kt`,
  `ScheduleToolsTest.kt`, and this log.
- Tests: new `ScheduleToolsTest` (7 tests: adapter order, risk/gate
  flags, CRUD through the kernel seam, per-call Confirmation
  refuse/reject/timeout/approve plus cancel-reject/approve, unknown-id
  guidance, offline non-retryable Failure, schema view). Full
  `:app:testDebugUnitTest` green (412 tests, 0 failures; 405 at #72 + 7
  new). Secret scan clean on all touched files.
- **Review notes (code-review skill, this ticket).** Standards: 1 minor —
  `arms alarms` / `routine` comments carried verbatim from the registry;
  GLOSSARY canonical is Schedule. Kept verbatim per the #71
  behavior-preserving-move rule and #72 precedent; schema descriptions
  live in `LlmProvider.kt`, out of scope until #75. Spec: no gaps, no
  creep; one cosmetic — advertised `toolSchemas()` order is now
  create/list/cancel contiguously (cancel previously registered after the
  memory Tools); executors identical, no order-sensitive test pins order.

## 2026-11-24 — Issue #72 Gmail adapter extraction (`main`)

- **Foreign uncommitted work broke the build, not the ticket.** First
  targeted run failed in `AgentViewModel.kt:1044+` (unresolved
  `ScheduleSideEffect`/`scheduleId`) from concurrent uncommitted
  schedule-path edits — the same foreign-tree condition logged under
  #67/#70. Verified #72 in isolation by stashing the seven foreign
  tracked paths and parking the four foreign untracked files in /tmp,
  then restoring them untouched — the commit holds only
  `GmailTools.kt`, `ToolRegistry.kt`, `GmailToolsTest.kt`, and this log.
- **No behavior drift by construction.** The three Tool builders moved
  verbatim from `ToolRegistry.kt:238-358` to `GmailTools.kt:10-112`;
  `defaultToolRegistry` now registers the `gmailTools()` list
  (`ToolRegistry.kt:234-238`) instead of three direct calls. Existing
  pins unchanged: `RedTeamTest.evilForwardForegroundYieldsCardNeverSend`
  (injected forward yields a Confirmation card, never a send),
  `EmailSendTest.plainExecuteRefusesWithoutGateApproval` (gate-refusal),
  `EmailSearchReadTest` source-tag rows.
- Tests: new `GmailToolsTest` (6 tests: adapter order, risk/gate flags,
  search+read through the kernel seam, per-call Confirmation
  refuse/reject/approve, offline non-retryable Failure, schema view).
  Full `:app:testDebugUnitTest` green (405 tests, 0 failures; 399 at
  #70 + 6 new). Secret scan clean on all touched files.

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

## 2026-11-24 — Issue #78 T1 unify Schedule id codec (`main`)

- **Codec code predates its tests.** `ScheduleIds.kt` plus the Room/InMemory
  schedule and `ScheduleAlarm.kt` codec hunks arrived in the working tree
  from the prior schedule-runtime session, so the new `ScheduleIdsTest`
  (9 tests) passed on the first run — red was not observed for those
  paths. The remaining implementation is behavior-preserving unification,
  not behavior change: `ScheduledRun.kt:46` hardcoded `"approval_$nextId"`
  (the exact line #67 reverted to keep its commit standalone) now reads
  `ScheduleIds.approvalId(nextId)`; `InMemoryScheduleStore.delete` and
  `InMemoryPendingApprovalStore.take` gained codec guards
  (`schedulePid`/`approvalPid` null → no-op) mirroring the Room adapters;
  the dead `RoomPendingApprovalStore.approvalId` private wrapper is gone.
  Verified `git diff` per file that `ScheduleDb.kt`, `ScheduleAlarm.kt`,
  and `ScheduledRun.kt` hunks are T1-pure before staging.
- **Dirty tree does not compile: foreign T2/T3 hunks break the clean
  build.** `AgentViewModel.kt` (T2, #79) references `onApprovedWrite` /
  `ScheduleSideEffect` and `ScheduleWork.kt` (T3, #80) calls
  `buildRoutineChat(full)` against a 3-param definition — both fail
  `:app:compileDebugKotlin`. Verified T1 in isolation by stashing those
  two paths and moving untracked `ScheduleRuntime.kt` aside (restored
  afterwards untouched). In that tree the new tests plus
  `ScheduleTest`/`PendingApprovalTest`/`ScheduledRunTest` are green.
- **One pre-existing failure belongs to T2, not T1.**
  `ScheduleTest.loopCreateArmsAndCancelDisarmsOnlyOnApprovedSuccess`
  (`ScheduleTest.kt:278`) expects the legacy 3-arg
  `loopScheduleSideEffect("create_schedule", …)` → ARM, but the in-tree
  uncommitted T2 hunk routes through `onApprovedWrite` with empty args
  (no id) → null. Untouched here; #79 owns the test update with its
  `onApprovedWrite` Arm/Disarm/None coverage.
- Tests: new `ScheduleIdsTest` (codec round-trips ×2, malformed/wrong-
  prefix rejection ×2, InMemory schedule days-preserving round-trip +
  malformed-delete no-op, InMemory approval codec round-trip + malformed-
  take no-op, Room approval malformed-take no-op). Room schedule
  round-trip incl. days stays pinned in `ScheduleTest`
  (`roomStoreCrudRoundTripsIds`, `roomStoreDeleteIgnoresMalformedId`).
  Full `:app:testDebugUnitTest` green on the exact T1 commit content
  (verified in a scratch worktree at HEAD plus the T1 files only:
  439 tests, 0 failures, 0 errors). No UI touched, so no `assembleDebug`
  per ticket rule.
- Glossary check: Schedule / PendingApproval used per `GLOSSARY.md`;
  no ADR conflict (codec unification is #77's decided design, no migration).

## 2026-11-24 — Issue #79 T2 single approved-write side-effect (`main`)

- **Prior-session T2 hunks broke the dirty-tree build.** `AgentViewModel.kt`
  called `onApprovedWrite`/`ScheduleSideEffect` with no import
  (`AgentViewModel.kt:70-71` imported the now-unused
  `loopScheduleSideEffect`/`LoopScheduleSideEffect` instead), so
  `:app:compileDebugKotlin` failed and the suite could not run. Fixed by
  swapping the two imports; the `scheduleId` errors at `1068/1078` were
  the same missing import (smart-cast on `effect`), not a second defect.
- **Legacy test pinned the old contract.** `ScheduleTest:278` asserted the
  3-arg `loopScheduleSideEffect("create_schedule", …)` → ARM with an
  id-less result; under the owned seam an id-less approved create arms
  nothing. Red observed on the old assertion (then a compile block
  before the import fix). Per review, the whole shim family was deleted
  instead of re-pinned, so this test was removed and its assertions
  folded into the `onApprovedWrite` block below.
- **Both ViewModel paths delegate, no mirrors left.** `git diff` confirms
  `syncAlarmsForApproved` + `syncLoopScheduleSideEffect`
  (`AgentViewModel.kt:1043-1090`) call `onApprovedWrite` with real
  toolName/args/approved/result; grep finds no `"create_schedule"` /
  `"cancel_schedule"` literals left in the file. Adapter executes:
  Arm → `registerRoutineAlarm`, Disarm → `cancelRoutineWork` +
  `cancelRoutineAlarm`.
- **Review drove shim deletion.** Both review axes flagged the
  `loopScheduleSideEffect` shims: the 3-arg cancel fallback (DISARM with
  empty args) contradicts "missing target disarms nothing", the `ToolCall`
  overload has zero callers, and `LoopScheduleSideEffect` vs
  `ScheduleSideEffect` is a dual vocabulary. Deleted the enum, both
  overloads, and the legacy 3-arg test (its assertions live on in the 7
  `onApprovedWrite` tests); also fixed the stale `// Ticket 2:` comment
  to `#79 T2`. `grep` confirms no `LoopScheduleSideEffect` /
  `loopScheduleSideEffect` references remain (the ViewModel's private
  `syncLoopScheduleSideEffect` is a different function and stays).
- Tests: new `onApprovedWrite` block in `ScheduleTest` (7 tests: Arm on
  id, None on missing id/target, approved=false, non-Success, send/read
  names). Full `:app:testDebugUnitTest` green in the T1+T2 isolation
  tree (ScheduleWork T3 stashed, `ScheduleRuntime.kt` aside: 445 tests,
  0 failures). No UI touched, so no `assembleDebug` per ticket rule.
- Glossary check: Arm/Disarm follow the spec wording; no ADR conflict
  (owned-seam delegation is #77's decided design).

## 2026-11-24 — Groq provider removed from the codebase

- **Groq is gone from providers, keys, UI, and build config.**
  `ProviderPicker.kt` no longer declares `groqKey`/`groqModel`, the
  `resolveProvider("groq")` branch, or the `groq` slot in the fallback
  order (now `preferred → deepseek → meta → gemini`);
  `LlmProvider.kt` drops `GROQ_BASE_URL` and the `"groq"` arm of
  `Providers.default()`; `TokenVault.kt`/`LlmKeys` drop `groqKey`
  (`clearLlmKeys` still removes the legacy `llm_groq_key` pref so
  upgrading installs shed the orphaned entry);
  `AgentViewModel.saveLlmKeys` is now
  `(geminiKey, deepseekKey, metaKey)`; `AgentUiState.groqKeySet` removed;
  `CommandScreen.kt` provider sheet and `SettingsScreen.kt` key rows no
  longer list Groq; `app/build.gradle.kts` and `local.properties.sample`
  drop `GROQ_API_KEY`/`GROQ_MODEL`; `spike/t1_live_check.py` checks Gemini
  only. Tests re-pinned from `groq` to `deepseek` as the fallback fixture
  (`ProviderPickerTest`, `ProviderPinningTest`,
  `ViewModelLoopWiringTest`, `LlmKeysTest`).
- **Docs amended, history kept.** ADR-0004 re-amended (title, Status,
  fallback order) instead of silently contradicting it; README,
  ARCHITECTURE, gaps, SECURITY, SCHEDULING_TEST, AURVIZ_EVOLUTION_PLAN,
  and spike docs updated. Historical records left intact:
  `docs/research/0001-llm-android-stack.md` (§2 Groq API notes),
  `docs/VALIDATION_LOG.md` prior entries, `RESEARCH_PATTERNS.md`
  retry-after example.
- Glossary check: no glossary term for Groq; remaining providers
  (Gemini/DeepSeek/Meta) match ADR-0004 as amended. Full
  `:app:testDebugUnitTest` required after this change.

## 2026-11-24 — Ticket #86 (primitives + nav chrome) self-review catches
- Wrong assertion drafted in `app/src/test/java/com/aurviz/agent/ToolLabelMapperTest.kt`
  (`assertEquals("Click", toolLabel("click").let { "Select item" })` tautologically
  failed); rewrote as `assertEquals("Select item", toolLabel("click"))` before the
  first test run. Lesson: never wrap the value under test in an identity lambda.
- Fallback casing drift: first `toolLabel()` fallback title-cased every word
  ("Unknown Tool Id"), contradicting the spec's sentence-case examples
  ("Send email", "Create schedule"); fixed to capitalize the first word only in
  `app/src/main/java/com/aurviz/agent/ToolLabels.kt`. Caught by the new unit test.
- Accidental import deletion: an edit removing `statusBarsPadding` from
  `CommandScreen.kt` also dropped the still-used `size` import; restored
  immediately after re-reading the file. Lesson: verify each import removal
  against remaining usages (confirmed via failing-compile-risk scan).

## 2026-11-24 — Ticket #87 (Command + Composer + Plan cards) self-review catches
- Dropped binding during ActiveRunSection rewrite: an edit replacing the
  timeline loop in `CommandScreen.kt` also removed
  `val pendingCall = state.pendingApproval`, leaving the `if (pendingCall !=
  null ...)` block unbound. Caught by re-reading the edited region
  (`CommandScreen.kt:455-459`) before compiling; restored the binding and
  routed step rendering through `visiblePlanSteps()`. Lesson: re-read the
  full hunk after every multi-line edit, not just the replaced lines.
- Scope guard on TimelineStep extension: adding `toolName`/`argsSummary`
  fields risked breaking the pinned `ChatTimelineTest` raw-title asserts
  (`"Tool 1 of 2: search_emails"`). Kept `buildTimeline` titles
  byte-identical and mapped labels only in the pure `stepDisplayTitle()`
  renderer — `ChatTimelineTest` (6 tests) green unchanged.
- Copy-risk on settled receipts: `onRememberPreference` is only wired when
  the settled title matches our own `"Send email to <addr>"` format
  (`CommandScreen.kt` InteractionItem); non-email settled cards show Copy
  only rather than a dead button. SchedulesScreen cards pass neither
  callback — unchanged for #89.

## 2026-11-24 — Ticket #88 (Browser empty state + synced chrome + inspector) self-review catches
- Text component has no `textAlign` param (`components/Text.kt:14-22` wraps
  BasicText): first BrowserScreen draft passed `textAlign =` to three
  empty-state Texts; fixed to `style.copy(textAlign = TextAlign.Center)` with
  `fillMaxWidth()` before compiling. Lesson: check shared-primitive
  signatures before using new params.
- Empty-state gating is structural, not alpha: `AndroidView` (the real
  WebView) is only composed when `state.webPage != null`
  (`BrowserScreen.kt`); a null page renders a theme-aware `FeedCard` so
  Midnight never flashes white. `WebViewHolder.applyBackground()` re-applies
  `Theme.colors.background` on every attach/update.
- Tab rename Extracted -> Text keeps glossary-clean copy; "Save to Memory"
  note stamps the source URL via pure `browserSaveNote()` seam
  (`BrowserChromeUi.kt`), covered by `BrowserChromeUiTest` (6 tests).
  Full gate: 474/474 green + assembleDebug green.

## 2026-11-24 — Ticket #89 (Schedules + Memory polish) self-review catches
- `Text` primitive takes `String` only (`components/Text.kt:14`): first
  MemoryScreen draft passed an `AnnotatedString` for bold search-match
  highlights; fixed to foundation `BasicText` with the merged body style
  (`MemoryScreen.kt`, `highlightedText()` fed by pure `matchRanges()` in
  `Memory.kt`, JVM-tested). Same lesson as #88: check primitive signatures
  before compiling.
- `semantics { contentDescription = ... }` needs the explicit
  `androidx.compose.ui.semantics.contentDescription` import — `Role`/`role`
  imports are not enough (`components/Switch.kt:62`). Caught at compile.
- Removed the leftover #86 raw-cron seam entirely: `formatScheduleCron()`
  deleted from `AgentViewModel.kt` (no other callers) and replaced with
  `firesIn()` + `formatRunDuration()` pure helpers; schedule cards show
  human cadence + live 30s-tick countdown, never cron strings.
  Lossy `restoreMemory`/`updateMemory` (delete+re-save dropped category,
  importance, useCount) now round-trip via `restoreMemoryRow()`
  (`Memory.kt`), covered by `ScheduleMemoryPolishTest` (11 tests).
  Full gate: 485/485 green + assembleDebug green.

## 2026-11-24 — Issue #90 Settings density + auto-select + telemetry (`feat/ui-batch-b`)

- **Edit merged comment into `fun` line, breaking compilation.** A comment-only
  edit in `ProviderPicker.kt` dropped the newline before `fun resolveProvider`,
  producing `...never crashes.fun resolveProvider(...)` and 5 compile errors at
  `ProviderPicker.kt:140-161`. Caught by `./gradlew testDebugUnitTest` failing
  in `kaptGenerateStubsDebugKotlin`; fixed by restoring the newline.
- **Duplicate-context edits need disambiguation.** Two identical
  `perf.copy(planMs = ...)` blocks (Answered vs Failed) rejected a context edit
  as ambiguous; fixed by including the distinct `statusLine` line in each
  `oldString`. Both now route through `recordRun(..., success = true/false)`.
- **Pre-existing copy note:** `AgentUiState.kt:158` comment says "recent run
  history" — code comment only, never rendered; no GLOSSARY UI-copy violation.
  Settings UI copy verified free of History/chatbot/assistant/cron-job/
  dictation/job-run and of `.alpha()` on settled cards.

## 2026-11-24 — Issue #93 Final contrast / tap-target / glossary QA (`feat/ui-batch-b`)

Per-screen/sheet audit, Midnight + Daylight (code inspection; no PNGs needed):

- **Command**: Sessions Menu icon + 56dp New-session row; starter rows
  48dp min (`StarterRow.kt:40`); Composer pill 48..56dp with 48dp
  Send/Stop/Voice actions (`Composer.kt`); Plan steps expandable, single
  error row + Retry; settled text on `onBackgroundMuted`, never dimmed
  alpha. Glossary: Session / Interaction / Confirmation / Tool labels via
  `toolLabel()`, zero raw snake_case.
- **Browser**: native theme-aware empty card (no white WebView); 52dp chrome
  row (Back / Forward / URL pill / Reload all 48dp hit); 2dp Electric Blue
  progress; 56dp error banner + Retry; 52dp inspector link rows; Save to
  Memory stamps URL. No banned words in chrome copy.
- **Schedules**: human cadence + live Fires-in countdown (never raw cron);
  Switch 56x48dp hit (`Switch.kt:53`); footer outline Run + inline Delete
  row; 64dp Recent-Run rows with formatted durations. No alarm/timer/cron
  wording in UI copy.
- **Memory**: lossless restore, title-case chips, bold match highlights via
  `BasicText`; distinct empty states; bottom-anchored Undo (Trash icon);
  48dp add action. No History/cache/log wording.
- **Settings**: Appearance & Feedback → Connections → Telemetry →
  Confirmation Rules → Permissions order; 52dp provider disclosure rows +
  masked key-editor sheet; human-case rule labels; On-time Schedules (Bell)
  + Background running (DeviceMobile) + single battery accordion. 5th tab
  stays `Settings`; Preference used inside copy.
- **Voice/Email/Forget sheets**: 520–560dp Ramble hero, 48dp transcript
  + 56dp Pause/Confirm (disabled on blank); Proton hairline stack, 48dp
  Confirm/Reject; stacked 48dp Delete/Keep, maxLines=3 previews + N-more.
  All buttons 48dp+ in both themes.
- **Contrast**: Midnight `onBackgroundMuted 0xA8F5F5F5` / Daylight
  `0xD61C1C1C` (`Theme.kt:62,93`); `edge ~12%` both themes; settled cards
  use muted tokens. Remaining `.alpha()` calls are disabled-state dimming
  on buttons only (`IconButton.kt`, `Button.kt`, `OptionRow.kt`,
  `VoiceCommandSheet.kt:206`), never on settled cards.
- **Fixes this ticket**: `IconButton` Medium 40→48dp visual; Small keeps
  32dp visual circle inside a transparent 48dp hit box (`IconButton.kt`);
  Composer Stop 40→48dp + pill v-padding 6→4dp so 48dp actions fit the
  56dp max (`Composer.kt`); attachment dismiss offset re-based 10→18dp so
  the badge renders pixel-identical (`AttachmentChip.kt`); `AgentUiState.kt`
  comment "recent run history" → "recent Task Execution runs".
- Full gate: testDebugUnitTest **503/503 green** (47 files, 0 failures) +
  assembleDebug green. Secret scan clean. One commit.

## 2026-11-24 — V2 flagship shell Part A (feat/ui-v2-shell)

- **Suspend DAO needs room-ktx.** Task spec asked for `suspend fun listBySession/deleteSession` in `InteractionDb.kt:36-43`, but the module only depended on `room-runtime` — `kaptDebugKotlin` failed with "To use Coroutine features, you must add `ktx` artifact" (`InteractionDao.java:20,26`). Caught by running `./gradlew testDebugUnitTest`. Fixed by adding `implementation("androidx.room:room-ktx:2.6.1")` (`app/build.gradle.kts`); Room DB stays at version 6, no migration.
- **CommandScreen Alpha import.** Replaced the `40.dp` hit-target `IconButton` top bar with `Box(...).alpha(...)` for the disabled `agentRunning` dim (`CommandScreen.kt`) — needed explicit `import androidx.compose.ui.draw.alpha`; verified via `compileDebugKotlin` green.
- Tests: `:app:testDebugUnitTest --rerun-tasks` green (516 tests, 0 failures incl. new `V2ShellPartATest` 13 tests) + `assembleDebug` green (EXIT:0). Secret scan clean (no keys in diff). Glossary check: Session/Schedule/Confirmation/Task Execution/Preference/Memory/Interaction used; no History/Chat/Alarm/Cron/raw `send_email` in new UI strings.

## 2026-11-24 — T2 CommandScreen IME fix & flagship polish (#100)

- **Over-broad source-guard assertion (own mistake).** New `FlagshipT2CraftTest.onboardingUsesLargeTouchTargetsAndHairlineCards` asserted `assertFalse(text.contains(".border(1.dp, Theme.colors.edge"))` (`FlagshipT2CraftTest.kt:102`), which also matches the intentional 1.dp `Theme.shapes.field` wells on the two `BasicTextField` inputs (`OnboardingScreen.kt:341,447`) — the ticket only thins *card* borders to 0.5.dp. Caught by `testDebugUnitTest` (1 failure). Fixed by narrowing the guard to `".border(1.dp, Theme.colors.edge, Theme.shapes.medium)"`.
- **Dead IME imports.** Removing the duplicate `.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))` from the Composer wrapper (`CommandScreen.kt:297`) orphaned the `WindowInsets`, `ime`, `navigationBars`, `union`, `windowInsetsPadding` imports; removed them to keep the file warning-clean.
- No ADR contradiction (ADRs 0001–0005 cover memory/scheduling/gmail/LLM/loop, not UI). Glossary check: Session/Memory/Confirmation/Task Execution/Preference used; "Workspace" sheet title per ticket spec, not a glossary term; zero "More options". Stock foundation only, existing 55 Icons only, no backend/Room edits.
- Full gate: testDebugUnitTest **543/543 green** (0 failures, incl. new `FlagshipT2CraftTest` 8 tests + updated `V2ShellPartATest`) + `assembleDebug` green. Secret scan clean. One commit.
