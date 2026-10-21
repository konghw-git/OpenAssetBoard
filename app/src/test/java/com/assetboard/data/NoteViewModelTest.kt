package com.assetboard.data

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.assetboard.AppContainer
import com.assetboard.TestContainers
import com.assetboard.ui.notes.NoteViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The note page's ViewModel: process death on a new note, and leaving text that cannot be saved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class NoteViewModelTest {
    private fun withContainer(block: suspend (AppContainer, ViewModelStore) -> Unit) = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = Fixture()
        val container = TestContainers.open(f.context)
        val store = ViewModelStore()
        try {
            block(container, store)
        } finally {
            store.clear()
            TestContainers.closeAll()
            f.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun newNoteReopensAsTheSameNoteAfterProcessDeath() = withContainer { c, store ->
        val handle = SavedStateHandle(mapOf(NoteViewModel.ARG_ID to "", NoteViewModel.ARG_TOPIC to ""))
        val vm = NoteViewModel(c, handle)
        store.put("first", vm)
        val s = withTimeout(5000) { vm.session.first { it != null }!! }
        vm.onInput("进程被杀前写的")
        assertTrue(withContext(c.gate.bound()) { s.saveNow() })
        val id = withTimeout(5000) { vm.noteId.first { it != null }!! }
        assertEquals(id, handle.get<String>(NoteViewModel.KEY_ID))
        // The route still says "new note"; only the saved state knows better.
        val restored = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        val again = NoteViewModel(c, restored)
        store.put("again", again)
        val s2 = withTimeout(5000) { again.session.first { it != null }!! }
        assertEquals(id, s2.noteId)
        assertEquals("进程被杀前写的", s2.text.value)
        assertEquals(1, c.db.noteDao().all().size)
    }

    @Test fun unsavedTextAsksBeforeLeaving() = withContainer { c, store ->
        val id = withContext(c.gate.bound()) { c.notes.create("写不进去的笔记", null) }
        val vm = NoteViewModel(c, SavedStateHandle(mapOf(NoteViewModel.ARG_ID to id)))
        store.put("note", vm)
        val s = withTimeout(5000) { vm.session.first { it != null }!! }
        val gone = mutableListOf<NoteViewModel.Leave>()
        vm.leave(NoteViewModel.Leave.Back) { gone += it }
        withTimeout(5000) { while (gone.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(listOf<NoteViewModel.Leave>(NoteViewModel.Leave.Back), gone)
        c.notes.failBodyWrites = true
        c.notes.failDraftWrites = true
        vm.onInput("写不进去的笔记，又加了一句")
        assertFalse(withContext(c.gate.bound()) { s.saveNow() })
        assertEquals(NoteSession.Status.UNSAVED, s.status.value)
        vm.leave(NoteViewModel.Leave.Back) { gone += it }
        assertEquals(NoteViewModel.Leave.Back, withTimeout(5000) { vm.unsavedLeave.first { it != null } })
        val day = java.time.LocalDate.of(2026, 10, 12)
        vm.leave(NoteViewModel.Leave.Diary(day)) { gone += it }
        assertEquals(NoteViewModel.Leave.Diary(day), withTimeout(5000) { vm.unsavedLeave.first { it is NoteViewModel.Leave.Diary } })
        assertEquals(1, gone.size)
        assertEquals("写不进去的笔记，又加了一句", s.text.value)
        c.notes.failBodyWrites = false
        c.notes.failDraftWrites = false
    }
}
