package com.assetboard.domain

import com.assetboard.data.Todo
import com.assetboard.data.TodoKind
import com.assetboard.domain.todo.ReminderPlan
import com.assetboard.domain.todo.ReminderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ReminderAdvanceTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.parse("2026-10-24")
    private fun at(day: LocalDate, time: String) = day.atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    @Test fun tomorrowAfterAnEarlyReminderStaysInTheFuture() {
        // The 00:10 event already falls tomorrow when its two-hour-early reminder arrives.
        for ((time, ahead, nowTime) in listOf(Triple("14:00", 1440, "14:00"), Triple("00:10", 120, "22:10"))) {
            val t = Todo("t", TodoKind.ORDINARY, "Meeting", planDate = today.plusDays(1).toString(),
                remindTime = time, remindBefore = ahead, remindedPeriod = 0, createdAt = 0)
            val now = at(today, nowTime)
            val next = ReminderState.tomorrow(t, today, now, zone)
            val plan = ReminderState.plan(next, now, zone)
            assertTrue("Tomorrow must schedule a future reminder, got $plan", plan is ReminderPlan.At)
            assertEquals(ahead, next.remindBefore)
        }
    }
}
