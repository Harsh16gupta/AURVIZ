package com.aurviz.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar

// Session 3 thin wiring, step 3: exact alarms + reboot.
// The alarm is what makes "9AM means 9AM" (Doze-safe); the worker
// (step 2) does the routine. Times come from Schedule.kt, so tests
// already cover which routines fire — this file only sets the clock.
//
// Ticket #27 (hardening, ADR-0002): receivers do no work of their own.
// They only enqueue Workers — the routine Worker for the fired schedule
// plus the re-arm Worker that sets the next occurrence — so no database
// is touched on the receiver path and nothing needs an async hold.
// Re-arm also runs on boot and on timezone/clock change (see the
// manifest), and a revoked exact-alarm grant falls back to inexact
// with the Settings prompt carrying the difference.

// Next occurrence of hour:minute after now. Pure math on millis,
// so a missed time today rolls to tomorrow, never to the past.
fun nextTriggerMillis(hour: Int, minute: Int, nowMillis: Long = System.currentTimeMillis()): Long {
    val next = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (next.timeInMillis <= nowMillis) next.add(Calendar.DAY_OF_YEAR, 1)
    return next.timeInMillis
}

private fun alarmIntent(context: Context, scheduleId: String, fireTimeMs: Long = 0L): PendingIntent {
    val intent =
        Intent(context, AlarmReceiver::class.java)
            .putExtra(SCHEDULE_ID_KEY, scheduleId)
            .putExtra(FIRE_TIME_KEY, fireTimeMs)
    val code = ScheduleIds.schedulePid(scheduleId)?.toInt() ?: scheduleId.hashCode()
    return PendingIntent.getBroadcast(
        context, code, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

// Pure revoked-exact decision: below API 31 there is no runtime grant to
// revoke, so exact is always used; on 31+ it follows the grant. The call
// site reads canScheduleExactAlarms() behind an SDK guard, so this stays
// JVM-testable and the framework method is never reached on old devices.
fun exactAlarmAllowed(sdkInt: Int, canScheduleExactAlarms: Boolean): Boolean =
    sdkInt < 31 || canScheduleExactAlarms

// Wall-clock trigger for a routine, days-aware: the next datetime in the
// device zone whose wall-clock time matches on an allowed day, strictly
// after now. An explicitly empty day set never fires, so it falls back
// to the daily computation rather than returning the past.
fun triggerMillisFor(schedule: Schedule, nowMillis: Long = System.currentTimeMillis()): Long {
    val zone = ZoneId.systemDefault()
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    return nextFireTime(schedule, zone, now)?.toInstant()?.toEpochMilli()
        ?: nextTriggerMillis(schedule.hour, schedule.minute, nowMillis)
}

// Exact when allowed (user-visible times), inexact fallback otherwise —
// the screen says which one was used so a denied permission is visible.
fun registerRoutineAlarm(context: Context, schedule: Schedule): Boolean {
    val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val triggerAt = triggerMillisFor(schedule)
    val exact =
        exactAlarmAllowed(
            Build.VERSION.SDK_INT,
            Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms(),
        )
    if (exact) {
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarmIntent(context, schedule.id, triggerAt))
    } else {
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarmIntent(context, schedule.id, triggerAt))
    }
    return exact
}

fun cancelRoutineAlarm(context: Context, scheduleId: String) {
    val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    alarms.cancel(alarmIntent(context, scheduleId))
}

// Pure re-arm over any store: every active routine is registered again
// with its full Schedule, days included (an earlier revision rebuilt the
// row without days, so weekly routines came back daily). Returns how many
// were registered. JVM-testable; the re-arm Worker supplies the Room
// store and the AlarmManager registration on the device.
fun rescheduleAll(store: ScheduleStore, register: (Schedule) -> Unit): Int {
    var count = 0
    for (schedule in store.list()) {
        register(schedule)
        count += 1
    }
    return count
}

// Which system broadcasts mean "alarms may be gone or stale": a reboot
// wipes them, a timezone or clock change moves the wall-clock instant.
// The manifest subscribes the reschedule receiver to all three actions.
fun shouldRescheduleOnSystemAction(action: String?): Boolean =
    action == Intent.ACTION_BOOT_COMPLETED ||
        action == Intent.ACTION_TIMEZONE_CHANGED ||
        action == Intent.ACTION_TIME_CHANGED

// The alarm went off: hand the routine to the Worker, then queue a
// re-arm for the next occurrence. onReceive only enqueues Workers,
// which is fast enough to need no async hold, so no work runs here.
// The alarm's own trigger instant travels as FIRE_TIME_KEY, so a
// double-delivered alarm claims the same schedule_runs key and the
// second delivery skips instead of double-running.
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId = intent.getStringExtra(SCHEDULE_ID_KEY) ?: return
        val fireTime = intent.getLongExtra(FIRE_TIME_KEY, 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
        enqueueRoutineWork(context, scheduleId, fireTime)
        enqueueRescheduleWork(context)
    }
}

// The phone rebooted, or the timezone/clock changed: alarms are gone or
// stale, so queue the Worker that sets every active one again.
// Which ids: the tested schedulesToRescheduleAfterReboot.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!shouldRescheduleOnSystemAction(intent.action)) return
        enqueueRescheduleWork(context)
    }
}
