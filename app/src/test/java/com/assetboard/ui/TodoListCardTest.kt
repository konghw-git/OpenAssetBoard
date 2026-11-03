package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import com.assetboard.CloseTestContainers
import com.assetboard.TestContainers
import com.assetboard.data.ThemeMode
import com.assetboard.data.TodoDraft
import com.assetboard.ui.theme.AssetBoardTheme
import com.assetboard.ui.todo.TodoHomeScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * The lists as a deck on the phone as unfolded: one compact pill opens a carousel of list cards,
 * with the neighbours leaning in beside the front one; a card carries the list's name over its
 * deadline, and picking the front card opens that list. Past six lists (and behind the header
 * toggle) the cards become a page, where one tap picks a list. The lists' order lives in the
 * repository, archived ones last.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class TodoListCardTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(composeRule).around(CloseTestContainers())
    private val compose get() = composeRule

    private fun show(setup: suspend (AppContainer) -> Unit = {}): AppContainer {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        runBlocking(c.gate.bound()) {
            c.todos.createList("搬家")
            val paper = c.todos.createList("论文")
            c.todos.updateList(paper, "论文", "", LocalDate.now().plusDays(2))
            c.todos.createList("旅行")
            setup(c)
        }
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { TodoHomeScreen({}, {}, initialView = "inbox") }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("清单")).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(1_000)
        return c
    }

    private fun lists(c: AppContainer) = runBlocking { c.db.todoDao().liveLists() }
    private fun shown(c: AppContainer) = lists(c).filter { it.archivedAt == null }.sortedBy { it.sortOrder }.map { it.name }

    private fun openDeck() {
        compose.onNodeWithText("清单").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("左右滑动浏览，点中间那张打开")).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(800)
    }

    private fun swipeToNextCard() {
        compose.onNodeWithText("搬家").performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeBy(900)
    }

    @Test fun theDeckShowsTheNameWithTheDeadlineUnderIt() {
        show()
        openDeck()
        swipeToNextCard()
        val name = compose.onNodeWithText("论文", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val tag = compose.onNodeWithText("截止还剩 2 天", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assert(tag.top >= name.bottom - 1f) { "the tag should be under the name: $name, $tag" }
    }

    @Test fun pickingACardOpensThatList() {
        show()
        openDeck()
        swipeToNextCard()
        compose.onNodeWithText("论文").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("左右滑动浏览，点中间那张打开")).fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("清单菜单")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("清单菜单").assertExists()
    }

    @Test fun aNewListMadeFromTheDeckOpens() {
        val c = show()
        openDeck()
        compose.onNodeWithText("新建清单").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("假期")
        compose.onNodeWithText("创建").performClick()
        compose.waitUntil(5_000) { runBlocking { lists(c) }.any { it.name == "假期" } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("清单菜单")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun anEmptyDeckMakesTheFirstList() {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { TodoHomeScreen({}, {}, initialView = "inbox") }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("清单")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("清单").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("还没有清单")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("新建清单").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("第一个清单")
        compose.onNodeWithText("创建").performClick()
        compose.waitUntil(5_000) { runBlocking { lists(c) }.any { it.name == "第一个清单" } }
    }

    /** The neighbours lean in beside the front card, so the next choice is already on screen. */
    @Test fun theNeighboursShowBesideTheFrontCard() {
        show()
        openDeck()
        compose.onNodeWithText("搬家").assertIsDisplayed()
        compose.onNodeWithText("论文").assertIsDisplayed()
        swipeToNextCard()
        compose.onNodeWithText("论文").assertIsDisplayed()
        compose.onNodeWithText("旅行").assertIsDisplayed()
    }

    /** A tap on a neighbour only brings it forward; the card in front is the one that opens. */
    @Test fun tappingANeighbourBringsItForwardAndOnlyTheFrontOpens() {
        show()
        openDeck()
        compose.onNodeWithText("打开「搬家」").assertExists()
        compose.onNodeWithText("论文").performClick()
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithText("打开「论文」").assertExists()
        compose.onNodeWithText("打开「搬家」").assertDoesNotExist()
        // Still the deck, and the list did not open.
        assert(compose.onAllNodes(hasText("左右滑动浏览，点中间那张打开")).fetchSemanticsNodes().isNotEmpty()) {
            "the deck should still be open"
        }
        compose.onNodeWithText("论文").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("清单菜单")).fetchSemanticsNodes().isNotEmpty() }
    }

    /** The card in front says what is open in the list, without opening it. */
    @Test fun theSpotlightShowsWhatIsOpenInTheList() {
        show { c ->
            val moving = c.todos.loadOnce().lists.first { it.name == "搬家" }
            c.todos.create(TodoDraft("打包书架", listId = moving.id))
            c.todos.create(TodoDraft("预约搬家车", listId = moving.id))
            c.todos.create(TodoDraft("改地址", listId = moving.id))
        }
        openDeck()
        compose.onNodeWithText("打包书架").assertIsDisplayed()
        compose.onNodeWithText("预约搬家车").assertIsDisplayed()
        compose.onNodeWithText("改地址").assertIsDisplayed()
    }

    /** Past a handful, the deck opens as a page of cards and one tap picks the list. */
    @Test fun manyListsOpenAsAPageAndPickInOneTap() {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        runBlocking(c.gate.bound()) { (1..8).forEach { c.todos.createList("项目$it") } }
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { TodoHomeScreen({}, {}, initialView = "inbox") }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("清单")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("清单").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("点一张卡直接打开")).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithContentDescription("环绕展示").assertExists()
        compose.onNodeWithText("项目8").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("清单菜单")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("清单菜单").assertExists()
    }

    /** A short deck can be laid out as a page too; the switch is in the header. */
    @Test fun theGridSwitchPicksInOneTap() {
        show()
        openDeck()
        compose.onNodeWithContentDescription("网格展示").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("点一张卡直接打开")).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("旅行").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("清单菜单")).fetchSemanticsNodes().isNotEmpty() }
    }

    /** The order saved leaves the archived lists, not on screen, after the rest. */
    @Test fun theOrderSavedPutsTheArchivedLast() {
        val c = show()
        runBlocking(c.gate.bound()) {
            val all = lists(c).associateBy { it.name }
            c.todos.setArchived(all.getValue("搬家").id, true)
            c.todos.reorderLists(listOf(all.getValue("旅行").id, all.getValue("论文").id))
        }
        assertEquals(listOf("旅行", "论文", "搬家"), lists(c).sortedBy { it.sortOrder }.map { it.name })
    }

    /** The deck opens the way it was last left, even with few enough lists for the carousel. */
    @Test fun theDeckOpensInLastChosenModeEvenWhenTheListIsShort() {
        show()
        openDeck()
        compose.onNodeWithContentDescription("网格展示").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("点一张卡直接打开")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("点一张卡直接打开")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("清单").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("点一张卡直接打开")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("左右滑动浏览，点中间那张打开").assertDoesNotExist()
    }
}
