package com.aurviz.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.room.Room
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

// Ticket 13/24: the real scheduled run (ADR-0002, gaps §3D).
// The exact alarm fires, the receiver enqueues this expedited unique
// Worker (REPLACE, so one routine never doubles), and the Worker runs
// the AgentLoop headless on the read-only allowlist, records the run
// in schedule_runs (idempotency scheduleId+fireTime), and posts the
// summary notification. Sends and schedule/memory writes never
// execute: they become PendingApproval rows plus a notification, and
// the user approves in-app. An expired Gmail token posts the
// "Reconnect Gmail" notification instead of failing silently.

const val SCHEDULE_ID_KEY = "schedule_id"
const val FIRE_TIME_KEY = "fire_time"
private const val ROUTINE_CHANNEL_ID = "routines"

class ScheduleWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    // Expedited work below API 31 runs as a foreground service: WorkManager
    // calls this before doWork. Without the override the base Worker throws
    // and the routine never runs on Android 8-11 (minSdk 26).
    override fun getForegroundInfo(): ForegroundInfo {
        val scheduleId = inputData.getString(SCHEDULE_ID_KEY) ?: "routine"
        val manager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(ROUTINE_CHANNEL_ID, "Routines", NotificationManager.IMPORTANCE_LOW),
        )
        val note =
            NotificationCompat.Builder(applicationContext, ROUTINE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Running routine")
                .setContentText(scheduleId)
                .setOngoing(true)
                .build()
        return ForegroundInfo(routineNotificationId(scheduleId), note)
    }

    override fun doWork(): Result {
        val scheduleId = inputData.getString(SCHEDULE_ID_KEY) ?: return Result.failure()
        val fireTime = inputData.getLong(FIRE_TIME_KEY, 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
        val db =
            Room.databaseBuilder(applicationContext, AppDb::class.java, "aurviz.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
        try {
            val scheduleStore = RoomScheduleStore(db.schedules())
            val schedule = scheduleStore.list().find { it.id == scheduleId } ?: return Result.failure()
            val tokens = TokenStore()
            try {
                TokenVault(applicationContext).loadInto(tokens)
            } catch (_: Exception) {
            }
            val gmail =
                GmailHttpApi(
                    tokens,
                    BuildConfig.GOOGLE_OAUTH_CLIENT_ID,
                    BuildConfig.GOOGLE_OAUTH_CLIENT_SECRET,
                )
            val full =
                defaultToolRegistry(
                    gmailApi = gmail,
                    scheduleStore = scheduleStore,
                    memoryStore = RoomMemoryStore(db.memories()),
                    backgroundWebLoader = OkHttpBackgroundPageLoader(),
                )
            val vaultKeys =
                try {
                    TokenVault(applicationContext).loadLlmKeys()
                } catch (_: Exception) {
                    LlmKeys()
                }
            val chat = HeadlessChatLlm(defaultCandidates(BuildConfig.LLM_DEFAULT_PROVIDER, vaultKeys))
            return when (
                val outcome =
                    runScheduledRoutine(
                        schedule,
                        fireTime,
                        chat,
                        full,
                        RoomScheduleRunStore(db.runs()),
                        RoomPendingApprovalStore(db.approvals()),
                    )
            ) {
                is ScheduledRunOutcome.DuplicateSkipped -> Result.success()
                is ScheduledRunOutcome.Ran -> {
                    postScheduleNotification(applicationContext, schedule.id, outcome.notification)
                    if (outcome.blocked.isNotEmpty()) {
                        postPendingApprovalNotification(applicationContext, schedule, outcome.blocked.size)
                    }
                    Result.success()
                }
            }
        } catch (_: Exception) {
            return Result.failure()
        } finally {
            try {
                db.close()
            } catch (_: Exception) {
            }
        }
    }
}

// One routine = one queued expedited job: same name replaces, never
// doubles (ADR-0002). fireTime is the alarm's trigger instant, so a
// double-delivered alarm claims the same schedule_runs key and the
// second delivery skips instead of double-running.
fun enqueueRoutineWork(context: Context, scheduleId: String, fireTimeMs: Long = System.currentTimeMillis()) {    val request =
        OneTimeWorkRequestBuilder<ScheduleWorker>()
            .setInputData(
                Data.Builder()
                    .putString(SCHEDULE_ID_KEY, scheduleId)
                    .putLong(FIRE_TIME_KEY, fireTimeMs)
                    .build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
    WorkManager.getInstance(context)
        .enqueueUniqueWork(uniqueWorkName(scheduleId), ExistingWorkPolicy.REPLACE, request)
}

fun cancelRoutineWork(context: Context, scheduleId: String) {
    WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(scheduleId))
}

// Ticket #27 (hardening, ADR-0002): the re-arm Worker. Receivers never
// touch the database on their own path; they enqueue this one-time
// unique Worker (REPLACE), which loads every active routine and sets its
// alarm again with days intact. Never periodic: each enqueue runs once
// (tomorrow's re-arm is queued by the next fire, boot, or clock change).
const val RESCHEDULE_WORK_NAME = "reschedule_all"

class RescheduleWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val db =
            Room.databaseBuilder(applicationContext, AppDb::class.java, "aurviz.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
        try {
            rescheduleAll(RoomScheduleStore(db.schedules())) { registerRoutineAlarm(applicationContext, it) }
            return Result.success()
        } catch (_: Exception) {
            return Result.failure()
        } finally {
            try {
                db.close()
            } catch (_: Exception) {
            }
        }
    }
}

fun enqueueRescheduleWork(context: Context) {
    val request = OneTimeWorkRequestBuilder<RescheduleWorker>().build()
    WorkManager.getInstance(context)
        .enqueueUniqueWork(RESCHEDULE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
}

fun postScheduleNotification(context: Context, scheduleId: String, note: ScheduleNotification) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(
        NotificationChannel(ROUTINE_CHANNEL_ID, "Routines", NotificationManager.IMPORTANCE_DEFAULT),
    )
    // Tap routes into the app; the Schedules screen reloads held approvals
    // on entry so the row the note refers to is visible.
    val openApp =
        android.app.PendingIntent.getActivity(
            context,
            routineNotificationId(scheduleId),
            android.content.Intent(context, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
    val androidNote =
        NotificationCompat.Builder(context, ROUTINE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(note.title)
            .setContentText(note.text)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
    manager.notify(routineNotificationId(scheduleId), androidNote)
}

// Writes a scheduled run attempted but never ran: one row per attempt
// already sits in pending_approvals; this note sends the user in-app
// to approve them. A stable id per routine keeps routines apart.
fun postPendingApprovalNotification(context: Context, schedule: Schedule, blockedCount: Int) {
    postScheduleNotification(
        context,
        "approval_${schedule.id}",
        ScheduleNotification(
            "Approval needed",
            "${schedule.instruction}: $blockedCount request(s) need your approval",
        ),
    )
}
