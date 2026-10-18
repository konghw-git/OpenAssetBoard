package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import com.assetboard.data.FlowKind
import com.assetboard.data.MoneyFlow
import com.assetboard.data.StocktakeEntry
import com.assetboard.data.ThemeMode
import com.assetboard.data.newId
import com.assetboard.ui.account.AccountDetailScreen
import com.assetboard.ui.account.AccountEditScreen
import com.assetboard.ui.overview.OverviewScreen
import com.assetboard.ui.record.FlowEditScreen
import com.assetboard.ui.settings.SettingsScreen
import com.assetboard.ui.stocktake.StocktakeScreen
import com.assetboard.ui.theme.AssetBoardTheme
import com.assetboard.ui.trend.TrendScreen
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main screens with realistic data into build/screenshots/ for visual review.
 * Not an assertion suite: run with `./gradlew testDebugUnitTest --tests '*ScreenshotTest*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val day = 24L * 3600 * 1000

    private fun demoContainer(): AppContainer {
        val c = AppContainer(ApplicationProvider.getApplicationContext())
        val repo = c.repository
        runBlocking(c.gate.bound()) {
            repo.seedFromSuishouji()
            val now = System.currentTimeMillis()
            val start = now - 200 * day
            var l = repo.loadOnce()
            for (a in l.accounts) {
                val display = l.toDisplay(a.id, l.currentBalance(a.id))
                repo.updateBaseline(a.id, start, display - 3_000_00L.coerceAtMost(display), null)
            }
            l = repo.loadOnce()
            val byName = l.accounts.associateBy { it.name }
            // Five monthly stocktakes drifting toward today's balances.
            for (i in 1..5) {
                val t = start + i * 36 * day
                val entries = l.stocktakeAccounts(t).map { a ->
                    val target = l.toDisplay(a.id, l.currentBalance(a.id))
                    val base = l.toDisplay(a.id, l.balanceAt(a.id, start) ?: 0)
                    StocktakeEntry(a.id, base + (target - base) * i / 6 + (i % 2) * 37_00L * (if (target > 0) 1 else 0))
                }
                repo.createSnapshot(t, entries)
            }
            repo.saveFlow(
                MoneyFlow(
                    newId(), FlowKind.BUY, byName.getValue("余额宝").id, byName.getValue("招商产业债券A").id,
                    2_000_00, fee = 3_00, at = now - 40 * day, createdAt = now,
                ),
            )
            repo.createSnapshot(now - 1000, repo.loadOnce().let { x ->
                x.stocktakeAccounts(now).map { a -> StocktakeEntry(a.id, x.toDisplay(a.id, x.currentBalance(a.id))) }
            })
        }
        return c
    }

    private fun shot(name: String, container: AppContainer, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                AssetBoardTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT, upIsRed = true) { content() }
            }
        }
        compose.waitUntil(10_000) { container.repository.ledger.value?.snapshots?.isNotEmpty() == true }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    private val noop: () -> Unit = {}

    @Test fun overview() {
        val c = demoContainer()
        shot("01-overview", c) { OverviewScreen({}, noop, {}, noop, noop, {}) }
    }

    /** Picking a group in the allocation card, then tapping the ring, shows the total again. */
    @Test fun allocationReturnsToTotal() {
        val c = demoContainer()
        shot("01-overview", c) { OverviewScreen({}, noop, {}, noop, noop, {}) }
        compose.onAllNodesWithText("储蓄账户")[0].performClick()
        compose.onNodeWithText("点圆环看总资产").assertExists().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("点圆环看总资产").assertDoesNotExist()
        compose.onAllNodesWithText("投资账户")[0].performClick()
        compose.onAllNodesWithText("投资账户")[0].performClick()
        compose.onNodeWithText("点圆环看总资产").assertDoesNotExist()
    }

    @Test fun overviewDark() {
        val c = demoContainer()
        shot("02-overview-dark", c, dark = true) { OverviewScreen({}, noop, {}, noop, noop, {}) }
    }

    @Config(qualifiers = "w850dp-h900dp-xhdpi")
    @Test fun overviewUnfolded() {
        val c = demoContainer()
        shot("03-overview-unfolded", c) { OverviewScreen({}, noop, {}, noop, noop, {}) }
    }

    @Test fun stocktake() {
        val c = demoContainer()
        shot("04-stocktake", c) { StocktakeScreen(snapshotId = null, onBack = noop) }
    }

    @Test fun trend() {
        val c = demoContainer()
        shot("05-trend", c) { TrendScreen(noop, {}, noop) }
    }

    @Test fun trendLower() {
        val c = demoContainer()
        shot("05-trend", c) { TrendScreen(noop, {}, noop) }
        // Items: headline, range, net worth chart, assets and debts, change.
        compose.onNode(hasScrollAction()).performScrollToIndex(3)
        compose.mainClock.advanceTimeBy(1_500)
        compose.onRoot().captureRoboImage("build/screenshots/05b-trend-balance.png")
        compose.onNode(hasScrollAction()).performScrollToIndex(4)
        compose.mainClock.advanceTimeBy(1_500)
        compose.onRoot().captureRoboImage("build/screenshots/05c-trend-change.png")
    }

    @Test fun investmentDetail() {
        val c = demoContainer()
        val id = runBlocking { c.repository.loadOnce().accounts.first { it.name == "招商产业债券A" }.id }
        shot("06-account-investment", c) { AccountDetailScreen(id, noop, noop, noop, {}, {}, {}, {}) }
    }

    @Test fun cashDetail() {
        val c = demoContainer()
        val id = runBlocking { c.repository.loadOnce().accounts.first { it.name == "微信钱包" }.id }
        shot("07-account-cash", c) { AccountDetailScreen(id, noop, noop, noop, {}, {}, {}, {}) }
    }

    @Test fun accountEdit() {
        val c = demoContainer()
        val id = runBlocking { c.repository.loadOnce().accounts.first { it.name == "农行②" }.id }
        shot("08-account-edit", c) { AccountEditScreen(id, null, noop, {}, noop, {}) }
    }

    @Test fun flowEdit() {
        val c = demoContainer()
        val id = runBlocking { c.repository.loadOnce().accounts.first { it.name == "招商产业债券A" }.id }
        shot("09-flow-buy", c) { FlowEditScreen(id, null, "BUY", noop) }
    }

    @Test fun settings() {
        val c = demoContainer()
        shot("10-settings", c) { SettingsScreen(noop, noop) }
    }
}
