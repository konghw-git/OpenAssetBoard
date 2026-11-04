package com.assetboard.data

import androidx.room.withTransaction
import com.assetboard.domain.AppClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The net worth goal shown on the assets tab's net worth card, in cents. It is user data (backed
 * up from format 7), kept as one `app_meta` row; an empty value means no goal.
 *
 * Format 16 keeps two more keys for the merge: clearing the goal is a change of its own, so the
 * stamp survives the value going away (docs/sync-merge-semantics.md §1.2).
 */
class GoalRepository(
    private val db: AppDatabase,
    private val gate: WriteGate,
    private val clock: () -> Long = AppClock::millis,
) {
    private val meta = db.metaDao()

    fun observe(): Flow<Long?> = meta.observe(KEY).map(::parse)

    /** Null or not above zero clears the goal; the value and its stamp land in one transaction. */
    suspend fun set(cents: Long?) = gate.write {
        val stamp = ChangeStamps.stamp(clock())
        db.withTransaction {
            meta.put(AppMeta(KEY, if (cents == null || cents <= 0) "" else cents.toString()))
            meta.put(AppMeta(AT_KEY, stamp.at.toString()))
            meta.put(AppMeta(CHANGE_ID_KEY, stamp.id))
        }
    }

    companion object {
        const val KEY = "net_worth_goal"
        const val AT_KEY = "net_worth_goal_at"
        const val CHANGE_ID_KEY = "net_worth_goal_change_id"

        fun parse(value: String?): Long? = value?.toLongOrNull()?.takeIf { it > 0 }
    }
}
