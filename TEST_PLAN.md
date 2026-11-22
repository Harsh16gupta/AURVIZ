# Aurviz test plan

## How to run

- `export ANDROID_HOME=$HOME/Library/Android/sdk`
- `./gradlew :app:testDebugUnitTest` — ticket 37: **294 tests, 0 failures**
  (26 classes incl. `PerfTimingsTest`; 293 before this ticket).
- Reports: `app/build/test-results/testDebugUnitTest/*.xml`.

## Coverage by area (representative, not exhaustive)

| Area | Classes | What they prove |
|---|---|---|
| Agent loop | `AgentLoopTest` | multi-step + `tool_call_id` match, tool-error recovery, max-step abort, reject path, null-id generation, step/total timeouts, transport failure |
| Confirmation gate | `ConfirmationGateTest`, `ApprovalCardTest` | approve / edit / reject / timeout, edited-args revalidation, `execute()` gate-refusal, ungated reads run free |
| Prompt shaping | `PromptShapingTest` | system framing, source-tagged results, delimited memory section |
| Red team | `RedTeamTest` | evil-forward + memory-poisoning → foreground card / scheduled PendingApproval, never a send; policy denies even when gate approved |
| Email | `EmailTest`, `EmailSearchReadTest`, `EmailSendTest` | PKCE/auth forms, search/read tools, send MIME + policy seam |
| Web | `WebTest`, `WebToolsTest`, `WebBackgroundTest` | foreground bridge tools, background OkHttp/Jsoup path, domain allowlist |
| Memory | `MemoryTest` | keyword ranking, importance/recency boosts, overlap-beats-boosts guard, `markUsed`, offline FTS prefix fallback, migration 2→3 |
| Schedules | `ScheduleTest` | parse 12am/12pm, daily/weekly triggers, create/list/cancel tools |
| Scheduled worker | `ScheduledRunTest` | 5-tool read-only allowlist, writes → PendingApproval, idempotency (same key once, new fireTime reruns), auth-error Reconnect path |
| Scheduler hardening | `SchedulerHardeningTest` | boot/timezone re-arm, days intact, no periodic work, no receiver-thread DB, exact-alarm grant logic |
| Backup / battery / keys | `BackupRulesTest`, `BatteryDozeTest`, `LlmKeysTest`, `ProviderPickerTest` | no-backup rules, exemption prompt, vault-backed keys, fallback ordering |
| ViewModel wiring | `ViewModelLoopWiringTest`, `PlannerTest`, `ChatTimelineTest`, `VoiceInputTest`, `PendingApprovalTest` | loop→timeline, retry disclosure, notifications, voice/approval seams |
| Submission benchmark | `PerfTimingsTest` | 10-run medians for recall / prompt / registry + top-k optimization comparison |

## Device pass (human, not run ticket 37)

`docs/SCHEDULING_TEST.md`: 2-minute schedule, killed app, screen off,
`force-idle`, reboot re-arm — record device model + Android version.
`docs/PERF_TIMINGS.md`: on-device STT / first-token / end-to-end medians via
the Settings `planMs`/`toolMs`/`recallMs` readout; profiler memory
screenshot method.

## Known gaps

- #32 embeddings cut: no vector-recall tests by design.
- No emulator/device in CI here: Doze/OEM behavior is covered by unit gates
  plus the human pass, not by automated device tests.
