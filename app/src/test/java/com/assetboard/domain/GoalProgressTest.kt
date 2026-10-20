package com.assetboard.domain

import com.assetboard.data.Versions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalProgressTest {
    private val day = 86_400_000L

    @Test fun progressIsClampedAndCountsWhatIsLeft() {
        val p = GoalProgress.of(1_000_000, 250_000, emptyList())
        assertEquals(0.25f, p.fraction)
        assertEquals(750_000L, p.remaining)
        assertFalse(p.reached)
        assertEquals(0f, GoalProgress.of(1_000_000, -5, emptyList()).fraction)
        val over = GoalProgress.of(1_000_000, 1_200_000, emptyList())
        assertEquals(1f, over.fraction)
        assertTrue(over.reached)
        assertNull(over.monthsToGo)
    }

    @Test fun monthsComeFromTheLastHalfYearsPace() {
        // 100 000 a month over the last 4 months; an older, slower point is outside the window.
        val history = listOf(0L to 0L, 300 * day to 1_000_000L, 422 * day to 1_400_000L)
        val p = GoalProgress.of(2_000_000, 1_400_000, history)
        // (1.4M - 1.0M) / 122 days * 30.44 ≈ 99 803 a month; 600 000 left -> 7 months.
        assertEquals(7, p.monthsToGo)
    }

    @Test fun noEstimateWithoutGrowthOrEnoughTime() {
        assertNull(GoalProgress.of(2_000_000, 1_000_000, listOf(0L to 1_000_000L, 20 * day to 1_100_000L)).monthsToGo)
        assertNull(GoalProgress.of(2_000_000, 1_000_000, listOf(0L to 1_100_000L, 60 * day to 1_000_000L)).monthsToGo)
        assertNull(GoalProgress.of(2_000_000, 1_000_000, emptyList()).monthsToGo)
    }

    @Test fun inputTakesYuanAndWan() {
        assertEquals(100_000_000L, GoalProgress.parseInput("1000000"))
        assertEquals(100_000_000L, GoalProgress.parseInput("100万"))
        assertEquals(25_000_000L, GoalProgress.parseInput("25.0万"))
        assertEquals(150_000L, GoalProgress.parseInput("1,500"))
        assertNull(GoalProgress.parseInput("0"))
        assertNull(GoalProgress.parseInput("一百万"))
    }

    @Test fun suggestionsAreRoundStepsAboveNetWorth() {
        assertEquals(listOf(50_000_000L, 100_000_000L, 200_000_000L, 500_000_000L), GoalProgress.suggestions(30_000_000))
        assertEquals(10_000_000L, GoalProgress.suggestions(0).first())
    }

    @Test fun versionsCompareByNumber() {
        assertTrue(Versions.isNewer("v0.10.0", "0.9.3"))
        assertTrue(Versions.isNewer("0.8.1", "0.8"))
        assertFalse(Versions.isNewer("0.8.0", "0.8.0-debug"))
        assertFalse(Versions.isNewer("v0.7.1", "0.8.0"))
    }
}
