package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import com.assetboard.CloseTestContainers
import com.assetboard.TestContainers
import com.assetboard.data.AiGateway
import com.assetboard.data.ThemeMode
import com.assetboard.domain.AiEdit
import com.assetboard.ui.diary.DiaryScreen
import com.assetboard.ui.theme.AssetBoardTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 生成日记 on an empty day: the button writes the model's body in one go from the day's records —
 * the prompt really carries them — and 撤销 empties the page again.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class DiaryAiGenerateTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(composeRule).around(CloseTestContainers())
    private val compose get() = composeRule

    /** Answers every pass with a fixed diary line, and remembers what it was asked. */
    private class EchoAi : AiGateway {
        var lastSystem = ""
        var lastUser = ""

        override suspend fun complete(system: String, user: String): String {
            lastSystem = system
            lastUser = user
            return "今天是个好日子。"
        }
    }

    private fun show(): Pair<AppContainer, EchoAi> {
        val ai = EchoAi()
        val c = TestContainers.open(ApplicationProvider.getApplicationContext(), ai)
        c.settings.setBool(c.settings.networkEnabled, false)
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { DiaryScreen(null, {}, {}, {}, {}) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("今天过得怎么样？", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)
        return c to ai
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun undoShown() = shown("撤销")

    @Test fun anEmptyDayIsFilledFromTheDaysRecords() {
        val (_, ai) = show()
        assertTrue(
            "generate is offered on an empty day",
            compose.onAllNodes(hasContentDescription("AI生成日记")).fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onNodeWithContentDescription("AI生成日记").performClick()
        compose.mainClock.advanceTimeBy(300)
        assertEquals(AiEdit.DIARY_SYSTEM, ai.lastSystem)
        assertTrue("the prompt carries the day", ai.lastUser.contains("日期："))
        assertTrue(shown("今天是个好日子。"))
        assertTrue(undoShown())
    }

    @Test fun undoEmptiesTheGeneratedPageAgain() {
        show()
        compose.onNodeWithContentDescription("AI生成日记").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("撤销").performClick()
        compose.mainClock.advanceTimeBy(500)
        assertEquals(false, shown("今天是个好日子。"))
        assertTrue("the day is back to empty", shown("今天过得怎么样？"))
    }
}
