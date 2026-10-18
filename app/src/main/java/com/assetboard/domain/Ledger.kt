package com.assetboard.domain

import com.assetboard.data.Account
import com.assetboard.data.FeeOn
import com.assetboard.data.MoneyFlow
import com.assetboard.data.SnapshotItem
import com.assetboard.data.Valuation

/**
 * The calculation rules, as pure functions. Every number the app shows is derived here.
 *
 * - balance(t) = latest valuation with at <= t, plus flows strictly after it and at <= t.
 *   A valuation already includes flows at the same instant.
 * - net invested (investment accounts) = baseline cost + contributions of flows after the baseline.
 *   The account bearing a fee (fee_on) absorbs it once; the other side sees only what moved.
 */
object Ledger {
    /** Change of [accountId]'s balance caused by [flow]. */
    fun flowDelta(flow: MoneyFlow, accountId: String): Long {
        var d = 0L
        if (flow.toAccountId == accountId) d += flow.amount - flow.fee
        if (flow.fromAccountId == accountId) d -= flow.amount
        return d
    }

    /** Change of [accountId]'s net invested caused by [flow]. */
    fun contribution(flow: MoneyFlow, accountId: String): Long {
        var c = 0L
        if (flow.toAccountId == accountId) {
            c += flow.amount - flow.fee
            if (flow.feeOn == FeeOn.TO) c += flow.fee
        }
        if (flow.fromAccountId == accountId) {
            c -= flow.amount
            if (flow.feeOn == FeeOn.FROM) c += flow.fee
        }
        return c
    }

    /** Null when [t] is before the account's first valuation, i.e. the account did not exist yet. */
    fun balanceAt(accountId: String, t: Long, valuations: List<Valuation>, flows: List<MoneyFlow>): Long? {
        val anchor = valuations.filter { it.accountId == accountId && it.at <= t }.maxByOrNull { it.at } ?: return null
        return anchor.value + flows
            .filter { it.involves(accountId) && it.at > anchor.at && it.at <= t }
            .sumOf { flowDelta(it, accountId) }
    }

    /** What the ledger implied right before [valuation] overwrote it; null for the first valuation. */
    fun impliedBefore(valuation: Valuation, valuations: List<Valuation>, flows: List<MoneyFlow>): Long? {
        val id = valuation.accountId
        val prev = valuations.filter { it.accountId == id && it.at < valuation.at }.maxByOrNull { it.at } ?: return null
        return prev.value + flows
            .filter { it.involves(id) && it.at > prev.at && it.at <= valuation.at }
            .sumOf { flowDelta(it, id) }
    }

    fun baselineValue(account: Account, valuations: List<Valuation>): Long? =
        valuations.firstOrNull { it.accountId == account.id && it.at == account.baselineAt }?.value

    fun netInvestedAt(account: Account, t: Long, valuations: List<Valuation>, flows: List<MoneyFlow>): Long? {
        if (t < account.baselineAt) return null
        val base = account.baselineCost ?: baselineValue(account, valuations) ?: return null
        return base + flows
            .filter { it.involves(account.id) && it.at > account.baselineAt && it.at <= t }
            .sumOf { contribution(it, account.id) }
    }

    /** Cumulative profit and loss. Deliberately no rate: net invested can be zero or negative. */
    fun pnlAt(account: Account, t: Long, valuations: List<Valuation>, flows: List<MoneyFlow>): Long? {
        val value = balanceAt(account.id, t, valuations, flows) ?: return null
        val invested = netInvestedAt(account, t, valuations, flows) ?: return null
        return value - invested
    }

    data class Totals(val assets: Long, val liabilities: Long) {
        /** Liabilities are negative, so net worth is a plain sum. */
        val netWorth: Long get() = assets + liabilities
    }

    /** A stocktake's totals come only from its own valuations, so later edits elsewhere never move it. */
    fun snapshotTotals(
        items: List<SnapshotItem>,
        valuationById: (String) -> Valuation?,
        isLiability: (accountId: String) -> Boolean,
    ): Totals {
        var assets = 0L
        var liabilities = 0L
        for (item in items) {
            if (!item.included) continue
            val v = valuationById(item.valuationId)?.value ?: continue
            if (isLiability(item.accountId)) liabilities += v else assets += v
        }
        return Totals(assets, liabilities)
    }
}
