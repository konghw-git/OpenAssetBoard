package com.assetboard.data

import android.app.Application
import android.net.Uri
import androidx.room.withTransaction
import com.assetboard.domain.todo.RepeatRule
import com.assetboard.domain.todo.RepeatType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Group R of the design note: the restore coordinator and the write gate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RestoreTest {
    private lateinit var f: Fixture
    private val codec = Json { encodeDefaults = true }

    @Before fun setUp() {
        f = Fixture()
        f.settings.setBackupTreeUri(null)
        File(f.context.filesDir, "prerestore").deleteRecursively()
    }

    @After fun tearDown() = f.close()

    private fun file(name: String, text: String) = Uri.fromFile(File(f.context.cacheDir, name).apply { writeText(text) })

    private suspend fun seed() {
        val g = f.ledger.createGroup("储蓄", false, 0)
        f.ledger.createAccount(AccountDraft(g, "银行"), 0, 100_00, null)
        val list = f.todos.createList("论文")
        f.todos.create(TodoDraft("写引言", listId = list))
        val rep = f.todos.create(TodoDraft("跑步", planDate = date("2026-10-20")))
        f.todos.setRepeat(rep, RepeatRule(RepeatType.DAILY))
        f.todos.complete(rep, f.todos.todo(rep)!!.stateSeq)
        f.todos.delete(f.todos.create(TodoDraft("进回收站")))
        f.habits.toggle(com.assetboard.domain.Habit.WORKOUT)
        val s = DiarySession(f.diary, date("2026-10-20"), f.scope + f.gate.bound(), clock = { f.now })
        s.open(null)
        s.onInput("今天写了日记")
        s.saveNow()
        s.close()
    }

    private suspend fun counts() = listOf(
        f.db.dao().accounts().size, f.db.todoDao().allTodos().size, f.db.diaryDao().all().size, f.db.diaryDao().allDrafts().size,
    )

    private fun localCopies() = File(f.context.filesDir, "prerestore").listFiles().orEmpty().toList()

    // ------------------------------------------------------------

    @Test fun r1_formatOneBackupWipesTodosAndDiary() = f.run {
        seed()
        val v2 = codec.parseToJsonElement(f.backup.exportJson()).jsonObject
        val v1 = JsonObject(
            v2.filterKeys { it !in setOf("todoLists", "todoSections", "todos", "checklistItems", "diaryEntries", "diaryDrafts", "habitChecks") }
                .mapValues { (k, v) -> if (k == "formatVersion") kotlinx.serialization.json.JsonPrimitive(1) else v },
        )
        val uri = file("v1.json", v1.toString())
        val preview = f.backup.inspect(uri)
        assertEquals(1, preview.formatVersion)
        assertEquals(0, preview.todos)
        assertEquals(2, preview.currentTodos)
        assertEquals(1, preview.currentDiaries)
        f.backup.restore(uri)
        assertEquals(1, f.db.dao().accounts().size)
        assertTrue(f.db.todoDao().allTodos().isEmpty())
        assertTrue(f.db.diaryDao().all().isEmpty())
    }

    @Test fun r2_failedTransactionChangesNothing() = f.run {
        seed()
        val uri = file("r2.json", f.backup.exportJson())
        f.todos.create(TodoDraft("备份之后"))
        val before = counts()
        val epoch = f.gate.epochNow()
        f.backup.failInsideRestoreTransaction = true
        try {
            f.backup.restore(uri)
            fail("restore should fail")
        } catch (e: Exception) {
            // expected
        }
        assertEquals(before, counts())
        assertEquals(epoch, f.gate.epochNow())
        assertTrue(f.effectCalls.isEmpty())
        assertFalse(f.gate.frozen.value)
        f.todos.create(TodoDraft("闸门已解冻"))
    }

    @Test fun r3_unsavedDiaryTextGoesThroughTheCoordinator() = f.run {
        seed()
        val uri = file("r3.json", f.backup.exportJson())
        val s = DiarySession(f.diary, date("2026-10-21"), this + f.gate.bound(), clock = { f.now })
        s.open(null)
        s.onInput("恢复前没来得及保存")
        withTimeout(10_000) { f.backup.restore(uri) }
        val copy = localCopies().single().readText()
        assertTrue(copy.contains("恢复前没来得及保存"))
        assertEquals(1, f.restoredCount)
        s.close()
    }

    @Test fun r3b_changedEntryBecomesADraft() = f.run {
        seed()
        val uri = file("r3b.json", f.backup.exportJson())
        val day = date("2026-10-20")
        val stale = DiarySession(f.diary, day, this + f.gate.bound(), clock = { f.now })
        stale.open(f.diary.entryOn(day))
        val other = DiarySession(f.diary, day, this + f.gate.bound(), clock = { f.now })
        other.open(f.diary.entryOn(day))
        other.onInput("另一个会话改过")
        other.saveNow()
        other.close()
        stale.onInput("旧会话的未保存文字")
        withTimeout(10_000) { f.backup.restore(uri) }
        val copy = codec.decodeFromString(BackupFile.serializer(), localCopies().single().readText())
        assertEquals("旧会话的未保存文字", copy.diaryDrafts.single().text)
        assertEquals(1, f.restoredCount)
        stale.close()
    }

    @Test fun r4_oldTokenWithMatchingIdAndSeqIsRejectedByEpoch() = runBlocking {
        val oldEpoch = f.gate.bound()
        val id = withContext(oldEpoch) {
            seed()
            f.todos.create(TodoDraft("小组件上的那条", planDate = date("2026-10-20")))
        }
        val seq = f.todos.todo(id)!!.stateSeq
        val uri = file("r4.json", withContext(oldEpoch) { f.backup.exportJson() })
        f.backup.restore(uri)
        assertEquals(seq, f.todos.todo(id)!!.stateSeq)
        try {
            withContext(oldEpoch) { f.todos.complete(id, seq) }
            fail("old epoch must be rejected")
        } catch (e: WriteRejected) {
            assertEquals(WriteGate.MSG_STALE, e.message)
        }
        assertTrue(TodoView_isOpen(id))
    }

    private suspend fun TodoView_isOpen(id: String) = com.assetboard.domain.todo.TodoViews.isOpen(f.todos.todo(id)!!)

    @Test fun r5_restoreFromThePreRestoreCopy() = f.run {
        seed()
        val uri = file("r5.json", f.backup.exportJson())
        f.todos.create(TodoDraft("只在恢复前存在"))
        val before = counts()
        f.backup.restore(uri)
        f.now += 1_000
        val copy = f.backup.preRestoreCopies().single()
        assertFalse(copy.inBackupFolder)
        // A timestamped copy from an older version goes away with the next one.
        val legacy = File(f.context.filesDir, "prerestore/asset-board-prerestore-20260101-000000-000.json").apply { writeText("{}") }
        f.run { f.backup.restore(copy.uri) }
        assertFalse(legacy.exists())
        assertEquals(before, counts())
        // One copy, overwritten: it now holds the data from before the second restore.
        assertEquals(1, f.backup.preRestoreCopies().size)
    }

    @Test fun r6_roundTripKeepsEverythingButTheEpoch() = f.run {
        seed()
        val exported = f.backup.exportJson()
        val epoch = f.gate.epochNow()
        f.backup.restore(file("r6.json", exported))
        val again = normalized(f.backup.exportJson())
        val original = normalized(exported)
        assertEquals(original, again)
        assertTrue(again.todos.any { it.deletedAt != null })
        assertTrue(again.todos.any { it.kind == TodoKind.COMPLETION })
        assertEquals(listOf("workout"), again.habitChecks.map { it.habit })
        assertEquals(epoch + 1, f.gate.epochNow())
        assertFalse(exported.contains(WriteGate.KEY_EPOCH))
    }

    /** Row order is not part of the data. */
    private fun normalized(text: String): BackupFile {
        val b = codec.decodeFromString(BackupFile.serializer(), text)
        return b.copy(
            exportedAt = 0,
            groups = b.groups.sortedBy { it.id }, accounts = b.accounts.sortedBy { it.id }, snapshots = b.snapshots.sortedBy { it.id },
            valuations = b.valuations.sortedBy { it.id }, snapshotItems = b.snapshotItems.sortedBy { it.snapshotId + it.accountId },
            flows = b.flows.sortedBy { it.id }, todoLists = b.todoLists.sortedBy { it.id }, todoSections = b.todoSections.sortedBy { it.id },
            todos = b.todos.sortedBy { it.id }, checklistItems = b.checklistItems.sortedBy { it.id },
            diaryEntries = b.diaryEntries.sortedBy { it.id }, diaryDrafts = b.diaryDrafts.sortedBy { it.id },
            habitChecks = b.habitChecks.sortedBy { it.date + it.habit },
        )
    }

    private suspend fun rejects(mutate: (BackupFile) -> BackupFile) {
        val good = codec.decodeFromString(BackupFile.serializer(), f.backup.exportJson())
        val uri = file("bad.json", codec.encodeToString(BackupFile.serializer(), mutate(good)))
        val before = counts()
        try {
            f.backup.restore(uri)
            fail("validation should reject")
        } catch (e: com.assetboard.domain.LedgerException) {
            // expected
        }
        assertEquals(before, counts())
        assertFalse(f.gate.frozen.value)
        assertTrue(localCopies().isEmpty())
    }

    @Test fun r7_twoLiveEntriesOnOneDate() = f.run {
        seed()
        rejects { b -> b.copy(diaryEntries = b.diaryEntries + b.diaryEntries.first().copy(id = "dup")) }
    }

    @Test fun r8_sectionOfAnotherList() = f.run {
        seed()
        val other = f.todos.createList("别的")
        val s = f.todos.createSection(other, "S")
        rejects { b ->
            val list = b.todoLists.first { it.name == "论文" }
            b.copy(todos = b.todos.map { if (it.listId == list.id) it.copy(sectionId = s) else it })
        }
    }

    @Test fun r9_repeatSourceWithoutPlanOrWithAGarbledRule() = f.run {
        seed()
        rejects { b -> b.copy(todos = b.todos.map { if (it.kind == TodoKind.REPEAT_SOURCE) it.copy(planDate = null) else it }) }
        rejects { b -> b.copy(todos = b.todos.map { if (it.kind == TodoKind.REPEAT_SOURCE) it.copy(repeatRule = "{oops") else it }) }
    }

    @Test fun r10_unwritableBackupFolderCancels() = f.run {
        seed()
        val uri = file("r10.json", f.backup.exportJson())
        f.todos.create(TodoDraft("不能丢"))
        val before = counts()
        f.settings.setBackupTreeUri("content://com.android.externalstorage.documents/tree/primary%3Anope")
        try {
            f.backup.restore(uri)
            fail("restore should be cancelled")
        } catch (e: com.assetboard.domain.LedgerException) {
            assertTrue(e.message!!.contains("备份目录"))
        } finally {
            f.settings.setBackupTreeUri(null)
        }
        assertEquals(before, counts())
        assertFalse(f.gate.frozen.value)
    }

    @Test fun r11_rescheduleFailureDoesNotUndoTheRestore() = f.run {
        seed()
        val uri = file("r11.json", f.backup.exportJson())
        f.todos.create(TodoDraft("恢复后消失"))
        f.effectsFail = true
        f.backup.restore(uri)
        assertEquals(2, f.db.todoDao().liveTodos().size)
        assertFalse(f.gate.frozen.value)
        assertTrue(f.settings.rescheduleNeeded())
        f.settings.setRescheduleNeeded(false)
    }

    @Test fun r12_restoreWaitsForTheRunningWriteAndCopiesIt() = runBlocking {
        withContext(f.gate.bound()) { seed() }
        val uri = file("r12.json", withContext(f.gate.bound()) { f.backup.exportJson() })
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // A stocktake-like write that is already inside the gate.
        val write = async(f.gate.bound()) {
            f.gate.write {
                f.db.dao().insertGroup(AccountGroup("running", "进行中的保存", sortOrder = 99))
                inside.complete(Unit)
                release.await()
            }
        }
        inside.await()
        val restore = async { f.backup.restore(uri) }
        delay(300)
        assertTrue(f.gate.frozen.value)
        assertFalse(restore.isCompleted)
        release.complete(Unit)
        write.await()
        withTimeout(10_000) { restore.await() }
        assertTrue(localCopies().single().readText().contains("进行中的保存"))
        assertTrue(f.db.dao().groups().none { it.id == "running" })
    }

    @Test fun r13_r15_writesDuringAndAfterTheRestoreAreRejected() = runBlocking {
        val oldEpoch = f.gate.bound()
        withContext(oldEpoch) { seed() }
        val uri = file("r13.json", withContext(oldEpoch) { f.backup.exportJson() })
        val during = mutableListOf<String?>()
        val effects = object : RestoreEffects {
            override suspend fun afterRestore(oldTodoIds: List<String>) {
                // Committed, not yet unfrozen: a fresh save (R13) and an old screen's save (R15).
                for (e in listOf(f.gate.bound(), oldEpoch)) {
                    during += runCatching { withContext(e) { f.ledger.createGroup("恢复中", false, 0) } }.exceptionOrNull()?.message
                }
            }
        }
        val bm = BackupManager(f.context, f.db, f.settings, f.gate, f.diary, { f.calendar }, effects, onRestored = {}, clock = { f.now })
        bm.restore(uri)
        assertEquals(listOf(WriteGate.MSG_RESTORING, WriteGate.MSG_RESTORING), during)
        // After unfreezing, the old screen is still turned away (R15).
        val after = runCatching { withContext(oldEpoch) { f.ledger.createGroup("旧页面", false, 0) } }.exceptionOrNull()
        assertEquals(WriteGate.MSG_STALE, after?.message)
        assertTrue(f.db.dao().groups().none { it.name == "恢复中" || it.name == "旧页面" })
    }

    @Test fun r14_screensAreRebuiltAfterARestore() = f.run {
        seed()
        f.backup.restore(file("r14.json", f.backup.exportJson()))
        assertEquals(1, f.restoredCount)
    }

    @Test fun r16_textThatCannotLandCancelsTheRestore() = f.run {
        seed()
        val uri = file("r16.json", f.backup.exportJson())
        f.todos.create(TodoDraft("不能丢"))
        val before = counts()
        val s = DiarySession(f.diary, date("2026-10-22"), this + f.gate.bound(), clock = { f.now })
        s.open(null)
        s.onInput("无处可写")
        f.diary.failBodyWrites = true
        f.diary.failDraftWrites = true
        try {
            f.backup.restore(uri)
            fail("restore should be cancelled")
        } catch (e: com.assetboard.domain.LedgerException) {
            assertEquals("有未保存的日记，恢复已取消", e.message)
        }
        assertTrue(localCopies().isEmpty())
        assertEquals(before, counts())
        assertFalse(f.gate.frozen.value)
        assertEquals("无处可写", s.text.value)
        assertEquals(DiarySession.Status.READY, s.status.value)
        f.diary.failBodyWrites = false
        f.diary.failDraftWrites = false
        s.close()
    }

    @Test fun r17_writeWithoutAnEpochCrashesInDebug() = runBlocking {
        try {
            f.ledger.createGroup("没有纪元", false, 0)
            fail("must crash")
        } catch (e: IllegalStateException) {
            assertNotNull(e.message)
        }
        Unit
    }

    @Test fun epochSurvivesAProcessRestart() = f.run {
        seed()
        f.backup.restore(file("ep.json", f.backup.exportJson()))
        val fresh = WriteGate(f.db, crashOnMissingEpoch = true)
        assertEquals(f.gate.epochNow(), fresh.epochNow())
        assertEquals(1L, fresh.epochNow())
        f.db.withTransaction { }
    }
}
