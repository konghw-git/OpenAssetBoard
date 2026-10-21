package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import com.assetboard.CloseTestContainers
import com.assetboard.TestContainers
import com.assetboard.data.DiarySession
import com.assetboard.data.ThemeMode
import com.assetboard.data.TodoDraft
import com.assetboard.domain.todo.RepeatRule
import com.assetboard.domain.todo.RepeatType
import com.assetboard.ui.diary.DiaryCalendarScreen
import com.assetboard.ui.diary.DiaryScreen
import com.assetboard.ui.settings.ReminderGuideScreen
import com.assetboard.ui.settings.SettingsScreen
import com.assetboard.ui.settings.TrashScreen
import com.assetboard.ui.theme.AssetBoardTheme
import com.assetboard.ui.todo.TodoDetailScreen
import com.assetboard.ui.todo.TodoHomeScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.assetboard.ui.components.DustHost
import com.assetboard.ui.components.DustState
import com.assetboard.ui.components.LocalDust
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.plus
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextReplacement
import kotlinx.coroutines.flow.first
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalTime

/**
 * The todo and diary screens with realistic data, into build/screenshots/ for visual review.
 * Not an assertion suite.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class ModuleScreenshotTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(composeRule).around(CloseTestContainers())
    private val compose get() = composeRule

    private val today = LocalDate.now()

    private class Demo(val c: AppContainer, val listId: String, val detailId: String)

    private fun demo(): Demo {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        val t = c.todos
        var listId = ""
        var detail = ""
        runBlocking(c.gate.bound()) {
            c.repository.seedDefaultGroups()
            listId = t.createList("论文 XYTX")
            val s1 = t.createSection(listId, "实验")
            val s2 = t.createSection(listId, "写作")
            t.create(TodoDraft("整理数据集", listId = listId, sectionId = s1, planDate = today))
            t.create(TodoDraft("跑对照实验", listId = listId, sectionId = s1, deadline = today.plusDays(2), important = true))
            t.create(TodoDraft("写引言", listId = listId, sectionId = s2, planDate = today.plusDays(3)))
            t.create(TodoDraft("画图 3", listId = listId, sectionId = s2))
            t.create(TodoDraft("给导师发周报", planDate = today.minusDays(1), remindTime = LocalTime.of(9, 0)))
            t.create(TodoDraft("交水电费", deadline = today, important = true))
            val gym = t.create(TodoDraft("健身", planDate = today, remindTime = LocalTime.of(19, 30), strongRemind = true))
            t.setRepeat(gym, RepeatRule(RepeatType.WEEKLY, weekdays = listOf(1, 3, 5, today.dayOfWeek.value)))
            detail = t.create(TodoDraft("准备组会 PPT", listId = listId, planDate = today, remindTime = LocalTime.of(14, 0), deadline = today.plusDays(1), notes = "重点讲消融实验和下一步计划"))
            t.addChecklistItem(detail, "实验结果表")
            t.toggleChecklist(t.addChecklistItem(detail, "消融实验图"))
            t.addChecklistItem(detail, "下一步计划")
            for (x in listOf("相关工作两页", "复现基线的坑", "时间线", "问导师的三个问题")) t.addChecklistItem(detail, x)
            t.create(TodoDraft("买猫粮"))
            t.create(TodoDraft("看《置身事内》", someday = true))
            t.create(TodoDraft("办护照", planDate = today.plusDays(5)))
            val done = t.create(TodoDraft("回邮件", planDate = today))
            t.complete(done, t.todo(done)!!.stateSeq)
            t.delete(t.create(TodoDraft("过期的想法")))
            c.habits.toggle(com.assetboard.domain.Habit.SUPPLEMENTS)
            c.habits.toggle(com.assetboard.domain.Habit.WORKOUT)
            c.habits.toggle(com.assetboard.domain.Habit.WATER)
            val s = DiarySession(c.diary, today, c.appScope + c.gate.bound())
            s.open(null)
            s.onInput("早上六点半起床，跑了五公里。\n\n上午把实验数据又过了一遍，发现第三组的对照有问题，下午重新跑了一遍，结果和预期一致。\n\n晚上和朋友吃了火锅。")
            s.saveNow()
            s.close()
            val past = DiarySession(c.diary, today.minusYears(1), c.appScope + c.gate.bound())
            past.open(null)
            past.onInput("去年今天第一次去爬了紫金山。")
            past.saveNow()
            past.close()
        }
        return Demo(c, listId, detail)
    }

    private fun shot(name: String, container: AppContainer, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                AssetBoardTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT, upIsRed = true) { content() }
            }
        }
        compose.waitUntil(10_000) { container.todos.data.value != null }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test fun todoHome() {
        val d = demo()
        shot("20-todo-home", d.c) { TodoHomeScreen({}, {}) }
    }

    @Test fun todoToday() {
        val d = demo()
        shot("21-todo-today", d.c) { TodoHomeScreen({}, {}, initialView = "today") }
    }

    @Test fun todoSwipeAndDust() {
        val d = demo()
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides d.c) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) {
                    val dust = remember { DustState() }
                    CompositionLocalProvider(LocalDust provides dust) {
                        Box {
                            TodoHomeScreen({}, {}, initialView = "today")
                            DustHost(dust)
                        }
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("交水电费")).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(2_000)
        // Eased past touch slop first: a deliberate drag, which the row takes (a flick goes to the pager).
        compose.onNodeWithText("交水电费").performTouchInput { down(center); repeat(8) { moveBy(Offset(10f, 0f)) }; moveBy(Offset(200f, 0f)); moveBy(Offset(200f, 0f)) }
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage("build/screenshots/26-todo-swipe-right.png")
        compose.onNodeWithText("交水电费").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)
        // A long left swipe: the card turns to dust, the undo bar comes up. The clock is driven
        // by hand, or waiting for idle would run the dust to its end.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("给导师发周报").performTouchInput { down(center); repeat(8) { moveBy(Offset(-10f, 0f)) }; moveBy(Offset(-300f, 0f)); moveBy(Offset(-300f, 0f)); up() }
        repeat(30) { compose.mainClock.advanceTimeByFrame() }
        compose.onRoot().captureRoboImage("build/screenshots/27-todo-dust.png")
        repeat(120) { compose.mainClock.advanceTimeByFrame() }
        compose.onRoot().captureRoboImage("build/screenshots/28-todo-undo.png")
    }

    @Test fun todoUpcoming() {
        val d = demo()
        shot("22-todo-upcoming", d.c) { TodoHomeScreen({}, {}, initialView = "upcoming") }
    }

    @Test fun todoList() {
        val d = demo()
        shot("23-todo-list", d.c) { TodoHomeScreen({}, {}, initialView = "list:${d.listId}") }
    }

    @Test fun todoComposer() {
        val d = demo()
        shot("27-todo-composer", d.c) { TodoHomeScreen({}, {}, request = OpenRequest.QuickAdd) }
    }

    /** The 每日 pill opens the habit editor; a habit's form opens under it, and so does a new one's. */
    @Test fun habitEditor() {
        val d = demo()
        shot("20-todo-home", d.c) { TodoHomeScreen({}, {}, initialView = "today") }
        compose.onNodeWithText("每日").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("每日打卡").assertExists()
        compose.onRoot().captureRoboImage("build/screenshots/20d-habit-editor.png")
        compose.onNodeWithText("添加打卡").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNode(hasSetTextAction()).performTextReplacement("读书")
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/20e-habit-new.png")
        compose.onNodeWithText("添加").performClick()
        compose.waitUntil(5_000) { runBlocking { d.c.habits.observeActive().first().size } == 6 }
    }

    /** Seven habits fill the battery; the diary shows today's seven, past days their five. */
    @Test fun sevenHabits() {
        val d = demo()
        runBlocking(d.c.gate.bound()) {
            d.c.habits.add("读书", "book", 0xFF2A9D8F)
            val m = d.c.habits.add("冥想", "meditate", 0xFFC2477A)
            d.c.habits.toggle(d.c.habits.observeActive().first().first { it.key == m })
        }
        shot("20f-todo-seven-habits", d.c) { TodoHomeScreen({}, {}, initialView = "today") }
    }

    /** Pulling the list down opens the new-todo field; pulling again while it is open opens nothing more. */
    @Test fun todoPullOpensTheComposer() {
        val d = demo()
        shot("20-todo-home", d.c) { TodoHomeScreen({}, {}, initialView = "today") }
        compose.onAllNodes(hasText("准备做什么？")).assertCountEquals(0)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("待办").performTouchInput { down(center); moveBy(Offset(0f, 40f)); repeat(6) { moveBy(Offset(0f, 100f)) } }
        repeat(20) { compose.mainClock.advanceTimeByFrame() }
        compose.onRoot().captureRoboImage("build/screenshots/27a-todo-pull-armed.png")
        compose.onNodeWithText("松手新建待办", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("待办").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("准备做什么？")).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(1_000)
        // Open already: the list stops at its top (the title does not move) and there is still one field.
        fun titleTop() = compose.onNodeWithText("待办").fetchSemanticsNode().boundsInRoot.top
        val before = titleTop()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("待办").performTouchInput { down(center); repeat(6) { moveBy(Offset(0f, 100f)) } }
        repeat(20) { compose.mainClock.advanceTimeByFrame() }
        assert(titleTop() == before) { "the list should not pull while the field is open" }
        compose.onNodeWithText("待办").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(1_000)
        compose.onAllNodes(hasText("准备做什么？")).assertCountEquals(1)
    }

    @Test fun todoInbox() {
        val d = demo()
        shot("28-todo-inbox", d.c) { TodoHomeScreen({}, {}, initialView = "inbox") }
    }

    @Test fun todoDetail() {
        val d = demo()
        shot("24-todo-detail", d.c) { TodoDetailScreen(d.detailId, onBack = {}, onOpenTodo = {}) }
    }

    /** Long press and drag: the lower row of a band moves above the upper one, and stays there. */
    @Test fun todoDragReorders() {
        val d = demo()
        shot("21-todo-today", d.c) { TodoHomeScreen({}, {}, initialView = "today") }
        compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToIndex(6)
        compose.mainClock.advanceTimeBy(500)
        fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top
        val (upper, lower) = listOf("准备组会 PPT", "整理数据集").sortedBy(::top)
        val gap = top(lower) - top(upper)
        // While a row is held the list keeps watching its edges every frame: step the clock by hand.
        compose.mainClock.autoAdvance = false
        val row = compose.onNodeWithText(lower)
        row.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(800)
        row.performTouchInput { moveBy(Offset(0f, -gap / 2)) }
        compose.mainClock.advanceTimeBy(100)
        row.performTouchInput { moveBy(Offset(0f, -gap / 2 - 20f)) }
        compose.mainClock.advanceTimeBy(300)
        compose.onRoot().captureRoboImage("build/screenshots/29-todo-drag.png")
        row.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(100)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) {
            val order = runBlocking { d.c.todos.loadOnce().todos }.associate { it.title to it.sortOrder }
            order.getValue(lower) < order.getValue(upper)
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        assert(top(lower) < top(upper)) { "$lower should now be above $upper" }
    }

    @Test fun todoTodayDark() {
        val d = demo()
        shot("25-todo-today-dark", d.c, dark = true) { TodoHomeScreen({}, {}, initialView = "today") }
    }

    @Config(qualifiers = "w850dp-h900dp-xhdpi")
    @Test fun todoUnfolded() {
        val d = demo()
        shot("26-todo-unfolded", d.c) { TodoHomeScreen({}, {}) }
    }

    @Test fun diary() {
        val d = demo()
        shot("30-diary", d.c) { DiaryScreen(null, {}, {}, {}, {}) }
    }

    @Test fun diaryDark() {
        val d = demo()
        shot("32-diary-dark", d.c, dark = true) { DiaryScreen(null, {}, {}, {}, {}) }
    }

    @Test fun diaryCalendar() {
        val d = demo()
        shot("31-diary-calendar", d.c) { DiaryCalendarScreen(null, onBack = {}, onPick = {}) }
    }

    @Test fun trash() {
        val d = demo()
        shot("40-trash", d.c) { TrashScreen(onBack = {}) }
    }

    @Test fun updateDialog() {
        val d = demo()
        d.c.updater.present(
            com.assetboard.data.UpdateState.Available(
                com.assetboard.data.Release("0.8.1", "## 新功能\n- **目标资产**：净资产卡片上的进度条\n- 待办可以长按拖动排序\n\n## 修复\n- 每日卡片的标题不再和格子重叠", "", 12_345_678, ""),
            ),
        )
        shot("43-update-dialog", d.c) { com.assetboard.ui.settings.UpdatePrompt() }
    }

    @Test fun settingsFull() {
        val d = demo()
        shot("41-settings", d.c) { SettingsScreen({}, {}) }
    }

    @Test fun guide() {
        val d = demo()
        shot("42-reminder-guide", d.c) { ReminderGuideScreen(onBack = {}) }
    }
}
