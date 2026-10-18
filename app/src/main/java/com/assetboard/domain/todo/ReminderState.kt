package com.assetboard.domain.todo

import com.assetboard.data.Todo
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** What to do with a todo's reminder right now. */
sealed interface ReminderPlan {
    data object None : ReminderPlan

    /** Schedule an alarm for [at]. [snooze] tells a snoozed reminder from the planned one. */
    data class At(val at: Long, val snooze: Boolean) : ReminderPlan

    /** The reminder time has passed by at most a day: deliver now, marked late. */
    data class Late(val at: Long, val lateMinutes: Long, val snooze: Boolean) : ReminderPlan

    /** Passed by more than a day: no notification; listed under missed reminders, then marked reminded. */
    data class Missed(val at: Long) : ReminderPlan
}

/**
 * Reminder state is two independent fields: `reminded_period` (has this period's planned
 * reminder been delivered) and `snooze_at` (a one-shot reminder consumed when it fires).
 */
object ReminderState {
    const val CATCH_UP_WINDOW_MS = 24L * 3600 * 1000

    fun plannedAt(t: Todo, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val date = TodoViews.parse(t.planDate) ?: return null
        val time = t.remindTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return null
        return date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    }

    /** The reminder currently due, if any: a pending snooze first, else the planned one if not yet delivered. */
    fun pending(t: Todo, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Boolean>? {
        if (!TodoViews.isOpen(t)) return null
        t.snoozeAt?.let { return it to true }
        if (t.remindedPeriod == t.occurrenceCount) return null
        return plannedAt(t, zone)?.let { it to false }
    }

    fun plan(t: Todo, now: Long, zone: ZoneId = ZoneId.systemDefault()): ReminderPlan {
        val (at, snooze) = pending(t, zone) ?: return ReminderPlan.None
        return when {
            at > now -> ReminderPlan.At(at, snooze)
            now - at <= CATCH_UP_WINDOW_MS -> ReminderPlan.Late(at, (now - at) / 60_000, snooze)
            else -> ReminderPlan.Missed(at)
        }
    }

    /** After a reminder was delivered (or written off as missed). */
    fun delivered(t: Todo): Todo = t.copy(remindedPeriod = t.occurrenceCount, snoozeAt = null)

    /**
     * After the plan date or reminder time was edited by hand: a future time will remind once
     * more; a time already past counts as reminded, so editing never fires a stale reminder.
     */
    fun afterManualEdit(t: Todo, now: Long, zone: ZoneId = ZoneId.systemDefault()): Todo {
        val at = plannedAt(t, zone)
        return t.copy(snoozeAt = null, remindedPeriod = if (at != null && at <= now) t.occurrenceCount else null)
    }

    /** "Tomorrow" on a notification. */
    fun tomorrow(t: Todo, today: LocalDate, now: Long, zone: ZoneId = ZoneId.systemDefault()): Todo =
        if (t.kind == com.assetboard.data.TodoKind.REPEAT_SOURCE) {
            // Keep the series' rhythm: only the reminder moves, to the same time tomorrow.
            val time = t.remindTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            val at = time?.let { today.plusDays(1).atTime(it).atZone(zone).toInstant().toEpochMilli() } ?: (now + 24L * 3600 * 1000)
            t.copy(snoozeAt = at)
        } else {
            t.copy(planDate = today.plusDays(1).toString(), snoozeAt = null, remindedPeriod = null)
        }
}
