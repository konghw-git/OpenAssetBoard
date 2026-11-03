package com.assetboard.ui.todo

import com.assetboard.data.TodoData
import com.assetboard.data.TodoKind
import com.assetboard.data.TodoList
import com.assetboard.domain.todo.TodoViews
import java.time.LocalDate

/**
 * One card's worth of a list, precomputed outside composition: the deck used to scan every todo
 * once per list while opening, which is what made the dialog feel slow on long lists.
 */
data class ListDeckRow(
    val id: String,
    val name: String,
    /** Open todos in the list: what the count gem shows. */
    val open: Int,
    /** The deadline badge text, when the list has a deadline that still shows. */
    val deadline: String?,
    /** Undeleted sections of the list. */
    val sections: Int,
    /** The first open titles in sort order, so two similar lists can be told apart without opening. */
    val preview: List<String>,
    /**
     * The finished share of the list's own todos, zero to one, or null when the list holds nothing
     * to finish yet. Settled means no longer open — done, or a repeat that has run its course —
     * among the undeleted todos that are not completion records.
     */
    val progress: Float?,
)

/** [ListDeckRow]s for [lists] in the given order. Null [d] (data still loading) yields empty rows. */
fun buildListDeckRows(lists: List<TodoList>, d: TodoData?, today: LocalDate): List<ListDeckRow> {
    val mine = d?.todos.orEmpty().filter { it.deletedAt == null && it.kind != TodoKind.COMPLETION }
    val openByList = mine.filter(TodoViews::isOpen).groupBy { it.listId }
    val settledByList = mine.filterNot(TodoViews::isOpen).groupBy { it.listId }
    val sectionsByList = d?.sections.orEmpty().filter { it.deletedAt == null }.groupingBy { it.listId }.eachCount()
    return lists.map { l ->
        val open = openByList[l.id].orEmpty()
        val settled = settledByList[l.id].orEmpty().size
        val total = open.size + settled
        ListDeckRow(
            id = l.id,
            name = l.name,
            open = open.size,
            deadline = TodoViews.parse(l.deadline)?.let { TodoViews.deadlineBadge(it, today)?.text },
            sections = sectionsByList[l.id] ?: 0,
            preview = open.sortedBy { it.sortOrder }.take(3).map { it.title },
            progress = if (total == 0) null else settled.toFloat() / total,
        )
    }
}
