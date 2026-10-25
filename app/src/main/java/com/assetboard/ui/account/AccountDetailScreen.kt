package com.assetboard.ui.account

import com.assetboard.domain.AppClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.CreditScore
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RequestQuote
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assetboard.ui.components.GlassScaffold
import com.assetboard.ui.components.glassBarColors
import com.assetboard.data.Account
import com.assetboard.data.AccountGroup
import com.assetboard.data.FlowKind
import com.assetboard.domain.Bills
import com.assetboard.ui.bill.BillSheet
import com.assetboard.ui.bill.billViewModel
import com.assetboard.ui.components.GlassSnackbarHost
import com.assetboard.ui.components.UndoMessages
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import com.assetboard.domain.Dates
import com.assetboard.domain.Ledger
import com.assetboard.domain.LedgerData
import com.assetboard.domain.Money
import com.assetboard.domain.SeriesPoint
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.AccountAvatar
import com.assetboard.ui.components.ChartPoint
import com.assetboard.ui.components.ChartRange
import com.assetboard.ui.components.ChartSeries
import com.assetboard.ui.components.Dot
import com.assetboard.ui.components.LineChart
import com.assetboard.ui.components.MoneyText
import com.assetboard.ui.components.Pill
import com.assetboard.ui.components.RangeChips
import com.assetboard.ui.components.RowDivider
import com.assetboard.ui.components.SectionCard
import com.assetboard.ui.components.SectionHeader
import com.assetboard.ui.theme.AppTheme
import com.assetboard.ui.theme.NumberStyle
import com.assetboard.ui.components.scrollIndicator
import androidx.compose.foundation.lazy.rememberLazyListState

enum class EntryType { BASELINE, STOCKTAKE, VALUATION, FLOW }

data class TimelineEntry(
    val key: String,
    val at: Long,
    val type: EntryType,
    val title: String,
    val subtitle: String?,
    /** Valuations: the value. Flows: the change to this account. Both in display sign. */
    val amount: Long,
    /** Valuations only: change versus what the ledger implied, in display sign. */
    val delta: Long?,
    /** Effect of [delta] on net worth, used for coloring. */
    val netEffect: Long?,
    val valuationId: String? = null,
    val flowId: String? = null,
    val snapshotId: String? = null,
    /** A 记账 entry, edited on its sheet rather than the flow page. */
    val bill: Boolean = false,
)

data class AccountDetail(
    val account: Account,
    val group: AccountGroup?,
    val isLiability: Boolean,
    val balance: Long,
    val pnl: Long?,
    val invested: Long?,
    val series: List<SeriesPoint>,
    val timeline: List<TimelineEntry>,
)

fun buildDetail(l: LedgerData, accountId: String): AccountDetail? {
    val a = l.accountById[accountId] ?: return null
    val isLiab = l.isLiability(a.id)
    val snapshotOfValuation = l.snapshotItems.associate { it.valuationId to it.snapshotId }
    val valuationEntries = l.valuationsOf(a.id).map { v ->
        val implied = l.impliedBefore(v)
        val baseline = l.isBaseline(v)
        val sid = v.snapshotId ?: snapshotOfValuation[v.id]
        val type = when {
            baseline -> EntryType.BASELINE
            sid != null -> EntryType.STOCKTAKE
            else -> EntryType.VALUATION
        }
        val title = when (type) {
            EntryType.BASELINE -> "期初"
            EntryType.STOCKTAKE -> "盘点"
            else -> if (a.isInvestment) "更新市值" else if (isLiab) "更新欠款" else "更新余额"
        }
        val subtitle = buildList {
            if (baseline && a.isInvestment && a.baselineCost != null) add("成本 " + Money.format(l.toDisplay(a.id, a.baselineCost)))
            v.note?.takeIf { it.isNotBlank() && it != "期初" }?.let(::add)
        }.joinToString(" · ").ifEmpty { null }
        TimelineEntry(
            key = "v" + v.id, at = v.at, type = type, title = title, subtitle = subtitle,
            amount = l.toDisplay(a.id, v.value),
            delta = implied?.let { l.toDisplay(a.id, v.value) - l.toDisplay(a.id, it) },
            netEffect = implied?.let { v.value - it },
            valuationId = v.id, snapshotId = sid,
        )
    }
    val flowEntries = l.flowsOf(a.id).map { f ->
        if (f.kind.isBill) {
            return@map TimelineEntry(
                key = "f" + f.id, at = f.at, type = EntryType.FLOW,
                title = f.kind.label + " · " + Bills.category(f).label,
                subtitle = f.note?.takeIf { it.isNotBlank() },
                amount = l.toDisplay(a.id, Ledger.flowDelta(f, a.id)),
                delta = null, netEffect = null, flowId = f.id, bill = true,
            )
        }
        val outgoing = f.fromAccountId == a.id
        val other = (if (outgoing) f.toAccountId else f.fromAccountId)?.let { l.accountById[it]?.name } ?: "外部"
        val fee = if (f.fee > 0) {
            val bearer = if ((f.feeOn.name == "FROM") == outgoing) "本账户承担" else "对方承担"
            "手续费 ${Money.format(f.fee)}（$bearer）"
        } else null
        TimelineEntry(
            key = "f" + f.id, at = f.at, type = EntryType.FLOW,
            title = f.kind.label + " · " + (if (outgoing) "转入 " else "来自 ") + other,
            subtitle = listOfNotNull(fee, f.note?.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { null },
            amount = l.toDisplay(a.id, Ledger.flowDelta(f, a.id)),
            delta = null, netEffect = null, flowId = f.id,
        )
    }
    // Same instant: the valuation already includes the flow, so it reads as happening after it.
    val timeline = (valuationEntries + flowEntries).sortedWith(
        compareByDescending<TimelineEntry> { it.at }.thenBy { if (it.type == EntryType.FLOW) 1 else 0 },
    )
    return AccountDetail(
        account = a,
        group = l.groupById[a.groupId],
        isLiability = isLiab,
        balance = l.toDisplay(a.id, l.currentBalance(a.id)),
        pnl = if (a.isInvestment) l.pnl(a) else null,
        invested = if (a.isInvestment) l.netInvested(a) else null,
        series = l.accountSeries(a),
        timeline = timeline,
    )
}

/** [flowKind] null: a new valuation, unless [bill], which opens 记一笔. */
private data class QuickAction(val label: String, val icon: ImageVector, val flowKind: FlowKind?, val bill: Boolean = false)

private fun actionsFor(d: AccountDetail): List<QuickAction> = when {
    d.account.isInvestment -> listOf(
        QuickAction("更新市值", Icons.Default.Edit, null),
        QuickAction("申购", Icons.Default.Add, FlowKind.BUY),
        QuickAction("赎回", Icons.Default.Remove, FlowKind.SELL),
        QuickAction("分红", Icons.Default.Payments, FlowKind.DIVIDEND),
        QuickAction("转账", Icons.Default.SwapHoriz, FlowKind.TRANSFER),
    )
    d.isLiability -> listOf(
        QuickAction("更新欠款", Icons.Default.Edit, null),
        QuickAction("记一笔", Icons.AutoMirrored.Outlined.ReceiptLong, null, bill = true),
        QuickAction("还款", Icons.Default.CreditScore, FlowKind.REPAY),
        QuickAction("借款", Icons.Default.RequestQuote, FlowKind.BORROW),
    )
    else -> listOf(
        QuickAction("更新余额", Icons.Default.Edit, null),
        QuickAction("记一笔", Icons.AutoMirrored.Outlined.ReceiptLong, null, bill = true),
        QuickAction("转账", Icons.Default.SwapHoriz, FlowKind.TRANSFER),
        QuickAction("外部转入", Icons.Default.CallReceived, FlowKind.EXTERNAL_IN),
        QuickAction("外部转出", Icons.Default.CallMade, FlowKind.EXTERNAL_OUT),
    )
}

@Composable
fun AccountDetailScreen(
    accountId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onNewValuation: () -> Unit,
    onOpenValuation: (String) -> Unit,
    onNewFlow: (String) -> Unit,
    onOpenFlow: (String) -> Unit,
    onOpenStocktake: (String) -> Unit,
) {
    val ledger by LocalContainer.current.repository.ledger.collectAsStateWithLifecycle()
    val detail = remember(ledger, accountId) { ledger?.let { buildDetail(it, accountId) } }
    val cs = MaterialTheme.colorScheme
    val billVm = billViewModel("account")
    val snackbar = remember { SnackbarHostState() }
    UndoMessages(billVm.events, snackbar, billVm::undo)
    // 记一笔 on this account, or a bill of its timeline being edited.
    var recording by rememberSaveable { mutableStateOf(false) }
    var billEditing by rememberSaveable { mutableStateOf<String?>(null) }
    if (recording) BillSheet(null, billVm, onDismiss = { recording = false }, presetAccount = accountId)
    billEditing?.let { id ->
        val f = ledger?.flows?.firstOrNull { it.id == id }
        if (f == null) billEditing = null else BillSheet(f, billVm, onDismiss = { billEditing = null })
    }

    GlassScaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.account?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { IconButton(onClick = onEdit) { Icon(Icons.Default.Tune, "账户设置") } },
                colors = glassBarColors(),
            )
        },
        snackbarHost = { GlassSnackbarHost(snackbar) },
    ) { padding ->
        val d = detail
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (ledger == null) CircularProgressIndicator() else Text("账户不存在")
            }
            return@GlassScaffold
        }
        val barState = rememberLazyListState()
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).scrollIndicator(barState), state = barState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { HeaderCard(d) }
            item {
                ActionsCard(actionsFor(d)) { a ->
                    when {
                        a.bill -> recording = true
                        a.flowKind == null -> onNewValuation()
                        else -> onNewFlow(a.flowKind.name)
                    }
                }
            }
            item { ChartCard(d) }
            item {
                SectionCard {
                    SectionHeader("记录（${d.timeline.size}）")
                    d.timeline.forEachIndexed { i, e ->
                        if (i > 0) RowDivider(startIndent = 70.dp)
                        TimelineRow(e) {
                            when {
                                e.type == EntryType.BASELINE -> onEdit()
                                e.bill -> billEditing = e.flowId
                                e.flowId != null -> onOpenFlow(e.flowId)
                                e.valuationId != null -> onOpenValuation(e.valuationId)
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "盘点产生的记录可以在这里改金额；改日期或删除请到那次盘点里操作。",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun HeaderCard(d: AccountDetail) {
    val extra = AppTheme.extra
    val cs = MaterialTheme.colorScheme
    val color = extra.group(d.group?.colorIndex ?: 0)
    var showInfo by rememberSaveable { mutableStateOf(false) }
    SectionCard(contentPadding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccountAvatar(d.account, color, size = 48.dp, muted = d.account.isDisabled)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(d.account.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                d.account.purpose?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            }
        }
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            d.group?.let { Pill(it.name, color) }
            if (d.account.isDisabled) Pill("已停用", cs.onSurfaceVariant)
            else if (!d.account.includeInTotal) Pill("不计入净资产", cs.onSurfaceVariant)
            d.account.lockedUntil?.let { until ->
                val days = Dates.daysBetween(AppClock.millis(), until)
                Pill(if (days > 0) "锁定至 ${Dates.date(until)} · 还剩 $days 天" else "已于 ${Dates.date(until)} 解锁", cs.secondary)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            when {
                d.account.isInvestment -> "当前市值"
                d.isLiability -> "当前欠款"
                else -> "当前余额"
            },
            style = MaterialTheme.typography.labelLarge,
            color = cs.onSurfaceVariant,
        )
        MoneyText(d.balance, style = MaterialTheme.typography.displaySmall)
        if (d.account.isInvestment && d.pnl != null && d.invested != null) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("累计盈亏", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    MoneyText(d.pnl, style = MaterialTheme.typography.titleLarge, color = extra.signed(d.pnl), signed = true)
                }
                Column(Modifier.weight(1f)) {
                    Text("净投入", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    MoneyText(d.invested, style = MaterialTheme.typography.titleLarge)
                }
                IconButton(onClick = { showInfo = !showInfo }) { Icon(Icons.Outlined.Info, "口径说明", tint = cs.onSurfaceVariant) }
            }
            if (showInfo) {
                Text(
                    "累计盈亏 = 当前市值 − 净投入。净投入 = 期初成本 + 之后的申购 − 赎回 − 现金分红，手续费算作成本。" +
                        "前提是这些流水都记全了：漏记一笔申购，会显示成等额盈利。净投入可能为负，所以这里不显示收益率。",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ActionsCard(actions: List<QuickAction>, onClick: (QuickAction) -> Unit) {
    val cs = MaterialTheme.colorScheme
    SectionCard(contentPadding = PaddingValues(vertical = 14.dp, horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            actions.forEach { a ->
                Column(
                    Modifier.clip(MaterialTheme.shapes.medium).clickable { onClick(a) }.padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(a.icon, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(a.label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ChartCard(d: AccountDetail) {
    val cs = MaterialTheme.colorScheme
    val extra = AppTheme.extra
    var range by rememberSaveable { mutableStateOf(ChartRange.ALL) }
    var selected by remember { mutableStateOf<Int?>(null) }
    // The index means nothing in another range: a shorter list would show another day.
    LaunchedEffect(range) { selected = null }
    val points = remember(d.series, range) { range.filter(d.series, { it.at }) }
    val valueColor = extra.group(d.group?.colorIndex ?: 0)
    SectionCard(contentPadding = PaddingValues(20.dp)) {
        val shown = selected?.let { points.getOrNull(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (shown != null) Dates.date(shown.at) else if (d.account.isInvestment) "市值与净投入" else "余额变化",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (shown != null) {
                Column(horizontalAlignment = Alignment.End) {
                    MoneyText(shown.value, style = MaterialTheme.typography.titleSmall, color = valueColor)
                    shown.invested?.let { MoneyText(it, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant) }
                }
            }
        }
        if (d.account.isInvestment) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(valueColor, 8.dp)
                Text(" 市值", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Dot(cs.onSurfaceVariant, 8.dp)
                Text(" 净投入", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        val series = buildList {
            add(ChartSeries(points.map { ChartPoint(it.at, it.value) }, valueColor, fill = true))
            if (d.account.isInvestment) add(ChartSeries(points.mapNotNull { p -> p.invested?.let { ChartPoint(p.at, it) } }, cs.onSurfaceVariant, dashed = true))
        }
        LineChart(series, Modifier.fillMaxWidth().height(190.dp), onSelect = { selected = it })
        Spacer(Modifier.height(8.dp))
        RangeChips(range, { range = it })
    }
}

@Composable
private fun TimelineRow(e: TimelineEntry, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val extra = AppTheme.extra
    val (icon, tint) = when (e.type) {
        EntryType.BASELINE -> Icons.Default.Flag to cs.secondary
        EntryType.STOCKTAKE -> Icons.Default.Checklist to cs.primary
        EntryType.VALUATION -> Icons.Default.Edit to cs.tertiary
        EntryType.FLOW -> Icons.Default.SwapHoriz to cs.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(Dates.dateTime(e.at), e.subtitle).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (e.type == EntryType.FLOW) {
                MoneyText(e.amount, signed = true, fontWeight = FontWeight.SemiBold)
            } else {
                MoneyText(e.amount, fontWeight = FontWeight.SemiBold)
                if (e.delta != null && e.delta != 0L) {
                    Text(
                        Money.format(e.delta, signed = true),
                        style = MaterialTheme.typography.labelMedium.merge(NumberStyle),
                        color = e.netEffect?.let { extra.signed(it) } ?: Color.Unspecified,
                    )
                }
            }
        }
    }
}
