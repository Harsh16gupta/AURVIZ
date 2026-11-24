# Scheduling test record (ticket 37)

Date: 2026-11-23. Commit: `f251cf2` worktree `ticket/37-submission`.
Recorder: submission agent, headless Linux/macOS container with Android SDK but **no device attached**.

## Environment

- `~/Library/Android/sdk/platform-tools/adb devices` → `List of devices attached` (empty, exit 0).
- `adb` is not on `PATH`; the platform-tools binary was invoked directly.
- Android version on device: **N/A — no device was attached in this environment, so no version can be reported.**
- Nothing below fabricates a device result.

## What ran here (verifiable subset, green)

Full JVM suite green on this commit **before** docs landing:

- `./gradlew :app:testDebugUnitTest` → **293 tests, 0 failures, 0 errors, 0 skipped** (26 test classes).
- After adding the ticket-37 benchmark test: 294 tests, still green (see `docs/PERF_TIMINGS.md`).

Scheduling-specific unit coverage that passed on this run:

| Area | Tests (all green) |
|---|---|
| Boot/timezone re-arm gate | `SchedulerHardeningTest.rescheduleGateOpensForBootTimezoneAndTimeSet`, `rescheduleGateIgnoresUnrelatedAndNullActions` |
| Re-arm preserves days | `SchedulerHardeningTest.rescheduleAllRearmsEveryScheduleWithDaysIntact`, `rescheduleAllOnEmptyStoreRegistersNothing` |
| Manifest subscribes to BOOT/TIMEZONE/TIME_SET | `SchedulerHardeningTest.manifestRearmsOnBootTimezoneAndTimeChange` |
| No periodic work; receivers do no DB on raw threads | `SchedulerHardeningTest.noPeriodicWorkAnywhereInMainSource`, `receiverFileHoldsNoRawThreadOrDirectDatabaseUse` |
| Cron edge cases (12am/12pm, weekly) | `ScheduleTest` (daily/weekly trigger), `SchedulerHardeningTest.weeklyTriggerLandsOnNextAllowedDayNotTomorrow`, `dailyTriggerIsTheNextOccurrenceToday` |
| Idempotency (scheduleId + fireTime key) | `ScheduledRunTest.doubleFireWithSameKeyRunsTheLoopOnce`, `differentFireTimeRunsAgain`, `runStoreClaimIsIdempotentOnScheduleIdPlusFireTime` |
| Scheduled writes become PendingApproval, never execute | `ScheduledRunTest.sendAttemptNeverExecutesAndRecordsPendingApproval`, `scheduleWritesAndMemoryWritesNeverExecute`, allowlist tests |
| Expired Gmail token → Reconnect notification | `ScheduledRunTest.expiredGmailTokenRecordsAuthErrorWithReconnectNotification`, `SchedulerHardeningTest.gmailAuthFailureVariantsAreRecognized` |
| Exact-alarm fallback + battery prompt | `SchedulerHardeningTest.exactAllowedBelow31EvenWithoutGrant`, `exactOn31PlusFollowsTheGrant`, `deniedExactNoteKeepsTheSettingsPrompt`, `BatteryDozeTest` |

## What needs a human device pass (not run here)

Doze (`force-idle`), killed-app delivery, screen-off delivery, and reboot
re-arm cannot be verified on the JVM — they need a real device. Steps for
the human pass (Doze limits exact alarms to roughly one per 9 minutes per
app; OEM battery managers can still kill delivery — see `BatteryDoze.kt`):

1. Install the **release** APK (built with empty keys, ticket 36) or a debug build; connect Gmail; grant exact alarms; exempt battery optimization in Settings.
2. Create a Schedule ~2 minutes in the future.
3. `adb shell dumpsys deviceidle force-idle`, screen off, **swipe-kill the app**, wait past the fire time → expect the summary notification and a `schedule_runs` SUCCESS row.
4. `adb shell dumpsys deviceidle unforce`, reboot the device, wait for BOOT_COMPLETED → check logcat for the re-arm Worker and confirm the next Schedule still fires.
5. Record here: device model, **Android version**, OEM, and pass/fail per step.

Result of the on-device 2-minute / kill / screen-off / force-idle / reboot
sequence in *this* environment: **NOT RUN — no device attached.** The table
above is the method, not a result.

## On-device results — Realme RMX3771, Android 15 (2026-11-23)

All runs on `GEYLL7YX5PIJ8LA6` via adb. Bracket B build: `merge/B-scheduler`
(`f251cf2`, `./gradlew :app:installDebug`). Bracket C build: `merge/C-deltas`
(`e465275`). Fires kept ≥3 min apart (Doze exact-alarm quota); kills done by
manual swipe-kill only — one `am force-stop` attempt voided a run (it cancels
alarms, `pi_cancelled` in alarm history) and was re-run correctly.

### Bracket B (scheduler)

- **B1 PASS** — baseline fire: result notification + `schedule_runs` SUCCESS
  row (2970 ms, idempotency key) + OS alarm re-armed for next day with
  `exactAllowReason=allow-listed`. Verified at 3 layers: screenshot,
  `run-as` DB pull (shm+wal pulled alongside), `dumpsys alarm`.
- **B2 PASS** — dead app + forced Doze + screen off: `sched_5` SUCCESS
  (3138 ms). First attempt voided by `am force-stop`; retry used swipe-kill.
- **B3 PASS** — reboot re-arm: after `adb reboot`, tomorrow's alarms armed
  allow-listed; today's occurrence correctly did not fire late
  (`dumpsys alarm`).
- **B4 PASS (via model-guard refusal)** — scheduled send refused in prose
  twice (16:59 + 17:05, different phrasings); nothing sent, nothing held.
  Refusal notifications arrived correctly. Held-row path stays unit-covered
  (`ScheduledRunTest.sendAttemptNeverExecutesAndRecordsPendingApproval`) +
  code-confirmed (`ScheduledRun.kt:336-360`). Follow-up (not this stack):
  headless prompt should steer the model to attempt-then-hold rather
  than refuse.
- **B5 ENVIRONMENT-BLOCKED (not an app bug)** — ColorOS hides default-granted
  apps from Alarms & reminders (screenshot proof); shell `appops set` denied
  (`MANAGE_APP_OPS_MODES`). Fallback path unit-covered only. Permission
  declared (`AndroidManifest.xml:7`), grant state default.

### Bracket C (approval cards, channels, disclosure)

- **C1 PASS (user-verified on phone)** — Confirmation card rendered with
  recipient/subject/body, `Confirm & Send` / `Deny`, and the "New recipient"
  warning. Approve sent; inline-edit + approve sent the edited text; reject
  sent nothing; tapping the result notification opened the app (`98eddc4`
  fix confirmed — broken at B-tip). Two model-quality notes → issue #51:
  "myself" resolved by luck/hallucination (system prompt never carries the
  signed-in Gmail address) and follow-up turns start a fresh run by design
  (`runLoopCommand` builds a new loop per command, `AgentViewModel.kt:863`).
- **C2 PASS** — `routines` channel confirmed DEFAULT at three layers: adb
  `dumpsys notification` (`mId='routines', mImportance=3`), Settings channel
  detail (allowed, not silent, Ring Default), Settings channel list
  (`Routines` = Notification Drawer + Lock screen + Ring). `routine_running`
  (LOW) correctly absent on Android 15: created only in
  `getForegroundInfo()`, the foreground-service path for API 26–30
  (`ScheduleWork.kt:33-35,43-44`).
- **C3 PASS** — Settings shows "Release builds ship with empty keys — enter
  them here to run the agent." (`SettingsScreen.kt:144`), Gmail connected,
  DeepSeek Set / Meta Not set. Tried-providers disclosure code-covered
  (`ProviderPicker.kt`, `ScheduledRun.kt`,
  `AgentUiState.kt`).
