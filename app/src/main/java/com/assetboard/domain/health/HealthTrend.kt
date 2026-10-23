package com.assetboard.domain.health

import com.assetboard.data.BodyMeasurement
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Measurements over a period, measured between two real weigh-ins like
 * [com.assetboard.domain.NetWorthTrend]: a period starts at the last record before its first day
 * when there is one, so the change is anchored on a real day; with nothing before it, the first
 * record inside the period is the start. Nothing is interpolated, and a day without a weigh-in
 * simply has no point.
 */
object HealthTrend {
    /** The seven-day window of the moving average, in calendar days. */
    const val WINDOW_DAYS = 7

    class Period(
        /** Oldest first; never empty. */
        val points: List<BodyMeasurement>,
        /** The first point is the weigh-in just before the period, its starting value. */
        val anchored: Boolean,
    ) {
        val start: BodyMeasurement get() = points.first()
        val end: BodyMeasurement get() = points.last()

        /** Weigh-ins inside the period; the anchor belongs to before it and is not counted. */
        val records: Int get() = points.size - if (anchored) 1 else 0

        val weightChange: Long? get() = if (points.size < 2) null else end.weightG - start.weightG

        /** In basis points; positive means it rose. Null unless both ends have a body fat reading. */
        val fatChange: Int?
            get() {
                if (points.size < 2) return null
                val a = start.bodyFatBp ?: return null
                val b = end.bodyFatBp ?: return null
                return b - a
            }

        val high: BodyMeasurement get() = points.maxBy { it.weightG }
        val low: BodyMeasurement get() = points.minBy { it.weightG }

        /** Days from the start weigh-in to the end one; null for a single point. */
        val spanDays: Long?
            get() = if (points.size < 2) null else ChronoUnit.DAYS.between(LocalDate.parse(start.date), LocalDate.parse(end.date))
    }

    private const val DAY_MS = 24L * 3600 * 1000

    /** [days] null means everything. Same three branches as NetWorthTrend.period. */
    fun period(all: List<BodyMeasurement>, days: Long?, now: Long): Period? {
        if (all.isEmpty()) return null
        val sorted = all.sortedBy { it.date }
        if (days == null) return Period(sorted, anchored = false)
        // By local dates, not epoch milliseconds: a fixed 24 h window drifts over a DST change.
        val from = com.assetboard.domain.Dates.localDate(now).minusDays(days - 1).toString()
        val firstIn = sorted.indexOfFirst { it.date >= from }
        return when {
            // Nothing inside the period: only the latest record, as where things stand.
            firstIn < 0 -> Period(listOf(sorted.last()), anchored = true)
            firstIn == 0 -> Period(sorted, anchored = false)
            else -> Period(sorted.subList(firstIn - 1, sorted.size), anchored = true)
        }
    }

    fun measurementAt(m: BodyMeasurement): Long = LocalDate.parse(m.date).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    /**
     * The rolling average over the seven calendar days ending on each day of [points] that has
     * the figure (inclusive), one point per such day, as one line. A window holds the readings of
     * its days in [history] (every record, so the first days of a period still count the week
     * before it), so it is the calendar that defines it, not the order of the points. A window
     * with a single reading is that reading: the line follows the record through sparse weeks
     * instead of breaking off there.
     *
     * Empty when there would be nothing to see: fewer than two points, or no window with two
     * readings, where the average would only retrace the line itself.
     */
    fun movingAverage(points: List<BodyMeasurement>, history: List<BodyMeasurement> = points, of: (BodyMeasurement) -> Long?): List<Pair<String, Long>> {
        val readings = history.mapNotNull { m -> of(m)?.let { LocalDate.parse(m.date) to it } }
        var averaged = false
        val line = points.sortedBy { it.date }.mapNotNull { m ->
            if (of(m) == null) return@mapNotNull null
            val date = LocalDate.parse(m.date)
            val from = date.minusDays(WINDOW_DAYS - 1L)
            val values = readings.filter { (d, _) -> !d.isBefore(from) && !d.isAfter(date) }.map { it.second }
            if (values.size > 1) averaged = true
            m.date to values.sum() / values.size
        }
        return if (averaged && line.size >= 2) line else emptyList()
    }

    /**
     * How far the period went toward the target, as a fraction in [0, 1]; null when the fraction
     * would not mean anything: no target, a start already at it, or a start and an end on
     * opposite sides of it (losing and gaining then are the same distance, so there is nothing to
     * call progress).
     */
    fun targetProgress(startG: Long, currentG: Long, targetG: Long): Double? {
        val mine = startG - targetG
        val left = currentG - targetG
        if (mine == 0L || left == 0L) return if (left == 0L) 1.0 else null
        if ((mine > 0) != (left > 0)) return null
        val done = (mine - left).toDouble() / mine.toDouble()
        return done.coerceIn(0.0, 1.0)
    }

    /** "近 30 天 · 8 次记录": coverage rather than a streak, so a missed day is not a failure. */
    fun coverageLabel(days: Long?, records: Int): String {
        val window = when (days) {
            null -> "全部"
            30L -> "近 30 天"
            90L -> "近 90 天"
            365L -> "近一年"
            else -> "近 $days 天"
        }
        return "$window · $records 次记录"
    }

    /** "上次称重 · 3 天前", and past a week a plain "已经 8 天没记录了" instead. */
    fun lastSeenLabel(daysAgo: Long): String = when {
        daysAgo <= 0 -> "今天称过"
        daysAgo == 1L -> "上次称重 · 昨天"
        daysAgo <= 7 -> "上次称重 · $daysAgo 天前"
        else -> "已经 $daysAgo 天没记录了"
    }

    fun daysBetweenDates(from: String, to: String): Long = abs(ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to)))
}
