package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import com.assetboard.data.SourceRef
import com.assetboard.data.ThemeMode
import com.assetboard.domain.todo.Recurrence
import com.assetboard.ui.notes.dayLabel
import com.assetboard.ui.theme.AssetBoardTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** The notes tab inside the real shell: a source leads to its diary day, unsaved text is never dropped silently. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class NoteShellTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(c: AppContainer) {
        c.settings.setBool(c.settings.networkEnabled, false)
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { AppNav(open = null, onOpenHandled = {}) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("资产")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitFor(text: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun sourceChipOpensThatDiaryDay() {
        val c = AppContainer(ApplicationProvider.getApplicationContext())
        val day = LocalDate.now().minusDays(3)
        runBlocking(c.gate.bound()) { c.notes.create("复盘里的结论\n要早睡", SourceRef.day(day)) }
        show(c)
        compose.onNodeWithText("笔记").performClick()
        waitFor("复盘里的结论")
        compose.onNodeWithText("复盘里的结论").performClick()
        waitFor("${dayLabel(day)}的日记")
        compose.onNodeWithText("${dayLabel(day)}的日记").performClick()
        waitFor("${day.monthValue}月 · ${Recurrence.weekName(day.dayOfWeek)}")
        // The diary tab is the one selected; its page title says 日记 too.
        compose.onNode(hasText("日记") and isSelected()).assertExists()
    }

    @Test fun unsavedNoteAsksBeforeGoingBack() {
        val c = AppContainer(ApplicationProvider.getApplicationContext())
        runBlocking(c.gate.bound()) { c.notes.create("写不进去", null) }
        show(c)
        compose.onNodeWithText("笔记").performClick()
        waitFor("写不进去")
        compose.onNodeWithText("写不进去").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("删除这篇")).fetchSemanticsNodes().isNotEmpty() }
        c.notes.failBodyWrites = true
        c.notes.failDraftWrites = true
        try {
            compose.onNode(hasSetTextAction()).performTextReplacement("写不进去，又写了一句")
            compose.waitUntil(8_000) { compose.onAllNodes(hasText("无法保存，请先复制内容")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("删除这篇").assertDoesNotExist()
            compose.onNodeWithContentDescription("返回").performClick()
            waitFor("这篇笔记的内容没能保存")
            compose.onNodeWithText("取消").performClick()
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor("这篇笔记的内容没能保存")
        } finally {
            c.notes.failBodyWrites = false
            c.notes.failDraftWrites = false
        }
    }
}
