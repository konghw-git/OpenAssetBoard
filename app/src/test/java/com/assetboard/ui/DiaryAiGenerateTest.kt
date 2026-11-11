package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import kotlinx.coroutines.CompletableDeferred
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import com.assetboard.CloseTestContainers
import com.assetboard.TestContainers
import com.assetboard.data.ai.AiGateway
import com.assetboard.data.ai.ChatTurn
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

    /** A gateway whose first reply asks for a tool, then writes once the result is fed back. */
    private class ToolAi : AiGateway {
        var toolResult = ""

        override suspend fun complete(system: String, user: String): String =
            if (user.contains("工具结果")) "　　今天过得不错。"
            else """{"tool":"day_overview","args":{"date":"${java.time.LocalDate.now()}"}}"""

        override suspend fun chat(system: String, history: List<ChatTurn>): String {
            val last = history.last().content
            if (last.contains("工具结果")) toolResult = last
            return complete(system, last)
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

    /**
     * The agent runs on the compose clock while the snackbar's 撤销 lives on it too: step time
     * in slices so the reply lands but the undo notice is still on screen.
     */
    private fun awaitShown(text: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!shown(text) && System.currentTimeMillis() < deadline) {
            compose.mainClock.advanceTimeBy(200)
            compose.waitForIdle()
        }
        assertTrue("timed out waiting for $text", shown(text))
    }

    @Test fun existingDiaryOffersUpdateAndSendsTheLatestEditorText() {
        val (_, ai) = show()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("我今天有一点疲惫，想早点休息。")
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription("AI更新日记").performClick()
        awaitShown("今天是个好日子。")
        assertTrue(ai.lastUser.contains("我今天有一点疲惫，想早点休息。"))
        assertTrue(ai.lastUser.contains("日期："))
        assertTrue(ai.lastUser.contains("更新"))
    }

    @Test fun streamingShowsOnlyOneProgressIndicatorAndCanBeStopped() {
        val release = CompletableDeferred<Unit>()
        val ai = object : AiGateway {
            override suspend fun complete(system: String, user: String): String = error("Expected streaming")
            override suspend fun streamChat(system: String, history: List<ChatTurn>, onText: suspend (String) -> Unit): String {
                release.await()
                return "尚未完成的内容"
            }
        }
        val c = TestContainers.open(ApplicationProvider.getApplicationContext(), ai)
        c.settings.setBool(c.settings.networkEnabled, false)
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { DiaryScreen(null, {}, {}, {}, {}) }
            }
        }
        compose.waitUntil(10_000) { shown("今天过得怎么样？") }
        compose.onNodeWithContentDescription("AI生成日记").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().size)
        compose.onNodeWithText("停止").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("停止")).fetchSemanticsNodes().isEmpty() }
        assertTrue(shown("今天过得怎么样？"))
        release.complete(Unit)
    }

    @Test fun streamedProseIsVisibleBeforeCompletionAndStoppingKeepsTheOriginal() {
        val release = CompletableDeferred<Unit>()
        val ai = object : AiGateway {
            override suspend fun complete(system: String, user: String): String = error("Expected streaming")
            override suspend fun streamChat(system: String, history: List<ChatTurn>, onText: suspend (String) -> Unit): String {
                onText("　　早上在公园散步，")
                release.await()
                return "　　早上在公园散步，晚上读书。"
            }
        }
        val c = TestContainers.open(ApplicationProvider.getApplicationContext(), ai)
        c.settings.setBool(c.settings.networkEnabled, false)
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { DiaryScreen(null, {}, {}, {}, {}) }
            }
        }
        compose.waitUntil(10_000) { shown("今天过得怎么样？") }
        compose.onNodeWithContentDescription("AI生成日记").performClick()
        compose.waitUntil(5_000) { shown("早上在公园散步") }
        assertTrue(shown("今天过得怎么样？"))
        assertEquals(0, compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().size)
        compose.onNodeWithText("停止").performClick()
        compose.waitUntil(5_000) { !shown("早上在公园散步") }
        assertTrue(shown("今天过得怎么样？"))
        release.complete(Unit)
    }

    @Test fun anEmptyDayIsFilledFromTheDaysRecords() {
        val (_, ai) = show()
        assertTrue(
            "generate is offered on an empty day",
            compose.onAllNodes(hasContentDescription("AI生成日记")).fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onNodeWithContentDescription("AI生成日记").performClick()
        awaitShown("今天是个好日子。")
        assertTrue("the agent's system carries the writing rules", ai.lastSystem.startsWith(AiEdit.DIARY_SYSTEM))
        assertTrue("the prompt carries the day", ai.lastUser.contains("日期："))
        assertTrue(undoShown())
    }

    @Test fun theModelCanPullDataWithAToolBeforeWriting() {
        val ai = ToolAi()
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
        compose.onNodeWithContentDescription("AI生成日记").performClick()
        awaitShown("今天过得不错。")
        assertTrue("the tool's answer went back to the model", ai.toolResult.contains("工具结果"))
    }

    @Test fun undoEmptiesTheGeneratedPageAgain() {
        show()
        compose.onNodeWithContentDescription("AI生成日记").performClick()
        awaitShown("今天是个好日子。")
        compose.onNodeWithText("撤销").performClick()
        compose.mainClock.advanceTimeBy(500)
        assertEquals(false, shown("今天是个好日子。"))
        assertTrue("the day is back to empty", shown("今天过得怎么样？"))
    }

    @Test fun thePromptSeesWhatWasUpdatedAfterThePageOpened() {
        val (c, ai) = show()
        // The day card's data moves after the page is open: a habit gets ticked and a todo done.
        kotlinx.coroutines.runBlocking(c.gate.bound()) {
            c.habits.toggle(com.assetboard.domain.Habit.DEFAULTS.first())
            val id = c.todos.create(com.assetboard.data.TodoDraft("买牛奶", planDate = java.time.LocalDate.now()))
            c.todos.complete(id, null)
            c.health.save(com.assetboard.data.MeasurementDraft(null, java.time.LocalDate.now(), 64_500, null, "晨起称重"))
        }
        compose.onNodeWithContentDescription("AI生成日记").performClick()
        awaitShown("今天是个好日子。")
        assertTrue("habits reach the prompt", ai.lastUser.contains("每日指标"))
        assertTrue("the freshly done todo reaches the prompt", ai.lastUser.contains("买牛奶"))
        assertTrue("day measurements reach the first request without a model tool round trip", ai.lastUser.contains("64.5kg"))
        assertTrue(ai.lastUser.contains("晨起称重"))
    }
}
