package com.aurviz.agent

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
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

    // Offline FTS fallback (ticket #31): prefix MATCH over the FTS index,
    // not a full-table scan. The FTS table mirrors `memories` via Room's
    // content-entity triggers; this query only filters candidates — the
    // boost ranking still lives in Memory.kt.
    @Query("SELECT memories.* FROM memories JOIN memoriesFts ON memories.pid = memoriesFts.rowid WHERE memoriesFts MATCH :query ORDER BY pid ASC")
    fun searchFts(query: String): List<MemoryRow>

    @Insert
    fun insert(row: MemoryRow): Long

    @Query("DELETE FROM memories WHERE pid = :pid")
    fun deleteById(pid: Long)

    @Query("UPDATE memories SET lastUsed = :nowMs, useCount = useCount + 1 WHERE pid = :pid")
    fun markUsed(pid: Long, nowMs: Long)
}

// FTS4 content index over memories.text (ticket #31 offline fallback).
// contentEntity keeps it in sync via generated triggers; queries use MATCH.
@Fts4(contentEntity = MemoryRow::class)
@Entity(tableName = "memoriesFts")
data class MemoryFts(
    val text: String,
)

@Database(
    entities = [MemoryRow::class, MemoryFts::class, ScheduleRow::class, ScheduleRunRow::class, PendingApprovalRow::class],
    version = 5,
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

// Version 4 -> 5 adds the ticket #31 offline FTS index. Statements copied
// from Room's generated createAllTables (AppDb_Impl) so the migrated schema
// validates identically; the rebuild backfills rows saved before upgrade.
val MIGRATION_4_5 =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `memoriesFts` USING FTS4(`text` TEXT NOT NULL, content=`memories`)")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_memoriesFts_BEFORE_UPDATE BEFORE UPDATE ON `memories` BEGIN DELETE FROM `memoriesFts` WHERE `docid`=OLD.`rowid`; END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_memoriesFts_BEFORE_DELETE BEFORE DELETE ON `memories` BEGIN DELETE FROM `memoriesFts` WHERE `docid`=OLD.`rowid`; END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_memoriesFts_AFTER_UPDATE AFTER UPDATE ON `memories` BEGIN INSERT INTO `memoriesFts`(`docid`, `text`) VALUES (NEW.`rowid`, NEW.`text`); END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_memoriesFts_AFTER_INSERT AFTER INSERT ON `memories` BEGIN INSERT INTO `memoriesFts`(`docid`, `text`) VALUES (NEW.`rowid`, NEW.`text`); END")
            db.execSQL("INSERT INTO `memoriesFts`(`memoriesFts`) VALUES ('rebuild')")
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

    // Real offline FTS: the MATCH runs in SQLite over the memoriesFts index.
    override fun searchFts(ftsQuery: String): List<Memory> =
        dao.searchFts(ftsQuery).map {
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
