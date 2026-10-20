package com.assetboard.domain

import com.assetboard.data.Account
import com.assetboard.data.AccountGroup
import com.assetboard.data.AccountKind
import com.assetboard.data.Valuation
import org.junit.Assert.assertEquals
import org.junit.Test

/** The cash card: counted asset accounts that are neither investments nor locked. */
class LiquidityTest {
    private val savings = AccountGroup("g1", "储蓄")
    private val funds = AccountGroup("g2", "投资")
    private val debts = AccountGroup("g3", "负债", isLiability = true)

    private fun account(id: String, group: AccountGroup, kind: AccountKind = AccountKind.NORMAL, lockedUntil: Long? = null, counted: Boolean = true, disabled: Boolean = false) =
        Account(id, group.id, id, kind = kind, lockedUntil = lockedUntil, baselineAt = 0, includeInTotal = counted, isDisabled = disabled, createdAt = 0)

    private fun ledger(vararg pairs: Pair<Account, Long>) = LedgerData(
        groups = listOf(savings, funds, debts),
        accounts = pairs.map { it.first },
        valuations = pairs.map { (a, v) -> Valuation("v-${a.id}", a.id, 0, v, createdAt = 0) },
        flows = emptyList(), snapshots = emptyList(), snapshotItems = emptyList(),
    )

    @Test fun cashExcludesInvestmentsLockedDebtsAndUncounted() {
        val l = ledger(
            account("wechat", savings) to 2_734_81,
            account("bank", savings) to 1_000_00,
            account("bond", funds, AccountKind.INVESTMENT) to 16_649_11,
            account("deposit", savings, lockedUntil = 2_000) to 5_000_00,
            account("loan", debts) to -20_000_00,
            account("hidden", savings, counted = false) to 9_999_00,
            account("old", savings, disabled = true) to 8_888_00,
        )
        val q = l.liquidity(now = 1_000)
        assertEquals(3_734_81L, q.cash)
        assertEquals(16_649_11L, q.invested)
        assertEquals(5_000_00L, q.locked)
        assertEquals(listOf("wechat", "bank"), q.accounts.map { it.first.id })
        assertEquals(l.currentTotals().assets, q.total)
    }

    @Test fun anExpiredLockIsCashAgain() {
        val l = ledger(account("deposit", savings, lockedUntil = 2_000) to 5_000_00)
        assertEquals(0L, l.liquidity(now = 1_999).cash)
        assertEquals(5_000_00L, l.liquidity(now = 2_000).cash)
    }
}
