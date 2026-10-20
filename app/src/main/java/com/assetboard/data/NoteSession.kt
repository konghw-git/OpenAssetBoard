package com.assetboard.data

import kotlinx.coroutines.CoroutineScope

/** The writes a note editor makes; a seam for tests. */
interface NoteStore {
    /** [sources] land in the same transaction as [text]; [topicId] places a note that has no row yet. */
    suspend fun saveBody(noteId: String?, expectedRev: Long?, text: String, sources: List<SourceRef>, topicId: String?): BodySave
    suspend fun saveDraft(noteId: String?, text: String, reason: String): String
    fun register(editor: EditorHandle)
    fun unregister(editor: EditorHandle)
}

/**
 * One editing session on one note: the [TextSession] autosave and conflict rules, plus appends
 * from the diary that arrive while its last save has not landed yet. An append goes into the
 * session's text, so the text stays the one source of truth and nothing races the editor.
 */
class NoteSession(
    private val store: NoteStore,
    scope: CoroutineScope,
    clock: () -> Long = System::currentTimeMillis,
    /** First body write of a new note: the page remembers the id it now has. */
    private val onCreated: (noteId: String) -> Unit = {},
) : TextSession<List<SourceRef>>(scope, clock) {
    typealias Status = TextSession.Status

    val noteId: String? get() = rowId

    /** Where a note that has no row yet goes on its first write; the page's topic chip sets it. */
    @Volatile var topicForNew: String? = null

    /** Sources of appends whose text has not landed yet; guarded by [snapshotLock]. */
    private val pendingSources = mutableListOf<SourceRef>()

    fun open(note: Note?) = load(note?.id, note?.bodyRev, note?.body.orEmpty())

    override fun register() = store.register(this)
    override fun unregister() = store.unregister(this)
    override fun captureExtra(): List<SourceRef> = pendingSources.toList()
    override fun hasPending(extra: List<SourceRef>) = extra.isNotEmpty()

    // Only what that snapshot carried: another append may have arrived while it was saving.
    override fun extraLanded(extra: List<SourceRef>) {
        synchronized(snapshotLock) { extra.forEach { pendingSources.remove(it) } }
    }

    override suspend fun writeBody(id: String?, expectedRev: Long?, text: String, extra: List<SourceRef>) =
        store.saveBody(id, expectedRev, text, extra, topicForNew)

    override suspend fun writeDraft(id: String?, text: String, reason: String) {
        store.saveDraft(id, text, reason)
    }

    override fun pending(id: String?, expectedRev: Long?, text: String, extra: List<SourceRef>) =
        PendingText.Note(id, expectedRev, text, extra, topicForNew)

    override fun onSaved(saved: BodySave.Saved) {
        if (saved.created) onCreated(saved.entryId)
    }

    /**
     * Appends [tail] and saves at once. Null when this session is no longer live: the caller
     * then writes to the row directly, which is the only truth left.
     */
    suspend fun append(tail: String, source: SourceRef): AppendResult? {
        val ok = changeAndSave({ pendingSources += source }) { NoteText.join(it, tail) } ?: return null
        return when {
            ok -> AppendResult.Appended
            // The save conflicted: the text, append included, is in a draft; its source goes with the session.
            status.value == Status.ENDED -> AppendResult.SavedAsDraft
            status.value == Status.UNSAVED -> AppendResult.Failed("写入失败，这次没有追加")
            else -> AppendResult.Failed("正在恢复数据，暂时无法追加")
        }
    }
}

sealed interface AppendResult {
    data object Appended : AppendResult
    data object SavedAsDraft : AppendResult
    data class Failed(val reason: String) : AppendResult
}
