package com.assetboard.data

import android.app.Application
import com.assetboard.domain.Habit
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HabitTest {
    private val f = Fixture()

    @After fun close() = f.close()

    private suspend fun on(date: String) = f.habits.observe(LocalDate.parse(date)).first()

    @Test fun toggleChecksAndUnchecksToday() = f.run {
        assertTrue(f.habits.toggle(Habit.WORKOUT))
        assertTrue(f.habits.toggle(Habit.WATER))
        assertEquals(setOf(Habit.WORKOUT, Habit.WATER), on("2026-10-20"))
        assertFalse(f.habits.toggle(Habit.WORKOUT))
        assertEquals(setOf(Habit.WATER), on("2026-10-20"))
    }

    @Test fun aNewDayStartsUncheckedAndKeepsYesterday() = f.run {
        f.setNow("2026-10-20", "23:59")
        f.habits.toggle(Habit.SLEEP)
        f.setNow("2026-10-21", "00:00")
        assertEquals(emptySet<Habit>(), on("2026-10-21"))
        // A tap after midnight belongs to the new day.
        f.habits.toggle(Habit.SLEEP)
        assertEquals(setOf(Habit.SLEEP), on("2026-10-20"))
        assertEquals(setOf(Habit.SLEEP), on("2026-10-21"))
    }

    @Test fun firstDateIsTheEarliestCheck() = f.run {
        assertNull(f.habits.observeFirstDate().first())
        f.setNow("2026-10-18")
        f.habits.toggle(Habit.DIET)
        f.setNow("2026-10-20")
        f.habits.toggle(Habit.DIET)
        assertEquals(LocalDate.parse("2026-10-18"), f.habits.observeFirstDate().first())
    }

    @Test fun habitsNeverTouchTheDiary() = f.run {
        f.habits.toggle(Habit.SUPPLEMENTS)
        assertTrue(f.db.diaryDao().all().isEmpty())
    }
}
