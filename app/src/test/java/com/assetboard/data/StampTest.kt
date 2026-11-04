package com.assetboard.data

import android.app.Application
import com.assetboard.domain.Habit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * M1's write-path audit: every repository write must move the right stamp (content or state, or
 * neither), and every physical delete must leave a tombstone. See
 * docs/sync-merge-semantics.md §1.2 and §4.2.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StampTest {
    private val f = Fixture()

    @Test fun ledgerWritesTakeContentStampsAndDeletesLeaveTombstones() = f.run {
        val id = f.ledger.createGroup("现金", isLiability = false, colorIndex = 0)
        val created = f.db.dao().group(id)!!
        assertTrue(created.changedAt > 0)
        assertTrue(created.changeId.isNotEmpty())

        f.now += 1_000
        f.ledger.updateGroup(id, "现金2", isLiability = false, colorIndex = 1)
        val updated = f.db.dao().group(id)!!
        assertTrue(updated.changedAt > created.changedAt)
        assertTrue(updated.changeId != created.changeId)

        f.ledger.deleteGroup(id)
        assertNotNull(f.db.dao().tombstone("groups", RowKey.of("groups", id)))
    }

    @Test fun diaryWeatherIsStateAndMoodIsContent() = f.run {
        val day = LocalDate.parse("2026-10-20")
        f.diary.saveBody(day, entryId = null, expectedRev = null, text = "正文")
        val before = f.diary.entryOn(day)!!

        f.now += 1_000
        val rev = f.diary.beginWeather(before.id)
        f.diary.writeWeather(before.id, rev, WeatherResult(1, 10.0, 20.0, "上海", 31.0, 121.0))
        val weather = f.diary.entryOn(day)!!
        assertEquals("weather must not steal the content version", before.changedAt, weather.changedAt)
        assertTrue("weather moves the state stamp", weather.stateAt > before.stateAt)

        f.now += 1_000
        f.diary.setMood(day, 4)
        assertTrue("mood is content", f.diary.entryOn(day)!!.changedAt > weather.changedAt)
    }

    @Test fun notePinnedIsStateAndTopicIsContent() = f.run {
        val topic = f.notes.createTopic("工作")
        val id = f.notes.create("正文", source = null)
        val before = f.notes.note(id)!!

        f.now += 1_000
        f.notes.setPinned(id, true)
        val pinned = f.notes.note(id)!!
        assertEquals("pinning must not steal the body version", before.changedAt, pinned.changedAt)
        assertTrue(pinned.stateAt > before.stateAt)

        f.now += 1_000
        f.notes.setTopic(id, topic)
        assertTrue("the topic is content", f.notes.note(id)!!.changedAt > pinned.changedAt)
    }

    @Test fun reminderDeliveryIsStateAndEditingIsContent() = f.run {
        val id = f.todos.create(
            TodoDraft(
                title = "任务",
                planDate = LocalDate.parse("2026-10-21"),
                remindTime = java.time.LocalTime.parse("09:00"),
            ),
        )
        val before = f.todos.todo(id)!!
        f.todos.markReminded(id, before.stateSeq)
        val reminded = f.todos.todo(id)!!
        assertEquals("delivery must not steal the content version", before.changedAt, reminded.changedAt)
        assertTrue(reminded.stateAt > before.stateAt)

        f.now += 1_000
        f.todos.setRemindBefore(id, 5)
        assertTrue(f.todos.todo(id)!!.changedAt > reminded.changedAt)
    }

    @Test fun uncheckingAHabitLeavesATombstone() = f.run {
        val habit = Habit.DEFAULTS.first()
        val day = f.habits.today().toString()
        assertTrue(f.habits.toggle(habit))
        val check = f.db.habitDao().row(day, habit.key)!!
        assertTrue(check.changedAt > 0)

        f.now += 1_000
        assertEquals(false, f.habits.toggle(habit))
        assertNotNull(f.db.dao().tombstone("habitChecks", RowKey.of("habitChecks", day, habit.key)))
    }

    @Test fun countdownPinnedIsStateAndDeleteLeavesATombstone() = run {
        val repo = CountdownRepository(f.db, f.gate, clock = { f.now })
        runBlocking(f.gate.bound()) {
            val id = repo.save(null, "生日", LocalDate.parse("2001-08-02"), yearly = true, pinned = false)
            val before = repo.get(id)!!
            f.now += 1_000
            repo.setPinned(id, true)
            val pinned = repo.get(id)!!
            assertEquals("pinning must not steal the content version", before.changedAt, pinned.changedAt)
            assertTrue(pinned.stateAt > before.stateAt)

            repo.delete(id)
            assertNotNull(f.db.dao().tombstone("countdowns", RowKey.of("countdowns", id)))
        }
    }

    @Test fun foodDeleteLeavesATombstoneAndUndoRestamps() = f.run {
        val id = f.food.save(
            FoodDraft(
                id = null, date = LocalDate.parse("2026-10-20"), name = "水煮蛋", per = null, amount = null,
                unit = null, energyCal = 70_000, inKj = false, proteinMg = null, fatMg = null, carbMg = null, sodiumMg = null,
            ),
        )
        val before = f.db.foodDao().entry(id)!!
        val gone = f.food.delete(id)!!
        assertNotNull(f.db.dao().tombstone("foodEntries", RowKey.of("foodEntries", id)))

        f.now += 1_000
        f.food.restore(gone)
        val restored = f.db.foodDao().entry(id)!!
        assertEquals(before.copy(changedAt = 0, changeId = ""), restored.copy(changedAt = 0, changeId = ""))
        assertTrue("undo is a new change", restored.changedAt > before.changedAt)
    }

    @Test fun clearingTheGoalKeepsItsStamp() = run {
        val repo = GoalRepository(f.db, f.gate, clock = { f.now })
        runBlocking(f.gate.bound()) {
            repo.set(12_345)
            val at = f.db.metaDao().get(GoalRepository.AT_KEY)!!.toLong()
            val id = f.db.metaDao().get(GoalRepository.CHANGE_ID_KEY)!!
            f.now += 1_000
            repo.set(null)
            assertEquals("", f.db.metaDao().get(GoalRepository.KEY))
            assertTrue(f.db.metaDao().get(GoalRepository.AT_KEY)!!.toLong() > at)
            assertTrue(f.db.metaDao().get(GoalRepository.CHANGE_ID_KEY)!! != id)
        }
    }
}
