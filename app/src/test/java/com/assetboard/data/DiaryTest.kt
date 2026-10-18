package com.assetboard.data

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** Group D of the design note, plus T6. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DiaryTest {
    private lateinit var f: Fixture
    private val repo get() = f.diary

    @Before fun setUp() {
        f = Fixture()
    }

    @After fun tearDown() = f.close()

    private fun CoroutineScope.session(day: String, onCreated: (String) -> Unit = {}): DiarySession {
        val d = date(day)
        val s = DiarySession(repo, d, this, clock = { f.now }, onCreated = onCreated)
        return s
    }

    private suspend fun DiarySession.load() = apply { open(repo.entryOn(date)) }

    @Test fun d1_openingWithoutTypingCreatesNothing() = f.run {
        var weather = 0
        val s = session("2026-10-20") { weather++ }.load()
        assertTrue(s.saveNow())
        assertNull(repo.entryOn(date("2026-10-20")))
        assertEquals(0, weather)
        s.close()
    }

    @Test fun firstWriteCreatesTheEntryAndAsksForWeather() = f.run {
        var weather = 0
        val s = session("2026-10-20") { weather++ }.load()
        s.onInput("今天")
        s.saveNow()
        assertEquals("今天", repo.entryOn(date("2026-10-20"))!!.body)
        s.onInput("今天下雨")
        s.saveNow()
        assertEquals(1, weather)
        s.close()
    }

    @Test fun d2_deletedEntryNeverComesBack() = f.run {
        val s = session("2026-10-20").load()
        s.onInput("abc")
        s.saveNow() // the delete menu saves first
        val id = s.entryId!!
        repo.delete(id)
        s.onInput("abcd")
        assertFalse(s.saveNow())
        assertNull(repo.entryOn(date("2026-10-20")))
        assertNotNull(repo.entry(id)!!.deletedAt)
        s.close()
    }

    @Test fun d3_weatherDoesNotTouchTheBody() = f.run {
        val s = session("2026-10-20").load()
        s.onInput("早上")
        s.saveNow()
        val rev = repo.beginWeather(s.entryId!!)
        s.onInput("早上出门")
        assertTrue(repo.writeWeather(s.entryId!!, rev, WeatherResult(61, 14.0, 19.0, "上海", 31.2, 121.5)))
        assertTrue(s.saveNow())
        val e = repo.entryOn(date("2026-10-20"))!!
        assertEquals("早上出门", e.body)
        assertEquals(61, e.weatherCode)
        s.close()
    }

    @Test fun d4_switchingDaysSavesToTheOldDay() = f.run {
        val s = session("2026-10-18").load()
        s.onInput("写给 18 号")
        s.saveNow() // closing the session on a date switch flushes it
        s.close()
        val next = session("2026-10-19").load()
        assertEquals("写给 18 号", repo.entryOn(date("2026-10-18"))!!.body)
        assertNull(repo.entryOn(date("2026-10-19")))
        next.close()
    }

    @Test fun d10_laterSubmissionWins() = f.run {
        val s = session("2026-10-20").load()
        s.onInput("A")
        val a = launch { s.saveNow() }
        s.onInput("B")
        val b = launch { s.saveNow() }
        a.join()
        b.join()
        assertEquals("B", repo.entryOn(date("2026-10-20"))!!.body)
        s.close()
    }

    @Test fun d11_staleSessionAfterDeleteAndRestoreBecomesADraft() = f.run {
        val s = session("2026-10-20").load()
        s.onInput("第一版")
        s.saveNow()
        val batch = repo.delete(s.entryId!!)
        assertTrue(repo.restore(batch, replace = false))
        s.onInput("第一版，旧会话继续写")
        assertFalse(s.saveNow())
        assertEquals("第一版", repo.entryOn(date("2026-10-20"))!!.body)
        assertEquals(DiarySession.Status.ENDED, s.status.value)
        assertEquals("第一版，旧会话继续写", f.db.diaryDao().allDrafts().single().text)
        s.close()
    }

    @Test fun d12_lateWeatherForAnEarlierCityIsDropped() = f.run {
        val s = session("2026-10-20").load()
        s.onInput("x")
        s.saveNow()
        val id = s.entryId!!
        val a = repo.beginWeather(id)
        val b = repo.beginWeather(id)
        assertTrue(repo.writeWeather(id, b, WeatherResult(0, 10.0, 20.0, "B", 1.0, 1.0)))
        assertFalse(repo.writeWeather(id, a, WeatherResult(3, 5.0, 8.0, "A", 2.0, 2.0)))
        assertEquals("B", repo.entry(id)!!.city)
        s.close()
    }

    @Test fun d13_draftsTravelWithTheBackup() = f.run {
        val s = session("2026-10-20").load()
        s.onInput("正文")
        s.saveNow()
        repo.restore(repo.delete(s.entryId!!), replace = false)
        s.onInput("正文和冲突的那段")
        s.saveNow()
        val file = File(f.context.cacheDir, "d13.json").apply { writeText(f.backup.exportJson()) }
        s.close()

        val other = Fixture()
        try {
            other.run { other.backup.restore(Uri.fromFile(file)) }
            other.run {
                val draft = other.db.diaryDao().allDrafts().single()
                assertEquals("正文和冲突的那段", draft.text)
                // Append to the body from a fresh session, then drop the draft.
                val ns = DiarySession(other.diary, date("2026-10-20"), this, clock = { other.now })
                ns.open(other.diary.entryOn(date("2026-10-20")))
                ns.onInput(ns.text.value + "\n" + draft.text)
                assertTrue(ns.saveNow())
                other.diary.discardDraft(draft.id)
                assertEquals("正文\n正文和冲突的那段", other.diary.entryOn(date("2026-10-20"))!!.body)
                ns.close()
            }
        } finally {
            other.close()
        }
    }

    @Test fun d14_d15_whenNothingCanBeWrittenTheTextStaysInTheEditor() = f.run {
        val s = session("2026-10-20").load()
        repo.failBodyWrites = true
        repo.failDraftWrites = true
        val messages = mutableListOf<String>()
        val collector = launch { s.messages.collect { messages += it } }
        s.onInput("写不进去的字")
        assertFalse(s.saveNow())
        assertEquals(DiarySession.Status.UNSAVED, s.status.value)
        assertEquals("写不进去的字", s.text.value)
        // Typing on keeps the red state; no further save is attempted (D15).
        s.onInput("写不进去的字，又写了一分钟")
        assertFalse(s.saveNow())
        assertEquals(DiarySession.Status.UNSAVED, s.status.value)
        delay(50)
        assertTrue(messages.isEmpty())
        assertNull(repo.entryOn(date("2026-10-20")))
        collector.cancel()
        s.close()
    }

    @Test fun t6_restoringOverANewerEntryOfTheSameDay() = f.run {
        val day = date("2026-10-19")
        val old = session("2026-10-19").load()
        old.onInput("旧的")
        old.saveNow()
        val batch = repo.delete(old.entryId!!)
        old.close()
        val fresh = session("2026-10-19").load()
        fresh.onInput("新的")
        fresh.saveNow()
        fresh.close()
        assertEquals("新的", repo.restoreConflict(batch)!!.body)
        assertFalse(repo.restore(batch, replace = false))
        assertEquals("新的", repo.entryOn(day)!!.body)
        assertTrue(repo.restore(batch, replace = true))
        assertEquals("旧的", repo.entryOn(day)!!.body)
        assertEquals(1, f.db.diaryDao().all().count { it.deletedAt != null && it.body == "新的" })
    }

    @Test fun search() = f.run {
        for ((d, text) in listOf("2026-10-01" to "国庆去爬山", "2026-10-02" to "100% 开心", "2026-10-03" to "在家")) {
            val s = session(d).load()
            s.onInput(text)
            s.saveNow()
            s.close()
        }
        assertEquals(listOf("2026-10-01"), repo.search("爬山").map { it.date })
        assertEquals(listOf("2026-10-02"), repo.search("100%").map { it.date })
        assertTrue(repo.search("  ").isEmpty())
    }
}

/** D9 on virtual time with a recording store: the gap between commits while typing. */
@OptIn(ExperimentalCoroutinesApi::class)
class AutosaveTimingTest {
    private class RecordingStore(val clock: () -> Long) : DiaryStore {
        val commits = mutableListOf<Long>()
        var rev = 0L
        override suspend fun saveBody(date: LocalDate, entryId: String?, expectedRev: Long?, text: String): BodySave {
            delay(30) // a slow transaction
            commits += clock()
            return BodySave.Saved("e", ++rev, created = entryId == null)
        }
        override suspend fun saveDraft(date: LocalDate, entryId: String?, text: String, reason: String) = "d"
        override fun register(editor: DiaryEditorHandle) {}
        override fun unregister(editor: DiaryEditorHandle) {}
    }

    private fun TestScope.typing(intervalMs: Long, totalMs: Long): List<Long> {
        val store = RecordingStore { testScheduler.currentTime }
        val s = DiarySession(store, LocalDate.of(2026, 10, 20), backgroundScope, clock = { testScheduler.currentTime })
        s.open(null)
        var text = ""
        var t = 0L
        while (t < totalMs) {
            text += "字"
            s.onInput(text)
            testScheduler.advanceTimeBy(intervalMs)
            runCurrent()
            t += intervalMs
        }
        return store.commits.toList()
    }

    @Test fun d9_commitsAtMostFiveSecondsApartWhileTyping() = runTest {
        val commits = typing(intervalMs = 700, totalMs = 30_000)
        // "Force stop" at 30 s: whatever was not committed by then is lost.
        assertTrue("first commit at ${commits.first()}", commits.first() <= 5_000)
        val gaps = commits.zipWithNext { a, b -> b - a }
        assertTrue("gaps $gaps", gaps.all { it <= 5_000 })
        assertTrue("last commit at ${commits.last()}", 30_000 - commits.last() <= 5_000)
    }

    @Test fun pauseOfOneSecondSaves() = runTest {
        val store = RecordingStore { testScheduler.currentTime }
        val s = DiarySession(store, LocalDate.of(2026, 10, 20), backgroundScope, clock = { testScheduler.currentTime })
        s.open(null)
        s.onInput("a")
        testScheduler.advanceTimeBy(900)
        runCurrent()
        assertTrue(store.commits.isEmpty())
        testScheduler.advanceTimeBy(200)
        advanceUntilIdle()
        assertEquals(1, store.commits.size)
    }
}
