package com.assetboard.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// All money fields are Long cents. Liability balances are stored negative; the UI shows them positive.

@Serializable
@Entity(tableName = "account_groups")
data class AccountGroup(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "is_liability") val isLiability: Boolean = false,
    @ColumnInfo(name = "color_index") val colorIndex: Int = 0,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
)

enum class AccountKind { NORMAL, INVESTMENT }

@Serializable
@Entity(
    tableName = "accounts",
    foreignKeys = [
        ForeignKey(
            entity = AccountGroup::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("group_id")],
)
data class Account(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "group_id") val groupId: String,
    val name: String,
    val purpose: String? = null,
    val kind: AccountKind = AccountKind.NORMAL,
    val institution: String? = null,
    @ColumnInfo(name = "card_tail") val cardTail: String? = null,
    val code: String? = null,
    @ColumnInfo(name = "locked_until") val lockedUntil: Long? = null,
    @ColumnInfo(name = "baseline_at") val baselineAt: Long,
    /** Investment accounts only; fixed at creation, already covers everything before [baselineAt]. */
    @ColumnInfo(name = "baseline_cost") val baselineCost: Long? = null,
    @ColumnInfo(name = "include_in_total") val includeInTotal: Boolean = true,
    @ColumnInfo(name = "is_disabled") val isDisabled: Boolean = false,
    val note: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    val isInvestment: Boolean get() = kind == AccountKind.INVESTMENT
}

@Serializable
@Entity(tableName = "snapshots", indices = [Index(value = ["at"], unique = true)])
data class Snapshot(
    @PrimaryKey val id: String,
    val at: Long,
    val note: String? = null,
)

/** Absolute balance of an account at [at]; already includes every flow with `flow.at <= at`. */
@Serializable
@Entity(
    tableName = "valuations",
    foreignKeys = [
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = Snapshot::class,
            parentColumns = ["id"],
            childColumns = ["snapshot_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["account_id", "at"], unique = true), Index("snapshot_id")],
)
data class Valuation(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    val at: Long,
    val value: Long,
    /** Reserved for share-based valuation; unused for now. */
    val shares: Long? = null,
    @ColumnInfo(name = "snapshot_id") val snapshotId: String? = null,
    val note: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Serializable
@Entity(
    tableName = "snapshot_items",
    primaryKeys = ["snapshot_id", "account_id"],
    foreignKeys = [
        ForeignKey(
            entity = Snapshot::class,
            parentColumns = ["id"],
            childColumns = ["snapshot_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = Valuation::class,
            parentColumns = ["id"],
            childColumns = ["valuation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("account_id"), Index("valuation_id")],
)
data class SnapshotItem(
    @ColumnInfo(name = "snapshot_id") val snapshotId: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "valuation_id") val valuationId: String,
    val included: Boolean,
)

enum class FeeOn { FROM, TO }

enum class FlowKind(val label: String, val defaultFeeOn: FeeOn) {
    BUY("申购", FeeOn.TO),
    SELL("赎回", FeeOn.FROM),
    DIVIDEND("分红", FeeOn.FROM),
    TRANSFER("转账", FeeOn.FROM),
    REPAY("还款", FeeOn.FROM),
    BORROW("借款", FeeOn.FROM),
    EXTERNAL_IN("外部转入", FeeOn.TO),
    EXTERNAL_OUT("外部转出", FeeOn.FROM),
    /** 记账: money spent from one account, with a [MoneyFlow.category]. */
    EXPENSE("支出", FeeOn.FROM),
    /** 记账: money earned into one account, with a [MoneyFlow.category]. */
    INCOME("收入", FeeOn.TO),
    ;

    /** An entry of 记账 rather than a move the account pages record. */
    val isBill: Boolean get() = this == EXPENSE || this == INCOME
}

/**
 * Money moving between accounts. `from` loses [amount], `to` gains `amount - fee`.
 * A null side means money entering or leaving the app. An [FlowKind.isBill] flow has one side
 * and a [category] (see `domain/Bills.kt`); every other kind leaves it null.
 */
@Serializable
@Entity(
    tableName = "flows",
    foreignKeys = [
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["from_account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["to_account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["from_account_id", "at"]), Index(value = ["to_account_id", "at"])],
)
data class MoneyFlow(
    @PrimaryKey val id: String,
    val kind: FlowKind,
    @ColumnInfo(name = "from_account_id") val fromAccountId: String?,
    @ColumnInfo(name = "to_account_id") val toAccountId: String?,
    val amount: Long,
    val fee: Long = 0,
    @ColumnInfo(name = "fee_on") val feeOn: FeeOn = kind.defaultFeeOn,
    val at: Long,
    val note: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** A bill's category key; null for the other kinds, and read as 其他 when unknown. */
    val category: String? = null,
) {
    fun involves(accountId: String) = fromAccountId == accountId || toAccountId == accountId
}
