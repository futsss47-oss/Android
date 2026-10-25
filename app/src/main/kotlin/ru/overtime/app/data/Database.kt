package ru.overtime.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import ru.overtime.domain.SessionGateway
import ru.overtime.domain.Span
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Одна запись сверхурочной.
 * [manual] = создана вручную, [edited] = автоматическая запись, исправленная пользователем.
 * Оба вида считаются «как есть», без обрезки по окнам сверхурочной.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMillis: Long,
    val endMillis: Long?,
    val manual: Boolean,
    val edited: Boolean = false,
) {
    fun toSpan(now: LocalDateTime): Span = Span(
        start = TimeConv.toLocal(startMillis),
        end = endMillis?.let { TimeConv.toLocal(it) } ?: now,
        manual = manual || edited,
    )
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY startMillis DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY startMillis DESC")
    fun all(): List<SessionEntity>

    @Insert
    fun insert(e: SessionEntity): Long

    @Update
    fun update(e: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    fun delete(id: Long)

    @Query("UPDATE sessions SET endMillis = :end WHERE id = :id")
    fun setEnd(id: Long, end: Long?)

    @Query("SELECT * FROM sessions WHERE manual = 1 AND endMillis IS NULL LIMIT 1")
    fun openManual(): SessionEntity?
}

@Database(entities = [SessionEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "overtime.db").build()
    }
}

object TimeConv {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun toMillis(t: LocalDateTime): Long = t.atZone(zone).toInstant().toEpochMilli()

    fun toLocal(ms: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
}

/** Связывает движок учёта с базой. Вызывать только не из главного потока. */
class RoomSessionGateway(private val dao: SessionDao) : SessionGateway {
    override fun open(start: LocalDateTime): Long =
        dao.insert(SessionEntity(startMillis = TimeConv.toMillis(start), endMillis = null, manual = false))

    override fun close(id: Long, end: LocalDateTime) = dao.setEnd(id, TimeConv.toMillis(end))

    override fun reopen(id: Long) = dao.setEnd(id, null)
}
