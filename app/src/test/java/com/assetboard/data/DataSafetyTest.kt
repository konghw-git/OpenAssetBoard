package com.assetboard.data

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.assetboard.AppContainer
import com.assetboard.TestContainers
import com.assetboard.domain.Habit
import com.assetboard.domain.health.HealthTrend
import com.assetboard.reminder.Notifier
import com.assetboard.reminder.ReminderToken
import com.assetboard.ui.notes.NoteViewModel
import com.assetboard.ui.todo.TodoDetailViewModel
import com.assetboard.ui.week.WeekViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** Text that must never be lost on the way out of an editor, and checks that must not be skipped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DataSafetyTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private fun withContainer(block: suspend (AppContainer, ViewModelStore) -> Unit) = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val c = com.assetboard.TestContainers.open(context)
        val store = ViewModelStore()
        try {
            block(c, store)
        } finally {
            store.clear()
            // Queries still running on the app scope would fail on the closed database inside the next test.
            TestContainers.closeAll()
            Dispatchers.resetMain()
        }
    }

    // ------------------------------------------------------------ leaving an editor

    @Test fun weekStaysWhenItsTextCannotBeSavedOnTheWayOut() = withContainer { c, store ->
        val start = LocalDate.of(2026, 10, 19)
        val vm = WeekViewModel(c, SavedStateHandle(mapOf(WeekViewModel.KEY_START to start.toString())))
        store.put("week", vm)
        val old = withTimeout(5000) { vm.session.first { it != null }!! }
        c.weeks.failBodyWrites = true
        c.weeks.failDraftWrites = true
        vm.onInput("must not disappear")
        vm.go(start.minusWeeks(1))
        withTimeout(5000) { vm.unsavedLeave.first { it != null } }
        assertEquals(start, vm.start.value)
        assertEquals(old, vm.session.value)
        assertEquals("must not disappear", old.text.value)
        var left: WeekViewModel.Leave? = null
        vm.leave(WeekViewModel.Leave.Back) { left = it }
        delay(100)
        assertNull(left)
        // A diary row is a way out too.
        val day = start.plusDays(1)
        vm.leave(WeekViewModel.Leave.Diary(day)) { left = it }
        assertEquals(WeekViewModel.Leave.Diary(day), withTimeout(5000) { vm.unsavedLeave.first { it is WeekViewModel.Leave.Diary } })
        assertNull(left)
    }

    @Test fun weekMovesOnOnceItsTextIsSaved() = withContainer { c, store ->
        val start = LocalDate.of(2026, 10, 19)
        val vm = WeekViewModel(c, SavedStateHandle(mapOf(WeekViewModel.KEY_START to start.toString())))
        store.put("week", vm)
        withTimeout(5000) { vm.session.first { it != null } }
        vm.onInput("kept")
        vm.go(start.minusWeeks(1))
        withTimeout(5000) { vm.start.first { it == start.minusWeeks(1) } }
        assertEquals("kept", c.db.weekDao().all().single().body)
    }

    @Test fun noteWaitsForTheFinalSaveBeforeLeaving() = withContainer { c, store ->
        val vm = NoteViewModel(c, SavedStateHandle())
        store.put("note", vm)
        val old = withTimeout(5000) { vm.session.first { it != null }!! }
        c.notes.failBodyWrites = true
        c.notes.failDraftWrites = true
        vm.onInput("must not disappear")
        var left: NoteViewModel.Leave? = null
        vm.leave(NoteViewModel.Leave.Back) { left = it }
        withTimeout(5000) { vm.unsavedLeave.first { it != null } }
        assertNull(left)
        assertEquals(TextSession.Status.UNSAVED, old.status.value)
        assertEquals(old, vm.session.value)

        c.notes.failBodyWrites = false
        c.notes.failDraftWrites = false
        val fresh = NoteViewModel(c, SavedStateHandle())
        store.put("fresh", fresh)
        withTimeout(5000) { fresh.session.first { it != null } }
        fresh.onInput("saved first")
        val gone = CompletableDeferred<NoteViewModel.Leave>()
        fresh.leave(NoteViewModel.Leave.Back) { gone.complete(it) }
        assertEquals(NoteViewModel.Leave.Back, withTimeout(5000) { gone.await() })
        assertTrue(c.db.noteDao().all().any { it.body == "saved first" })
    }

    @Test fun typingWhileTheDraftIsWrittenGoesIntoADraftToo() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val drafts = mutableListOf<String>()
        val store = object : NoteStore {
            override suspend fun saveBody(noteId: String?, expectedRev: Long?, text: String, sources: List<SourceRef>, topicId: String?) = BodySave.Conflict("conflict")
            override suspend fun saveDraft(noteId: String?, text: String, reason: String): String {
                if (drafts.isEmpty()) {
                    started.complete(Unit)
                    finish.await()
                }
                drafts += text
                return "draft"
            }
            override fun register(editor: EditorHandle) {}
            override fun unregister(editor: EditorHandle) {}
        }
        val session = NoteSession(store, backgroundScope)
        session.open(null)
        session.onInput("first sentence")
        val saving = async { session.saveNow() }
        started.await()
        session.onInput("first sentence plus last sentence")
        finish.complete(Unit)
        saving.await()
        assertEquals(TextSession.Status.ENDED, session.status.value)
        assertEquals("first sentence plus last sentence", drafts.last())
        // Once ended, the editor is about to reload: nothing more is taken in.
        session.onInput("too late")
        assertEquals("first sentence plus last sentence", session.text.value)
    }

    @Test fun deletingATodoKeepsTheLatestTextInTheTrash() = withContainer { c, store ->
        val id = withContext(c.gate.bound()) { c.todos.create(TodoDraft("old title")) }
        val vm = TodoDetailViewModel(c, id)
        store.put("todo", vm)
        vm.editTitle("new title")
        vm.editNotes("new notes")
        val deleted = CompletableDeferred<Unit>()
        vm.delete { deleted.complete(Unit) }
        withTimeout(5000) { deleted.await() }
        val t = c.todos.todo(id)!!
        assertNotNull(t.deletedAt)
        assertEquals("new title", t.title)
        assertEquals("new notes", t.notes)
    }

    // ------------------------------------------------------------ restore

    @Test fun habitsWithBadDatesAreRejectedBeforeRestore() {
        val f = Fixture()
        try {
            f.run {
                f.settings.setBackupTreeUri(null)
                val codec = Json { encodeDefaults = true }
                val original = codec.decodeFromString(BackupFile.serializer(), f.backup.exportJson())
                suspend fun restore(name: String, habits: List<Habit>) = runCatching {
                    val file = File(f.context.cacheDir, name).apply { writeText(codec.encodeToString(BackupFile.serializer(), original.copy(habits = habits))) }
                    f.backup.restore(Uri.fromFile(file))
                }
                val five = Habit.DEFAULTS
                assertTrue(restore("bad-added.json", five.map { if (it.key == "workout") it.copy(addedOn = "not-a-date") else it }).isFailure)
                assertTrue(restore("bad-removed.json", five.map { if (it.key == "workout") it.copy(removedOn = "2026-13-01") else it }).isFailure)
                assertTrue(restore("backwards.json", five.map { if (it.key == "workout") it.copy(addedOn = "2026-10-20", removedOn = "2026-10-19") else it }).isFailure)
                assertTrue(restore("long-label.json", five.map { if (it.key == "workout") it.copy(label = "一二三四五六七八九") else it }).isFailure)
                assertTrue(restore("none-active.json", five.map { it.copy(removedOn = "2026-10-20") }).isFailure)
                assertTrue(f.db.habitDao().habits().isEmpty())
                // Taken off on the day it was added: allowed, as the repository does it.
                val sameDay = five + Habit("new", "读书", "book", 0xFF000000, 5, addedOn = "2026-10-20", removedOn = "2026-10-20")
                assertTrue(restore("same-day.json", sameDay).isSuccess)
                assertEquals(6, f.db.habitDao().habits().size)
            }
        } finally {
            f.close()
        }
    }

    // ------------------------------------------------------------ reminders and updates

    @Test fun anAlarmDeliveredMoreThanADayLateIsMissed() = withContainer { c, _ ->
        val t = Todo(id = "late-alarm", title = "old reminder", createdAt = 0, planDate = LocalDate.now().minusDays(3).toString(), remindTime = "09:00")
        c.db.todoDao().insertTodo(t)
        Notifier.ensureChannels(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        shadowOf(nm).setNotificationsEnabled(true)
        c.reminders.fire(ReminderToken(t.id, t.stateSeq, c.gate.epochNow()))
        assertTrue(c.reminders.missed.value.any { it.todoId == t.id })
        assertFalse(nm.activeNotifications.any { it.id == Notifier.notificationId(t.id) })
        assertEquals(t.occurrenceCount, c.todos.todo(t.id)!!.remindedPeriod)
    }

    @Test fun anAlarmOnTimeIsStillDelivered() = withContainer { c, _ ->
        val now = java.time.LocalDateTime.now().minusMinutes(5)
        val t = Todo(id = "on-time", title = "now", createdAt = 0, planDate = now.toLocalDate().toString(), remindTime = "%02d:%02d".format(now.hour, now.minute))
        c.db.todoDao().insertTodo(t)
        Notifier.ensureChannels(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        shadowOf(nm).setNotificationsEnabled(true)
        c.reminders.fire(ReminderToken(t.id, t.stateSeq, c.gate.epochNow()))
        assertTrue(nm.activeNotifications.any { it.id == Notifier.notificationId(t.id) })
        assertTrue(c.reminders.missed.value.none { it.todoId == t.id })
    }

    @Test fun aFailedBackupStopsTheInstallUntilTheUserSkipsIt() = withContainer { c, _ ->
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        c.settings.setBackupTreeUri("content://test/unwritable")
        val updater = AppUpdater(context, c.settings, c.appScope) { throw java.io.IOException("备份目录不可写") }
        val apk = File(context.cacheDir, "updates/test.apk").apply { parentFile!!.mkdirs(); writeText("test") }
        val release = Release("9.9.9", "", "", 0, "")
        updater.present(UpdateState.Ready(release, apk))
        updater.install(apk)
        val failed = withTimeout(5000) { updater.state.first { it is UpdateState.Ready && it.backupFailed != null } }
        assertEquals("备份目录不可写", (failed as UpdateState.Ready).backupFailed)
        assertNull(shadowOf(context).nextStartedActivity)

        updater.install(apk, skipBackup = true)
        val intent = withTimeout(5000) {
            var i: Intent? = null
            while (i == null) {
                i = shadowOf(context).nextStartedActivity
                if (i == null) delay(10)
            }
            i
        }
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertNull((updater.state.value as UpdateState.Ready).backupFailed)
    }
    // ------------------------------------------------------------ the second review

    @Test fun weekDiaryRowWaitsForTheReviewToo() = withContainer { c, store ->
        val start = LocalDate.of(2026, 10, 19)
        val vm = WeekViewModel(c, SavedStateHandle(mapOf(WeekViewModel.KEY_START to start.toString())))
        store.put("week", vm)
        withTimeout(5000) { vm.session.first { it != null } }
        c.weeks.failBodyWrites = true
        c.weeks.failDraftWrites = true
        vm.onInput("only copy")
        val day = start.plusDays(2)
        var left: WeekViewModel.Leave? = null
        vm.leave(WeekViewModel.Leave.Diary(day)) { left = it }
        assertEquals(WeekViewModel.Leave.Diary(day), withTimeout(5000) { vm.unsavedLeave.first { it != null } })
        assertNull(left)
    }

    @Test fun aFlowOnAValuationInstantIsRefused() = runBlocking {
        val f = Fixture()
        try {
            f.run {
                val g = f.ledger.createGroup("现金", false, 0)
                val a = f.ledger.createAccount(AccountDraft(g, "钱包"), 0, 100_00, null)
                val at = f.at("2026-10-01")
                f.ledger.addValuation(a, at, 120_00, null)
                val failed = runCatching { f.ledger.saveFlow(MoneyFlow(newId(), FlowKind.EXTERNAL_OUT, a, null, 10_00, at = at, createdAt = f.now)) }
                assertTrue("a flow at a valuation's instant would never move the balance", failed.isFailure)
                assertTrue(f.ledger.saveFlow(MoneyFlow(newId(), FlowKind.EXTERNAL_OUT, a, null, 10_00, at = at + 1_000, createdAt = f.now)) == Unit)
            }
        } finally {
            f.close()
        }
    }

    @Test fun aLaterValuationIsNotTodaysBalanceOnReset() = runBlocking {
        val f = Fixture()
        try {
            f.run {
                val g = f.ledger.createGroup("现金", false, 0)
                val a = f.ledger.createAccount(AccountDraft(g, "钱包"), 0, 100_00, null)
                // A valuation dated tomorrow is not today's balance; today still shows the 100.00 baseline.
                f.ledger.addValuation(a, f.now + 86_400_000, 999_00, null)
                f.backup.resetLedgerHistory()
                assertEquals(100_00L, f.ledger.loadOnce().currentBalance(a))
            }
        } finally {
            f.close()
        }
    }

    @Test fun aBackupWithTwoValuationsAtOneInstantIsRefused() = runBlocking {
        val f = Fixture()
        try {
            f.settings.setBackupTreeUri(null)
            f.run {
                val g = f.ledger.createGroup("现金", false, 0)
                val a = f.ledger.createAccount(AccountDraft(g, "钱包"), 0, 100_00, null)
                f.ledger.addValuation(a, f.at("2026-10-01"), 120_00, null)
            }
            val codec = Json { encodeDefaults = true }
            val original = codec.decodeFromString(BackupFile.serializer(), f.backup.exportJson())
            // Two valuations of one account at one instant, which the schema's unique index refuses.
            val doubled = original.copy(valuations = original.valuations + original.valuations.first().copy(id = "second"))
            val file = File(f.context.cacheDir, "double.json").apply { writeText(codec.encodeToString(BackupFile.serializer(), doubled)) }
            assertTrue(runCatching { f.backup.restore(Uri.fromFile(file)) }.isFailure)
        } finally {
            f.close()
        }
    }

    @Test fun aTrashedNoteWithoutItsBatchIsRefused() = runBlocking {
        val f = Fixture()
        try {
            f.settings.setBackupTreeUri(null)
            f.run {
                f.ledger.createGroup("现金", false, 0).let { f.ledger.createAccount(AccountDraft(it, "钱包"), 0, 100_00, null) }
                f.notes.create("to the trash", null)
            }
            val codec = Json { encodeDefaults = true }
            val original = codec.decodeFromString(BackupFile.serializer(), f.backup.exportJson())
            // Deleted, but without the batch the trash groups it by: the trash reader would NPE.
            val broken = original.copy(notes = original.notes.map { it.copy(deletedAt = 1) })
            val file = File(f.context.cacheDir, "trash.json").apply { writeText(codec.encodeToString(BackupFile.serializer(), broken)) }
            assertTrue(runCatching { f.backup.restore(Uri.fromFile(file)) }.isFailure)
        } finally {
            f.close()
        }
    }

    @Test fun aQuietDayInTheMiddleDoesNotCutTheFatAverage() {
        fun m(date: String, fat: Int?) = BodyMeasurement("m-$date", date, 0, 92_000, fat, null, 0, 0)
        val points = listOf(m("2026-09-30", 2_600), m("2026-10-01", 2_500), m("2026-10-02", null), m("2026-10-03", 2_400))
        val runs = HealthTrend.movingAverage(points) { it.bodyFatBp?.toLong() }
        assertEquals(1, runs.size)
        assertEquals(listOf("2026-10-01", "2026-10-03"), runs.single().map { it.first })
    }

    @Test fun aBlockedReminderChannelCountsTheReminderAsMissed() = withContainer { c, _ ->
        val now = java.time.LocalDateTime.now().minusMinutes(5)
        val t = Todo(id = "quiet", title = "quiet", createdAt = 0, planDate = now.toLocalDate().toString(), remindTime = "%02d:%02d".format(now.hour, now.minute))
        c.db.todoDao().insertTodo(t)
        Notifier.ensureChannels(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        shadowOf(nm).setNotificationsEnabled(true)
        nm.createNotificationChannel(android.app.NotificationChannel(Notifier.CH_REMINDER, "待办提醒", NotificationManager.IMPORTANCE_NONE))
        c.reminders.fire(ReminderToken(t.id, t.stateSeq, c.gate.epochNow()))
        assertTrue(c.reminders.missed.value.any { it.todoId == t.id })
        nm.deleteNotificationChannel(Notifier.CH_REMINDER)
    }

    @Test fun aStaleRingTokenNeverStartsRinging() = withContainer { c, _ ->
        val t = Todo(id = "done", title = "done", createdAt = 0)
        c.db.todoDao().insertTodo(t)
        // The todo moved on since the alarm was set: the token no longer matches.
        assertFalse(c.reminders.stillLive(ReminderToken(t.id, t.stateSeq + 1, c.gate.epochNow())))
        assertFalse(c.reminders.stillLive(ReminderToken(t.id, t.stateSeq, c.gate.epochNow() + 1)))
        assertTrue(c.reminders.stillLive(ReminderToken(t.id, t.stateSeq, c.gate.epochNow())))
    }
}
