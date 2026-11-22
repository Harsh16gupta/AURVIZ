package com.aurviz.agent

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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

// Adapter: Room rows in, Schedule objects out. Ids round-trip
// ("sched_12" -> pid 12) so unschedule-by-id works on the phone too.
class RoomScheduleStore(private val dao: ScheduleDao) : ScheduleStore {
    override fun save(instruction: String, hour: Int, minute: Int, days: Set<Int>?): Schedule {
        val pid = dao.insert(ScheduleRow(instruction = instruction, hour = hour, minute = minute, days = days?.sorted()?.joinToString(",")))
        return Schedule("sched_$pid", instruction, hour, minute, days)
    }

    override fun list(): List<Schedule> =
        dao.listAll().map {
            Schedule(
                "sched_${it.pid}",
                it.instruction,
                it.hour,
                it.minute,
                it.days?.split(",")?.mapNotNull { day -> day.toIntOrNull() }?.toSet(),
            )
        }

    override fun delete(id: String) {
        val pid = id.removePrefix("sched_").toLongOrNull() ?: return
        dao.deleteById(pid)
    }
}
