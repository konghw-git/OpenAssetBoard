package com.assetboard.domain

import com.assetboard.data.Countdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CountdownsTest {
    private val today = LocalDate.parse("2026-10-25")
    private fun c(title: String, date: String, yearly: Boolean = false, pinned: Boolean = false, created: Long = 0) =
        Countdown(title, title, date, yearly, pinned, createdAt = created, updatedAt = created)

    @Test fun aDayAheadCountsDownAndAPassedOneCountsUp() {
        val ahead = Countdowns.state(c("考试", "2026-12-26"), today)!!
        assertEquals(62, ahead.days)
        assertEquals("还有", ahead.lead)
        val passed = Countdowns.state(c("入职", "2026-10-15"), today)!!
        assertEquals(-10, passed.days)
        assertTrue(passed.passed)
        assertEquals("已经", passed.lead)
        assertEquals("就是今天", Countdowns.state(c("今天", "2026-10-25"), today)!!.lead)
    }

    @Test fun aYearlyOneCountsToItsNextOccurrenceAndNumbersIt() {
        val birthday = Countdowns.state(c("生日", "2001-08-02", yearly = true), today)!!
        assertEquals(LocalDate.parse("2027-08-02"), birthday.target)
        assertEquals(26, birthday.nth)
        assertEquals(281, birthday.days)
        // Today itself is this year's occurrence, not next year's.
        val onTheDay = Countdowns.state(c("纪念日", "2020-10-25", yearly = true), today)!!
        assertEquals(0, onTheDay.days)
        assertEquals(6, onTheDay.nth)
        // Not yet reached its first date: counts to that, with no number.
        val first = Countdowns.state(c("婚礼", "2027-05-01", yearly = true), today)!!
        assertEquals(LocalDate.parse("2027-05-01"), first.target)
        assertNull(first.nth)
    }

    @Test fun theTwentyNinthOfFebruaryFallsOnTheTwentyEighthInOtherYears() {
        assertEquals(LocalDate.parse("2027-02-28"), Countdowns.nextOccurrence(LocalDate.parse("2024-02-29"), today))
        assertEquals(LocalDate.parse("2028-02-29"), Countdowns.nextOccurrence(LocalDate.parse("2024-02-29"), LocalDate.parse("2028-01-01")))
    }

    @Test fun orderIsPinnedThenNearestAheadThenMostRecentlyPassed() {
        val list = listOf(
            c("远", "2027-01-01"),
            c("过去久", "2026-01-01"),
            c("近", "2026-10-30"),
            c("刚过", "2026-10-20"),
            c("置顶", "2027-06-01", pinned = true),
        )
        assertEquals(listOf("置顶", "近", "远", "刚过", "过去久"), Countdowns.ordered(list, today).map { it.countdown.title })
    }

    @Test fun aBadDateIsLeftOut() {
        assertTrue(Countdowns.ordered(listOf(c("坏", "not-a-date")), today).isEmpty())
    }

    @Test fun subtitleNamesTheDayAndTheOccurrence() {
        val s = Countdowns.state(c("生日", "2001-10-28", yearly = true), today)!!
        assertEquals("10月28日 周三 · 第 25 年", Countdowns.subtitle(s, today))
        val next = Countdowns.state(c("元旦", "2027-01-01"), today)!!
        assertEquals("2027年1月1日 周五", Countdowns.subtitle(next, today))
    }
}
