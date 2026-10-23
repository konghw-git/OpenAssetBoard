package com.assetboard.domain

import com.assetboard.data.BodyMeasurement
import com.assetboard.data.HabitCheck
import com.assetboard.data.Todo
import com.assetboard.data.TodoKind
import com.assetboard.domain.todo.TodoViews
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A week at a glance, worked out when the review page opens and never stored. Rules:
 *
 * - diary: days with a live, non-empty entry, out of the week's days so far (7 once it is over);
 * - habits: days each one was ticked, and the rest days each one took ([habitRestDays]), which
 *   come off the days it is measured against;
 * - todos: completed in the week, live, ordinary ones and repeat completions;
 * - weight: the mean of the week's readings minus the mean of the week before's; null when
 *   either week has none ([hasWeights] tells "no records yet" from "not enough"). The weeks'
 *   own readings, not 7-day averages: an average reaches back into the week before and, with
 *   one weigh-in a week, has nothing to average at all;
 * - net worth: from the ledger's balances, not from stocktakes, so an amount changed in place
 *   or a flow counts as well ([LedgerData.netWorthChange]): the change from the end of the week
 *   before to the end of this one, or to [now] while it runs. [netWorthNow] is the net worth
 *   then, and both are null when nothing was recorded in the week.
 */
data class WeekStats(
    val diaryDays: Int,
    val diaryOf: Int,
    val habits: Map<Habit, Int>,
    val todosDone: Int,
    val hasWeights: Boolean,
    val weightChangeG: Long?,
    val netWorthNow: Long?,
    val netWorthChange: Long?,
    /** Habits with at least one rest day in the week, and how many. */
    val habitRestDays: Map<Habit, Int> = emptyMap(),
) {
    companion object {
        fun of(
            start: LocalDate,
            today: LocalDate,
            diaryDates: Collection<LocalDate>,
            habitChecks: List<HabitCheck>,
            todos: List<Todo>,
            weights: List<BodyMeasurement>,
            ledger: LedgerData?,
            now: Long,
            zone: ZoneId = ZoneId.systemDefault(),
            /** Every habit there is or was; the week shows the ones it had, in order. */
            allHabits: List<Habit> = Habit.DEFAULTS,
        ): WeekStats {
            val end = start.plusDays(6)
            fun inWeek(d: LocalDate) = !d.isBefore(start) && !d.isAfter(end)
            val of = when {
                today.isBefore(start) -> 0
                today.isAfter(end) -> 7
                else -> ChronoUnit.DAYS.between(start, today).toInt() + 1
            }
            val (skips, ticks) = habitChecks.filter { inWeek(LocalDate.parse(it.date)) }.partition { it.skipped }
            val days = ticks.groupBy { it.habit }.mapValues { (_, c) -> c.map { it.date }.toSet().size }
            val rest = skips.groupBy { it.habit }.mapValues { (_, c) -> c.map { it.date }.toSet().size }
            val habits = allHabits.sortedBy { it.position }
                .filter { h -> (days[h.key] ?: 0) > 0 || (0L..6L).any { h.on(start.plusDays(it)) } }
                .associateWith { h -> days[h.key] ?: 0 }
            val done = todos.count { t ->
                t.deletedAt == null && (t.kind == TodoKind.ORDINARY || t.kind == TodoKind.COMPLETION) &&
                    t.completedAt?.let { inWeek(TodoViews.localDate(it, zone)) } == true
            }
            fun mean(from: LocalDate) = weights.filter { val d = LocalDate.parse(it.date); !d.isBefore(from) && !d.isAfter(from.plusDays(6)) }
                .map { it.weightG }.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size }
            val thisWeek = mean(start)
            val lastWeek = mean(start.minusDays(7))
            // Balances at the last moment of the week before, and at the end of this one or now.
            val from = start.atStartOfDay(zone).toInstant().toEpochMilli() - 1
            val to = minOf(start.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli() - 1, now)
            val recorded = ledger != null && to > from && ledger.hasRecordsIn(from, to)
            return WeekStats(
                diaryDays = diaryDates.count { inWeek(it) && !it.isAfter(today) },
                diaryOf = of,
                habits = habits,
                habitRestDays = habits.keys.associateWith { h -> rest[h.key] ?: 0 }.filterValues { it > 0 },
                todosDone = done,
                hasWeights = weights.isNotEmpty(),
                weightChangeG = if (thisWeek != null && lastWeek != null) thisWeek - lastWeek else null,
                netWorthNow = if (recorded) ledger!!.netWorthAt(to) else null,
                netWorthChange = if (recorded) ledger!!.netWorthChange(from, to) else null,
            )
        }
    }
}
