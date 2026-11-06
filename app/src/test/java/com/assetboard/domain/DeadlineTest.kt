package com.assetboard.domain

import com.assetboard.domain.todo.Deadline
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

class DeadlineTest {
    private fun label(date: String, now: String) = Deadline.label(LocalDate.parse(date), ZonedDateTime.parse(now))

    @Test fun datesKeepTheWholeLastDayAndShowWholeHours() {
        assertEquals("截止 2026-11-08（当天结束） · 剩余 2 天 3 小时", label("2026-11-08", "2026-11-06T20:30:00+08:00[Asia/Shanghai]"))
        assertEquals("截止 2026-11-06（当天结束） · 剩余 不足 1 小时", label("2026-11-06", "2026-11-06T23:59:00+08:00[Asia/Shanghai]"))
    }

    @Test fun midnightAndOverdueHoursAreExplicit() {
        assertEquals("截止 2026-11-05（当天结束） · 已超期 不足 1 小时", label("2026-11-05", "2026-11-06T00:00:00+08:00[Asia/Shanghai]"))
        assertEquals("截止 2026-11-04（当天结束） · 已超期 1 天 3 小时", label("2026-11-04", "2026-11-06T03:30:00+08:00[Asia/Shanghai]"))
    }

    @Test fun daylightSavingUsesElapsedHours() {
        assertEquals("截止 2026-03-08（当天结束） · 剩余 0 天 23 小时", label("2026-03-08", "2026-03-08T00:00:00-05:00[America/New_York]"))
    }
}
