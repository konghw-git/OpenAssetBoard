package com.assetboard.data

import com.assetboard.domain.AppClock
import com.assetboard.domain.LedgerException
import com.assetboard.domain.ensure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/**
 * Every note write goes through one serial queue (a fair mutex) under the write gate, as the
 * diary's do: pinning, changing the topic, deleting and appending can never overwrite each
 * other. Body writes are conditional on `body_rev`, and only they (and the trash) bump it.
 * [onChanged] fires after every write that went through (it refreshes the home-screen widget).
 */
class NoteRepository(
    private val db: AppDatabase,
    private val gate: WriteGate,
    private val editors: EditorRegistry,
    private val clock: () -> Long = AppClock::millis,
    private val onChanged: () -> Unit = {},
) : NoteStore {
    private val dao = db.noteDao()
    private val queue = Mutex()

    /** Fault injection for tests: thrown inside the body or draft transaction. */
    @Volatile internal var failBodyWrites: Boolean = false
    @Volatile internal var failDraftWrites: Boolean = false

    override fun register(editor: EditorHandle) = editors.add(editor)
    override fun unregister(editor: EditorHandle) = editors.remove(editor)

    private suspend fun <T> serial(block: suspend () -> T): T = queue.withLock { gate.write { block() } }.also { onChanged() }

    fun observeNotes(): Flow<List<Note>> = dao.observeLive()
    fun observe(id: String): Flow<Note?> = dao.observeNote(id)
    fun observeTopics(): Flow<List<NoteTopic>> = dao.observeTopics()
    fun observeSources(noteId: String): Flow<List<NoteSource>> = dao.observeSources(noteId)
    fun observeDrafts(): Flow<List<NoteDraft>> = dao.observeDrafts()
    /** Live notes with something taken from [date]'s diary, for that day's page. */
    fun observeFromDay(date: LocalDate): Flow<List<Note>> = dao.observeFromSource(SourceRef.DAY, date.toString())
    /** Live notes taken from the week starting [start]: its days or its review. */
    fun observeFromWeek(start: LocalDate): Flow<List<Note>> = dao.observeFromWeek(start.toString(), start.plusDays(6).toString())
    suspend fun note(id: String): Note? = dao.note(id)

    suspend fun search(query: String): List<Note> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return dao.search(escaped)
    }

    // ------------------------------------------------------------ body

    override suspend fun saveBody(noteId: String?, expectedRev: Long?, text: String, sources: List<SourceRef>, topicId: String?): BodySave = serial {
        if (failBodyWrites) throw LedgerException("模拟写盘失败")
        val now = clock()
        if (noteId == null) {
            val n = Note(newId(), topicId?.takeIf { dao.topic(it) != null }, text, bodyRev = 1, position = dao.minPosition() - 1, createdAt = now, updatedAt = now)
            dao.insert(n)
            addSources(n.id, sources, now)
            BodySave.Saved(n.id, 1, created = true)
        } else if (dao.updateBody(noteId, text, expectedRev ?: -1, now) == 1) {
            addSources(noteId, sources, now)
            BodySave.Saved(noteId, (expectedRev ?: -1) + 1, created = false)
        } else {
            BodySave.Conflict(conflictReason(dao.note(noteId)))
        }
    }

    override suspend fun saveDraft(noteId: String?, text: String, reason: String): String = serial {
        if (failDraftWrites) throw LedgerException("模拟写盘失败")
        val id = newId()
        dao.insertDraft(NoteDraft(id, noteId?.takeIf { dao.note(it) != null }, text, reason, clock()))
        id
    }

    private suspend fun addSources(noteId: String, sources: List<SourceRef>, now: Long) {
        for (s in sources) dao.insertSource(NoteSource(noteId, s.kind, s.ref, now))
    }

    /**
     * Adds [tail] to the end of the note, with [source]. A note whose editor is still saving gets
     * it through that session; otherwise the row is written directly, in one transaction.
     */
    suspend fun append(noteId: String, tail: String, source: SourceRef): AppendResult {
        for (e in editors) {
            if (e is NoteSession && e.noteId == noteId) e.append(tail, source)?.let { return it }
        }
        return try {
            serial {
                val n = dao.note(noteId)?.takeIf { it.deletedAt == null } ?: throw LedgerException("这篇笔记已被删除")
                val now = clock()
                ensure(dao.updateBody(noteId, NoteText.join(n.body, tail), n.bodyRev, now) == 1) { "这篇笔记已在别处修改" }
                addSources(noteId, listOf(source), now)
            }
            AppendResult.Appended
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppendResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** A new note with [text], taken from [source]; returns its id. */
    suspend fun create(text: String, source: SourceRef?, topicId: String? = null): String = serial {
        val now = clock()
        val n = Note(newId(), topicId?.takeIf { dao.topic(it) != null }, text, bodyRev = 1, position = dao.minPosition() - 1, createdAt = now, updatedAt = now)
        dao.insert(n)
        if (source != null) addSources(n.id, listOf(source), now)
        n.id
    }

    // ------------------------------------------------------------ drafts

    /** The draft becomes a note of its own, in the topic of the note it came from; returns its id. */
    suspend fun draftToNote(draftId: String): String = serial {
        val d = dao.draft(draftId) ?: throw LedgerException("这份草稿已经处理过了")
        val now = clock()
        val topic = d.noteId?.let { dao.note(it) }?.topicId
        val n = Note(newId(), topic, d.text, bodyRev = 1, position = dao.minPosition() - 1, createdAt = now, updatedAt = now)
        dao.insert(n)
        dao.deleteDraft(d.id)
        n.id
    }

    suspend fun discardDraft(id: String) = serial { dao.deleteDraft(id) }

    // ------------------------------------------------------------ columns beside the body

    suspend fun setPinned(id: String, pinned: Boolean) = serial { dao.setPinned(id, pinned) }

    suspend fun setTopic(id: String, topicId: String?) = serial {
        ensure(topicId == null || dao.topic(topicId) != null) { "这个主题已被删除" }
        dao.setTopic(id, topicId)
    }

    /**
     * Puts the notes of [ids] in that order, among the places they already hold, so notes not
     * in the list (another topic, the other section) keep theirs.
     */
    suspend fun reorder(ids: List<String>) = serial {
        var all = dao.all().sortedWith(compareBy<Note> { it.position }.thenByDescending { it.updatedAt })
        // Ties (an old backup, say) are spread out first, in the order they show.
        if (all.map { it.position }.toSet().size < all.size) {
            all = all.mapIndexed { i, n -> n.copy(position = i.toLong()) }
            for (n in all) dao.setPosition(n.id, n.position)
        }
        val byId = all.associateBy { it.id }
        val moved = ids.mapNotNull { byId[it] }
        val slots = moved.map { it.position }.sorted()
        moved.forEachIndexed { i, n -> if (n.position != slots[i]) dao.setPosition(n.id, slots[i]) }
    }

    /**
     * Today's review: the note left alone the longest (a week at least), the same one all day.
     * The pick lives in `app_meta` (device-local, never backed up); a day change, or a pick that
     * no longer exists or was pinned since, chooses again. Null when nothing qualifies or the
     * card was put away for today.
     */
    suspend fun reviewPick(today: LocalDate): Note? = serial {
        val meta = db.metaDao()
        val stored = meta.get(REVIEW_PICK)?.split('|')
        if (stored != null && stored.size >= 2 && stored[0] == today.toString()) {
            if (stored.getOrNull(2) == REVIEW_DONE) return@serial null
            dao.note(stored[1])?.takeIf { it.deletedAt == null && !it.pinned }?.let { return@serial it }
        }
        val n = dao.reviewCandidate(clock() - REVIEW_MIN_AGE_MS) ?: return@serial null
        meta.put(AppMeta(REVIEW_PICK, "$today|${n.id}"))
        n
    }

    /** Puts today's review away: the note counts as reviewed and the card stays hidden until tomorrow. */
    suspend fun dismissReview(today: LocalDate, noteId: String) = serial {
        dao.setReviewed(noteId, clock())
        db.metaDao().put(AppMeta(REVIEW_PICK, "$today|$noteId|$REVIEW_DONE"))
    }

    suspend fun setReviewed(id: String, at: Long? = clock()) = serial { dao.setReviewed(id, at) }

    // ------------------------------------------------------------ trash

    /** Moves the note to the trash as its own batch; an open session on it becomes stale. */
    suspend fun delete(id: String): String = serial {
        val n = dao.note(id)?.takeIf { it.deletedAt == null } ?: throw LedgerException("笔记不存在")
        val batch = newId()
        dao.update(n.copy(deletedAt = clock(), deleteBatch = batch, bodyRev = n.bodyRev + 1))
        batch
    }

    /** Moves the live notes of [ids] to the trash as one batch, for one undo; returns it. */
    suspend fun deleteMany(ids: List<String>): String = serial {
        val batch = newId()
        val now = clock()
        for (id in ids.distinct()) {
            val n = dao.note(id)?.takeIf { it.deletedAt == null } ?: continue
            dao.update(n.copy(deletedAt = now, deleteBatch = batch, bodyRev = n.bodyRev + 1))
        }
        batch
    }

    /** Restores the notes of [batch]; notes never collide, so there is nothing to ask. */
    suspend fun restore(batch: String): Boolean = serial {
        val deleted = dao.inBatch(batch).filter { it.deletedAt != null }
        for (n in deleted) dao.update(n.copy(deletedAt = null, deleteBatch = null, bodyRev = n.bodyRev + 1))
        deleted.isNotEmpty()
    }

    // ------------------------------------------------------------ topics

    suspend fun createTopic(name: String): String = serial {
        val topics = dao.topics()
        val clean = checkTopicName(name, topics.map { it.name })
        val t = NoteTopic(newId(), clean, sortOrder = (topics.maxOfOrNull { it.sortOrder } ?: -1) + 1, createdAt = clock())
        dao.insertTopic(t)
        t.id
    }

    suspend fun renameTopic(id: String, name: String) = serial {
        val clean = checkTopicName(name, dao.topics().filter { it.id != id }.map { it.name })
        dao.renameTopic(id, clean)
    }

    suspend fun moveTopic(id: String, delta: Int) = serial {
        val topics = dao.topics().toMutableList()
        val i = topics.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in topics.indices) return@serial
        topics.add(j, topics.removeAt(i))
        dao.updateTopics(topics.mapIndexed { idx, t -> t.copy(sortOrder = idx) })
    }

    /** Live notes that would move to 未归类. Notes in the trash move too, but are not counted. */
    suspend fun liveCountIn(topicId: String): Int = dao.liveCountIn(topicId)

    /** The topic goes; its notes, the trashed ones too, become 未归类 through the foreign key. */
    suspend fun deleteTopic(id: String) = serial { dao.deleteTopic(id) }

    companion object {
        const val TOPIC_NAME_MAX = 16
        const val REVIEW_PICK = "note_review_pick"
        private const val REVIEW_DONE = "done"
        /** A note touched within the last week is too fresh to come back for review. */
        const val REVIEW_MIN_AGE_MS = 7L * 24 * 60 * 60 * 1000

        fun conflictReason(n: Note?) = when {
            n == null -> "这篇笔记已被永久删除"
            n.deletedAt != null -> "这篇笔记已被删除"
            else -> "这篇笔记已在别处修改"
        }

        /** Trimmed, not empty, not too long, and unique among [others] ignoring case. */
        fun checkTopicName(name: String, others: List<String>): String {
            val clean = name.trim()
            ensure(clean.isNotEmpty()) { "请输入主题名" }
            ensure(clean.length <= TOPIC_NAME_MAX) { "主题名最多 $TOPIC_NAME_MAX 个字" }
            ensure(others.none { it.trim().equals(clean, ignoreCase = true) }) { "已有同名主题「$clean」" }
            return clean
        }

        /** The note tables of a backup, before anything is touched. */
        fun checkBackup(topics: List<NoteTopic>, notes: List<Note>, sources: List<NoteSource>, drafts: List<NoteDraft>) {
            ensure(topics.map { it.id }.toSet().size == topics.size) { "备份里有重复的笔记主题" }
            ensure(notes.map { it.id }.toSet().size == notes.size) { "备份里有重复的笔记" }
            val names = topics.map { it.name.trim().lowercase() }
            ensure(names.toSet().size == names.size) { "备份里有同名的笔记主题" }
            val topicIds = topics.map { it.id }.toSet()
            val noteIds = notes.map { it.id }.toSet()
            for (n in notes) {
                ensure(n.topicId == null || n.topicId in topicIds) { "有笔记指向不存在的主题" }
                ensure(n.bodyRev >= 0) { "有笔记的版本号无效" }
                // The trash reads both together.
                ensure((n.deletedAt == null) == (n.deleteBatch == null)) { "有笔记的删除标记不完整" }
            }
            ensure(sources.map { Triple(it.noteId, it.kind, it.ref) }.toSet().size == sources.size) { "备份里有重复的笔记来源" }
            for (s in sources) {
                ensure(s.noteId in noteIds) { "有笔记来源指向不存在的笔记" }
                ensure(s.kind == SourceRef.DAY || s.kind == SourceRef.WEEK) { "备份里有未知的笔记来源「${s.kind}」" }
                ensure(runCatching { LocalDate.parse(s.ref) }.isSuccess) { "备份里有日期无效的笔记来源" }
                ensure(s.kind != SourceRef.WEEK || Weeks.isMonday(s.ref)) { "备份里有日期无效的笔记来源" }
            }
            ensure(drafts.map { it.id }.toSet().size == drafts.size) { "备份里有重复的笔记草稿" }
            for (d in drafts) ensure(d.noteId == null || d.noteId in noteIds) { "有笔记草稿指向不存在的笔记" }
        }
    }
}
