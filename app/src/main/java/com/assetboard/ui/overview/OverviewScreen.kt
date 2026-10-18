package com.assetboard.ui.overview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.assetboard.ui.components.animatedCents
import com.assetboard.ui.components.appear
import com.assetboard.ui.components.pressScale
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assetboard.AppContainer
import com.assetboard.data.Account
import com.assetboard.data.AccountGroup
import com.assetboard.domain.Dates
import com.assetboard.domain.Ledger
import com.assetboard.domain.LedgerData
import com.assetboard.domain.Money
import com.assetboard.domain.NetWorthPoint
import com.assetboard.ui.ActionViewModel
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.AccountAvatar
import com.assetboard.ui.components.ChartPoint
import com.assetboard.ui.components.ChartSeries
import com.assetboard.ui.components.DonutChart
import com.assetboard.ui.components.DonutSlice
import com.assetboard.ui.components.Dot
import com.assetboard.ui.components.ErrorDialog
import com.assetboard.ui.components.LineChart
import com.assetboard.ui.components.ListRow
import com.assetboard.ui.components.MoneyText
import com.assetboard.ui.components.Pill
import com.assetboard.ui.components.RowDivider
import com.assetboard.ui.components.SectionCard
import com.assetboard.ui.components.SectionHeader
import com.assetboard.ui.components.HeaderAction
import com.assetboard.ui.components.PrimaryActionButton
import com.assetboard.ui.components.TabHeader
import com.assetboard.ui.components.Sparkline
import com.assetboard.ui.theme.AppTheme
import com.assetboard.ui.theme.NumberStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class AccountRowUi(
    val account: Account,
    val colorIndex: Int,
    /** Debts positive. */
    val display: Long,
    val counted: Boolean,
    val lockDays: Long?,
    val pnl: Long?,
)

data class GroupUi(val group: AccountGroup, val subtotal: Long, val rows: List<AccountRowUi>)

data class AllocationUi(val group: AccountGroup, val value: Long)

data class OverviewState(
    val totals: Ledger.Totals,
    val groups: List<GroupUi>,
    val disabled: List<AccountRowUi>,
    val allocation: List<AllocationUi>,
    val history: List<NetWorthPoint>,
    val changeSinceLast: Long?,
    val hasAccounts: Boolean,
    val hasGroups: Boolean,
)

fun buildOverview(l: LedgerData, now: Long = System.currentTimeMillis()): OverviewState {
    fun row(a: Account): AccountRowUi {
        val g = l.groupById[a.groupId]
        return AccountRowUi(
            account = a,
            colorIndex = g?.colorIndex ?: 0,
            display = l.toDisplay(a.id, l.currentBalance(a.id)),
            counted = a.includeInTotal && !a.isDisabled,
            lockDays = a.lockedUntil?.let { Dates.daysBetween(now, it) }?.takeIf { it > 0 },
            pnl = if (a.isInvestment) l.pnl(a) else null,
        )
    }
    val active = l.accounts.filter { !it.isDisabled }
    val groups = l.groups.map { g ->
        val rows = active.filter { it.groupId == g.id }.map(::row)
        GroupUi(g, rows.filter { it.counted }.sumOf { it.display }, rows)
    }
    val allocation = groups
        .filter { !it.group.isLiability }
        .map { gu -> AllocationUi(gu.group, gu.rows.filter { it.counted }.sumOf { maxOf(0L, it.display) }) }
        .filter { it.value > 0 }
    val history = l.netWorthSeries()
    val totals = l.currentTotals()
    return OverviewState(
        totals = totals,
        groups = groups,
        disabled = l.accounts.filter { it.isDisabled }.map(::row),
        allocation = allocation,
        history = history,
        changeSinceLast = history.lastOrNull()?.let { totals.netWorth - it.netWorth },
        hasAccounts = l.accounts.isNotEmpty(),
        hasGroups = l.groups.isNotEmpty(),
    )
}

class OverviewViewModel(private val container: AppContainer) : ActionViewModel(container) {
    val state: StateFlow<OverviewState?> = container.repository.ledger
        .map { it?.let { l -> buildOverview(l) } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun seedSuishouji() = action { container.repository.seedFromSuishouji() }
    fun seedGroups() = action { container.repository.seedDefaultGroups() }
}

@Composable
fun OverviewScreen(
    onOpenAccount: (String) -> Unit,
    onStocktake: () -> Unit,
    onEditStocktake: (String) -> Unit,
    onTrend: () -> Unit,
    onSettings: () -> Unit,
    onAddAccount: (String?) -> Unit,
) {
    val container = LocalContainer.current
    val vm: OverviewViewModel = viewModel { OverviewViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    ErrorDialog(error) { vm.error.value = null }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            val accounts = state?.groups?.sumOf { it.rows.size } ?: 0
            val today = java.time.LocalDate.now()
            TabHeader(
                "我的资产",
                "${today.monthValue}月${today.dayOfMonth}日 ${com.assetboard.domain.todo.Recurrence.weekName(today.dayOfWeek)}" +
                    if (accounts > 0) " · $accounts 个账户" else "",
            ) {
                HeaderAction(Icons.AutoMirrored.Filled.ShowChart, "净值趋势", onTrend)
                HeaderAction(Icons.Default.Add, "新建账户", { onAddAccount(null) })
                HeaderAction(Icons.Outlined.Settings, "设置", onSettings)
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            if (state?.hasAccounts == true) {
                PrimaryActionButton(Icons.Rounded.Checklist, "盘点", onStocktake, Modifier.padding(bottom = 12.dp))
            }
        },
    ) { padding ->
        val s = state
        when {
            s == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            !s.hasGroups -> Welcome(Modifier.padding(padding), onSeed = vm::seedSuishouji, onBlank = vm::seedGroups)
            else -> BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                if (maxWidth >= 600.dp) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LazyColumn(Modifier.weight(1.1f), contentPadding = PaddingValues(start = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            item { Box(Modifier.appear(0)) { Hero(s) } }
                            accountSections(s, onOpenAccount, onAddAccount, first = 1)
                        }
                        Column(
                            Modifier.weight(0.9f).verticalScroll(rememberScrollState()).padding(end = 16.dp, bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            AllocationCard(s)
                            TrendCard(s, onTrend)
                            RecentStocktakes(s, onEditStocktake)
                        }
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        item { Box(Modifier.appear(0)) { Hero(s) } }
                        if (s.allocation.isNotEmpty()) item { Box(Modifier.appear(1)) { AllocationCard(s) } }
                        accountSections(s, onOpenAccount, onAddAccount, first = 2)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.accountSections(s: OverviewState, onOpenAccount: (String) -> Unit, onAddAccount: (String?) -> Unit, first: Int) {
    val assets = s.totals.assets
    itemsIndexed(s.groups, key = { _, g -> g.group.id }) { i, g ->
        Box(Modifier.appear(first + i)) { GroupCard(g, assets, onOpenAccount, onAddAccount) }
    }
    if (s.disabled.isNotEmpty()) item(key = "disabled") { DisabledCard(s.disabled, onOpenAccount) }
}

/**
 * The net worth card: a slow aurora drifting over the gradient, a light sheen every few seconds,
 * amounts that count up, and the sparkline drawing itself in.
 */
@Composable
private fun Hero(s: OverviewState) {
    val extra = AppTheme.extra
    val motion = rememberInfiniteTransition(label = "hero")
    val drift by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
    val sheen by motion.animateFloat(-0.6f, 1.6f, infiniteRepeatable(tween(7_000, delayMillis = 0, easing = LinearEasing)), label = "sheen")
    val source = remember { MutableInteractionSource() }
    val net = animatedCents(s.totals.netWorth)
    Box(
        Modifier
            .fillMaxWidth()
            .pressScale(source, 0.985f)
            .shadow(18.dp, MaterialTheme.shapes.extraLarge, ambientColor = extra.heroStart, spotColor = extra.heroStart)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.linearGradient(listOf(extra.heroStart, extra.heroEnd)))
            .drawBehind {
                val w = size.width
                val h = size.height
                // Two soft lights that wander across the card.
                val a = Offset(w * (0.15f + 0.7f * drift), h * (0.2f + 0.3f * kotlin.math.sin(drift * 3.14f)))
                val b = Offset(w * (0.9f - 0.6f * drift), h * (0.95f - 0.4f * drift))
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), a, w * 0.55f), w * 0.55f, a)
                drawCircle(Brush.radialGradient(listOf(Color(0xFF8FE3C8).copy(alpha = 0.18f), Color.Transparent), b, w * 0.5f), w * 0.5f, b)
                // The sheen: a narrow diagonal band crossing once per cycle; mostly off-card.
                val x = w * sheen
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent),
                        start = Offset(x - w * 0.25f, 0f), end = Offset(x + w * 0.05f, h),
                    ),
                )
            }
            .clickable(interactionSource = source, indication = null) {}
            .padding(22.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("净资产", color = extra.onHeroMuted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                s.history.lastOrNull()?.let {
                    Text(
                        "上次盘点 · ${Dates.relative(it.snapshot.at)}",
                        color = extra.onHero,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(extra.onHero.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("¥", color = extra.onHero, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 5.dp, end = 4.dp))
                Text(
                    Money.format(net),
                    color = extra.onHero,
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 1,
                )
            }
            s.changeSinceLast?.takeIf { it != 0L }?.let { c ->
                val up = c > 0
                Row(
                    Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(extra.onHero.copy(alpha = 0.14f))
                        .padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (up) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null, tint = extra.onHero, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("较上次盘点 ${Money.format(c, signed = true)}", color = extra.onHero, style = MaterialTheme.typography.labelMedium.merge(NumberStyle))
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                HeroStat("总资产", s.totals.assets, Modifier.weight(1f))
                HeroStat("总负债", -s.totals.liabilities, Modifier.weight(1f))
                if (s.history.size >= 2) {
                    Sparkline(
                        values = s.history.takeLast(24).map { it.netWorth },
                        color = extra.onHero,
                        modifier = Modifier.width(96.dp).height(40.dp),
                        animate = true,
                    )
                }
            }
            // Liabilities are carried negative.
            val debts = -s.totals.liabilities
            if (s.totals.assets > 0 && debts > 0) {
                Spacer(Modifier.height(16.dp))
                DebtRatio(s.totals.assets, debts)
            }
        }
    }
}

/** A thin bar: how much of the assets the debts eat, filling in on first show. */
@Composable
private fun DebtRatio(assets: Long, liabilities: Long) {
    val extra = AppTheme.extra
    val ratio = (liabilities.toDouble() / assets.toDouble()).toFloat().coerceIn(0f, 1f)
    val shown = remember { Animatable(0f) }
    LaunchedEffect(ratio) { shown.animateTo(ratio, tween(1_100, delayMillis = 250, easing = FastOutSlowInEasing)) }
    Column {
        Row {
            Text("负债率", color = extra.onHeroMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(Money.percent(liabilities, assets), color = extra.onHeroMuted, style = MaterialTheme.typography.labelSmall.merge(NumberStyle))
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(extra.onHero.copy(alpha = 0.16f))) {
            Box(Modifier.fillMaxWidth(shown.value).height(6.dp).clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(extra.onHero.copy(alpha = 0.55f), extra.onHero))))
        }
    }
}

@Composable
private fun HeroStat(label: String, cents: Long, modifier: Modifier) {
    val extra = AppTheme.extra
    val shown = animatedCents(cents)
    Column(modifier) {
        Text(label, color = extra.onHeroMuted, style = MaterialTheme.typography.labelMedium)
        MoneyText(shown, color = extra.onHero, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun AllocationCard(s: OverviewState) {
    if (s.allocation.isEmpty()) return
    val extra = AppTheme.extra
    val total = s.allocation.sumOf { it.value }
    var highlighted by rememberSaveable { mutableIntStateOf(-1) }
    // Tapping the ring or anywhere outside a legend row goes back to the total.
    val reset = remember { MutableInteractionSource() }
    SectionCard(Modifier.clickable(interactionSource = reset, indication = null) { highlighted = -1 }, contentPadding = PaddingValues(20.dp)) {
        Text("资产配置", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DonutChart(
                slices = s.allocation.map { DonutSlice(it.value.toFloat(), extra.group(it.group.colorIndex)) },
                modifier = Modifier.size(132.dp).clip(CircleShape).clickable { highlighted = -1 },
                highlighted = highlighted.takeIf { it >= 0 },
            ) {
                val h = s.allocation.getOrNull(highlighted)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(h?.group?.name ?: "总资产", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Money.compact(h?.value ?: total), style = MaterialTheme.typography.titleSmall.merge(NumberStyle))
                    if (h != null) Text("点圆环看总资产", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                s.allocation.forEachIndexed { i, a ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { highlighted = if (highlighted == i) -1 else i }
                            .background(if (highlighted == i) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceContainer)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Dot(extra.group(a.group.colorIndex))
                        Spacer(Modifier.width(8.dp))
                        Text(a.group.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Money.percent(a.value, total), style = MaterialTheme.typography.labelLarge.merge(NumberStyle))
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupCard(g: GroupUi, totalAssets: Long, onOpenAccount: (String) -> Unit, onAddAccount: (String?) -> Unit) {
    val extra = AppTheme.extra
    val color = extra.group(g.group.colorIndex)
    SectionCard {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(color, 8.dp)
            Spacer(Modifier.width(8.dp))
            Text(g.group.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                (if (g.group.isLiability) "负债 " else "资产 ") + Money.format(g.subtotal),
                style = MaterialTheme.typography.labelLarge.merge(NumberStyle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The group's share of all assets, as a thin bar that fills in.
        if (!g.group.isLiability && totalAssets > 0 && g.subtotal > 0) {
            val share = (g.subtotal.toDouble() / totalAssets).toFloat().coerceIn(0f, 1f)
            val shown = remember { Animatable(0f) }
            LaunchedEffect(share) { shown.animateTo(share, tween(900, delayMillis = 200, easing = FastOutSlowInEasing)) }
            Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(shown.value).height(4.dp).clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.6f), color))))
            }
        }
        if (g.rows.isEmpty()) {
            ListRow(
                title = "添加账户",
                onClick = { onAddAccount(g.group.id) },
                leading = { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary) },
                chevron = false,
            )
        }
        g.rows.forEachIndexed { i, r ->
            if (i > 0) RowDivider(startIndent = 74.dp)
            AccountRow(r, onClick = { onOpenAccount(r.account.id) })
        }
    }
}

@Composable
fun AccountRow(r: AccountRowUi, onClick: () -> Unit) {
    val extra = AppTheme.extra
    val muted = !r.counted
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountAvatar(r.account.name, extra.group(r.colorIndex), muted = muted)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                r.account.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            val sub = r.account.purpose
            val hasBadges = r.lockDays != null || !r.counted
            if (!sub.isNullOrBlank() || hasBadges) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                    if (!sub.isNullOrBlank()) {
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                    r.lockDays?.let { Pill("锁定 $it 天", MaterialTheme.colorScheme.secondary) }
                    if (!r.counted && !r.account.isDisabled) Pill("不计入", MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            MoneyText(
                r.display,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            r.pnl?.takeIf { it != 0L }?.let {
                MoneyText(it, style = MaterialTheme.typography.labelMedium, color = extra.signed(it), signed = true)
            }
        }
    }
}

@Composable
private fun DisabledCard(rows: List<AccountRowUi>, onOpenAccount: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    SectionCard {
        ListRow(
            title = "已停用（${rows.size}）",
            subtitle = "不计入净资产",
            onClick = { open = !open },
            chevron = false,
            trailing = { Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
        AnimatedVisibility(open) {
            Column {
                rows.forEach { r ->
                    RowDivider(startIndent = 74.dp)
                    AccountRow(r, onClick = { onOpenAccount(r.account.id) })
                }
            }
        }
    }
}

@Composable
private fun TrendCard(s: OverviewState, onTrend: () -> Unit) {
    SectionCard(contentPadding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("净值走势", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("查看", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable(onClick = onTrend))
        }
        Spacer(Modifier.height(12.dp))
        LineChart(
            series = listOf(ChartSeries(s.history.map { ChartPoint(it.snapshot.at, it.netWorth) }, MaterialTheme.colorScheme.primary, fill = true)),
            modifier = Modifier.fillMaxWidth().height(160.dp),
        )
    }
}

@Composable
private fun RecentStocktakes(s: OverviewState, onEditStocktake: (String) -> Unit) {
    if (s.history.isEmpty()) return
    SectionCard {
        SectionHeader("最近盘点")
        s.history.takeLast(5).asReversed().forEachIndexed { i, p ->
            if (i > 0) RowDivider()
            ListRow(
                title = Dates.date(p.snapshot.at),
                subtitle = Dates.relative(p.snapshot.at).takeIf { it != Dates.date(p.snapshot.at) },
                onClick = { onEditStocktake(p.snapshot.id) },
                trailing = { MoneyText(p.netWorth, fontWeight = FontWeight.SemiBold) },
            )
        }
    }
}

@Composable
private fun Welcome(modifier: Modifier, onSeed: () -> Unit, onBlank: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Checklist, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("开始记录你的资产", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "数据只保存在这台手机上。建议先在设置里选好备份目录。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onSeed, modifier = Modifier.fillMaxWidth()) { Text("导入随手记里的 15 个账户") }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onBlank, modifier = Modifier.fillMaxWidth()) { Text("从空白开始") }
    }
}
