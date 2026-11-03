package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.assetboard.data.ThemeMode
import com.assetboard.ui.notes.NoteEditorState
import com.assetboard.ui.notes.NoteFormatBar
import com.assetboard.ui.theme.AssetBoardTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The format bar wraps instead of scrolling: every button is on screen at phone width across two
 * or three rows, and the wide screen holds them all on one.
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

    @Test fun everyButtonIsReachableWithoutScrolling() {
        show()
        buttons.forEach { compose.onNodeWithContentDescription(it).assertExists() }
    }

    @Test fun theButtonsWrapIntoSeveralRowsOnAPhone() {
        show()
        assertNotEquals("undo starts a row of its own up top", topOf("撤销"), topOf("润色"))
    }

    @Test
    @Config(qualifiers = "w720dp-h880dp-xxhdpi")
    fun theButtonsSitOnOneRowOnAWideScreen() {
        show()
        assertEquals("one row on a wide screen", topOf("撤销"), topOf("润色"))
    }
}
