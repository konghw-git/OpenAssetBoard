package com.assetboard.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.withTransaction
import com.assetboard.domain.Habit
import com.assetboard.domain.HabitDay
import com.assetboard.domain.LedgerException
import com.assetboard.domain.todo.TodoViews
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

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
    @Query("SELECT * FROM habits ORDER BY position") fun observeHabits(): Flow<List<Habit>>
    @Query("SELECT * FROM habits ORDER BY position") suspend fun habits(): List<Habit>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putHabits(items: List<Habit>)
    @Query("DELETE FROM habits WHERE `key` = :key") suspend fun deleteHabit(key: String)
    @Query("SELECT COUNT(*) FROM habit_checks WHERE habit = :habit") suspend fun checksOf(habit: String): Int
    @Query("DELETE FROM habits") suspend fun clearHabits()

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
 * The daily habits and their checks. Only today can be checked: the day is taken from the clock at
 * the moment of the tap, so after midnight every habit starts unchecked again and yesterday's
 * record stays as it was.
 *
 * Until habits are first edited the table is empty and [Habit.DEFAULTS] stand in for it; the
 * first edit writes them out. That covers new installs, upgrades and backups from before habits
 * could change in one rule.
 */
class HabitRepository(
    private val db: AppDatabase,
    private val gate: WriteGate,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val dao = db.habitDao()

    fun today(): LocalDate = TodoViews.localDate(clock(), zone())

    /** Every habit there is or was, in order. */
    fun observeAll(): Flow<List<Habit>> = dao.observeHabits().map { it.ifEmpty { Habit.DEFAULTS } }

    /** The habits checked off from today on, in order. */
    fun observeActive(): Flow<List<Habit>> = observeAll().map { all -> all.filter { it.active } }

    /** The keys checked on [date]. */
    fun observe(date: LocalDate): Flow<Set<String>> = dao.observeOn(date.toString()).map { it.toSet() }

    /** What [date] shows: its habits and its checks. */
    fun observeDay(date: LocalDate): Flow<HabitDay> =
        combine(observeAll(), observe(date)) { all, checked -> HabitDay(Habit.shownOn(date, all, checked), checked) }

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

    /** Adds a habit at the end, counted from today; returns its key. */
    suspend fun add(label: String, icon: String, color: Long): String = gate.write {
        val name = checkLabel(label)
        db.withTransaction {
            val all = current()
            if (all.count { it.active } >= Habit.MAX_ACTIVE) throw LedgerException("每日打卡最多 ${Habit.MAX_ACTIVE} 项")
            val key = UUID.randomUUID().toString()
            dao.putHabits(all + Habit(key, name, icon, color, (all.maxOfOrNull { it.position } ?: -1) + 1, addedOn = today().toString()))
            key
        }
    }

    /** Renames or restyles a habit; the days it was already part of show the change too. */
    suspend fun update(key: String, label: String, icon: String, color: Long) = gate.write {
        val name = checkLabel(label)
        db.withTransaction {
            val all = current()
            val h = all.firstOrNull { it.key == key && it.active } ?: throw LedgerException("这项打卡已经不在了")
            dao.putHabits(all.map { if (it.key == key) h.copy(label = name, icon = icon, color = color) else it })
        }
    }

    /** Puts the active habits in the order of [keys]; removed ones keep their places after them. */
    suspend fun reorder(keys: List<String>) = gate.write {
        db.withTransaction {
            val all = current()
            val active = keys.mapNotNull { k -> all.firstOrNull { it.key == k && it.active } }
            val rest = all.filter { it !in active }
            dao.putHabits((active + rest).mapIndexed { i, h -> h.copy(position = i) })
        }
    }

    /**
     * Takes a habit off from today. One never checked goes entirely; one with checks stays in the
     * days it was part of, today too if it was checked today.
     */
    suspend fun remove(key: String) = gate.write {
        db.withTransaction {
            val all = current()
            if (all.count { it.active } <= 1) throw LedgerException("至少留一项每日打卡")
            val h = all.firstOrNull { it.key == key && it.active } ?: return@withTransaction
            dao.putHabits(all)
            if (dao.checksOf(key) == 0) dao.deleteHabit(key)
            else dao.putHabits(listOf(h.copy(removedOn = today().toString())))
        }
    }

    /** The table as it stands, the defaults written out if it has never been edited. */
    private suspend fun current(): List<Habit> = dao.habits().ifEmpty { Habit.DEFAULTS }

    private fun checkLabel(label: String): String {
        val name = label.trim()
        if (name.isEmpty()) throw LedgerException("给这项打卡起个名字")
        if (name.length > Habit.MAX_LABEL) throw LedgerException("名字最多 ${Habit.MAX_LABEL} 个字")
        return name
    }
}
