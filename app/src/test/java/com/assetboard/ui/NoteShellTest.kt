package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import com.assetboard.CloseTestContainers
import com.assetboard.TestContainers
import com.assetboard.data.SourceRef
import com.assetboard.data.ThemeMode
import com.assetboard.domain.todo.Recurrence
import com.assetboard.ui.notes.dayLabel
import com.assetboard.ui.theme.AssetBoardTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** The notes tab inside the real shell: a source leads to its diary day, unsaved text is never dropped silently. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class NoteShellTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(composeRule).around(CloseTestContainers())
    private val compose get() = composeRule

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
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
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

    @Test fun theTopicPillIsAsWideAsTheNotesUnderIt() {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        runBlocking(c.gate.bound()) {
            val topic = c.notes.createTopic("读书")
            c.notes.create("一本书的摘要", null, topicId = topic)
        }
        show(c)
        compose.onNodeWithText("笔记").performClick()
        waitFor("一本书的摘要")
        val pill = compose.onNode(hasText("主题") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val note = compose.onNode(hasText("一本书的摘要") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        assertEquals("the pill should start where the notes do", note.left, pill.left, 1f)
        assertEquals("the pill should end where the notes do", note.right, pill.right, 1f)
    }

    @Test fun theTopicDeckFiltersToThePickedTopic() {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        val topic = runBlocking(c.gate.bound()) {
            val topic = c.notes.createTopic("读书")
            c.notes.create("一本书的摘要", null, topicId = topic)
            c.notes.create("没有主题的想法", null)
            topic
        }
        show(c)
        compose.onNodeWithText("笔记").performClick()
        waitFor("一本书的摘要")
        // The pill counts topics, not the deck's 全部/未归类 cards.
        val pill = compose.onNode(hasText("主题") and hasClickAction()).fetchSemanticsNode()
        val pillText = pill.config[androidx.compose.ui.semantics.SemanticsProperties.Text].map { it.text }
        assertTrue("the pill should say one topic: $pillText", pillText.contains("1"))
        compose.onNodeWithText("主题").performClick()
        waitFor("左右滑动浏览，点中间那张筛选")
        // The deck opens on 全部; swipe to 读书, then pick it.
        compose.onNodeWithText("所有主题与未归类的笔记").performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithTag("deck:$topic").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("没有主题的想法")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("一本书的摘要").assertExists()
    }

    /** Each deck keeps its own choice of carousel or page; switching one leaves the other alone. */
    @Test fun theTwoDecksRememberTheirModesSeparately() {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        runBlocking(c.gate.bound()) {
            c.todos.createList("搬家")
            c.todos.createList("论文")
            val topic = c.notes.createTopic("读书")
            c.notes.create("一本书的摘要", null, topicId = topic)
        }
        show(c)
        // The lists' deck is switched to the list of rows and closed.
        compose.onNodeWithText("待办").performClick()
        waitFor("清单")
        compose.onNodeWithText("清单").performClick()
        waitFor("左右滑动浏览，点中间那张打开")
        compose.onNodeWithContentDescription("列表展示").performClick()
        waitFor("点一行打开 · 右滑编辑 · 左滑删除 · 长按拖动排序")
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("点一行打开 · 右滑编辑 · 左滑删除 · 长按拖动排序")).fetchSemanticsNodes().isEmpty() }
        // The topics' deck knows nothing of that choice and opens on its carousel.
        compose.onNodeWithText("笔记").performClick()
        waitFor("一本书的摘要")
        compose.onNodeWithText("主题").performClick()
        waitFor("左右滑动浏览，点中间那张筛选")
        // And the lists' deck still opens the way it was left.
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithText("待办").performClick()
        waitFor("清单")
        compose.onNodeWithText("清单").performClick()
        waitFor("点一行打开 · 右滑编辑 · 左滑删除 · 长按拖动排序")
    }

    @Test fun unsavedNoteAsksBeforeGoingBack() {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
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
