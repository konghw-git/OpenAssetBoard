package com.assetboard.data.ai

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import com.assetboard.TestContainers
import com.assetboard.data.AccountDraft
import com.assetboard.data.AccountKind
import com.assetboard.data.StocktakeEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** The two tools whose numbers the generated diary repeats: stocktake changes and countdowns. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AiDayToolsTest {
    @After
    fun close() {
        TestContainers.closeAll()
    }

    private fun tools(c: AppContainer) = AiDayTools(
        c.diary, c.todos, c.habits, c.health, c.food, c.repository, c.countdowns, c.weeks, c.goal,
    )

    @Test
    fun netWorthComparesEachStocktakeWithItsPredecessorEvenBelowFive() = runBlocking {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        runBlocking(c.gate.bound()) {
            val gid = c.repository.createGroup("现金", false, 0)
            val a = c.repository.createAccount(AccountDraft(gid, "现金", null, AccountKind.NORMAL, null), 1, 100_00, null)
            c.repository.createSnapshot(10, listOf(StocktakeEntry(a, 100_00)))
            c.repository.createSnapshot(20, listOf(StocktakeEntry(a, 150_00)))
            c.repository.createSnapshot(30, listOf(StocktakeEntry(a, 130_00)))
        }

        val out = tools(c).tools(LocalDate.of(2026, 11, 4)).first { it.name == "net_worth" }.run(emptyMap())
        val lines = out.lines()

        assertEquals(3, lines.size)
        assertFalse("the first stocktake has no predecessor: ${lines[0]}", lines[0].contains("较上次"))
        assertTrue("the second compares against the first: ${lines[1]}", lines[1].contains("较上次"))
        assertTrue("the third compares against the second: ${lines[2]}", lines[2].contains("较上次"))
    }

    @Test
    fun aYearlyCountdownRollsOverToItsNextOccurrence() = runBlocking {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        runBlocking(c.gate.bound()) { c.countdowns.save(null, "生日", LocalDate.of(2020, 1, 1), yearly = true, pinned = false) }

        val out = tools(c).tools(LocalDate.of(2026, 11, 4)).first { it.name == "countdowns" }.run(emptyMap())

        assertTrue("a passed yearly date counts to the next one: $out", out.contains("还有 58 天"))
    }

    @Test
    fun aOneOffCountdownStillSaysHowLongAgoItWas() = runBlocking {
        val c = TestContainers.open(ApplicationProvider.getApplicationContext())
        runBlocking(c.gate.bound()) { c.countdowns.save(null, "搬家", LocalDate.of(2020, 1, 1), yearly = false, pinned = false) }

        val out = tools(c).tools(LocalDate.of(2026, 11, 4)).first { it.name == "countdowns" }.run(emptyMap())

        assertTrue("a passed one-off date stays in the past: $out", out.contains("已过"))
    }
}
