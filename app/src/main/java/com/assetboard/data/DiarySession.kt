package com.assetboard.data

import kotlinx.coroutines.CoroutineScope
import java.time.LocalDate

/**
 * One editing session on one day's entry: the [TextSession] autosave and conflict rules, plus
 * the day it belongs to, the weather fetch on the first write and the midnight rule's [touched].
 */
class DiarySession(
    private val store: DiaryStore,
    val date: LocalDate,
    scope: CoroutineScope,
    clock: () -> Long = System::currentTimeMillis,
    /** First body write of a new entry: time to fetch the weather. */
    private val onCreated: (entryId: String) -> Unit = {},
) : TextSession<Unit>(scope, clock) {
    typealias Status = TextSession.Status

    val entryId: String? get() = rowId

    fun open(entry: DiaryEntry?) = load(entry?.id, entry?.bodyRev, entry?.body.orEmpty())

    override fun register() = store.register(this)
    override fun unregister() = store.unregister(this)
    override fun captureExtra() = Unit

    override suspend fun writeBody(id: String?, expectedRev: Long?, text: String, extra: Unit) = store.saveBody(date, id, expectedRev, text)

    override suspend fun writeDraft(id: String?, text: String, reason: String) {
        store.saveDraft(date, id, text, reason)
    }

    override fun pending(id: String?, expectedRev: Long?, text: String, extra: Unit) = PendingText.Diary(date.toString(), id, expectedRev, text)

    override fun onSaved(saved: BodySave.Saved) {
        if (saved.created) onCreated(saved.entryId)
    }
}
