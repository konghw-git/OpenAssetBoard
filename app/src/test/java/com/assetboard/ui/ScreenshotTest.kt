package com.assetboard.ui

import com.assetboard.ui.theme.AppTheme
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
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
import com.assetboard.ui.health.HealthScreen
import com.assetboard.data.MeasurementDraft
import com.assetboard.data.Sex
import java.time.LocalDate
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

    /** Every seeded account's tile, light and dark, to check that no two look alike. */
    @Test fun accountIcons() = accountIcons(dark = false)

    @Test fun accountIconsDark() = accountIcons(dark = true)

    private fun accountIcons(dark: Boolean) {
        val c = demoContainer()
        run {
            shot(if (dark) "04b-account-icons-dark" else "04a-account-icons", c, dark) {
                val l = c.repository.ledger.collectAsState().value
                androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.padding(16.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
                    ) {
                        l?.accounts.orEmpty().forEach { a ->
                            androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, modifier = Modifier.width(80.dp)) {
                                com.assetboard.ui.components.AccountAvatar(a, AppTheme.extra.group(l!!.groupById[a.groupId]?.colorIndex ?: 0), size = 48.dp)
                                androidx.compose.material3.Text(a.name, maxLines = 1, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
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

    /**
     * About three months of weigh-ins toward today's 92.1 kg: most mornings, body fat on most of
     * them, and two trips with nothing recorded at all.
     */
    private fun withHealth(c: AppContainer, profile: Boolean = true): AppContainer {
        runBlocking(c.gate.bound()) {
            if (profile) c.health.saveProfile(Sex.MALE, LocalDate.of(2001, 8, 2), 1820, null)
            val today = LocalDate.now()
            for (i in 90 downTo 0) {
                if (i in 40..49 || i in 12..15 || i % 5 == 3) continue
                val g = 92_100L + i * 32L + ((i * 37) % 7 - 3) * 120L
                val fat = if (i % 3 == 1) null else 2_580 + i * 4 + ((i * 13) % 5 - 2) * 10
                c.health.save(MeasurementDraft(null, today.minusDays(i.toLong()), g, fat, null))
            }
        }
        return c
    }

    @Test fun health() {
        val c = withHealth(demoContainer())
        shot("11-health", c) { HealthScreen(noop) }
    }

    @Test fun healthLower() {
        val c = withHealth(demoContainer())
        shot("11-health", c) { HealthScreen(noop) }
        // Items: hero, selectors, trend, figures, target, body type, records.
        compose.onNode(hasScrollAction()).performScrollToIndex(3)
        compose.mainClock.advanceTimeBy(1_500)
        compose.onRoot().captureRoboImage("build/screenshots/11b-health-figures.png")
        compose.onNode(hasScrollAction()).performScrollToIndex(5)
        compose.mainClock.advanceTimeBy(1_500)
        compose.onRoot().captureRoboImage("build/screenshots/11c-health-records.png")
    }

    @Test fun healthBodyFat() {
        val c = withHealth(demoContainer())
        shot("11-health", c) { HealthScreen(noop) }
        compose.onNode(hasText("体脂率") and hasClickAction()).performClick()
        compose.onNode(hasText("全部") and hasClickAction()).performClick()
        compose.mainClock.advanceTimeBy(1_500)
        compose.onRoot().captureRoboImage("build/screenshots/11d-health-bodyfat.png")
    }

    @Test fun healthDark() {
        val c = withHealth(demoContainer())
        shot("12-health-dark", c, dark = true) { HealthScreen(noop) }
    }

    @Test fun healthEdit() {
        val c = withHealth(demoContainer())
        shot("13-health-edit", c) { HealthScreen(noop, startRecording = true) }
    }

    /** No profile yet: weight only, and the first visit asks for the profile. */
    @Test fun healthWithoutProfile() {
        val c = withHealth(demoContainer(), profile = false)
        shot("14-health-profile", c) { HealthScreen(noop) }
    }

    @Test fun overviewCashAndHealth() {
        val c = withHealth(demoContainer())
        shot("01-overview", c) { OverviewScreen({}, noop, {}, noop, noop, {}) }
        compose.onNodeWithText("个现金账户", substring = true).performClick()
        compose.onNode(hasScrollAction()).performScrollToIndex(2)
        compose.mainClock.advanceTimeBy(2_000)
        compose.onRoot().captureRoboImage("build/screenshots/01b-overview-cash-health.png")
    }
}
