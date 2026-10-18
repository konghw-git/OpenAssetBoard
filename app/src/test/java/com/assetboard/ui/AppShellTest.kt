package com.assetboard.ui

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import com.assetboard.data.ThemeMode
import com.assetboard.domain.todo.RepeatRule
import com.assetboard.domain.todo.RepeatType
import com.assetboard.reminder.DiaryReminderReceiver
import com.assetboard.ui.theme.AssetBoardTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** Group S (start-up), R14 at the UI level, the built-in holiday data and D8. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class AppShellTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** Bumping it throws the whole shell away, as a cold start does. */
    private var launches by mutableIntStateOf(0)

    private fun show(c: AppContainer) {
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) { key(launches) { AppNav(open = null, onOpenHandled = {}) } }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("资产")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun s1_coldStartOpensTheAssetsTab() {
        val c = AppContainer(ApplicationProvider.getApplicationContext())
        show(c)
        compose.onNodeWithText("日记").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("今天过得怎么样？")).fetchSemanticsNodes().isNotEmpty() }
        // A cold start is a fresh shell.
        launches++
        compose.waitForIdle()
        compose.onNodeWithText("资产").assertIsSelected()
        compose.onNodeWithText("开始记录你的资产").assertExists()
    }

    @Test fun s2_noNetworkNoPermissionsStillShowsAssets() {
        val c = AppContainer(ApplicationProvider.getApplicationContext())
        c.settings.setBool(c.settings.networkEnabled, false)
        show(c)
        compose.onNodeWithText("资产").assertIsSelected()
        compose.onNodeWithText("开始记录你的资产").assertExists()
        c.settings.setBool(c.settings.networkEnabled, true)
    }

    @Test fun r14_restoreClosesOpenScreensAndReturnsHome() {
        val c = AppContainer(ApplicationProvider.getApplicationContext())
        runBlocking(c.gate.bound()) { c.repository.seedDefaultGroups() }
        val file = File(c.db.openHelper.readableDatabase.path!!).parentFile!!.resolve("r14.json")
        runBlocking { file.writeText(c.backup.exportJson()) }
        show(c)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("我的资产")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("设置").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("备份目录")).fetchSemanticsNodes().isNotEmpty() }
        runBlocking { c.backup.restore(Uri.fromFile(file)) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("备份目录")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("我的资产").assertExists()
    }

    @Test fun builtInHolidaysDriveTheWorkdayRule() = runBlocking {
        val c = AppContainer(ApplicationProvider.getApplicationContext())
        c.holidays.init()
        val table = c.holidays.table.value
        assertTrue(table.hasYear(2026))
        assertFalse(table.isWorkday(LocalDate.of(2026, 10, 1)))
        assertTrue(table.isWorkday(LocalDate.of(2026, 10, 10))) // a make-up Saturday
        // P5 with the real schedule.
        val (a, b) = runBlocking(c.gate.bound()) {
            val t = c.todos
            val x = t.create(com.assetboard.data.TodoDraft("周报", planDate = LocalDate.of(2026, 9, 30)))
            t.setRepeat(x, RepeatRule(RepeatType.WORKDAY))
            val y = t.create(com.assetboard.data.TodoDraft("打卡", planDate = LocalDate.of(2026, 10, 9)))
            t.setRepeat(y, RepeatRule(RepeatType.WORKDAY))
            x to y
        }
        val next = com.assetboard.domain.todo.Recurrence.next(RepeatRule(RepeatType.WORKDAY), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 30), table)
        assertEquals(LocalDate.of(2026, 10, 8), next)
        val sat = com.assetboard.domain.todo.Recurrence.next(RepeatRule(RepeatType.WORKDAY), LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 9), table)
        assertEquals(LocalDate.of(2026, 10, 10), sat)
        assertTrue(a.isNotEmpty() && b.isNotEmpty())
    }

    @Test fun d8_noNudgeOnceTodayHasText() {
        assertTrue(DiaryReminderReceiver.needsNudge(null))
        assertTrue(DiaryReminderReceiver.needsNudge("  \n"))
        assertFalse(DiaryReminderReceiver.needsNudge("今天写过了"))
    }
}
