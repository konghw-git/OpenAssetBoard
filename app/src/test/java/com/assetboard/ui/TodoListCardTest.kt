package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
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
import com.assetboard.ui.theme.AssetBoardTheme
import com.assetboard.ui.todo.TodoHomeScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * The lists as cards, on the phone as unfolded: a deadline's tag under the name, a left swipe
 * archives, a right swipe deletes, and held, a card is dragged to another place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class TodoListCardTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(composeRule).around(CloseTestContainers())
    private val compose get() = composeRule

    private fun show(): AppContainer {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        runBlocking(c.gate.bound()) {
            c.todos.createList("搬家")
            val paper = c.todos.createList("论文")
            c.todos.updateList(paper, "论文", "", LocalDate.now().plusDays(2))
            c.todos.createList("旅行")
        }
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { TodoHomeScreen({}, {}, initialView = "inbox") }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("旅行")).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(1_000)
        return c
    }

    private fun lists(c: AppContainer) = runBlocking { c.db.todoDao().liveLists() }
    private fun shown(c: AppContainer) = lists(c).filter { it.archivedAt == null }.sortedBy { it.sortOrder }.map { it.name }

    /** Eased past touch slop, then pushed far: a full swipe. */
    private fun TouchInjectionScope.swipe(dx: Float) {
        down(center)
        repeat(8) { moveBy(Offset(dx / 80, 0f)) }
        repeat(4) { moveBy(Offset(dx / 4, 0f)) }
        up()
    }

    @Test fun theDeadlineSitsUnderTheName() {
        show()
        val name = compose.onNodeWithText("论文", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val tag = compose.onNodeWithText("截止还剩 2 天", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assert(tag.top >= name.bottom - 1f) { "the tag should be under the name: $name, $tag" }
    }

    @Test fun aLeftSwipeArchives() {
        val c = show()
        // The clock by hand: waiting for idle would run the undo's time out at once.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("搬家").performTouchInput { swipe(-1000f) }
        repeat(90) {
            if (compose.onAllNodes(hasText("撤销")).fetchSemanticsNodes().isEmpty()) {
                Thread.sleep(20)
                compose.mainClock.advanceTimeByFrame()
            }
        }
        assertNotNull(lists(c).first { it.name == "搬家" }.archivedAt)
        assertEquals(listOf("论文", "旅行"), shown(c))
        // The undo brings it back.
        compose.onNodeWithText("撤销").performClick()
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { shown(c) == listOf("搬家", "论文", "旅行") }
    }

    @Test fun aRightSwipeDeletes() {
        val c = show()
        compose.onNodeWithText("旅行").performTouchInput { swipe(1000f) }
        compose.waitUntil(5_000) { lists(c).none { it.name == "旅行" } }
        assertEquals(listOf("搬家", "论文"), shown(c))
        assertNull(lists(c).firstOrNull { it.name == "旅行" })
        assertNotNull(runBlocking { c.db.todoDao().allLists() }.firstOrNull { it.name == "旅行" && it.deletedAt != null })
    }

    @Test fun heldAndMovedUpItChangesPlaces() {
        val c = show()
        fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top
        val gap = top("旅行") - top("论文")
        compose.mainClock.autoAdvance = false
        val row = compose.onNodeWithText("旅行")
        row.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(800)
        row.performTouchInput { moveBy(Offset(0f, -gap / 2)) }
        compose.mainClock.advanceTimeBy(100)
        row.performTouchInput { moveBy(Offset(0f, -gap / 2 - 20f)) }
        compose.mainClock.advanceTimeBy(300)
        row.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(100)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { shown(c) == listOf("搬家", "旅行", "论文") }
    }

    /** The order saved leaves the archived lists, not on screen, after the rest. */
    @Test fun theOrderSavedPutsTheArchivedLast() {
        val c = show()
        runBlocking(c.gate.bound()) {
            val all = lists(c).associateBy { it.name }
            c.todos.setArchived(all.getValue("搬家").id, true)
            c.todos.reorderLists(listOf(all.getValue("旅行").id, all.getValue("论文").id))
        }
        assertEquals(listOf("旅行", "论文", "搬家"), lists(c).sortedBy { it.sortOrder }.map { it.name })
    }
}
