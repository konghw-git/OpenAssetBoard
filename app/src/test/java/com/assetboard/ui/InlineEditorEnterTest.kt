package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import com.assetboard.CloseTestContainers
import com.assetboard.TestContainers
import com.assetboard.data.ChecklistItem
import com.assetboard.data.InlineItem
import com.assetboard.data.ThemeMode
import com.assetboard.data.Todo
import com.assetboard.data.TodoDraft
import com.assetboard.ui.theme.AssetBoardTheme
import com.assetboard.ui.todo.TodoHomeScreen
import com.assetboard.ui.todo.TodoInlineEditor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Enter in the in-place editor, then leaving it, must keep every existing checklist item. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class InlineEditorEnterTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(compose).around(CloseTestContainers())

    @Test fun enterForAnEmptyItemThenDoneKeepsTheExistingItems() {
        val todo = Todo("t", title = "买菜", createdAt = 0)
        val a = ChecklistItem("a", "t", "买蛋")
        val b = ChecklistItem("b", "t", "买奶")
        var committed: List<InlineItem>? = null
        compose.setContent {
            var editing by remember { mutableStateOf(true) }
            AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) {
                if (editing) {
                    TodoInlineEditor(
                        todo, listOf(a, b),
                        onCommit = { _, items -> committed = items },
                        onDone = { editing = false },
                    )
                }
            }
        }
        compose.onNodeWithText("买菜").performClick()
        compose.onNodeWithText("买菜").performTextInput("\n")
        compose.waitForIdle()
        compose.onNodeWithText("完成").performClick()
        compose.waitForIdle()
        assertEquals(listOf("a", "b"), committed!!.filter { it.text.isNotEmpty() }.map { it.id })
        assertEquals(listOf("买蛋", "买奶"), committed!!.filter { it.text.isNotEmpty() }.map { it.text })
    }

    @Test fun enterInTheListThenBackKeepsTheExistingItems() {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        val id = runBlocking(c.gate.bound()) {
            val id = c.todos.create(TodoDraft("买菜", planDate = LocalDate.now()))
            c.todos.addChecklistItem(id, "买蛋")
            c.todos.addChecklistItem(id, "买奶")
            id
        }
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { TodoHomeScreen({}, {}, initialView = "today") }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("买菜").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("买蛋").performClick()
        compose.waitForIdle()
        // Edit in place starts with the tapped item focused, cursor at its end.
        compose.onNode(hasText("买蛋", substring = true)).performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText("检查项").fetchSemanticsNodes().size)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        val texts = runBlocking { c.db.todoDao().checklistOf(id).sortedBy { it.sortOrder }.map { it.text } }
        assertEquals(listOf("买蛋", "买奶"), texts)
    }

    @Test fun aSecondEnterOnTheNewEmptyItemThenBackKeepsTheExistingItems() {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        val id = runBlocking(c.gate.bound()) {
            val id = c.todos.create(TodoDraft("买菜", planDate = LocalDate.now()))
            c.todos.addChecklistItem(id, "买蛋")
            c.todos.addChecklistItem(id, "买奶")
            id
        }
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { TodoHomeScreen({}, {}, initialView = "today") }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("买菜").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("买蛋").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("买蛋", substring = true)).performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText("检查项").fetchSemanticsNodes().size)
        compose.onNode(hasSetTextAction() and isFocused()).performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        val texts = runBlocking { c.db.todoDao().checklistOf(id).sortedBy { it.sortOrder }.map { it.text } }
        assertEquals(listOf("买蛋", "买奶"), texts)
    }
}
