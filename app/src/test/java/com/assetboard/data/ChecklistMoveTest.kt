package com.assetboard.data

import android.app.Application
import com.assetboard.domain.LedgerException
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChecklistMoveTest {
    private val f = Fixture()
    @After fun close() = f.close()
    private suspend fun items(id: String) = f.db.todoDao().checklistOf(id)
    private suspend fun parent(name: String, vararg texts: String): String {
        val id = f.todos.create(TodoDraft(name))
        texts.forEach { f.todos.addChecklistItem(id, it) }
        return id
    }

    @Test fun crossTodoMovePreservesTheItemAndUndoRestoresBothOrders() = f.run {
        val a = parent("A", "A1", "A2", "A3")
        val b = parent("B", "B1", "B2")
        val moved = items(a)[1]
        f.todos.toggleChecklist(moved.id)
        val seq = listOf(a, b).associateWith { f.todos.todo(it)!!.stateSeq }
        val undo = f.todos.moveChecklistItem(moved.id, b, items(b)[1].id)!!
        assertEquals(listOf("A1", "A3"), items(a).map { it.text })
        assertEquals(listOf("B1", "A2", "B2"), items(b).map { it.text })
        assertEquals(moved.id, items(b)[1].id)
        assertTrue(items(b)[1].done)
        assertEquals(seq, listOf(a, b).associateWith { f.todos.todo(it)!!.stateSeq })
        f.todos.undoChecklistMove(undo)
        assertEquals(listOf("A1", "A2", "A3"), items(a).map { it.text })
        assertEquals(listOf("B1", "B2"), items(b).map { it.text })
        assertTrue(items(a)[1].done)
    }

    @Test fun sortingIsPersistentAndDroppingInPlaceDoesNotWrite() = f.run {
        val a = parent("A", "A1", "A2", "A3")
        val first = items(a).first().id
        val revision = f.todos.todo(a)!!.revision
        assertNull(f.todos.moveChecklistItem(first, a, items(a)[1].id))
        assertEquals(revision, f.todos.todo(a)!!.revision)
        val undo = f.todos.moveChecklistItem(first, a, null)!!
        assertEquals(listOf("A2", "A3", "A1"), items(a).map { it.text })
        assertEquals(listOf(0, 1, 2), items(a).map { it.sortOrder })
        f.todos.undoChecklistMove(undo)
        assertEquals(listOf("A1", "A2", "A3"), items(a).map { it.text })
    }

    @Test fun staleUndoDoesNotOverwriteALaterTick() = f.run {
        val a = parent("A", "A1")
        val b = parent("B", "B1")
        val moved = items(a).single().id
        val undo = f.todos.moveChecklistItem(moved, b, null)!!
        f.todos.toggleChecklist(moved)
        try { f.todos.undoChecklistMove(undo); fail("stale undo accepted") } catch (_: LedgerException) { }
        assertTrue(items(a).isEmpty())
        assertEquals(listOf("B1", "A1"), items(b).map { it.text })
        assertTrue(items(b).last().done)
    }

    @Test fun gapsInStoredOrdersDoNotTurnAHoldIntoAMove() = f.run {
        val a = parent("A", "A1", "A2", "A3")
        f.todos.deleteChecklistItem(items(a)[1].id)
        val before = items(a)
        val revision = f.todos.todo(a)!!.revision
        assertNull(f.todos.moveChecklistItem(before[0].id, a, before[1].id))
        assertEquals(before, items(a))
        assertEquals(revision, f.todos.todo(a)!!.revision)
    }

    @Test fun missingPositionOrCompletedTargetCannotPartiallyMoveAnItem() = f.run {
        val a = parent("A", "A1")
        val b = parent("B", "B1")
        val moved = items(a).single().id
        try { f.todos.moveChecklistItem(moved, b, "missing"); fail("missing position accepted") } catch (_: LedgerException) { }
        f.todos.complete(b, f.todos.todo(b)!!.stateSeq)
        try { f.todos.moveChecklistItem(moved, b, null); fail("completed target accepted") } catch (_: LedgerException) { }
        assertEquals(listOf("A1"), items(a).map { it.text })
        assertEquals(listOf("B1"), items(b).map { it.text })
    }
}
