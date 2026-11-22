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
