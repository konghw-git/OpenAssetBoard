package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import com.assetboard.CloseTestContainers
import com.assetboard.TestContainers
import com.assetboard.data.DiarySession
import com.assetboard.data.ThemeMode
import com.assetboard.domain.DiaryFormat
import com.assetboard.ui.diary.DiaryScreen
import com.assetboard.ui.theme.AssetBoardTheme
import kotlinx.coroutines.plus
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
import java.time.LocalDate

/**
 * The notice after 一键排版: its 撤销 goes by itself after a few seconds, a second tap offers it
 * afresh instead of queueing another notice behind it, and it goes at once when typing makes it
 * unable to undo.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class DiaryTidyNoticeTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(composeRule).around(CloseTestContainers())
    private val compose get() = composeRule

    private val typed = "早上跑了五公里.\n\n\n下午改论文"

    private fun show(): AppContainer {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        runBlocking(c.gate.bound()) {
            val s = DiarySession(c.diary, LocalDate.now(), c.appScope + c.gate.bound())
            s.open(null)
            s.onInput(typed)
            s.saveNow()
            s.close()
        }
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { DiaryScreen(null, {}, {}, {}, {}) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("下午改论文", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)
        return c
    }

    private fun undoShown() = compose.onAllNodes(hasText("撤销")).fetchSemanticsNodes().isNotEmpty()
    private fun shown(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun tidy() {
        compose.onNodeWithContentDescription("一键排版").performClick()
        compose.mainClock.advanceTimeBy(300)
    }

    @Test fun theUndoGoesByItself() {
        show()
        tidy()
        assertEquals(true, undoShown())
        compose.mainClock.advanceTimeBy(3_000)
        assertEquals(true, undoShown())
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(false, undoShown())
    }

    @Test fun aSecondTapOffersTheUndoAfresh() {
        show()
        tidy()
        compose.mainClock.advanceTimeBy(3_000)
        tidy()
        assertEquals(false, shown("已经很整齐了"))
        compose.mainClock.advanceTimeBy(3_000)
        assertEquals(true, undoShown())
        compose.onNodeWithText("撤销").performClick()
        compose.mainClock.advanceTimeBy(500)
        assertEquals(false, undoShown())
        compose.waitUntil(5_000) { shown("早上跑了五公里.") }
        // Back to the text as typed, it can be tidied again, and the notice comes again.
        tidy()
        assertEquals(true, undoShown())
    }

    @Test fun typingAfterTheTidyTakesTheUndoAway() {
        show()
        tidy()
        assertEquals(true, undoShown())
        val field = compose.onAllNodes(hasSetTextAction())
        if (field.fetchSemanticsNodes().isEmpty()) compose.onNodeWithText("下午改论文", substring = true).performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement(DiaryFormat.tidy(typed) + "，晚上散步")
        compose.mainClock.advanceTimeBy(300)
        assertEquals(false, undoShown())
    }
}
