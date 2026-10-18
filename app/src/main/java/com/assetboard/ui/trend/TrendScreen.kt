package com.assetboard.ui.trend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assetboard.domain.Dates
import com.assetboard.domain.Money
import com.assetboard.domain.NetWorthPoint
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.ChartPoint
import com.assetboard.ui.components.ChartRange
import com.assetboard.ui.components.ChartSeries
import com.assetboard.ui.components.LineChart
import com.assetboard.ui.components.ListRow
import com.assetboard.ui.components.MoneyText
import com.assetboard.ui.components.Pill
import com.assetboard.ui.components.RangeChips
import com.assetboard.ui.components.RowDivider
import com.assetboard.ui.components.SectionCard
import com.assetboard.ui.components.SectionHeader
import com.assetboard.ui.theme.AppTheme
import com.assetboard.ui.theme.NumberStyle

@Composable
fun TrendScreen(onBack: () -> Unit, onOpenStocktake: (String) -> Unit, onNewStocktake: () -> Unit) {
    val ledger by LocalContainer.current.repository.ledger.collectAsStateWithLifecycle()
    val all = remember(ledger) { ledger?.netWorthSeries().orEmpty() }
    var range by rememberSaveable { mutableStateOf(ChartRange.Y1) }
    var showAssets by rememberSaveable { mutableStateOf(false) }
    var showDebts by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }
    val points = remember(all, range) { range.filter(all, { it.snapshot.at }) }
    val extra = AppTheme.extra
    val cs = MaterialTheme.colorScheme

    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = { Text("净值趋势") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewStocktake,
                icon = { Icon(Icons.Default.Checklist, null) },
                text = { Text("盘点") },
                containerColor = cs.primary,
                contentColor = cs.onPrimary,
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SectionCard(contentPadding = PaddingValues(20.dp)) {
                    val shown: NetWorthPoint? = selected?.let { points.getOrNull(it) } ?: points.lastOrNull()
                    Text(
                        if (selected != null && shown != null) Dates.date(shown.snapshot.at) else "最新净资产",
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.onSurfaceVariant,
                    )
                    MoneyText(shown?.netWorth ?: 0L, style = MaterialTheme.typography.headlineMedium)
                    if (points.size >= 2 && shown != null) {
                        val change = shown.netWorth - points.first().netWorth
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Text("较 ${Dates.date(points.first().snapshot.at)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Pill(Money.format(change, signed = true), extra.signed(change))
                        }
                    }
                    if (shown != null && (showAssets || showDebts)) {
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (showAssets) Text("资产 " + Money.format(shown.assets), style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = cs.tertiary)
                            if (showDebts) Text("负债 " + Money.format(shown.liabilities), style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = cs.secondary)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    val series = buildList {
                        add(ChartSeries(points.map { ChartPoint(it.snapshot.at, it.netWorth) }, cs.primary, fill = true))
                        if (showAssets) add(ChartSeries(points.map { ChartPoint(it.snapshot.at, it.assets) }, cs.tertiary, dashed = true))
                        if (showDebts) add(ChartSeries(points.map { ChartPoint(it.snapshot.at, it.liabilities) }, cs.secondary, dashed = true))
                    }
                    LineChart(series, Modifier.fillMaxWidth().height(220.dp), onSelect = { selected = it })
                    Spacer(Modifier.height(12.dp))
                    RangeChips(range, { range = it })
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = showAssets, onClick = { showAssets = !showAssets }, label = { Text("总资产") })
                        FilterChip(selected = showDebts, onClick = { showDebts = !showDebts }, label = { Text("总负债") })
                    }
                    if (all.isEmpty()) {
                        Text(
                            "净值曲线只在盘点时记点。点右下角「盘点」记下第一个点。",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            if (all.isNotEmpty()) {
                item {
                    SectionCard {
                        SectionHeader("全部盘点（${all.size}）")
                        val desc = all.asReversed()
                        desc.forEachIndexed { i, p ->
                            if (i > 0) RowDivider()
                            val prev = desc.getOrNull(i + 1)
                            ListRow(
                                title = Dates.date(p.snapshot.at),
                                subtitle = "资产 ${Money.format(p.assets)} · 负债 ${Money.format(p.liabilities)}",
                                onClick = { onOpenStocktake(p.snapshot.id) },
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
            }
        }
    }
}
