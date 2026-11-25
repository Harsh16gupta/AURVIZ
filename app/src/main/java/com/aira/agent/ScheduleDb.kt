package com.aira.agent

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.ColumnInfo
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

// Session 3 thin wiring: routines live in the same on-phone database.
// Room only stores rows; all decisions (parse/due/reboot/note) stay in
// Schedule.kt, so the unit tests never need a database.

// One routine row. days is null = every day, else comma-separated
// 1=Monday..7=Sunday (e.g. "1,2,3,4,5" for weekdays).
@Entity(tableName = "schedules")
data class ScheduleRow(
    @PrimaryKey(autoGenerate = true) val pid: Long = 0,
    val instruction: String,
    val hour: Int,
    val minute: Int,
    val days: String? = null,
)

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY pid ASC")
    fun listAll(): List<ScheduleRow>

    @Insert
    fun insert(row: ScheduleRow): Long

    @Query("DELETE FROM schedules WHERE pid = :pid")
    fun deleteById(pid: Long)
}

// Version 1 -> 2 adds only the new table; memories are untouched.
val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `schedules` (" +
                    "`pid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`instruction` TEXT NOT NULL, " +
                    "`hour` INTEGER NOT NULL, " +
                    "`minute` INTEGER NOT NULL, " +
                    "`days` TEXT)",
            )
        }
    }

// Version 3 -> 4 adds the ticket 13/24 scheduled-run tables (ADR-0002):
// schedule_runs records every headless run with the idempotency key
// scheduleId+fireTime (unique index, so a double-fire loses the race),
// and pending_approvals holds writes the loop attempted but never ran.
val MIGRATION_3_4 =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `schedule_runs` (" +
                    "`pid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`scheduleId` TEXT NOT NULL, " +
                    "`fireTime` INTEGER NOT NULL, " +
                    "`status` TEXT NOT NULL, " +
                    "`error` TEXT, " +
                    "`result` TEXT, " +
                    "`durationMs` INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_schedule_runs_scheduleId_fireTime` " +
                    "ON `schedule_runs` (`scheduleId`, `fireTime`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `pending_approvals` (" +
                    "`pid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`scheduleId` TEXT NOT NULL, " +
                    "`toolName` TEXT NOT NULL, " +
                    "`argsJson` TEXT NOT NULL, " +
                    "`reason` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL)",
            )
        }
    }

// One recorded headless run. The unique (scheduleId, fireTime) index is
// the double-fire guard: the second claim inserts nothing (IGNORE).
// Ticket #92: provider names the LLM that answered (null = unknown /
// pre-upgrade row); planMs/toolMs/recallMs split the run's stage timings
// (0 = untimed / pre-upgrade row).
@Entity(
    tableName = "schedule_runs",
    indices = [Index(value = ["scheduleId", "fireTime"], unique = true)],
)
data class ScheduleRunRow(
    @PrimaryKey(autoGenerate = true) val pid: Long = 0,
    val scheduleId: String,
    val fireTime: Long,
    val status: String,
    val error: String?,
    val result: String?,
    val durationMs: Long,
    val provider: String? = null,
    @ColumnInfo(defaultValue = "0") val planMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val toolMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val recallMs: Long = 0,
)

@Dao
interface ScheduleRunDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIgnore(row: ScheduleRunRow): Long

    @Query("SELECT * FROM schedule_runs WHERE scheduleId = :scheduleId AND fireTime = :fireTime LIMIT 1")
    fun find(scheduleId: String, fireTime: Long): ScheduleRunRow?

    @Query("SELECT * FROM schedule_runs ORDER BY pid ASC")
    fun listAll(): List<ScheduleRunRow>

    @Query("UPDATE schedule_runs SET status = :status, `error` = :error, `result` = :result, durationMs = :durationMs, provider = :provider, planMs = :planMs, toolMs = :toolMs, recallMs = :recallMs WHERE pid = :pid")
    fun finish(pid: Long, status: String, error: String?, result: String?, durationMs: Long, provider: String?, planMs: Long, toolMs: Long, recallMs: Long)

    @Query("SELECT pid FROM schedule_runs WHERE scheduleId = :scheduleId AND fireTime = :fireTime LIMIT 1")
    fun findPid(scheduleId: String, fireTime: Long): Long?

    /**
     * Reclaim a stale RUNNING row as a fresh lease in one conditional
     * UPDATE: exactly one racing worker wins (returns 1), the rest see 0.
     * The row stays RUNNING with a reclaim note — never terminally marked —
     * so a second crash keeps the due run retryable until finish() records
     * the real outcome. TTL ages from the alarm's fireTime (kept).
     */
    @Query("UPDATE schedule_runs SET status = 'RUNNING', `error` = 'stale RUNNING claim reclaimed; retrying' WHERE scheduleId = :scheduleId AND fireTime = :fireTime AND status = 'RUNNING' AND :nowMs - fireTime > :ttlMs")
    fun reclaimStaleRunning(scheduleId: String, fireTime: Long, nowMs: Long, ttlMs: Long): Int
}

// One write a scheduled run attempted but never executed: the user
// approves it in-app. argsJson carries the exact attempted arguments;
// reason records why it was held (ticket 17/24 wording).
@Entity(tableName = "pending_approvals")
data class PendingApprovalRow(
    @PrimaryKey(autoGenerate = true) val pid: Long = 0,
    val scheduleId: String,
    val toolName: String,
    val argsJson: String,
    val reason: String,
    val createdAt: Long,
)

@Dao
interface PendingApprovalDao {
    @Insert
    fun insert(row: PendingApprovalRow): Long

    @Query("SELECT * FROM pending_approvals WHERE pid = :pid LIMIT 1")
    fun findById(pid: Long): PendingApprovalRow?

    @Query("SELECT * FROM pending_approvals ORDER BY pid ASC")
    fun listAll(): List<PendingApprovalRow>

    @Query("DELETE FROM pending_approvals WHERE pid = :pid")
    fun deleteById(pid: Long): Int
}

// Adapter: Room rows in, ScheduleRun objects out. A -1 insert (IGNORE
// conflict on the idempotency key) is the duplicate-fire signal.
class RoomScheduleRunStore(private val dao: ScheduleRunDao) : ScheduleRunStore {
    override fun claim(scheduleId: String, fireTime: Long): Long? {
        val pid =
            dao.insertIgnore(
                ScheduleRunRow(
                    scheduleId = scheduleId,
                    fireTime = fireTime,
                    status = ScheduleRunStatus.RUNNING.name,
                    error = null,
                    result = null,
                    durationMs = 0L,
                ),
            )
        return if (pid == -1L) null else pid
    }

    override fun finish(
        pid: Long,
        status: ScheduleRunStatus,
        error: String?,
        result: String?,
        durationMs: Long,
        provider: String?,
        planMs: Long,
        toolMs: Long,
        recallMs: Long,
    ) {
        dao.finish(pid, status.name, error, result, durationMs, provider, planMs, toolMs, recallMs)
    }

    override fun find(scheduleId: String, fireTime: Long): ScheduleRun? = dao.find(scheduleId, fireTime)?.toRun()

    override fun list(): List<ScheduleRun> = dao.listAll().map { it.toRun() }

    override fun resetStaleRunning(scheduleId: String, fireTime: Long, nowMs: Long, ttlMs: Long): Long? {
        // One conditional UPDATE wins the lease atomically; the follow-up
        // read only fetches the winner's pid.
        if (dao.reclaimStaleRunning(scheduleId, fireTime, nowMs, ttlMs) == 0) return null
        return dao.findPid(scheduleId, fireTime)
    }

    private fun ScheduleRunRow.toRun(): ScheduleRun =
        ScheduleRun(scheduleId, fireTime, ScheduleRunStatus.valueOf(status), error, result, durationMs, provider, planMs, toolMs, recallMs)
}

class RoomPendingApprovalStore(private val dao: PendingApprovalDao) : PendingApprovalStore {
    override fun save(approval: PendingApproval): PendingApproval {
        val pid =
            dao.insert(
                PendingApprovalRow(
                    scheduleId = approval.scheduleId,
                    toolName = approval.toolName,
                    argsJson = JSONObject(approval.arguments.filterValues { it != null }).toString(),
                    reason = approval.reason,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        return approval.copy(id = ScheduleIds.approvalId(pid))
    }

    override fun list(): List<PendingApproval> =
        dao.listAll().map {
            PendingApproval(it.scheduleId, it.toolName, argsFromJson(it.argsJson), it.reason, ScheduleIds.approvalId(it.pid))
        }

    // Claim-then-read: the DELETE count is the exactly-once guard — a
    // concurrent claim deletes zero rows and loses before any transport
    // touch. Ids round-trip via ScheduleIds (ticket 1: single codec).
    override fun take(id: String): PendingApproval? {
        val pid = ScheduleIds.approvalPid(id) ?: return null
        val row = dao.findById(pid) ?: return null
        if (dao.deleteById(pid) == 0) return null
        return PendingApproval(row.scheduleId, row.toolName, argsFromJson(row.argsJson), row.reason, ScheduleIds.approvalId(row.pid))
    }

    private fun argsFromJson(json: String): Map<String, Any?> {
        val obj = JSONObject(json)
        val out = mutableMapOf<String, Any?>()
        for (key in obj.keys()) {
            out[key] = if (obj.isNull(key)) null else obj.get(key)
        }
        return out
    }
}

// Adapter: Room rows in, Schedule objects out. Ids round-trip via
// ScheduleIds ("sched_12" -> pid 12) so unschedule-by-id works.
class RoomScheduleStore(private val dao: ScheduleDao) : ScheduleStore {
    override fun save(instruction: String, hour: Int, minute: Int, days: Set<Int>?): Schedule {
        val pid = dao.insert(ScheduleRow(instruction = instruction, hour = hour, minute = minute, days = days?.sorted()?.joinToString(",")))
        return Schedule(ScheduleIds.scheduleId(pid), instruction, hour, minute, days)
    }

    override fun list(): List<Schedule> =
        dao.listAll().map {
            Schedule(
                ScheduleIds.scheduleId(it.pid),
                it.instruction,
                it.hour,
                it.minute,
                it.days?.split(",")?.mapNotNull { day -> day.toIntOrNull() }?.toSet(),
            )
        }

    override fun delete(id: String) {
        val pid = ScheduleIds.schedulePid(id) ?: return
        dao.deleteById(pid)
    }
}
