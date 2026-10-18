package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
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
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.plus
import org.junit.Rule
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
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val today = LocalDate.now()

    private class Demo(val c: AppContainer, val listId: String, val detailId: String)

    private fun demo(): Demo {
        val c = AppContainer(ApplicationProvider.getApplicationContext())
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

    @Test fun todoInbox() {
        val d = demo()
        shot("28-todo-inbox", d.c) { TodoHomeScreen({}, {}, initialView = "inbox") }
    }

    @Test fun todoDetail() {
        val d = demo()
        shot("24-todo-detail", d.c) { TodoDetailScreen(d.detailId, onBack = {}, onOpenTodo = {}) }
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

    @Test fun settingsFull() {
        val d = demo()
        shot("41-settings", d.c) { SettingsScreen({}, {}) }
    }

    @Test fun guide() {
        val d = demo()
        shot("42-reminder-guide", d.c) { ReminderGuideScreen(onBack = {}) }
    }
}
