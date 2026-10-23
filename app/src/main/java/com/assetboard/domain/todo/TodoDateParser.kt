package com.assetboard.domain.todo

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * The day and time written into a new todo's title, the way they are said in Chinese:
 *
 * - days: 今天 明天 后天 大后天, 周五 / 星期五 / 礼拜五 (the next one, today included), 这周五,
 *   下周五, N 天后, 10月25日 / 10月25号 (this year, or next once past), 25号 (this month, or next);
 * - times: 下午3点, 上午10点半, 晚上8点15, 9点一刻, 15:30; 早上 上午 凌晨 keep the hour, 中午
 *   下午 傍晚 晚上 今晚 move it past noon, and a bare 1 to 5 点 is the afternoon. A time without
 *   a day is today, or tomorrow once it has passed.
 *
 * A day or time followed by 的 is part of the text (明天的会议纪要) and is left alone. [Found.rest] is the
 * title without the matched words; the caller decides whether to use it.
 */
object TodoDateParser {
    data class Found(
        val date: LocalDate?,
        val time: LocalTime?,
        /** Where the matched words sit in the title, to show them. */
        val spans: List<IntRange>,
        val rest: String,
    )

    fun parse(title: String, today: LocalDate, now: LocalTime): Found? {
        val day = findDay(title, today)
        val time = findTime(title)?.takeIf { t -> day == null || !t.first.overlaps(day.first) }
        if (day == null && time == null) return null
        val date = day?.second ?: time?.second?.let { if (it.isAfter(now)) today else today.plusDays(1) }
        val spans = listOfNotNull(day?.first, time?.first).sortedBy { it.first }
        var rest = title
        for (s in spans.asReversed()) rest = rest.removeRange(s)
        rest = rest.replace(Regex("\\s+"), " ").trim()
        if (rest.isEmpty()) return null
        return Found(date, time?.second, spans, rest)
    }

    private fun IntRange.overlaps(o: IntRange) = first <= o.last && o.first <= last

    // ------------------------------------------------------------ days

    private val NUM = "(\\d{1,3}|[零一二两三四五六七八九十]{1,3})"
    private val RELATIVE = Regex("大后天|后天|明天|明早|明晚|今天|今晚|今早")
    private val WEEKDAY = Regex("(下下|下|这|本)?(?:周|星期|礼拜)([一二三四五六日天1-7])")
    private val AFTER = Regex("${NUM}天(?:之)?后")
    private val MONTH_DAY = Regex("(\\d{1,2})月(\\d{1,2})[日号]")
    private val DAY_ONLY = Regex("(?<![月\\d])(\\d{1,2})号")

    private fun findDay(s: String, today: LocalDate): Pair<IntRange, LocalDate>? {
        val found = buildList {
            RELATIVE.find(s)?.let { m ->
                val d = when (m.value) {
                    "今天", "今晚", "今早" -> today
                    "明天", "明早", "明晚" -> today.plusDays(1)
                    "后天" -> today.plusDays(2)
                    else -> today.plusDays(3)
                }
                add(m.range to d)
            }
            WEEKDAY.find(s)?.let { m ->
                val dow = weekday(m.groupValues[2]) ?: return@let
                val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val d = when (m.groupValues[1]) {
                    "下" -> monday.plusWeeks(1).with(TemporalAdjusters.nextOrSame(dow))
                    "下下" -> monday.plusWeeks(2).with(TemporalAdjusters.nextOrSame(dow))
                    "这", "本" -> monday.with(TemporalAdjusters.nextOrSame(dow))
                    else -> today.with(TemporalAdjusters.nextOrSame(dow))
                }
                add(m.range to d)
            }
            AFTER.find(s)?.let { m -> number(m.groupValues[1])?.takeIf { it in 1..365 }?.let { add(m.range to today.plusDays(it.toLong())) } }
            MONTH_DAY.find(s)?.let { m ->
                val d = runCatching { LocalDate.of(today.year, m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull() ?: return@let
                add(m.range to if (d.isBefore(today)) d.plusYears(1) else d)
            }
            DAY_ONLY.find(s)?.let { m ->
                val n = m.groupValues[1].toInt()
                val d = (0L..2L).asSequence().map { today.plusMonths(it) }
                    .mapNotNull { runCatching { it.withDayOfMonth(n) }.getOrNull() }
                    .firstOrNull { !it.isBefore(today) } ?: return@let
                add(m.range to d)
            }
        }
        // The first one written wins; one followed by 的 is text, not a day.
        return found.filter { (r, _) -> s.getOrNull(r.last + 1) != '的' }.minByOrNull { it.first.first }
    }

    private fun weekday(c: String): DayOfWeek? = when (c) {
        "一", "1" -> DayOfWeek.MONDAY
        "二", "2" -> DayOfWeek.TUESDAY
        "三", "3" -> DayOfWeek.WEDNESDAY
        "四", "4" -> DayOfWeek.THURSDAY
        "五", "5" -> DayOfWeek.FRIDAY
        "六", "6" -> DayOfWeek.SATURDAY
        "日", "天", "7" -> DayOfWeek.SUNDAY
        else -> null
    }

    // ------------------------------------------------------------ times

    private val CLOCK = Regex("(?<!\\d)([01]?\\d|2[0-3])[:：]([0-5]\\d)(?!\\d)")
    private val SPOKEN = Regex("(早上|上午|中午|下午|傍晚|晚上|今晚|明晚|凌晨)?${NUM}[点时](半|一刻|三刻|${NUM}分?)?")

    private fun findTime(s: String): Pair<IntRange, LocalTime>? {
        CLOCK.find(s)?.let { m -> return m.range to LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
        for (m in SPOKEN.findAll(s)) {
            val period = m.groupValues[1]
            var hour = number(m.groupValues[2]) ?: continue
            if (hour > 24) continue
            // "3点心" is a snack, not a time; "3点的会" keeps its words, like a day before 的.
            val after = s.getOrNull(m.range.last + 1)
            if ((after == '心' && m.groupValues[3].isEmpty()) || after == '的') continue
            val minute = when (m.groupValues[3]) {
                "" -> 0
                "半" -> 30
                "一刻" -> 15
                "三刻" -> 45
                else -> number(m.groupValues[4]) ?: continue
            }
            if (minute > 59) continue
            if (period in listOf("中午", "下午", "傍晚", "晚上", "今晚", "明晚") && hour < 12) hour += 12
            // Said without a part of the day, 1 to 5 o'clock is an afternoon: nobody plans 3 a.m.
            if (period.isEmpty() && hour in 1..5) hour += 12
            if (hour == 24) hour = 0
            // 今晚 and 明晚 also name a day; that word is read as the day, the time starts after it.
            val start = if (period == "今晚" || period == "明晚") m.range.first + 2 else m.range.first
            return (start..m.range.last) to LocalTime.of(hour, minute)
        }
        return null
    }

    /** "15", "十五", "两" and the like, up to 999. */
    internal fun number(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        val digits = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (s.isEmpty()) return null
        val ten = s.indexOf('十')
        if (ten < 0) return if (s.length == 1) digits[s[0]] else null
        val tens = if (ten == 0) 1 else digits[s[0]] ?: return null
        val ones = if (ten == s.lastIndex) 0 else digits[s[ten + 1]] ?: return null
        return tens * 10 + ones
    }
}
