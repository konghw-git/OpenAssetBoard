package com.assetboard.ui.notes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.assetboard.AppContainer
import com.assetboard.data.Note
import com.assetboard.data.NoteSession
import com.assetboard.data.NoteSource
import com.assetboard.data.NoteTopic
import com.assetboard.ui.ActionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.time.LocalDate

/**
 * One note's page. A new note has no row until its first write; the id it gets then is kept in
 * [handle], so a page brought back after the process was killed opens that note rather than a
 * second empty one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteViewModel(private val container: AppContainer, private val handle: SavedStateHandle) : ActionViewModel(container) {
    private val notes = container.notes

    /** Where the page goes once nothing can be lost on the way. */
    sealed interface Leave {
        data object Back : Leave
        data class Diary(val date: LocalDate) : Leave
    }

    private val _noteId = MutableStateFlow(handle.get<String>(KEY_ID) ?: handle.get<String>(ARG_ID)?.takeIf { it.isNotEmpty() })
    val noteId: StateFlow<String?> = _noteId.asStateFlow()
    private val _session = MutableStateFlow<NoteSession?>(null)
    val session: StateFlow<NoteSession?> = _session.asStateFlow()
    /** The page was opened on a note that no longer exists, or that went to the trash. */
    val missing = MutableStateFlow(false)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** A way out the user took while the text could neither be saved nor drafted: asked before it is dropped. */
    val unsavedLeave = MutableStateFlow<Leave?>(null)

    /** The topic of a note that has no row yet; its first write puts it there. */
    val pendingTopic = MutableStateFlow(handle.get<String>(ARG_TOPIC)?.takeIf { it.isNotEmpty() })

    val note: StateFlow<Note?> = _noteId.flatMapLatest { id -> if (id == null) flowOf(null) else notes.observe(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val sources: StateFlow<List<NoteSource>> = _noteId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else notes.observeSources(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val topics: StateFlow<List<NoteTopic>> = notes.observeTopics().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Saves outlive the screen: the app scope plus the epoch bound for this screen. */
    private val saveScope: CoroutineScope = container.appScope + epoch
    private var statusJob: Job? = null
    private var leaving: Job? = null

    init {
        open()
    }

    private fun open() {
        statusJob?.cancel()
        launchBound {
            val id = _noteId.value
            val n = id?.let { notes.note(it) }
            if (id != null && (n == null || n.deletedAt != null)) {
                _session.value = null
                missing.value = true
                return@launchBound
            }
            val s = NoteSession(notes, saveScope, onCreated = ::created)
            s.topicForNew = pendingTopic.value
            s.open(n)
            _session.value = s
            statusJob = launchBound { watch(s) }
        }
    }

    // Called on the save's thread; the handle and the id flow belong to the main thread.
    private fun created(id: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            handle[KEY_ID] = id
            _noteId.value = id
        }
    }

    private suspend fun watch(s: NoteSession) {
        launchBound { s.messages.collect { messages.emit(it) } }
        s.status.collect { st ->
            // The text went into a draft; reload the note from the database.
            if (st == NoteSession.Status.ENDED && _session.value === s) {
                s.close()
                open()
            }
        }
    }

    fun onInput(text: String) {
        _session.value?.onInput(text)
    }

    /** Going to the background or putting the keyboard away. */
    fun flush() {
        val s = _session.value ?: return
        saveScope.launch { s.saveNow() }
    }

    /**
     * Goes to [target] through [go] once the text is safe: saved, or in a draft. The session stays
     * on screen until then; text that could not even go into a draft is never dropped without asking.
     */
    fun leave(target: Leave, go: (Leave) -> Unit) {
        val s = _session.value ?: return go(target)
        leaving?.cancel()
        leaving = viewModelScope.launch {
            // On the save scope, so the save lands even if this screen goes away meanwhile.
            val saved = saveScope.async { s.saveBeforeLeaving() }.await()
            when {
                saved -> go(target)
                s.status.value == NoteSession.Status.UNSAVED -> unsavedLeave.value = target
                // A restore holds the gate; it collects this text itself.
                else -> messages.emit("正在恢复数据，请稍后再离开")
            }
        }
    }

    fun setTopic(topicId: String?) {
        val id = _session.value?.noteId ?: note.value?.id
        if (id == null) {
            pendingTopic.value = topicId
            _session.value?.topicForNew = topicId
        } else {
            action { notes.setTopic(id, topicId) }
        }
    }

    fun createTopic(name: String) = action {
        val topicId = notes.createTopic(name)
        setTopic(topicId)
    }

    fun togglePin() {
        val n = note.value ?: return
        action { notes.setPinned(n.id, !n.pinned) }
    }

    /** Saves first, so what is in the trash is what was on screen. */
    fun delete(onDone: () -> Unit) = action(onDone) {
        val s = _session.value ?: return@action
        s.saveNow()
        val id = s.noteId ?: return@action
        notes.delete(id)
        s.close()
        container.announce("笔记已移到回收站")
    }

    override fun onCleared() {
        val s = _session.value
        if (s != null) saveScope.launch { s.saveNow(); s.close() }
        super.onCleared()
    }

    companion object {
        /** The route's arguments. */
        const val ARG_ID = "id"
        const val ARG_TOPIC = "topic"
        /** The id a new note got on its first write, surviving process death with the page. */
        const val KEY_ID = "noteId"
    }
}
