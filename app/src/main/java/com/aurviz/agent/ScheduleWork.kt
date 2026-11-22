package com.aurviz.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.room.Room
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

// Session 3 thin wiring, step 2: the background worker.
// An alarm (step 3) wakes the phone at the routine's time; the worker
// then runs the routine and posts the result note. All wording comes
// from the tested buildResultNotification — this file only delivers it.

const val SCHEDULE_ID_KEY = "schedule_id"
private const val ROUTINE_CHANNEL_ID = "routines"

class ScheduleWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val scheduleId = inputData.getString(SCHEDULE_ID_KEY) ?: return Result.failure()
        val db = Room.databaseBuilder(applicationContext, AppDb::class.java, "aurviz.db")
            .addMigrations(MIGRATION_1_2)
            .build()
        val store = RoomScheduleStore(db.schedules())
        val schedule = store.list().find { it.id == scheduleId } ?: return Result.failure()
        // Session 4 plugs the full Agent Plan run in here; until then the
        // note confirms the routine fired (punctuality log for the report).
        val note = buildResultNotification(schedule, "fired on time")
        postScheduleNotification(applicationContext, schedule.id, note)
        db.close()
        return Result.success()
    }
}

// One routine = one queued job: same name replaces, never doubles.
fun enqueueRoutineWork(context: Context, scheduleId: String) {
    val request = OneTimeWorkRequestBuilder<ScheduleWorker>()
        .setInputData(Data.Builder().putString(SCHEDULE_ID_KEY, scheduleId).build())
        .build()
    WorkManager.getInstance(context)
        .enqueueUniqueWork(uniqueWorkName(scheduleId), ExistingWorkPolicy.REPLACE, request)
}

fun cancelRoutineWork(context: Context, scheduleId: String) {
    WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(scheduleId))
}

fun postScheduleNotification(context: Context, scheduleId: String, note: ScheduleNotification) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(
        NotificationChannel(ROUTINE_CHANNEL_ID, "Routines", NotificationManager.IMPORTANCE_DEFAULT),
    )
    val androidNote = NotificationCompat.Builder(context, ROUTINE_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(note.title)
        .setContentText(note.text)
        .setAutoCancel(true)
        .build()
    manager.notify(routineNotificationId(scheduleId), androidNote)
}
