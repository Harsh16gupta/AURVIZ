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
private const val ROUTINE_RUNNING_CHANNEL_ID = "routine_running"

class ScheduleWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    // Expedited work below API 31 runs as a foreground service: WorkManager
    // calls this before doWork. Without the override the base Worker throws
    // and the routine never runs on Android 8-11 (minSdk 26).
    override fun getForegroundInfo(): ForegroundInfo {
        val scheduleId = inputData.getString(SCHEDULE_ID_KEY) ?: "routine"
        val manager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Own channel: the results channel is DEFAULT (audible) while this
        // ongoing note stays LOW — first channel creation wins, so sharing
        // one id would have pinned the results to LOW forever.
        manager.createNotificationChannel(
            NotificationChannel(ROUTINE_RUNNING_CHANNEL_ID, "Routine running", NotificationManager.IMPORTANCE_LOW),
        )
        val note =
            NotificationCompat.Builder(applicationContext, ROUTINE_RUNNING_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Running routine")
                .setContentText(scheduleId)
                .setOngoing(true)
                .build()
        return ForegroundInfo(routineForegroundNotificationId(scheduleId), note)
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
            val full = buildRoutineRegistry(scheduleStore, db)
            val chat = buildRoutineChat(full)
            val outcome =
                fireSchedule(scheduleId, fireTime, scheduleStore) { sched ->
                    runScheduledRoutine(sched, fireTime, chat, full, RoomScheduleRunStore(db.runs()), RoomPendingApprovalStore(db.approvals()))
                }
            if (outcome.nextArmMillis != null) {
                try {
                    registerRoutineAlarm(applicationContext, schedule)
                } catch (_: Exception) {
                }
            }
            return when (outcome.status) {
                FireStatus.RAN -> {
                    val note = outcome.notification
                    if (note != null) postScheduleNotification(applicationContext, schedule.id, note)
                    if (outcome.blocked.isNotEmpty()) {
                        postPendingApprovalNotification(applicationContext, schedule, outcome.blocked.size)
                    }
                    Result.success()
                }
                FireStatus.DUPLICATE_SKIPPED -> Result.success()
                FireStatus.SCHEDULE_MISSING -> Result.failure()
                FireStatus.FAILED -> Result.retry()
            }
        } catch (_: Exception) {
            // Transient failure (DB/LLM throw): retry with the same
            // fireTime. Safe by idempotency — a post-claim crash re-enters
            // as DuplicateSkipped, a pre-claim crash retries for real — so
            // the due run is preserved instead of terminally dropped.
            return Result.retry()
        } finally {
            try {
                db.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun buildRoutineRegistry(scheduleStore: RoomScheduleStore, db: AppDb): ToolRegistry {
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
        return defaultToolRegistry(
            gmailApi = gmail,
            scheduleStore = scheduleStore,
            memoryStore = RoomMemoryStore(db.memories()),
            backgroundWebLoader = OkHttpBackgroundPageLoader(),
        )
    }

    private fun buildRoutineChat(full: ToolRegistry): PinnedFallbackChatLlm {
        val vaultKeys =
            try {
                TokenVault(applicationContext).loadLlmKeys()
            } catch (_: Exception) {
                LlmKeys()
            }
        // Ticket #90: headless runs auto-select like the foreground loop —
        // a keyless default provider falls back to whichever provider is
        // Set instead of failing the run on a blank key.
        val effective =
            try {
                firstConfiguredProvider(BuildConfig.LLM_DEFAULT_PROVIDER, effectiveSettings(settingsFromBuildConfig(), vaultKeys))
            } catch (_: Exception) {
                BuildConfig.LLM_DEFAULT_PROVIDER
            }
        return PinnedFallbackChatLlm(
            defaultCandidates(effective, vaultKeys),
            scheduledRegistry(full).toolSchemas(),
        )
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
