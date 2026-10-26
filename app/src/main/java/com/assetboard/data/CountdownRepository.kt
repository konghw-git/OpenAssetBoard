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
    db: AppDatabase,
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
        val c = if (id == null) {
            Countdown(newId(), clean, date.toString(), yearly, pinned, createdAt = now, updatedAt = now).also { dao.insert(it) }
        } else {
            val old = dao.get(id) ?: throw LedgerException("这个倒数日已被删除")
            old.copy(title = clean, date = date.toString(), yearly = yearly, pinned = pinned, updatedAt = now).also { dao.update(it) }
        }
        if (c.pinned) dao.unpinOthers(c.id)
        c.id
    }

    suspend fun setPinned(id: String, pinned: Boolean) = write {
        val old = dao.get(id) ?: throw LedgerException("这个倒数日已被删除")
        dao.update(old.copy(pinned = pinned, updatedAt = clock()))
        if (pinned) dao.unpinOthers(id)
    }

    /** Removes it and returns the row, for [restore]; null when it was already gone. */
    suspend fun delete(id: String): Countdown? = write {
        dao.get(id)?.also { dao.delete(id) }
    }

    /** Puts a deleted countdown back as it was; it loses the pin if another took it meanwhile. */
    suspend fun restore(c: Countdown) = write {
        if (dao.get(c.id) != null) return@write
        val pinTaken = c.pinned && dao.all().any { it.pinned }
        dao.insert(if (pinTaken) c.copy(pinned = false) else c)
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
