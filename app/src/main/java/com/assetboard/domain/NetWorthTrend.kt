package com.assetboard.domain

import com.assetboard.data.AccountGroup
import kotlin.math.abs

/**
 * Net worth over a chosen period, read from stocktakes only. A period starts at the last
 * stocktake on or before its first day, so every change is measured between two real stocktakes
 * and nothing is interpolated.
 */
object NetWorthTrend {
    private const val DAY_MS = 24L * 3600 * 1000

    class Period(
        /** Oldest first; never empty. */
        val points: List<NetWorthPoint>,
        /** The first point is the stocktake just before the period, its starting value. */
        val anchored: Boolean,
    ) {
        val start: NetWorthPoint get() = points.first()
        val end: NetWorthPoint get() = points.last()
        val change: Long get() = end.netWorth - start.netWorth
        val assetsChange: Long get() = end.assets - start.assets
        val debtsChange: Long get() = end.liabilities - start.liabilities
        val high: NetWorthPoint get() = points.maxBy { it.netWorth }
        val low: NetWorthPoint get() = points.minBy { it.netWorth }

        /** The change against a positive starting net worth; null when that is not meaningful. */
        val changeRatio: Double? get() = if (points.size < 2 || start.netWorth <= 0) null else change.toDouble() / start.netWorth
    }

    /** What one group did between two stocktakes, in display terms (debts positive). */
    data class GroupChange(val group: AccountGroup, val start: Long, val end: Long) {
        /** The effect on net worth: a debt that grew lowers it. */
        val effect: Long get() = if (group.isLiability) start - end else end - start
    }

    /** [days] null means all of history. Null when there is no stocktake at all. */
    fun period(series: List<NetWorthPoint>, days: Long?, now: Long): Period? {
        if (series.isEmpty()) return null
        if (days == null) return Period(series, anchored = false)
        val cutoff = now - days * DAY_MS
        val firstIn = series.indexOfFirst { it.snapshot.at >= cutoff }
        return when {
            // No stocktake inside the period: only the latest one, as where things stand.
            firstIn < 0 -> Period(listOf(series.last()), anchored = true)
            firstIn == 0 -> Period(series, anchored = false)
            else -> Period(series.subList(firstIn - 1, series.size), anchored = true)
        }
    }

    /** Per-group totals of one stocktake, debts positive. */
    fun groupValues(ledger: LedgerData, snapshotId: String): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        for (item in ledger.itemsOf(snapshotId)) {
            if (!item.included) continue
            val v = ledger.valuationById[item.valuationId]?.value ?: continue
            val account = ledger.accountById[item.accountId] ?: continue
            out[account.groupId] = (out[account.groupId] ?: 0L) + ledger.toDisplay(account.id, v)
        }
        return out
    }

    /** Which groups moved net worth between the period's ends, biggest effect first. */
    fun groupChanges(ledger: LedgerData, period: Period): List<GroupChange> {
        if (period.points.size < 2) return emptyList()
        val a = groupValues(ledger, period.start.snapshot.id)
        val b = groupValues(ledger, period.end.snapshot.id)
        return (a.keys + b.keys)
            .mapNotNull { id -> ledger.groupById[id]?.let { GroupChange(it, a[id] ?: 0L, b[id] ?: 0L) } }
            .filter { it.effect != 0L }
            .sortedByDescending { abs(it.effect) }
    }
}
