package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import com.assetboard.CloseTestContainers
import com.assetboard.TestContainers
import com.assetboard.data.ThemeMode
import com.assetboard.data.TodoDraft
import com.assetboard.ui.theme.AssetBoardTheme
import com.assetboard.ui.todo.TodoHomeScreen
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

/**
 * A todo held and slid right goes into the todo above it as a checklist item; held and moved up
 * or down, even with a long rest before letting go, it only changes places.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class TodoNestTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(composeRule).around(CloseTestContainers())
    private val compose get() = composeRule

    private fun show(): AppContainer {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        runBlocking(c.gate.bound()) {
            c.todos.create(TodoDraft("先写的"))
            c.todos.create(TodoDraft("后写的"))
            c.todos.create(TodoDraft("最后的"))
        }
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { TodoHomeScreen({}, {}, initialView = "inbox") }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("最后的")).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(1_000)
        return c
    }

    private fun titles(c: AppContainer) = runBlocking { c.todos.loadOnce().todos }.filter { it.deletedAt == null }.sortedBy { it.sortOrder }.map { it.title }

    /** Held, then moved by [dx], [dy] in a few steps; [rest] ms still before letting go. */
    private fun drag(text: String, dx: Float, dy: Float, rest: Long = 100, cancelled: Boolean = false) {
        compose.mainClock.autoAdvance = false
        val row = compose.onNodeWithText(text)
        row.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(800)
        repeat(6) {
            row.performTouchInput { moveBy(Offset(dx / 6, dy / 6)) }
            compose.mainClock.advanceTimeBy(50)
        }
        compose.mainClock.advanceTimeBy(rest)
        row.performTouchInput { if (cancelled) cancel() else up() }
        compose.mainClock.advanceTimeBy(100)
        compose.mainClock.autoAdvance = true
    }

    @Test fun slidRightItGoesIntoTheTodoAbove() {
        val c = show()
        // 80dp right at xxhdpi, past the 56dp where it goes in.
        drag("后写的", dx = 240f, dy = 0f)
        compose.waitUntil(5_000) { "后写的" !in titles(c) }
        assertEquals(listOf("先写的", "最后的"), titles(c))
        val d = runBlocking { c.todos.loadOnce() }
        val target = d.todos.first { it.title == "先写的" }
        assertEquals(listOf("后写的"), d.checklistByTodo[target.id].orEmpty().map { it.text })
    }

    @Test fun aShortSlideStaysARow() {
        val c = show()
        // 30dp: not far enough to go in.
        drag("后写的", dx = 90f, dy = 0f)
        compose.mainClock.advanceTimeBy(500)
        assertEquals(listOf("先写的", "后写的", "最后的"), titles(c))
        assertTrue(runBlocking { c.todos.loadOnce() }.checklistByTodo.values.all { it.isEmpty() })
    }

    @Test fun aCancelledDragDoesNotMerge() {
        val c = show()
        drag("后写的", dx = 240f, dy = 0f, cancelled = true)
        compose.mainClock.advanceTimeBy(500)
        assertEquals(listOf("先写的", "后写的", "最后的"), titles(c))
        assertTrue(runBlocking { c.todos.loadOnce() }.checklistByTodo.values.all { it.isEmpty() })
    }

    @Test fun selectedRowsMergeIntoTheVisibleRowAbove() {
        val c = show()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("后写的").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("后写的").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("最后的").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("已选 2 条").assertExists()
        drag("最后的", dx = 240f, dy = 0f)
        compose.waitUntil(5_000) { titles(c) == listOf("先写的") }
        val d = runBlocking { c.todos.loadOnce() }
        val target = d.todos.first { it.title == "先写的" }
        assertEquals(setOf("后写的", "最后的"), d.checklistByTodo[target.id].orEmpty().map { it.text }.toSet())
    }

    @Test fun movedUpAndRestedItOnlyChangesPlaces() {
        val c = show()
        fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top
        val gap = top("最后的") - top("后写的")
        drag("最后的", dx = 0f, dy = -gap - 20f, rest = 1_500)
        compose.waitUntil(5_000) { titles(c) == listOf("先写的", "最后的", "后写的") }
        assertTrue(runBlocking { c.todos.loadOnce() }.checklistByTodo.values.all { it.isEmpty() })
    }
}
