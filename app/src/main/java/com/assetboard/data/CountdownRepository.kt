package com.assetboard.data

import com.assetboard.domain.AppClock
import com.assetboard.domain.Countdowns
import com.assetboard.domain.LedgerException
import com.assetboard.domain.ensure
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Countdowns, written under the write gate like everything else that is backed up. A delete
 * removes the row outright and hands it back, for the undo bar to put back as it was.
 * [onChanged] fires after every write (it refreshes the home-screen widget).
 */
class CountdownRepository(
    private val db: AppDatabase,
    private val gate: WriteGate,
    private val clock: () -> Long = AppClock::millis,
    private val onChanged: () -> Unit = {},
) {
    private val dao = db.countdownDao()

    fun observe(): Flow<List<Countdown>> = dao.observeAll()
    suspend fun all(): List<Countdown> = dao.all()
    suspend fun get(id: String): Countdown? = dao.get(id)

    private suspend fun <T> write(block: suspend () -> T): T {
        val result = gate.write { block() }
        onChanged()
        return result
    }

    /** A new countdown when [id] is null, else that one changed; returns its id. */
    suspend fun save(id: String?, title: String, date: LocalDate, yearly: Boolean, pinned: Boolean): String = write {
        val clean = checkTitle(title)
        val now = clock()
        val stamp = ChangeStamps.stamp(now)
        val old = if (id == null) null else dao.get(id) ?: throw LedgerException("这个倒数日已被删除")
        val pinnedChanged = old == null || old.pinned != pinned
        val c = if (old == null) {
            Countdown(
                newId(), clean, date.toString(), yearly, pinned, createdAt = now, updatedAt = now,
                changedAt = stamp.at, changeId = stamp.id,
                stateAt = if (pinnedChanged) stamp.at else 0, stateId = if (pinnedChanged) stamp.id else "",
            ).also { dao.insert(it) }
        } else {
            old.copy(
                title = clean, date = date.toString(), yearly = yearly, pinned = pinned, updatedAt = now,
                changedAt = stamp.at, changeId = stamp.id,
                stateAt = if (pinnedChanged) stamp.at else old.stateAt,
                stateId = if (pinnedChanged) stamp.id else old.stateId,
            ).also { dao.update(it) }
        }
        if (c.pinned) dao.unpinOthers(c.id, stamp.at, stamp.id)
        c.id
    }

    suspend fun setPinned(id: String, pinned: Boolean) = write {
        val old = dao.get(id) ?: throw LedgerException("这个倒数日已被删除")
        val now = clock()
        val stamp = ChangeStamps.stamp(now)
        // Pinned is a state field: it moves the state stamp only (§1.2).
        dao.update(old.copy(pinned = pinned, updatedAt = now, stateAt = stamp.at, stateId = stamp.id))
        if (pinned) dao.unpinOthers(id, stamp.at, stamp.id)
    }

    /** Removes it and returns the row, for [restore]; null when it was already gone. */
    suspend fun delete(id: String): Countdown? = write {
        val now = clock()
        dao.get(id)?.also {
            db.dao().putTombstone(deletedTombstone("countdowns", RowKey.of("countdowns", it.id), now))
            dao.delete(id)
        }
    }

    /** Puts a deleted countdown back as it was, as a new change (§5.4); it loses the pin if another took it meanwhile. */
    suspend fun restore(c: Countdown) = write {
        if (dao.get(c.id) != null) return@write
        val now = clock()
        val stamp = ChangeStamps.stamp(now)
        val pinTaken = c.pinned && dao.all().any { it.pinned }
        val restored = if (pinTaken) c.copy(pinned = false) else c
        dao.insert(
            restored.copy(
                changedAt = stamp.at, changeId = stamp.id,
                stateAt = if (pinTaken != c.pinned || c.stateAt == 0L) stamp.at else c.stateAt,
                stateId = if (pinTaken != c.pinned || c.stateAt == 0L) stamp.id else c.stateId,
            ),
        )
    }

    companion object {
        fun checkTitle(title: String): String {
            val clean = title.trim().replace('\n', ' ')
            ensure(clean.isNotEmpty()) { "请填写名称" }
            ensure(clean.length <= Countdowns.MAX_TITLE) { "名称最多 ${Countdowns.MAX_TITLE} 个字" }
            return clean
        }

        /** What [save] would have allowed, for a restore. */
        fun checkBackup(items: List<Countdown>) {
            ensure(items.map { it.id }.toSet().size == items.size) { "备份里有重复的倒数日" }
            for (c in items) {
                ensure(Countdowns.parse(c.date) != null) { "备份里有日期无效的倒数日" }
                ensure(c.title.isNotBlank() && c.title.length <= Countdowns.MAX_TITLE) { "备份里有名称无效的倒数日" }
            }
            ensure(items.count { it.pinned } <= 1) { "备份里有多个置顶的倒数日" }
        }
    }
}
