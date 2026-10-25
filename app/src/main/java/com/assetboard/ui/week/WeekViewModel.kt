package com.assetboard.ui.week

import com.assetboard.domain.AppClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.assetboard.AppContainer
import com.assetboard.data.AppendResult
import com.assetboard.data.DiaryEntry
import com.assetboard.data.Note
import com.assetboard.data.NoteTopic
import com.assetboard.data.SourceRef
import com.assetboard.data.WeekSession
import com.assetboard.data.WeeklyDraft
import com.assetboard.data.WeeklyReview
import com.assetboard.data.Weeks
import com.assetboard.domain.WeekStats
import com.assetboard.domain.todo.TodoViews
import com.assetboard.ui.ActionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.time.LocalDate

/** A message after an extract, with the note to open when there is one. */
data class WeekMessage(val text: String, val noteId: String? = null)

/**
 * One week's review page: the week in numbers (worked out on opening), its seven diary days,
 * the notes taken from it, and the review text, which saves itself like a diary entry. The page
 * can step to the week before or after; the week shown is kept in [handle].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WeekViewModel(private val container: AppContainer, private val handle: SavedStateHandle) : ActionViewModel(container) {
    private val weeks = container.weeks
    private val notes = container.notes

    private val _start = MutableStateFlow(
        Weeks.start(handle.get<String>(KEY_START)?.takeIf { it.isNotEmpty() }?.let(LocalDate::parse) ?: today()),
    )
    val start: StateFlow<LocalDate> = _start.asStateFlow()
    private val _session = MutableStateFlow<WeekSession?>(null)
    val session: StateFlow<WeekSession?> = _session.asStateFlow()
    val stats = MutableStateFlow<WeekStats?>(null)
    val messages = MutableSharedFlow<WeekMessage>(extraBufferCapacity = 4)
    /** Where the page goes once nothing can be lost on the way. */
    sealed interface Leave {
        data object Back : Leave
        data class Week(val start: LocalDate) : Leave
        data class Diary(val date: LocalDate) : Leave
    }

    /** A way out the user took while the text could neither be saved nor drafted: asked before it is dropped. */
    val unsavedLeave = MutableStateFlow<Leave?>(null)

    val review: StateFlow<WeeklyReview?> = _start.flatMapLatest { weeks.observe(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val drafts: StateFlow<List<WeeklyDraft>> = _start.flatMapLatest { weeks.observeDrafts(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val days: StateFlow<List<DiaryEntry>> = _start.flatMapLatest { container.diary.observeBetween(it, it.plusDays(6)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val taken: StateFlow<List<Note>> = _start.flatMapLatest { notes.observeFromWeek(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allNotes: StateFlow<List<Note>> = notes.observeNotes().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val topics: StateFlow<List<NoteTopic>> = notes.observeTopics().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val saveScope: CoroutineScope = container.appScope + epoch
    private var statusJob: Job? = null
    private var leaving: Job? = null

    init {
        open(_start.value)
    }

    fun today(): LocalDate = TodoViews.localDate(AppClock.millis())

    /** Steps to the week starting [to], saving this one first. */
    fun go(to: LocalDate) = leave(Leave.Week(Weeks.start(to))) { open(Weeks.start(to)) }

    /** Back to the diary tab, or the page before: the review is saved first either way. */
    fun leave(target: Leave, go: (Leave) -> Unit) {
        val s = _session.value ?: return go(target)
        leaving?.cancel()
        leaving = viewModelScope.launch {
            // On the save scope, so the save lands even if this screen goes away meanwhile.
            val saved = saveScope.async { s.saveBeforeLeaving() }.await()
            when {
                saved -> {
                    s.close()
                    go(target)
                }
                s.status.value == WeekSession.Status.UNSAVED -> unsavedLeave.value = target
                // A restore holds the gate and collects this text itself; otherwise a save is behind.
                else -> messages.emit(WeekMessage(if (container.gate.frozen.value) "正在恢复数据，请稍后再离开" else "刚才的输入还没写完，请停手片刻再离开"))
            }
        }
    }

    private fun open(start: LocalDate) {
        statusJob?.cancel()
        _start.value = start
        handle[KEY_START] = start.toString()
        _session.value = null
        stats.value = null
        launchBound {
            val s = WeekSession(weeks, start, saveScope)
            s.open(weeks.reviewOf(start))
            if (_start.value != start) return@launchBound
            _session.value = s
            statusJob = launchBound { watch(s) }
        }
        launchBound { loadStats(start) }
    }

    private suspend fun loadStats(start: LocalDate) {
        val end = start.plusDays(6)
        val diaries = container.diary.observeBetween(start, end).first().filter { it.body.isNotBlank() }.map { LocalDate.parse(it.date) }
        val checks = container.db.habitDao().all()
        val todos = container.todos.loadOnce().todos
        val weights = container.db.healthDao().all()
        val ledger = container.repository.loadOnce()
        val habits = container.habits.observeAll().first()
        val s = WeekStats.of(start, today(), diaries, checks, todos, weights, ledger, AppClock.millis(), allHabits = habits)
        if (_start.value == start) stats.value = s
    }

    /** The session's messages and status together: both end with the status job, on reload or on leaving. */
    private suspend fun watch(s: WeekSession): Unit = coroutineScope {
        launch { s.messages.collect { messages.emit(WeekMessage(it)) } }
        s.status.collect { st ->
            // The text went into a draft: reload the week from the database.
            if (st == WeekSession.Status.ENDED && _session.value === s) {
                s.close()
                open(s.weekStart)
            }
        }
    }

    fun onInput(text: String) {
        _session.value?.onInput(text)
    }

    fun flush() {
        val s = _session.value ?: return
        saveScope.launch { s.saveNow() }
    }

    /** After the user copied the text: leave without saving. */
    fun leaveUnsaved(go: (Leave) -> Unit) {
        val target = unsavedLeave.value ?: return
        unsavedLeave.value = null
        _session.value?.close()
        _session.value = null
        if (target is Leave.Week) open(target.start) else go(target)
    }

    fun appendDraft(d: WeeklyDraft) = action {
        val s = _session.value ?: return@action
        val current = s.text.value
        s.onInput(if (current.isEmpty()) d.text else current.trimEnd() + "\n\n" + d.text)
        if (s.saveNow()) weeks.discardDraft(d.id)
    }

    fun discardDraft(d: WeeklyDraft) = action { weeks.discardDraft(d.id) }

    /** Saves first, so what goes to the trash is what was on screen. */
    fun delete() = action {
        val s = _session.value ?: return@action
        s.saveNow()
        val id = s.reviewId ?: return@action
        weeks.delete(id)
        s.close()
        open(s.weekStart)
        container.announce("周复盘已移到回收站")
    }

    fun extractToNew(source: SourceRef, body: String, onDone: (String) -> Unit) = action {
        onDone(notes.create(body, source))
    }

    /** Only the result is reported: an append that went into a draft or failed never reads as done. */
    fun extractAppend(source: SourceRef, note: Note, block: String) = action {
        when (val r = notes.append(note.id, block, source)) {
            AppendResult.Appended -> messages.emit(WeekMessage("已追加到《${note.title}》", note.id))
            AppendResult.SavedAsDraft -> messages.emit(WeekMessage("保存冲突，内容已存为草稿，可在笔记页处理"))
            is AppendResult.Failed -> error.value = r.reason
        }
    }

    override fun onCleared() {
        val s = _session.value
        if (s != null) saveScope.launch { s.saveNow(); s.close() }
        super.onCleared()
    }

    companion object {
        /** The route's argument and the saved week: its Monday, or any day in it. */
        const val KEY_START = "start"
    }
}
