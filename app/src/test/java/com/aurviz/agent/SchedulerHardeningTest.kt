package com.aurviz.agent

import android.content.Intent
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

// Ticket #27 (14 Scheduler hardening): receivers delegate to the Worker
// path (no Room on raw threads), re-arm covers fire/boot/timezone/time-set,
// revoked SCHEDULE_EXACT_ALARM falls back to inexact plus the persistent
// Settings prompt, and an expired Gmail token posts Reconnect instead of
// failing silently. No periodic work anywhere.
//
// Seams (what each catches / misses):
// 1. receiverActionGate — pure intent-action dispatch. Catches manifest and
//    onReceive drift; misses actual on-device delivery.
// 2. rescheduleAll + triggerMillisFor — pure re-arm over a ScheduleStore.
//    Catches the days-drop regression (re-arm rebuilt the Schedule without
//    days) and weekly schedules firing daily; misses AlarmManager wiring.
// 3. exactAlarmAllowed — pure revoked-exact decision. Catches fallback
//    inversions; misses the framework canScheduleExactAlarms() read (the
//    call site guards it for SDK < 31).
// 4. isGmailAuthFailure + scheduledRunNotification — expired-token variants
//    map to the Reconnect note. Catches silent-failure regressions; misses
//    Worker notification posting (needs a device).
// 5. Architecture guards (manifest XML + main-source scan) — the manifest
//    declares boot/timezone/time-set re-arm, no periodic WorkManager API is
//    used, and the receiver file holds no raw Thread/goAsync/Room use.
//    Catches wiring regressions; misses runtime behavior.
class SchedulerHardeningTest {

    // --- Seam 1: receiver dispatch gate. ---

    @Test
    fun rescheduleGateOpensForBootTimezoneAndTimeSet() {
        assertTrue(shouldRescheduleOnSystemAction(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(shouldRescheduleOnSystemAction(Intent.ACTION_TIMEZONE_CHANGED))
        assertTrue(shouldRescheduleOnSystemAction(Intent.ACTION_TIME_CHANGED))
    }

    @Test
    fun rescheduleGateIgnoresUnrelatedAndNullActions() {
        assertFalse(shouldRescheduleOnSystemAction(Intent.ACTION_SCREEN_ON))
        assertFalse(shouldRescheduleOnSystemAction("android.intent.action.PACKAGE_ADDED"))
        assertFalse(shouldRescheduleOnSystemAction(null))
    }

    // --- Seam 2: re-arm preserves the full Schedule (days-drop regression). ---

    @Test
    fun rescheduleAllRearmsEveryScheduleWithDaysIntact() {
        val store = InMemoryScheduleStore()
        val weekday = store.save("weekly report", 9, 0, days = setOf(1))
        val daily = store.save("check emails", 18, 30)
        val seen = mutableListOf<Schedule>()
        val count = rescheduleAll(store) { seen += it }
        assertEquals(2, count)
        assertEquals(setOf(weekday.id, daily.id), seen.map { it.id }.toSet())
        assertEquals(setOf(1), seen.first { it.id == weekday.id }.days)
        assertEquals(null, seen.first { it.id == daily.id }.days)
    }

    @Test
    fun rescheduleAllOnEmptyStoreRegistersNothing() {
        val seen = mutableListOf<Schedule>()
        assertEquals(0, rescheduleAll(InMemoryScheduleStore()) { seen += it })
        assertTrue(seen.isEmpty())
    }

    // --- Ticket #107 (T2, fix 1): disabled Schedules never re-arm. ---

    @Test
    fun rescheduleAllSkipsDisabledIds() {
        val store = InMemoryScheduleStore()
        val morning = store.save("morning report", 9, 0)
        val evening = store.save("evening report", 18, 0)
        val seen = mutableListOf<Schedule>()
        val count = rescheduleAll(store, setOf(morning.id)) { seen += it }
        assertEquals(1, count)
        assertEquals(listOf(evening.id), seen.map { it.id })
    }

    @Test
    fun rescheduleAllWithAllDisabledRegistersNothing() {
        val store = InMemoryScheduleStore()
        val only = store.save("paused routine", 9, 0)
        val seen = mutableListOf<Schedule>()
        assertEquals(0, rescheduleAll(store, setOf(only.id)) { seen += it })
        assertTrue(seen.isEmpty())
    }

    @Test
    fun rescheduleAllWithUnknownDisabledIdsStillRearmsKnown() {
        val store = InMemoryScheduleStore()
        val kept = store.save("kept routine", 9, 0)
        val seen = mutableListOf<Schedule>()
        assertEquals(1, rescheduleAll(store, setOf("sched_nope")) { seen += it })
        assertEquals(listOf(kept.id), seen.map { it.id })
    }

    @Test
    fun rearmPathsFilterDisabledSchedules() {
        // After-fire (Worker), boot/timezone (Worker), and resume
        // (ViewModel) re-arms must all consult disabledScheduleIds so a
        // disabled Schedule is never re-armed.
        val work = File(moduleDir(), "src/main/java/com/aurviz/agent/ScheduleWork.kt").readText()
        assertTrue(work.contains("disabledScheduleIds"))
        assertTrue(work.contains("schedule.id !in disabledIds"))
        val vm = File(moduleDir(), "src/main/java/com/aurviz/agent/ui/AgentViewModel.kt").readText()
        assertTrue(vm.contains("rescheduleAll(scheduleStore, disabledIds)"))
        assertTrue(vm.contains("val disabledIds = state.value.disabledScheduleIds"))
    }

    @Test
    fun weeklyTriggerLandsOnNextAllowedDayNotTomorrow() {
        // 2026-11-21 is a Saturday (same anchor as ScheduleTest).
        val zone = ZoneId.systemDefault()
        val saturday =
            ZonedDateTime.of(2026, 11, 21, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val trigger =
            triggerMillisFor(Schedule("sched_9", "weekly report", 9, 0, days = setOf(1)), saturday)
        val got = Instant.ofEpochMilli(trigger).atZone(zone)
        assertTrue(trigger > saturday)
        assertEquals(9, got.hour)
        assertEquals(0, got.minute)
        assertEquals(DayOfWeek.MONDAY, got.dayOfWeek)
    }

    @Test
    fun dailyTriggerIsTheNextOccurrenceToday() {
        val zone = ZoneId.systemDefault()
        val morning =
            ZonedDateTime.of(2026, 11, 23, 7, 0, 0, 0, zone).toInstant().toEpochMilli()
        val got =
            Instant.ofEpochMilli(triggerMillisFor(Schedule("sched_1", "mail", 9, 0), morning))
                .atZone(zone)
        assertEquals(ZonedDateTime.of(2026, 11, 23, 9, 0, 0, 0, zone), got)
    }

    // --- Seam 3: revoked-exact fallback decision + persistent prompt. ---

    @Test
    fun exactAllowedBelow31EvenWithoutGrant() {
        assertTrue(exactAlarmAllowed(sdkInt = 30, canScheduleExactAlarms = false))
    }

    @Test
    fun exactOn31PlusFollowsTheGrant() {
        assertTrue(exactAlarmAllowed(sdkInt = 31, canScheduleExactAlarms = true))
        assertFalse(exactAlarmAllowed(sdkInt = 31, canScheduleExactAlarms = false))
        assertFalse(exactAlarmAllowed(sdkInt = 35, canScheduleExactAlarms = false))
    }

    @Test
    fun deniedExactNoteKeepsTheSettingsPrompt() {
        val note = scheduleTimingNote(exactGranted = false)
        assertTrue(note.contains("inexact", ignoreCase = true))
        assertTrue(note.contains("Settings"))
    }

    // --- Seam 4: expired-token variants map to the Reconnect note. ---

    @Test
    fun gmailAuthFailureVariantsAreRecognized() {
        for (error in listOf(
            "gmail is not connected: token expired",
            "Gmail: HTTP 401 token expired",
            "gmail request failed with 401",
            "invalid_grant: Token has been expired or revoked",
            "gmail token refresh failed",
            "Please Reconnect Gmail to continue",
        )) {
            assertTrue("should be auth failure: $error", isGmailAuthFailure(error))
        }
        assertFalse(isGmailAuthFailure("timeout waiting for search results"))
    }

    @Test
    fun bareLlm401IsNotAGmailAuthFailure() {
        // A bad model key surfaces as "HTTP 401" with no Gmail shape: it
        // must not post a misleading "Reconnect Gmail" notification.
        assertFalse(isGmailAuthFailure("step 0 failed: HTTP 401"))
        assertFalse(isGmailAuthFailure("scheduled run failed: HTTP 401"))
        assertFalse(isGmailAuthFailure("401 Unauthorized"))
    }

    @Test
    fun reconnectNotificationWinsEvenWithAnAnswer() {
        val note =
            scheduledRunNotification(
                Schedule("sched_1", "check emails", 9, 0),
                answer = "stale answer",
                error = "401 Unauthorized",
                blockedCount = 0,
                authFailed = true,
            )
        assertTrue(note.title.contains("Reconnect Gmail"))
        assertTrue(note.text.contains("Reconnect Gmail"))
    }

    // --- Seam 5: architecture guards. ---

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun moduleDir(): File {
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            if (File(dir, "src/main/AndroidManifest.xml").exists()) return dir
            dir = dir.parentFile ?: throw AssertionError("Could not locate app module dir from user.dir")
        }
    }

    private fun manifestActions(): Set<String> {
        val root =
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(File(moduleDir(), "src/main/AndroidManifest.xml")).documentElement
        val receivers = root.getElementsByTagName("receiver")
        val out = mutableSetOf<String>()
        for (i in 0 until receivers.length) {
            val receiver = receivers.item(i) as Element
            val filters = receiver.getElementsByTagName("intent-filter")
            for (j in 0 until filters.length) {
                val actions = (filters.item(j) as Element).getElementsByTagName("action")
                for (k in 0 until actions.length) {
                    out += (actions.item(k) as Element).getAttributeNS(androidNs, "name")
                }
            }
        }
        return out
    }

    @Test
    fun manifestRearmsOnBootTimezoneAndTimeChange() {
        val actions = manifestActions()
        assertTrue(actions.contains("android.intent.action.BOOT_COMPLETED"))
        assertTrue(actions.contains("android.intent.action.TIMEZONE_CHANGED"))
        assertTrue(actions.contains("android.intent.action.TIME_SET"))
    }

    @Test
    fun resumeRearmsPersistedSchedulesToCurrentGrant() {
        // Revocation cancels existing exact alarms with no callback here:
        // every resume must re-register persisted Schedules (exact vs
        // inexact follows the current grant inside registerRoutineAlarm).
        val activity = File(moduleDir(), "src/main/java/com/aurviz/agent/MainActivity.kt").readText()
        assertTrue(activity.contains("reconcileAlarmsAfterResume"))
        val vm = File(moduleDir(), "src/main/java/com/aurviz/agent/ui/AgentViewModel.kt").readText()
        assertTrue(vm.contains("fun reconcileAlarmsAfterResume"))
        assertTrue(vm.contains("registerRoutineAlarm(app, schedule)"))
    }

    @Test
    fun noPeriodicWorkAnywhereInMainSource() {
        val banned = listOf("PeriodicWorkRequest", "enqueueUniquePeriodicWork", "setPeriodic(")
        val hits = mutableListOf<String>()
        File(moduleDir(), "src/main/java").walkTopDown()
            .filter { it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                for (marker in banned) {
                    if (marker in text) hits += "${file.name}: $marker"
                }
            }
        assertTrue("periodic work must never be used: $hits", hits.isEmpty())
    }

    @Test
    fun receiverFileHoldsNoRawThreadOrDirectDatabaseUse() {
        val text = File(moduleDir(), "src/main/java/com/aurviz/agent/ScheduleAlarm.kt").readText()
        for (marker in listOf("Thread(", "goAsync(", "Room.databaseBuilder", "thread {")) {
            assertFalse("receivers must use the Worker path, found: $marker", marker in text)
        }
    }

    @Test
    fun expeditedWorkerOverridesForegroundInfo() {
        // Android 8-11 runs expedited work as a foreground service: without
        // this override the base Worker throws before doWork. Reflection
        // pins the override (the runtime behavior needs a device).
        val method = ScheduleWorker::class.java.getDeclaredMethod("getForegroundInfo")
        assertEquals(ScheduleWorker::class.java, method.declaringClass)
    }

    // --- Ticket #107 (T2, fix 4): mic-stop / dismiss-cancel guards. ---
    // SpeechRecognizer needs a device, so these pin the wiring: the sheet
    // mic button stops (not restarts) while listening, dismissing the
    // sheet cancels recognition, and the sheet always notifies on dismiss.

    @Test
    fun micTapStopsWhileListeningAndDismissCancels() {
        val activity = File(moduleDir(), "src/main/java/com/aurviz/agent/MainActivity.kt").readText()
        assertTrue(activity.contains("if (vm.state.value.voice.listening)"))
        assertTrue(activity.contains("recognizer?.stopListening()"))
        assertTrue(activity.contains("fun stopVoiceRecognition"))
        assertTrue(activity.contains("recognizer?.cancel()"))
        assertTrue(activity.contains("onVoiceDismiss = ::stopVoiceRecognition"))
        val nav = File(moduleDir(), "src/main/java/com/aurviz/agent/ui/AgentNav.kt").readText()
        assertTrue(nav.contains("onVoiceDismiss: () -> Unit = {}"))
        // Both dismiss paths (X + use-transcript) cancel first so the
        // microphone never stays hot after the sheet closes.
        assertTrue(nav.contains("onVoiceDismiss()"))
    }
}
