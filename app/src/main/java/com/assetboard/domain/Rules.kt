package com.assetboard.domain

import com.assetboard.data.Account
import com.assetboard.data.MoneyFlow

/** A rule violation with a message fit to show the user as is. */
class LedgerException(message: String) : Exception(message)

fun ensure(condition: Boolean, message: () -> String) {
    if (!condition) throw LedgerException(message())
}

/** Constraints Room annotations cannot express. Checked by the repository before every write. */
object Rules {
    fun checkFlow(flow: MoneyFlow, from: Account?, to: Account?) {
        ensure(flow.amount > 0) { "金额必须大于 0" }
        ensure(flow.fee >= 0 && flow.fee <= flow.amount) { "手续费需在 0 到金额之间" }
        ensure(flow.fromAccountId != null || flow.toAccountId != null) { "转出和转入至少选一个账户" }
        ensure(flow.fromAccountId == null || flow.fromAccountId != flow.toAccountId) { "转出和转入不能是同一个账户" }
        ensure((flow.fromAccountId == null) == (from == null)) { "转出账户不存在" }
        ensure((flow.toAccountId == null) == (to == null)) { "转入账户不存在" }
        for (account in listOfNotNull(from, to)) {
            // A flow at the baseline instant would be silently absorbed by the baseline valuation.
            ensure(flow.at > account.baselineAt) {
                "早于「${account.name}」的期初（${Dates.date(account.baselineAt)}），请先在账户设置里前移期初"
            }
        }
    }

    fun checkValuationTime(account: Account, at: Long) {
        ensure(at >= account.baselineAt) {
            "早于「${account.name}」的期初（${Dates.date(account.baselineAt)}），请先在账户设置里前移期初"
        }
    }
}
