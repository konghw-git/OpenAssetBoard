package com.assetboard.data

import android.app.Application
import android.net.Uri
import com.assetboard.domain.LedgerException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The notes tab's data layer: sessions, appends, topics, trash, backup and restore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NoteTest {
    private lateinit var f: Fixture
    private val repo get() = f.notes
    private val dao get() = f.db.noteDao()
    private val day = SourceRef.day(date("2026-10-12"))

    @Before fun setUp() {
        f = Fixture()
        f.settings.setBackupTreeUri(null)
        File(f.context.filesDir, "prerestore").deleteRecursively()
    }

    @After fun tearDown() = f.close()

    private fun CoroutineScope.session(note: Note? = null, onCreated: (String) -> Unit = {}) =
        NoteSession(repo, this, clock = { f.now }, onCreated = onCreated).apply { open(note) }

    private suspend fun note(body: String, topicId: String? = null) = repo.note(repo.create(body, null, topicId))!!

    private fun file(name: String, text: String) = Uri.fromFile(File(f.context.cacheDir, name).apply { writeText(text) })

    // ------------------------------------------------------------ body

    @Test fun emptyNewNoteCreatesNothing() = f.run {
        var created: String? = null
        val s = session { created = it }
        assertTrue(s.saveNow())
        s.onInput("x")
        s.onInput("")
        assertTrue(s.saveNow())
        assertTrue(dao.all().isEmpty())
        assertNull(created)
        s.close()
    }

    @Test fun firstWriteCreatesTheRowInTheChosenTopic() = f.run {
        val topic = repo.createTopic("投资")
        var created: String? = null
        val s = session { created = it }
        s.topicForNew = topic
        s.onInput("定投纪律\n跌了不停")
        assertTrue(s.saveNow())
        val n = dao.all().single()
        assertEquals(created, n.id)
        assertEquals(topic, n.topicId)
        assertEquals("定投纪律", n.title)
        assertEquals("跌了不停", NoteText.preview(n.body))
        s.close()
    }

    @Test fun clearedNoteStaysAsUntitled() = f.run {
        val n = note("会被清空")
        val s = session(n)
        s.onInput("")
        assertTrue(s.saveNow())
        val after = repo.note(n.id)!!
        assertNull(after.deletedAt)
        assertEquals("", after.body)
        assertEquals(NoteText.UNTITLED, after.title)
        s.close()
    }

    @Test fun pinTopicAndReviewNeverMakeTheEditorStale() = f.run {
        val topic = repo.createTopic("健身")
        val n = note("早睡的真正障碍")
        val s = session(n)
        val updated = repo.note(n.id)!!.updatedAt
        f.now += 60_000
        repo.setPinned(n.id, true)
        repo.setTopic(n.id, topic)
        repo.setReviewed(n.id)
        s.onInput("早睡的真正障碍\n是手机")
        assertTrue(s.saveNow())
        val after = repo.note(n.id)!!
        assertEquals(NoteSession.Status.READY, s.status.value)
        assertTrue(after.pinned)
        assertEquals(topic, after.topicId)
        assertNotNull(after.reviewedAt)
        assertTrue(dao.allDrafts().isEmpty())
        assertTrue(after.updatedAt > updated)
        s.close()
    }

    @Test fun pinningDoesNotMoveUpdatedAt() = f.run {
        val n = note("排序只看正文")
        f.now += 60_000
        repo.setPinned(n.id, true)
        assertEquals(n.updatedAt, repo.note(n.id)!!.updatedAt)
        assertEquals(n.bodyRev, repo.note(n.id)!!.bodyRev)
    }

    // ------------------------------------------------------------ append

    @Test fun appendWithoutASessionWritesTheRowAndTheSource() = f.run {
        val n = note("年度目标")
        val r = repo.append(n.id, NoteText.block("2026-10-12", "跑完半马"), day)
        assertEquals(AppendResult.Appended, r)
        assertEquals("年度目标\n\n— 2026-10-12\n跑完半马", repo.note(n.id)!!.body)
        assertEquals(listOf("2026-10-12"), dao.allSources().map { it.ref })
    }

    @Test fun appendToANoteWhoseEditorStillHasUnsavedText() = f.run {
        val n = note("读书")
        val s = NoteSession(repo, f.scope + f.gate.bound(), clock = { f.now }).apply { open(n) }
        s.onInput("读书\n用户还没存的字")
        // A save in flight and the append race; both go through the session.
        val saving = async(f.scope.coroutineContext + f.gate.bound()) { s.saveNow() }
        val r = repo.append(n.id, NoteText.block("2026-10-12", "摘的段落"), day)
        saving.await()
        assertEquals(AppendResult.Appended, r)
        val body = repo.note(n.id)!!.body
        assertTrue(body, body.startsWith("读书\n用户还没存的字"))
        assertTrue(body, body.endsWith("— 2026-10-12\n摘的段落"))
        assertTrue(dao.allDrafts().isEmpty())
        assertEquals(1, dao.allSources().size)
        assertEquals(body, s.text.value)
        assertFalse(s.dirty)
        s.close()
    }

    @Test fun appendToAnEndedSessionGoesToTheRow() = f.run {
        val n = note("旧会话")
        val s = session(n)
        // Written elsewhere: the session's next save conflicts and ends it.
        dao.updateBody(n.id, "别处改过", n.bodyRev, f.now)
        s.onInput("旧会话的字")
        assertFalse(s.saveNow())
        assertEquals(NoteSession.Status.ENDED, s.status.value)
        val r = repo.append(n.id, NoteText.block("2026-10-12", "追加"), day)
        assertEquals(AppendResult.Appended, r)
        assertEquals("别处改过\n\n— 2026-10-12\n追加", repo.note(n.id)!!.body)
        assertEquals("旧会话的字", s.text.value)
        assertEquals(1, dao.allSources().size)
        s.close()
    }

    @Test fun appendToAnUnsavedSessionGoesToTheRow() = f.run {
        val n = note("磁盘满了")
        val s = session(n)
        repo.failBodyWrites = true
        repo.failDraftWrites = true
        s.onInput("只在编辑器里")
        assertFalse(s.saveNow())
        assertEquals(NoteSession.Status.UNSAVED, s.status.value)
        repo.failBodyWrites = false
        repo.failDraftWrites = false
        val r = repo.append(n.id, NoteText.block("2026-10-12", "追加"), day)
        assertEquals(AppendResult.Appended, r)
        assertEquals("磁盘满了\n\n— 2026-10-12\n追加", repo.note(n.id)!!.body)
        assertEquals("只在编辑器里", s.text.value)
        s.close()
    }

    @Test fun appendThroughASessionWhoseSaveConflictsBecomesADraft() = f.run {
        val n = note("会冲突")
        val s = session(n)
        dao.updateBody(n.id, "别处改过", n.bodyRev, f.now)
        val r = repo.append(n.id, NoteText.block("2026-10-12", "追加块"), day)
        assertEquals(AppendResult.SavedAsDraft, r)
        assertTrue(dao.allDrafts().single().text.endsWith("追加块"))
        assertFalse(repo.note(n.id)!!.body.contains("追加块"))
        assertTrue(dao.allSources().isEmpty())
        s.close()
    }

    @Test fun appendThroughASessionDuringARestoreFails() = f.run {
        val n = note("恢复中")
        val s = session(n)
        f.gate.freeze()
        try {
            val r = repo.append(n.id, NoteText.block("2026-10-12", "追加"), day)
            assertTrue(r is AppendResult.Failed)
        } finally {
            f.gate.unfreeze()
        }
        s.close()
    }

    @Test fun appendToATrashedNoteFails() = f.run {
        val n = note("在回收站")
        repo.delete(n.id)
        val r = repo.append(n.id, NoteText.block("2026-10-12", "追加"), day)
        assertEquals(AppendResult.Failed("这篇笔记已被删除"), r)
        assertEquals("在回收站", repo.note(n.id)!!.body)
        assertTrue(dao.allSources().isEmpty())
    }

    @Test fun twoAppendsWhileSavingBothLandWithTheirSources() = f.run {
        val n = note("两次追加")
        val s = NoteSession(repo, f.scope + f.gate.bound(), clock = { f.now }).apply { open(n) }
        s.onInput("两次追加\n还没存")
        val ctx = f.scope.coroutineContext + f.gate.bound()
        val results = listOf(
            async(ctx) { s.saveNow() },
            async(ctx) { repo.append(n.id, NoteText.block("2026-10-12", "第一段"), SourceRef.day(date("2026-10-12"))) },
            async(ctx) { repo.append(n.id, NoteText.block("2026-10-13", "第二段"), SourceRef.day(date("2026-10-13"))) },
        ).awaitAll()
        assertEquals(listOf(true, AppendResult.Appended, AppendResult.Appended), results)
        val body = repo.note(n.id)!!.body
        assertTrue(body.contains("第一段") && body.contains("第二段"))
        assertEquals(setOf("2026-10-12", "2026-10-13"), dao.allSources().map { it.ref }.toSet())
        assertFalse(s.dirty)
        s.close()
    }

    @Test fun sameDayTwiceKeepsOneSource() = f.run {
        val n = note("同一天")
        repo.append(n.id, NoteText.block("2026-10-12", "一"), day)
        repo.append(n.id, NoteText.block("2026-10-12", "二"), day)
        assertEquals(1, dao.allSources().size)
    }

    @Test fun notesFromADayLeaveOutTheTrash() = f.run {
        val kept = repo.create("留着", day)
        val gone = repo.create("删掉", day)
        repo.delete(gone)
        assertEquals(listOf(kept), repo.observeFromDay(date("2026-10-12")).first().map { it.id })
    }

    // ------------------------------------------------------------ topics and drafts

    @Test fun topicNamesAreTrimmedAndUniqueIgnoringCase() = f.run {
        repo.createTopic(" Reading ")
        assertEquals("Reading", dao.topics().single().name)
        expectRule { repo.createTopic("reading") }
        expectRule { repo.createTopic("   ") }
        val other = repo.createTopic("跑步")
        expectRule { repo.renameTopic(other, "READING") }
        repo.renameTopic(other, "跑步 ")
    }

    @Test fun movingTopicsKeepsTheirOrderDense() = f.run {
        val a = repo.createTopic("A")
        val b = repo.createTopic("B")
        val c = repo.createTopic("C")
        repo.moveTopic(c, -1)
        assertEquals(listOf(a, c, b), dao.topics().map { it.id })
        repo.moveTopic(a, -1)
        assertEquals(listOf(0, 1, 2), dao.topics().map { it.sortOrder })
    }

    @Test fun deletingATopicUnsortsLiveAndTrashedNotes() = f.run {
        val t = repo.createTopic("会删")
        val live = note("活的", t).id
        val trashed = note("回收站里的", t).id
        repo.delete(trashed)
        assertEquals(1, repo.liveCountIn(t))
        repo.deleteTopic(t)
        assertNull(repo.note(live)!!.topicId)
        assertNull(repo.note(trashed)!!.topicId)
    }

    @Test fun draftBecomesANoteInItsOriginalTopic() = f.run {
        val t = repo.createTopic("投资")
        val n = note("原笔记", t)
        val draft = repo.saveDraft(n.id, "没存上的", "冲突")
        val id = repo.draftToNote(draft)
        assertEquals("没存上的", repo.note(id)!!.body)
        assertEquals(t, repo.note(id)!!.topicId)
        assertTrue(dao.allDrafts().isEmpty())
        expectRule { repo.draftToNote(draft) }
    }

    // ------------------------------------------------------------ trash

    @Test fun trashListsRestoresAndPurgesNotes() = f.run {
        val n = note("要删的笔记\n第二行")
        val batch = repo.delete(n.id)
        val listed = f.todos.trash.first().single()
        assertEquals(batch, listed.batch)
        assertEquals("要删的笔记", listed.notes.single().title)
        assertTrue(f.db.noteDao().observeLive().first().isEmpty())
        assertTrue(repo.restore(batch))
        assertEquals(listOf(n.id), f.db.noteDao().observeLive().first().map { it.id })
        assertTrue(f.todos.trash.first().isEmpty())

        repo.delete(n.id)
        f.todos.emptyTrash()
        assertNull(repo.note(n.id))

        val old = note("过期的")
        repo.delete(old.id)
        f.now += (TodoRepository.TRASH_DAYS + 1) * 24 * 3600 * 1000
        f.todos.purgeExpired()
        assertNull(repo.note(old.id))
    }

    @Test fun deletingMakesTheOpenEditorStale() = f.run {
        val n = note("删了还在打字")
        val s = session(n)
        repo.delete(n.id)
        s.onInput("删了还在打字，又打了几个")
        assertFalse(s.saveNow())
        assertEquals(NoteSession.Status.ENDED, s.status.value)
        assertEquals("这篇笔记已被删除", dao.allDrafts().single().reason)
        s.close()
    }

    // ------------------------------------------------------------ backup and restore

    private suspend fun seedNotes(): String {
        val t = repo.createTopic("投资")
        val id = repo.create("定投纪律", day, t)
        repo.saveDraft(id, "草稿", "冲突")
        return id
    }

    @Test fun backupRoundTripsNotes() = f.run {
        val id = seedNotes()
        val json = f.backup.exportJson()
        val uri = file("notes.json", json)
        repo.delete(id)
        repo.createTopic("备份之后")
        val preview = f.backup.inspect(uri)
        assertEquals(1, preview.notes)
        assertEquals(0, preview.currentNotes)
        f.backup.restore(uri)
        assertEquals("定投纪律", repo.note(id)!!.body)
        assertNull(repo.note(id)!!.deletedAt)
        assertEquals(listOf("投资"), dao.allTopics().map { it.name })
        assertEquals(1, dao.allSources().size)
        assertEquals(1, dao.allDrafts().size)
    }

    @Test fun formatFourBackupEmptiesTheNotes() = f.run {
        seedNotes()
        val v5 = Json.parseToJsonElement(f.backup.exportJson()).jsonObject
        val v4 = JsonObject(
            v5.filterKeys { it !in setOf("noteTopics", "notes", "noteSources", "noteDrafts") }
                .mapValues { (k, v) -> if (k == "formatVersion") JsonPrimitive(4) else if (k == "schemaVersion") JsonPrimitive(4) else v },
        )
        val uri = file("v4.json", v4.toString())
        val preview = f.backup.inspect(uri)
        assertEquals(4, preview.formatVersion)
        assertEquals(0, preview.notes)
        assertEquals(1, preview.currentNotes)
        f.backup.restore(uri)
        assertTrue(dao.all().isEmpty() && dao.allTopics().isEmpty() && dao.allSources().isEmpty() && dao.allDrafts().isEmpty())
    }

    @Test fun backupWithASourceOfAnUnknownKindIsRefused() = f.run {
        seedNotes()
        val text = f.backup.exportJson().replace("\"kind\":\"DAY\"", "\"kind\":\"MONTH\"")
        try {
            f.backup.restore(file("bad.json", text))
            fail("must be refused")
        } catch (e: LedgerException) {
            assertEquals("备份里有未知的笔记来源「MONTH」", e.message)
        }
        assertEquals(1, dao.all().size)
    }

    @Test fun restoreCommitsANewNoteFirst() = f.run {
        val uri = file("r-new.json", f.backup.exportJson())
        val s = NoteSession(repo, this + f.gate.bound(), clock = { f.now }).apply { open(null) }
        s.onInput("恢复前新写的笔记")
        withTimeout(10_000) { f.backup.restore(uri) }
        val copy = File(f.context.filesDir, "prerestore").listFiles()!!.single().readText()
        assertTrue(copy.contains("恢复前新写的笔记"))
        s.close()
    }

    @Test fun restoreCommitsAnEditAtItsVersionWithItsSources() = f.run {
        val n = note("旧正文")
        val uri = file("r-edit.json", f.backup.exportJson())
        val s = NoteSession(repo, this + f.gate.bound(), clock = { f.now }).apply { open(n) }
        // An append that met a restore: its text and source wait in the session.
        f.gate.freeze()
        val r = try {
            repo.append(n.id, NoteText.block("2026-10-12", "等恢复的追加"), day)
        } finally {
            f.gate.unfreeze()
        }
        assertTrue(r is AppendResult.Failed)
        assertEquals(listOf(day), (s.pendingForRestore() as PendingText.Note).sources)
        withTimeout(10_000) { f.backup.restore(uri) }
        val copy = File(f.context.filesDir, "prerestore").listFiles()!!.single().readText()
        assertTrue(copy.contains("旧正文\\n\\n— 2026-10-12\\n等恢复的追加"))
        assertTrue(copy.contains("\"ref\":\"2026-10-12\""))
        assertFalse(copy.contains("恢复备份前保存冲突"))
        s.close()
    }

    @Test fun restoreTurnsAStaleEditIntoADraft() = f.run {
        val n = note("会过期")
        val uri = file("r-stale.json", f.backup.exportJson())
        val s = NoteSession(repo, this + f.gate.bound(), clock = { f.now }).apply { open(n) }
        dao.updateBody(n.id, "别处改过", n.bodyRev, f.now)
        s.onInput("过期会话的字")
        withTimeout(10_000) { f.backup.restore(uri) }
        val copy = File(f.context.filesDir, "prerestore").listFiles()!!.single().readText()
        assertTrue(copy.contains("恢复备份前保存冲突"))
        assertTrue(copy.contains("过期会话的字"))
        s.close()
    }

    @Test fun noteTextThatCannotLandCancelsTheRestore() = f.run {
        val uri = file("r-cancel.json", f.backup.exportJson())
        val s = NoteSession(repo, this + f.gate.bound(), clock = { f.now }).apply { open(null) }
        s.onInput("无处可写")
        repo.failBodyWrites = true
        repo.failDraftWrites = true
        try {
            f.backup.restore(uri)
            fail("restore should be cancelled")
        } catch (e: LedgerException) {
            assertEquals("有未保存的笔记，恢复已取消", e.message)
        } finally {
            repo.failBodyWrites = false
            repo.failDraftWrites = false
        }
        assertFalse(f.gate.frozen.value)
        assertEquals("无处可写", s.text.value)
        assertEquals(NoteSession.Status.READY, s.status.value)
        s.close()
    }

    // ------------------------------------------------------------ text rules

    // ------------------------------------------------------------ hand-set order

    private suspend fun order() = repo.observeNotes().first().map { it.body }

    @Test fun newNotesGoToTheTopAndEditsDoNotMoveThem() = f.run {
        val a = note("甲")
        note("乙")
        f.now += 1_000
        repo.append(a.id, "补充", day)
        val s = session()
        s.onInput("丙")
        assertTrue(s.saveNow())
        s.close()
        assertEquals(listOf("丙", "乙", "甲\n\n补充"), order())
    }

    @Test fun reorderingASubsetKeepsTheOthersInPlace() = f.run {
        val t = repo.createTopic("投资")
        val a = note("甲", t)
        val b = note("乙")
        val c = note("丙", t)
        note("丁")
        // On screen: 丁 丙 乙 甲; the topic's view shows 丙 甲 and swaps them.
        assertEquals(listOf("丁", "丙", "乙", "甲"), order())
        repo.reorder(listOf(a.id, c.id))
        assertEquals(listOf("丁", "甲", "乙", "丙"), order())
        repo.reorder(listOf(b.id, a.id))
        assertEquals(listOf("丁", "乙", "甲", "丙"), order())
    }

    @Test fun reorderSpreadsTiesFirst() = f.run {
        val a = note("甲")
        f.now += 10
        val b = note("乙")
        dao.setPosition(a.id, 0)
        dao.setPosition(b.id, 0)
        repo.reorder(listOf(a.id, b.id))
        assertEquals(listOf("甲", "乙"), order())
        assertEquals(2, dao.all().map { it.position }.toSet().size)
    }

    @Test fun formatFiveBackupTakesTheOrderItShowed() = f.run {
        val a = note("甲")
        f.now += 10
        val b = note("乙")
        repo.reorder(listOf(a.id, b.id))
        val v6 = Json.parseToJsonElement(f.backup.exportJson()).jsonObject
        val v5 = JsonObject(v6.mapValues { (k, v) -> if (k == "formatVersion") JsonPrimitive(5) else v })
        f.backup.restore(file("v5.json", v5.toString()))
        // Format 5 had no order of its own: most recently updated first.
        assertEquals(listOf("乙", "甲"), order())
    }

    @Test fun extractedNotesAlwaysHaveATitleLine() {
        val d = date("2026-10-12")
        assertEquals("10月12日 日记摘录\n\n一段很长的日记\n\n第二段", com.assetboard.ui.notes.extractBody(" ", d, listOf("一段很长的日记", "第二段")).replace(Regex("^\\d+年"), ""))
        assertEquals("定投\n\n一段", com.assetboard.ui.notes.extractBody(" 定投 ", d, listOf("一段")))
    }

    // ------------------------------------------------------------ today's review

    private val week = NoteRepository.REVIEW_MIN_AGE_MS

    @Test fun reviewPicksTheNoteLeftAloneLongestAndKeepsItAllDay() = f.run {
        val old = note("老笔记")
        f.now += 1_000
        val older = note("更老的也会被改")
        f.now += 1_000
        repo.setPinned(note("置顶的").id, true)
        f.now += 1_000
        repo.append(older.id, "补一句", day)
        val today = date("2026-10-20")
        // Nothing is a week old yet.
        assertNull(repo.reviewPick(today))
        f.now += week + 10_000
        assertEquals(old.id, repo.reviewPick(today)!!.id)
        // Opening it does not change today's pick, even though another note is now older.
        repo.setReviewed(old.id)
        assertEquals(old.id, repo.reviewPick(today)!!.id)
        // Tomorrow the other one comes up.
        assertEquals(older.id, repo.reviewPick(today.plusDays(1))!!.id)
    }

    @Test fun reviewPicksAgainWhenItsNoteIsGoneOrPinned() = f.run {
        val a = note("甲")
        f.now += 1_000
        val b = note("乙")
        f.now += 1_000
        val c = note("丙")
        f.now += week + 1
        val today = date("2026-10-20")
        assertEquals(a.id, repo.reviewPick(today)!!.id)
        repo.delete(a.id)
        assertEquals(b.id, repo.reviewPick(today)!!.id)
        repo.setPinned(b.id, true)
        assertEquals(c.id, repo.reviewPick(today)!!.id)
        // Put away for today: no card until tomorrow, and it goes to the back of the queue.
        repo.dismissReview(today, c.id)
        assertNull(repo.reviewPick(today))
        f.now += week + 1
        repo.restore(dao.all().first { it.id == a.id }.deleteBatch!!)
        assertEquals(a.id, repo.reviewPick(today.plusDays(1))!!.id)
    }

    @Test fun textRules() {
        assertEquals("标题", NoteText.title("\n  标题  \n正文"))
        assertEquals(NoteText.UNTITLED, NoteText.title(" \n "))
        assertEquals("正文 第二行", NoteText.preview("标题\n\n正文\n第二行"))
        assertEquals(listOf("一段\n同一段", "二段"), NoteText.paragraphs("一段\n同一段\n\n二段\n"))
        assertEquals(listOf("一行", "二行"), NoteText.paragraphs("一行\n二行"))
        assertEquals("块", NoteText.join("  \n", "块"))
        assertEquals("正文\n\n块", NoteText.join("正文\n\n", "块"))
        assertEquals(listOf("#投资", "#早睡"), NoteText.TAG.findAll("#投资 和 #早睡，不算 a#b 或 ##").map { it.value }.toList())
    }
}
