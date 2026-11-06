package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import com.assetboard.CloseTestContainers
import com.assetboard.TestContainers
import com.assetboard.data.ThemeMode
import com.assetboard.data.TodoDraft
import com.assetboard.ui.theme.AssetBoardTheme
import com.assetboard.ui.todo.TodoDetailScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class TodoSaveTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules = RuleChain.outerRule(compose).around(CloseTestContainers())

    @Test fun savingANewTodoWithChecklistReturnsWithoutCompletingIt() {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        val id = runBlocking(c.gate.bound()) {
            c.todos.create(TodoDraft("准备出行")).also { c.todos.addChecklistItem(it, "订车票") }
        }
        var closed = false
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { TodoDetailScreen(id, { closed = true }, {}) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("准备出行")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("保存并返回").performClick()
        compose.waitUntil(5_000) { closed }
        assertNull(runBlocking { c.todos.todo(id) }!!.completedAt)
    }

    @Test fun returningSavesFocusedChecklistEditsAndTheUnsubmittedNewItem() {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        val id = runBlocking(c.gate.bound()) {
            c.todos.create(TodoDraft("准备出行")).also { c.todos.addChecklistItem(it, "订车票") }
        }
        var open by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) {
                    if (open) TodoDetailScreen(id, { open = false }, {})
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 4 }
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement("周末出行")
        fields[1].performTextReplacement("记得提前出门")
        fields[2].performTextReplacement("订高铁票")
        fields[3].performTextReplacement("带身份证")
        fields[3].assertTextContains("带身份证", substring = false)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitUntil(5_000) { compose.waitForIdle(); !open }
        try {
            compose.waitUntil(5_000) {
                compose.waitForIdle()
                runBlocking { c.todos.loadOnce() }.checklist.filter { it.todoId == id }.map { it.text }.toSet() == setOf("订高铁票", "带身份证")
            }
        } catch (e: Throwable) { throw AssertionError(runBlocking { c.todos.loadOnce() }.toString(), e) }
        val todo = runBlocking { c.todos.todo(id) }!!
        assertEquals("周末出行", todo.title)
        assertEquals("记得提前出门", todo.notes)
        assertNull(todo.completedAt)
    }
}
