package com.assetboard.ui.trend

import com.assetboard.domain.AppClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assetboard.ui.components.GlassScaffold
import com.assetboard.ui.components.glassBarColors
import com.assetboard.domain.Dates
import com.assetboard.domain.LedgerData
import com.assetboard.domain.Money
import com.assetboard.domain.NetWorthPoint
import com.assetboard.domain.NetWorthTrend
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.SegmentStyle
import com.assetboard.ui.components.SegmentedControl
import com.assetboard.ui.components.ChartRange
import com.assetboard.ui.components.ListRow
import com.assetboard.ui.components.MoneyText
import com.assetboard.ui.components.RowDivider
import com.assetboard.ui.components.SectionCard
import com.assetboard.ui.components.SectionHeader
import com.assetboard.ui.components.amount
import com.assetboard.ui.components.animatedCents
import com.assetboard.ui.components.heroSurface
import com.assetboard.ui.components.appear
import com.assetboard.ui.components.pressScale
import com.assetboard.ui.theme.AppTheme
import com.assetboard.ui.theme.NumberStyle
import kotlin.math.abs
import com.assetboard.ui.components.scrollIndicator
import androidx.compose.foundation.lazy.rememberLazyListState

/**
 * Net worth over a period: the headline change, the net worth line, assets against debts per
 * stocktake, which groups made the change, and every stocktake. One pick (tap or drag on either
 * chart) is shared by both charts.
 */
@Composable
fun TrendScreen(onBack: () -> Unit, onOpenStocktake: (String) -> Unit, onNewStocktake: () -> Unit) {
    val ledger by LocalContainer.current.repository.ledger.collectAsStateWithLifecycle()
    val all = remember(ledger) { ledger?.netWorthSeries().orEmpty() }
    var range by rememberSaveable { mutableStateOf(ChartRange.Y1) }
    val period = remember(all, range) { NetWorthTrend.period(all, range.days, AppClock.millis()) }
    var selected by remember(period) { mutableStateOf<Int?>(null) }
    val cs = MaterialTheme.colorScheme

    GlassScaffold(
        topBar = {
            TopAppBar(
                title = { Text("净值趋势") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { StocktakeAction(onNewStocktake) },
                colors = glassBarColors(),
            )
        },
    ) { padding ->
        val barState = rememberLazyListState()
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).scrollIndicator(barState), state = barState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (period == null) {
                item { EmptyTrend(onNewStocktake) }
                return@LazyColumn
            }
            item { Box(Modifier.appear(0)) { Headline(period, range) } }
            item { RangeTabs(range) { range = it } }
            item {
                ChartCard(Modifier.appear(1), "净资产走势", if (period.points.size > 1) "虚线是期初的净资产，线在它上方就是赚了" else null) {
                    NetWorthChart(period.points, selected, { selected = it }, Modifier.fillMaxWidth().height(230.dp))
                }
            }
            item { Box(Modifier.appear(2)) { BalanceCard(period, selected) { selected = it } } }
            if (period.points.size > 1) {
                item { Box(Modifier.appear(3)) { ChangeCard(period, ledger) } }
            }
            item { Box(Modifier.appear(4)) { Stocktakes(all, onOpenStocktake) } }
        }
    }
}

@Composable
private fun StocktakeAction(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .padding(end = 12.dp)
            .pressScale(source, 0.92f)
            .clip(RoundedCornerShape(50))
            .background(cs.primary.copy(alpha = 0.12f))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Checklist, null, tint = cs.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("盘点", style = MaterialTheme.typography.labelLarge, color = cs.primary)
    }
}

/** What the period amounts to: where net worth stands, how far it moved, its high and low. */
@Composable
private fun Headline(p: NetWorthTrend.Period, range: ChartRange) {
    val extra = AppTheme.extra
    val net = animatedCents(p.end.netWorth)
    Column(
        Modifier
            .fillMaxWidth()
            .heroSurface(MaterialTheme.shapes.extraLarge, extra, elevation = 16.dp)
            .padding(22.dp),
    ) {
        Text("净资产 · ${Dates.date(p.end.snapshot.at)} 盘点", color = extra.onHeroMuted, style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
            Text("¥", color = extra.onHero, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 4.dp, end = 4.dp))
            Text(amount(Money.format(net)), color = extra.onHero, style = MaterialTheme.typography.displaySmall, maxLines = 1)
        }
        Spacer(Modifier.height(10.dp))
        if (p.points.size < 2) {
            Text(
                "${range.label}内没有新的盘点，这是最近一次的结果",
                color = extra.onHeroMuted, style = MaterialTheme.typography.bodySmall,
            )
        } else {
            val c = p.change
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(extra.onHero.copy(alpha = 0.16f)).padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (c >= 0) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null, tint = extra.onHero, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    val ratio = p.changeRatio?.let { " (" + (if (it >= 0) "+" else "") + String.format(java.util.Locale.US, "%.2f", it * 100) + "%)" }.orEmpty()
                    Text(amount(Money.format(c, signed = true)) + ratio, color = extra.onHero, style = MaterialTheme.typography.labelLarge.merge(NumberStyle))
                }
                Spacer(Modifier.width(8.dp))
                Text("较 ${Dates.date(p.start.snapshot.at)}", color = extra.onHeroMuted, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(18.dp))
            Row {
                HeadStat("期间最高", p.high, Modifier.weight(1f))
                HeadStat("期间最低", p.low, Modifier.weight(1f))
                Column(Modifier.weight(0.7f), horizontalAlignment = Alignment.End) {
                    Text("盘点", color = extra.onHeroMuted, style = MaterialTheme.typography.labelSmall)
                    Text("${p.points.size} 次", color = extra.onHero, style = MaterialTheme.typography.titleSmall)
                }
            }
            if (p.anchored) {
                Text(
                    "从${range.label}前最近的一次盘点算起",
                    color = extra.onHeroMuted, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun HeadStat(label: String, p: NetWorthPoint, modifier: Modifier) {
    val extra = AppTheme.extra
    Column(modifier) {
        Text("$label · ${Dates.short(p.snapshot.at)}", color = extra.onHeroMuted, style = MaterialTheme.typography.labelSmall)
        Text(amount(Money.short(p.netWorth)), color = extra.onHero, style = MaterialTheme.typography.titleSmall.merge(NumberStyle))
    }
}

/** 3月 / 半年 / 1年 / 全部: the one control this page is about. */
@Composable
private fun RangeTabs(range: ChartRange, onSelect: (ChartRange) -> Unit) {
    SegmentedControl(ChartRange.entries, range, { it.label }, onSelect, style = SegmentStyle.SOLID)
}

@Composable
private fun ChartCard(modifier: Modifier, title: String, hint: String?, content: @Composable () -> Unit) {
    SectionCard(modifier, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        content()
        Text("点按或左右滑动查看每次盘点", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Assets and debts of each stocktake, with the picked (or latest) one's numbers as the legend. */
@Composable
private fun BalanceCard(p: NetWorthTrend.Period, selected: Int?, onSelect: (Int?) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val extra = AppTheme.extra
    val assets = extra.group(0)
    val debts = extra.group(3)
    val net = cs.onSurface
    val shown = selected?.let { p.points.getOrNull(it) } ?: p.end
    ChartCard(Modifier, "资产与负债", "柱子向上是资产、向下是负债，折线是两者相减的净资产") {
        Text(
            if (selected != null) Dates.date(shown.snapshot.at) else "最近一次 · ${Dates.date(shown.snapshot.at)}",
            style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp)) {
            Legend("资产", shown.assets, assets, Modifier.weight(1f))
            Legend("负债", shown.liabilities, debts, Modifier.weight(1f))
            Legend("净资产", shown.netWorth, net, Modifier.weight(1f))
        }
        BalanceBars(p.points, selected, onSelect, assets, debts, net, Modifier.fillMaxWidth().height(190.dp))
    }
}

@Composable
private fun Legend(label: String, cents: Long, color: Color, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(amount(Money.short(cents)), style = MaterialTheme.typography.titleSmall.merge(NumberStyle), maxLines = 1)
    }
}

/**
 * Where the period's change came from: the start, what assets and debts each added, the end;
 * then the groups behind it, biggest effect first.
 */
@Composable
private fun ChangeCard(p: NetWorthTrend.Period, ledger: LedgerData?) {
    val cs = MaterialTheme.colorScheme
    val extra = AppTheme.extra
    val groups = remember(p, ledger) { ledger?.let { NetWorthTrend.groupChanges(it, p) }.orEmpty() }
    SectionCard(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp)) {
        Text("变化来自哪里", style = MaterialTheme.typography.titleMedium)
        Text("${Dates.date(p.start.snapshot.at)} → ${Dates.date(p.end.snapshot.at)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        StepRow("期初净资产", p.start.netWorth, null)
        StepRow(if (p.assetsChange >= 0) "资产增加" else "资产减少", p.assetsChange, p.assetsChange)
        StepRow(if (p.debtsChange > 0) "负债增加" else if (p.debtsChange < 0) "负债减少" else "负债不变", -p.debtsChange, -p.debtsChange)
        Box(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(1.dp).background(cs.outlineVariant))
        StepRow("期末净资产", p.end.netWorth, null, strong = true)
        if (groups.isNotEmpty()) {
            Text("按分组", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
            val biggest = groups.maxOf { abs(it.effect) }.coerceAtLeast(1L)
            groups.forEach { g ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(extra.group(g.group.colorIndex)))
                        Spacer(Modifier.width(8.dp))
                        Text(g.group.name + if (g.group.isLiability) "（负债）" else "", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        MoneyText(g.effect, style = MaterialTheme.typography.bodyMedium, color = extra.signed(g.effect), signed = true, fontWeight = FontWeight.SemiBold)
                    }
                    Row(Modifier.padding(start = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(50)).background(cs.surfaceContainerHighest)) {
                            Box(Modifier.fillMaxWidth(abs(g.effect).toFloat() / biggest).height(5.dp).clip(RoundedCornerShape(50)).background(extra.signed(g.effect).copy(alpha = 0.7f)))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(amount(Money.short(g.start)) + " → " + amount(Money.short(g.end)), style = MaterialTheme.typography.labelSmall.merge(NumberStyle), color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** One line of the change: a plain amount, or (with [effect]) a signed one colored by its effect. */
@Composable
private fun StepRow(label: String, cents: Long, effect: Long?, strong: Boolean = false) {
    val extra = AppTheme.extra
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = if (strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        MoneyText(
            cents,
            style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = effect?.let { if (it == 0L) MaterialTheme.colorScheme.onSurfaceVariant else extra.signed(it) } ?: Color.Unspecified,
            signed = effect != null,
            fontWeight = if (strong) FontWeight.SemiBold else null,
        )
    }
}

@Composable
private fun Stocktakes(all: List<NetWorthPoint>, onOpen: (String) -> Unit) {
    val extra = AppTheme.extra
    SectionCard {
        SectionHeader("全部盘点（${all.size}）")
        val desc = all.asReversed()
        desc.forEachIndexed { i, p ->
            if (i > 0) RowDivider()
            val prev = desc.getOrNull(i + 1)
            ListRow(
                title = Dates.date(p.snapshot.at),
                subtitle = "资产 " + amount(Money.short(p.assets)) + " · 负债 " + amount(Money.short(p.liabilities)),
                onClick = { onOpen(p.snapshot.id) },
                trailing = {
                    Column(horizontalAlignment = Alignment.End) {
                        MoneyText(p.netWorth, fontWeight = FontWeight.SemiBold)
                        prev?.let {
                            val d = p.netWorth - it.netWorth
                            MoneyText(d, style = MaterialTheme.typography.labelMedium, color = extra.signed(d), signed = true)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun EmptyTrend(onNewStocktake: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    SectionCard(contentPadding = PaddingValues(24.dp)) {
        Text("还没有盘点", style = MaterialTheme.typography.titleMedium)
        Text(
            "净值曲线只在盘点时记点：每次盘点记下当时所有账户的余额，连起来就是这里的走势。",
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
        )
        StocktakeAction(onNewStocktake)
    }
}
