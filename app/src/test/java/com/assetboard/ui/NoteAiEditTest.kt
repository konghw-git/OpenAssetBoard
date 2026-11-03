package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import com.assetboard.CloseTestContainers
import com.assetboard.TestContainers
import com.assetboard.data.AiGateway
import com.assetboard.data.NoteText
import com.assetboard.data.ThemeMode
import com.assetboard.ui.theme.AssetBoardTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The note's AI passes: a selection goes alone while nothing selected takes the whole body, the
 * result lands as one undo step, and a body typed on while the model was working keeps what was
 * typed — the stale result is dropped, not pasted over it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class NoteAiEditTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(composeRule).around(CloseTestContainers())
    private val compose get() = composeRule

    /** Echoes what it got back in «», so a test sees exactly which text the pass ran on. */
    private class MarkingAi : AiGateway {
        /** When set, the reply waits for it — long enough for the test to type. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun complete(system: String, user: String): String {
            gate?.await()
            return "«$user»"
        }
    }

    private fun openNote(c: AppContainer, body: String) {
        runBlocking(c.gate.bound()) { c.notes.create(body, null) }
        c.settings.setBool(c.settings.networkEnabled, false)
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { AppNav(open = null, onOpenHandled = {}) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("资产")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("笔记").performClick()
        compose.waitUntil(8_000) { compose.onAllNodes(hasText(NoteText.title(body))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(NoteText.title(body)).performClick()
        compose.waitUntil(8_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun show(body: String, ai: AiGateway): AppContainer {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext(), ai)
        openNote(c, body)
        return c
    }

    private fun field() = compose.onNode(hasSetTextAction())
    private fun shown(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test fun aSelectionGoesAlone() {
        val body = "标题\n\n第一段。第二段。"
        show(body, MarkingAi())
        field().performClick()
        field().performTextInputSelection(TextRange(4, 8))
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("AI排版")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("AI排版").performClick()
        compose.waitUntil(5_000) { shown("«第一段。»") }
        field().assertTextContains("标题\n\n«第一段。»第二段。")
    }

    @Test fun noSelectionTakesTheWholeNote() {
        show("买牛奶", MarkingAi())
        field().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("润色")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("润色").performClick()
        compose.waitUntil(5_000) { shown("«买牛奶»") }
        field().assertTextContains("«买牛奶»")
    }

    @Test fun theUndoStepsBackOverAnAiPass() {
        val body = "标题\n\n正文。"
        show(body, MarkingAi())
        field().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("AI排版")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("AI排版").performClick()
        compose.waitUntil(5_000) { shown("«标题") }
        compose.onNodeWithContentDescription("撤销").performClick()
        field().assertTextContains(body, substring = false)
    }

    @Test fun typingDuringThePassDropsTheStaleResult() {
        val ai = MarkingAi().also { it.gate = CompletableDeferred() }
        show("原文。", ai)
        field().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("AI排版")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("AI排版").performClick()
        field().performTextReplacement("改过了。")
        ai.gate!!.complete(Unit)
        compose.waitUntil(5_000) { shown("文本已改动") }
        assertEquals(false, shown("«"))
        field().assertTextContains("改过了。", substring = false)
    }
}
