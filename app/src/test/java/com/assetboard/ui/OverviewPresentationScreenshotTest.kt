package com.assetboard.ui

import com.assetboard.domain.AppClock
import com.assetboard.PinnedClock
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.assetboard.CloseTestContainers
import com.assetboard.TestContainers
import com.assetboard.data.AccountDraft
import com.assetboard.data.AccountKind
import com.assetboard.data.ThemeMode
import com.assetboard.data.seedDemo
import com.assetboard.domain.Money
import com.assetboard.ui.components.LocalHideAmounts
import com.assetboard.ui.overview.OverviewScreen
import com.assetboard.ui.theme.AssetBoardTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Readability on a small phone, including large amounts, font scaling and the privacy toggle. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h880dp-xxhdpi")
class OverviewPresentationScreenshotTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(PinnedClock()).around(compose).around(CloseTestContainers())

    @Test fun narrowLargeAmountsLight() = narrow(ThemeMode.LIGHT)
    @Test fun narrowLargeAmountsDark() = narrow(ThemeMode.DARK)

    private fun narrow(mode: ThemeMode) {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        runBlocking(c.gate.bound()) {
            c.repository.seedDemo()
            val group = c.repository.loadOnce().groups.first { !it.isLiability }
            c.repository.createAccount(AccountDraft(group.id, "长期储备", null, AccountKind.NORMAL, null), AppClock.millis(), 123_456_789_012L, null)
        }
        val totals = runBlocking { c.repository.loadOnce().currentTotals() }
        val total = totals.netWorth
        val hidden = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalContainer provides c, LocalDensity provides Density(density, 1.3f), LocalHideAmounts provides hidden.value) {
                AssetBoardTheme(mode, upIsRed = true) { OverviewScreen({}, {}, {}, {}, {}, {}) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(Money.format(total)).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(1_000)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(Money.format(total)).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertFalse("The full net worth must fit at 320dp and 130% font size", layouts.single().hasVisualOverflow)
        val assets = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(Money.format(totals.assets)).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(assets) }
        assertFalse("Supporting totals must also remain complete", assets.single().hasVisualOverflow)
        compose.onRoot().captureRoboImage("screenshots/ui-narrow-${mode.name.lowercase()}.png", roborazziOptions = SHOT_OPTIONS)

        compose.onNodeWithContentDescription("隐藏金额").performClick()
        assertTrue(c.settings.hideAmounts.value)
        compose.runOnIdle { hidden.value = true }
        compose.onNodeWithText(Money.format(total)).assertDoesNotExist()
        compose.onNodeWithContentDescription("显示金额").assertExists()
        compose.onNode(hasScrollAction()).performScrollToIndex(2)
        compose.onNodeWithText("全部展开").assertExists()
        val accountText = mutableListOf<TextLayoutResult>()
        compose.onAllNodesWithText("现金账户", useUnmergedTree = true).onLast().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(accountText) }
        if (mode == ThemeMode.DARK) assertTrue("Custom account cards must inherit a light foreground", accountText.single().layoutInput.style.color.luminance() > 0.5f)
        compose.onNode(hasScrollAction()).performScrollToIndex(0)
        compose.onNodeWithText("可用现金").performClick()
        compose.onNodeWithText("现金账户 · ", substring = true).assertExists()
    }
}
