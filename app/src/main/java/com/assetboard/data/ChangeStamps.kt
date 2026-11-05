package com.assetboard.data

import java.util.concurrent.atomic.AtomicLong

/** A row's version stamp: when it changed, and which change it was. */
data class ChangeStamp(val at: Long, val id: String)

/**
 * Stamps for record-level merge. One user operation makes one [ChangeStamp], shared by every row
 * it touches, so a stocktake or a repeat completion is won or lost as a group.
 *
 * [nextAt] keeps the stamps of this process strictly increasing: two edits in the same
 * millisecond never tie, and a row edited twice in a row always moves forward, whatever the wall
 * clock says. Across devices the wall clock is what orders them (see docs/sync-merge-semantics.md).
 */
object ChangeStamps {
    private val last = AtomicLong(0)

    fun stamp(now: Long): ChangeStamp = ChangeStamp(nextAt(now), newId())

    /** Observe persisted or merged versions before issuing a subsequent local change. */
    fun observe(at: Long) {
        last.updateAndGet { previous -> maxOf(previous, at) }
    }

    fun nextAt(now: Long): Long {
        // The server's clock, not this device's, orders rows across devices (ServerClock).
        val corrected = now + com.assetboard.data.sync.ServerClock.offsetMs
        while (true) {
            val prev = last.get()
            val next = if (corrected > prev) corrected else prev + 1
            if (last.compareAndSet(prev, next)) return next
        }
    }

    /** Tests run many fixtures, each with its own fake clock, in one process. */
    internal fun resetForTests() {
        last.set(0)
        com.assetboard.data.sync.ServerClock.resetForTests()
    }
}

/**
 * The canonical name of a row across devices: the backup field name plus its key parts in
 * declaration order. Tombstones and merge repairs refer to rows this way. See
 * docs/sync-merge-semantics.md §1.1.
 */
object RowKey {
    fun of(table: String, vararg parts: Any?): String =
        table + "#" + parts.joinToString("|") { part ->
            when (part) {
                null -> "~"
                is String -> part.replace("%", "%25").replace("|", "%7C").replace("#", "%23")
                else -> part.toString()
            }
        }
}

/** A tombstone for a row the user deleted now (active delete, §4.1). */
fun deletedTombstone(table: String, rowKey: String, at: Long) =
    Tombstone(table, rowKey, ChangeStamps.nextAt(at), "T:" + newId())

/**
 * A tombstone for a row a merge kept out (dedup, cascade, purge): its version is the row's own,
 * derived deterministically so both devices make the same one (§4.3).
 */
fun derivedTombstone(table: String, rowKey: String, deletedAt: Long, changeId: String): Tombstone {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest("dedup\u0000$rowKey\u0000$changeId".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return Tombstone(table, rowKey, deletedAt, "T:" + digest.take(32))
}
