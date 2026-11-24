package com.aurviz.agent

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Ticket #92: persisted Past Interactions. One row per completed command:
// the Session it ran in, the command text, the settled response, the
// provider that answered, and when it finished. Room only stores rows —
// grouping (Today / Previous 7 Days) stays in pure helpers in the ui
// package, so the unit tests never need a database.

// One persisted Interaction (GLOSSARY Interaction): a past command plus
// its outcome. `failed` rides as INTEGER (Room Boolean mapping).
@Entity(tableName = "interactions")
data class InteractionRow(
    @PrimaryKey(autoGenerate = true) val pid: Long = 0,
    val sessionId: String,
    val command: String,
    val responseText: String,
    val provider: String?,
    val timestampMs: Long,
    val failed: Boolean,
)

@Dao
interface InteractionDao {
    @Insert
    fun insert(row: InteractionRow): Long

    @Query("SELECT * FROM interactions ORDER BY timestampMs DESC LIMIT :limit")
    fun listRecent(limit: Int): List<InteractionRow>

    @Query("DELETE FROM interactions WHERE pid NOT IN (SELECT pid FROM interactions ORDER BY timestampMs DESC LIMIT :keep)")
    fun prune(keep: Int)
}

// Version 5 -> 6 adds the ticket #92 interactions table plus timing and
// provider columns on schedule_runs (headless-run analytics). New columns
// carry neutral defaults so pre-upgrade rows read as untimed/unattributed.
val MIGRATION_5_6 =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `interactions` (" +
                    "`pid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`sessionId` TEXT NOT NULL, " +
                    "`command` TEXT NOT NULL, " +
                    "`responseText` TEXT NOT NULL, " +
                    "`provider` TEXT, " +
                    "`timestampMs` INTEGER NOT NULL, " +
                    "`failed` INTEGER NOT NULL)",
            )
            db.execSQL("ALTER TABLE `schedule_runs` ADD COLUMN `provider` TEXT")
            db.execSQL("ALTER TABLE `schedule_runs` ADD COLUMN `planMs` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `schedule_runs` ADD COLUMN `toolMs` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `schedule_runs` ADD COLUMN `recallMs` INTEGER NOT NULL DEFAULT 0")
        }
    }

// Single ordered migration chain. AgentViewModel AND ScheduleWork both
// build AppDb from this array — a new Migration lands here once and is
// registered at every open site, never at just one.
val AppMigrations = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
