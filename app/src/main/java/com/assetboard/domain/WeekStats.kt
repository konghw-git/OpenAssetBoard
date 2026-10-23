package com.assetboard.domain

import com.assetboard.data.BodyMeasurement
import com.assetboard.data.HabitCheck
import com.assetboard.data.Todo
import com.assetboard.data.TodoKind
import com.assetboard.domain.health.HealthTrend
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
 * - weight: the week's last 7-day average point minus the previous week's; null when either
 *   is missing ([hasWeights] tells "no records yet" from "not enough");
 * - net worth: the week's last stocktake minus the last one before the week; [netWorthNow] is
 *   null when there was no stocktake in the week, [netWorthChange] also when none came before.
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
            netWorth: List<Pair<Long, Long>>,
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
            val averages = HealthTrend.movingAverage(weights) { it.weightG }.map { LocalDate.parse(it.first) to it.second }
            val thisWeek = averages.lastOrNull { inWeek(it.first) }?.second
            val lastWeek = averages.lastOrNull { !it.first.isBefore(start.minusDays(7)) && it.first.isBefore(start) }?.second
            val weekStartAt = start.atStartOfDay(zone).toInstant().toEpochMilli()
            val weekEndAt = start.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
            val sorted = netWorth.sortedBy { it.first }
            val now = sorted.lastOrNull { it.first in weekStartAt until weekEndAt }?.second
            val before = sorted.lastOrNull { it.first < weekStartAt }?.second
            return WeekStats(
                diaryDays = diaryDates.count { inWeek(it) && !it.isAfter(today) },
                diaryOf = of,
                habits = habits,
                habitRestDays = habits.keys.associateWith { h -> rest[h.key] ?: 0 }.filterValues { it > 0 },
                todosDone = done,
                hasWeights = weights.isNotEmpty(),
                weightChangeG = if (thisWeek != null && lastWeek != null) thisWeek - lastWeek else null,
                netWorthNow = now,
                netWorthChange = if (now != null && before != null) now - before else null,
            )
        }
    }
}
