package com.assetboard.domain

import com.assetboard.data.Account
import com.assetboard.data.AccountGroup
import com.assetboard.data.MoneyFlow
import com.assetboard.data.Snapshot
import com.assetboard.data.SnapshotItem
import com.assetboard.data.Valuation

/** An immutable copy of the whole database plus indexes. Personal data is small enough to keep in memory. */
class LedgerData(
    val groups: List<AccountGroup>,
    val accounts: List<Account>,
    val valuations: List<Valuation>,
    val flows: List<MoneyFlow>,
    val snapshots: List<Snapshot>,
    val snapshotItems: List<SnapshotItem>,
) {
    val groupById: Map<String, AccountGroup> = groups.associateBy { it.id }
    val accountById: Map<String, Account> = accounts.associateBy { it.id }
    val valuationById: Map<String, Valuation> = valuations.associateBy { it.id }
    private val valuationsByAccount: Map<String, List<Valuation>> =
        valuations.groupBy { it.accountId }.mapValues { (_, v) -> v.sortedBy { it.at } }
    private val flowsByAccount: Map<String, List<MoneyFlow>> = buildMap<String, MutableList<MoneyFlow>> {
        for (f in flows) {
            f.fromAccountId?.let { getOrPut(it) { mutableListOf() }.add(f) }
            f.toAccountId?.let { getOrPut(it) { mutableListOf() }.add(f) }
        }
    }.mapValues { (_, v) -> v.sortedBy { it.at } }
    private val itemsBySnapshot: Map<String, List<SnapshotItem>> = snapshotItems.groupBy { it.snapshotId }

    fun valuationsOf(accountId: String): List<Valuation> = valuationsByAccount[accountId].orEmpty()
    fun flowsOf(accountId: String): List<MoneyFlow> = flowsByAccount[accountId].orEmpty()
    fun itemsOf(snapshotId: String): List<SnapshotItem> = itemsBySnapshot[snapshotId].orEmpty()

    fun isLiability(accountId: String): Boolean =
        accountById[accountId]?.let { groupById[it.groupId]?.isLiability } == true

    fun balanceAt(accountId: String, t: Long): Long? =
        Ledger.balanceAt(accountId, t, valuationsOf(accountId), flowsOf(accountId))

    fun currentBalance(accountId: String): Long = balanceAt(accountId, Long.MAX_VALUE) ?: 0L

    /** Liabilities read as positive debt in the UI. */
    fun toDisplay(accountId: String, stored: Long): Long = if (isLiability(accountId)) -stored else stored

    fun toStored(accountId: String, display: Long): Long = if (isLiability(accountId)) -display else display

    fun netInvested(account: Account, t: Long = Long.MAX_VALUE): Long? =
        Ledger.netInvestedAt(account, t, valuationsOf(account.id), flowsOf(account.id))

    fun pnl(account: Account, t: Long = Long.MAX_VALUE): Long? =
        Ledger.pnlAt(account, t, valuationsOf(account.id), flowsOf(account.id))

    fun impliedBefore(valuation: Valuation): Long? =
        Ledger.impliedBefore(valuation, valuationsOf(valuation.accountId), flowsOf(valuation.accountId))

    fun isBaseline(valuation: Valuation): Boolean =
        accountById[valuation.accountId]?.baselineAt == valuation.at

    /** Accounts that count toward today's net worth. */
    fun countedAccounts(): List<Account> = accounts.filter { !it.isDisabled && it.includeInTotal }

    fun currentTotals(): Ledger.Totals {
        var assets = 0L
        var liabilities = 0L
        for (a in countedAccounts()) {
            val b = currentBalance(a.id)
            if (isLiability(a.id)) liabilities += b else assets += b
        }
        return Ledger.Totals(assets, liabilities)
    }

    /**
     * The money at hand: counted asset accounts that are not investments (funds and the like)
     * and not locked at [now]. Investments and locked balances are reported beside it, so the
     * three add up to the counted assets.
     */
    fun liquidity(now: Long): Liquidity {
        val cash = mutableListOf<Pair<Account, Long>>()
        var invested = 0L
        var locked = 0L
        for (a in countedAccounts()) {
            if (isLiability(a.id)) continue
            val b = currentBalance(a.id)
            when {
                a.isInvestment -> invested += b
                a.lockedUntil != null && a.lockedUntil > now -> locked += b
                else -> cash += a to b
            }
        }
        return Liquidity(cash.sumOf { it.second }, cash.sortedByDescending { it.second }, invested, locked)
    }

    fun snapshotTotals(snapshotId: String): Ledger.Totals =
        Ledger.snapshotTotals(itemsOf(snapshotId), { valuationById[it] }, ::isLiability)

    /** Accounts a new stocktake at [t] lists: enabled and already existing at [t]. */
    fun stocktakeAccounts(t: Long): List<Account> =
        accounts.filter { !it.isDisabled && it.baselineAt <= t }

    /** The same ledger with one stocktake removed, used to preview what deleting it changes. */
    fun withoutSnapshot(snapshotId: String): LedgerData = LedgerData(
        groups = groups,
        accounts = accounts,
        valuations = valuations.filter { it.snapshotId != snapshotId },
        flows = flows,
        snapshots = snapshots.filter { it.id != snapshotId },
        snapshotItems = snapshotItems.filter { it.snapshotId != snapshotId },
    )

    /** Points where the balance (and, for investments, net invested) can change. */
    fun accountSeries(account: Account): List<SeriesPoint> {
        val times = (valuationsOf(account.id).map { it.at } + flowsOf(account.id).map { it.at })
            .filter { it >= account.baselineAt }
            .distinct()
            .sorted()
        return times.mapNotNull { t ->
            val b = balanceAt(account.id, t) ?: return@mapNotNull null
            SeriesPoint(
                at = t,
                value = toDisplay(account.id, b),
                invested = if (account.isInvestment) netInvested(account, t) else null,
            )
        }
    }

    fun netWorthSeries(): List<NetWorthPoint> = snapshots.sortedBy { it.at }.map { s ->
        val t = snapshotTotals(s.id)
        NetWorthPoint(snapshot = s, assets = t.assets, liabilities = -t.liabilities, netWorth = t.netWorth)
    }
}

data class SeriesPoint(val at: Long, val value: Long, val invested: Long?)

/** [cash] is spendable now; [invested] and [locked] are the rest of the counted assets. */
data class Liquidity(val cash: Long, val accounts: List<Pair<Account, Long>>, val invested: Long, val locked: Long) {
    val total: Long get() = cash + invested + locked
}

/** [liabilities] is positive here, ready for display. */
data class NetWorthPoint(val snapshot: Snapshot, val assets: Long, val liabilities: Long, val netWorth: Long)
