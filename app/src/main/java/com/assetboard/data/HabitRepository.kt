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

/**
 * One daily habit on one day: done, or [skipped] (a rest day, which neither counts as done nor as
 * missed). A row exists only while it is one of the two.
 */
@Serializable
@Entity(tableName = "habit_checks", primaryKeys = ["date", "habit"])
data class HabitCheck(
    val date: String,
    /** [Habit.key]. */
    val habit: String,
    @ColumnInfo(name = "checked_at") val checkedAt: Long,
    @ColumnInfo(defaultValue = "0") val skipped: Boolean = false,
)

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits ORDER BY position") fun observeHabits(): Flow<List<Habit>>
    @Query("SELECT * FROM habits ORDER BY position") suspend fun habits(): List<Habit>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putHabits(items: List<Habit>)
    @Query("DELETE FROM habits WHERE `key` = :key") suspend fun deleteHabit(key: String)
    @Query("SELECT COUNT(*) FROM habit_checks WHERE habit = :habit AND skipped = 0") suspend fun checksOf(habit: String): Int
    @Query("DELETE FROM habit_checks WHERE habit = :habit") suspend fun deleteChecksOf(habit: String)
    @Query("DELETE FROM habits") suspend fun clearHabits()

    @Query("SELECT habit FROM habit_checks WHERE date = :date AND skipped = 0") fun observeOn(date: String): Flow<List<String>>
    @Query("SELECT habit FROM habit_checks WHERE date = :date AND skipped = 1") fun observeSkippedOn(date: String): Flow<List<String>>
    @Query("SELECT MIN(date) FROM habit_checks WHERE skipped = 0") fun observeFirstDate(): Flow<String?>
    @Query("SELECT * FROM habit_checks WHERE date = :date AND habit = :habit") suspend fun row(date: String, habit: String): HabitCheck?
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

    /** The keys skipped on [date]: a rest day for them. */
    fun observeSkipped(date: LocalDate): Flow<Set<String>> = dao.observeSkippedOn(date.toString()).map { it.toSet() }

    /** What [date] shows: its habits, its checks and its rest days. */
    fun observeDay(date: LocalDate): Flow<HabitDay> =
        combine(observeAll(), observe(date), observeSkipped(date)) { all, checked, skipped ->
            HabitDay(Habit.shownOn(date, all, checked), checked, skipped)
        }

    /** The first day anything was checked: days from then on show their record, even an empty one. */
    fun observeFirstDate(): Flow<LocalDate?> = dao.observeFirstDate().map { it?.let(LocalDate::parse) }

    /** Checks [habit] for today, or unchecks it; a skipped one becomes checked. Returns whether it is now checked. */
    suspend fun toggle(habit: Habit): Boolean = gate.write {
        val date = today().toString()
        db.withTransaction {
            val row = dao.row(date, habit.key)
            if (row != null) dao.delete(date, habit.key)
            if (row == null || row.skipped) {
                dao.insert(HabitCheck(date, habit.key, clock()))
                true
            } else {
                false
            }
        }
    }

    /**
     * Makes today a rest day for [habit], or takes the rest day back; a checked one is skipped
     * instead. Returns whether it is now skipped.
     */
    suspend fun toggleSkip(habit: Habit): Boolean = gate.write {
        val date = today().toString()
        db.withTransaction {
            val row = dao.row(date, habit.key)
            if (row != null) dao.delete(date, habit.key)
            if (row == null || !row.skipped) {
                dao.insert(HabitCheck(date, habit.key, clock(), skipped = true))
                true
            } else {
                false
            }
        }
    }

    /** Adds a habit at the end, counted from today; returns its key. */
    suspend fun add(label: String, icon: String, color: Long, rubric: String = ""): String = gate.write {
        val name = checkLabel(label)
        val what = checkRubric(rubric)
        db.withTransaction {
            val all = current()
            if (all.count { it.active } >= Habit.MAX_ACTIVE) throw LedgerException("每日打卡最多 ${Habit.MAX_ACTIVE} 项")
            val key = UUID.randomUUID().toString()
            dao.putHabits(all + Habit(key, name, icon, color, (all.maxOfOrNull { it.position } ?: -1) + 1, addedOn = today().toString(), rubric = what))
            key
        }
    }

    /** Renames, restyles or re-rubrics a habit; the days it was already part of show the change too. */
    suspend fun update(key: String, label: String, icon: String, color: Long, rubric: String? = null) = gate.write {
        val name = checkLabel(label)
        val what = rubric?.let(::checkRubric)
        db.withTransaction {
            val all = current()
            val h = all.firstOrNull { it.key == key && it.active } ?: throw LedgerException("这项打卡已经不在了")
            dao.putHabits(all.map { if (it.key == key) h.copy(label = name, icon = icon, color = color, rubric = what ?: h.rubric) else it })
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
            // Rest days alone do not keep a habit: it goes with them.
            if (dao.checksOf(key) == 0) {
                dao.deleteChecksOf(key)
                dao.deleteHabit(key)
            } else {
                dao.putHabits(listOf(h.copy(removedOn = today().toString())))
            }
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

    private fun checkRubric(rubric: String): String {
        val clean = Habit.cleanRubric(rubric)
        if (clean.length > Habit.MAX_RUBRIC) throw LedgerException("打卡标准最多 ${Habit.MAX_RUBRIC} 个字")
        return clean
    }
}
