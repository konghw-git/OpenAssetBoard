package com.assetboard.ui

import android.Manifest
import android.app.Application
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.assetboard.CloseTestContainers
import com.assetboard.TestContainers
import com.assetboard.data.FakeCalendarProvider
import com.assetboard.data.ThemeMode
import com.assetboard.data.TodoDraft
import com.assetboard.ui.theme.AssetBoardTheme
import com.assetboard.ui.todo.TodoDetailScreen
import com.assetboard.ui.todo.TodoHomeScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.time.LocalDate
import java.time.LocalTime

/** Writing a todo, and then a whole day, into the phone calendar from the app's own screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class CalendarUiTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(compose).around(CloseTestContainers())

    private fun calendarReady(): Pair<Application, FakeCalendarProvider> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val provider = FakeCalendarProvider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        return app to provider
    }

    @Test fun aTodoWithADayIsWrittenToTheCalendarPickedInItsDetail() {
        val (app, provider) = calendarReady()
        val c = TestContainers.open(app)
        val id = runBlocking(c.gate.bound()) {
            c.todos.create(TodoDraft("写周报", planDate = LocalDate.now(), remindTime = LocalTime.of(10, 0)))
        }
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { TodoDetailScreen(id, {}, {}) }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("写入系统日历")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("写入系统日历").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("日历1")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("日历1").performClick()
        compose.waitUntil(5_000) { provider.events.isNotEmpty() }
        assertEquals("写周报", provider.events.values.single().getAsString(CalendarContract.Events.TITLE))
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("系统日历")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun theTodayHeaderWritesEveryTodoPlannedForToday() {
        val (app, provider) = calendarReady()
        val c = TestContainers.open(app)
        runBlocking(c.gate.bound()) {
            c.todos.create(TodoDraft("晨跑", planDate = LocalDate.now(), remindTime = LocalTime.of(7, 0)))
            c.todos.create(TodoDraft("交水电费", planDate = LocalDate.now()))
            c.todos.create(TodoDraft("明天的事", planDate = LocalDate.now().plusDays(1)))
        }
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { TodoHomeScreen({}, {}, initialView = "today") }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("把今天的日程写入日历").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("把今天的日程写入日历").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("日历1")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("日历1").performClick()
        compose.waitUntil(5_000) { provider.events.size == 2 }
        assertEquals(setOf("晨跑", "交水电费"), provider.events.values.map { it.getAsString(CalendarContract.Events.TITLE) }.toSet())
    }
}
