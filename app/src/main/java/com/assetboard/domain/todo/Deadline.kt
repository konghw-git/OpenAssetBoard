package com.assetboard.domain.todo

import com.assetboard.domain.AppClock
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime

/** Date-only deadlines include the whole selected day in the user's time zone. */
object Deadline {
    fun label(date: LocalDate, now: ZonedDateTime = ZonedDateTime.now(AppClock.clock)): String {
        val remaining = Duration.between(now, date.plusDays(1).atStartOfDay(now.zone))
        val hours = remaining.abs().toHours()
        val distance = if (hours == 0L) "不足 1 小时" else "${hours / 24} 天 ${hours % 24} 小时"
        return "截止 ${date}（当天结束） · " + if (remaining.isNegative || remaining.isZero) "已超期 $distance" else "剩余 $distance"
    }
}
