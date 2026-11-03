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
import com.assetboard.data.AiGateway
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
 * The notice after AI 排版: its 撤销 goes by itself after a few seconds, a second tap offers it
 * afresh instead of queueing another notice behind it, and it goes at once when typing makes it
 * unable to undo. The gateway here is the local tidy — what 排版 did before AI — so the text the
 * tests expect stays deterministic.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class DiaryTidyNoticeTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(composeRule).around(CloseTestContainers())
    private val compose get() = composeRule

    private val typed = "早上跑了五公里.\n\n\n下午改论文"

    private object TidyAi : AiGateway {
        override suspend fun complete(system: String, user: String): String = DiaryFormat.tidy(user)
    }

    /** The local tidy behind a gate: a reply the test lets land only when it says so. */
    private class GatedTidyAi : AiGateway {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()

        override suspend fun complete(system: String, user: String): String {
            gate.await()
            return DiaryFormat.tidy(user)
        }
    }

    private fun show(ai: AiGateway = TidyAi): AppContainer {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext(), ai)
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
        compose.onNodeWithContentDescription("AI排版").performClick()
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

    @Test fun typingDuringThePassDropsTheStaleResult() {
        val ai = GatedTidyAi()
        show(ai)
        compose.onNodeWithContentDescription("AI排版").performClick()
        compose.onNodeWithText("下午改论文", substring = true).performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("改过了，晚上散步")
        ai.gate.complete(Unit)
        compose.mainClock.advanceTimeBy(600)
        assertEquals("the stale reply is announced", true, shown("文本已改动"))
        assertEquals("and offers no undo", false, undoShown())
        assertEquals("what was typed stays", true, shown("改过了，晚上散步"))
    }
}
