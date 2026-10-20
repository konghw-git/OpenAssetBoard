package com.assetboard.domain

import com.assetboard.data.BodyMeasurement
import com.assetboard.domain.health.HealthTrend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Periods, the moving average and target progress; days without a weigh-in are simply absent. */
class HealthTrendTest {
    private fun m(date: String, g: Long, fat: Int? = null) = BodyMeasurement("m-$date", date, 0, g, fat, null, 0, 0)

    private fun now(date: String) = LocalDate.parse(date).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun periodStartsAtTheLastRecordBeforeIt() {
        val all = listOf(m("2026-08-01", 95_000), m("2026-09-15", 93_000), m("2026-10-10", 92_500), m("2026-10-17", 92_100))
        val p = HealthTrend.period(all, 30, now("2026-10-20"))!!
        assertTrue(p.anchored)
        assertEquals("2026-09-15", p.start.date)
        assertEquals(-900L, p.weightChange)
        assertEquals(2, p.records)
    }

    @Test fun nothingBeforeThePeriodFallsBackToItsFirstRecord() {
        val all = listOf(m("2026-10-10", 92_500), m("2026-10-17", 92_100))
        val p = HealthTrend.period(all, 30, now("2026-10-20"))!!
        assertFalse(p.anchored)
        assertEquals("2026-10-10", p.start.date)
        assertEquals(2, p.records)
        val everything = HealthTrend.period(all.reversed(), null, now("2026-10-20"))!!
        assertFalse(everything.anchored)
        assertEquals("2026-10-10", everything.start.date)
    }

    @Test fun nothingInsideThePeriodIsASinglePointWithoutChange() {
        val p = HealthTrend.period(listOf(m("2026-01-01", 95_000)), 30, now("2026-10-20"))!!
        assertEquals(1, p.points.size)
        assertNull(p.weightChange)
        assertEquals(0, p.records)
        assertNull(HealthTrend.period(emptyList(), 30, 0))
    }

    @Test fun fatChangeNeedsBothEnds() {
        val p = HealthTrend.period(listOf(m("2026-10-01", 93_000, 2_600), m("2026-10-17", 92_100, 2_510)), null, 0)!!
        assertEquals(-90, p.fatChange)
        val q = HealthTrend.period(listOf(m("2026-10-01", 93_000, 2_600), m("2026-10-17", 92_100)), null, 0)!!
        assertNull(q.fatChange)
    }

    @Test fun movingAverageIsARollingCalendarWindow() {
        val pts = listOf(m("2026-10-01", 90_000), m("2026-10-02", 92_000), m("2026-10-04", 94_000))
        val runs = HealthTrend.movingAverage(pts) { it.weightG }
        assertEquals(1, runs.size)
        // 10-01 alone has no average; 10-02 averages two days; 10-04 averages all three.
        assertEquals(listOf("2026-10-02" to 91_000L, "2026-10-04" to 92_000L), runs[0])
    }

    @Test fun aGapLongerThanTheWindowSplitsTheAverage() {
        val pts = listOf(
            m("2026-09-01", 90_000), m("2026-09-02", 90_200), m("2026-09-03", 90_400),
            m("2026-09-20", 89_000), // alone in its window: no point, and the line must not bridge to it
            m("2026-10-01", 88_000), m("2026-10-02", 88_200), m("2026-10-04", 88_400),
        )
        val runs = HealthTrend.movingAverage(pts) { it.weightG }
        assertEquals(2, runs.size)
        assertEquals(listOf("2026-09-02", "2026-09-03"), runs[0].map { it.first })
        assertEquals(listOf("2026-10-02", "2026-10-04"), runs[1].map { it.first })
    }

    @Test fun aSinglePointRunIsDropped() {
        val pts = listOf(m("2026-09-01", 90_000), m("2026-09-02", 90_200), m("2026-09-30", 89_000))
        assertTrue(HealthTrend.movingAverage(pts) { it.weightG }.isEmpty())
    }

    @Test fun bodyFatAverageSkipsDaysWithoutIt() {
        val pts = listOf(m("2026-10-01", 90_000, 2_600), m("2026-10-02", 90_000), m("2026-10-03", 90_000, 2_500), m("2026-10-04", 90_000, 2_400))
        val runs = HealthTrend.movingAverage(pts) { it.bodyFatBp?.toLong() }
        assertEquals(listOf(listOf("2026-10-03" to 2_550L, "2026-10-04" to 2_500L)), runs)
    }

    @Test fun targetProgress() {
        assertEquals(0.5, HealthTrend.targetProgress(100_000, 90_000, 80_000)!!, 1e-9)
        assertEquals(1.0, HealthTrend.targetProgress(100_000, 80_000, 80_000)!!, 1e-9)
        assertEquals(0.0, HealthTrend.targetProgress(100_000, 101_000, 80_000)!!, 1e-9)
        assertNull(HealthTrend.targetProgress(80_000, 85_000, 80_000)) // started at the target
        assertNull(HealthTrend.targetProgress(85_000, 75_000, 80_000)) // crossed it
        assertEquals(0.5, HealthTrend.targetProgress(60_000, 65_000, 70_000)!!, 1e-9) // gaining toward it
    }

    @Test fun labelsStateCoverageWithoutNagging() {
        assertEquals("近 30 天 · 8 次记录", HealthTrend.coverageLabel(30, 8))
        assertEquals("上次称重 · 3 天前", HealthTrend.lastSeenLabel(3))
        assertEquals("已经 8 天没记录了", HealthTrend.lastSeenLabel(8))
    }
}
