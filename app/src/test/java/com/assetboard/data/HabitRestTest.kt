package com.assetboard.data

import android.app.Application
import com.assetboard.domain.Habit
import com.assetboard.domain.WeekStats
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** A habit's rest day: neither done nor missed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HabitRestTest {
    private val f = Fixture()

    @After fun close() = f.close()

    private val today = LocalDate.parse("2026-10-20")
    private suspend fun day() = f.habits.observeDay(today).first()

    @Test fun aRestDayIsNeitherCheckedNorDue() = f.run {
        assertTrue(f.habits.toggleSkip(Habit.WORKOUT))
        val d = day()
        assertEquals(emptySet<String>(), d.checked)
        assertEquals(setOf(Habit.WORKOUT.key), d.skipped)
        // Five shown, one resting: four asked for.
        assertEquals(5, d.shown.size)
        assertEquals(4, d.due)
        // A rest day is not a first check.
        assertNull(f.habits.observeFirstDate().first())
    }

    @Test fun tappingARestingHabitChecksItAndLongPressingACheckedOneRestsIt() = f.run {
        f.habits.toggleSkip(Habit.WATER)
        assertTrue(f.habits.toggle(Habit.WATER))
        assertEquals(setOf(Habit.WATER.key), day().checked)
        assertEquals(emptySet<String>(), day().skipped)
        assertTrue(f.habits.toggleSkip(Habit.WATER))
        assertEquals(emptySet<String>(), day().checked)
        assertFalse(f.habits.toggleSkip(Habit.WATER))
        assertEquals(emptySet<String>(), day().skipped)
        assertTrue(f.db.habitDao().all().isEmpty())
    }

    @Test fun aHabitWithOnlyRestDaysGoesEntirelyWhenRemoved() = f.run {
        val key = f.habits.add("读书", "book", 0xFF3D8FD1)
        val read = f.habits.observeActive().first().first { it.key == key }
        f.habits.toggleSkip(read)
        f.habits.remove(key)
        assertTrue(f.habits.observeAll().first().none { it.key == key })
        assertTrue(f.db.habitDao().all().none { it.habit == key })
    }

    @Test fun theWeekCountsRestDaysApart() {
        val start = LocalDate.parse("2026-10-19")
        val checks = listOf(
            HabitCheck("2026-10-19", Habit.WORKOUT.key, 1),
            HabitCheck("2026-10-20", Habit.WORKOUT.key, 1, skipped = true),
            HabitCheck("2026-10-21", Habit.WORKOUT.key, 1, skipped = true),
        )
        val s = WeekStats.of(start, start.plusDays(6), emptyList(), checks, emptyList(), emptyList(), emptyList())
        assertEquals(1, s.habits.getValue(Habit.WORKOUT))
        assertEquals(2, s.habitRestDays.getValue(Habit.WORKOUT))
        assertNull(s.habitRestDays[Habit.WATER])
    }
}
