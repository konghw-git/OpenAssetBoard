package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import com.assetboard.data.DiarySession
import com.assetboard.data.SourceRef
import com.assetboard.data.ThemeMode
import com.assetboard.ui.theme.AssetBoardTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** The notes tab and its pages with realistic data, into build/screenshots/ for visual review. Not an assertion suite. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class NoteScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val today = LocalDate.now()

    private fun demo(empty: Boolean = false): AppContainer {
        val c = AppContainer(ApplicationProvider.getApplicationContext())
        c.settings.setBool(c.settings.networkEnabled, false)
        runBlocking(c.gate.bound()) {
            c.repository.seedDefaultGroups()
            val s = DiarySession(c.diary, today, c.appScope + c.gate.bound())
            s.open(null)
            s.onInput("早上六点半起床，跑了五公里。\n\n复盘这周的定投：跌的时候最想停，但纪律就是在这时候起作用的。#投资\n\n晚上和朋友吃了火锅，聊到他换工作的事。")
            s.saveNow()
            s.close()
            if (empty) return@runBlocking
            val n = c.notes
            val invest = n.createTopic("投资")
            val fit = n.createTopic("健身")
            val read = n.createTopic("读书")
            val goal = n.create("2027 年度目标\n存下 30% 收入；跑完一场半马；读 24 本书。", null)
            n.setPinned(goal, true)
            n.create("定投纪律\n跌的时候最想停，但纪律就是在这时候起作用的。#投资 #长期主义", SourceRef.day(today), invest)
            n.create("早睡的真正障碍\n不是不困，是舍不得结束这一天。把手机放在客厅充电。#早睡", SourceRef.day(today.minusDays(4)), fit)
            n.create("《置身事内》读后\n地方政府的激励结构解释了很多看起来不合理的现象。", null, read)
            n.create("火锅局的启发\n换工作之前，先想清楚自己要的是什么。", SourceRef.day(today.minusDays(9)))
            n.saveDraft(null, "一段没能存进正文的文字", "保存失败：磁盘已满")
        }
        return c
    }

    private fun show(c: AppContainer, dark: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT, upIsRed = true) { AppNav(open = null, onOpenHandled = {}) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("资产")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitFor(text: String) = compose.waitUntil(8_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    private fun openTab() {
        compose.onNodeWithText("笔记").performClick()
        waitFor("写笔记")
    }

    @Test fun notesList() {
        show(demo())
        openTab()
        waitFor("定投纪律")
        capture("50-notes")
    }

    @Test fun notesListDark() {
        show(demo(), dark = true)
        openTab()
        waitFor("定投纪律")
        capture("51-notes-dark")
    }

    @Test fun notesEmpty() {
        show(demo(empty = true))
        openTab()
        waitFor("还没有笔记")
        capture("52-notes-empty")
    }

    @Test fun noteRead() {
        show(demo())
        openTab()
        waitFor("定投纪律")
        compose.onNodeWithText("定投纪律").performClick()
        waitFor("摘自")
        capture("53-note")
    }

    @Test fun noteNew() {
        show(demo())
        openTab()
        compose.onNodeWithText("写笔记").performClick()
        waitFor("标题")
        capture("54-note-new")
    }

    @Test fun noteSearch() {
        show(demo())
        openTab()
        compose.onNodeWithContentDescription("搜索笔记").performClick()
        waitFor("搜索笔记")
        compose.onNode(hasSetTextAction()).performTextInput("纪律")
        compose.waitUntil(8_000) { compose.onAllNodes(hasText("篇笔记", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        capture("55-note-search")
    }

    @Test fun noteTopics() {
        show(demo())
        openTab()
        // Every topic in a sheet, from the button that stays at the end of the row.
        compose.onNodeWithContentDescription("全部主题").performClick()
        waitFor("按主题看")
        capture("56-note-topic-sheet")
        compose.onNodeWithText("管理").performClick()
        waitFor("笔记主题")
        capture("56b-note-topics")
    }

    @Test fun swipeActions() {
        show(demo())
        openTab()
        waitFor("定投纪律")
        // Halfway through a swipe: right shows pin and topic, left shows delete.
        compose.onNodeWithText("火锅局的启发").performTouchInput { down(center); moveBy(Offset(60f, 0f)); moveBy(Offset(200f, 0f)); moveBy(Offset(200f, 0f)) }
        capture("61-note-swipe-right")
        compose.onNodeWithText("火锅局的启发").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("早睡的真正障碍").performTouchInput { down(center); moveBy(Offset(-60f, 0f)); moveBy(Offset(-200f, 0f)) }
        capture("62-note-swipe-left")
    }

    @Test fun sortMenu() {
        show(demo())
        openTab()
        waitFor("定投纪律")
        compose.onNodeWithContentDescription("排序").performClick()
        waitFor("自定义顺序")
        capture("63-note-sort")
    }

    @Test fun drafts() {
        show(demo())
        openTab()
        compose.onNodeWithText("有 1 份草稿未处理").performClick()
        waitFor("未处理的草稿")
        capture("57-note-drafts")
    }

    @Test fun extract() {
        show(demo())
        compose.onNodeWithText("日记").performClick()
        compose.waitUntil(8_000) { compose.onAllNodes(hasContentDescription("提炼到笔记")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("提炼到笔记").performClick()
        waitFor("提炼到笔记")
        // The second match is the sheet's paragraph; the first is the diary's own text.
        compose.onAllNodes(hasText("复盘这周的定投", substring = true))[1].performClick()
        capture("58-extract")
        compose.onNodeWithText("追加到笔记…").performClick()
        waitFor("追加到哪篇笔记？")
        capture("59-extract-pick")
    }

    @Test fun diaryWithNotes() {
        show(demo())
        compose.onNodeWithText("日记").performClick()
        waitFor("从这天提炼的笔记")
        compose.onNodeWithText("从这天提炼的笔记").performClick()
        capture("60-diary-notes")
    }
}
