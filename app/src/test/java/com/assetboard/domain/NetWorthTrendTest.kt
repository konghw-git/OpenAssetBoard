package com.assetboard.domain

import com.assetboard.data.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetWorthTrendTest {
    private val day = 24L * 3600 * 1000
    private val now = 1_000 * day

    private fun point(daysAgo: Long, assets: Long, debts: Long) =
        NetWorthPoint(Snapshot("s$daysAgo", now - daysAgo * day), assets, debts, assets - debts)

    private val series = listOf(point(400, 100, 0), point(120, 300, 50), point(60, 500, 100), point(10, 400, 20))

    @Test fun periodStartsAtTheLastStocktakeBeforeIt() {
        val p = NetWorthTrend.period(series, 92, now)!!
        assertTrue(p.anchored)
        assertEquals(listOf("s120", "s60", "s10"), p.points.map { it.snapshot.id })
        // 380 - 250, measured between two real stocktakes.
        assertEquals(130L, p.change)
        assertEquals(100L, p.assetsChange)
        assertEquals(-30L, p.debtsChange)
        assertEquals(p.change, p.assetsChange - p.debtsChange)
        assertEquals("s60", p.high.snapshot.id)
        assertEquals("s120", p.low.snapshot.id)
    }

    @Test fun allOfHistoryHasNoAnchor() {
        val p = NetWorthTrend.period(series, null, now)!!
        assertFalse(p.anchored)
        assertEquals(4, p.points.size)
        assertEquals(280L, p.change)
        assertEquals(2.8, p.changeRatio!!, 1e-9)
    }

    @Test fun aPeriodWithoutStocktakesShowsTheLatestOnly() {
        val p = NetWorthTrend.period(series.take(2), 92, now)!!
        assertEquals(listOf("s120"), p.points.map { it.snapshot.id })
        assertEquals(0L, p.change)
        assertNull(p.changeRatio)
    }

    @Test fun aPeriodLongerThanHistoryTakesItAll() {
        val p = NetWorthTrend.period(series, 3650, now)!!
        assertFalse(p.anchored)
        assertEquals(4, p.points.size)
    }

    @Test fun noStocktakesNoPeriod() {
        assertNull(NetWorthTrend.period(emptyList(), 92, now))
    }
}
