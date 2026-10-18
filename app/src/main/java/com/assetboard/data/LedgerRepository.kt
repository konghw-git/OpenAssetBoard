package com.assetboard.data

import com.assetboard.domain.LedgerData
import com.assetboard.domain.Rules
import com.assetboard.domain.ensure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

/** Editable account fields; balance and baseline go through their own methods. */
data class AccountDraft(
    val groupId: String,
    val name: String,
    val purpose: String? = null,
    val kind: AccountKind = AccountKind.NORMAL,
    val institution: String? = null,
    val cardTail: String? = null,
    val code: String? = null,
    val lockedUntil: Long? = null,
    val includeInTotal: Boolean = true,
    val note: String? = null,
)

/** One row of a stocktake form: the balance as the user sees it (debts positive). */
data class StocktakeEntry(val accountId: String, val displayValue: Long)

/**
 * Every write goes through here: validated, wrapped in one transaction under the [WriteGate],
 * then [onChanged] fires (it refreshes the home-screen widget). Amounts passed in are display
 * values; the liability sign is applied here and nowhere else.
 */
class LedgerRepository(
    private val db: AppDatabase,
    private val gate: WriteGate,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onChanged: () -> Unit = {},
) {
    private val dao = db.dao()

    val ledger: StateFlow<LedgerData?> = combine(
        combine(dao.observeGroups(), dao.observeAccounts(), dao.observeValuations(), ::Triple),
        combine(dao.observeFlows(), dao.observeSnapshots(), dao.observeSnapshotItems(), ::Triple),
    ) { (g, a, v), (f, s, i) -> LedgerData(g, a, v, f, s, i) }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun loadOnce(): LedgerData = LedgerData(
        dao.groups(), dao.accounts(), dao.valuations(), dao.flows(), dao.snapshots(), dao.snapshotItems(),
    )

    private suspend fun <T> write(block: suspend () -> T): T {
        val result = gate.write { block() }
        onChanged()
        return result
    }

    private suspend fun requireAccount(id: String): Account =
        dao.account(id) ?: throw com.assetboard.domain.LedgerException("账户不存在")

    private suspend fun signOf(account: Account): Long = signOfGroup(account.groupId)

    private suspend fun signOfGroup(groupId: String): Long =
        if (dao.group(groupId)?.isLiability == true) -1L else 1L

    // ---------------------------------------------------------------- groups

    suspend fun createGroup(name: String, isLiability: Boolean, colorIndex: Int): String = write {
        ensure(name.isNotBlank()) { "分组名称不能为空" }
        val id = newId()
        dao.insertGroup(AccountGroup(id, name.trim(), isLiability, colorIndex, dao.maxGroupOrder() + 1))
        id
    }

    suspend fun updateGroup(id: String, name: String, isLiability: Boolean, colorIndex: Int) = write {
        ensure(name.isNotBlank()) { "分组名称不能为空" }
        val old = dao.group(id) ?: return@write
        if (old.isLiability != isLiability) {
            ensure(dao.accountCountInGroup(id) == 0) { "分组里已有账户，不能切换资产 / 负债属性" }
        }
        dao.updateGroup(old.copy(name = name.trim(), isLiability = isLiability, colorIndex = colorIndex))
    }

    suspend fun deleteGroup(id: String) = write {
        val g = dao.group(id) ?: return@write
        ensure(dao.accountCountInGroup(id) == 0) { "分组里还有账户，不能删除" }
        dao.deleteGroup(g)
    }

    suspend fun moveGroup(id: String, delta: Int) = write {
        val groups = dao.groups().toMutableList()
        val i = groups.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in groups.indices) return@write
        groups.add(j, groups.removeAt(i))
        dao.updateGroups(groups.mapIndexed { idx, g -> g.copy(sortOrder = idx) })
    }

    // ---------------------------------------------------------------- accounts

    suspend fun createAccount(
        draft: AccountDraft,
        baselineAt: Long,
        baselineDisplay: Long,
        baselineCostDisplay: Long?,
    ): String = write {
        ensure(draft.name.isNotBlank()) { "账户名称不能为空" }
        ensure(dao.group(draft.groupId) != null) { "请选择分组" }
        val sign = signOfGroup(draft.groupId)
        val value = sign * baselineDisplay
        val now = clock()
        val account = Account(
            id = newId(),
            groupId = draft.groupId,
            name = draft.name.trim(),
            purpose = draft.purpose?.trim()?.ifEmpty { null },
            kind = draft.kind,
            institution = draft.institution?.trim()?.ifEmpty { null },
            cardTail = draft.cardTail?.trim()?.ifEmpty { null },
            code = draft.code?.trim()?.ifEmpty { null },
            lockedUntil = draft.lockedUntil,
            baselineAt = baselineAt,
            baselineCost = if (draft.kind == AccountKind.INVESTMENT) (baselineCostDisplay?.let { sign * it } ?: value) else null,
            includeInTotal = draft.includeInTotal,
            note = draft.note?.trim()?.ifEmpty { null },
            sortOrder = dao.maxAccountOrder() + 1,
            createdAt = now,
        )
        dao.insertAccount(account)
        dao.insertValuation(Valuation(newId(), account.id, baselineAt, value, createdAt = now, note = "期初"))
        account.id
    }

    suspend fun updateAccount(id: String, draft: AccountDraft) = write {
        ensure(draft.name.isNotBlank()) { "账户名称不能为空" }
        val old = requireAccount(id)
        if (old.groupId != draft.groupId) {
            ensure(signOfGroup(old.groupId) == signOfGroup(draft.groupId)) {
                "不能在资产分组和负债分组之间移动账户，请新建账户"
            }
        }
        var cost = old.baselineCost
        if (draft.kind == AccountKind.INVESTMENT && cost == null) {
            cost = dao.valuationAt(id, old.baselineAt)?.value ?: 0L
        }
        dao.updateAccount(
            old.copy(
                groupId = draft.groupId,
                name = draft.name.trim(),
                purpose = draft.purpose?.trim()?.ifEmpty { null },
                kind = draft.kind,
                institution = draft.institution?.trim()?.ifEmpty { null },
                cardTail = draft.cardTail?.trim()?.ifEmpty { null },
                code = draft.code?.trim()?.ifEmpty { null },
                lockedUntil = draft.lockedUntil,
                baselineCost = cost,
                includeInTotal = draft.includeInTotal,
                note = draft.note?.trim()?.ifEmpty { null },
            ),
        )
    }

    /** Disabling never writes a valuation; money that really left should be recorded as a flow. */
    suspend fun setDisabled(id: String, disabled: Boolean) = write {
        val a = requireAccount(id)
        dao.updateAccount(a.copy(isDisabled = disabled))
    }

    /** Only an account holding nothing but its baseline may be deleted; anything else is disabled instead. */
    suspend fun canDelete(id: String): Boolean =
        dao.flowCount(id) == 0 && dao.valuationCount(id) <= 1 && dao.snapshotItemCount(id) == 0

    suspend fun deleteAccount(id: String) = write {
        val a = requireAccount(id)
        ensure(canDelete(id)) { "账户已有记录，只能停用" }
        dao.valuationAt(id, a.baselineAt)?.let { dao.deleteValuation(it) }
        dao.deleteAccount(a)
    }

    /**
     * Same instant: rewrite the baseline value and cost. Earlier instant: a new baseline valuation is
     * written there and the old one stays as an ordinary valuation. The baseline never moves later.
     */
    suspend fun updateBaseline(id: String, newAt: Long, valueDisplay: Long, costDisplay: Long?) = write {
        val a = requireAccount(id)
        ensure(newAt <= a.baselineAt) { "期初只能前移，不能后移" }
        val sign = signOf(a)
        val value = sign * valueDisplay
        val cost = if (a.isInvestment) (costDisplay?.let { sign * it } ?: value) else null
        if (newAt == a.baselineAt) {
            val v = dao.valuationAt(id, a.baselineAt)
            if (v != null) dao.updateValuation(v.copy(value = value))
            else dao.insertValuation(Valuation(newId(), id, newAt, value, createdAt = clock(), note = "期初"))
        } else {
            ensure(dao.valuationAt(id, newAt) == null) { "该时刻已有记录" }
            dao.insertValuation(Valuation(newId(), id, newAt, value, createdAt = clock(), note = "期初"))
        }
        dao.updateAccount(a.copy(baselineAt = newAt, baselineCost = cost))
    }

    // ---------------------------------------------------------------- valuations

    suspend fun addValuation(accountId: String, at: Long, valueDisplay: Long, note: String?): String = write {
        val a = requireAccount(accountId)
        Rules.checkValuationTime(a, at)
        ensure(dao.valuationAt(accountId, at) == null) { "该时刻已有一条记录" }
        val id = newId()
        dao.insertValuation(Valuation(id, accountId, at, signOf(a) * valueDisplay, note = note?.ifBlank { null }, createdAt = clock()))
        id
    }

    suspend fun updateValuation(id: String, at: Long, valueDisplay: Long, note: String?) = write {
        val v = dao.valuation(id) ?: return@write
        val a = requireAccount(v.accountId)
        if (at != v.at) {
            ensure(v.snapshotId == null && dao.itemCountForValuation(id) == 0) { "盘点中的记录请在盘点里改日期" }
            ensure(v.at != a.baselineAt) { "期初请在账户设置里修改" }
            Rules.checkValuationTime(a, at)
            ensure(dao.valuationAt(a.id, at) == null) { "该时刻已有一条记录" }
        }
        dao.updateValuation(v.copy(at = at, value = signOf(a) * valueDisplay, note = note?.ifBlank { null }))
    }

    suspend fun deleteValuation(id: String) = write {
        val v = dao.valuation(id) ?: return@write
        val a = requireAccount(v.accountId)
        ensure(v.at != a.baselineAt) { "期初记录不能删除" }
        ensure(v.snapshotId == null && dao.itemCountForValuation(id) == 0) { "这条记录属于一次盘点，请在那次盘点中修改，或删除整次盘点" }
        dao.deleteValuation(v)
    }

    // ---------------------------------------------------------------- flows

    /** Inserts when [flow.id] is new, updates otherwise. */
    suspend fun saveFlow(flow: MoneyFlow) = write {
        val from = flow.fromAccountId?.let { dao.account(it) }
        val to = flow.toAccountId?.let { dao.account(it) }
        Rules.checkFlow(flow, from, to)
        if (dao.flow(flow.id) == null) dao.insertFlow(flow) else dao.updateFlow(flow)
    }

    suspend fun deleteFlow(id: String) = write {
        dao.flow(id)?.let { dao.deleteFlow(it) }
    }

    // ---------------------------------------------------------------- stocktakes

    /**
     * Writes one valuation per listed account at [at]. An existing valuation at that exact instant
     * (a baseline or a back-filled record) is adopted rather than duplicated; it is not owned by the
     * stocktake, so deleting the stocktake leaves it in place.
     */
    suspend fun createSnapshot(at: Long, entries: List<StocktakeEntry>, note: String? = null): String = write {
        ensure(entries.isNotEmpty()) { "没有可盘点的账户" }
        ensure(dao.snapshotAt(at) == null) { "这个时刻已有一次盘点，请直接编辑那一次" }
        val sid = newId()
        dao.insertSnapshot(Snapshot(sid, at, note))
        val now = clock()
        for (e in entries) {
            val a = requireAccount(e.accountId)
            Rules.checkValuationTime(a, at)
            val stored = signOf(a) * e.displayValue
            val existing = dao.valuationAt(a.id, at)
            val vid = if (existing != null) {
                if (existing.value != stored) dao.updateValuation(existing.copy(value = stored))
                existing.id
            } else {
                newId().also { dao.insertValuation(Valuation(it, a.id, at, stored, snapshotId = sid, createdAt = now)) }
            }
            dao.insertSnapshotItem(SnapshotItem(sid, a.id, vid, included = a.includeInTotal && !a.isDisabled))
        }
        sid
    }

    /** Edits use the stocktake's own account list; a date change moves all of its valuations together. */
    suspend fun updateSnapshot(id: String, at: Long, entries: List<StocktakeEntry>) = write {
        val snap = dao.snapshot(id) ?: return@write
        val items = dao.itemsOfSnapshot(id)
        val byAccount = entries.associateBy { it.accountId }
        val owned = dao.valuationsOfSnapshot(id).associateBy { it.id }
        if (at != snap.at) {
            ensure(dao.snapshotAt(at) == null) { "这个时刻已有一次盘点" }
            ensure(items.all { it.valuationId in owned }) { "这次盘点沿用了期初或单独补录的记录，不能改日期" }
            for (item in items) {
                val a = requireAccount(item.accountId)
                Rules.checkValuationTime(a, at)
                ensure(dao.valuationAt(a.id, at) == null) { "「${a.name}」在新时刻已有记录" }
            }
        }
        for (item in items) {
            val v = dao.valuation(item.valuationId) ?: continue
            val a = requireAccount(item.accountId)
            val stored = byAccount[item.accountId]?.let { signOf(a) * it.displayValue } ?: v.value
            val newAt = if (item.valuationId in owned) at else v.at
            if (stored != v.value || newAt != v.at) dao.updateValuation(v.copy(value = stored, at = newAt))
        }
        if (at != snap.at) dao.updateSnapshot(snap.copy(at = at))
    }

    suspend fun deleteSnapshot(id: String) = write {
        dao.snapshot(id)?.let { dao.deleteSnapshot(it) }
    }

    // ---------------------------------------------------------------- seed

    /** The 5 groups and 15 accounts from the Suishouji account page, as of today. */
    suspend fun seedFromSuishouji() = write {
        ensure(dao.accounts().isEmpty()) { "已有账户，不能再导入预置数据" }
        val now = clock()
        data class Seed(val name: String, val purpose: String?, val cents: Long, val kind: AccountKind = AccountKind.NORMAL, val institution: String? = null)
        val plan = listOf(
            Triple("现金账户", false, listOf(Seed("现金", null, 0))),
            Triple(
                "储蓄账户", false,
                listOf(
                    Seed("农行①", "日常消费", 31, institution = "中国农业银行"),
                    Seed("农行②", "暂时不用", 1, institution = "中国农业银行"),
                    Seed("邮储", "周转资金", 100, institution = "中国邮政储蓄银行"),
                    Seed("SJTU", "理财", 166024),
                    Seed("宁波银行", null, 0, institution = "宁波银行"),
                ),
            ),
            Triple(
                "虚拟账户", false,
                listOf(
                    Seed("微信钱包", "日常消费", 273481, institution = "微信"),
                    Seed("余额宝", null, 296200, institution = "支付宝"),
                ),
            ),
            Triple(
                "负债账户", true,
                listOf(
                    Seed("校园贷款", null, 2000000),
                    Seed("花呗", null, 0, institution = "支付宝"),
                    Seed("抖音月付", null, 0),
                    Seed("美团月付", null, 0),
                    Seed("白条", null, 0, institution = "京东"),
                ),
            ),
            Triple(
                "投资账户", false,
                listOf(
                    Seed("招商产业债券A", "孔心悦6000 3年后", 1664911, AccountKind.INVESTMENT),
                    Seed("兴证全球恒悦180天持有期债券A", "半年强制存储", 1120940, AccountKind.INVESTMENT),
                    Seed("进阶理财", null, 326525, AccountKind.INVESTMENT),
                ),
            ),
        )
        var order = 0
        plan.forEachIndexed { gi, (groupName, liability, seeds) ->
            val gid = newId()
            dao.insertGroup(AccountGroup(gid, groupName, liability, colorIndex = gi, sortOrder = gi))
            for (s in seeds) {
                val value = if (liability) -s.cents else s.cents
                val account = Account(
                    id = newId(), groupId = gid, name = s.name, purpose = s.purpose, kind = s.kind,
                    institution = s.institution, baselineAt = now,
                    baselineCost = if (s.kind == AccountKind.INVESTMENT) value else null,
                    sortOrder = order++, createdAt = now,
                )
                dao.insertAccount(account)
                dao.insertValuation(Valuation(newId(), account.id, now, value, note = "期初", createdAt = now))
            }
        }
    }

    /** Groups only, for someone starting from scratch. */
    suspend fun seedDefaultGroups() = write {
        if (dao.groups().isNotEmpty()) return@write
        listOf("现金账户" to false, "储蓄账户" to false, "虚拟账户" to false, "负债账户" to true, "投资账户" to false)
            .forEachIndexed { i, (name, liability) ->
                dao.insertGroup(AccountGroup(newId(), name, liability, colorIndex = i, sortOrder = i))
            }
    }
}
