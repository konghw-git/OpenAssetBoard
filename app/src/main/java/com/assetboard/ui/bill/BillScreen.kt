package com.assetboard.ui.bill

import com.assetboard.domain.AppClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assetboard.data.FlowKind
import com.assetboard.data.MoneyFlow
import com.assetboard.domain.Bills
import com.assetboard.domain.Dates
import com.assetboard.domain.LedgerData
import com.assetboard.domain.Money
import com.assetboard.domain.todo.Recurrence
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.GlassScaffold
import com.assetboard.ui.components.GlassSnackbarHost
import com.assetboard.ui.components.LocalHideAmounts
import com.assetboard.ui.components.PullToCreateBox
import com.assetboard.ui.components.SectionCard
import com.assetboard.ui.components.SegmentedControl
import com.assetboard.ui.components.SwipeActionsBox
import com.assetboard.ui.components.UndoMessages
import com.assetboard.ui.components.amount
import com.assetboard.ui.components.animatedCents
import com.assetboard.ui.components.appear
import com.assetboard.ui.components.glass
import com.assetboard.ui.components.glassBarColors
import com.assetboard.ui.components.heroSurface
import com.assetboard.ui.components.pressScale
import com.assetboard.ui.components.rememberPullToCreate
import com.assetboard.ui.components.rememberSwipeGroup
import com.assetboard.ui.components.scrollIndicator
import com.assetboard.ui.theme.AppTheme
import com.assetboard.ui.theme.NumberStyle
import java.time.LocalDate
import java.time.YearMonth

/** The page's own [BillViewModel], so its undo messages show on this page. */
@Composable
internal fun billViewModel(key: String): BillViewModel {
    val container = LocalContainer.current
    return viewModel(key = "bill-$key") { BillViewModel(container) }
}

/** −35.00 or +8,000.00, masked while amounts are hidden. */
@Composable
internal fun billAmount(f: MoneyFlow): String = amount((if (f.kind == FlowKind.INCOME) "+" else "−") + Money.format(f.amount))

/**
 * 账单: a month of 记账 at a time. The month's spending, earning and what is left; where the money
 * went by category (a tap shows only that category below); then each day with its bills. A tap
 * edits a bill, a left swipe deletes it with an undo, and pulling the list down records a new one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillScreen(onBack: () -> Unit, onAddAccount: () -> Unit) {
    val container = LocalContainer.current
    val vm = billViewModel("page")
    val ledger by container.repository.ledger.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val hide = LocalHideAmounts.current
    UndoMessages(vm.events, snackbar, vm::undo)
    val now = java.time.YearMonth.now(AppClock.clock)
    var monthText by rememberSaveable { mutableStateOf(now.toString()) }
    val month = YearMonth.parse(monthText)
    var incomeShares by rememberSaveable { mutableStateOf(false) }
    var only by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val swipe = rememberSwipeGroup()
    val listState = rememberLazyListState()
    val pull = rememberPullToCreate(enabled = !creating && editing == null) { swipe.closeAll(); creating = true }
    val pullTop = with(LocalDensity.current) { pull.pulled.toDp() }

    GlassScaffold(
        topBar = {
            TopAppBar(
                title = { Text("账单") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { IconButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "记一笔") } },
                colors = glassBarColors(),
            )
        },
        snackbarHost = { GlassSnackbarHost(snackbar) },
    ) { padding ->
        val l = ledger
        if (l == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@GlassScaffold
        }
        val m = remember(l, month) { Bills.month(l.flows, month) }
        val shownCategory = only?.let { key -> (m.expenseShares + m.incomeShares).firstOrNull { it.category.key == key && it.category.income == incomeShares }?.category }
        PullToCreateBox(pull, "下拉记一笔", "松手记一笔", Modifier.fillMaxSize().padding(padding), icon = Icons.AutoMirrored.Outlined.ReceiptLong) {
            LazyColumn(
                Modifier.fillMaxSize().scrollIndicator(listState),
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pullTop, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item("month") {
                    MonthBar(month, canNext = month < now, onMove = { monthText = month.plusMonths(it.toLong()).toString(); only = null })
                }
                item("summary") { Box(Modifier.appear(0).padding(bottom = 8.dp)) { MonthHero(m) } }
                if (!m.isEmpty) {
                    item("shares") {
                        Box(Modifier.appear(1).padding(bottom = 8.dp)) {
                            SharesCard(m, incomeShares, only, onKind = { incomeShares = it; only = null }, onOnly = { only = if (only == it) null else it })
                        }
                    }
                }
                if (shownCategory != null) {
                    item("only") {
                        Row(
                            Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(50)).background(shownCategory.color().copy(alpha = 0.14f)).clickable { only = null }
                                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("只看「${shownCategory.label}」", style = MaterialTheme.typography.labelLarge, color = shownCategory.color())
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.Close, "不再只看这一类", Modifier.size(16.dp), tint = shownCategory.color())
                        }
                    }
                }
                val days = m.days.mapNotNull { d ->
                    val bills = d.bills.filter { shownCategory == null || (Bills.category(it) == shownCategory) }
                    if (bills.isEmpty()) null else d.copy(bills = bills)
                }
                if (days.isEmpty()) {
                    item("empty") { EmptyMonth(month == now, onRecord = { creating = true }) }
                }
                days.forEach { d ->
                    item("day-${d.date}") { DayHeader(d) }
                    items(d.bills, key = { it.id }) { f ->
                        Box(Modifier.animateItem()) {
                            SwipeActionsBox(
                                key = "bill:${f.id}",
                                group = swipe,
                                shape = BillRowShape,
                                onDelete = { vm.delete(f, hide = hide) },
                            ) {
                                BillRow(f, l, onClick = { swipe.closeAll(); editing = f.id })
                            }
                        }
                    }
                }
            }
        }
        if (creating) BillSheet(null, vm, onDismiss = { creating = false }, onAddAccount = onAddAccount)
        editing?.let { id ->
            val f = l.flows.firstOrNull { it.id == id }
            if (f == null) editing = null else BillSheet(f, vm, onDismiss = { editing = null })
        }
    }
}

internal val BillRowShape = RoundedCornerShape(16.dp)

@Composable
private fun MonthBar(month: YearMonth, canNext: Boolean, onMove: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onMove(-1) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "上个月") }
        AnimatedContent(
            month,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally { if (forward) it / 2 else -it / 2 } + fadeIn()) togetherWith (slideOutHorizontally { if (forward) -it / 2 else it / 2 } + fadeOut())
            },
            label = "month",
            modifier = Modifier.weight(1f),
        ) { m ->
            Text("${m.year}年${m.monthValue}月", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        IconButton(onClick = { onMove(1) }, enabled = canNext) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "下个月") }
    }
}

/** The month's figure on the hero surface: what went out, and beside it what came in and what is left. */
@Composable
private fun MonthHero(m: Bills.Month) {
    val extra = AppTheme.extra
    val shape = MaterialTheme.shapes.extraLarge
    val spent = animatedCents(m.expense, countUp = true)
    Column(Modifier.fillMaxWidth().heroSurface(shape, extra).padding(20.dp)) {
        Text("${m.month.monthValue}月支出", style = MaterialTheme.typography.labelLarge, color = extra.onHeroMuted)
        Text(amount("¥" + Money.format(spent)), style = MaterialTheme.typography.displaySmall.merge(NumberStyle), fontWeight = FontWeight.SemiBold, color = extra.onHero, maxLines = 1)
        Spacer(Modifier.height(14.dp))
        Row {
            HeroFigure("收入", m.income, Modifier.weight(1f))
            HeroFigure("结余", m.balance, Modifier.weight(1f), signed = true)
            val days = if (m.month == java.time.YearMonth.now(AppClock.clock)) AppClock.today().dayOfMonth else m.month.lengthOfMonth()
            HeroFigure("日均支出", m.expense / days, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeroFigure(label: String, cents: Long, modifier: Modifier, signed: Boolean = false) {
    val extra = AppTheme.extra
    val shown = animatedCents(cents)
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = extra.onHeroMuted)
        Text(amount(Money.format(shown, signed = signed && cents != 0L)), style = MaterialTheme.typography.titleMedium.merge(NumberStyle), color = extra.onHero, maxLines = 1)
    }
}

/** Where the money went (or came from): a bar per category, widest first; a tap shows only that one. */
@Composable
private fun SharesCard(m: Bills.Month, income: Boolean, only: String?, onKind: (Boolean) -> Unit, onOnly: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shares = if (income) m.incomeShares else m.expenseShares
    val total = if (income) m.income else m.expense
    SectionCard(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("分类", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            SegmentedControl(listOf(false, true), income, { if (it) "收入" else "支出" }, onKind, Modifier.width(132.dp), height = 32.dp, textStyle = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(8.dp))
        if (shares.isEmpty()) {
            Text(if (income) "这个月还没有收入" else "这个月还没有支出", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        }
        shares.forEachIndexed { i, s ->
            val color = s.category.color()
            val share = if (total > 0) s.total.toFloat() / total else 0f
            // Each bar grows out from nothing when it first shows, then follows its share.
            val grown = remember(s.category.key, income) { Animatable(0f) }
            // Keyed on the bar as well: switching 支出 / 收入 makes a new one, and an effect keyed
            // only on a share that happens to match the old one would never grow it.
            LaunchedEffect(share, grown) { grown.animateTo(share, spring(dampingRatio = 0.8f, stiffness = 120f)) }
            val dim = only != null && only != s.category.key
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOnly(s.category.key) }.padding(vertical = 6.dp).appear(i, enabled = i < 8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.graphicsLayer { alpha = if (dim) 0.4f else 1f }) { CategoryDot(s.category, 30.dp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).graphicsLayer { alpha = if (dim) 0.4f else 1f }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.category.label, style = MaterialTheme.typography.bodyMedium)
                        Text("  ${s.count} 笔 · ${Money.percent(s.total, total)}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(amount(Money.format(s.total)), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(5.dp))
                    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                        val r = CornerRadius(size.height / 2)
                        drawRoundRect(color.copy(alpha = 0.14f), cornerRadius = r)
                        drawRoundRect(color, size = Size(size.width * grown.value.coerceIn(0.015f, 1f), size.height), cornerRadius = r)
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(d: Bills.Day) {
    val cs = MaterialTheme.colorScheme
    val today = AppClock.today()
    val name = when (d.date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> "${d.date.monthValue}月${d.date.dayOfMonth}日"
    }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.Bottom) {
        Text(name, style = MaterialTheme.typography.titleSmall)
        Text("  " + Recurrence.weekName(d.date.dayOfWeek), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
        val parts = listOfNotNull(
            d.expense.takeIf { it > 0 }?.let { "支 " + amount(Money.format(it)) },
            d.income.takeIf { it > 0 }?.let { "收 " + amount(Money.format(it)) },
        )
        Text(parts.joinToString("  "), style = MaterialTheme.typography.labelMedium.merge(NumberStyle), color = cs.onSurfaceVariant)
    }
}

/** One bill: its category, note (or the category again), account and time, and the amount. */
@Composable
internal fun BillRow(f: MoneyFlow, l: LedgerData, onClick: () -> Unit, modifier: Modifier = Modifier, showDate: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val extra = AppTheme.extra
    val c = Bills.category(f)
    val source = remember { MutableInteractionSource() }
    val account = Bills.accountOf(f)?.let { l.accountById[it]?.name }
    Row(
        modifier
            .fillMaxWidth()
            .pressScale(source)
            .glass(BillRowShape, com.assetboard.ui.components.glass, elevation = 4.dp)
            .clip(BillRowShape)
            .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryDot(c, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.note?.takeIf { it.isNotBlank() } ?: c.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val time = if (showDate) Dates.short(f.at) + " " + Dates.time(f.at) else Dates.time(f.at)
            Text(
                listOfNotNull(if (f.note.isNullOrBlank()) null else c.label, account, time).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            billAmount(f),
            style = MaterialTheme.typography.titleMedium.merge(NumberStyle),
            fontWeight = FontWeight.SemiBold,
            color = if (f.kind == FlowKind.INCOME) extra.signed(1) else cs.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun EmptyMonth(current: Boolean, onRecord: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Outlined.ReceiptLong, null, tint = cs.primary, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(if (current) "这个月还没有记账" else "这个月没有记账", style = MaterialTheme.typography.titleSmall)
        Text("下拉列表，或点右上角 + 记一笔", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        if (current) TextButton(onClick = onRecord, modifier = Modifier.padding(top = 6.dp)) { Text("记一笔") }
    }
}

/**
 * 记账 on the assets tab: this month's spending counting up, what came in and today's, the last
 * seven days as small bars, and the latest bills. A tap opens 账单; 记一笔 opens the sheet.
 */
@Composable
fun BillCard(l: LedgerData, onOpen: () -> Unit, onRecord: () -> Unit, onEdit: (MoneyFlow) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val today = AppClock.today()
    val m = remember(l, today) { Bills.month(l.flows, YearMonth.from(today)) }
    val week = remember(l, today) { Bills.dailySpending(l.flows, today) }
    val recent = remember(l) { Bills.bills(l.flows).sortedWith(compareByDescending<MoneyFlow> { it.at }.thenByDescending { it.createdAt }).take(3) }
    val accent = cs.tertiary
    SectionCard(modifier, contentPadding = PaddingValues(16.dp), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Outlined.ReceiptLong, null, tint = accent, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text("记账", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(cs.primary.copy(alpha = 0.10f)).clickable(onClick = onRecord).padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Add, null, tint = cs.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(2.dp))
                Text("记一笔", style = MaterialTheme.typography.labelLarge, color = cs.primary)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("本月支出", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                val spent = animatedCents(m.expense, countUp = true)
                Text(amount(Money.format(spent)), style = MaterialTheme.typography.headlineSmall.merge(NumberStyle), fontWeight = FontWeight.Medium, maxLines = 1)
                Text(
                    "收入 ${amount(Money.format(m.income))} · 今天 ${amount(Money.format(week.last()))}",
                    style = MaterialTheme.typography.labelMedium.merge(NumberStyle), color = cs.onSurfaceVariant, maxLines = 1,
                )
            }
            WeekBars(week, accent, Modifier.width(112.dp).height(44.dp))
        }
        AnimatedVisibility(recent.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(top = 10.dp)) {
                recent.forEach { f ->
                    val c = Bills.category(f)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onEdit(f) }.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CategoryDot(c, 26.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(f.note?.takeIf { it.isNotBlank() } ?: c.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(Dates.short(f.at), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            billAmount(f), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), fontWeight = FontWeight.Medium,
                            color = if (f.kind == FlowKind.INCOME) AppTheme.extra.signed(1) else cs.onSurface,
                        )
                    }
                }
            }
        }
        if (recent.isEmpty()) {
            Text("下拉页面就能记一笔", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Seven days of spending as bars growing from the bottom, today's in full colour. */
@Composable
private fun WeekBars(days: List<Long>, color: Color, modifier: Modifier) {
    val top = (days.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(600)) }
    val track = color.copy(alpha = 0.10f)
    Canvas(modifier) {
        val n = days.size
        val gap = 5.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        days.forEachIndexed { i, v ->
            val x = i * (w + gap)
            val h = (size.height * v / top * grow.value).coerceAtLeast(if (v > 0) 3.dp.toPx() else 0f)
            drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2))
            if (h > 0f) drawRoundRect(if (i == n - 1) color else color.copy(alpha = 0.45f), Offset(x, size.height - h), Size(w, h), CornerRadius(w / 2))
        }
    }
}
