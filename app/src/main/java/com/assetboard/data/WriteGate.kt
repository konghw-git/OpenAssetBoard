package com.assetboard.data

import androidx.room.withTransaction
import com.assetboard.domain.LedgerException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * The data epoch an operation was started under. Bound where the operation originates (a
 * ViewModel's creation, a notification or widget token, a worker's start) and carried through the
 * coroutine context, so an operation that began before a restore can never write into the
 * restored data.
 */
class Epoch(val value: Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<Epoch>

    override fun toString() = "Epoch($value)"
}

/** Thrown for writes that arrive while a restore holds the gate, or that carry a stale epoch. */
open class WriteRejected(message: String) : LedgerException(message)

/** The gate is held by a restore; unsaved diary text stays pending for the coordinator to commit. */
class RestoreInProgress : WriteRejected(WriteGate.MSG_RESTORING)

/**
 * A read-write gate around every repository transaction. Ordinary writes hold the read side and
 * may run concurrently; a restore holds the write side. While a restore is pending or running,
 * new writes are rejected immediately rather than queued.
 */
class WriteGate(
    private val db: AppDatabase,
    /** Debug builds crash on a write without an epoch; release builds reject it. */
    private val crashOnMissingEpoch: Boolean,
) {
    private val lock = Any()
    private var readers = 0
    private var restoring = false
    private var drained: CompletableDeferred<Unit>? = null

    @Volatile private var cachedEpoch: Long? = null

    private val _frozen = MutableStateFlow(false)
    /** True from the moment a restore starts until it has finished, successfully or not. */
    val frozen: StateFlow<Boolean> = _frozen.asStateFlow()

    /**
     * The current epoch, for binding at an operation's origin. Never call this at the moment of
     * writing: that would let an old operation pass as a new one.
     */
    fun epochNow(): Long {
        cachedEpoch?.let { return it }
        // Plain SQLite read: callable from any thread, unlike Room's DAO methods.
        val value = db.openHelper.readableDatabase.query("SELECT value FROM app_meta WHERE `key` = '$KEY_EPOCH'").use { c ->
            if (c.moveToFirst()) c.getString(0).toLong() else 0L
        }
        cachedEpoch = value
        return value
    }

    fun bound(): Epoch = Epoch(epochNow())

    /**
     * A database opened for a profile starts with no epoch of its own. Give it one no other
     * profile on this device has used, before any alarm or widget token can be minted from it.
     */
    fun ensureEpochAtLeast(value: Long) {
        if (epochNow() >= value) return
        db.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO app_meta(`key`, value) VALUES(?, ?)",
            arrayOf(KEY_EPOCH, value.toString()),
        )
        cachedEpoch = value
    }

    /** Runs [block] in one transaction under the read side of the gate, after the epoch check. */
    suspend fun <T> write(block: suspend () -> T): T {
        val epoch = coroutineContext[Epoch]
        if (epoch == null) {
            if (crashOnMissingEpoch) throw IllegalStateException("write without a bound epoch")
            throw WriteRejected("内部错误：写入缺少数据纪元，本次操作未保存")
        }
        synchronized(lock) {
            if (restoring) throw RestoreInProgress()
            readers++
        }
        try {
            if (epoch.value != epochNow()) throw WriteRejected(MSG_STALE)
            return db.withTransaction { block() }
        } finally {
            synchronized(lock) {
                readers--
                if (readers == 0) drained?.complete(Unit)
            }
        }
    }

    // ------------------------------------------------------------ restore side

    /**
     * Stops new writes, then waits for the running ones. Only [RestoreCoordinator] calls this.
     * Cancelled while waiting, it unfreezes again: the caller never got the gate to release.
     */
    internal suspend fun freeze() {
        val wait: CompletableDeferred<Unit>
        synchronized(lock) {
            check(!restoring) { "restore already running" }
            restoring = true
            wait = CompletableDeferred<Unit>().also { if (readers == 0) it.complete(Unit) }
            drained = wait
        }
        _frozen.value = true
        try {
            wait.await()
        } catch (e: Throwable) {
            unfreeze()
            throw e
        }
    }

    internal fun unfreeze() {
        synchronized(lock) {
            restoring = false
            drained = null
        }
        _frozen.value = false
    }

    /** Called inside the restore transaction, which holds the write side. */
    internal suspend fun bumpEpochInTransaction(): Long {
        val next = (db.metaDao().get(KEY_EPOCH)?.toLong() ?: 0L) + 1
        db.metaDao().put(AppMeta(KEY_EPOCH, next.toString()))
        return next
    }

    /** After the restore transaction committed. */
    internal fun epochCommitted(value: Long) {
        cachedEpoch = value
    }

    /** After a rolled-back restore: forget the cache so the next read sees the database. */
    internal fun invalidateCache() {
        cachedEpoch = null
    }

    companion object {
        const val KEY_EPOCH = "data_epoch"
        const val MSG_RESTORING = "正在恢复数据，本次操作未保存"
        const val MSG_STALE = "数据已恢复，本次操作未保存"
    }
}
