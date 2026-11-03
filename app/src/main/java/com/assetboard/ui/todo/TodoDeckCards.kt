package com.assetboard.ui.todo

import com.assetboard.data.TodoData
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
)

/** [ListDeckRow]s for [lists] in the given order. Null [d] (data still loading) yields empty rows. */
fun buildListDeckRows(lists: List<TodoList>, d: TodoData?, today: LocalDate): List<ListDeckRow> {
    val openByList = d?.todos.orEmpty().filter(TodoViews::isOpen).groupBy { it.listId }
    val sectionsByList = d?.sections.orEmpty().filter { it.deletedAt == null }.groupingBy { it.listId }.eachCount()
    return lists.map { l ->
        val open = openByList[l.id].orEmpty()
        ListDeckRow(
            id = l.id,
            name = l.name,
            open = open.size,
            deadline = TodoViews.parse(l.deadline)?.let { TodoViews.deadlineBadge(it, today)?.text },
            sections = sectionsByList[l.id] ?: 0,
            preview = open.sortedBy { it.sortOrder }.take(3).map { it.title },
        )
    }
}
