package com.assetboard.domain

import com.assetboard.data.Account
import com.assetboard.data.AccountGroup
import com.assetboard.data.BodyMeasurement
import com.assetboard.data.FlowKind
import com.assetboard.data.HabitCheck
import com.assetboard.data.MoneyFlow
import com.assetboard.data.Todo
import com.assetboard.data.TodoKind
import com.assetboard.data.Valuation
import com.assetboard.domain.todo.TodoViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The week in numbers, as [WeekStats] works them out; plain JVM, no device. */
class WeekStatsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val week = LocalDate.parse("2026-10-19")
    private fun date(s: String) = LocalDate.parse(s)
    private fun at(d: String, time: String = "09:00") = date(d).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()
    private fun weigh(d: String, g: Long) = BodyMeasurement("m$d", d, 0, g, null, null, 0, 0)

    private val assets = AccountGroup("g", "储蓄")
    private val debts = AccountGroup("d", "负债", isLiability = true)
    private fun account(id: String, opened: String, group: AccountGroup = assets) = Account(id, group.id, id, baselineAt = at(opened), createdAt = 0)
    private fun value(account: String, d: String, cents: Long, time: String = "09:00") = Valuation("$account@$d$time", account, at(d, time), cents, createdAt = 0)

    private fun stats(
        ledger: LedgerData?,
        now: Long = at("2026-10-22", "20:00"),
        weights: List<BodyMeasurement> = emptyList(),
        start: LocalDate = week,
    ) = WeekStats.of(start, TodoViews.localDate(now, zone), emptyList(), emptyList(), emptyList(), weights, ledger, now, zone)

    @Test fun daysHabitsAndTodos() {
        val todos = listOf(
            Todo(id = "a", kind = TodoKind.ORDINARY, title = "a", completedAt = at("2026-10-20"), createdAt = 0),
            Todo(id = "b", kind = TodoKind.COMPLETION, title = "b", completedAt = at("2026-10-25"), createdAt = 0),
            Todo(id = "c", kind = TodoKind.ORDINARY, title = "c", completedAt = at("2026-10-26"), createdAt = 0),
            Todo(id = "d", kind = TodoKind.ORDINARY, title = "d", completedAt = at("2026-10-21"), deletedAt = 1, createdAt = 0),
        )
        val checks = listOf(HabitCheck("2026-10-19", Habit.WORKOUT.key, 0), HabitCheck("2026-10-21", Habit.WORKOUT.key, 0), HabitCheck("2026-10-18", Habit.WORKOUT.key, 0))
        val s = WeekStats.of(week, date("2026-10-22"), listOf(date("2026-10-19"), date("2026-10-22"), date("2026-10-23")), checks, todos, emptyList(), null, at("2026-10-22"), zone)
        // Four days so far; a day after today is not counted.
        assertEquals(2, s.diaryDays)
        assertEquals(4, s.diaryOf)
        assertEquals(2, s.habits[Habit.WORKOUT])
        assertEquals(2, s.todosDone)
        val later = WeekStats.of(week.plusWeeks(1), date("2026-10-22"), emptyList(), emptyList(), emptyList(), emptyList(), null, at("2026-10-22"), zone)
        assertEquals(0, later.diaryOf)
        assertFalse(later.hasWeights)
        assertNull(later.netWorthNow)
    }

    /** The means of the two weeks' own readings, never a window reaching into the week before. */
    @Test fun weightIsTheChangeOfTheWeeksMeans() {
        val daily = listOf(weigh("2026-10-13", 80_000), weigh("2026-10-15", 80_400), weigh("2026-10-20", 79_800), weigh("2026-10-22", 79_600))
        assertEquals(79_700L - 80_200L, stats(null, weights = daily).weightChangeG)
        // Once a week: a 7-day average has nothing to average and once said 记录不足.
        val weekly = listOf(weigh("2026-10-12", 81_000), weigh("2026-10-19", 80_300))
        assertEquals(-700L, stats(null, weights = weekly).weightChangeG)
        // Nothing the week before: not enough to compare.
        val s = stats(null, weights = listOf(weigh("2026-10-20", 79_800)))
        assertEquals(true, s.hasWeights)
        assertNull(s.weightChangeG)
    }

    /**
     * An amount changed in place (a valuation, no stocktake) moves the week's net worth, as it
     * moves the one on the assets page; it once counted only stocktakes and missed it.
     */
    @Test fun netWorthFollowsTheBalancesNotOnlyStocktakes() {
        val ledger = LedgerData(
            groups = listOf(assets, debts),
            accounts = listOf(account("bank", "2026-09-01"), account("card", "2026-09-01", debts)),
            valuations = listOf(
                value("bank", "2026-09-01", 100_000_00), value("card", "2026-09-01", -2_000_00),
                value("bank", "2026-10-21", 103_000_00, "21:30"),
            ),
            flows = emptyList(), snapshots = emptyList(), snapshotItems = emptyList(),
        )
        val s = stats(ledger)
        assertEquals(3_000_00L, s.netWorthChange)
        assertEquals(101_000_00L, s.netWorthNow)
        // A later week with nothing recorded shows nothing.
        assertNull(stats(ledger, now = at("2026-11-04"), start = date("2026-11-02")).netWorthNow)
        // While the week runs, only what was recorded by now counts.
        assertNull(stats(ledger, now = at("2026-10-21", "08:00")).netWorthChange)
    }

    /** A flow moves the balance with no valuation at all; an account opened in the week is not a gain. */
    @Test fun flowsCountAndOpeningAnAccountDoesNot() {
        val ledger = LedgerData(
            groups = listOf(assets),
            accounts = listOf(account("bank", "2026-09-01"), account("new", "2026-10-20")),
            valuations = listOf(value("bank", "2026-09-01", 50_000_00), value("new", "2026-10-20", 80_000_00)),
            flows = listOf(MoneyFlow("f", FlowKind.EXTERNAL_OUT, "bank", null, 1_200_00, at = at("2026-10-21"), createdAt = 0)),
            snapshots = emptyList(), snapshotItems = emptyList(),
        )
        val s = stats(ledger)
        assertEquals(-1_200_00L, s.netWorthChange)
        assertEquals(128_800_00L, s.netWorthNow)
    }
}
