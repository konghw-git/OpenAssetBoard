package com.assetboard.domain.calendar

import com.nlf.calendar.Lunar
import com.nlf.calendar.LunarYear
import com.nlf.calendar.Solar
import java.time.DayOfWeek
import java.time.LocalDate

/** Whether a date is a working day under the official schedule. */
interface WorkdayCalendar {
    fun isWorkday(date: LocalDate): Boolean

    /** False when the official schedule for [year] has not been loaded; Mon–Fri is assumed then. */
    fun hasYear(year: Int): Boolean
}

private fun isWeekday(date: LocalDate) = date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY

object WeekdaysOnly : WorkdayCalendar {
    override fun isWorkday(date: LocalDate) = isWeekday(date)
    override fun hasYear(year: Int) = false
}

/**
 * The official schedule. A year listed in [years] is complete: a date without an entry is an
 * ordinary day. A year not listed is missing as a whole; a single date with no entry never means that.
 */
class HolidayTable(val years: Set<Int>, private val days: Map<LocalDate, DayMark>) : WorkdayCalendar {
    data class DayMark(val name: String, val isOffDay: Boolean)

    override fun isWorkday(date: LocalDate): Boolean {
        if (date.year !in years) return isWeekday(date)
        val mark = days[date] ?: return isWeekday(date)
        return !mark.isOffDay
    }

    override fun hasYear(year: Int) = year in years

    fun mark(date: LocalDate): DayMark? = if (date.year in years) days[date] else null

    companion object {
        val EMPTY = HolidayTable(emptySet(), emptyMap())
    }
}

/** Offline lunar calendar, solar terms and festivals (cn.6tail:lunar). */
object ChineseCalendar {
    data class DayInfo(
        /** "九月初九" */
        val lunar: String,
        val solarTerm: String?,
        /** Traditional and Gregorian festivals of the day, traditional first. */
        val festivals: List<String>,
    )

    fun info(date: LocalDate): DayInfo {
        val solar = Solar.fromYmd(date.year, date.monthValue, date.dayOfMonth)
        val lunar = solar.lunar
        val festivals = buildList {
            addAll(lunar.festivals)
            addAll(solar.festivals)
        }.distinct()
        return DayInfo(
            lunar = lunar.monthInChinese + "月" + lunar.dayInChinese,
            solarTerm = lunar.jieQi.takeIf { it.isNotEmpty() },
            festivals = festivals,
        )
    }

    /** Short label for a calendar cell: festival, else solar term, else the lunar day ("初一" shows the month). */
    fun cellLabel(date: LocalDate): String {
        val solar = Solar.fromYmd(date.year, date.monthValue, date.dayOfMonth)
        val lunar = solar.lunar
        lunar.festivals.firstOrNull()?.let { return it }
        lunar.jieQi.takeIf { it.isNotEmpty() }?.let { return it }
        return if (lunar.day == 1) lunar.monthInChinese + "月" else lunar.dayInChinese
    }

    fun lunarYearOf(date: LocalDate): Int = Solar.fromYmd(date.year, date.monthValue, date.dayOfMonth).lunar.year

    /**
     * The solar date of lunar [month]/[day] in lunar [year], ignoring leap months. Day 30 falls
     * on day 29 in a short month.
     */
    fun lunarToSolar(year: Int, month: Int, day: Int): LocalDate? = runCatching {
        val days = LunarYear.fromYear(year).getMonth(month)?.dayCount ?: return null
        val s = Lunar.fromYmd(year, month, minOf(day, days)).solar
        LocalDate.of(s.year, s.month, s.day)
    }.getOrNull()

    private val MONTHS = listOf("正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊")
    private val DAYS = listOf(
        "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十",
    )

    fun lunarMonthName(month: Int) = MONTHS.getOrElse(month - 1) { "?" } + "月"
    fun lunarDayName(day: Int) = DAYS.getOrElse(day - 1) { "?" }
}
