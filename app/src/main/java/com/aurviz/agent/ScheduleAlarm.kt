package com.aurviz.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.room.Room
import java.util.Calendar

// Session 3 thin wiring, step 3: exact alarms + reboot.
// The alarm is what makes "9AM means 9AM" (Doze-safe); the worker
// (step 2) does the routine. Times come from Schedule.kt, so tests
// already cover which routines fire — this file only sets the clock.

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

private fun alarmIntent(context: Context, scheduleId: String): PendingIntent {
    val intent = Intent(context, AlarmReceiver::class.java).putExtra(SCHEDULE_ID_KEY, scheduleId)
    val code = scheduleId.removePrefix("sched_").toIntOrNull() ?: scheduleId.hashCode()
    return PendingIntent.getBroadcast(
        context, code, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

// Exact when allowed (user-visible times), inexact fallback otherwise —
// the screen says which one was used so a denied permission is visible.
fun registerRoutineAlarm(context: Context, schedule: Schedule): Boolean {
    val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val triggerAt = nextTriggerMillis(schedule.hour, schedule.minute)
    val exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
    if (exact) {
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarmIntent(context, schedule.id))
    } else {
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarmIntent(context, schedule.id))
    }
    return exact
}

fun cancelRoutineAlarm(context: Context, scheduleId: String) {
    val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    alarms.cancel(alarmIntent(context, scheduleId))
}

// The alarm went off: hand the routine to the worker, then set tomorrow's.
// onReceive must return fast, so the database work runs on a side thread
// held open with goAsync (the phone may otherwise kill us mid-query).
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId = intent.getStringExtra(SCHEDULE_ID_KEY) ?: return
        val ongoing = goAsync()
        Thread {
            try {
                enqueueRoutineWork(context, scheduleId)
                val db = Room.databaseBuilder(context, AppDb::class.java, "aurviz.db")
                    .addMigrations(MIGRATION_1_2)
                    .build()
                db.schedules().listAll()
                    .find { "sched_${it.pid}" == scheduleId }
                    ?.let { registerRoutineAlarm(context, Schedule("sched_${it.pid}", it.instruction, it.hour, it.minute)) }
                db.close()
            } finally {
                ongoing.finish()
            }
        }.start()
    }
}

// The phone restarted: alarms are gone, so set every active one again.
// Which ids: the tested schedulesToRescheduleAfterReboot.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val ongoing = goAsync()
        Thread {
            try {
                val db = Room.databaseBuilder(context, AppDb::class.java, "aurviz.db")
                    .addMigrations(MIGRATION_1_2)
                    .build()
                val store = RoomScheduleStore(db.schedules())
                for (id in schedulesToRescheduleAfterReboot(store)) {
                    store.list().find { it.id == id }?.let { registerRoutineAlarm(context, it) }
                }
                db.close()
            } finally {
                ongoing.finish()
            }
        }.start()
    }
}
