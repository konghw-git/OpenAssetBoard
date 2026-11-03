package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.assetboard.data.DeckMode
import com.assetboard.data.ThemeMode
import com.assetboard.ui.components.CardDeckContent
import com.assetboard.ui.components.DeckCard
import com.assetboard.ui.components.UndoEvent
import com.assetboard.ui.theme.AssetBoardTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The deck's one-tap mode is a list of entry rows: a tap opens the row, a swipe right renames it,
 * a swipe left deletes it the way a todo entry does (to an undo at the foot of the deck), and a
 * long press drags it into order. The fixed picks — 全部 and 未归类 — only open.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class DeckListModeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val picks = mutableListOf<String>()
    private val renames = mutableListOf<String>()
    private val deletes = mutableListOf<String>()
    private val orders = mutableListOf<List<String>>()

    private fun card(key: String, title: String, manageable: Boolean = true) = DeckCard(
        key = key, title = title, count = 1, accent = Color(0xFF23864D),
        icon = Icons.Outlined.FormatListBulleted, countLabel = "1 项未完成", manageable = manageable,
    )

    private fun show(cards: List<DeckCard>, undoEvents: SharedFlow<UndoEvent>? = null, onUndo: ((UndoEvent) -> Unit)? = null) {
        picks.clear(); renames.clear(); deletes.clear(); orders.clear()
        compose.setContent {
            AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) {
                CardDeckContent(
                    title = "清单", cards = cards, selected = null,
                    onPick = { picks += it }, onDismiss = {},
                    onRename = { renames += it }, onDelete = { deletes += it }, onReorder = { orders += it },
                    undoEvents = undoEvents, onUndo = onUndo,
                    rememberedMode = DeckMode.LIST,
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_600)
        compose.waitForIdle()
    }

    private fun row(key: String) = compose.onNodeWithTag("deck:$key")

    /** A deliberate drag, which the row takes (a flick would go to the list). */
    private fun swipeLeft(key: String) {
        row(key).performTouchInput {
            down(center)
            repeat(8) { moveBy(Offset(-10f, 0f)) }
            moveBy(Offset(-400f, 0f)); moveBy(Offset(-400f, 0f)); up()
        }
    }

    /** Short of the full-swipe threshold, so the action button shows instead of running. */
    private fun revealRight(key: String) {
        row(key).performTouchInput {
            down(center)
            repeat(8) { moveBy(Offset(10f, 0f)) }
            repeat(4) { moveBy(Offset(20f, 0f)) }
            up()
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    /** Past the full-swipe threshold: the first action runs, the way a full swipe left deletes. */
    private fun fullSwipeRight(key: String) {
        row(key).performTouchInput {
            down(center)
            repeat(8) { moveBy(Offset(10f, 0f)) }
            moveBy(Offset(400f, 0f)); moveBy(Offset(400f, 0f)); up()
        }
    }

    /** Held, then moved by [dx], [dy] in a few steps before letting go. */
    private fun drag(key: String, dx: Float, dy: Float) {
        compose.mainClock.autoAdvance = false
        val row = row(key)
        row.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(800)
        repeat(6) {
            row.performTouchInput { moveBy(Offset(dx / 6, dy / 6)) }
            compose.mainClock.advanceTimeBy(50)
        }
        compose.mainClock.advanceTimeBy(100)
        row.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(100)
        compose.mainClock.autoAdvance = true
    }

    @Test fun tappingARowPicksIt() {
        show(listOf(card("a", "甲"), card("b", "乙")))
        row("a").performClick()
        assertEquals(listOf("a"), picks)
    }

    @Test fun rightSwipeRenamesTheRow() {
        show(listOf(card("a", "甲"), card("b", "乙")))
        // The revealed button renames…
        revealRight("a")
        compose.onNodeWithText("重命名").performClick()
        assertEquals(listOf("a"), renames)
        // …and so does the swipe all the way.
        fullSwipeRight("b")
        assertEquals(listOf("a", "b"), renames)
    }

    @Test fun leftSwipeDeletesTheRowAndCallsTheDelete() {
        show(listOf(card("a", "甲"), card("b", "乙")))
        swipeLeft("a")
        compose.waitUntil(5_000) { deletes == listOf("a") }
    }

    @Test fun longPressDraggingReordersRowsAndReportsTheNewOrder() {
        show(listOf(card("a", "甲"), card("b", "乙"), card("c", "丙")))
        drag("a", dx = 0f, dy = 260f)
        compose.mainClock.advanceTimeBy(500)
        assertEquals(listOf(listOf("b", "a", "c")), orders)
    }

    @Test fun theAllAndUnsortedRowsNeitherSwipeNorDrag() {
        show(listOf(card("all", "全部", manageable = false), card("a", "甲"), card("u", "未归类", manageable = false)))
        swipeLeft("all")
        fullSwipeRight("all")
        swipeLeft("u")
        compose.mainClock.advanceTimeBy(500)
        assertTrue("fixed rows must not delete", deletes.isEmpty())
        assertTrue("fixed rows must not rename", renames.isEmpty())
        drag("all", dx = 0f, dy = 260f)
        assertTrue("fixed rows must not move", orders.isEmpty())
        // They still open.
        row("all").performClick()
        assertEquals(listOf("all"), picks)
    }

    @Test fun theUndoBarInTheDeckRunsItsUndo() {
        val events = MutableSharedFlow<UndoEvent>(extraBufferCapacity = 8)
        var undone = false
        show(listOf(card("a", "甲")), undoEvents = events, onUndo = { undone = true })
        compose.mainClock.autoAdvance = false
        // The action button only shows for an event that carries an undo to run.
        events.tryEmit(UndoEvent("已删除") { undone = true })
        repeat(90) {
            if (compose.onAllNodes(hasText("撤销")).fetchSemanticsNodes().isEmpty()) {
                Thread.sleep(20)
                compose.mainClock.advanceTimeByFrame()
            }
        }
        compose.onNodeWithText("撤销").performClick()
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { undone }
        assertTrue(undone)
    }
}
