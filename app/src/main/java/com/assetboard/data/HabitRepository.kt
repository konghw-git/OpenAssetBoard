package com.assetboard.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Query
import androidx.room.withTransaction
import com.assetboard.domain.Habit
import com.assetboard.domain.todo.TodoViews
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId

/** One daily habit done on one day. A row exists only while it is checked. */
@Serializable
@Entity(tableName = "habit_checks", primaryKeys = ["date", "habit"])
data class HabitCheck(
    val date: String,
    /** [Habit.key]. */
    val habit: String,
    @ColumnInfo(name = "checked_at") val checkedAt: Long,
)

@Dao
interface HabitDao {
    @Query("SELECT habit FROM habit_checks WHERE date = :date") fun observeOn(date: String): Flow<List<String>>
    @Query("SELECT MIN(date) FROM habit_checks") fun observeFirstDate(): Flow<String?>
    @Query("SELECT COUNT(*) FROM habit_checks WHERE date = :date AND habit = :habit") suspend fun count(date: String, habit: String): Int
    @Query("DELETE FROM habit_checks WHERE date = :date AND habit = :habit") suspend fun delete(date: String, habit: String)
    @Insert suspend fun insert(check: HabitCheck)

    @Query("SELECT * FROM habit_checks") suspend fun all(): List<HabitCheck>
    @Insert suspend fun insertAll(items: List<HabitCheck>)
    @Query("DELETE FROM habit_checks") suspend fun clear()
}

/**
 * The five daily habits. Only today can be checked: the day is taken from the clock at the
 * moment of the tap, so after midnight every habit starts unchecked again and yesterday's record
 * stays as it was.
 */
class HabitRepository(
    private val db: AppDatabase,
    private val gate: WriteGate,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val dao = db.habitDao()

    fun today(): LocalDate = TodoViews.localDate(clock(), zone())

    fun observe(date: LocalDate): Flow<Set<Habit>> =
        dao.observeOn(date.toString()).map { keys -> keys.mapNotNull(Habit::of).toSet() }

    /** The first day anything was checked: days from then on show their record, even an empty one. */
    fun observeFirstDate(): Flow<LocalDate?> = dao.observeFirstDate().map { it?.let(LocalDate::parse) }

    /** Checks or unchecks [habit] for today; returns whether it is now checked. */
    suspend fun toggle(habit: Habit): Boolean = gate.write {
        val date = today().toString()
        db.withTransaction {
            if (dao.count(date, habit.key) > 0) {
                dao.delete(date, habit.key)
                false
            } else {
                dao.insert(HabitCheck(date, habit.key, clock()))
                true
            }
        }
    }
}
