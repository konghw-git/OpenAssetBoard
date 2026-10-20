package com.assetboard.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The net worth goal shown on the assets tab's net worth card, in cents. It is user data (backed
 * up from format 7), kept as one `app_meta` row; no row means no goal.
 */
class GoalRepository(db: AppDatabase, private val gate: WriteGate) {
    private val meta = db.metaDao()

    fun observe(): Flow<Long?> = meta.observe(KEY).map(::parse)

    /** Null or not above zero clears the goal. */
    suspend fun set(cents: Long?) = gate.write {
        if (cents == null || cents <= 0) meta.delete(KEY) else meta.put(AppMeta(KEY, cents.toString()))
    }

    companion object {
        const val KEY = "net_worth_goal"

        fun parse(value: String?): Long? = value?.toLongOrNull()?.takeIf { it > 0 }
    }
}
