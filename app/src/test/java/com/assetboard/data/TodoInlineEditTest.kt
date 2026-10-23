package com.assetboard.data

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime

/** What an edit in place writes, and a reschedule with a time of day. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TodoInlineEditTest {
    private val f = Fixture()

    @After fun close() = f.close()

    private suspend fun items(id: String) = f.db.todoDao().checklistOf(id).sortedBy { it.sortOrder }

    @Test fun editInPlaceWritesTitleAndChecklistInOrder() = f.run {
        val id = f.todos.create(TodoDraft("买菜"))
        val keep = f.todos.addChecklistItem(id, "西红柿")
        val drop = f.todos.addChecklistItem(id, "鸡蛋")
        f.todos.toggleChecklist(keep)
        f.todos.editInline(
            id, "  周末买菜 ",
            listOf(InlineItem(null, "葱"), InlineItem(keep, "番茄"), InlineItem(null, "   "), InlineItem(drop, "")),
        )
        assertEquals("周末买菜", f.todos.todo(id)!!.title)
        val now = items(id)
        assertEquals(listOf("葱", "番茄"), now.map { it.text })
        // The item kept its id and its tick; the blank new one and the emptied one are gone.
        assertEquals(keep, now[1].id)
        assertEquals(true, now[1].done)
        assertNull(f.db.todoDao().checklistItem(drop))
    }

    @Test fun aBlankTitleKeepsTheOldOneAndNothingChangedWritesNothing() = f.run {
        val id = f.todos.create(TodoDraft("跑步"))
        val item = f.todos.addChecklistItem(id, "拉伸")
        val rev = f.todos.todo(id)!!.revision
        f.todos.editInline(id, "跑步", listOf(InlineItem(item, "拉伸")))
        assertEquals(rev, f.todos.todo(id)!!.revision)
        f.todos.editInline(id, "  ", listOf(InlineItem(item, "拉伸")))
        assertEquals("跑步", f.todos.todo(id)!!.title)
    }

    @Test fun gatheredTodosBecomeTheChecklistOfANewOneAndComeBackOnUndo() = f.run {
        val list = f.todos.createList("周末")
        val a = f.todos.create(TodoDraft("买菜", listId = list, planDate = date("2026-10-22")))
        val b = f.todos.create(TodoDraft("洗车", listId = list, planDate = date("2026-10-20"), deadline = date("2026-10-25"), important = true))
        f.todos.addChecklistItem(b, "打蜡")
        val first = f.todos.todo(a)!!.sortOrder
        val (id, batch) = f.todos.gather(listOf(a, b), "周末杂事")
        val t = f.todos.todo(id)!!
        assertEquals("周末杂事", t.title)
        assertEquals(list, t.listId)
        // Wherever both showed: the earliest day and deadline, important as one of them was.
        assertEquals("2026-10-20", t.planDate)
        assertEquals("2026-10-25", t.deadline)
        assertEquals(true, t.important)
        assertEquals(first, t.sortOrder)
        assertEquals(listOf("买菜", "洗车"), items(id).map { it.text })
        assertEquals(batch, f.todos.todo(a)!!.deleteBatch)
        assertEquals(batch, f.todos.todo(b)!!.deleteBatch)

        f.todos.ungather(id, batch)
        assertNull(f.todos.todo(id))
        assertNull(f.todos.todo(a)!!.deletedAt)
        assertNull(f.todos.todo(b)!!.deletedAt)
        // What they had of their own came back with them.
        assertEquals(listOf("打蜡"), items(b).map { it.text })
        // An edit still open on the gathered todo ends quietly.
        f.todos.editInline(id, "晚了", emptyList())
    }

    @Test fun gatheringFromSeveralListsLeavesTheNewOneInTheInbox() = f.run {
        val x = f.todos.create(TodoDraft("甲", listId = f.todos.createList("一")))
        val y = f.todos.create(TodoDraft("乙", listId = f.todos.createList("二")))
        val (id, _) = f.todos.gather(listOf(y, x), "合并")
        assertNull(f.todos.todo(id)!!.listId)
        assertEquals(listOf("乙", "甲"), items(id).map { it.text })
    }

    @Test fun rescheduleSetsKeepsOrClearsTheTime() = f.run {
        val id = f.todos.create(TodoDraft("开会", planDate = date("2026-10-20"), remindTime = LocalTime.of(9, 0)))
        f.todos.reschedule(listOf(id), date("2026-10-21"), false)
        assertEquals("09:00", f.todos.todo(id)!!.remindTime)
        f.todos.reschedule(listOf(id), date("2026-10-21"), false, TimeChange.At(LocalTime.of(15, 30)))
        assertEquals("2026-10-21", f.todos.todo(id)!!.planDate)
        assertEquals("15:30", f.todos.todo(id)!!.remindTime)
        f.todos.reschedule(listOf(id), date("2026-10-22"), false, TimeChange.Clear)
        assertNull(f.todos.todo(id)!!.remindTime)
        // Without a day there is no time.
        f.todos.reschedule(listOf(id), null, false, TimeChange.At(LocalTime.of(8, 0)))
        assertNull(f.todos.todo(id)!!.planDate)
        assertNull(f.todos.todo(id)!!.remindTime)
    }
}
