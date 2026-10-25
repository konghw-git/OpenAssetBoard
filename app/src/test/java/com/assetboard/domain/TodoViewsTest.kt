package com.assetboard.domain

import com.assetboard.data.Todo
import com.assetboard.data.TodoKind
import com.assetboard.domain.calendar.ChineseCalendar
import com.assetboard.domain.calendar.WeekdaysOnly
import com.assetboard.domain.todo.Recurrence
import com.assetboard.domain.todo.RepeatRule
import com.assetboard.domain.todo.RepeatType
import com.assetboard.domain.todo.TodoView
import com.assetboard.domain.todo.TodoViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Group V (views) and the pure parts of group P (recurrence) of the design note. */
class TodoViewsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 10, 20)
    private var n = 0

    private fun todo(
        plan: LocalDate? = null,
        deadline: LocalDate? = null,
        list: String? = null,
        someday: Boolean = false,
        important: Boolean = false,
        completedAt: Long? = null,
        kind: TodoKind = TodoKind.ORDINARY,
    ) = Todo(
        id = "t${n++}", kind = kind, title = "t$n", listId = list, planDate = plan?.toString(), deadline = deadline?.toString(),
        someday = someday, important = important, createdAt = 0, completedAt = completedAt, sortOrder = n,
    )

    private fun views(t: Todo) = TodoView.entries.filter { it != TodoView.LOGBOOK && TodoViews.matches(it, t, today) }.toSet()

    @Test fun v1_overdueByOneDay() {
        val t = todo(plan = today.minusDays(1))
        val rows = TodoViews.today(listOf(t), today, zone)
        assertTrue(rows.single().overdue)
        assertEquals("已过 1 天", TodoViews.trailing(t, today)?.text)
    }

    @Test fun v2_deadlineTodayWithoutPlan() {
        assertEquals(setOf(TodoView.TODAY, TodoView.INBOX), views(todo(deadline = today)))
    }

    @Test fun v3_noListNoDates() {
        assertEquals(setOf(TodoView.INBOX), views(todo()))
    }

    @Test fun v4_plannedYesterdayDueNextWeek() {
        val t = todo(plan = today.minusDays(1), deadline = today.plusDays(7))
        assertEquals(setOf(TodoView.TODAY), views(t))
    }

    @Test fun v5_somedayDueToday() {
        assertEquals(setOf(TodoView.TODAY, TodoView.SOMEDAY), views(todo(someday = true, deadline = today)))
    }

    @Test fun v6_somedayDueNextWeek() {
        assertEquals(setOf(TodoView.SOMEDAY), views(todo(someday = true, deadline = today.plusDays(7))))
    }

    @Test fun v7_inboxWithAFutureDeadline() {
        val t = todo(deadline = today.plusDays(7))
        assertEquals(setOf(TodoView.INBOX, TodoView.UPCOMING), views(t))
        assertEquals(today.plusDays(7), TodoViews.upcoming(listOf(t), today, zone).single().first)
    }

    @Test fun v8_todayKeepsTheHandSetOrderOverdueOrNot() {
        val today1 = todo(plan = today)
        val overdue = todo(plan = today.minusDays(2))
        val important = todo(plan = today, important = true)
        val rows = TodoViews.today(listOf(important, overdue, today1), today, zone)
        assertEquals(listOf(today1.id, overdue.id, important.id), rows.map { it.todo.id })
    }

    @Test fun everyOpenTodayRowTradesPlacesWithAnyOther() {
        val a = todo(plan = today)
        val flagged = todo(plan = today, important = true)
        val late = todo(plan = today.minusDays(2))
        val doneAt = today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val done = todo(plan = today, completedAt = doneAt)
        val rows = TodoViews.today(listOf(a, flagged, late, done), today, zone).associateBy { it.todo.id }
        fun band(t: com.assetboard.data.Todo) = TodoViews.todayBand(rows.getValue(t.id))
        assertEquals(band(a), band(flagged))
        assertEquals(band(a), band(late))
        assertEquals(null, band(done))
    }

    @Test fun v9_finishedTodayIsGreyAndNotCounted() {
        val doneAt = today.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val all = listOf(todo(plan = today, completedAt = doneAt), todo(plan = today), todo(plan = today), todo(plan = today))
        val rows = TodoViews.today(all, today, zone)
        assertEquals(4, rows.size)
        assertEquals(1, rows.count { it.done })
        assertTrue(rows.last().done)
        assertEquals(3, TodoViews.count(TodoView.TODAY, all, today))
    }

    @Test fun todayAndUpcomingAreExclusive() {
        val dates = listOf(null, today.minusDays(1), today, today.plusDays(1))
        for (p in dates) for (d in dates) for (s in listOf(false, true)) {
            val t = todo(plan = if (s) null else p, deadline = d, someday = s)
            assertFalse("$p $d $s", TodoViews.inToday(t, today) && TodoViews.inUpcoming(t, today))
        }
    }

    // ------------------------------------------------------------ recurrence

    private fun next(rule: RepeatRule, plan: String, on: String) =
        Recurrence.next(rule, LocalDate.parse(plan), LocalDate.parse(on), LocalDate.parse(on), WeekdaysOnly)

    @Test fun p6_lunarRepeatIgnoresTheLeapMonth() {
        // 2020 has a leap fourth month; the 8th of the regular fourth month only.
        val rule = RepeatRule(RepeatType.LUNAR_YEARLY, month = 4, day = 8)
        val in2019 = ChineseCalendar.lunarToSolar(2019, 4, 8)!!
        val in2020 = next(rule, in2019.toString(), in2019.toString())!!
        assertEquals(ChineseCalendar.lunarToSolar(2020, 4, 8), in2020)
        val after = next(rule, in2020.toString(), in2020.toString())!!
        assertEquals(2021, after.year)
    }

    @Test fun p20_feb29FromALeapYear() {
        assertEquals(LocalDate.of(2029, 2, 28), next(RepeatRule(RepeatType.YEARLY, month = 2, day = 29), "2028-02-29", "2028-02-29"))
    }

    @Test fun lunarDay30FallsOn29InAShortMonth() {
        val y = (2026..2040).first { com.nlf.calendar.LunarYear.fromYear(it).getMonth(12).dayCount == 29 }
        val d = ChineseCalendar.lunarToSolar(y, 12, 30)!!
        assertEquals(ChineseCalendar.lunarToSolar(y, 12, 29), d)
    }

    @Test fun everyNDaysIsAnchoredOnThePlanDate() {
        assertEquals(LocalDate.of(2026, 10, 23), next(RepeatRule(RepeatType.DAILY, interval = 3), "2026-10-20", "2026-10-20"))
        // Missed periods are not made up: the first one on or after today.
        assertEquals(LocalDate.of(2026, 10, 29), next(RepeatRule(RepeatType.DAILY, interval = 3), "2026-10-20", "2026-10-28"))
    }

    @Test fun ruleWithNoNextDateWithinTenYearsIsInvalid() {
        expectRuleViolation { Recurrence.validate(RepeatRule(RepeatType.WEEKLY, weekdays = listOf(8)), LocalDate.of(2026, 10, 20), 0, WeekdaysOnly) }
        expectRuleViolation { Recurrence.validate(RepeatRule(RepeatType.YEARLY, month = 2, day = 30), LocalDate.of(2026, 10, 20), 0, WeekdaysOnly) }
        expectRuleViolation { Recurrence.validate(RepeatRule(RepeatType.DAILY, untilDate = "2026-10-01"), LocalDate.of(2026, 10, 20), 0, WeekdaysOnly) }
        expectRuleViolation { Recurrence.validate(RepeatRule(RepeatType.DAILY, countBase = 3), LocalDate.of(2026, 10, 20), 2, WeekdaysOnly) }
    }

    private fun expectRuleViolation(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected LedgerException")
        } catch (e: LedgerException) {
            // expected
        }
    }
}
