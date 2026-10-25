package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import com.assetboard.CloseTestContainers
import com.assetboard.TestContainers
import com.assetboard.data.FoodDraft
import com.assetboard.data.FoodUnit
import com.assetboard.data.MeasurementDraft
import com.assetboard.data.Sex
import com.assetboard.data.ThemeMode
import com.assetboard.data.TodoDraft
import com.assetboard.domain.Habit
import com.assetboard.domain.health.Energy
import com.assetboard.ui.health.FoodScreen
import com.assetboard.ui.health.HealthScreen
import com.assetboard.ui.notes.NotesScreen
import com.assetboard.ui.theme.AssetBoardTheme
import com.assetboard.ui.todo.TodoHomeScreen
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.filter
import kotlinx.coroutines.flow.first
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

/**
 * The third round's screens and gestures: several todos dragged as one, merged into another's
 * checklist, a checklist let go, a gathering undone from its editor, a habit's rubric held up,
 * the food page folded and its recent foods in a grid, the compact health cards, and notes
 * selected and dragged together. Into screenshots/ with the rest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class RoundThreeShotTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(composeRule).around(CloseTestContainers())
    private val compose get() = composeRule

    private val today = LocalDate.now()

    private fun container(): AppContainer = TestContainers.open(ApplicationProvider.getApplicationContext())

    private fun set(c: AppContainer, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
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

    private fun waitFor(text: String) = compose.waitUntil(8_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    /** Four todos in the inbox, in this order; the third with a checklist of two. */
    private fun inbox(c: AppContainer): List<String> = runBlocking(c.gate.bound()) {
        val ids = listOf("回邮件", "订机票", "采购清单", "整理桌面").map { c.todos.create(TodoDraft(it)) }
        c.todos.addChecklistItem(ids[2], "牛奶")
        c.todos.addChecklistItem(ids[2], "面包")
        ids
    }

    private fun titlesInOrder(c: AppContainer): List<String> = runBlocking {
        c.todos.loadOnce().todos.filter { it.deletedAt == null && it.completedAt == null }.sortedBy { it.sortOrder }.map { it.title }
    }

    /** A long press held still on [text]'s row: on a row that can move, that selects it. */
    private fun hold(text: String) {
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(text, useUnmergedTree = true).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText(text, useUnmergedTree = true).performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(500)
    }

    private fun more(item: String) {
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText(item).performClick()
        compose.mainClock.advanceTimeBy(500)
    }

    @Test fun selectedTodosAreDraggedAsOne() {
        val c = container()
        inbox(c)
        set(c) { TodoHomeScreen({}, {}, initialView = "inbox") }
        waitFor("回邮件")
        hold("回邮件")
        compose.onNodeWithText("订机票").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("已选 2 条").assertExists()
        // Picked up by the first of the two, dragged down past the other two rows.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("回邮件").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        repeat(18) {
            compose.onNodeWithText("回邮件").performTouchInput { moveBy(Offset(0f, 30f)) }
            compose.mainClock.advanceTimeByFrame()
        }
        compose.mainClock.advanceTimeBy(300)
        compose.onRoot().captureRoboImage("screenshots/70-todo-drag-together.png", roborazziOptions = SHOT_OPTIONS)
        compose.onNodeWithText("2 条").assertExists()
        compose.onNodeWithText("回邮件").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { titlesInOrder(c) == listOf("采购清单", "整理桌面", "回邮件", "订机票") }
    }

    /** Held and slid right, a todo goes into the one above: the one above lights up, the held one says so. */
    @Test fun aTodoSlidRightGoesIntoTheOneAbove() {
        val c = container()
        inbox(c)
        set(c) { TodoHomeScreen({}, {}, initialView = "inbox") }
        waitFor("整理桌面")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("整理桌面").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        repeat(8) {
            compose.onNodeWithText("整理桌面").performTouchInput { moveBy(Offset(30f, 0f)) }
            compose.mainClock.advanceTimeByFrame()
        }
        compose.mainClock.advanceTimeBy(300)
        compose.onRoot().captureRoboImage("screenshots/78-todo-nest.png", roborazziOptions = SHOT_OPTIONS)
        compose.onNodeWithText("并入「采购清单」").assertExists()
        compose.onNodeWithText("整理桌面").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { titlesInOrder(c) == listOf("回邮件", "订机票", "采购清单") }
        shot("78b-todo-nested")
    }

    @Test fun selectedTodosJoinAnotherOnesChecklist() {
        val c = container()
        val ids = inbox(c)
        set(c) { TodoHomeScreen({}, {}, initialView = "inbox") }
        waitFor("回邮件")
        hold("回邮件")
        compose.onNodeWithText("订机票").performClick()
        compose.mainClock.advanceTimeBy(500)
        more("并入已有待办的检查项")
        waitFor("并入哪条待办的检查项")
        shot("71-todo-merge-pick")
        compose.onNodeWithText("已有 2 个检查项").performClick()
        compose.waitUntil(5_000) {
            runBlocking { c.todos.loadOnce().checklistByTodo[ids[2]].orEmpty().sortedBy { it.sortOrder }.map { it.text } } == listOf("牛奶", "面包", "回邮件", "订机票")
        }
        compose.mainClock.advanceTimeBy(500)
        shot("71b-todo-merged")
    }

    @Test fun aChecklistIsLetGoAsTodos() {
        val c = container()
        val ids = inbox(c)
        set(c) { TodoHomeScreen({}, {}, initialView = "inbox") }
        waitFor("采购清单")
        hold("采购清单")
        more("解散检查项")
        compose.waitUntil(5_000) { titlesInOrder(c) == listOf("回邮件", "订机票", "牛奶", "面包", "整理桌面") }
        assert(runBlocking { c.todos.todo(ids[2])!!.deletedAt } != null) { "the todo itself goes" }
        shot("72-todo-dissolved")
    }

    @Test fun aGatheringIsUndoneFromItsEditor() {
        val c = container()
        inbox(c)
        set(c) { TodoHomeScreen({}, {}, initialView = "inbox") }
        waitFor("回邮件")
        hold("回邮件")
        compose.onNodeWithText("整理桌面").performClick()
        compose.mainClock.advanceTimeBy(500)
        more("作为检查项创建新待办")
        waitFor("给新待办起个标题")
        // Past the snackbar's undo: the editor still offers its own.
        compose.mainClock.advanceTimeBy(4_000)
        compose.waitUntil(8_000) { compose.onAllNodesWithText("撤销").fetchSemanticsNodes().size == 1 }
        shot("73-todo-gathered-undo")
        compose.onNodeWithText("撤销").performClick()
        compose.waitUntil(5_000) { titlesInOrder(c) == listOf("回邮件", "订机票", "采购清单", "整理桌面") }
        compose.onNodeWithText("给新待办起个标题").assertDoesNotExist()
    }

    @Test fun holdingAHabitShowsItsRubric() {
        val c = container()
        runBlocking(c.gate.bound()) {
            c.habits.update(Habit.WORKOUT.key, Habit.WORKOUT.label, Habit.WORKOUT.icon, Habit.WORKOUT.color, "不少于 30 分钟\n心率进入有氧区间\n结束后拉伸")
            c.habits.toggle(Habit.SUPPLEMENTS)
        }
        set(c) { TodoHomeScreen({}, {}, initialView = "today") }
        waitFor("健身")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("健身").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_200)
        compose.waitForIdle()
        waitFor("心率进入有氧区间")
        // The card floats in a popup window of its own: that window alone.
        compose.mainClock.advanceTimeBy(600)
        compose.onAllNodes(isRoot()).filter(hasAnyDescendant(hasText("心率进入有氧区间"))).onFirst()
            .captureRoboImage("screenshots/74-habit-rubric.png", roborazziOptions = SHOT_OPTIONS)
        // The cell, not the floating card's title.
        compose.onAllNodesWithText("健身")[0].performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(500)
        // Gone with the finger, and the hold checked nothing.
        compose.onAllNodesWithText("心率进入有氧区间").fetchSemanticsNodes().let { assert(it.isEmpty()) { "the rubric goes when the finger lifts" } }
        assert(runBlocking { c.habits.observe(today).first() } == setOf(Habit.SUPPLEMENTS.key)) { "a hold is not a tap" }
    }

    @Test fun foodFoldsAndItsRecentFoodsLineUp() {
        val c = container()
        runBlocking(c.gate.bound()) {
            val names = listOf("燕麦奶", "水煮蛋", "鸡胸肉沙拉", "全麦面包", "希腊酸奶配蓝莓", "米饭", "番茄炒蛋", "拿铁")
            names.forEachIndexed { i, n ->
                c.food.save(FoodDraft(null, today.minusDays(1L + i), n, null, null, null, 150_000L + 40_000L * i, false, null, null, null, null))
            }
            c.food.save(FoodDraft(null, today, "燕麦奶", 100, 250, FoodUnit.ML, 40_870, true, 600, 1_600, 6_000, 40))
            c.food.save(FoodDraft(null, today, "水煮蛋", Energy.SERVING_PER, 4, FoodUnit.SERVING, 70_000, false, null, null, null, null))
            c.food.save(FoodDraft(null, today, "午饭", null, null, null, 650_000, false, null, null, null, null))
        }
        set(c) { FoodScreen({}) }
        waitFor("吃了什么")
        shot("75-food-folded")
        compose.onNodeWithText("吃了什么").performClick()
        // Every row in the same shape, however its energy went in.
        waitFor("1 份 · 每份 650 kcal")
        waitFor("2 份 · 每份 70 kcal")
        shot("75b-food-open")
        compose.onNodeWithText("记一笔").performClick()
        waitFor("最近吃过 · 点一下填好")
        compose.mainClock.advanceTimeBy(800)
        compose.onAllNodes(isRoot()).onLast().captureRoboImage("screenshots/75c-food-recent-grid.png", roborazziOptions = SHOT_OPTIONS)
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("蛋")
        waitFor("最近吃过里有 2 项「蛋」")
        compose.mainClock.advanceTimeBy(800)
        compose.onAllNodes(isRoot()).onLast().captureRoboImage("screenshots/75d-food-recent-filtered.png", roborazziOptions = SHOT_OPTIONS)
    }

    @Test fun healthCardsAreCompactAndRecordsFold() {
        val c = container()
        runBlocking(c.gate.bound()) {
            c.health.saveProfile(Sex.MALE, LocalDate.of(2001, 8, 2), 1820, null)
            listOf(20L to 93_400L, 16L to 93_000L, 9L to 92_600L, 5L to 92_300L, 2L to 92_100L, 0L to 91_800L).forEach { (ago, g) ->
                c.health.save(MeasurementDraft(null, today.minusDays(ago), g, 2_400 + (g / 1_000).toInt(), null))
            }
        }
        set(c) { HealthScreen({}) }
        waitFor("最新体重")
        compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToIndex(3)
        compose.mainClock.advanceTimeBy(1_500)
        shot("76-health-compact")
        // Folded to its head until tapped.
        compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToIndex(6)
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("6 条").assertExists()
        compose.onNodeWithText("全部记录").performClick()
        waitFor("6 条 · 长按删除")
    }

    @Test fun notesAreSelectedDraggedAndDeletedTogether() {
        val c = container()
        runBlocking(c.gate.bound()) {
            // Fixed times, so the cards read the same on every run; recent, so none comes back as 今日回顾.
            val at = today.minusDays(1).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            listOf("周计划", "读书摘录", "旅行清单", "灵感").forEachIndexed { i, t ->
                c.db.noteDao().insert(com.assetboard.data.Note("n$i", null, "$t\n正文", bodyRev = 1, position = i.toLong(), createdAt = at, updatedAt = at))
            }
        }
        set(c) { NotesScreen({}, {}, {}, {}, {}) }
        waitFor("周计划")
        hold("周计划")
        compose.onNodeWithText("旅行清单").performClick()
        compose.mainClock.advanceTimeBy(500)
        waitFor("已选 2 篇")
        shot("77-notes-selected")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("周计划").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        repeat(16) {
            compose.onNodeWithText("周计划").performTouchInput { moveBy(Offset(0f, 30f)) }
            compose.mainClock.advanceTimeByFrame()
        }
        compose.mainClock.advanceTimeBy(300)
        // No shot here: the lifted card is glass over the drifting backdrop, different every frame.
        compose.onNodeWithText("2 篇").assertExists()
        compose.onNodeWithText("周计划").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) {
            runBlocking { c.db.noteDao().all().filter { it.deletedAt == null }.sortedBy { it.position }.map { it.title } }.let { it.indexOf("旅行清单") == it.indexOf("周计划") + 1 }
        }
        compose.onNodeWithContentDescription("删除选中的笔记").performClick()
        compose.waitUntil(5_000) { runBlocking { c.db.noteDao().all().count { it.deletedAt != null } } == 2 }
    }
}
