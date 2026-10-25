package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import com.assetboard.CloseTestContainers
import com.assetboard.TestContainers
import com.assetboard.data.AccountDraft
import com.assetboard.data.FlowKind
import com.assetboard.data.ThemeMode
import com.assetboard.ui.bill.BillScreen
import com.assetboard.ui.theme.AssetBoardTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 记一笔 from the sheet: keys, category, 保存 and 再记. The pull that opens it is in ScreenshotTest. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class BillSheetTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(composeRule).around(CloseTestContainers())
    private val compose get() = composeRule

    private lateinit var wallet: String

    private fun container(): AppContainer {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        runBlocking(c.gate.bound()) {
            val g = c.repository.createGroup("储蓄", false, 0)
            wallet = c.repository.createAccount(AccountDraft(g, "钱包"), 0, 1_000_00, null)
        }
        return c
    }

    private fun press(vararg keys: String) = keys.forEach { compose.onNodeWithContentDescription(it).performClick() }

    private fun bills(c: AppContainer) = runBlocking { c.repository.loadOnce() }.flows.filter { it.kind.isBill }

    @Test fun anAmountAndACategoryAreABill() {
        val c = container()
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) { AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { BillScreen({}, {}) } }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("这个月还没有记账")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("记一笔").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("交通")).fetchSemanticsNodes().isNotEmpty() }
        press("2", "0", "+", "1", "5", ".", "5")
        compose.onNodeWithText("交通").performClick()
        // The clock stands still from here, or waiting would run the undo's few seconds out at once.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("保存").performClick()
        repeat(400) {
            if (bills(c).size == 1 && compose.onAllNodes(hasText("撤销")).fetchSemanticsNodes().isNotEmpty()) return@repeat
            compose.mainClock.advanceTimeBy(16)
            Thread.sleep(5)
        }
        val b = bills(c).single()
        assertEquals(FlowKind.EXPENSE, b.kind)
        assertEquals(35_50L, b.amount)
        assertEquals("transport", b.category)
        assertEquals(wallet, b.fromAccountId)
        assertEquals(964_50L, runBlocking { c.repository.loadOnce() }.currentBalance(wallet))
        // 撤销 takes it back, and the balance with it.
        compose.onNode(hasText("已记一笔", substring = true)).assertExists()
        compose.onNodeWithText("撤销").performClick()
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { bills(c).isEmpty() }
        assertEquals(1_000_00L, runBlocking { c.repository.loadOnce() }.currentBalance(wallet))
    }

    @Test fun againSavesAndStaysOpenForTheNext() {
        val c = container()
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) { AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { BillScreen({}, {}) } }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("这个月还没有记账")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("记一笔").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("收入")).fetchSemanticsNodes().isNotEmpty() }
        press("1", "2")
        compose.onNodeWithContentDescription("再记").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("已记 1 笔")).fetchSemanticsNodes().isNotEmpty() }
        // Nothing typed yet: 保存 does not save an empty bill.
        compose.onNodeWithContentDescription("保存").performClick()
        compose.mainClock.advanceTimeBy(500)
        assertEquals(1, bills(c).size)
        compose.onNodeWithTag(com.assetboard.ui.bill.KIND_TAG).performTouchInput { click(Offset(width * 0.75f, centerY)) }
        press("8", "0", "0", "0")
        compose.onNodeWithContentDescription("保存").performClick()
        compose.waitUntil(5_000) { bills(c).size == 2 }
        assertEquals(listOf(FlowKind.EXPENSE to 12_00L, FlowKind.INCOME to 8_000_00L), bills(c).sortedBy { it.amount }.map { it.kind to it.amount })
        assertEquals(1_000_00L - 12_00L + 8_000_00L, runBlocking { c.repository.loadOnce() }.currentBalance(wallet))
    }
}
