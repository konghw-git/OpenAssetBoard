package com.assetboard.data

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import com.assetboard.AppContainer
import com.assetboard.domain.LedgerException
import com.assetboard.domain.todo.RepeatRule
import com.assetboard.domain.todo.RepeatType
import com.assetboard.reminder.MissedReminder
import com.assetboard.reminder.ReminderScheduler
import com.assetboard.ui.diary.DiaryViewModel
import com.assetboard.ui.todo.TodoDetailViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** Failure paths found in the 0.4.0 review: cancellation, failed writes, corrupt backups. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class RegressionTest {
    private fun file(f: Fixture, name: String, text: String) = Uri.fromFile(File(f.context.cacheDir, name).apply { writeText(text) })

    @Test fun restoreCancelledWhileWaitingForAWriteUnfreezesTheGate() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.settings.setBackupTreeUri(null)
            val uri = file(f, "cancel.json", f.backup.exportJson())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writer = launch(f.gate.bound()) { f.gate.write { entered.complete(Unit); release.await() } }
            entered.await()
            val restore = launch { f.backup.restore(uri) }
            withTimeout(5000) { f.gate.frozen.first { it } }
            restore.cancelAndJoin()
            release.complete(Unit)
            writer.join()
            assertFalse(f.gate.frozen.value)
            withContext(f.gate.bound()) { f.todos.create(TodoDraft("after cancellation")) }
            // And a later restore can still take the gate.
            f.backup.restore(uri)
        } finally {
            f.close()
        }
    }

    @Test fun restoreCancelledAfterTheCommitStillRebuildsScreensAndFlagsReminders() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.settings.setBackupTreeUri(null)
            f.settings.setRescheduleNeeded(false)
            val entered = CompletableDeferred<Unit>()
            var refreshed = false
            val manager = BackupManager(
                f.context, f.db, f.settings, f.gate, f.diary, { f.calendar },
                object : RestoreEffects {
                    override suspend fun afterRestore(oldTodoIds: List<String>) { entered.complete(Unit); awaitCancellation() }
                },
                { refreshed = true },
            )
            val uri = file(f, "committed.json", manager.exportJson())
            val oldEpoch = f.gate.bound()
            val restore = launch { manager.restore(uri) }
            entered.await()
            restore.cancelAndJoin()
            assertEquals(oldEpoch.value + 1, f.gate.epochNow())
            assertTrue("screens are rebuilt on the new epoch", refreshed)
            assertTrue("reminders are rebuilt on the next start", f.settings.rescheduleNeeded())
            assertFalse(f.gate.frozen.value)
        } finally {
            f.close()
        }
    }

    @Test fun completedRestoreClearsTheRescheduleFlag() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.settings.setBackupTreeUri(null)
            f.backup.restore(file(f, "ok.json", f.backup.exportJson()))
            assertFalse(f.settings.rescheduleNeeded())
            assertEquals(1, f.restoredCount)
        } finally {
            f.close()
        }
    }

    @Test fun missedRemindersSurviveARestartedScheduler() {
        val f = Fixture()
        val container = AppContainer(f.context)
        try {
            val first = ReminderScheduler(f.context, container)
            first.clearMissed()
            first.addMissed(MissedReminder("first", "first missed", 1, false))
            val restarted = ReminderScheduler(f.context, container)
            assertEquals(listOf("first"), restarted.missed.value.map { it.todoId })
            restarted.addMissed(MissedReminder("second", "second missed", 2, false))
            restarted.refreshMissed()
            assertEquals(listOf("first", "second"), restarted.missed.value.map { it.todoId })
        } finally {
            container.appScope.cancel()
            container.db.close()
            f.close()
        }
    }

    @Test fun stocktakeItemsMustPointAtTheirOwnAccountsValuation() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.settings.setBackupTreeUri(null)
            withContext(f.gate.bound()) {
                val g = f.ledger.createGroup("assets", false, 0)
                val a = f.ledger.createAccount(AccountDraft(g, "A"), 0, 10000, null)
                val b = f.ledger.createAccount(AccountDraft(g, "B"), 0, 90000, null)
                f.ledger.createSnapshot(100, listOf(StocktakeEntry(a, 10000), StocktakeEntry(b, 90000)))
            }
            val codec = Json { encodeDefaults = true }
            val exported = f.backup.exportJson()
            val backup = codec.decodeFromString(BackupFile.serializer(), exported)
            val first = backup.snapshotItems.first()
            val other = backup.snapshotItems.last()
            val corrupt = listOf(
                backup.copy(snapshotItems = listOf(first.copy(valuationId = other.valuationId), other)),
                backup.copy(valuations = backup.valuations.map { if (it.id == first.valuationId) it.copy(at = 200) else it }),
            )
            for ((i, c) in corrupt.withIndex()) {
                val uri = file(f, "corrupt$i.json", codec.encodeToString(BackupFile.serializer(), c))
                try {
                    f.backup.restore(uri)
                    fail("corrupt backup $i was accepted")
                } catch (e: LedgerException) {
                    // expected
                }
            }
            // The untouched file still restores.
            f.backup.restore(file(f, "good.json", exported))
            assertEquals(100000L, f.ledger.loadOnce().snapshotTotals(backup.snapshots.single().id).netWorth)
        } finally {
            f.close()
        }
    }

    @Test fun diaryTextThatCannotBeSavedKeepsTheDayOpenUntilDropped() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = Fixture()
        val container = AppContainer(f.context)
        val store = ViewModelStore()
        try {
            container.settings.setBool(container.settings.networkEnabled, false)
            container.diary.failBodyWrites = true
            container.diary.failDraftWrites = true
            val vm = DiaryViewModel(container)
            store.put("diary", vm)
            val original = withTimeout(5000) { vm.session.first { it != null }!! }
            val yesterday = LocalDate.now().minusDays(1)
            vm.onInput("only copy of unsaved diary")
            vm.open(yesterday)
            withTimeout(5000) { vm.unsavedLeave.first { it == yesterday } }
            assertSame(original, vm.session.value)
            assertEquals(LocalDate.now(), vm.date.value)
            assertTrue(original in container.diary.editors)
            assertEquals("only copy of unsaved diary", original.text.value)

            vm.dropUnsavedAndOpen(yesterday)
            withTimeout(5000) { vm.session.first { it != null && it !== original } }
            assertEquals(yesterday, vm.date.value)
            assertFalse(original in container.diary.editors)
        } finally {
            store.clear()
            container.appScope.cancel()
            container.db.close()
            f.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun switchingDiaryDaysWaitsForTheSave() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = Fixture()
        val container = AppContainer(f.context)
        val store = ViewModelStore()
        try {
            container.settings.setBool(container.settings.networkEnabled, false)
            val vm = DiaryViewModel(container)
            store.put("diary", vm)
            val original = withTimeout(5000) { vm.session.first { it != null }!! }
            vm.onInput("saved before leaving")
            vm.open(LocalDate.now().minusDays(1))
            val next = withTimeout(5000) { vm.session.first { it != null && it !== original }!! }
            assertNotSame(original, next)
            assertEquals("saved before leaving", container.diary.entryOn(LocalDate.now())?.body)
        } finally {
            store.clear()
            container.appScope.cancel()
            container.db.close()
            f.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun completingARepeatRightAfterEditingKeepsTheNewTitleAndUndo() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = Fixture()
        val container = AppContainer(f.context)
        val store = ViewModelStore()
        try {
            val id = withContext(container.gate.bound()) {
                val id = container.todos.create(TodoDraft("old title", planDate = LocalDate.now()))
                container.todos.setRepeat(id, RepeatRule(RepeatType.DAILY))
                id
            }
            val vm = TodoDetailViewModel(container, id)
            store.put("todo", vm)
            withTimeout(5000) { vm.state.first { it.todo != null } }
            vm.editTitle("new title")
            vm.complete()
            withTimeout(5000) { while (container.todos.todo(id)!!.occurrenceCount == 0) delay(10) }
            // The debounce and the page's disposal write the same text again: nothing changes.
            vm.flush(container)
            delay(900)
            val record = container.db.todoDao().completionsOf(id).single()
            assertEquals("new title", record.title)
            val outcome = withContext(container.gate.bound()) { container.todos.uncomplete(record.id) }
            assertEquals(UndoOutcome.RESTORED, outcome)
        } finally {
            store.clear()
            container.appScope.cancel()
            container.db.close()
            f.close()
            Dispatchers.resetMain()
        }
    }
}
