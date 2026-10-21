package com.assetboard.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArraySet

/** Unsaved editor text handed to the restore coordinator; the editor does not write it itself. */
sealed interface PendingText {
    val text: String

    data class Diary(val date: String, val entryId: String?, val expectedRev: Long?, override val text: String) : PendingText

    data class Week(val weekStart: String, val reviewId: String?, val expectedRev: Long?, override val text: String) : PendingText

    /** [topicId] is where a note that has no row yet goes; [sources] land with the text. */
    data class Note(
        val noteId: String?,
        val expectedRev: Long?,
        override val text: String,
        val sources: List<SourceRef>,
        val topicId: String? = null,
    ) : PendingText
}

/** A live editor, as the restore coordinator sees it. */
interface EditorHandle {
    fun pendingForRestore(): PendingText?

    /** The coordinator committed the text: into row [id] at [newRev], or as a draft when [newRev] is null. */
    fun flushedByRestore(id: String?, newRev: Long?)
}

typealias DiaryEditorHandle = EditorHandle

/** Every open editor, diary and note alike; the restore commits their unsaved text first. */
class EditorRegistry : Iterable<EditorHandle> {
    private val editors = CopyOnWriteArraySet<EditorHandle>()

    fun add(editor: EditorHandle) { editors += editor }
    fun remove(editor: EditorHandle) { editors -= editor }
    operator fun contains(editor: EditorHandle) = editor in editors
    override fun iterator(): Iterator<EditorHandle> = editors.iterator()
}

/**
 * One editing session on one row's text. It keeps the expected `body_rev`, saves after a 1 s
 * pause and at most 4.5 s apart while typing, and ends on the first failed save: the text then
 * goes into a draft, or stays in the editor with a red bar if even that fails.
 *
 * [X] is whatever must land in the same transaction as a text snapshot (a note's pending
 * sources); it is captured together with the text, under one lock.
 *
 * [scope] outlives the screen (the app scope plus the session's epoch), so a save started when
 * leaving the page still lands.
 */
abstract class TextSession<X>(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) : EditorHandle {
    enum class Status {
        READY,
        /** A save failed and the draft was written; the page should reload. */
        ENDED,
        /** Neither the body nor a draft could be written; the text exists only in the editor. */
        UNSAVED,
    }

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()
    private val _status = MutableStateFlow(Status.READY)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    private val _savedAt = MutableStateFlow<Long?>(null)
    /** When the body last landed in this session; drives "已保存 21:03". */
    val savedAt: StateFlow<Long?> = _savedAt.asStateFlow()

    /** The row being edited; null until the first write of a new one. */
    @Volatile var rowId: String? = null
        private set
    @Volatile private var expectedRev: Long? = null
    @Volatile private var savedText = ""
    /** Typed anything since this session opened (saved or not). */
    @Volatile var touched = false
        private set

    private var firstDirtyAt: Long? = null
    /** When the last save started; continuous typing measures the window from here. */
    private var lastSaveAt: Long? = null
    private var timer: Job? = null
    private val saveLock = Mutex()
    /** Guards a text change that comes with extra (an append) against a snapshot taken in between. */
    protected val snapshotLock = Any()
    @Volatile private var handedToRestore: Pair<String, X>? = null

    /** Commit timestamps, for the D9 check. */
    val commits = mutableListOf<Long>()

    protected abstract fun register()
    protected abstract fun unregister()

    /** What goes with the text now, read under [snapshotLock]. */
    protected abstract fun captureExtra(): X
    /** Whether [extra] still has something to write even when the text itself is saved. */
    protected open fun hasPending(extra: X): Boolean = false
    /** [extra] landed together with the text it was captured with. */
    protected open fun extraLanded(extra: X) {}

    protected abstract suspend fun writeBody(id: String?, expectedRev: Long?, text: String, extra: X): BodySave
    protected abstract suspend fun writeDraft(id: String?, text: String, reason: String)
    protected abstract fun pending(id: String?, expectedRev: Long?, text: String, extra: X): PendingText
    protected open fun onSaved(saved: BodySave.Saved) {}

    protected fun load(id: String?, rev: Long?, body: String) {
        rowId = id
        expectedRev = rev
        savedText = body
        _text.value = body
        register()
    }

    fun close() {
        timer?.cancel()
        unregister()
    }

    val dirty: Boolean get() = _text.value != savedText

    fun onInput(value: String) {
        synchronized(snapshotLock) {
            // An ended session is about to be reloaded: what its draft holds is all it keeps.
            if (value == _text.value || _status.value == Status.ENDED) return
            _text.value = value
        }
        touched = true
        if (_status.value != Status.READY) return
        val now = clock()
        val first = firstDirtyAt ?: now.also { firstDirtyAt = it }
        // Typing that never paused since the last save keeps that save's window.
        val anchor = lastSaveAt?.takeIf { first - it <= IDLE_MS } ?: first
        val due = minOf(now + IDLE_MS, anchor + MAX_GAP_MS)
        timer?.cancel()
        timer = scope.launch {
            delay((due - now).coerceAtLeast(0))
            // Detached from the timer, so the next keystroke cannot cancel a save in flight.
            scope.launch { saveNow() }
        }
    }

    /**
     * Changes the text with [change] (and the extra with [withExtra], atomically against any
     * snapshot) and saves it, all under the save lock, so no other save can end the session in
     * between. Null when the session is no longer live and nothing was changed.
     */
    protected suspend fun changeAndSave(withExtra: () -> Unit, change: (String) -> String): Boolean? = saveLock.withLock {
        if (_status.value != Status.READY) return@withLock null
        synchronized(snapshotLock) {
            withExtra()
            _text.value = change(_text.value)
        }
        touched = true
        saveLocked()
    }

    /** Saves now if anything is unsaved. Returns false while the save could not land. */
    suspend fun saveNow(): Boolean = saveLock.withLock { saveLocked() }

    /** True once nothing of this session can be lost: the body is saved, or the text went into a draft. */
    suspend fun saveBeforeLeaving(): Boolean {
        // Typing can go on during a save; a few rounds catch up with it.
        repeat(3) {
            if (!saveNow()) return _status.value == Status.ENDED
            if (!dirty) return true
        }
        return !dirty
    }

    private suspend fun saveLocked(): Boolean {
        if (_status.value != Status.READY) return false
        val (snapshot, extra) = synchronized(snapshotLock) { _text.value to captureExtra() }
        if (snapshot == savedText && !hasPending(extra)) {
            firstDirtyAt = null
            return true
        }
        val dirtySince = firstDirtyAt
        firstDirtyAt = null
        lastSaveAt = clock()
        return try {
            when (val r = writeBody(rowId, expectedRev, snapshot, extra)) {
                is BodySave.Saved -> {
                    commits += clock()
                    rowId = r.entryId
                    expectedRev = r.rev
                    savedText = snapshot
                    extraLanded(extra)
                    _savedAt.value = clock()
                    onSaved(r)
                    true
                }
                is BodySave.Conflict -> {
                    endWithDraft(r.reason)
                    false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestoreInProgress) {
            // The restore coordinator collects this text through its own channel.
            firstDirtyAt = dirtySince ?: clock()
            false
        } catch (e: Exception) {
            endWithDraft("保存失败：" + (e.message ?: e.javaClass.simpleName))
            false
        }
    }

    /**
     * Typing goes on while the draft is written, so the session ends only once a draft holds the
     * text as it stands: anything typed meanwhile goes into another, complete one.
     */
    private suspend fun endWithDraft(reason: String) {
        try {
            while (true) {
                val snapshot = _text.value
                writeDraft(rowId, snapshot, reason)
                val ended = synchronized(snapshotLock) {
                    (_text.value == snapshot).also { if (it) _status.value = Status.ENDED }
                }
                if (ended) break
            }
            _messages.tryEmit("保存冲突，未保存的内容已存为草稿")
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestoreInProgress) {
            firstDirtyAt = clock()
        } catch (e: Exception) {
            _status.value = Status.UNSAVED
        }
    }

    // ------------------------------------------------------------ restore channel

    override fun pendingForRestore(): PendingText? {
        if (_status.value == Status.ENDED) return null
        val (snapshot, extra) = synchronized(snapshotLock) { _text.value to captureExtra() }
        if (snapshot == savedText && !hasPending(extra)) return null
        handedToRestore = snapshot to extra
        return pending(rowId, expectedRev, snapshot, extra)
    }

    override fun flushedByRestore(id: String?, newRev: Long?) {
        val (snapshot, extra) = handedToRestore ?: return
        handedToRestore = null
        if (newRev != null) {
            rowId = id
            expectedRev = newRev
            savedText = snapshot
            extraLanded(extra)
        } else {
            _status.value = Status.ENDED
        }
    }

    companion object {
        const val IDLE_MS = 1_000L
        // Under the 5 s acceptance bound, leaving room for the transaction itself.
        const val MAX_GAP_MS = 4_500L
    }
}
