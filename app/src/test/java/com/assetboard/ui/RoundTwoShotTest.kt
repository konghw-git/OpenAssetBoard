package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import com.assetboard.CloseTestContainers
import com.assetboard.TestContainers
import com.assetboard.data.DiarySession
import com.assetboard.data.FoodDraft
import com.assetboard.data.FoodUnit
import com.assetboard.data.MeasurementDraft
import com.assetboard.data.Sex
import com.assetboard.data.ThemeMode
import com.assetboard.data.TodoDraft
import com.assetboard.data.seedDemo
import com.assetboard.domain.Habit
import com.assetboard.ui.components.LocalHideAmounts
import com.assetboard.ui.diary.DiaryCalendarScreen
import com.assetboard.ui.diary.DiaryScreen
import com.assetboard.ui.health.FoodScreen
import com.assetboard.ui.overview.OverviewScreen
import com.assetboard.ui.settings.SettingsScreen
import com.assetboard.ui.stocktake.StocktakeScreen
import com.assetboard.ui.theme.AssetBoardTheme
import com.assetboard.ui.todo.RescheduleDialog
import com.assetboard.ui.todo.TodoHomeScreen
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalTime

/**
 * The second round's screens: the diary's mood and 排版, the todo edited in place, 安排时间 and
 * the words read from quick add, a habit's rest day, hidden amounts, eating again, the backup
 * warning and the + − × ÷ keys. Into screenshots/ with the rest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class RoundTwoShotTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(composeRule).around(CloseTestContainers())
    private val compose get() = composeRule

    private val today = LocalDate.now()

    private fun container(): AppContainer = TestContainers.open(ApplicationProvider.getApplicationContext())

    private fun set(c: AppContainer, hide: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c, LocalHideAmounts provides hide) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png", roborazziOptions = SHOT_OPTIONS)
    }

    private fun diaryDay(c: AppContainer, day: LocalDate, text: String) = runBlocking(c.gate.bound()) {
        val s = DiarySession(c.diary, day, c.appScope + c.gate.bound())
        s.open(null)
        s.onInput(text)
        s.saveNow()
        s.close()
    }

    private fun todos(c: AppContainer): String = runBlocking(c.gate.bound()) {
        val t = c.todos
        val ppt = t.create(TodoDraft("准备组会 PPT", planDate = today, remindTime = LocalTime.of(14, 0)))
        t.addChecklistItem(ppt, "实验结果表")
        t.toggleChecklist(t.addChecklistItem(ppt, "消融实验图"))
        t.addChecklistItem(ppt, "下一步计划")
        t.create(TodoDraft("交水电费", planDate = today, important = true))
        t.create(TodoDraft("给导师发周报", planDate = today))
        c.habits.toggle(Habit.SUPPLEMENTS)
        c.habits.toggle(Habit.WORKOUT)
        c.habits.toggleSkip(Habit.DIET)
        ppt
    }

    @Test fun diaryMoodAndTidy() {
        val c = container()
        diaryDay(c, today, "早上六点半起床,跑了五公里.\n\n\n  上午把实验数据又过了一遍(第三组有问题),下午重新跑了一遍...\n晚上和朋友吃了火锅!")
        runBlocking(c.gate.bound()) { c.diary.setMood(today, 4) }
        set(c) { DiaryScreen(null, {}, {}, {}, {}) }
        shot("50-diary-mood")
        compose.onNodeWithContentDescription("一键排版").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("撤销")).fetchSemanticsNodes().isNotEmpty() }
        shot("51-diary-tidied")
    }

    @Test fun diaryCalendarMoods() {
        val c = container()
        runBlocking(c.gate.bound()) {
            listOf(0L to 5, 1L to 4, 2L to 3, 4L to 2, 6L to 1, 7L to 4).forEach { (back, m) ->
                c.diary.setMood(today.minusDays(back), m)
            }
        }
        diaryDay(c, today.minusDays(1), "写了一点")
        set(c) { DiaryCalendarScreen(null, onBack = {}, onPick = {}) }
        shot("52-diary-calendar-mood")
    }

    @Test fun todoEditedInPlace() {
        val c = container()
        todos(c)
        set(c) { TodoHomeScreen({}, {}, initialView = "today") }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("准备组会 PPT")).fetchSemanticsNodes().isNotEmpty() }
        // On the title itself: the card's middle is its checklist, which ticks.
        compose.onNodeWithText("准备组会 PPT", useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("完成").assertExists()
        shot("53-todo-inline-edit")
        // Enter at the end of the last item: a new, empty one under it.
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[3].performTextReplacement("​下一步计划\n")
        compose.mainClock.advanceTimeBy(500)
        fields[4].performTextReplacement("​问导师的问题")
        shot("53b-todo-inline-added")
        compose.onNodeWithText("完成").performClick()
        compose.waitUntil(5_000) {
            runBlocking { c.todos.loadOnce().checklist.any { it.text == "问导师的问题" } }
        }
    }

    @Test fun todoSwipeOffersEdit() {
        val c = container()
        todos(c)
        set(c) { TodoHomeScreen({}, {}, initialView = "today") }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("交水电费")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("交水电费").performTouchInput { down(center); repeat(8) { moveBy(Offset(10f, 0f)) }; moveBy(Offset(200f, 0f)); moveBy(Offset(200f, 0f)) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("编辑").assertExists()
        compose.onRoot().captureRoboImage("screenshots/54a-todo-swipe-edit.png", roborazziOptions = SHOT_OPTIONS)
        compose.onNodeWithText("交水电费").performTouchInput { up() }
    }

    @Test fun longPressSelectsOnlyAndGathersIntoChecklist() {
        val c = container()
        todos(c)
        set(c) { TodoHomeScreen({}, {}, initialView = "today") }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("交水电费")).fetchSemanticsNodes().isNotEmpty() }
        // Frame by frame: an edit started by the lift would show for a frame before the selection ends it.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("交水电费").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("交水电费").performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("完成").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(500)
        // Selected, not edited as well.
        compose.onNodeWithText("已选 1 条").assertExists()
        compose.onNodeWithText("完成").assertDoesNotExist()
        compose.onNodeWithText("给导师发周报").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("已选 2 条").assertExists()
        shot("61-todo-selected")
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("作为检查项创建新待办").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("给新待办起个标题")).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(500)
        shot("62-todo-gathered")
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("这周杂事")
        compose.onNodeWithText("完成").performClick()
        compose.waitUntil(5_000) {
            runBlocking { c.todos.loadOnce().todos.any { it.title == "这周杂事" && it.deletedAt == null } }
        }
        val d = runBlocking { c.todos.loadOnce() }
        val made = d.todos.first { it.title == "这周杂事" }
        assert(d.checklist.filter { it.todoId == made.id }.sortedBy { it.sortOrder }.map { it.text } == listOf("交水电费", "给导师发周报")) { "the two in screen order" }
    }

    @Test fun scheduleDialog() {
        val c = container()
        set(c) {
            RescheduleDialog(today, allowSomeday = true, allowClear = true, onPick = { _, _, _ -> }, onDismiss = {}, current = today.plusDays(1), currentTime = LocalTime.of(15, 30))
        }
        shot("54-todo-schedule-dialog")
    }

    @Test fun quickAddReadsTheDay() {
        val c = container()
        set(c) { TodoHomeScreen({}, {}, request = OpenRequest.QuickAdd) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("准备做什么？")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("明天下午3点和导师开会")
        shot("55-todo-quickadd-parse")
    }

    @Test fun habitRestDay() {
        val c = container()
        todos(c)
        set(c) { TodoHomeScreen({}, {}, initialView = "today") }
        shot("56-todo-habit-rest")
    }

    @Test fun overviewHidden() {
        val c = container()
        runBlocking(c.gate.bound()) { c.repository.seedDemo() }
        set(c, hide = true) { OverviewScreen({}, {}, {}, {}, {}, {}) }
        compose.waitUntil(5_000) { c.repository.ledger.value != null }
        shot("57-overview-hidden")
    }

    @Test fun foodEatAgain() {
        val c = container()
        runBlocking(c.gate.bound()) {
            c.food.save(FoodDraft(null, today, "燕麦奶", 100, 250, FoodUnit.ML, 40_870, true, 600, 1_600, 6_000, 40))
            c.food.save(FoodDraft(null, today, "鸡胸肉沙拉", null, null, null, 420_000, false, null, null, null, null))
        }
        set(c) { FoodScreen({}) }
        // Folded to a line of names until its head is tapped.
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("吃了什么")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("吃了什么").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("鸡胸肉沙拉")).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("鸡胸肉沙拉").performTouchInput { down(center); repeat(8) { moveBy(Offset(10f, 0f)) }; moveBy(Offset(120f, 0f)) }
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage("screenshots/58-food-again.png", roborazziOptions = SHOT_OPTIONS)
        compose.onNodeWithText("鸡胸肉沙拉").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("再来一份").performClick()
        // Today's portion takes one more in place, and its row says so.
        compose.waitUntil(5_000) { runBlocking { c.food.entries.first().singleOrNull { it.name == "鸡胸肉沙拉" }?.amount } == 4 }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("2 份 · 每份 420 kcal")).fetchSemanticsNodes().isNotEmpty() }
        assert(runBlocking { c.food.entries.first().size } == 2) { "no second row" }
    }

    @Test fun foodAgainstRestingRate() {
        val c = container()
        runBlocking(c.gate.bound()) {
            c.health.saveProfile(Sex.MALE, LocalDate.of(2001, 8, 2), 1820, null)
            c.health.save(MeasurementDraft(null, today.minusDays(3), 92_100, null, null))
            c.food.save(FoodDraft(null, today, "燕麦奶", 100, 250, FoodUnit.ML, 40_870, true, 600, 1_600, 6_000, 40))
            c.food.save(FoodDraft(null, today, "鸡胸肉沙拉", null, null, null, 420_000, false, null, null, null, null))
            c.food.save(FoodDraft(null, today.minusDays(1), "火锅", null, null, null, 2_300_000, false, null, null, null, null))
        }
        set(c) { FoodScreen({}) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("吃到基础代谢的")).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(2_000)
        shot("63-food-resting")
    }

    @Test fun settingsBackupProblem() {
        val c = container()
        c.settings.setBackupTreeUri("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FAssetBoard")
        c.settings.setBackupError("无法在备份目录创建文件", System.currentTimeMillis() - 3_600_000)
        set(c) { SettingsScreen({}, {}) }
        shot("59-settings-backup-problem")
    }

    @Test fun stocktakeCalcKeys() {
        val c = container()
        runBlocking(c.gate.bound()) { c.repository.seedDemo() }
        set(c) { StocktakeScreen(snapshotId = null, onBack = {}) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        val field = compose.onAllNodes(hasSetTextAction())[0]
        field.performClick()
        field.performTextReplacement("12000+350")
        shot("60-stocktake-calc")
        compose.onAllNodesWithText("= 12,350.00").fetchSemanticsNodes().let { assert(it.isNotEmpty()) { "the sum should show" } }
    }
}
