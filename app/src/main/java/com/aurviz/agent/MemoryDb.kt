package com.aurviz.agent

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Session 1 thin wiring: the same MemoryStore plug, backed by Room.
// Recall ranking still lives in Memory.kt (pure logic); Room only
// stores rows, so the unit tests never need a database.

// One row in the on-phone database. pid is the phone's own number;
// Memory.id ("room_<pid>") is derived in RoomMemoryStore below.
// Ticket #31: category/importance/createdAt/lastUsed/useCount drive the
// recency plus importance ranking in Memory.kt. No embeddings column:
// embedding cache is ticket #32 and stays cuttable.
@Entity(tableName = "memories")
data class MemoryRow(
    @PrimaryKey(autoGenerate = true) val pid: Long = 0,
    val text: String,
    val source: String,
    val category: String = "general",
    val importance: Int = 0,
    val createdAt: Long = 0L,
    val lastUsed: Long = 0L,
    val useCount: Int = 0,
)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY pid ASC")
    fun listAll(): List<MemoryRow>

    @Insert
    fun insert(row: MemoryRow): Long

    @Query("DELETE FROM memories WHERE pid = :pid")
    fun deleteById(pid: Long)

    @Query("UPDATE memories SET lastUsed = :nowMs, useCount = useCount + 1 WHERE pid = :pid")
    fun markUsed(pid: Long, nowMs: Long)
}

@Database(
    entities = [MemoryRow::class, ScheduleRow::class, ScheduleRunRow::class, PendingApprovalRow::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun memories(): MemoryDao
    abstract fun schedules(): ScheduleDao
    abstract fun runs(): ScheduleRunDao
    abstract fun approvals(): PendingApprovalDao
}

// Version 2 -> 3 adds only the ticket #31 ranking columns; existing rows
// keep their text/source and get neutral defaults (general, 0, unknown
// time), so pre-upgrade recall order is unchanged.
val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `memories` ADD COLUMN `category` TEXT NOT NULL DEFAULT 'general'")
            db.execSQL("ALTER TABLE `memories` ADD COLUMN `importance` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `memories` ADD COLUMN `createdAt` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `memories` ADD COLUMN `lastUsed` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `memories` ADD COLUMN `useCount` INTEGER NOT NULL DEFAULT 0")
        }
    }

// Adapter: Room rows in, Memory objects out. Ids round-trip
// ("room_12" -> pid 12) so forget-by-id works on the phone too.
class RoomMemoryStore(private val dao: MemoryDao) : MemoryStore {
    override fun save(
        text: String,
        source: String,
        category: String,
        importance: Int,
        createdAt: Long,
        lastUsed: Long,
        useCount: Int,
    ): Memory {
        val pid =
            dao.insert(
                MemoryRow(
                    text = text,
                    source = source,
                    category = category,
                    importance = importance,
                    createdAt = createdAt,
                    lastUsed = lastUsed,
                    useCount = useCount,
                ),
            )
        return Memory("room_$pid", text, source, category, importance, createdAt, lastUsed, useCount)
    }

    override fun list(): List<Memory> =
        dao.listAll().map {
            Memory(
                "room_${it.pid}",
                it.text,
                it.source,
                it.category,
                it.importance,
                it.createdAt,
                it.lastUsed,
                it.useCount,
            )
        }

    override fun delete(id: String) {
        val pid = id.removePrefix("room_").toLongOrNull() ?: return
        dao.deleteById(pid)
    }

    override fun markUsed(id: String, nowMs: Long) {
        val pid = id.removePrefix("room_").toLongOrNull() ?: return
        dao.markUsed(pid, nowMs)
    }
}
