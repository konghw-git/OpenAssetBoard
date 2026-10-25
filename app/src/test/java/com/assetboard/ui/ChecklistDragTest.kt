package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import com.assetboard.CloseTestContainers
import com.assetboard.TestContainers
import com.assetboard.data.ThemeMode
import com.assetboard.data.TodoDraft
import com.assetboard.ui.theme.AssetBoardTheme
import com.assetboard.ui.todo.TodoHomeScreen
import com.assetboard.ui.todo.TodoDetailScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h1300dp-xhdpi")
class ChecklistDragTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(compose).around(CloseTestContainers())

    private fun show(detail: Boolean = false): AppContainer {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        var parentA = ""
        runBlocking(c.gate.bound()) {
            val a = c.todos.create(TodoDraft("Parent A"))
            parentA = a
            val b = c.todos.create(TodoDraft("Parent B"))
            for (text in listOf("A1", "A2", "A3")) c.todos.addChecklistItem(a, text)
            for (text in listOf("B1", "B2")) c.todos.addChecklistItem(b, text)
        }
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) {
                    if (detail) TodoDetailScreen(parentA, {}, {}) else TodoHomeScreen({}, {}, initialView = "inbox")
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(if (detail) "A3" else "B2")).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(1_000)
        return c
    }

    private fun items(c: AppContainer, title: String) = runBlocking {
        val d = c.todos.loadOnce()
        val parent = d.todos.single { it.title == title && it.deletedAt == null }
        d.checklistByTodo[parent.id].orEmpty().sortedBy { it.sortOrder }.map { it.text }
    }

    private fun drag(from: String, to: String, cancel: Boolean = false, handle: Boolean = false, keepClockStopped: Boolean = false) {
        fun node(text: String) = if (handle) compose.onNodeWithContentDescription("拖动检查项：$text") else compose.onNodeWithText(text)
        val start = node(from).fetchSemanticsNode().boundsInRoot.center
        val end = node(to).fetchSemanticsNode().boundsInRoot.center + Offset(0f, 10f)
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { down(start) }
        compose.mainClock.advanceTimeBy(900)
        repeat(10) { step ->
            val next = start + (end - start) * ((step + 1) / 10f)
            compose.onRoot().performTouchInput { moveTo(next) }
            compose.mainClock.advanceTimeBy(40)
        }
        compose.mainClock.advanceTimeBy(200)
        compose.onRoot().performTouchInput { if (cancel) cancel() else up() }
        compose.mainClock.advanceTimeBy(100)
        compose.mainClock.autoAdvance = !keepClockStopped
    }

    @Test fun reorderWithinTheChecklist() {
        val c = show()
        drag("A1", "A3")
        compose.waitUntil(5_000) { items(c, "Parent A") == listOf("A2", "A3", "A1") }
        assertEquals(listOf("B1", "B2"), items(c, "Parent B"))
    }

    @Test fun theOtherItemsMakeRoomWhileOneIsHeld() {
        show()
        fun top(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        val a2 = top("A2")
        val a3 = top("A3")
        val start = compose.onNodeWithText("A1").fetchSemanticsNode().boundsInRoot.center
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { down(start) }
        compose.mainClock.advanceTimeBy(900)
        repeat(10) { step ->
            compose.onRoot().performTouchInput { moveTo(start + Offset(0f, (a3 - a2) * 2.2f * (step + 1) / 10f)) }
            compose.mainClock.advanceTimeBy(40)
        }
        compose.mainClock.advanceTimeBy(600)
        // A1's slot went to the end: A2 and A3 slid up into the room it left, before any drop.
        assertTrue(top("A2") < a2 - 10f)
        assertTrue(top("A3") < a3 - 10f)
        compose.onRoot().performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(600)
        compose.mainClock.autoAdvance = true
        assertEquals(a2, top("A2"), 2f)
    }

    @Test fun moveIntoAnotherTodosChecklistAtTheDropPosition() {
        val c = show()
        drag("A2", "B1")
        compose.waitUntil(5_000) { items(c, "Parent B") == listOf("B1", "A2", "B2") }
        assertEquals(listOf("A1", "A3"), items(c, "Parent A"))
    }

    @Test fun cancellingDoesNotMoveOrToggleTheItem() {
        val c = show()
        drag("A2", "B1", cancel = true)
        assertEquals(listOf("A1", "A2", "A3"), items(c, "Parent A"))
        assertEquals(listOf("B1", "B2"), items(c, "Parent B"))
        assertEquals(0, runBlocking { c.todos.loadOnce().checklist.count { it.done } })
    }

    @Test fun theDetailHandleSortsWithoutEditingOrToggling() {
        val c = show(detail = true)
        drag("A1", "A3", handle = true)
        compose.waitUntil(5_000) { items(c, "Parent A") == listOf("A2", "A3", "A1") }
        assertEquals(0, runBlocking { c.todos.loadOnce().checklist.count { it.done } })
    }

    @Test fun crossTodoMoveCanBeUndoneFromTheSnackbar() {
        val c = show()
        drag("A2", "B1", keepClockStopped = true)
        repeat(90) {
            if (compose.onAllNodes(hasText("撤销")).fetchSemanticsNodes().isEmpty()) {
                Thread.sleep(20)
                compose.mainClock.advanceTimeByFrame()
            }
        }
        assertEquals(listOf("B1", "A2", "B2"), items(c, "Parent B"))
        compose.onNodeWithText("撤销").performClick()
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { items(c, "Parent A") == listOf("A1", "A2", "A3") }
        assertEquals(listOf("B1", "B2"), items(c, "Parent B"))
    }

    @Test fun autoScrollKeepsTheDragAliveAfterItsSourceLeavesTheScreen() {
        val c = show()
        runBlocking(c.gate.bound()) {
            repeat(12) { i ->
                val parent = c.todos.create(TodoDraft("Far $i"))
                c.todos.addChecklistItem(parent, "End $i")
            }
        }
        compose.waitUntil(10_000) { c.todos.data.value?.todos?.size == 14 }
        compose.mainClock.advanceTimeBy(1_000)
        val start = compose.onNodeWithText("A1").fetchSemanticsNode().boundsInRoot.center
        val bounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { down(start) }
        compose.mainClock.advanceTimeBy(900)
        compose.onRoot().performTouchInput { moveTo(Offset(start.x, bounds.bottom - 12f)) }
        repeat(150) {
            compose.mainClock.advanceTimeBy(32)
            Thread.sleep(5)
        }
        assertEquals(0, compose.onAllNodes(hasText("Parent A")).fetchSemanticsNodes().size)
        val end = compose.onNodeWithText("End 11").fetchSemanticsNode().boundsInRoot.center + Offset(0f, 10f)
        compose.onRoot().performTouchInput { moveTo(end) }
        compose.mainClock.advanceTimeBy(100)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { items(c, "Far 11") == listOf("End 11", "A1") }
        assertEquals(listOf("A2", "A3"), items(c, "Parent A"))
    }
}
