package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.assetboard.data.ThemeMode
import com.assetboard.ui.notes.NoteEditorState
import com.assetboard.ui.notes.NoteFormatBar
import com.assetboard.ui.theme.AssetBoardTheme
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Frequent actions occupy one row; the rest stay reachable behind an explicit expansion.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class NoteToolbarTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val buttons = listOf(
        "撤销", "重做", "一级标题", "二级标题", "三级标题",
        "待办清单", "无序列表", "有序列表", "引用", "分割线",
        "加粗", "斜体", "删除线", "行内代码", "增加缩进", "减少缩进",
        "AI排版", "润色",
    )

    private fun show() {
        compose.setContent {
            AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) {
                val state = remember { NoteEditorState {} }
                NoteFormatBar(state)
            }
        }
        compose.waitForIdle()
    }

    private fun topOf(label: String) = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot.top

    @Test fun everyButtonIsReachableAfterExpanding() {
        show()
        compose.onNodeWithContentDescription("更多工具").performClick()
        buttons.forEach { compose.onNodeWithContentDescription(it).assertExists() }
    }

    @Test fun primaryActionsOccupyOneRowOnAPhone() {
        show()
        assertEquals("primary actions fit one compact row", topOf("撤销"), topOf("润色"))
        val height = compose.onNodeWithTag("note-format-bar").fetchSemanticsNode().boundsInRoot.height
        assertTrue("toolbar is at most 56dp", height <= 56 * compose.activity.resources.displayMetrics.density)
    }

    @Test
    @Config(qualifiers = "w720dp-h880dp-xxhdpi")
    fun theButtonsSitOnOneRowOnAWideScreen() {
        show()
        assertEquals("one row on a wide screen", topOf("撤销"), topOf("润色"))
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xxhdpi")
    fun outlineAndAiActionsRemainVisibleOnASmallPhone() {
        compose.setContent {
            AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) {
                NoteFormatBar(remember { NoteEditorState {} }, onOutline = {})
            }
        }
        listOf("撤销", "二级标题", "加粗", "待办清单", "AI排版", "润色", "大纲", "更多工具").forEach {
            compose.onNodeWithContentDescription(it).assertIsDisplayed()
            assertEquals(topOf("撤销"), topOf(it))
        }
    }

    @Test fun aLongPressSaysWhatTheButtonDoes() {
        show()
        compose.onNodeWithContentDescription("加粗").performTouchInput { longClick() }
        compose.waitUntil(3_000) {
            compose.onAllNodes(hasText("加粗", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
