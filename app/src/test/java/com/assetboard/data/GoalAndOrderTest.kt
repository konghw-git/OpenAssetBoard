package com.assetboard.data

import android.app.Application
import android.net.Uri
import com.assetboard.domain.LedgerException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The net worth goal (its row, backup and restore) and the hand-set order of todos. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GoalAndOrderTest {
    private lateinit var f: Fixture
    private lateinit var goal: GoalRepository

    @Before fun setUp() {
        f = Fixture()
        goal = GoalRepository(f.db, f.gate)
    }

    @After fun tearDown() = f.close()

    private fun file(name: String, text: String) = Uri.fromFile(File(f.context.cacheDir, name).apply { writeText(text) })

    @Test fun goalIsSetAndCleared() = f.run {
        assertNull(goal.observe().first())
        goal.set(100_000_000)
        assertEquals(100_000_000L, goal.observe().first())
        goal.set(0)
        assertNull(goal.observe().first())
    }

    @Test fun goalRoundTripsThroughABackup() = f.run {
        goal.set(50_000_000)
        val uri = file("goal.json", f.backup.exportJson())
        goal.set(null)
        f.backup.restore(uri)
        assertEquals(50_000_000L, goal.observe().first())
    }

    @Test fun anOlderBackupClearsTheGoal() = f.run {
        goal.set(50_000_000)
        val v7 = Json.parseToJsonElement(f.backup.exportJson()).jsonObject
        val v6 = JsonObject(v7.filterKeys { it != "netWorthGoal" }.mapValues { (k, v) -> if (k == "formatVersion") JsonPrimitive(6) else v })
        f.backup.restore(file("v6.json", v6.toString()))
        assertNull(goal.observe().first())
    }

    @Test fun aBackupWithAGoalBelowZeroIsRefused() = f.run {
        goal.set(50_000_000)
        val bad = f.backup.exportJson().replace("\"netWorthGoal\":50000000", "\"netWorthGoal\":-1")
        try {
            f.backup.restore(file("bad.json", bad))
            fail("must be refused")
        } catch (e: LedgerException) {
            assertEquals("备份里的目标资产无效", e.message)
        }
        assertEquals(50_000_000L, goal.observe().first())
    }

    private suspend fun order() = f.todos.loadOnce().todos.sortedBy { it.sortOrder }.map { it.title }

    @Test fun reorderingSomeTodosLeavesTheRestWhereTheyWere() = f.run {
        val ids = listOf("a", "b", "c", "d", "e").associateWith { f.todos.create(TodoDraft(it)) }
        // b and d trade places; a, c and e keep theirs.
        f.todos.reorder(listOf(ids.getValue("d"), ids.getValue("b")))
        assertEquals(listOf("a", "d", "c", "b", "e"), order())
    }

    @Test fun reorderSpreadsSharedPlacesFirst() = f.run {
        // Created a second apart: shared places are spread out in the order the todos were made.
        val ids = listOf("a", "b", "c").map { f.now += 1_000; f.todos.create(TodoDraft(it)) }
        // Orders from before: every todo at 0.
        f.db.todoDao().allTodos().forEach { f.db.todoDao().updateTodo(it.copy(sortOrder = 0)) }
        f.todos.reorder(listOf(ids[2], ids[0]))
        val sorted = f.todos.loadOnce().todos.sortedBy { it.sortOrder }
        assertEquals(3, sorted.map { it.sortOrder }.toSet().size)
        assertEquals(listOf("c", "b", "a"), sorted.map { it.title })
    }
}
