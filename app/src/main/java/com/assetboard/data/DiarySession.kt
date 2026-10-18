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
import java.time.LocalDate

/**
 * One editing session on one day's entry. It keeps the expected `body_rev`, saves after a 1 s
 * pause and at most 5 s apart while typing, and ends on the first failed save: the text then
 * goes into a draft, or stays in the editor with a red bar if even that fails.
 *
 * [scope] outlives the screen (the app scope plus the session's epoch), so a save started when
 * leaving the page still lands.
 */
class DiarySession(
    private val store: DiaryStore,
    val date: LocalDate,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** First body write of a new entry: time to fetch the weather. */
    private val onCreated: (entryId: String) -> Unit = {},
) : DiaryEditorHandle {
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

    @Volatile var entryId: String? = null
        private set
    @Volatile private var expectedRev: Long? = null
    @Volatile private var savedText = ""
    /** Typed anything since this session opened (saved or not); decides the midnight rule. */
    @Volatile var touched = false
        private set

    private var firstDirtyAt: Long? = null
    /** When the last save started; continuous typing measures the 5 s window from here. */
    private var lastSaveAt: Long? = null
    private var timer: Job? = null
    private val saveLock = Mutex()
    @Volatile private var handedToRestore: String? = null

    /** Commit timestamps, for the D9 check. */
    val commits = mutableListOf<Long>()

    fun open(entry: DiaryEntry?) {
        entryId = entry?.id
        expectedRev = entry?.bodyRev
        savedText = entry?.body.orEmpty()
        _text.value = savedText
        store.register(this)
    }

    fun close() {
        timer?.cancel()
        store.unregister(this)
    }

    val dirty: Boolean get() = _text.value != savedText

    fun onInput(value: String) {
        if (value == _text.value) return
        _text.value = value
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

    /** Saves now if anything is unsaved. Returns false while the save could not land. */
    suspend fun saveNow(): Boolean = saveLock.withLock {
        if (_status.value != Status.READY) return@withLock false
        val snapshot = _text.value
        if (snapshot == savedText) {
            firstDirtyAt = null
            return@withLock true
        }
        val dirtySince = firstDirtyAt
        firstDirtyAt = null
        lastSaveAt = clock()
        try {
            when (val r = store.saveBody(date, entryId, expectedRev, snapshot)) {
                is BodySave.Saved -> {
                    commits += clock()
                    entryId = r.entryId
                    expectedRev = r.rev
                    savedText = snapshot
                    _savedAt.value = clock()
                    if (r.created) onCreated(r.entryId)
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

    private suspend fun endWithDraft(reason: String) {
        val snapshot = _text.value
        try {
            store.saveDraft(date, entryId, snapshot, reason)
            _status.value = Status.ENDED
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

    override fun pendingForRestore(): PendingDiaryText? {
        if (_status.value == Status.ENDED) return null
        val snapshot = _text.value
        if (snapshot == savedText) return null
        handedToRestore = snapshot
        return PendingDiaryText(date.toString(), entryId, expectedRev, snapshot)
    }

    override fun flushedByRestore(entryId: String?, newRev: Long?) {
        val snapshot = handedToRestore ?: return
        handedToRestore = null
        if (newRev != null) {
            this.entryId = entryId
            expectedRev = newRev
            savedText = snapshot
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
