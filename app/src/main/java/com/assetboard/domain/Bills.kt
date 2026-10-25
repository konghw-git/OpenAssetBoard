package com.assetboard.domain

import com.assetboard.data.Account
import com.assetboard.data.FlowKind
import com.assetboard.data.MoneyFlow
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** A 记账 category: [key] is what is stored, [label] what is shown. Icons and colours are the UI's. */
data class BillCategory(val key: String, val label: String, val income: Boolean)

/**
 * 记账 on top of the ledger: a bill is a flow of kind [FlowKind.EXPENSE] (out of one account) or
 * [FlowKind.INCOME] (into one), so it moves that account's balance like any flow, and a later
 * stocktake still has the last word. The categories are fixed; a key the app no longer knows
 * reads as 其他 rather than failing.
 */
object Bills {
    val expense = listOf(
        BillCategory("food", "餐饮", false),
        BillCategory("transport", "交通", false),
        BillCategory("shopping", "购物", false),
        BillCategory("daily", "日用", false),
        BillCategory("housing", "居住", false),
        BillCategory("fun", "娱乐", false),
        BillCategory("health", "医疗", false),
        BillCategory("learning", "学习", false),
        BillCategory("social", "人情", false),
        BillCategory("other", "其他", false),
    )

    val income = listOf(
        BillCategory("salary", "工资", true),
        BillCategory("bonus", "奖金", true),
        BillCategory("parttime", "兼职", true),
        BillCategory("invest", "理财", true),
        BillCategory("gift", "红包", true),
        BillCategory("reimburse", "报销", true),
        BillCategory("refund", "退款", true),
        BillCategory("other_in", "其他", true),
    )

    fun categories(income: Boolean): List<BillCategory> = if (income) this.income else expense

    fun category(key: String?, income: Boolean): BillCategory =
        categories(income).let { list -> list.firstOrNull { it.key == key } ?: list.last() }

    fun category(f: MoneyFlow): BillCategory = category(f.category, f.kind == FlowKind.INCOME)

    /** The one account a bill moves. */
    fun accountOf(f: MoneyFlow): String? = if (f.kind == FlowKind.INCOME) f.toAccountId else f.fromAccountId

    /** What a bill did to the money: income positive, spending negative. */
    fun signed(f: MoneyFlow): Long = if (f.kind == FlowKind.INCOME) f.amount else -f.amount

    /**
     * The instant a bill on [date] is recorded at: right now for today; for another day, that day
     * at [timeOfDay]'s time (the bill's own when it is being edited, otherwise the clock's), so a
     * back-filled bill neither lands on the day's last second, where a stocktake of that day
     * sits, nor in the future.
     */
    fun at(date: LocalDate, now: Long, timeOfDay: Long = now, zone: ZoneId = ZoneId.systemDefault()): Long {
        if (date == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) return now
        val time = Instant.ofEpochMilli(timeOfDay).atZone(zone).toLocalTime()
        return date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    }

    /** Accounts a bill can go through: open, and not investments (buying a fund is a 申购, not spending). */
    fun usableAccounts(l: LedgerData, keep: String? = null): List<Account> =
        l.accounts.filter { (!it.isDisabled && !it.isInvestment) || it.id == keep }

    /**
     * The account a new bill starts on: the one used last for this kind while it is still usable,
     * else the spendable account holding the most, else any usable one.
     */
    fun suggestAccount(l: LedgerData, last: String?): String? {
        val usable = usableAccounts(l)
        usable.firstOrNull { it.id == last }?.let { return it.id }
        return usable.filter { !l.isLiability(it.id) }.maxByOrNull { l.currentBalance(it.id) }?.id ?: usable.firstOrNull()?.id
    }

    /** The categories used most for this kind lately, most used first, for the quick picks. */
    fun recentCategories(flows: List<MoneyFlow>, income: Boolean, limit: Int = 3): List<String> =
        flows.asSequence()
            .filter { it.kind == if (income) FlowKind.INCOME else FlowKind.EXPENSE }
            .sortedByDescending { it.at }
            .take(40)
            .groupingBy { category(it).key }
            .eachCount()
            .entries.sortedByDescending { it.value }
            .take(limit)
            .map { it.key }

    data class Day(val date: LocalDate, val expense: Long, val income: Long, val bills: List<MoneyFlow>)

    data class Share(val category: BillCategory, val total: Long, val count: Int)

    data class Month(
        val month: YearMonth,
        /** Both positive. */
        val expense: Long,
        val income: Long,
        val expenseShares: List<Share>,
        val incomeShares: List<Share>,
        /** Most recent first, each day's bills most recent first. */
        val days: List<Day>,
    ) {
        val balance: Long get() = income - expense
        val isEmpty: Boolean get() = days.isEmpty()
    }

    fun bills(flows: List<MoneyFlow>): List<MoneyFlow> = flows.filter { it.kind.isBill }

    fun month(flows: List<MoneyFlow>, month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): Month {
        fun date(f: MoneyFlow) = Instant.ofEpochMilli(f.at).atZone(zone).toLocalDate()
        val inMonth = bills(flows).filter { YearMonth.from(date(it)) == month }
        fun shares(kind: FlowKind) = inMonth.filter { it.kind == kind }
            .groupBy { category(it) }
            .map { (c, fs) -> Share(c, fs.sumOf { it.amount }, fs.size) }
            .sortedByDescending { it.total }
        val days = inMonth.groupBy(::date).toSortedMap(compareByDescending { it }).map { (d, fs) ->
            Day(
                date = d,
                expense = fs.filter { it.kind == FlowKind.EXPENSE }.sumOf { it.amount },
                income = fs.filter { it.kind == FlowKind.INCOME }.sumOf { it.amount },
                bills = fs.sortedWith(compareByDescending<MoneyFlow> { it.at }.thenByDescending { it.createdAt }),
            )
        }
        return Month(
            month = month,
            expense = inMonth.filter { it.kind == FlowKind.EXPENSE }.sumOf { it.amount },
            income = inMonth.filter { it.kind == FlowKind.INCOME }.sumOf { it.amount },
            expenseShares = shares(FlowKind.EXPENSE),
            incomeShares = shares(FlowKind.INCOME),
            days = days,
        )
    }

    /** Spending on each of the [n] days up to [today], oldest first. */
    fun dailySpending(flows: List<MoneyFlow>, today: LocalDate, n: Int = 7, zone: ZoneId = ZoneId.systemDefault()): List<Long> {
        val byDay = bills(flows).filter { it.kind == FlowKind.EXPENSE }
            .groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
        return (n - 1 downTo 0).map { back -> byDay[today.minusDays(back.toLong())].orEmpty().sumOf { it.amount } }
    }

    /**
     * The amount as typed on the sheet's keys: digits, ".", "+" and "−" (to add up a receipt on
     * the spot) and "⌫". A key that would make it unreadable does nothing: a third decimal, a
     * second point, a sign first or after another sign (which it replaces), a leading zero.
     */
    fun press(input: String, key: String): String {
        if (key == "⌫") return input.dropLast(1)
        val last = input.lastOrNull()
        val segment = input.substringAfterLast('+').substringAfterLast('−')
        return when (key) {
            "+", "−" -> when {
                input.isEmpty() -> input
                last == '+' || last == '−' -> input.dropLast(1) + key
                last == '.' -> input.dropLast(1) + key
                else -> input + key
            }
            "." -> when {
                '.' in segment -> input
                segment.isEmpty() -> input + "0."
                else -> input + key
            }
            else -> when {
                key.length != 1 || !key[0].isDigit() || input.length >= MAX_INPUT -> input
                '.' in segment && segment.substringAfter('.').length >= 2 -> input
                '.' !in segment && segment.length >= MAX_DIGITS -> input
                segment == "0" -> input.dropLast(1) + key
                else -> input + key
            }
        }
    }

    /** What [input] comes to, a sign left at its end ignored; null unless more than zero. */
    fun amountOf(input: String): Long? = Money.parse(input.trimEnd('+', '−', '.'))?.takeIf { it > 0 }

    private const val MAX_INPUT = 32
    private const val MAX_DIGITS = 9

    /** The months that have bills, most recent first, always with [current] among them. */
    fun months(flows: List<MoneyFlow>, current: YearMonth, zone: ZoneId = ZoneId.systemDefault()): List<YearMonth> =
        (bills(flows).map { YearMonth.from(Instant.ofEpochMilli(it.at).atZone(zone)) } + current).distinct().sortedDescending()
}
