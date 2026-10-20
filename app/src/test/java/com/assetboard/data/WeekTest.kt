package com.assetboard.data

import android.app.Application
import android.net.Uri
import com.assetboard.domain.Habit
import com.assetboard.domain.LedgerException
import com.assetboard.domain.WeekStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Weekly reviews: the week's rules, its session, the trash, backup and restore, and its numbers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WeekTest {
    private lateinit var f: Fixture
    private val repo get() = f.weeks
    private val dao get() = f.db.weekDao()
    private val week = date("2026-10-19")

    @Before fun setUp() {
        f = Fixture()
        f.settings.setBackupTreeUri(null)
        File(f.context.filesDir, "prerestore").deleteRecursively()
    }

    @After fun tearDown() = f.close()

    private fun CoroutineScope.session(start: java.time.LocalDate = week, review: WeeklyReview? = null) =
        WeekSession(repo, start, this + f.gate.bound(), clock = { f.now }).apply { open(review) }

    private fun file(name: String, text: String) = Uri.fromFile(File(f.context.cacheDir, name).apply { writeText(text) })

    private suspend fun written(text: String, start: java.time.LocalDate = week): WeeklyReview {
        repo.saveBody(start, null, null, text)
        return repo.reviewOf(start)!!
    }

    @Test fun weeksStartOnMondayAndUseTheWeekBasedYear() {
        assertEquals(date("2026-10-19"), Weeks.start(date("2026-10-25")))
        assertEquals(date("2026-10-19"), Weeks.start(date("2026-10-19")))
        assertEquals("2026 年第 43 周", Weeks.title(date("2026-10-19")))
        // 2027-01-01 is a Friday in 2026's last week.
        assertEquals("2026 年第 53 周", Weeks.title(Weeks.start(date("2027-01-01"))))
        assertEquals("9月28日 – 10月4日", Weeks.range(date("2026-09-28")))
        assertEquals("10月19日 – 25日", Weeks.range(week))
    }

    @Test fun firstWriteCreatesTheRowAndASecondEditorOfTheWeekGetsADraft() = f.run {
        val a = session()
        val b = session()
        a.onInput("做成了：论文初稿")
        assertTrue(a.saveNow())
        b.onInput("另一处写的")
        assertFalse(b.saveNow())
        assertEquals(WeekSession.Status.ENDED, b.status.value)
        assertEquals("做成了：论文初稿", repo.reviewOf(week)!!.body)
        val drafts = repo.observeDrafts(week).first()
        assertEquals(listOf("另一处写的"), drafts.map { it.text })
        assertEquals("这一周已有另一篇复盘", drafts.single().reason)
        a.close()
        b.close()
    }

    @Test fun deletedReviewGoesToTheTrashAndComesBackOrReplaces() = f.run {
        val r = written("旧的")
        val batch = repo.delete(r.id)
        assertEquals(listOf(r.id), f.todos.trash.first().single().weeks.map { it.id })
        written("新写的")
        val current = repo.restoreConflict(batch)!!
        assertEquals("新写的", current.body)
        assertFalse(repo.restore(batch, replace = false))
        assertEquals("新写的", repo.reviewOf(week)!!.body)
        assertTrue(repo.restore(batch, replace = true))
        assertEquals("旧的", repo.reviewOf(week)!!.body)
        // The one it replaced is in the trash now, as its own batch.
        assertEquals(listOf("新写的"), f.todos.trash.first().flatMap { it.weeks }.map { it.body })
        f.todos.emptyTrash()
        assertEquals(1, dao.all().size)
    }

    @Test fun backupRoundTripsReviewsAndDrafts() = f.run {
        val r = written("复盘正文")
        repo.saveDraft(week, r.id, "草稿", "冲突")
        f.notes.create("周复盘里的想法", SourceRef.week(week))
        val uri = file("weeks.json", f.backup.exportJson())
        repo.delete(r.id)
        val preview = f.backup.inspect(uri)
        assertEquals(1, preview.weeks)
        assertEquals(0, preview.currentWeeks)
        f.backup.restore(uri)
        assertEquals("复盘正文", repo.reviewOf(week)!!.body)
        assertEquals(1, dao.allDrafts().size)
        assertEquals(listOf(SourceRef.WEEK), f.db.noteDao().allSources().map { it.kind })
    }

    @Test fun formatFiveBackupEmptiesTheReviews() = f.run {
        written("会被清空")
        val v6 = Json.parseToJsonElement(f.backup.exportJson()).jsonObject
        val v5 = JsonObject(v6.filterKeys { it !in setOf("weeklyReviews", "weeklyDrafts") }.mapValues { (k, v) -> if (k == "formatVersion") JsonPrimitive(5) else v })
        val uri = file("v5.json", v5.toString())
        assertEquals(1, f.backup.inspect(uri).currentWeeks)
        f.backup.restore(uri)
        assertTrue(dao.all().isEmpty())
    }

    @Test fun backupWithTwoLiveReviewsOfAWeekIsRefused() = f.run {
        written("一篇")
        val text = f.backup.exportJson()
        val obj = Json.parseToJsonElement(text).jsonObject
        val one = obj["weeklyReviews"]!!.toString().removePrefix("[").removeSuffix("]")
        val two = one.replaceFirst(Regex("\"id\":\"[^\"]+\""), "\"id\":\"another\"")
        val bad = text.replace("\"weeklyReviews\":[$one]", "\"weeklyReviews\":[$one,$two]")
        try {
            f.backup.restore(file("bad.json", bad))
            fail("must be refused")
        } catch (e: LedgerException) {
            assertEquals("备份里同一周有多篇复盘", e.message)
        }
    }

    @Test fun restoreCommitsAnOpenReviewFirstOrCancels() = f.run {
        val uri = file("r.json", f.backup.exportJson())
        val s = session()
        s.onInput("恢复前写的复盘")
        withTimeout(10_000) { f.backup.restore(uri) }
        val copy = File(f.context.filesDir, "prerestore").listFiles()!!.single().readText()
        assertTrue(copy.contains("恢复前写的复盘"))
        s.close()

        val t = session()
        t.onInput("无处可写")
        repo.failBodyWrites = true
        repo.failDraftWrites = true
        try {
            f.backup.restore(uri)
            fail("restore should be cancelled")
        } catch (e: LedgerException) {
            assertEquals("有未保存的周复盘，恢复已取消", e.message)
        } finally {
            repo.failBodyWrites = false
            repo.failDraftWrites = false
        }
        assertEquals("无处可写", t.text.value)
        t.close()
    }

    @Test fun notesFromTheWeekComeFromItsDaysAndItsReview() = f.run {
        f.notes.create("周三的", SourceRef.day(date("2026-10-21")))
        f.notes.create("复盘的", SourceRef.week(week))
        f.notes.create("上周日的", SourceRef.day(date("2026-10-18")))
        f.notes.create("下周的复盘", SourceRef.week(week.plusWeeks(1)))
        assertEquals(setOf("周三的", "复盘的"), f.notes.observeFromWeek(week).first().map { it.body }.toSet())
    }

    @Test fun weekStats() {
        val zone = f.zone
        fun at(d: String) = f.at(d, "09:00")
        val todos = listOf(
            Todo(id = "a", kind = TodoKind.ORDINARY, title = "a", completedAt = at("2026-10-20"), createdAt = 0),
            Todo(id = "b", kind = TodoKind.COMPLETION, title = "b", completedAt = at("2026-10-25"), createdAt = 0),
            Todo(id = "c", kind = TodoKind.ORDINARY, title = "c", completedAt = at("2026-10-26"), createdAt = 0),
            Todo(id = "d", kind = TodoKind.ORDINARY, title = "d", completedAt = at("2026-10-21"), deletedAt = 1, createdAt = 0),
        )
        fun weigh(d: String, g: Long) = BodyMeasurement("m$d", d, 0, g, null, null, 0, 0)
        val weights = listOf(weigh("2026-10-13", 80_000), weigh("2026-10-15", 80_400), weigh("2026-10-20", 79_800), weigh("2026-10-22", 79_600))
        val checks = listOf(HabitCheck("2026-10-19", Habit.WORKOUT.key, 0), HabitCheck("2026-10-21", Habit.WORKOUT.key, 0), HabitCheck("2026-10-18", Habit.WORKOUT.key, 0))
        val worth = listOf(at("2026-10-10") to 100_000L, at("2026-10-21") to 130_000L, at("2026-10-23") to 125_000L)
        val s = WeekStats.of(week, date("2026-10-22"), listOf(date("2026-10-19"), date("2026-10-22"), date("2026-10-23")), checks, todos, weights, worth, zone)
        // Four days so far; a day after today is not counted.
        assertEquals(2, s.diaryDays)
        assertEquals(4, s.diaryOf)
        assertEquals(2, s.habits[Habit.WORKOUT])
        assertEquals(2, s.todosDone)
        // This week's last 7-day average (10-16..10-22: 79.8, 79.6) minus last week's (10-09..10-15: 80.0, 80.4).
        assertEquals(79_700L - 80_200L, s.weightChangeG)
        assertEquals(125_000L, s.netWorthNow)
        assertEquals(25_000L, s.netWorthChange)
        val empty = WeekStats.of(week.plusWeeks(1), date("2026-10-22"), emptyList(), emptyList(), emptyList(), emptyList(), worth, zone)
        assertEquals(0, empty.diaryOf)
        assertFalse(empty.hasWeights)
        assertNull(empty.netWorthNow)
    }
}
