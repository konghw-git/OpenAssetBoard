package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import com.assetboard.CloseTestContainers
import com.assetboard.TestContainers
import com.assetboard.data.NoteText
import com.assetboard.data.ThemeMode
import com.assetboard.ui.theme.AssetBoardTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Markdown surface: a real checkbox under the pointer, the toolbar, and Enter carrying a list on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class NoteEditorTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(composeRule).around(CloseTestContainers())
    private val compose get() = composeRule

    private fun show(c: AppContainer) {
        c.settings.setBool(c.settings.networkEnabled, false)
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { AppNav(open = null, onOpenHandled = {}) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("资产")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitFor(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun openNote(c: AppContainer, body: String) {
        runBlocking(c.gate.bound()) { c.notes.create(body, null) }
        show(c)
        compose.onNodeWithText("笔记").performClick()
        waitFor(NoteText.title(body))
        compose.onNodeWithText(NoteText.title(body)).performClick()
        compose.waitUntil(8_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForDescription(text: String) =
        compose.waitUntil(8_000) { compose.onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun theBoxOverAChecklistItemTogglesIt() {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        openNote(c, "- [ ] 买牛奶\n- [ ] 跑步")
        waitForDescription("待办：买牛奶")
        compose.onNodeWithContentDescription("待办：买牛奶").assertIsOff()
        compose.onNodeWithContentDescription("待办：跑步").assertIsOff()

        compose.onNodeWithContentDescription("待办：买牛奶").performClick()
        compose.onNodeWithContentDescription("待办：买牛奶").assertIsOn()
        compose.onNodeWithContentDescription("待办：跑步").assertIsOff()

        compose.onNodeWithContentDescription("待办：买牛奶").performClick()
        compose.onNodeWithContentDescription("待办：买牛奶").assertIsOff()
    }

    @Test fun theToolbarTurnsTheLineIntoAChecklistAndAddsADivider() {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        openNote(c, "买牛奶")
        compose.onNode(hasSetTextAction()).performClick()
        waitForDescription("待办清单")
        compose.onNodeWithContentDescription("待办清单").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("- [ ] 买牛奶")
        compose.onNodeWithContentDescription("分割线").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("- [ ] 买牛奶\n---\n")
    }

    @Test fun enterAfterAChecklistStartsTheNextOne() {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        openNote(c, "- [ ] 买牛奶")
        val field = compose.onNode(hasSetTextAction())
        field.performClick()
        field.performTextInputSelection(TextRange("- [ ] 买牛奶".length))
        field.performTextInput("\n")
        compose.onNode(hasSetTextAction()).assertTextContains("- [ ] 买牛奶\n- [ ] ")
    }
}
