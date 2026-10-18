package com.assetboard.domain.todo

import com.assetboard.data.Todo
import com.assetboard.data.TodoKind
import com.assetboard.data.TodoList
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class TodoView(val label: String) { INBOX("收件箱"), TODAY("今天"), UPCOMING("计划"), SOMEDAY("以后再说"), LOGBOOK("日志") }

/** One row of a view. [done] rows are finished today and shown greyed until the day ends. */
data class ViewRow(val todo: Todo, val done: Boolean, val overdue: Boolean)

/**
 * View membership, ordering and counts. Lists, the widget and the entry counts all call these,
 * so they never disagree. Every function takes "today" explicitly.
 */
object TodoViews {
    fun parse(date: String?): LocalDate? = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun localDate(at: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    /** Undeleted, unfinished: an ordinary todo not completed, or a repeat source still running. */
    fun isOpen(t: Todo): Boolean = t.deletedAt == null && when (t.kind) {
        TodoKind.ORDINARY -> t.completedAt == null
        TodoKind.REPEAT_SOURCE -> t.repeatEndedAt == null
        TodoKind.COMPLETION -> false
    }

    /** Rows of the log: completed ordinary todos and every completion record. */
    fun isLogged(t: Todo): Boolean = t.deletedAt == null && t.completedAt != null

    private fun doneOn(t: Todo, today: LocalDate, zone: ZoneId): Boolean =
        isLogged(t) && localDate(t.completedAt!!, zone) == today

    fun inToday(t: Todo, today: LocalDate): Boolean {
        val plan = parse(t.planDate)
        val deadline = parse(t.deadline)
        return (plan != null && !plan.isAfter(today)) || (deadline != null && !deadline.isAfter(today))
    }

    fun inUpcoming(t: Todo, today: LocalDate): Boolean {
        if (inToday(t, today) || t.someday) return false
        val plan = parse(t.planDate)
        val deadline = parse(t.deadline)
        return (plan != null && plan.isAfter(today)) || (deadline != null && deadline.isAfter(today))
    }

    fun inSomeday(t: Todo): Boolean = t.someday

    fun inInbox(t: Todo): Boolean = t.listId == null && t.planDate == null && !t.someday

    fun matches(view: TodoView, t: Todo, today: LocalDate): Boolean = when (view) {
        TodoView.INBOX -> inInbox(t)
        TodoView.TODAY -> inToday(t, today)
        TodoView.UPCOMING -> inUpcoming(t, today)
        TodoView.SOMEDAY -> inSomeday(t)
        TodoView.LOGBOOK -> isLogged(t)
    }

    fun isOverdue(t: Todo, today: LocalDate): Boolean {
        val plan = parse(t.planDate)
        val deadline = parse(t.deadline)
        return (plan != null && plan.isBefore(today)) || (deadline != null && deadline.isBefore(today))
    }

    /** The open count shown on entries and on the widget; finished-today rows are not counted. */
    fun count(view: TodoView, todos: List<Todo>, today: LocalDate): Int =
        if (view == TodoView.LOGBOOK) 0 else todos.count { isOpen(it) && matches(view, it, today) }

    fun countInList(listId: String, todos: List<Todo>): Int = todos.count { isOpen(it) && it.listId == listId }

    /** Open rows plus today's finished rows, both filtered by [predicate]. */
    private fun rows(todos: List<Todo>, today: LocalDate, zone: ZoneId, predicate: (Todo) -> Boolean): List<ViewRow> =
        todos.filter { (isOpen(it) || doneOn(it, today, zone)) && predicate(it) }
            .map { ViewRow(it, done = !isOpen(it), overdue = isOpen(it) && isOverdue(it, today)) }

    /**
     * Today: the overdue group first, then today's. Within a group important first, then list
     * order, then order within the list; the overdue group additionally earliest date first.
     */
    fun today(todos: List<Todo>, lists: List<TodoList>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<ViewRow> {
        val listOrder = lists.associate { it.id to it.sortOrder }
        fun earliest(t: Todo): LocalDate = listOfNotNull(parse(t.planDate), parse(t.deadline)).minOrNull() ?: today
        val cmp = compareBy<ViewRow>(
            { if (it.done) 1 else 0 },
            { if (isOverdue(it.todo, today)) 0 else 1 },
            { if (it.todo.important) 0 else 1 },
            { if (isOverdue(it.todo, today)) earliest(it.todo) else LocalDate.MIN },
            { it.todo.listId?.let { id -> listOrder[id] }?.plus(1) ?: 0 },
            { it.todo.sortOrder },
        )
        return rows(todos, today, zone) { inToday(it, today) }.sortedWith(cmp)
    }

    /** Upcoming, grouped by plan date, else by deadline. */
    fun upcoming(todos: List<Todo>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Pair<LocalDate, List<ViewRow>>> =
        rows(todos, today, zone) { inUpcoming(it, today) }
            .groupBy { r -> parse(r.todo.planDate)?.takeIf { it.isAfter(today) } ?: parse(r.todo.deadline)!! }
            .toSortedMap()
            .map { (d, rs) -> d to rs.sortedWith(compareBy({ it.done }, { !it.todo.important }, { it.todo.sortOrder })) }

    /** Inbox, a list, or someday: manual order, finished rows last. */
    fun manual(todos: List<Todo>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault(), predicate: (Todo) -> Boolean): List<ViewRow> =
        rows(todos, today, zone, predicate).sortedWith(compareBy({ it.done }, { it.todo.sortOrder }))

    fun inbox(todos: List<Todo>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()) = manual(todos, today, zone, ::inInbox)

    fun someday(todos: List<Todo>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()) = manual(todos, today, zone, ::inSomeday)

    fun list(listId: String, todos: List<Todo>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()) =
        manual(todos, today, zone) { it.listId == listId }

    /** The log, most recent first. */
    fun logbook(todos: List<Todo>): List<Todo> = todos.filter(::isLogged).sortedByDescending { it.completedAt }

    // ------------------------------------------------------------ row labels

    /** Trailing text for a row: overdue days, deadline countdown, or a future date. */
    fun trailing(t: Todo, today: LocalDate, inUpcomingView: Boolean = false): DateBadge? {
        val plan = parse(t.planDate)
        val deadline = parse(t.deadline)
        if (deadline != null) {
            val left = ChronoUnit.DAYS.between(today, deadline)
            when {
                left < 0 -> return DateBadge("已超期 ${-left} 天", DateBadge.Tone.ALERT)
                left == 0L -> return DateBadge("今天截止", DateBadge.Tone.ALERT)
                left <= 3 -> return DateBadge("截止还剩 $left 天", DateBadge.Tone.WARN)
            }
        }
        if (plan != null && plan.isBefore(today)) {
            return DateBadge("已过 ${ChronoUnit.DAYS.between(plan, today)} 天", DateBadge.Tone.WARN)
        }
        if (!inUpcomingView && plan != null && plan.isAfter(today)) return DateBadge(shortDate(plan, today), DateBadge.Tone.PLAIN)
        if (deadline != null) return DateBadge("截止 " + shortDate(deadline, today), DateBadge.Tone.PLAIN)
        return null
    }

    fun shortDate(d: LocalDate, today: LocalDate): String {
        val diff = ChronoUnit.DAYS.between(today, d)
        return when {
            diff == 0L -> "今天"
            diff == 1L -> "明天"
            diff == -1L -> "昨天"
            diff in 2..6 -> Recurrence.weekName(d.dayOfWeek)
            d.year == today.year -> "${d.monthValue}月${d.dayOfMonth}日"
            else -> "${d.year}年${d.monthValue}月${d.dayOfMonth}日"
        }
    }
}

data class DateBadge(val text: String, val tone: Tone) {
    enum class Tone { PLAIN, WARN, ALERT }
}
