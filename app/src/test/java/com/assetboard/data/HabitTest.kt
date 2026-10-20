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
    private fun keys(vararg h: Habit) = h.map { it.key }.toSet()
    private suspend fun active() = f.habits.observeActive().first().map { it.label }
    private suspend fun shown(date: String) = f.habits.observeDay(LocalDate.parse(date)).first().shown.map { it.label }

    @Test fun toggleChecksAndUnchecksToday() = f.run {
        assertTrue(f.habits.toggle(Habit.WORKOUT))
        assertTrue(f.habits.toggle(Habit.WATER))
        assertEquals(keys(Habit.WORKOUT, Habit.WATER), on("2026-10-20"))
        assertFalse(f.habits.toggle(Habit.WORKOUT))
        assertEquals(keys(Habit.WATER), on("2026-10-20"))
    }

    @Test fun aNewDayStartsUncheckedAndKeepsYesterday() = f.run {
        f.setNow("2026-10-20", "23:59")
        f.habits.toggle(Habit.SLEEP)
        f.setNow("2026-10-21", "00:00")
        assertEquals(emptySet<String>(), on("2026-10-21"))
        // A tap after midnight belongs to the new day.
        f.habits.toggle(Habit.SLEEP)
        assertEquals(keys(Habit.SLEEP), on("2026-10-20"))
        assertEquals(keys(Habit.SLEEP), on("2026-10-21"))
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

    @Test fun untouchedHabitsAreTheFiveBuiltIn() = f.run {
        assertEquals(listOf("吃补剂", "健身", "控制饮食", "喝够水", "早睡早起"), active())
        assertTrue(f.db.habitDao().habits().isEmpty())
    }

    @Test fun aNewHabitCountsFromToday() = f.run {
        f.setNow("2026-10-20")
        f.habits.add("读书", "book", 0xFF2A9D8F)
        assertEquals(listOf("吃补剂", "健身", "控制饮食", "喝够水", "早睡早起", "读书"), active())
        // The first edit writes the built-in five out with it.
        assertEquals(6, f.db.habitDao().habits().size)
        assertEquals(6, shown("2026-10-20").size)
        // Yesterday never had it, so it does not show there as missed.
        assertEquals(5, shown("2026-10-19").size)
    }

    @Test fun aRemovedHabitStaysInTheDaysItWasPartOf() = f.run {
        f.setNow("2026-10-19")
        f.habits.toggle(Habit.WORKOUT)
        f.setNow("2026-10-20")
        f.habits.remove(Habit.WORKOUT.key)
        assertFalse("健身" in active())
        assertTrue("健身" in shown("2026-10-19"))
        assertFalse("健身" in shown("2026-10-20"))
        assertEquals(keys(Habit.WORKOUT), on("2026-10-19"))
    }

    @Test fun aHabitCheckedTodayAndRemovedStillShowsToday() = f.run {
        f.habits.toggle(Habit.WATER)
        f.habits.remove(Habit.WATER.key)
        assertFalse("喝够水" in active())
        assertTrue("喝够水" in shown("2026-10-20"))
    }

    @Test fun aHabitNeverCheckedGoesEntirely() = f.run {
        f.habits.remove(Habit.DIET.key)
        assertEquals(4, f.db.habitDao().habits().size)
        assertFalse("控制饮食" in shown("2026-10-01"))
    }

    @Test fun habitsHaveLimits() = f.run {
        expectRule { f.habits.add("  ", "book", 0xFF2A9D8F) }
        expectRule { f.habits.add("一二三四五六七八九", "book", 0xFF2A9D8F) }
        f.habits.add("读书", "book", 0xFF2A9D8F)
        f.habits.add("冥想", "meditate", 0xFF7A62C2)
        expectRule { f.habits.add("第八项", "sun", 0xFF3D8FD1) }
        for (h in listOf(Habit.SUPPLEMENTS, Habit.WORKOUT, Habit.DIET, Habit.WATER, Habit.SLEEP)) f.habits.remove(h.key)
        assertEquals(listOf("读书", "冥想"), active())
        val read = f.habits.observeActive().first().first()
        f.habits.remove(read.key)
        val last = f.habits.observeActive().first().single()
        expectRule { f.habits.remove(last.key) }
    }

    @Test fun renameAndReorder() = f.run {
        f.habits.update(Habit.WATER.key, "喝水 2L", "water", 0xFF2A9D8F)
        f.habits.reorder(listOf(Habit.SLEEP, Habit.WATER, Habit.SUPPLEMENTS, Habit.WORKOUT, Habit.DIET).map { it.key })
        assertEquals(listOf("早睡早起", "喝水 2L", "吃补剂", "健身", "控制饮食"), active())
        // The key stays, so the days already checked keep their tick.
        assertEquals(Habit.WATER.key, f.habits.observeActive().first()[1].key)
    }
}
