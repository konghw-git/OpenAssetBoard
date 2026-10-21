package com.assetboard.ui

import com.assetboard.CloseTestContainers
import com.assetboard.TestContainers
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.assetboard.data.ThemeMode
import com.assetboard.ui.components.Burst
import com.assetboard.ui.components.DustHost
import androidx.compose.ui.graphics.ImageBitmap
import com.assetboard.ui.components.DustState
import com.assetboard.ui.components.LocalDust
import com.assetboard.ui.components.SwipeActionsBox
import com.assetboard.ui.theme.AssetBoardTheme
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

/** A row swiped away turns to dust; when it comes back (undo, the trash) it must be visible again. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class SwipeDeleteTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(composeRule).around(CloseTestContainers())
    private val compose get() = composeRule

    private val dust = DustState()
    private val rows = mutableStateListOf("甲", "乙", "丙")

    private fun show() {
        compose.setContent {
            AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) {
                CompositionLocalProvider(LocalDust provides dust) {
                    Box {
                        LazyColumn {
                            items(rows, key = { it }) { r ->
                                SwipeActionsBox(key = r, group = null, shape = RoundedCornerShape(18.dp), onDelete = { rows.remove(r) }) {
                                    Text(r, Modifier.fillMaxWidth().height(72.dp))
                                }
                            }
                        }
                        DustHost(dust)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun swipeAway(text: String) {
        // Put down and pushed: a deliberate drag, which the row takes (a flick would go to the pager).
        compose.onNodeWithText(text).performTouchInput {
            down(center)
            repeat(8) { moveBy(Offset(-10f, 0f)) }
            moveBy(Offset(-400f, 0f)); moveBy(Offset(-400f, 0f)); up()
        }
    }

    @Test fun aRowBroughtBackAfterTheDustIsVisible() {
        show()
        swipeAway("乙")
        compose.waitUntil(5_000) { "乙" !in rows }
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        // Restored from the trash, long after the dust settled.
        rows.add(1, "乙")
        compose.waitForIdle()
        assertFalse(dust.isHidden("乙"))
    }

    @Test fun aRowShowsAgainWhenItsDustSettlesOrItComesBack() {
        val image = ImageBitmap(1, 1)
        // The dust settles: the key no longer hides anything, so a restore later shows the row.
        val first = Burst("甲", image, Offset.Zero, FloatArray(0), 1f)
        dust.start(first)
        assertTrue(dust.isHidden("甲"))
        dust.finished(first)
        assertFalse(dust.isHidden("甲"))
        // An undo while the dust still drifts: the row composed again shows at once.
        val second = Burst("乙", image, Offset.Zero, FloatArray(0), 1f)
        dust.start(second)
        assertTrue(dust.isHidden("乙"))
        dust.cameBack("乙")
        assertFalse(dust.isHidden("乙"))
    }
}
