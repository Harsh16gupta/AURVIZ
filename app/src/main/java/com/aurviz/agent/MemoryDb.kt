package com.aurviz.agent

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

// Session 1 thin wiring: the same MemoryStore plug, backed by Room.
// Recall ranking still lives in Memory.kt (pure logic); Room only
// stores rows, so the unit tests never need a database.

// One row in the on-phone database. pid is the phone's own number;
// Memory.id ("room_<pid>") is derived in RoomMemoryStore below.
@Entity(tableName = "memories")
data class MemoryRow(
    @PrimaryKey(autoGenerate = true) val pid: Long = 0,
    val text: String,
    val source: String,
)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY pid ASC")
    fun listAll(): List<MemoryRow>

    @Insert
    fun insert(row: MemoryRow): Long

    @Query("DELETE FROM memories WHERE pid = :pid")
    fun deleteById(pid: Long)
}

@Database(entities = [MemoryRow::class, ScheduleRow::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun memories(): MemoryDao
    abstract fun schedules(): ScheduleDao
}

// Adapter: Room rows in, Memory objects out. Ids round-trip
// ("room_12" -> pid 12) so forget-by-id works on the phone too.
class RoomMemoryStore(private val dao: MemoryDao) : MemoryStore {
    override fun save(text: String, source: String): Memory {
        val pid = dao.insert(MemoryRow(text = text, source = source))
        return Memory("room_$pid", text, source)
    }

    override fun list(): List<Memory> =
        dao.listAll().map { Memory("room_${it.pid}", it.text, it.source) }

    override fun delete(id: String) {
        val pid = id.removePrefix("room_").toLongOrNull() ?: return
        dao.deleteById(pid)
    }
}
