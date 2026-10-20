package com.assetboard.ui

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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.assetboard.data.ThemeMode
import com.assetboard.ui.components.SwipeAction
import com.assetboard.ui.components.LocalTabSwipeZones
import com.assetboard.ui.components.SwipeActionsBox
import com.assetboard.ui.components.TabSwipeHandles
import com.assetboard.ui.components.TabSwipeZones
import com.assetboard.ui.theme.AssetBoardTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Rows with swipe actions inside the tab pager: any stroke on a row works the row, one that starts
 * on an edge handle turns the page, and a row inside a row (a checklist item in its todo) takes
 * only its own direction.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class RowGestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val rows = mutableStateListOf("待办", "另一条")
    private val items = mutableStateListOf("检查项")
    private val completed = mutableStateOf(false)
    private lateinit var pager: PagerState
    private val zones = TabSwipeZones()

    private fun show() {
        compose.setContent {
            AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) {
                pager = rememberPagerState(initialPage = 1) { 3 }
                CompositionLocalProvider(LocalTabSwipeZones provides zones) {
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
                    // Rows fill the page, so the handles lie over them.
                    if (page == 1) TabSwipeHandles(active = false)
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

    @Test fun aStrokeFromTheRightHandleTurnsThePage() {
        show()
        // Halfway down the page, a few pixels in from the right edge: on the handle, over a row.
        compose.onRoot().performTouchInput {
            down(Offset(width - 12f, height / 2f))
            repeat(6) { moveBy(Offset(-150f, 0f)) }
            up()
        }
        settle()
        assertEquals(2, pager.currentPage)
        assertTrue("另一条" in rows)
    }

    @Test fun aStrokeFromTheLeftHandleTurnsBack() {
        show()
        compose.onRoot().performTouchInput {
            down(Offset(12f, height / 2f))
            repeat(6) { moveBy(Offset(150f, 0f)) }
            up()
        }
        settle()
        assertEquals(0, pager.currentPage)
        assertFalse(completed.value)
    }

    @Test fun theSameStrokeBelowTheHandleWorksTheRow() {
        show()
        // Same edge, near the bottom: outside the handle, so the row under it takes the stroke.
        compose.onRoot().performTouchInput {
            down(Offset(width - 40f, height * 0.72f))
            repeat(8) { moveBy(Offset(-150f, 0f)) }
            up()
        }
        settle()
        assertEquals(1, pager.currentPage)
        assertFalse("另一条" in rows)
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
}
