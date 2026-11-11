package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import com.assetboard.CloseTestContainers
import com.assetboard.TestContainers
import com.assetboard.data.DiarySession
import com.assetboard.data.ThemeMode
import com.assetboard.ui.diary.DiaryScreen
import com.assetboard.ui.theme.AssetBoardTheme
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

/** The diary page now writes Markdown live like the note page: real checkboxes, the format bar, undo. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class DiaryMarkdownTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(composeRule).around(CloseTestContainers())
    private val compose get() = composeRule

    private fun show(body: String): AppContainer {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        runBlocking(c.gate.bound()) {
            val s = DiarySession(c.diary, LocalDate.now(), c.appScope + c.gate.bound())
            s.open(null)
            s.onInput(body)
            s.saveNow()
            s.close()
        }
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { DiaryScreen(null, {}, {}, {}, {}) }
            }
        }
        compose.waitUntil(10_000) {
            if (body.isEmpty()) {
                compose.onAllNodes(hasText("今天过得怎么样？")).fetchSemanticsNodes().isNotEmpty()
            } else {
                compose.onAllNodes(hasText(body.lineSequence().first(), substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
        }
        return c
    }

    private fun waitForDescription(text: String) =
        compose.waitUntil(8_000) { compose.onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun markdownCheckboxesAreRealInTheDiary() {
        show("- [ ] 买牛奶")
        waitForDescription("待办：买牛奶")
        compose.onNodeWithContentDescription("待办：买牛奶").performClick()
        compose.onNodeWithContentDescription("待办：买牛奶").assertIsOn()
    }

    @Test fun theToolbarFormatsAndTheBackButtonStepsBack() {
        show("买牛奶")
        compose.onAllNodes(hasSetTextAction())[0].performClick()
        waitForDescription("更多工具")
        compose.onNodeWithContentDescription("更多工具").performClick()
        waitForDescription("一级标题")
        compose.onNodeWithContentDescription("一级标题").performClick()
        compose.onAllNodes(hasSetTextAction())[0].assertTextContains("# 买牛奶")
        waitForDescription("撤销")
        compose.onNodeWithContentDescription("撤销").performClick()
        compose.onAllNodes(hasSetTextAction())[0].assertTextContains("买牛奶")
    }

    @Test fun theExtractButtonAppearsForASelection() {
        show("买牛奶和鸡蛋")
        compose.onAllNodes(hasSetTextAction())[0].performClick()
        waitForDescription("撤销")
        compose.onAllNodes(hasSetTextAction())[0].performTextInputSelection(TextRange(0, 3))
        compose.waitUntil(8_000) {
            compose.onAllNodes(hasText("提炼选中的 3 字")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun tappingTheHintStartsTheWriting() {
        show("")
        compose.onAllNodes(hasText("今天过得怎么样？"))[0].performClick()
        waitForDescription("撤销")
        compose.onAllNodes(hasSetTextAction())[0].assertExists()
    }
}
