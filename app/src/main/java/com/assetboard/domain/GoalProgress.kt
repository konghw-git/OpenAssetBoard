package com.assetboard.domain

import kotlin.math.ceil

/**
 * How far net worth has come toward the goal set on the assets tab and, at the pace of the
 * stocktakes in the last half year, roughly how many months are left. Rules:
 *
 * - [fraction] is net worth over the goal, clamped to 0..1 (net worth below zero is no progress);
 * - the pace runs from the earliest stocktake in the 183 days before the latest one to the latest,
 *   scaled to a 30.44-day month; under 28 days apart, or not growing, there is no estimate;
 * - an estimate past 50 years is no estimate either.
 */
data class GoalProgress(
    val goal: Long,
    val net: Long,
    val fraction: Float,
    /** What is still missing; zero or less once reached. */
    val remaining: Long,
    val monthsToGo: Int?,
) {
    val reached: Boolean get() = remaining <= 0

    companion object {
        private const val DAY = 86_400_000L

        /** [history] is (stocktake time, net worth) in any order. */
        fun of(goal: Long, net: Long, history: List<Pair<Long, Long>>): GoalProgress {
            val remaining = goal - net
            val fraction = if (goal <= 0) 1f else (net.toDouble() / goal).coerceIn(0.0, 1.0).toFloat()
            return GoalProgress(goal, net, fraction, remaining, if (remaining > 0) months(remaining, history) else null)
        }

        /** Yuan as typed: "1000000", "1,000,000.5", or with 万 ("100万", "2.5万"). Null unless above zero. */
        fun parseInput(text: String): Long? {
            val t = text.trim().replace(" ", "")
            val cents = if (t.endsWith("万")) {
                Money.parse(t.dropLast(1))?.let { runCatching { Math.multiplyExact(it, 10_000L) }.getOrNull() }
            } else {
                Money.parse(t)
            }
            return cents?.takeIf { it > 0 }
        }

        /** Four round goals above [net]: the next steps of 1, 2 and 5 from 10万 up. */
        fun suggestions(net: Long): List<Long> {
            val steps = generateSequence(10_000_00L * 10) { it * 10 }.take(8).flatMap { sequenceOf(it, it * 2, it * 5) }
            return steps.filter { it > net }.take(4).toList()
        }

        private fun months(remaining: Long, history: List<Pair<Long, Long>>): Int? {
            val sorted = history.sortedBy { it.first }
            val last = sorted.lastOrNull() ?: return null
            val first = sorted.first { it.first >= last.first - 183 * DAY }
            val days = (last.first - first.first).toDouble() / DAY
            if (days < 28) return null
            val perMonth = (last.second - first.second) / days * 30.44
            if (perMonth <= 0) return null
            val months = ceil(remaining / perMonth)
            return if (months > 600) null else months.toInt().coerceAtLeast(1)
        }
    }
}
