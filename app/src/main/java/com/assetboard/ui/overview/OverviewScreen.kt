package com.assetboard.ui.overview

import com.assetboard.domain.AppClock
import androidx.compose.animation.AnimatedVisibility
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.key
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import com.assetboard.domain.LedgerException
import com.assetboard.ui.components.GlassSnackbarHost
import com.assetboard.ui.TabPageBottomInset
import com.assetboard.ui.components.SwipeAction
import com.assetboard.ui.components.SwipeActionsBox
import com.assetboard.ui.components.LocalSwipeAtRest
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import com.assetboard.ui.components.SwipeGroup
import com.assetboard.ui.components.UndoEvent
import com.assetboard.ui.components.UndoMessages
import com.assetboard.ui.components.rememberSwipeGroup
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.KeyboardType
import com.assetboard.ui.components.GlassSheet
import com.assetboard.domain.GoalProgress
import com.assetboard.ui.components.LocalAmbient
import com.assetboard.ui.components.ambient
import com.assetboard.ui.components.glass
import com.assetboard.ui.components.rememberAmbient
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import com.assetboard.ui.components.tilt
import com.assetboard.ui.components.pressScale
import com.assetboard.ui.components.Motion
import com.assetboard.ui.components.auroraSurface
import com.assetboard.ui.theme.OnAurora
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.platform.LocalDensity
import com.assetboard.ui.components.rememberPullToCreate
import com.assetboard.ui.components.PullToCreateBox
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import com.assetboard.ui.bill.BillCard
import com.assetboard.ui.bill.BillSheet
import com.assetboard.ui.bill.billViewModel
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.assetboard.domain.Liquidity
import com.assetboard.domain.NetWorthPoint
import com.assetboard.domain.health.Energy
import com.assetboard.domain.health.Health
import com.assetboard.ui.health.FoodState
import com.assetboard.ui.health.HealthState
import com.assetboard.ui.health.gradeColor
import com.assetboard.ui.ActionViewModel
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.AccountAvatar
import com.assetboard.ui.components.ChartPoint
import com.assetboard.ui.components.ChartSeries
import com.assetboard.ui.components.Dot
import com.assetboard.ui.components.DonutChart
import com.assetboard.ui.components.DonutSlice
import com.assetboard.ui.components.ErrorDialog
import com.assetboard.ui.components.LineChart
import com.assetboard.ui.components.ListRow
import com.assetboard.ui.components.MoneyText
import com.assetboard.ui.components.LocalHideAmounts
import kotlinx.coroutines.launch
import com.assetboard.ui.components.MASKED
import com.assetboard.ui.components.amount
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import com.assetboard.ui.components.Pill
import com.assetboard.ui.components.RowDivider
import com.assetboard.ui.components.SectionCard
import com.assetboard.ui.components.SectionHeader
import com.assetboard.ui.components.HeaderAction
import com.assetboard.ui.components.SettingsAction
import com.assetboard.ui.components.TabHeader
import com.assetboard.ui.components.Sparkline
import com.assetboard.ui.theme.AppTheme
import com.assetboard.ui.theme.NumberStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.assetboard.ui.components.verticalScrollWithIndicator
import com.assetboard.ui.components.scrollIndicator

data class AccountRowUi(
    val account: Account,
    val colorIndex: Int,
    /** Debts positive. */
    val display: Long,
    val counted: Boolean,
    val lockDays: Long?,
    val pnl: Long?,
    /** Holds nothing but its baseline, so a left swipe deletes it; otherwise it is disabled. */
    val deletable: Boolean = false,
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
    val liquidity: Liquidity,
)

fun buildOverview(l: LedgerData, now: Long = AppClock.millis()): OverviewState {
    fun row(a: Account): AccountRowUi {
        val g = l.groupById[a.groupId]
        return AccountRowUi(
            account = a,
            colorIndex = g?.colorIndex ?: 0,
            display = l.toDisplay(a.id, l.currentBalance(a.id)),
            counted = a.includeInTotal && !a.isDisabled,
            lockDays = a.lockedUntil?.let { Dates.daysBetween(now, it) }?.takeIf { it > 0 },
            pnl = if (a.isInvestment) l.pnl(a) else null,
            deletable = l.flowsOf(a.id).isEmpty() && l.valuationsOf(a.id).size <= 1 && l.snapshotItems.none { it.accountId == a.id },
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
        liquidity = l.liquidity(now),
    )
}

class OverviewViewModel(private val container: AppContainer) : ActionViewModel(container) {
    val state: StateFlow<OverviewState?> = container.repository.ledger
        .map { it?.let { l -> buildOverview(l) } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val health: StateFlow<HealthState?> = combine(container.health.profile, container.health.measurements, ::HealthState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val food: StateFlow<FoodState?> = combine(container.food.entries, container.health.profile, container.health.measurements, ::FoodState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val goal: StateFlow<Long?> = container.goal.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setGoal(cents: Long?) = action { container.goal.set(cents) }

    private val _events = MutableSharedFlow<UndoEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UndoEvent> = _events

    /**
     * A left swipe on an account: one holding only its baseline is deleted, one with history is
     * disabled (its records stay; it moves to 已停用). Either can be undone.
     */
    fun remove(r: AccountRowUi) = action {
        val repo = container.repository
        val name = "「" + r.account.name.take(12) + "」"
        if (r.deletable && repo.canDelete(r.account.id)) {
            val gone = repo.deleteAccount(r.account.id)
            _events.tryEmit(UndoEvent("已删除$name") { repo.restoreAccount(gone) })
        } else {
            repo.setDisabled(r.account.id, true)
            // The undo bar is a sentence, not a MoneyText: it is masked here when amounts are hidden.
            val shown = if (container.settings.hideAmounts.value) MASKED else Money.format(r.display)
            val note = if (r.counted && r.display != 0L) "，$shown 不再计入" else ""
            _events.tryEmit(UndoEvent("已停用$name$note") { repo.setDisabled(r.account.id, false) })
        }
    }

    /** The inline edit after a right swipe: a valuation now, if the amount changed. */
    fun setBalance(r: AccountRowUi, input: String, noun: String, onDone: () -> Unit) = action(onDone) {
        val value = Money.parse(input) ?: throw LedgerException("金额格式不对")
        if (value == r.display) return@action
        val repo = container.repository
        val id = repo.addValuation(r.account.id, AppClock.millis(), value, null)
        val shown = if (container.settings.hideAmounts.value) MASKED else Money.format(value)
        _events.tryEmit(UndoEvent("「${r.account.name.take(12)}」$noun 改为 $shown") { repo.deleteValuation(id) })
    }

    fun undo(e: UndoEvent) {
        val u = e.undo ?: return
        action { u() }
    }

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
    onHealth: (record: Boolean) -> Unit = {},
    onFood: (record: Boolean) -> Unit = {},
    onBills: () -> Unit = {},
) {
    val container = LocalContainer.current
    val vm: OverviewViewModel = viewModel { OverviewViewModel(container) }
    val billVm = billViewModel("overview")
    val ledger by container.repository.ledger.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val food by vm.food.collectAsStateWithLifecycle()
    val goal by vm.goal.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    ErrorDialog(error) { vm.error.value = null }
    val snackbar = remember { SnackbarHostState() }
    UndoMessages(vm.events, snackbar, vm::undo)
    UndoMessages(billVm.events, snackbar, billVm::undo)
    // Account rows: one open by a swipe at a time, one being edited in place at a time.
    val swipe = rememberSwipeGroup()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(editing != null) { editing = null }
    val listState = rememberLazyListState()
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) swipe.closeAll() }
    // 记一笔: a new bill (true), or the bill being edited from the card.
    var recording by rememberSaveable { mutableStateOf(false) }
    var billEditing by rememberSaveable { mutableStateOf<String?>(null) }
    if (recording) BillSheet(null, billVm, onDismiss = { recording = false }, onAddAccount = { onAddAccount(null) })
    billEditing?.let { id ->
        val f = ledger?.flows?.firstOrNull { it.id == id }
        if (f == null) billEditing = null else BillSheet(f, billVm, onDismiss = { billEditing = null })
    }
    // Pulling the list down past its top records a bill; 新建账户 stays in the header.
    val pull = rememberPullToCreate(enabled = editing == null && !recording && billEditing == null) { swipe.closeAll(); editing = null; recording = true }
    val pullTop = with(LocalDensity.current) { pull.pulled.toDp() }
    val rows = RowActions(
        swipe = swipe,
        editing = editing,
        onEdit = { id -> swipe.closeAll(); editing = id },
        onRemove = vm::remove,
        onSetBalance = { r, input, noun -> vm.setBalance(r, input, noun) { if (editing == r.account.id) editing = null } },
    )
    var cashOpen by rememberSaveable { mutableStateOf(false) }
    state?.takeIf { cashOpen }?.let { s ->
        CashSheet(s, onOpenAccount = { cashOpen = false; onOpenAccount(it) }, onDismiss = { cashOpen = false })
    }
    var goalOpen by rememberSaveable { mutableStateOf(false) }
    state?.takeIf { goalOpen }?.let { s ->
        GoalDialog(goal, s.totals.netWorth, onDismiss = { goalOpen = false }) { goalOpen = false; vm.setGoal(it) }
    }
    val hero: @Composable (OverviewState) -> Unit = { s -> Hero(s, goal, onStocktake, onGoal = { goalOpen = true }, onCash = { cashOpen = true }) }

    val ambient = rememberAmbient()
    Box(Modifier.fillMaxSize().ambient(ambient)) {
        CompositionLocalProvider(LocalAmbient provides ambient) {
        Scaffold(
            containerColor = Color.Transparent,
            // Clear of the floating tab bar, which the page runs on under.
            snackbarHost = { GlassSnackbarHost(snackbar, Modifier.padding(bottom = TabPageBottomInset)) },
            topBar = {
                val accounts = state?.groups?.sumOf { it.rows.size } ?: 0
                val today = AppClock.today()
                TabHeader(
                    "我的资产",
                    "${today.monthValue}月${today.dayOfMonth}日 ${com.assetboard.domain.todo.Recurrence.weekName(today.dayOfWeek)}" +
                        if (accounts > 0) " · $accounts 个账户" else "",
                ) {
                    HeaderAction(Icons.AutoMirrored.Filled.ShowChart, "净值趋势", onTrend)
                    HeaderAction(Icons.Default.Add, "新建账户", { onAddAccount(null) })
                    SettingsAction(onSettings)
                }
            },
        ) { padding ->
            val s = state
            when {
                s == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                !s.hasGroups -> Welcome(Modifier.padding(padding), onStart = vm::seedGroups)
                else -> BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                    if (maxWidth >= 600.dp) {
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            PullToCreateBox(pull, PULL_HINT, PULL_ARMED, Modifier.weight(1.1f), icon = Icons.AutoMirrored.Outlined.ReceiptLong) {
                                LazyColumn(Modifier.fillMaxSize().imePadding().scrollIndicator(listState), state = listState, contentPadding = PaddingValues(start = 16.dp, top = pullTop, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    item { Box(Modifier.appear(0)) { hero(s) } }
                                    item(key = "allocation") { Box(Modifier.appear(1)) { HeroAllocation(s.allocation) } }
                                    accountSections(s, onOpenAccount, onAddAccount, rows, first = 2)
                                }
                            }
                            Column(
                                Modifier.weight(0.9f).verticalScrollWithIndicator(rememberScrollState()).padding(end = 16.dp, bottom = 32.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                TrendCard(s, onTrend)
                                RecentStocktakes(s, onEditStocktake)
                                Tiles(food, health, onFood, onHealth)
                                ledger?.let { l -> BillCard(l, onBills, onRecord = { recording = true }, onEdit = { billEditing = it.id }) }
                            }
                        }
                    } else {
                        PullToCreateBox(pull, PULL_HINT, PULL_ARMED, Modifier.fillMaxSize(), icon = Icons.AutoMirrored.Outlined.ReceiptLong) {
                            LazyColumn(
                                Modifier.fillMaxSize().imePadding().scrollIndicator(listState),
                                state = listState,
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pullTop, bottom = 32.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                item { Box(Modifier.appear(0)) { hero(s) } }
                                // Today's food and weight right under the figure, before the allocation.
                                item(key = "tiles") { Box(Modifier.appear(1)) { Tiles(food, health, onFood, onHealth) } }
                                // This month's 记账, which the pull above records into.
                                ledger?.let { l -> item(key = "bills") { Box(Modifier.appear(2)) { BillCard(l, onBills, onRecord = { recording = true }, onEdit = { billEditing = it.id }) } } }
                                item(key = "allocation") { Box(Modifier.appear(3)) { HeroAllocation(s.allocation) } }
                                accountSections(s, onOpenAccount, onAddAccount, rows, first = 4)
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

private const val PULL_HINT = "下拉记一笔"
private const val PULL_ARMED = "松手记一笔"

private fun LazyListScope.accountSections(s: OverviewState, onOpenAccount: (String) -> Unit, onAddAccount: (String?) -> Unit, rows: RowActions, first: Int) {
    item(key = "accounts") { Box(Modifier.appear(first)) { AccountsCard(s, onOpenAccount, onAddAccount, rows) } }
    if (s.disabled.isNotEmpty()) item(key = "disabled") { DisabledCard(s.disabled, onOpenAccount) }
}

/**
 * One primary figure, its supporting totals and two actionable details, on warm glass whose
 * colour drifts slowly under it; allocation has its own surface below this card.
 */
@Composable
private fun Hero(s: OverviewState, goal: Long?, onStocktake: () -> Unit, onGoal: () -> Unit, onCash: () -> Unit) {
    val aurora = AppTheme.extra.aurora
    Box(Modifier.fillMaxWidth().tilt().auroraSurface(MaterialTheme.shapes.extraLarge, aurora).padding(20.dp)) {
        OnAurora { HeroContent(s, goal, onStocktake, onGoal, onCash) }
    }
}

@Composable
private fun HeroContent(s: OverviewState, goal: Long?, onStocktake: () -> Unit, onGoal: () -> Unit, onCash: () -> Unit) {
    val extra = AppTheme.extra
    val aurora = extra.aurora
    val net = animatedCents(s.totals.netWorth, countUp = true)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // The eye hides every amount, here and wherever else one is shown, and on the widget.
                val container = LocalContainer.current
                val settings = container.settings
                val context = androidx.compose.ui.platform.LocalContext.current
                val hidden = LocalHideAmounts.current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("净资产", color = extra.onHeroMuted, style = MaterialTheme.typography.labelLarge)
                    IconButton(
                        onClick = {
                                settings.setBool(settings.hideAmounts, !hidden)
                                container.appScope.launch { com.assetboard.widget.NetWorthWidget.refresh(context) }
                        }, modifier = Modifier.size(48.dp),
                    ) {
                        Icon(if (hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            if (hidden) "显示金额" else "隐藏金额", tint = extra.onHeroMuted, modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    s.history.lastOrNull()?.let { "上次盘点 · ${Dates.relative(it.snapshot.at)}" } ?: "还没有盘点过",
                    color = extra.onHeroMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (s.hasAccounts) StocktakeButton(onStocktake)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "¥",
                style = MaterialTheme.typography.titleLarge.copy(brush = Brush.linearGradient(aurora.flame)),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 7.dp, end = 5.dp),
            )
            Text(
                amount(Money.format(net)),
                style = MaterialTheme.typography.displayMedium.merge(NumberStyle).copy(brush = Brush.linearGradient(aurora.figure)),
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = 42.sp),
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
        }
        s.changeSinceLast?.takeIf { it != 0L }?.let { c ->
            val up = c > 0
            val tone = extra.signed(c)
            Row(
                Modifier
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(aurora.pane)
                    .border(1.dp, aurora.paneRim, RoundedCornerShape(50))
                    .padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (up) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null, tint = tone, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("较上次盘点 ", color = extra.onHeroMuted, style = MaterialTheme.typography.labelMedium)
                Text(amount(Money.format(c, signed = true)), color = tone, style = MaterialTheme.typography.labelMedium.merge(NumberStyle), fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(20.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val debtLabel = "总负债" + if (s.totals.assets > 0) " · " + Money.percent(-s.totals.liabilities, s.totals.assets) else ""
            if (maxWidth / LocalDensity.current.fontScale < 280.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeroStat("总资产", s.totals.assets, Modifier.fillMaxWidth())
                    HeroStat(debtLabel, -s.totals.liabilities, Modifier.fillMaxWidth())
                }
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    HeroStat("总资产", s.totals.assets, Modifier.weight(1f))
                    Box(Modifier.padding(horizontal = 16.dp).width(1.dp).height(32.dp).background(extra.onHero.copy(alpha = 0.14f)))
                    HeroStat(debtLabel, -s.totals.liabilities, Modifier.weight(1f))
                }
            }
        }
        if (s.hasAccounts) {
            Spacer(Modifier.height(16.dp))
            HeroGauges(s, goal, onCash, onGoal)
        }
    }
}

/** A flame pill on the warm card, the one way into a stocktake, with a gloss that breathes. */
@Composable
private fun StocktakeButton(onClick: () -> Unit) {
    val aurora = AppTheme.extra.aurora
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    val glow = rememberInfiniteTransition(label = "stocktake")
        .animateFloat(0.35f, 0.75f, infiniteRepeatable(tween(1_800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    val flame = aurora.flame.last()
    Row(
        Modifier
            .pressScale(source, 0.92f)
            .drawBehind {
                // A soft halo in the flame's colour that swells and settles.
                val r = size.height * 0.9f
                drawRoundRect(
                    Brush.radialGradient(listOf(flame.copy(alpha = 0.32f * glow.value), Color.Transparent), center = center, radius = size.width * 0.75f),
                    topLeft = Offset(-r * 0.4f, -r * 0.3f), size = Size(size.width + r * 0.8f, size.height + r * 0.6f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r),
                )
            }
            .shadow(8.dp, shape, ambientColor = flame, spotColor = flame)
            .clip(shape)
            .background(Brush.linearGradient(aurora.flame))
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent), endY = size.height * 0.55f))
            }
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .height(46.dp)
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Checklist, null, tint = aurora.onFlame, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text("盘点", color = aurora.onFlame, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Cash at hand and the goal side by side under the totals: two clear actions. Cash
 * opens [CashSheet]; the goal tile changes the goal, or sets one when there is none.
 */
@Composable
private fun HeroGauges(s: OverviewState, goal: Long?, onCash: () -> Unit, onGoal: () -> Unit) {
    val extra = AppTheme.extra
    val l = s.liquidity
    val progress = remember(s, goal) { goal?.let { GoalProgress.of(it, s.totals.netWorth, s.history.map { p -> p.snapshot.at to p.netWorth }) } }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(l) { reveal.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val tile = Modifier.weight(1f).fillMaxHeight()
        if (l.total > 0) {
            // Cash in the flame's warmest, then locked in its red, then invested faintest.
            val a = extra.aurora
            val parts = listOf(l.cash to a.flame.first(), l.locked to a.flame.last().copy(alpha = 0.6f), l.invested to extra.onHero.copy(alpha = 0.18f))
                .filter { it.first > 0 }
            GaugeTile(
                Icons.Outlined.Payments, a.flame.first(), "可用现金", amount("¥" + Money.short(animatedCents(l.cash))), extra.onHero,
                "占 " + Money.percent(l.cash, l.total), tile, onCash,
            ) {
                Row(Modifier.fillMaxWidth(reveal.value).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    parts.forEach { (cents, color) -> Box(Modifier.weight(cents.toFloat()).fillMaxHeight().clip(RoundedCornerShape(50)).background(color)) }
                }
            }
        }
        if (progress == null) {
            GaugeTile(Icons.Outlined.Flag, extra.onHeroAccent, "攒钱目标", "设定目标", extra.onHero, "让积累有方向", tile, onGoal, showBar = false) {}
        } else {
            GoalTile(progress, tile, onGoal)
        }
    }
}

/** The goal's tile: a bounded progress transition and the time to go at the recent pace. */
@Composable
private fun GoalTile(p: GoalProgress, modifier: Modifier, onClick: () -> Unit) {
    val extra = AppTheme.extra
    val shown = remember { Animatable(0f) }
    LaunchedEffect(p.fraction) { shown.animateTo(p.fraction, tween(360, easing = FastOutSlowInEasing)) }
    val gold = extra.onHeroAccent
    GaugeTile(
        Icons.Outlined.Flag, gold, "目标 " + amount(Money.compact(p.goal)),
        if (p.reached) "已达成" else Money.percent(p.net.coerceAtLeast(0), p.goal),
        if (p.reached) gold else extra.onHero,
        when {
            p.reached -> "点这里设新目标"
            p.monthsToGo != null -> "约 ${monthsText(p.monthsToGo)}"
            else -> "还差 " + amount("¥" + Money.short(p.remaining))
        },
        modifier, onClick,
    ) {
        Box(
            Modifier.fillMaxSize().drawBehind {
                val h = size.height
                val w = size.width * shown.value
                if (w <= 0f) return@drawBehind
                val fill = Size(w.coerceAtLeast(h), h)
                val r = androidx.compose.ui.geometry.CornerRadius(h / 2)
                drawRoundRect(Brush.horizontalGradient(extra.aurora.flame, endX = fill.width), size = fill, cornerRadius = r)
            },
        )
    }
}

/** One of the hero's small tiles: an icon and a label, the figure, a thin bar along the bottom and a line under it. */
@Composable
private fun GaugeTile(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    value: String,
    valueColor: Color,
    caption: String,
    modifier: Modifier,
    onClick: (() -> Unit)?,
    showBar: Boolean = true,
    bar: @Composable BoxScope.() -> Unit,
) {
    val extra = AppTheme.extra
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .then(if (onClick != null) Modifier.pressScale(source) else Modifier)
            .clip(RoundedCornerShape(18.dp))
            // A pane of frosted glass on the warm card: a milky fill and a rim lit along its top.
            .background(Brush.verticalGradient(listOf(extra.aurora.pane, extra.aurora.pane.copy(alpha = extra.aurora.pane.alpha * 0.6f))))
            .border(1.dp, Brush.verticalGradient(listOf(extra.aurora.paneRim, extra.aurora.paneRim.copy(alpha = 0.1f))), RoundedCornerShape(18.dp))
            .then(if (onClick != null) Modifier.clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {}
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = extra.onHeroMuted, style = MaterialTheme.typography.labelSmall.merge(NumberStyle), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            value, color = valueColor, style = MaterialTheme.typography.titleSmall.merge(NumberStyle), fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp),
        )
        Spacer(Modifier.weight(1f).heightIn(min = 7.dp))
        if (showBar) Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(extra.aurora.flame.first().copy(alpha = 0.18f)), content = bar)
        else Spacer(Modifier.height(4.dp))
        Text(
            caption, color = extra.onHeroMuted, style = MaterialTheme.typography.labelSmall.merge(NumberStyle),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp),
        )
    }
}

@Composable
private fun HeroStat(label: String, cents: Long, modifier: Modifier) {
    val extra = AppTheme.extra
    val shown = animatedCents(cents)
    Column(modifier) {
        Text(label, color = extra.onHeroMuted, style = MaterialTheme.typography.labelMedium)
        Text(amount(Money.format(shown)), color = extra.onHero, style = MaterialTheme.typography.titleMedium.merge(NumberStyle),
            maxLines = 1, autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 16.sp))
    }
}

/**
 * Where the assets sit: a small ring and a row per group, largest first. Tapping a row lifts its
 * slice and puts its share in the middle; tapping it again lets go.
 */
@Composable
private fun HeroAllocation(allocation: List<AllocationUi>) {
    if (allocation.isEmpty()) return
    val extra = AppTheme.extra
    val cs = MaterialTheme.colorScheme
    val parts = allocation.sortedByDescending { it.value }
    val total = parts.sumOf { it.value }
    val shades = parts.map { extra.group(it.group.colorIndex) }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val index = parts.indexOfFirst { it.group.id == picked }.takeIf { it >= 0 }
    // The share bars grow in just after the ring starts drawing.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(900, delayMillis = 150, easing = Motion.Decelerate)) }
    SectionCard(contentPadding = PaddingValues(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("资产配置", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${parts.size} 个分组", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val ringSize = if (maxWidth / LocalDensity.current.fontScale < 280.dp) 60.dp else 96.dp
        Row(verticalAlignment = Alignment.CenterVertically) {
        // The ring turns into place as it draws, the way the todo dial does.
        DonutChart(
            slices = parts.mapIndexed { i, a -> DonutSlice(a.value.toFloat(), shades[i]) },
            modifier = Modifier.size(ringSize).graphicsLayer {
                val t = reveal.value
                rotationZ = -70f * (1f - t)
                val sc = 0.86f + 0.14f * t
                scaleX = sc; scaleY = sc
                alpha = (0.2f + 0.8f * t).coerceAtMost(1f)
            },
            thickness = if (ringSize < 96.dp) 7.dp else 11.dp,
            highlighted = index,
            track = cs.outlineVariant,
        ) {
            val h = index?.let { parts[it] }
            Text(
                h?.let { Money.percent(it.value, total) } ?: "配置",
                color = if (h == null) cs.onSurfaceVariant else cs.onSurface,
                style = (if (h == null) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge).merge(NumberStyle),
                modifier = Modifier.width(ringSize - 28.dp), textAlign = TextAlign.Center, maxLines = 1,
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 14.sp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            parts.forEachIndexed { i, a ->
                val on = picked == a.group.id
                val wash by animateColorAsState(if (on) shades[i].copy(alpha = 0.10f) else Color.Transparent, tween(200), label = "pick")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(wash)
                        .clickable { picked = if (on) null else a.group.id }
                        .heightIn(min = 40.dp)
                        .semantics { selected = on }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Dot(shades[i], 7.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Amounts already live in the account list; allocation only compares shares.
                            Text(a.group.name, color = cs.onSurface, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(
                                Money.percent(a.value, total), color = if (on) shades[i] else cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge.merge(NumberStyle),
                                maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        // Each share as a thin bar too, so the legend reads at a glance without the ring.
                        Box(Modifier.fillMaxWidth().padding(top = 5.dp).height(3.dp).clip(RoundedCornerShape(50)).background(shades[i].copy(alpha = 0.16f))) {
                            Box(Modifier.fillMaxWidth((a.value.toFloat() / total * reveal.value).coerceIn(0.02f, 1f)).fillMaxHeight().background(shades[i]))
                        }
                    }
                }
            }
        }
    }
    }
    }
}

private fun monthsText(m: Int): String = when {
    m < 12 -> "$m 个月"
    m % 12 == 0 -> "${m / 12} 年"
    else -> "${m / 12} 年 ${m % 12} 个月"
}

/** Setting the goal: an amount in yuan ("100万" works too) or one of a few round amounts above today's net worth. */
@Composable
private fun GoalDialog(current: Long?, net: Long, onDismiss: () -> Unit, onSave: (Long?) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current?.let { Money.toInput(it).removeSuffix(".00") } ?: "") }
    val parsed = GoalProgress.parseInput(text)
    val suggestions = remember(net) { GoalProgress.suggestions(net) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current == null) "设个攒钱目标" else "目标资产") },
        text = {
            Column {
                Text("净资产达到多少？卡片上会显示进度和预计还要多久。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    text, { text = it.take(20) },
                    singleLine = true,
                    prefix = { Text("¥") },
                    placeholder = { Text("如 1000000 或 100万") },
                    isError = text.isNotBlank() && parsed == null,
                    supportingText = parsed?.let { { Text(Money.compact(it) + " 元") } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { c ->
                        FilterChip(parsed == c, onClick = { text = Money.toInput(c).removeSuffix(".00") }, label = { Text(Money.compact(c)) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(parsed) }, enabled = parsed != null) { Text("保存") } },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null) }) { Text("清除目标", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/**
 * Every group in one card, folded by default: a group shows its initial, a few account tiles, how
 * many accounts it has and its subtotal; tapping it opens the accounts underneath. The header
 * opens or folds them all at once.
 */
@Composable
private fun AccountsCard(s: OverviewState, onOpenAccount: (String) -> Unit, onAddAccount: (String?) -> Unit, rows: RowActions) {
    val cs = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(listOf<String>()) }
    val all = s.groups.map { it.group.id }
    val allOpen = all.isNotEmpty() && open.containsAll(all)
    // A heading over the groups, then each group a glass card of its own.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("账户", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Text("${s.groups.sumOf { it.rows.size }} 个", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(
                if (allOpen) "全部收起" else "全部展开",
                style = MaterialTheme.typography.labelLarge, color = cs.primary,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { open = if (allOpen) emptyList() else all }.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        s.groups.forEach { g ->
            val id = g.group.id
            GroupSection(
                g, s.totals.assets, id in open,
                onToggle = { open = if (id in open) open - id else open + id },
                onOpenAccount = onOpenAccount, onAddAccount = onAddAccount, rows = rows,
            )
        }
    }
}

@Composable
private fun GroupSection(
    g: GroupUi,
    totalAssets: Long,
    open: Boolean,
    onToggle: () -> Unit,
    onOpenAccount: (String) -> Unit,
    onAddAccount: (String?) -> Unit,
    rows: RowActions,
) {
    val cs = MaterialTheme.colorScheme
    val color = AppTheme.extra.group(g.group.colorIndex)
    val turn by animateFloatAsState(if (open) 180f else 0f, Motion.bouncy(), label = "fold")
    val shape = MaterialTheme.shapes.large
    // Open, the card takes a wash of the group's colour, so its accounts read as one set.
    val wash by animateColorAsState(if (open) color.copy(alpha = 0.07f) else Color.Transparent, tween(240), label = "wash")
    val source = remember { MutableInteractionSource() }
    // The share of the assets, as a thin line along the card's bottom edge: the figure at a glance.
    val share = if (!g.group.isLiability && totalAssets > 0 && g.subtotal > 0) (g.subtotal.toFloat() / totalAssets).coerceIn(0f, 1f) else 0f
    val shareShown by animateFloatAsState(share, tween(700, delayMillis = 200, easing = Motion.Decelerate), label = "share")
    val detail = when {
        g.rows.isEmpty() -> "还没有账户"
        g.group.isLiability -> "${g.rows.size} 个账户 · 负债"
        totalAssets > 0 && g.subtotal > 0 -> "${g.rows.size} 个账户 · 占 ${Money.percent(g.subtotal, totalAssets)}"
        else -> "${g.rows.size} 个账户"
    }
    Column(Modifier.fillMaxWidth().pressScale(source, 0.99f).glass(shape, glass, wash).clip(shape)) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val compact = maxWidth / LocalDensity.current.fontScale < 300.dp
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = source, indication = null, onClick = onToggle)
            .drawBehind {
                if (shareShown <= 0f) return@drawBehind
                val h = 2.dp.toPx()
                drawRoundRect(color.copy(alpha = 0.55f), Offset(0f, size.height - h), Size(size.width * shareShown, h), androidx.compose.ui.geometry.CornerRadius(h))
            }
            .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(11.dp))
                .background(Brush.linearGradient(listOf(color.copy(alpha = 0.34f), color.copy(alpha = 0.10f))))
                .border(1.dp, color.copy(alpha = 0.22f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(g.group.name.take(1), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(g.group.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 1.dp))
            if (compact) {
                Text(amount(Money.format(g.subtotal)), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), fontWeight = FontWeight.SemiBold,
                    maxLines = 1, autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 15.sp), modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        if (!compact) {
            Text(amount(Money.format(g.subtotal)), style = MaterialTheme.typography.bodyLarge.merge(NumberStyle), fontWeight = FontWeight.SemiBold,
                maxLines = 1, autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 16.sp), textAlign = TextAlign.End,
                modifier = Modifier.weight(0.9f))
        }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Default.ExpandMore, if (open) "收起" else "展开", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp).rotate(turn))
    }
    }
    AnimatedVisibility(
        open,
        enter = expandVertically(Motion.soft()) + fadeIn(tween(220, delayMillis = 60)),
        exit = shrinkVertically(Motion.soft()) + fadeOut(tween(120)),
    ) {
        // Each account on a pane of its own inside the group's card, arriving one after another.
        Column(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (g.rows.isEmpty()) {
                Box(Modifier.fillMaxWidth().clip(AccountPaneShape).background(accountPane())) {
                    ListRow(
                        title = "添加账户",
                        onClick = { onAddAccount(g.group.id) },
                        leading = { Icon(Icons.Default.Add, null, tint = cs.primary) },
                        chevron = false,
                    )
                }
            }
            g.rows.forEachIndexed { i, r ->
                key(r.account.id) {
                    Box(Modifier.appear(i)) { SwipeAccountPane(r, g.group.isLiability, rows, onOpen = { onOpenAccount(r.account.id) }) }
                }
            }
        }
    }
    }
}

private val AccountPaneShape = RoundedCornerShape(14.dp)

/** What a swipe on an account row can do, from the screen that keeps the state. */
private class RowActions(
    val swipe: SwipeGroup,
    /** The account being edited in place, if any. */
    val editing: String?,
    val onEdit: (String?) -> Unit,
    val onRemove: (AccountRowUi) -> Unit,
    val onSetBalance: (AccountRowUi, String, String) -> Unit,
)

/** What a valuation of the account is called: market value, debt or balance. */
private fun noun(r: AccountRowUi, liability: Boolean) = when {
    liability -> "欠款"
    r.account.isInvestment -> "市值"
    else -> "余额"
}

/**
 * An account on its pane, with the todo rows' swipes: left turns it to dust and deletes it (or
 * disables it, once it has history); right offers 改余额, and a full swipe or a tap on it turns the
 * pane itself into the amount field, keyboard up, the old amount selected.
 */
@Composable
private fun SwipeAccountPane(r: AccountRowUi, liability: Boolean, rows: RowActions, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val id = r.account.id
    val editing = rows.editing == id
    val noun = noun(r, liability)
    val ring by animateColorAsState(if (editing) cs.primary.copy(alpha = 0.55f) else Color.Transparent, tween(200), label = "editRing")
    SwipeActionsBox(
        key = "account:$id",
        group = rows.swipe,
        shape = AccountPaneShape,
        enabled = !editing,
        actions = listOf(SwipeAction("改$noun", Icons.Outlined.Edit, cs.primary) { rows.onEdit(id) }),
        onDelete = { rows.onRemove(r) },
        deleteLabel = if (r.deletable) "删除" else "停用",
        deleteIcon = if (r.deletable) Icons.Outlined.Delete else Icons.Outlined.Block,
    ) {
        Box(Modifier.fillMaxWidth().clip(AccountPaneShape).background(accountPane()).border(1.5.dp, ring, AccountPaneShape)) {
            AnimatedContent(
                editing,
                transitionSpec = { (fadeIn(tween(200)) + scaleIn(tween(220), initialScale = 0.97f)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false) },
                label = "accountEdit",
            ) { on ->
                if (on) {
                    AccountRowEditor(r, noun, onSave = { input -> rows.onSetBalance(r, input, noun) }, onCancel = { rows.onEdit(null) })
                } else {
                    AccountRow(r, onClick = onOpen, horizontalPadding = 12.dp)
                }
            }
        }
    }
}

/** The row as an amount field: avatar and name kept, the amount editable where it stood. */
@Composable
private fun AccountRowEditor(r: AccountRowUi, noun: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val extra = AppTheme.extra
    val start = Money.toInput(r.display)
    // Kept across a rotation, and re-read per account so another row never shows the last amount.
    var text by rememberSaveable(r.account.id) { mutableStateOf(start) }
    var value by remember(r.account.id) { mutableStateOf(TextFieldValue(text, TextRange(0, text.length))) }
    val focus = remember { FocusRequester() }
    // Once the pane has slid back from the swipe (see LocalSwipeAtRest).
    val atRest = LocalSwipeAtRest.current
    LaunchedEffect(r.account.id) {
        snapshotFlow { atRest() }.first { it }
        runCatching { focus.requestFocus() }
    }
    val valid = Money.parse(value.text) != null
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountAvatar(r.account, extra.group(r.colorIndex), size = AccountRowAvatar)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.account.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("改$noun", style = MaterialTheme.typography.labelSmall, color = cs.primary, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        // The field as wide as its text, at the end of the box: aligned to the end inside a wider
        // field instead, its cursor sat a fraction of a pixel past the edge, and the field kept
        // scrolling to show it, a scroll that went on to the tabs and had them creep sideways.
        Box(
            Modifier
                .width(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(cs.primary.copy(alpha = 0.08f))
                .clickable(interactionSource = null, indication = null) { runCatching { focus.requestFocus() } }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            BasicTextField(
                value = value,
                onValueChange = { v ->
                    value = v.copy(text = v.text.filter { it.isDigit() || it in ".,-+*/×÷()" })
                    text = value.text
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.merge(NumberStyle).copy(color = if (valid) cs.onSurface else cs.error),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onSave(value.text) }),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.width(IntrinsicSize.Max).focusRequester(focus),
            )
        }
        IconButton(onClick = { if (valid) onSave(value.text) }, enabled = valid, modifier = Modifier.size(40.dp)) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(if (valid) cs.primary else cs.outlineVariant), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, "保存", tint = cs.onPrimary, modifier = Modifier.size(18.dp))
            }
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Close, "取消", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

/** A pane lighter than the glass under it (darker in the dark theme), the way checklist items sit on a todo. */
@Composable
private fun accountPane(): Color {
    val cs = MaterialTheme.colorScheme
    return if (cs.background.luminance() < 0.5f) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.50f)
}

/** The account tile on a row: small enough that a long group stays a compact list. */
private val AccountRowAvatar = 34.dp

@Composable
fun AccountRow(r: AccountRowUi, onClick: () -> Unit, horizontalPadding: Dp = 20.dp) {
    val extra = AppTheme.extra
    val muted = !r.counted
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = horizontalPadding, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountAvatar(r.account, extra.group(r.colorIndex), muted = muted, size = AccountRowAvatar)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                r.account.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            val sub = r.account.purpose
            val hasBadges = r.lockDays != null || !r.counted
            if (!sub.isNullOrBlank() || hasBadges) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 1.dp)) {
                    if (!sub.isNullOrBlank()) {
                        Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
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
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
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
        AnimatedVisibility(open, enter = expandVertically(Motion.soft()) + fadeIn(tween(220, delayMillis = 60)), exit = shrinkVertically(Motion.soft()) + fadeOut(tween(120))) {
            Column {
                rows.forEach { r ->
                    RowDivider(startIndent = 64.dp)
                    AccountRow(r, onClick = { onOpenAccount(r.account.id) })
                }
            }
        }
    }
}

/** Today's food and the latest weigh-in, side by side and the same height. */
@Composable
private fun Tiles(food: FoodState?, health: HealthState?, onFood: (record: Boolean) -> Unit, onHealth: (record: Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val tile = Modifier.weight(1f).fillMaxHeight()
        // Each held open while it loads, so neither tile jumps from full width to half.
        if (food != null) FoodTile(food, onFood, tile) else Spacer(tile)
        if (health != null) HealthTile(health, onHealth, tile) else Spacer(tile)
    }
}

/** A half-width card washed in [accent]: an icon chip and a label on top, a glow in the corner. */
@Composable
private fun Tile(
    accent: Color,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    action: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.large
    Box(modifier.pressScale(source).glass(shape, glass).clip(shape)) {
        Column(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(Brush.verticalGradient(listOf(accent.copy(alpha = 0.14f), Color.Transparent)))
                    val c = Offset(size.width, 0f)
                    val r = size.width * 0.9f
                    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.20f), Color.Transparent), c, r), r, c)
                }
                .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick)
                .padding(16.dp),
        ) {
            Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = accent, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, maxLines = 1, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * The tile's big number. It steps down a size when [sizeFor] (the final text, not the one counting
 * up) is long, so a half-width tile never wraps; [note] sits at the far end on the same baseline.
 */
@Composable
private fun TileNumber(value: String, sizeFor: String, prefix: String? = null, suffix: String? = null, note: String? = null) {
    val cs = MaterialTheme.colorScheme
    val style = when {
        sizeFor.length <= 9 -> MaterialTheme.typography.headlineSmall
        sizeFor.length <= 12 -> MaterialTheme.typography.titleLarge
        else -> MaterialTheme.typography.titleMedium
    }.merge(NumberStyle)
    Row(Modifier.fillMaxWidth()) {
        prefix?.let { Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.alignByBaseline().padding(end = 2.dp)) }
        Text(value, style = style, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.alignByBaseline())
        suffix?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.alignByBaseline().padding(start = 3.dp)) }
        Spacer(Modifier.weight(1f))
        note?.let { Text(it, style = MaterialTheme.typography.labelMedium.merge(NumberStyle), color = cs.onSurfaceVariant, maxLines = 1, modifier = Modifier.alignByBaseline()) }
    }
}

/**
 * Today's food: what was eaten counting up, the deficit against today's resting rate, and the last
 * seven days as small bars under it. The + records straight away.
 */
@Composable
private fun FoodTile(f: FoodState, onFood: (record: Boolean) -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val today = AppClock.today()
    val week = remember(f, today) { (6 downTo 0).map { f.day(today.minusDays(it.toLong())) } }
    val day = week.last()
    Tile(
        cs.secondary, Icons.Outlined.Restaurant, "饮食", { onFood(false) }, modifier,
        action = {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(cs.secondary.copy(alpha = 0.14f)).clickable { onFood(true) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Add, "记一笔饮食", tint = cs.secondary, modifier = Modifier.size(18.dp))
            }
        },
    ) {
        if (f.entries.isEmpty()) {
            Text("还没有记录", style = MaterialTheme.typography.titleMedium)
            Text("估个大概的热量就能记", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            return@Tile
        }
        // The eye on the hero hides these figures too.
        TileNumber(amount(Energy.kcal(animatedCents(day.intakeCal))), sizeFor = Energy.kcal(day.intakeCal), suffix = "kcal")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            val deficit = day.deficitCal
            if (day.entries.isNotEmpty() && deficit != null) {
                Pill(if (deficit >= 0) "缺口 ${amount(Energy.kcal(deficit))}" else "超出 ${amount(Energy.kcal(-deficit))}", cs.secondary)
            }
            Text(
                when {
                    day.entries.isEmpty() -> "今天还没记"
                    deficit == null -> "今天 ${day.entries.size} 笔"
                    else -> "今天"
                },
                style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.heightIn(min = 12.dp).weight(1f))
        FoodWeek(week, cs.secondary, Modifier.fillMaxWidth().height(24.dp))
    }
}

/** Seven small bars ending in today's solid one, today's resting rate a dashed level over them; a day without entries is a dot. */
@Composable
private fun FoodWeek(week: List<Energy.Day>, color: Color, modifier: Modifier) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(900, delayMillis = 200, easing = FastOutSlowInEasing)) }
    val burn = week.last().burn?.restingCal
    Canvas(modifier) {
        val top = maxOf(week.maxOf { it.intakeCal }, burn ?: 0).coerceAtLeast(1)
        val slot = size.width / week.size
        val w = minOf(slot * 0.5f, 8.dp.toPx())
        week.forEachIndexed { i, d ->
            val cx = slot * i + slot / 2
            if (d.entries.isEmpty()) {
                drawCircle(color.copy(alpha = 0.25f), 1.5.dp.toPx(), Offset(cx, size.height - 1.5.dp.toPx()))
            } else {
                val h = (size.height * d.intakeCal / top * reveal.value).coerceAtLeast(2.dp.toPx())
                drawRoundRect(
                    color.copy(alpha = if (i == week.lastIndex) 1f else 0.45f), Offset(cx - w / 2, size.height - h), Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2),
                )
            }
        }
        if (burn != null) {
            val y = size.height - size.height * burn / top
            drawLine(
                color.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
            )
        }
    }
}

/**
 * Health as one more asset: the latest weight counting up, the change since the previous
 * weigh-in, BMI with its grade, and the last 30 records drawn in. The + records straight away.
 */
@Composable
private fun HealthTile(h: HealthState, onHealth: (record: Boolean) -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val latest = h.latest
    Tile(
        cs.tertiary, Icons.Outlined.MonitorWeight, "健康", { onHealth(false) }, modifier,
        action = {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(cs.tertiary.copy(alpha = 0.14f)).clickable { onHealth(true) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Add, "记录体重", tint = cs.tertiary, modifier = Modifier.size(18.dp))
            }
        },
    ) {
        if (latest == null) {
            Text("还没有记录", style = MaterialTheme.typography.titleMedium)
            Text("每天称一次，没称的日子空着就好", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            return@Tile
        }
        // The eye on the hero hides these figures too.
        TileNumber(
            amount(Health.kg(animatedCents(latest.weightG))), sizeFor = Health.kg(latest.weightG), suffix = "kg",
            note = h.previous?.let { amount(Health.kgDelta(latest.weightG - it.weightG)) },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            h.profile?.let { Health.bmi(latest.weightG, it.heightMm) }?.let { bmi ->
                val g = Health.bmiGrade(bmi)
                Pill("BMI ${amount(Health.bmiText(bmi))} ${g.label}", gradeColor(g))
            }
            Text(
                com.assetboard.ui.health.dayLabel(latest.date), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.heightIn(min = 12.dp).weight(1f))
        val recent = h.records.takeLast(30).map { it.weightG }
        if (recent.size >= 2) {
            Sparkline(recent, cs.tertiary, Modifier.fillMaxWidth().height(24.dp), animate = true)
        } else {
            // One weigh-in is not a line yet: a dashed level ending in today's dot.
            val line = cs.tertiary
            Canvas(Modifier.fillMaxWidth().height(24.dp)) {
                val y = size.height / 2
                val r = 3.5.dp.toPx()
                drawLine(
                    line.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width - r * 2, y), strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                )
                drawCircle(line, r, Offset(size.width - r, y))
            }
        }
    }
}

private data class Share(val label: String, val cents: Long, val color: Color)

/** Cash in the theme colour, locked a paler shade of it, investments grey: cash is the point. */
@Composable
private fun liquidityShares(l: Liquidity): List<Share> {
    val cs = MaterialTheme.colorScheme
    return listOf(
        Share("现金", l.cash, cs.primary),
        Share("锁定", l.locked, cs.primary.copy(alpha = 0.45f)),
        Share("投资", l.invested, cs.onSurfaceVariant.copy(alpha = 0.3f)),
    )
}

/** Cash, locked and invested as rounded segments of one bar, growing in from the left. */
@Composable
private fun LiquidityBar(l: Liquidity, height: Dp) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(1_000, delayMillis = 200, easing = FastOutSlowInEasing)) }
    val parts = liquidityShares(l).filter { it.cents > 0 }
    Row(Modifier.fillMaxWidth(reveal.value).height(height), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        parts.forEach { Box(Modifier.weight(it.cents.toFloat()).fillMaxHeight().clip(RoundedCornerShape(50)).background(it.color)) }
    }
}

/** What the cash tile opens: the three-way split with amounts, and every cash account by balance. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CashSheet(s: OverviewState, onOpenAccount: (String) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val extra = AppTheme.extra
    val l = s.liquidity
    val colorOf = remember(s) { s.groups.flatMap { g -> g.rows.map { it.account.id to g.group.colorIndex } }.toMap() }
    GlassSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScrollWithIndicator(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text("可用现金", style = MaterialTheme.typography.titleLarge)
            Text("手里随时能用的钱，不含基金投资和锁定资金", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(16.dp))
            Row {
                Text("¥", style = MaterialTheme.typography.titleMedium, modifier = Modifier.alignByBaseline().padding(end = 3.dp))
                Text(amount(Money.format(animatedCents(l.cash))), style = MaterialTheme.typography.headlineMedium.merge(NumberStyle), maxLines = 1,
                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 28.sp), modifier = Modifier.weight(1f).alignByBaseline())
            }
            if (l.total > 0) {
                Spacer(Modifier.height(14.dp))
                LiquidityBar(l, 10.dp)
                Spacer(Modifier.height(10.dp))
                liquidityShares(l).filter { it.cents > 0 || it.label == "现金" }.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Dot(p.color, 8.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(p.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        MoneyText(p.cents, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            Money.percent(p.cents, l.total), style = MaterialTheme.typography.labelMedium.merge(NumberStyle), color = cs.onSurfaceVariant,
                            textAlign = TextAlign.End, modifier = Modifier.width(62.dp),
                        )
                    }
                }
            }
            val accounts = l.accounts.filter { it.second != 0L }
            if (accounts.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("现金账户 · ${accounts.size}", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                accounts.forEach { (a, v) ->
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onOpenAccount(a.id) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AccountAvatar(a, extra.group(colorOf[a.id] ?: 0), size = 32.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(a.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        MoneyText(v, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    }
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
private fun Welcome(modifier: Modifier, onStart: () -> Unit) {
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
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("开始使用") }
        Spacer(Modifier.height(8.dp))
        Text(
            "会建好现金、储蓄、虚拟、负债、投资 5 个空分组，账户由你自己添加。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
