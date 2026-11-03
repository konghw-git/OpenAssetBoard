package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import com.assetboard.CloseTestContainers
import com.assetboard.TestContainers
import com.assetboard.data.ThemeMode
import com.assetboard.ui.notes.NotesScreen
import com.assetboard.ui.notes.NotesViewModel
import com.assetboard.ui.theme.AssetBoardTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The topics, managed inside the deck's one-tap list: a swipe right renames, a swipe left deletes
 * with an undo that brings the topic and its notes back, and a long press drags the order home.
 * 全部 and 未归类 only filter.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class TopicDeckManageTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(composeRule).around(CloseTestContainers())
    private val compose get() = composeRule

    private fun show(): AppContainer {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        runBlocking(c.gate.bound()) {
            val reading = c.notes.createTopic("读书")
            c.notes.createTopic("健身")
            c.notes.createTopic("投资")
            c.notes.create("一本书的摘要", null, topicId = reading)
            c.notes.create("没有主题的想法", null)
        }
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { NotesScreen({}, {}, {}, {}) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("一本书的摘要")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("主题").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("左右滑动浏览，点中间那张筛选")).fetchSemanticsNodes().isNotEmpty() }
        // The one-tap mode as a list of rows.
        compose.onNodeWithContentDescription("列表展示").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("点一行筛选 · 右滑重命名 · 左滑删除 · 长按拖动排序")).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(600)
        return c
    }

    private fun topics(c: AppContainer) = runBlocking { c.db.noteDao().topics() }.map { it.name }
    private fun noteTopic(c: AppContainer, title: String) =
        runBlocking { c.db.noteDao().observeLive().first() }.first { it.title == title }.topicId

    /** A deliberate drag, which the row takes (a flick would go to the list). */
    private fun swipeLeft(key: String) {
        compose.onNodeWithTag("deck:$key").performTouchInput {
            down(center)
            repeat(8) { moveBy(Offset(-10f, 0f)) }
            moveBy(Offset(-400f, 0f)); moveBy(Offset(-400f, 0f)); up()
        }
    }

    /** Short of the full-swipe threshold, so the action button shows instead of running. */
    private fun revealRight(key: String) {
        compose.onNodeWithTag("deck:$key").performTouchInput {
            down(center)
            repeat(8) { moveBy(Offset(10f, 0f)) }
            repeat(4) { moveBy(Offset(20f, 0f)) }
            up()
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    /** Held, then moved by [dx], [dy] in a few steps before letting go. */
    private fun drag(key: String, dx: Float, dy: Float) {
        compose.mainClock.autoAdvance = false
        val row = compose.onNodeWithTag("deck:$key")
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

    @Test fun rightSwipeOnATopicRowRenamesTheTopic() {
        val c = show()
        val reading = runBlocking { c.db.noteDao().topics() }.first { it.name == "读书" }
        revealRight(reading.id)
        compose.onNodeWithText("重命名").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("重命名主题")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextReplacement("阅读")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5_000) { topics(c) == listOf("阅读", "健身", "投资") }
    }

    @Test fun leftSwipeOnATopicRowDeletesItAndUndoBringsItBack() {
        val c = show()
        val reading = runBlocking { c.db.noteDao().topics() }.first { it.name == "读书" }
        swipeLeft(reading.id)
        compose.waitUntil(5_000) { topics(c) == listOf("健身", "投资") }
        // Its note fell to 未归类.
        assertEquals(null, noteTopic(c, "一本书的摘要"))
        // The deck's own undo bar, pushed frame by frame: waiting idle would run its five seconds
        // out. The screen's bar collects the same event behind the deck; either undo runs it.
        compose.mainClock.autoAdvance = false
        repeat(90) {
            if (compose.onAllNodes(hasText("撤销")).fetchSemanticsNodes().isEmpty()) {
                Thread.sleep(20)
                compose.mainClock.advanceTimeByFrame()
            }
        }
        compose.onAllNodes(hasText("撤销"))[0].performClick()
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { topics(c) == listOf("读书", "健身", "投资") }
        val back = runBlocking { c.db.noteDao().topics() }.first { it.name == "读书" }
        assertEquals(reading.id, back.id)
        compose.waitUntil(5_000) { noteTopic(c, "一本书的摘要") == back.id }
    }

    @Test fun draggingTopicRowsSavesTheOrder() {
        val c = show()
        val ids = runBlocking { c.db.noteDao().topics() }
        drag(ids.first { it.name == "读书" }.id, dx = 0f, dy = 260f)
        compose.mainClock.advanceTimeBy(500)
        assertEquals(listOf("健身", "读书", "投资"), topics(c))
    }

    @Test fun theAllAndUnsortedRowsOnlyFilter() {
        val c = show()
        // 未归类 opens the filter; it has no rename, no delete and no place in the order.
        compose.onNodeWithTag("deck:${NotesViewModel.UNSORTED}").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("没有主题的想法")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("一本书的摘要").assertDoesNotExist()
        assertEquals(listOf("读书", "健身", "投资"), topics(c))
    }
}
