package com.assetboard.ui

import com.assetboard.domain.AppClock
import com.assetboard.PinnedClock
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
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import com.assetboard.CloseTestContainers
import com.assetboard.TestContainers
import com.assetboard.data.DiarySession
import com.assetboard.data.SourceRef
import com.assetboard.data.ThemeMode
import com.assetboard.ui.theme.AssetBoardTheme
import com.assetboard.ui.components.GlassMode
import com.assetboard.ui.components.LocalGlassMode
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** The notes tab and its pages with realistic data, into screenshots/ (the baselines the tests are checked against). Not an assertion suite. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class NoteScreenshotTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(PinnedClock()).around(composeRule).around(CloseTestContainers())
    private val compose get() = composeRule

    private val today: LocalDate get() = AppClock.today()

    private fun demo(empty: Boolean = false, review: Boolean = false): AppContainer {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
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
            if (!review) return@runBlocking
            // Written six weeks ago and not touched since: today's review.
            val longAgo = AppClock.millis() - 42L * 24 * 60 * 60 * 1000
            c.db.noteDao().insert(
                com.assetboard.data.Note(
                    "old", read, "复利的耐心\n真正难的不是算清楚，而是在什么都没发生的那些年里不下车。#长期主义",
                    bodyRev = 1, position = 99, createdAt = longAgo, updatedAt = longAgo,
                ),
            )
        }
        return c
    }

    private fun show(c: AppContainer, dark: Boolean = false, deep: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c, LocalGlassMode provides if (deep) GlassMode.DEEP else GlassMode.CLEAR) {
                AssetBoardTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT, upIsRed = true) { AppNav(open = null, onOpenHandled = {}) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("资产")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitFor(text: String, substring: Boolean = false) =
        compose.waitUntil(8_000) { compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png", roborazziOptions = SHOT_OPTIONS)
    }

    private fun openTab() {
        compose.onNodeWithText("笔记").performClick()
        compose.waitUntil(8_000) { compose.onAllNodes(hasContentDescription("搜索笔记")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun notesList() {
        show(demo())
        openTab()
        waitFor("火锅局的启发")
        capture("50-notes")
    }

    @Test fun reviewCard() {
        show(demo(review = true))
        openTab()
        waitFor("今日回顾")
        capture("50b-notes-review")
    }

    @Test fun deepGlass() {
        show(demo(), deep = true)
        openTab()
        waitFor("火锅局的启发")
        capture("51b-notes-deep")
    }

    @Test fun deepGlassDark() {
        show(demo(), dark = true, deep = true)
        openTab()
        waitFor("火锅局的启发")
        capture("51c-notes-deep-dark")
    }

    @Test fun todoGlass() {
        show(demo())
        compose.onNodeWithText("待办").performClick()
        waitFor("新待办")
        capture("51d-todo-glass")
    }

    @Test fun todoGlassDark() {
        show(demo(), dark = true)
        compose.onNodeWithText("待办").performClick()
        waitFor("新待办")
        capture("51e-todo-glass-dark")
    }

    @Test fun notesListDark() {
        show(demo(), dark = true)
        openTab()
        waitFor("火锅局的启发")
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
        waitFor("火锅局的启发")
        compose.onNodeWithText("火锅局的启发").performClick()
        waitFor("摘自")
        capture("53-note")
    }

    @Test fun richNoteReadsAsADocument() {
        val c = demo()
        runBlocking(c.gate.bound()) {
            c.notes.create(
                """
                # 本周复盘
                这周把定投纪律写下来。#投资
                ## 做成了
                - [x] 周一、周三各跑五公里
                - [ ] 整理读书笔记
                - [ ] 给爸妈打电话
                ## 想法
                > 真正难的不是算清楚，而是在什么都没发生的那些年里不下车。
                ---
                **下周三**前读完《置身事内》第三章。
                """.trimIndent(),
                null,
            )
        }
        show(c)
        openTab()
        waitFor("本周复盘")
        compose.onNodeWithText("本周复盘").performClick()
        waitFor("想法", substring = true)
        capture("53b-note-rich")
    }

    @Test fun richNoteInTheDark() {
        val c = demo()
        runBlocking(c.gate.bound()) {
            c.notes.create(
                "# 夜读\n- [x] 读完一章\n- [ ] 写下摘录\n---\n> 慢慢来，比较快。",
                null,
            )
        }
        show(c, dark = true)
        openTab()
        waitFor("夜读")
        compose.onNodeWithText("夜读").performClick()
        waitFor("慢慢来", substring = true)
        capture("53e-note-rich-dark")
    }

    @Test fun richNoteWhileEditing() {
        val c = demo()
        runBlocking(c.gate.bound()) {
            c.notes.create("# 一起读书\n- [ ] 读完第三章\n- [x] 做笔记\n---\n> 慢一点也没关系。", null)
        }
        show(c)
        openTab()
        waitFor("一起读书")
        compose.onNodeWithText("一起读书").performClick()
        waitFor("慢一点也没关系", substring = true)
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).performTextInputSelection(TextRange(0))
        compose.waitUntil(8_000) {
            compose.onAllNodes(hasContentDescription("待办清单")).fetchSemanticsNodes().isNotEmpty()
        }
        capture("53c-note-editing")
    }

    @Test fun outlineShowsTheHeadings() {
        val c = demo()
        runBlocking(c.gate.bound()) {
            c.notes.create(
                "# 年度复盘\n## 做成了\n- [x] 存下三个月开销\n## 没做成\n- [ ] 跑完半马",
                null,
            )
        }
        show(c)
        openTab()
        waitFor("年度复盘")
        compose.onNodeWithText("年度复盘").performClick()
        compose.waitUntil(8_000) { compose.onAllNodes(hasContentDescription("大纲")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("大纲").performClick()
        waitFor("3 个小标题", substring = true)
        capture("53d-note-outline")
    }

    @Test fun noteNew() {
        show(demo())
        openTab()
        waitFor("火锅局的启发")
        // Halfway down: the hint shows; past the threshold it changes, and letting go opens a new note.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("全部").performTouchInput { down(center); moveBy(Offset(0f, 40f)); moveBy(Offset(0f, 120f)) }
        repeat(10) { compose.mainClock.advanceTimeByFrame() }
        capture("54a-note-pull")
        compose.onNodeWithText("全部").performTouchInput { moveBy(Offset(0f, 200f)); moveBy(Offset(0f, 200f)) }
        repeat(20) { compose.mainClock.advanceTimeByFrame() }
        capture("54b-note-pull-armed")
        compose.onNodeWithText("全部").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
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
        // The topics as a deck, from the one pill above the list.
        compose.onNodeWithText("主题").performClick()
        waitFor("管理主题")
        compose.onNodeWithText("管理主题").performClick()
        waitFor("笔记主题")
        capture("56b-note-topics")
    }

    @Test fun swipeActions() {
        show(demo())
        openTab()
        waitFor("火锅局的启发")
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
        waitFor("火锅局的启发")
        compose.onNodeWithContentDescription("排序").performClick()
        waitFor("自定义顺序")
        capture("63-note-sort")
    }

    @Test fun drafts() {
        show(demo())
        openTab()
        // The banner can still be settling when the tab opens; the click needs it in place.
        waitFor("有 1 份草稿未处理")
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

    @Test fun weekReview() {
        val c = demo()
        runBlocking(c.gate.bound()) {
            c.habits.toggle(com.assetboard.domain.Habit.WORKOUT)
            c.habits.toggle(com.assetboard.domain.Habit.WATER)
        }
        show(c)
        compose.onNodeWithText("日记").performClick()
        // The week's tile on the day card.
        compose.waitUntil(8_000) { compose.onAllNodes(hasText("周复盘", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        capture("64-diary-week-row")
        compose.onAllNodes(hasText("周复盘", substring = true))[0].performClick()
        waitFor("这一周的日记")
        capture("65-week")
        compose.onNodeWithContentDescription("上一周").performClick()
        compose.waitUntil(8_000) { compose.onAllNodes(hasText("没写")).fetchSemanticsNodes().size >= 7 }
        capture("66-week-before")
    }

    @Test fun calendarWeeks() {
        val c = demo()
        runBlocking(c.gate.bound()) { c.weeks.saveBody(com.assetboard.data.Weeks.start(today), null, null, "这周做成了：笔记功能上线。") }
        show(c)
        compose.onNodeWithText("日记").performClick()
        compose.waitUntil(8_000) { compose.onAllNodes(hasContentDescription("日历")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("日历").performClick()
        waitFor("日记日历")
        capture("67-calendar-weeks")
    }

    @Test fun extractSelection() {
        show(demo())
        compose.onNodeWithText("日记").performClick()
        compose.waitUntil(8_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        val editor = compose.onNode(hasSetTextAction())
        editor.performClick()
        editor.performTextInputSelection(androidx.compose.ui.text.TextRange(0, 13))
        waitFor("提炼选中的 13 字")
        capture("58b-extract-selection-button")
        compose.onNodeWithText("提炼选中的 13 字").performClick()
        compose.waitUntil(8_000) { compose.onAllNodes(hasText("选中的文字", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        capture("58c-extract-selection")
    }

    @Test fun diaryWithNotes() {
        show(demo())
        compose.onNodeWithText("日记").performClick()
        // Unfolded inside the day card.
        compose.waitUntil(8_000) { compose.onAllNodes(hasText("提炼的笔记", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("提炼的笔记", substring = true))[0].performClick()
        capture("60-diary-notes")
    }
}
