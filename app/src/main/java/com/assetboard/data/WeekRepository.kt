package com.assetboard.data

import com.assetboard.domain.LedgerException
import com.assetboard.domain.ensure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/** The two writes a week's editor makes. */
interface WeekStore {
    suspend fun saveBody(weekStart: LocalDate, reviewId: String?, expectedRev: Long?, text: String): BodySave
    suspend fun saveDraft(weekStart: LocalDate, reviewId: String?, text: String, reason: String): String
    fun register(editor: EditorHandle)
    fun unregister(editor: EditorHandle)
}

/**
 * Weekly reviews, written the way diary entries are: one serial queue under the write gate,
 * body writes conditional on `body_rev`, and at most one live review per week.
 */
class WeekRepository(
    private val db: AppDatabase,
    private val gate: WriteGate,
    private val editors: EditorRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
) : WeekStore {
    private val dao = db.weekDao()
    private val queue = Mutex()

    /** Fault injection for tests: thrown inside the body or draft transaction. */
    @Volatile internal var failBodyWrites: Boolean = false
    @Volatile internal var failDraftWrites: Boolean = false

    override fun register(editor: EditorHandle) = editors.add(editor)
    override fun unregister(editor: EditorHandle) = editors.remove(editor)

    private suspend fun <T> serial(block: suspend () -> T): T = queue.withLock { gate.write { block() } }

    fun observe(weekStart: LocalDate): Flow<WeeklyReview?> = dao.observeByWeek(weekStart.toString())
    suspend fun reviewOf(weekStart: LocalDate): WeeklyReview? = dao.liveByWeek(weekStart.toString())
    suspend fun review(id: String): WeeklyReview? = dao.review(id)
    fun observeDrafts(weekStart: LocalDate): Flow<List<WeeklyDraft>> = dao.observeDrafts(weekStart.toString())
    fun observeWeeksWithText(from: LocalDate, to: LocalDate): Flow<List<String>> = dao.observeWeeksWithText(from.toString(), to.toString())

    /**
     * Saves [text] as [weekStart]'s review. A session without a row adopts an empty live one or
     * inserts one; a review with text that appeared meanwhile is a conflict.
     */
    override suspend fun saveBody(weekStart: LocalDate, reviewId: String?, expectedRev: Long?, text: String): BodySave = serial {
        if (failBodyWrites) throw LedgerException("模拟写盘失败")
        val now = clock()
        if (reviewId == null) {
            val existing = dao.liveByWeek(weekStart.toString())
            when {
                existing == null -> {
                    val r = WeeklyReview(newId(), weekStart.toString(), text, bodyRev = 1, createdAt = now, updatedAt = now)
                    dao.insert(r)
                    BodySave.Saved(r.id, 1, created = true)
                }
                existing.body.isEmpty() && dao.updateBody(existing.id, text, existing.bodyRev, now) == 1 ->
                    BodySave.Saved(existing.id, existing.bodyRev + 1, created = false)
                else -> BodySave.Conflict("这一周已有另一篇复盘")
            }
        } else if (dao.updateBody(reviewId, text, expectedRev ?: -1, now) == 1) {
            BodySave.Saved(reviewId, (expectedRev ?: -1) + 1, created = false)
        } else {
            val r = dao.review(reviewId)
            BodySave.Conflict(
                when {
                    r == null -> "这篇复盘已被永久删除"
                    r.deletedAt != null -> "这篇复盘已被删除"
                    else -> "这篇复盘已在别处修改"
                },
            )
        }
    }

    override suspend fun saveDraft(weekStart: LocalDate, reviewId: String?, text: String, reason: String): String = serial {
        if (failDraftWrites) throw LedgerException("模拟写盘失败")
        val id = newId()
        dao.insertDraft(WeeklyDraft(id, weekStart.toString(), reviewId?.takeIf { dao.review(it) != null }, text, reason, clock()))
        id
    }

    suspend fun discardDraft(id: String) = serial { dao.deleteDraft(id) }

    /** Moves the review to the trash as its own batch; an open session on it becomes stale. */
    suspend fun delete(reviewId: String): String = serial {
        val r = dao.review(reviewId)?.takeIf { it.deletedAt == null } ?: throw LedgerException("复盘不存在")
        val batch = newId()
        dao.update(r.copy(deletedAt = clock(), deleteBatch = batch, bodyRev = r.bodyRev + 1))
        batch
    }

    /** The live review that blocks restoring [batch], if any. */
    suspend fun restoreConflict(batch: String): WeeklyReview? {
        val r = dao.inBatch(batch).firstOrNull { it.deletedAt != null } ?: return null
        return dao.liveByWeek(r.weekStart)
    }

    /**
     * Restores the review of [batch]. When its week already has a live review, [replace] decides:
     * true sends the current one to the trash as a new batch; false keeps it and restores nothing.
     */
    suspend fun restore(batch: String, replace: Boolean): Boolean = serial {
        val r = dao.inBatch(batch).firstOrNull { it.deletedAt != null } ?: return@serial false
        val current = dao.liveByWeek(r.weekStart)
        if (current != null) {
            if (!replace) return@serial false
            dao.update(current.copy(deletedAt = clock(), deleteBatch = newId(), bodyRev = current.bodyRev + 1))
        }
        dao.update(r.copy(deletedAt = null, deleteBatch = null, bodyRev = r.bodyRev + 1))
        true
    }

    companion object {
        /** The weekly tables of a backup, before anything is touched. */
        fun checkBackup(reviews: List<WeeklyReview>, drafts: List<WeeklyDraft>) {
            ensure(reviews.map { it.id }.toSet().size == reviews.size) { "备份里有重复的周复盘" }
            for (r in reviews) {
                ensure(Weeks.isMonday(r.weekStart)) { "备份里有日期无效的周复盘" }
                ensure(r.bodyRev >= 0) { "有周复盘的版本号无效" }
                // The trash reads both together.
                ensure((r.deletedAt == null) == (r.deleteBatch == null)) { "有周复盘的删除标记不完整" }
            }
            val live = reviews.filter { it.deletedAt == null }.map { it.weekStart }
            ensure(live.toSet().size == live.size) { "备份里同一周有多篇复盘" }
            val ids = reviews.map { it.id }.toSet()
            ensure(drafts.map { it.id }.toSet().size == drafts.size) { "备份里有重复的周复盘草稿" }
            for (d in drafts) {
                ensure(Weeks.isMonday(d.weekStart)) { "备份里有日期无效的周复盘草稿" }
                ensure(d.reviewId == null || d.reviewId in ids) { "有周复盘草稿指向不存在的复盘" }
            }
        }
    }
}

/** One editing session on one week's review: the [TextSession] rules, keyed by the week's Monday. */
class WeekSession(
    private val store: WeekStore,
    val weekStart: LocalDate,
    scope: CoroutineScope,
    clock: () -> Long = System::currentTimeMillis,
) : TextSession<Unit>(scope, clock) {
    typealias Status = TextSession.Status

    val reviewId: String? get() = rowId

    fun open(review: WeeklyReview?) = load(review?.id, review?.bodyRev, review?.body.orEmpty())

    override fun register() = store.register(this)
    override fun unregister() = store.unregister(this)
    override fun captureExtra() = Unit

    override suspend fun writeBody(id: String?, expectedRev: Long?, text: String, extra: Unit) = store.saveBody(weekStart, id, expectedRev, text)

    override suspend fun writeDraft(id: String?, text: String, reason: String) {
        store.saveDraft(weekStart, id, text, reason)
    }

    override fun pending(id: String?, expectedRev: Long?, text: String, extra: Unit) = PendingText.Week(weekStart.toString(), id, expectedRev, text)
}
