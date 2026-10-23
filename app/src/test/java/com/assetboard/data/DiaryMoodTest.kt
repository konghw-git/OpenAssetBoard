package com.assetboard.data

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.plus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A day's mood: beside the text, never in its way. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DiaryMoodTest {
    private val f = Fixture()

    @After fun close() = f.close()

    private val day = date("2026-10-20")

    @Test fun aMoodOnAnEmptyDayHoldsAnEmptyEntryThatTheTextThenTakesOver() = f.run {
        f.diary.setMood(day, 4)
        val held = f.diary.entryOn(day)!!
        assertEquals("", held.body)
        assertEquals(4, held.mood)
        // An empty entry is not a written day.
        assertTrue(f.diary.observeDatesWithText(day, day).first().isEmpty())

        val s = DiarySession(f.diary, day, f.scope + f.gate.bound(), clock = { f.now })
        s.open(null)
        s.onInput("写了几行")
        assertTrue(s.saveNow())
        s.close()
        val e = f.diary.entryOn(day)!!
        assertEquals(held.id, e.id)
        assertEquals("写了几行", e.body)
        assertEquals(4, e.mood)
    }

    @Test fun settingTheMoodNeverTouchesTheBodyOrItsRevision() = f.run {
        val s = DiarySession(f.diary, day, f.scope + f.gate.bound(), clock = { f.now })
        s.open(null)
        s.onInput("今天很好")
        s.saveNow()
        val before = f.diary.entryOn(day)!!
        f.diary.setMood(day, 5)
        // The open session's next save still lands: the mood did not move body_rev.
        s.onInput("今天很好，晚上更好")
        assertTrue(s.saveNow())
        s.close()
        val after = f.diary.entryOn(day)!!
        assertEquals(before.bodyRev + 1, after.bodyRev)
        assertEquals("今天很好，晚上更好", after.body)
        assertEquals(5, after.mood)
    }

    @Test fun clearingAndBadValues() = f.run {
        f.diary.setMood(day, 3)
        f.diary.setMood(day, null)
        assertNull(f.diary.entryOn(day)!!.mood)
        // Clearing a day with no entry makes none.
        f.diary.setMood(date("2026-10-18"), null)
        assertNull(f.diary.entryOn(date("2026-10-18")))
        expectRule { f.diary.setMood(day, 9) }
    }

    @Test fun theCalendarReadsTheMonthsMoods() = f.run {
        f.diary.setMood(date("2026-10-01"), 1)
        f.diary.setMood(date("2026-10-20"), 5)
        f.diary.setMood(date("2026-11-01"), 3)
        assertEquals(mapOf("2026-10-01" to 1, "2026-10-20" to 5), f.diary.observeMoods(date("2026-10-01"), date("2026-10-31")).first())
    }
}
