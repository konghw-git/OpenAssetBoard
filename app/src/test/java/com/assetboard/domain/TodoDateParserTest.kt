package com.assetboard.domain

import com.assetboard.domain.todo.TodoDateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TodoDateParserTest {
    /** A Friday, mid-morning. */
    private val today = LocalDate.of(2026, 10, 23)
    private val now = LocalTime.of(10, 0)

    private fun parse(s: String) = TodoDateParser.parse(s, today, now)

    @Test fun dayAndTimeTogether() {
        val f = parse("明天下午3点开会")!!
        assertEquals(today.plusDays(1), f.date)
        assertEquals(LocalTime.of(15, 0), f.time)
        assertEquals("开会", f.rest)
        assertEquals(listOf(0..1, 2..5), f.spans)
    }

    @Test fun relativeDays() {
        assertEquals(today, parse("今天交周报")!!.date)
        assertEquals(today.plusDays(2), parse("后天去体检")!!.date)
        assertEquals(today.plusDays(3), parse("大后天还书")!!.date)
        assertEquals(today.plusDays(5), parse("5天后复查")!!.date)
        assertEquals(today.plusDays(3), parse("三天之后打电话")!!.date)
    }

    @Test fun weekdays() {
        // Friday itself is today; the next Monday is three days on.
        assertEquals(today, parse("周五喝咖啡")!!.date)
        assertEquals(LocalDate.of(2026, 10, 26), parse("周一例会")!!.date)
        assertEquals(LocalDate.of(2026, 10, 25), parse("星期天大扫除")!!.date)
        assertEquals(LocalDate.of(2026, 10, 30), parse("下周五交报告")!!.date)
        assertEquals(LocalDate.of(2026, 10, 25), parse("这周日扫除")!!.date)
        assertEquals(LocalDate.of(2026, 10, 28), parse("礼拜三买菜")!!.date)
    }

    @Test fun datesInTheCalendar() {
        assertEquals(LocalDate.of(2026, 11, 5), parse("11月5号续费")!!.date)
        // Gone this year: next year's.
        assertEquals(LocalDate.of(2027, 3, 1), parse("3月1日交房租")!!.date)
        assertEquals(LocalDate.of(2026, 10, 25), parse("25号还信用卡")!!.date)
        assertEquals(LocalDate.of(2026, 11, 10), parse("10号还信用卡")!!.date)
    }

    @Test fun spokenAndWrittenTimes() {
        assertEquals(LocalTime.of(10, 30), parse("上午10点半开会")!!.time)
        assertEquals(LocalTime.of(20, 15), parse("晚上8点15分跑步")!!.time)
        assertEquals(LocalTime.of(9, 15), parse("9点一刻出门")!!.time)
        assertEquals(LocalTime.of(14, 0), parse("下午两点取快递")!!.time)
        assertEquals(LocalTime.of(15, 30), parse("15:30 面试")!!.time)
        assertEquals(LocalTime.of(12, 0), parse("中午12点吃饭")!!.time)
        // A bare small hour is the afternoon; a bare morning one stays.
        assertEquals(LocalTime.of(15, 0), parse("3点开会")!!.time)
        assertEquals(LocalTime.of(7, 0), parse("7点起床")!!.time)
    }

    @Test fun aTimeAloneIsTodayOrTomorrowOncePassed() {
        assertEquals(today, parse("下午3点开会")!!.date)
        assertEquals(today.plusDays(1), parse("早上8点跑步")!!.date)
    }

    @Test fun tonightNamesTheDayAndTheTime() {
        val f = parse("今晚8点看电影")!!
        assertEquals(today, f.date)
        assertEquals(LocalTime.of(20, 0), f.time)
        assertEquals("看电影", f.rest)
    }

    @Test fun wordsThatOnlyLookLikeADay() {
        assertNull(parse("明天的会议纪要"))
        assertNull(parse("买3点心"))
        assertNull(parse("整理下午3点的会议记录"))
        assertNull(parse("写周报"))
        // Nothing would be left of the title.
        assertNull(parse("明天"))
    }

    @Test fun chineseNumbers() {
        assertEquals(15, TodoDateParser.number("十五"))
        assertEquals(10, TodoDateParser.number("十"))
        assertEquals(20, TodoDateParser.number("二十"))
        assertEquals(2, TodoDateParser.number("两"))
        assertNull(TodoDateParser.number("百"))
    }
}
