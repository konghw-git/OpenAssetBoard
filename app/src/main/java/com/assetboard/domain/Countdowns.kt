package com.assetboard.domain

import com.assetboard.data.Countdown
import java.time.LocalDate
import java.time.MonthDay
import java.time.temporal.ChronoUnit

/** Where a countdown stands on [today]: [days] to go, 0 on the day, negative once passed. */
data class CountdownState(
    val countdown: Countdown,
    /** The day counted to: the date itself, or for a yearly one its next occurrence. */
    val target: LocalDate,
    val days: Long,
    /** Which occurrence [target] is for a yearly one (1 a year after the first date); null otherwise. */
    val nth: Int?,
) {
    val passed: Boolean get() = days < 0
    val isToday: Boolean get() = days == 0L

    /** "还有" / "今天" / "已经": the word before the number. */
    val lead: String get() = when {
        isToday -> "就是今天"
        passed -> "已经"
        else -> "还有"
    }
}

/** Every function takes "today" explicitly, as the todo views do. */
object Countdowns {
    fun parse(date: String): LocalDate? = runCatching { LocalDate.parse(date) }.getOrNull()

    /** The next [date]'s month and day on or after [today]; 29 February falls on the 28th in other years. */
    fun nextOccurrence(date: LocalDate, today: LocalDate): LocalDate {
        if (!date.isBefore(today)) return date
        val md = MonthDay.from(date)
        var year = today.year
        while (true) {
            val d = md.atYear(year)
            if (!d.isBefore(today)) return d
            year++
        }
    }

    fun state(c: Countdown, today: LocalDate): CountdownState? {
        val date = parse(c.date) ?: return null
        val target = if (c.yearly) nextOccurrence(date, today) else date
        val nth = if (c.yearly) (target.year - date.year).takeIf { it > 0 } else null
        return CountdownState(c, target, ChronoUnit.DAYS.between(today, target), nth)
    }

    /**
     * The order lists and the widget show: the pinned one first, then what is still ahead, the
     * nearest first, then what has passed, the most recent first.
     */
    fun ordered(items: List<Countdown>, today: LocalDate): List<CountdownState> =
        items.mapNotNull { state(it, today) }.sortedWith(
            compareByDescending<CountdownState> { it.countdown.pinned }
                .thenBy { it.passed }
                .thenBy { if (it.passed) -it.days else it.days }
                .thenBy { it.countdown.createdAt },
        )

    /** "12月25日 周五", with the year when it is not this one. */
    fun dateLabel(d: LocalDate, today: LocalDate): String {
        val week = "周" + "一二三四五六日"[d.dayOfWeek.value - 1]
        return if (d.year == today.year) "${d.monthValue}月${d.dayOfMonth}日 $week" else "${d.year}年${d.monthValue}月${d.dayOfMonth}日 $week"
    }

    /** What goes under the title: the date, and which birthday or anniversary it is. */
    fun subtitle(s: CountdownState, today: LocalDate): String =
        listOfNotNull(dateLabel(s.target, today), s.nth?.let { "第 $it 年" }).joinToString(" · ")

    const val MAX_TITLE = 40
}
