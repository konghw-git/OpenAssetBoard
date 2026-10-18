package com.assetboard.domain.todo

import com.assetboard.domain.calendar.ChineseCalendar
import com.assetboard.domain.calendar.WorkdayCalendar
import com.assetboard.domain.ensure
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

@Serializable
enum class RepeatType(val label: String) {
    DAILY("每 N 天"),
    WEEKLY("每周"),
    MONTHLY_DAY("每月 N 号"),
    MONTHLY_LAST("每月最后一天"),
    YEARLY("每年"),
    WORKDAY("法定工作日"),
    AFTER_COMPLETION("完成后间隔"),
    LUNAR_YEARLY("每年农历"),
}

@Serializable
enum class IntervalUnit(val label: String) { DAY("天"), WEEK("周"), MONTH("个月") }

/**
 * A repeat rule. Fields not used by [type] keep their defaults.
 *
 * [countBase] is the occurrence count at the moment this repetition was set up, so "N times"
 * counts from then; editing the rule keeps it.
 */
@Serializable
data class RepeatRule(
    val type: RepeatType,
    /** DAILY: every N days. AFTER_COMPLETION: N [unit]s after the day it was done. */
    val interval: Int = 1,
    val unit: IntervalUnit = IntervalUnit.DAY,
    /** WEEKLY: ISO day numbers, 1 = Monday. */
    val weekdays: List<Int> = emptyList(),
    /** YEARLY and LUNAR_YEARLY. */
    val month: Int = 1,
    /** MONTHLY_DAY, YEARLY and LUNAR_YEARLY. */
    val day: Int = 1,
    val untilDate: String? = null,
    val count: Int? = null,
    val countBase: Int = 0,
) {
    fun encode(): String = codec.encodeToString(serializer(), this)

    companion object {
        private val codec = Json { encodeDefaults = true; ignoreUnknownKeys = false }

        fun decode(text: String): RepeatRule = codec.decodeFromString(serializer(), text)

        fun decodeOrNull(text: String?): RepeatRule? = text?.let { runCatching { decode(it) }.getOrNull() }
    }
}

/** What completing or skipping the current period does to a repeat source. */
data class Advance(val nextPlan: LocalDate?, val ended: Boolean)

object Recurrence {
    /** The search horizon; a rule that finds nothing within it is invalid. */
    private const val HORIZON_YEARS = 10L

    fun describe(rule: RepeatRule): String {
        val base = when (rule.type) {
            RepeatType.DAILY -> if (rule.interval == 1) "每天" else "每 ${rule.interval} 天"
            RepeatType.WEEKLY -> "每周" + rule.weekdays.sorted().joinToString("、") { WEEK_NAMES[it - 1] }
            RepeatType.MONTHLY_DAY -> "每月 ${rule.day} 号"
            RepeatType.MONTHLY_LAST -> "每月最后一天"
            RepeatType.YEARLY -> "每年 ${rule.month} 月 ${rule.day} 日"
            RepeatType.WORKDAY -> "法定工作日"
            RepeatType.AFTER_COMPLETION -> "完成后 ${rule.interval} ${rule.unit.label}"
            RepeatType.LUNAR_YEARLY -> "每年农历" + ChineseCalendar.lunarMonthName(rule.month) + ChineseCalendar.lunarDayName(rule.day)
        }
        val end = when {
            rule.count != null -> " · 共 ${rule.count} 次"
            rule.untilDate != null -> " · 至 ${rule.untilDate}"
            else -> ""
        }
        return base + end
    }

    /** Throws a user-facing rule violation; repository writes and backup import share it. */
    fun validate(rule: RepeatRule, planDate: LocalDate?, occurrenceCount: Int, calendar: WorkdayCalendar) {
        ensure(planDate != null) { "重复待办必须有计划日" }
        when (rule.type) {
            RepeatType.DAILY, RepeatType.AFTER_COMPLETION -> ensure(rule.interval in 1..999) { "间隔需在 1 到 999 之间" }
            RepeatType.WEEKLY -> ensure(rule.weekdays.isNotEmpty() && rule.weekdays.all { it in 1..7 }) { "请至少选择一个星期几" }
            RepeatType.MONTHLY_DAY -> ensure(rule.day in 1..31) { "每月的日期需在 1 到 31 之间" }
            RepeatType.YEARLY -> ensure(rule.month in 1..12 && rule.day in 1..YearMonth.of(2024, rule.month.coerceIn(1, 12)).lengthOfMonth()) { "每年的日期不合法" }
            RepeatType.LUNAR_YEARLY -> ensure(rule.month in 1..12 && rule.day in 1..30) { "农历日期不合法" }
            RepeatType.MONTHLY_LAST, RepeatType.WORKDAY -> Unit
        }
        rule.count?.let { ensure(it in 1..9999) { "重复次数需在 1 到 9999 之间" } }
        ensure(rule.countBase in 0..occurrenceCount) { "重复计数起点不合法" }
        rule.untilDate?.let { u ->
            val until = runCatching { LocalDate.parse(u) }.getOrNull()
            ensure(until != null) { "结束日期不合法" }
            ensure(!until!!.isBefore(planDate!!)) { "结束日期不能早于计划日" }
        }
        ensure(firstAfter(rule, planDate!!, planDate, calendar) != null) { "按这条规则在 10 年内找不到下一次" }
    }

    /** Periods this rule has already run (completed or skipped). */
    fun executed(rule: RepeatRule, occurrenceCount: Int): Int = occurrenceCount - rule.countBase

    /**
     * The next plan date after finishing the period planned for [current] on [doneOn]. Missed
     * periods are not made up: a fixed rule lands on its first date that is today or later.
     */
    fun next(rule: RepeatRule, current: LocalDate, today: LocalDate, doneOn: LocalDate, calendar: WorkdayCalendar): LocalDate? {
        if (rule.type == RepeatType.AFTER_COMPLETION) {
            return when (rule.unit) {
                IntervalUnit.DAY -> doneOn.plusDays(rule.interval.toLong())
                IntervalUnit.WEEK -> doneOn.plusWeeks(rule.interval.toLong())
                IntervalUnit.MONTH -> doneOn.plusMonths(rule.interval.toLong())
            }
        }
        val first = firstAfter(rule, current, current, calendar) ?: return null
        if (!first.isBefore(today)) return first
        return firstAfter(rule, today.minusDays(1), current, calendar)
    }

    fun advance(rule: RepeatRule, current: LocalDate, occurrenceCount: Int, today: LocalDate, doneOn: LocalDate, calendar: WorkdayCalendar): Advance {
        val done = executed(rule, occurrenceCount) + 1
        if (rule.count != null && done >= rule.count) return Advance(null, ended = true)
        val next = next(rule, current, today, doneOn, calendar) ?: return Advance(null, ended = true)
        val until = rule.untilDate?.let(LocalDate::parse)
        if (until != null && next.isAfter(until)) return Advance(null, ended = true)
        return Advance(next, ended = false)
    }

    /** First date of the rule strictly after [after]. [anchor] is the series' current plan date. */
    internal fun firstAfter(rule: RepeatRule, after: LocalDate, anchor: LocalDate, calendar: WorkdayCalendar): LocalDate? {
        val limit = after.plusYears(HORIZON_YEARS)
        val found: LocalDate? = when (rule.type) {
            RepeatType.DAILY -> {
                val n = rule.interval.toLong().coerceAtLeast(1)
                val gap = ChronoUnit.DAYS.between(anchor, after)
                val k = if (gap < 0) 1 else gap / n + 1
                anchor.plusDays(k * n)
            }
            RepeatType.AFTER_COMPLETION -> after.plusDays(1)
            RepeatType.WEEKLY -> (1L..7L).map { after.plusDays(it) }.firstOrNull { it.dayOfWeek.value in rule.weekdays }
            RepeatType.MONTHLY_DAY, RepeatType.MONTHLY_LAST -> {
                var ym = YearMonth.from(after)
                var hit: LocalDate? = null
                repeat((HORIZON_YEARS * 12 + 1).toInt()) {
                    if (hit == null) {
                        val d = if (rule.type == RepeatType.MONTHLY_LAST) ym.atEndOfMonth() else ym.atDay(minOf(rule.day, ym.lengthOfMonth()))
                        if (d.isAfter(after)) hit = d
                        ym = ym.plusMonths(1)
                    }
                }
                hit
            }
            RepeatType.YEARLY -> (0L..HORIZON_YEARS).map { yearly(after.year + it.toInt(), rule.month, rule.day) }.firstOrNull { it.isAfter(after) }
            RepeatType.WORKDAY -> generateSequence(after.plusDays(1)) { it.plusDays(1) }
                .takeWhile { !it.isAfter(limit) }
                .firstOrNull { calendar.isWorkday(it) }
            RepeatType.LUNAR_YEARLY -> {
                val startYear = ChineseCalendar.lunarYearOf(after) - 1
                (0..HORIZON_YEARS.toInt() + 1).asSequence()
                    .mapNotNull { ChineseCalendar.lunarToSolar(startYear + it, rule.month, rule.day) }
                    .firstOrNull { it.isAfter(after) }
            }
        }
        return found?.takeIf { !it.isAfter(limit) }
    }

    /** Feb 29 falls on Feb 28 in common years. */
    private fun yearly(year: Int, month: Int, day: Int): LocalDate {
        val ym = YearMonth.of(year, month)
        return ym.atDay(minOf(day, ym.lengthOfMonth()))
    }

    val WEEK_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")

    fun weekName(d: DayOfWeek) = "周" + WEEK_NAMES[d.value - 1]
}
