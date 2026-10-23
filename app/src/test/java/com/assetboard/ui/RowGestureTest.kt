package com.assetboard.ui

import com.assetboard.CloseTestContainers
import com.assetboard.TestContainers
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.assetboard.data.seedDemo
import com.assetboard.ui.overview.OverviewScreen
import kotlinx.coroutines.runBlocking
import com.assetboard.data.ThemeMode
import com.assetboard.ui.components.SwipeAction
import com.assetboard.ui.components.SwipeActionsBox
import com.assetboard.ui.theme.AssetBoardTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Rows with swipe actions inside the tab pager: any stroke on a row works the row and only the row,
 * one beside the rows turns the page, and a row inside a row (a checklist item in its todo) takes
 * only its own direction.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class RowGestureTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(composeRule).around(CloseTestContainers())
    private val compose get() = composeRule

    private val rows = mutableStateListOf("待办", "另一条")
    private val items = mutableStateListOf("检查项")
    private val completed = mutableStateOf(false)
    private lateinit var pager: PagerState
    /** Whether the pages moved at all, however briefly, since [show]. */
    private var pagerMoved = false

    private fun show() {
        compose.setContent {
            AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) {
                pager = rememberPagerState(initialPage = 1) { 3 }
                LaunchedEffect(pager) { snapshotFlow { pager.isScrollInProgress }.collect { if (it) pagerMoved = true } }
                HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                    Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        if (page == 1) rows.forEach { r ->
                            SwipeActionsBox(
                                key = r, group = null, shape = RoundedCornerShape(18.dp),
                                actions = listOf(SwipeAction("完成", Icons.Default.Check, Color.Green) { completed.value = true }),
                                onDelete = { rows.remove(r) },
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(r, Modifier.fillMaxWidth().height(if (r == "另一条") 600.dp else 60.dp))
                                    if (r == "待办") items.forEach { i ->
                                        SwipeActionsBox(key = "check:$i", group = null, shape = RoundedCornerShape(10.dp), onDelete = { items.remove(i) }, compact = true) {
                                            Text(i, Modifier.fillMaxWidth().height(36.dp))
                                        }
                                    }
                                }
                            }
                        } else {
                            Text("第 $page 页")
                        }
                    }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Put down, eased past touch slop, then pushed far: the way a row is swiped open or away. */
    private fun TouchInjectionScope.deliberate(dx: Float) {
        down(center)
        repeat(6) { moveBy(Offset(dx / 100f, 0f)) }
        repeat(4) { moveBy(Offset(dx / 4f, 0f)) }
        up()
    }

    /** Already moving fast when it lands. */
    private fun TouchInjectionScope.flick(dx: Float) {
        down(center)
        repeat(6) { moveBy(Offset(dx / 6f, 0f), delayMillis = 8) }
        up()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
    }

    @Test fun aFlickAcrossARowWorksTheRow() {
        show()
        compose.onNodeWithText("另一条").performTouchInput { flick(-1100f) }
        settle()
        assertEquals(1, pager.currentPage)
        assertFalse("另一条" in rows)
    }

    @Test fun aStrokeBelowTheRowsTurnsThePage() {
        show()
        // The rows end above the bottom of the page; the empty space under them is the pager's.
        compose.onRoot().performTouchInput {
            down(Offset(width / 2f, height * 0.95f))
            repeat(6) { moveBy(Offset(-150f, 0f)) }
            up()
        }
        settle()
        assertEquals(2, pager.currentPage)
        assertTrue("另一条" in rows)
    }

    @Test fun aWobblingSwipeNeverMovesThePage() {
        show()
        // A real finger: past slop, then moves that are only vertical, or no move at all, between
        // the sideways ones. Each of those once let the pager pick the stroke up under the row.
        compose.onNodeWithText("另一条").performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(-4f, 0f)) }
            moveBy(Offset(-40f, 0f))
        }
        repeat(6) { i ->
            compose.onNodeWithText("另一条").performTouchInput {
                moveBy(Offset(0f, if (i % 2 == 0) 3f else -3f))
                moveBy(Offset(0f, if (i % 2 == 0) -2f else 2f))
                moveBy(Offset(-30f, 1f))
            }
            compose.waitForIdle()
        }
        compose.onNodeWithText("另一条").performTouchInput { up() }
        settle()
        assertFalse("the pages moved under the row", pagerMoved)
        assertEquals(1, pager.currentPage)
    }

    @Test fun aDeliberateDragWorksTheRow() {
        show()
        compose.onNodeWithText("另一条").performTouchInput { deliberate(-1100f) }
        settle()
        assertEquals(1, pager.currentPage)
        assertFalse("另一条" in rows)
    }

    @Test fun aChecklistItemSwipedLeftGoesAlone() {
        show()
        compose.onNodeWithText("检查项").performTouchInput { deliberate(-1100f) }
        settle()
        assertFalse("检查项" in items)
        assertTrue("待办" in rows)
        assertEquals(1, pager.currentPage)
    }

    @Test fun aChecklistItemSwipedRightSwipesItsTodo() {
        show()
        compose.onNodeWithText("检查项").performTouchInput { deliberate(1100f) }
        settle()
        assertTrue(completed.value)
        assertTrue("检查项" in items)
    }

    /**
     * A full right swipe on an account turns its pane into the amount field while the pane is
     * still sliding back from far right. The field once took focus out there, off the side of the
     * page, and the tabs turned toward it; and once focused, its cursor, a fraction of a pixel
     * past the edge, kept the tabs creeping on toward the next one: the whole screen shook.
     */
    @Test fun anAccountSwipedToItsAmountLeavesThePagesStill() {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        runBlocking(c.gate.bound()) { c.repository.seedDemo() }
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) {
                    pager = rememberPagerState { 2 }
                    LaunchedEffect(pager) { snapshotFlow { pager.isScrollInProgress }.collect { if (it) pagerMoved = true } }
                    HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                        if (page == 0) OverviewScreen({}, {}, {}, {}, {}, {}) else Text("第 $page 页")
                    }
                }
            }
        }
        compose.waitUntil(10_000) { c.repository.ledger.value != null }
        settle()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(2)
        settle()
        compose.onNodeWithText("全部展开").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        pagerMoved = false
        compose.onNodeWithText("农行①").performTouchInput { down(center); repeat(6) { moveBy(Offset(30f, 0f)) }; repeat(4) { moveBy(Offset(220f, 0f)) }; up() }
        settle()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertFalse("the pages moved under the account", pagerMoved)
        assertEquals(0, pager.currentPage)
        assertEquals(0f, pager.currentPageOffsetFraction)
    }
}
