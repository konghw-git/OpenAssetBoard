package com.assetboard.domain

import com.assetboard.data.FlowKind
import com.assetboard.data.MoneyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class BillsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private var n = 0

    private fun at(date: String, time: String = "12:00") = LocalDateTime.parse("${date}T$time").atZone(zone).toInstant().toEpochMilli()

    private fun bill(amount: Long, date: String, category: String? = "food", income: Boolean = false, time: String = "12:00") = MoneyFlow(
        id = "b${n++}",
        kind = if (income) FlowKind.INCOME else FlowKind.EXPENSE,
        fromAccountId = if (income) null else "cash",
        toAccountId = if (income) "cash" else null,
        amount = amount, at = at(date, time), createdAt = 0, category = category,
    )

    private fun typed(vararg keys: String) = keys.fold("") { s, k -> Bills.press(s, k) }

    @Test fun keysBuildAnAmountAndRefuseWhatCannotBeRead() {
        assertEquals("12.5", typed("1", "2", ".", "5"))
        // A third decimal and a second point do nothing.
        assertEquals("12.55", typed("1", "2", ".", "5", "5", "9", "."))
        // A leading zero gives way; a point first becomes 0.
        assertEquals("7", typed("0", "7"))
        assertEquals("0.5", typed(".", "5"))
        // A sign first does nothing; a second sign replaces the first.
        assertEquals("3−", typed("+", "3", "+", "−"))
        assertEquals("12+3.5", typed("1", "2", "+", "3", ".", "5"))
        assertEquals("1", typed("1", "2", "⌫"))
    }

    @Test fun anAmountAddsUpAndIgnoresATrailingSign() {
        assertEquals(15_50L, Bills.amountOf("12+3.5"))
        assertEquals(12_00L, Bills.amountOf("12+"))
        assertEquals(10_00L, Bills.amountOf("12−2"))
        assertNull(Bills.amountOf(""))
        assertNull(Bills.amountOf("0"))
        assertNull(Bills.amountOf("2−5"))
    }

    @Test fun anUnknownCategoryReadsAsOther() {
        assertEquals("其他", Bills.category("gone", income = false).label)
        assertEquals("other_in", Bills.category(null, income = true).key)
        assertEquals("餐饮", Bills.category(bill(1, "2026-10-20")).label)
    }

    @Test fun todayIsNowAndAnotherDayKeepsTheTimeOfDay() {
        val now = at("2026-10-25", "18:30")
        assertEquals(now, Bills.at(LocalDate.of(2026, 10, 25), now, zone = zone))
        assertEquals(at("2026-10-23", "18:30"), Bills.at(LocalDate.of(2026, 10, 23), now, zone = zone))
        // An edited bill moved to another day keeps its own time of day — today included, where
        // it is not reset to the moment the sheet happened to be open.
        assertEquals(at("2026-10-22", "08:15"), Bills.at(LocalDate.of(2026, 10, 22), now, timeOfDay = at("2026-10-24", "08:15"), zone = zone))
        assertEquals(at("2026-10-25", "08:15"), Bills.at(LocalDate.of(2026, 10, 25), now, timeOfDay = at("2026-10-24", "08:15"), zone = zone))
    }

    @Test fun aMonthAddsUpByCategoryAndByDay() {
        val flows = listOf(
            bill(30_00, "2026-10-20", "food", time = "08:00"),
            bill(20_00, "2026-10-20", "food", time = "19:00"),
            bill(100_00, "2026-10-21", "transport"),
            bill(8_000_00, "2026-10-10", "salary", income = true),
            bill(999_00, "2026-09-30", "food"),
            MoneyFlow("t", FlowKind.TRANSFER, "cash", "card", 500_00, at = at("2026-10-20"), createdAt = 0),
        )
        val m = Bills.month(flows, YearMonth.of(2026, 10), zone)
        assertEquals(150_00L, m.expense)
        assertEquals(8_000_00L, m.income)
        assertEquals(7_850_00L, m.balance)
        assertEquals(listOf("transport" to 100_00L, "food" to 50_00L), m.expenseShares.map { it.category.key to it.total })
        assertEquals(listOf(LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 10)), m.days.map { it.date })
        // Each day's bills most recent first.
        assertEquals(listOf(20_00L, 30_00L), m.days[1].bills.map { it.amount })
        assertEquals(50_00L, m.days[1].expense)
    }

    @Test fun dailySpendingCountsOnlyExpensesOfEachDay() {
        val flows = listOf(bill(10_00, "2026-10-25"), bill(5_00, "2026-10-23"), bill(1_000_00, "2026-10-25", "salary", income = true))
        assertEquals(listOf(0L, 0L, 0L, 0L, 5_00L, 0L, 10_00L), Bills.dailySpending(flows, LocalDate.of(2026, 10, 25), zone = zone))
    }

    @Test fun recentCategoriesComeMostUsedFirst() {
        val flows = listOf(bill(1, "2026-10-20", "fun"), bill(1, "2026-10-21", "food"), bill(1, "2026-10-22", "food"), bill(1, "2026-10-22", "salary", income = true))
        assertEquals(listOf("food", "fun"), Bills.recentCategories(flows, income = false))
        assertEquals(listOf("salary"), Bills.recentCategories(flows, income = true))
    }
}
