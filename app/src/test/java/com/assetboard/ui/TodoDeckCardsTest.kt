package com.assetboard.ui

import com.assetboard.data.Todo
import com.assetboard.data.TodoData
import com.assetboard.data.TodoKind
import com.assetboard.data.TodoList
import com.assetboard.data.TodoSection
import com.assetboard.ui.todo.buildListDeckRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** The deck rows must agree with the counts and previews the inline code used to build. */
class TodoDeckCardsTest {
    private val today = LocalDate.of(2026, 10, 25)
    private var n = 0

    private fun list(id: String, deadline: String? = null) =
        TodoList(id = id, name = "清单$id", deadline = deadline, createdAt = 0)

    private fun section(list: String, deletedAt: Long? = null) =
        TodoSection(id = "s${n++}", listId = list, name = "分区", deletedAt = deletedAt)

    private fun todo(
        title: String,
        list: String?,
        sortOrder: Int = n,
        completedAt: Long? = null,
        deletedAt: Long? = null,
        kind: TodoKind = TodoKind.ORDINARY,
        repeatEndedAt: Long? = null,
    ) = Todo(
        id = "t${n++}", kind = kind, title = title, listId = list, sortOrder = sortOrder,
        createdAt = 0, completedAt = completedAt, deletedAt = deletedAt, repeatEndedAt = repeatEndedAt,
    )

    private fun data(todos: List<Todo> = emptyList(), sections: List<TodoSection> = emptyList()) =
        TodoData(lists = emptyList(), sections = sections, todos = todos, checklist = emptyList())

    private fun rows(d: TodoData?, lists: List<TodoList>) = buildListDeckRows(lists, d, today)

    @Test fun countsOnlyTheOpenTodosOfEachList() {
        val d = data(
            todos = listOf(
                todo("开着", list = "a"),
                todo("做完了", list = "a", completedAt = 1),
                todo("删掉了", list = "a", deletedAt = 1),
                todo("完成记录", list = "a", kind = TodoKind.COMPLETION),
                todo("重复进行中", list = "a", kind = TodoKind.REPEAT_SOURCE),
                todo("重复结束了", list = "a", kind = TodoKind.REPEAT_SOURCE, repeatEndedAt = 1),
                todo("别的清单", list = "b"),
                todo("收件箱", list = null),
            ),
        )
        val row = rows(d, listOf(list("a"))).single()
        assertEquals(2, row.open)
    }

    @Test fun previewsTheFirstThreeOpenTitlesInSortOrder() {
        val d = data(
            todos = listOf(
                todo("三", list = "a", sortOrder = 3),
                todo("一", list = "a", sortOrder = 1),
                todo("做完了", list = "a", sortOrder = 0, completedAt = 1),
                todo("二", list = "a", sortOrder = 2),
                todo("四", list = "a", sortOrder = 4),
            ),
        )
        val row = rows(d, listOf(list("a"))).single()
        assertEquals(listOf("一", "二", "三"), row.preview)
    }

    @Test fun anEmptyListCountsZeroAndPreviewsNothing() {
        val row = rows(data(), listOf(list("a"))).single()
        assertEquals(0, row.open)
        assertEquals(emptyList<String>(), row.preview)
    }

    @Test fun theDeadlineShowsAsBadgeText() {
        val rows = rows(data(), listOf(list("soon", deadline = today.plusDays(1).toString()), list("plain")))
        assertEquals("截止还剩 1 天", rows[0].deadline)
        assertNull(rows[1].deadline)
    }

    @Test fun countsOnlyUndeletedSections() {
        val d = data(sections = listOf(section("a"), section("a"), section("a", deletedAt = 1), section("b")))
        val row = rows(d, listOf(list("a"))).single()
        assertEquals(2, row.sections)
    }

    @Test fun rowsFollowTheListOrderGiven() {
        val d = data(todos = listOf(todo("b 里的", list = "b")))
        val rows = rows(d, listOf(list("b"), list("a")))
        assertEquals(listOf("b", "a"), rows.map { it.id })
        assertEquals(listOf(1, 0), rows.map { it.open })
    }

    @Test fun withoutDataEveryRowIsEmpty() {
        val row = rows(null, listOf(list("a"))).single()
        assertEquals(0, row.open)
        assertEquals(0, row.sections)
        assertNull(row.deadline)
        assertEquals(emptyList<String>(), row.preview)
    }
}
