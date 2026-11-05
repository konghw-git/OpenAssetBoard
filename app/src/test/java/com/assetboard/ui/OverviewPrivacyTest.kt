package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.assetboard.CloseTestContainers
import com.assetboard.PinnedClock
import com.assetboard.TestContainers
import com.assetboard.data.ThemeMode
import com.assetboard.data.StocktakeEntry
import com.assetboard.data.seedDemo
import com.assetboard.domain.AppClock
import com.assetboard.ui.components.LocalHideAmounts
import com.assetboard.ui.components.CalcKeys
import com.assetboard.ui.overview.OverviewScreen
import com.assetboard.ui.stocktake.StocktakeScreen
import com.assetboard.ui.trend.TrendScreen
import com.assetboard.ui.theme.AssetBoardTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class OverviewPrivacyTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(PinnedClock()).around(compose).around(CloseTestContainers())

    @Test fun hiddenOverviewDoesNotRevealRatios() {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        runBlocking(c.gate.bound()) {
            c.repository.seedDemo()
            c.goal.set(54_321_000L)
        }
        val hidden = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c, LocalHideAmounts provides hidden.value) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { OverviewScreen({}, {}, {}, {}, {}, {}) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("目标 ****", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("allocation and goal percentages must be hidden", compose.onAllNodesWithText("%", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        compose.runOnIdle { hidden.value = false }
        assertTrue("percentages return when amounts are shown", compose.onAllNodesWithText("%", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun editingABalanceWhileHiddenUsesPasswordSemantics() {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        runBlocking(c.gate.bound()) { c.repository.seedDemo() }
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c, LocalHideAmounts provides true) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { OverviewScreen({}, {}, {}, {}, {}, {}) }
            }
        }
        compose.waitUntil(10_000) { c.repository.ledger.value != null }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(2)
        compose.onNodeWithText("全部展开").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("农行①").performTouchInput {
            down(center); repeat(6) { moveBy(Offset(30f, 0f)) }; repeat(4) { moveBy(Offset(220f, 0f)) }; up()
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNode(hasSetTextAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).assertExists()
    }

    @Test fun stocktakeAmountsAreHiddenFromViewAndAccessibility() {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        runBlocking(c.gate.bound()) { c.repository.seedDemo() }
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c, LocalHideAmounts provides true) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { StocktakeScreen(snapshotId = null, onBack = {}) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        val fields = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
        assertTrue(fields.all { it.config.contains(SemanticsProperties.Password) })
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("123.45")
        compose.onAllNodes(hasSetTextAction())[0].assertExists()
    }

    @Test fun hiddenCalculatorDoesNotRevealItsResult() {
        compose.setContent {
            CompositionLocalProvider(LocalHideAmounts provides true) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { CalcKeys("123+456", {}) }
            }
        }
        compose.onNodeWithText("= ****").assertExists()
        compose.onNodeWithText("579.00", substring = true).assertDoesNotExist()
    }

    @Test fun hiddenTrendDoesNotRevealChangeRatio() {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        runBlocking(c.gate.bound()) {
            val day = 24L * 60 * 60 * 1000
            val now = AppClock.millis()
            c.repository.seedDemo(now - 30 * day)
            for (offset in listOf(20L, 10L)) {
                val ledger = c.repository.loadOnce()
                val at = now - offset * day
                val entries = ledger.stocktakeAccounts(at).map { a ->
                    StocktakeEntry(a.id, ledger.toDisplay(a.id, ledger.currentBalance(a.id)) + 10_000L)
                }
                c.repository.createSnapshot(at, entries)
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c, LocalHideAmounts provides true) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { TrendScreen({}, {}, {}) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("较 ", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("change ratio must be hidden", compose.onAllNodesWithText("%", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }
}
