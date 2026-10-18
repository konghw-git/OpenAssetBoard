package com.assetboard.domain

import com.assetboard.data.Account
import com.assetboard.data.AccountKind
import com.assetboard.data.FeeOn
import com.assetboard.data.FlowKind
import com.assetboard.data.MoneyFlow
import com.assetboard.data.SnapshotItem
import com.assetboard.data.Valuation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** The calculation cases from section 9 of the design doc. Amounts are yuan * 100. */
class LedgerTest {
    private fun account(id: String, baselineAt: Long = 0, cost: Long? = null, investment: Boolean = false) = Account(
        id = id, groupId = "g", name = id,
        kind = if (investment) AccountKind.INVESTMENT else AccountKind.NORMAL,
        baselineAt = baselineAt, baselineCost = cost, createdAt = 0,
    )

    private fun v(account: String, at: Long, value: Long) = Valuation("v-$account-$at", account, at, value, createdAt = 0)

    private fun flow(kind: FlowKind, from: String?, to: String?, amount: Long, at: Long, fee: Long = 0, feeOn: FeeOn = kind.defaultFeeOn) =
        MoneyFlow("f-$at-$from-$to", kind, from, to, amount, fee, feeOn, at, createdAt = 0)

    private fun balance(id: String, t: Long, vs: List<Valuation>, fs: List<MoneyFlow>) = Ledger.balanceAt(id, t, vs, fs)

    @Test fun case1_balanceIsLatestValuationPlusLaterFlows() {
        val vs = listOf(v("a", 10, 1_000_00))
        val fs = listOf(flow(FlowKind.EXTERNAL_OUT, "a", null, 200_00, at = 20))
        assertEquals(800_00L, balance("a", 20, vs, fs))
        assertEquals(1_000_00L, balance("a", 15, vs, fs))
        assertNull(balance("a", 5, vs, fs))
    }

    @Test fun case2_editingAValuationOnlyMovesItsOwnSegment() {
        val fs = listOf(flow(FlowKind.EXTERNAL_OUT, "a", null, 200_00, at = 20))
        val edited = listOf(v("a", 10, 1_100_00), v("a", 30, 500_00))
        assertEquals(900_00L, balance("a", 25, edited, fs))
        assertEquals(500_00L, balance("a", 40, edited, fs))
        val original = listOf(v("a", 10, 1_000_00), v("a", 30, 500_00))
        assertEquals(500_00L, balance("a", 40, original, fs))
    }

    @Test fun case3_flowAtTheValuationInstantIsAlreadyIncluded() {
        val vs = listOf(v("a", 10, 1_000_00))
        val fs = listOf(flow(FlowKind.EXTERNAL_OUT, "a", null, 200_00, at = 10))
        assertEquals(1_000_00L, balance("a", 10, vs, fs))
        assertEquals(1_000_00L, balance("a", 99, vs, fs))
    }

    @Test fun case4_fullRedemptionGivesNegativeNetInvestedAndPositivePnl() {
        val fund = account("fund", baselineAt = 0, cost = 100_00, investment = true)
        val vs = listOf(v("fund", 0, 100_00), v("fund", 10, 120_00))
        val fs = listOf(flow(FlowKind.SELL, "fund", "cash", 120_00, at = 20))
        assertEquals(0L, balance("fund", 30, vs, fs))
        assertEquals(-20_00L, Ledger.netInvestedAt(fund, 30, vs, fs))
        assertEquals(20_00L, Ledger.pnlAt(fund, 30, vs, fs))
    }

    @Test fun case5_baselineCostDrivesPnl() {
        val fund = account("fund", baselineAt = 0, cost = 10_000_00, investment = true)
        val vs = listOf(v("fund", 0, 12_000_00))
        assertEquals(2_000_00L, Ledger.pnlAt(fund, 1, vs, emptyList()))
    }

    @Test fun baselineCostMissingFallsBackToBaselineValue() {
        val fund = account("fund", baselineAt = 0, cost = null, investment = true)
        val vs = listOf(v("fund", 0, 12_000_00), v("fund", 5, 12_500_00))
        assertEquals(500_00L, Ledger.pnlAt(fund, 9, vs, emptyList()))
    }

    @Test fun case6_flowBeforeOrAtBaselineIsRejected() {
        val fund = account("fund", baselineAt = 100, investment = true)
        val cash = account("cash", baselineAt = 0)
        assertThrows(LedgerException::class.java) {
            Rules.checkFlow(flow(FlowKind.BUY, "cash", "fund", 1_000_00, at = 50), cash, fund)
        }
        assertThrows(LedgerException::class.java) {
            Rules.checkFlow(flow(FlowKind.BUY, "cash", "fund", 1_000_00, at = 100), cash, fund)
        }
        Rules.checkFlow(flow(FlowKind.BUY, "cash", "fund", 1_000_00, at = 101), cash, fund)
    }

    @Test fun case7_purchaseFeeIsBorneByTheFund() {
        val fund = account("fund", baselineAt = 0, cost = 0, investment = true)
        val vs = listOf(v("fund", 0, 0))
        val fs = listOf(flow(FlowKind.BUY, "cash", "fund", 10_000_00, at = 10, fee = 15_00, feeOn = FeeOn.TO))
        assertEquals(9_985_00L, balance("fund", 10, vs, fs))
        assertEquals(10_000_00L, Ledger.netInvestedAt(fund, 10, vs, fs))
        assertEquals(-15_00L, Ledger.pnlAt(fund, 10, vs, fs))
    }

    @Test fun case8_feeBetweenTwoInvestmentsCountsOnce() {
        val a = account("A", cost = 1_000_00, investment = true)
        val b = account("B", cost = 1_000_00, investment = true)
        val vs = listOf(v("A", 0, 1_000_00), v("B", 0, 1_000_00))
        val fs = listOf(flow(FlowKind.TRANSFER, "A", "B", 100_00, at = 10, fee = 1_00, feeOn = FeeOn.FROM))
        assertEquals(-1_00L, Ledger.pnlAt(a, 10, vs, fs))
        assertEquals(0L, Ledger.pnlAt(b, 10, vs, fs))
    }

    @Test fun feeInvariant_bothEndsTogetherLoseExactlyTheFee() {
        for (feeOn in FeeOn.entries) {
            val f = flow(FlowKind.TRANSFER, "A", "B", 100_00, at = 1, fee = 7_00, feeOn = feeOn)
            val effect = listOf("A", "B").sumOf { Ledger.flowDelta(f, it) - Ledger.contribution(f, it) }
            assertEquals(-7_00L, effect)
        }
    }

    @Test fun case9_cashDividendLeavesPnlUnchanged() {
        val fund = account("fund", cost = 10_000_00, investment = true)
        val before = listOf(v("fund", 0, 10_000_00), v("fund", 10, 10_500_00))
        val pnlBefore = Ledger.pnlAt(fund, 10, before, emptyList())
        val fs = listOf(flow(FlowKind.DIVIDEND, "fund", "bank", 300_00, at = 20))
        val after = before + v("fund", 30, 10_200_00)
        assertEquals(pnlBefore, Ledger.pnlAt(fund, 30, after, fs))
    }

    @Test fun impliedBeforeUsesPreviousAnchorAndFlowsUpToTheValuation() {
        val vs = listOf(v("a", 0, 1_000_00), v("a", 20, 900_00))
        val fs = listOf(flow(FlowKind.EXTERNAL_IN, null, "a", 50_00, at = 20))
        assertEquals(1_050_00L, Ledger.impliedBefore(vs[1], vs, fs))
        assertNull(Ledger.impliedBefore(vs[0], vs, fs))
    }

    @Test fun snapshotTotalsSkipExcludedAndSplitLiabilities() {
        val vals = listOf(v("a", 5, 300_00), v("debt", 5, -100_00), v("other", 5, 999_00)).associateBy { it.id }
        val items = listOf(
            SnapshotItem("s", "a", "v-a-5", included = true),
            SnapshotItem("s", "debt", "v-debt-5", included = true),
            SnapshotItem("s", "other", "v-other-5", included = false),
        )
        val t = Ledger.snapshotTotals(items, { vals[it] }, { it == "debt" })
        assertEquals(300_00L, t.assets)
        assertEquals(-100_00L, t.liabilities)
        assertEquals(200_00L, t.netWorth)
    }

    @Test fun checkFlowBasicConstraints() {
        val a = account("a")
        val b = account("b")
        assertThrows(LedgerException::class.java) { Rules.checkFlow(flow(FlowKind.TRANSFER, "a", "b", 0, at = 1), a, b) }
        assertThrows(LedgerException::class.java) { Rules.checkFlow(flow(FlowKind.TRANSFER, "a", "b", 10, at = 1, fee = 11), a, b) }
        assertThrows(LedgerException::class.java) { Rules.checkFlow(flow(FlowKind.TRANSFER, "a", "a", 10, at = 1), a, a) }
        assertThrows(LedgerException::class.java) { Rules.checkFlow(flow(FlowKind.TRANSFER, null, null, 10, at = 1), null, null) }
    }
}

class MoneyTest {
    @Test fun parseAndFormat() {
        assertEquals(166_024L, Money.parse("1,660.24"))
        assertEquals(166_020L, Money.parse("1660.2"))
        assertEquals(1_200L, Money.parse("¥12"))
        assertNull(Money.parse("1.234"))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse(""))
        assertEquals("18,482.13", Money.format(1_848_213))
        assertEquals("-20,000.00", Money.format(-2_000_000))
        assertEquals("+32.40", Money.format(3_240, signed = true))
        assertEquals("1660.24", Money.toInput(166_024))
        assertEquals("1.85万", Money.compact(1_848_213))
    }
}
