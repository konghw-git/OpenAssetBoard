package com.assetboard.ui.bill

import com.assetboard.domain.AppClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assetboard.AppContainer
import com.assetboard.data.FlowKind
import com.assetboard.data.MoneyFlow
import com.assetboard.data.newId
import com.assetboard.domain.Bills
import com.assetboard.domain.Dates
import com.assetboard.domain.Money
import com.assetboard.ui.ActionViewModel
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.AccountAvatar
import com.assetboard.ui.components.ConfirmDialog
import com.assetboard.ui.components.DatePickDialog
import com.assetboard.ui.components.ErrorDialog
import com.assetboard.ui.components.GlassSheet
import com.assetboard.ui.components.ListPickerDialog
import com.assetboard.ui.components.SegmentedControl
import com.assetboard.ui.components.UndoEvent
import com.assetboard.ui.components.MASKED
import com.assetboard.ui.components.pressScale
import com.assetboard.ui.theme.AppTheme
import com.assetboard.ui.theme.NumberStyle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Writes 记账 entries. Every save and delete comes back as an [UndoEvent] for the page that
 * opened the sheet; the account last used for each kind is remembered for the next one.
 */
class BillViewModel(private val container: AppContainer) : ActionViewModel(container) {
    private val repo = container.repository
    private val settings = container.settings
    private val _events = MutableSharedFlow<UndoEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UndoEvent> = _events

    fun lastAccount(income: Boolean): String? = settings.string(lastKey(income))

    /** [before] is the bill as it was when edited, null for a new one. */
    fun save(bill: MoneyFlow, before: MoneyFlow?, hide: Boolean, onDone: () -> Unit) = action(onDone) {
        repo.saveFlow(bill)
        settings.putString(lastKey(bill.kind == FlowKind.INCOME), Bills.accountOf(bill))
        val what = describe(bill, hide)
        _events.tryEmit(
            if (before == null) UndoEvent("已记一笔 · $what") { repo.deleteFlow(bill.id) }
            else UndoEvent("已保存 · $what") { repo.saveFlow(before) },
        )
    }

    fun delete(bill: MoneyFlow, hide: Boolean, onDone: () -> Unit = {}) = action(onDone) {
        repo.deleteFlow(bill.id)
        _events.tryEmit(UndoEvent("已删除 · ${describe(bill, hide)}") { repo.saveFlow(bill) })
    }

    fun undo(e: UndoEvent) {
        val u = e.undo ?: return
        action { u() }
    }

    private fun describe(bill: MoneyFlow, hide: Boolean): String {
        val c = Bills.category(bill).label
        return if (hide) c else "$c ${if (bill.kind == FlowKind.INCOME) "+" else "−"}${Money.format(bill.amount)}"
    }

    private fun lastKey(income: Boolean) = if (income) "bill_account_income" else "bill_account_expense"
}

/**
 * 记一笔 as a sheet over the page: 支出 or 收入, the amount on keys of its own (so no keyboard
 * jumps in and out, and a receipt can be added up with + and −), the category in one tap, and the
 * account and the day, both remembered or guessed so most bills are amount, category, 保存.
 * 再记 saves and stays open for the next one. Editing [existing] adds 删除.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BillSheet(
    existing: MoneyFlow?,
    vm: BillViewModel,
    onDismiss: () -> Unit,
    presetAccount: String? = null,
    onAddAccount: (() -> Unit)? = null,
) {
    val container = LocalContainer.current
    val ledger by container.repository.ledger.collectAsStateWithLifecycle()
    val l = ledger ?: return
    val error by vm.error.collectAsStateWithLifecycle()
    val hide = com.assetboard.ui.components.LocalHideAmounts.current
    val cs = MaterialTheme.colorScheme
    val extra = AppTheme.extra
    val haptic = LocalHapticFeedback.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var income by rememberSaveable { mutableStateOf(existing?.kind == FlowKind.INCOME) }
    var expenseCategory by rememberSaveable {
        mutableStateOf(existing?.takeIf { it.kind == FlowKind.EXPENSE }?.let { Bills.category(it).key } ?: Bills.recentCategories(l.flows, false).firstOrNull() ?: Bills.expense.first().key)
    }
    var incomeCategory by rememberSaveable {
        mutableStateOf(existing?.takeIf { it.kind == FlowKind.INCOME }?.let { Bills.category(it).key } ?: Bills.recentCategories(l.flows, true).firstOrNull() ?: Bills.income.first().key)
    }
    var input by rememberSaveable { mutableStateOf(existing?.let { Money.toInput(it.amount).removeSuffix(".00") }.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(existing?.note.orEmpty()) }
    var date by rememberSaveable { mutableStateOf((existing?.at?.let(Dates::localDate) ?: AppClock.today()).toString()) }
    var accountId by rememberSaveable {
        mutableStateOf(existing?.let(Bills::accountOf) ?: presetAccount?.takeIf { id -> Bills.usableAccounts(l).any { it.id == id } } ?: Bills.suggestAccount(l, vm.lastAccount(false)))
    }
    // Switching kind moves to that kind's last account, unless one was picked here by hand.
    var accountPicked by rememberSaveable { mutableStateOf(existing != null || presetAccount != null) }
    var saved by rememberSaveable { mutableIntStateOf(0) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val pop = remember { Animatable(1f) }

    val accounts = remember(l, existing) { Bills.usableAccounts(l, existing?.let(Bills::accountOf)) }
    val account = accounts.firstOrNull { it.id == accountId }
    val category = if (income) incomeCategory else expenseCategory
    val value = Bills.amountOf(input)
    val day = LocalDate.parse(date)
    val now = AppClock.millis()
    // An edited bill keeps its instant while its day stays; moved to another day, its time of day.
    val at = if (existing != null && Dates.localDate(existing.at) == day) existing.at else Bills.at(day, now, existing?.at ?: now)

    ErrorDialog(error) { vm.error.value = null }

    fun bill(): MoneyFlow? {
        val acc = account ?: return null
        val cents = value ?: return null
        return MoneyFlow(
            id = existing?.id ?: newId(),
            kind = if (income) FlowKind.INCOME else FlowKind.EXPENSE,
            fromAccountId = if (income) null else acc.id,
            toAccountId = if (income) acc.id else null,
            amount = cents,
            at = at,
            note = note.trim().ifEmpty { null },
            createdAt = existing?.createdAt ?: AppClock.millis(),
            category = category,
        )
    }

    fun commit(again: Boolean) {
        val b = bill()
        if (b == null) {
            haptic.performHapticFeedback(HapticFeedbackType.Reject)
            scope.launch {
                for (x in listOf(14f, -12f, 8f, -5f, 0f)) shake.animateTo(x, tween(45))
            }
            return
        }
        focus.clearFocus()
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        vm.save(b, existing, hide) {
            if (again) {
                saved++
                input = ""
                note = ""
            } else {
                onDismiss()
            }
        }
    }

    GlassSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            // 支出 | 收入, and the sheet's count of bills made in a row, or 删除 when editing.
            Row(verticalAlignment = Alignment.CenterVertically) {
                SegmentedControl(
                    listOf(false, true), income, { if (it) "收入" else "支出" },
                    onSelect = { k ->
                        income = k
                        if (!accountPicked) accountId = Bills.suggestAccount(l, vm.lastAccount(k))
                    },
                    modifier = Modifier.width(160.dp).testTag(KIND_TAG),
                )
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(saved > 0, enter = scaleIn(spring(dampingRatio = 0.5f)) + fadeIn(), exit = fadeOut()) {
                    AnimatedContent(saved, transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) }, label = "saved") { n ->
                        Text(
                            "已记 $n 笔",
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.primary,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(cs.primary.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                if (existing != null) {
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "删除这笔", tint = cs.error) }
                }
            }

            if (accounts.isEmpty()) {
                NoAccount(onAddAccount = onAddAccount?.let { add -> { onDismiss(); add() } })
                return@Column
            }

            // The note on the left and the amount on the right, the way a receipt reads.
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp).graphicsLayer { translationX = shake.value },
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(Modifier.weight(1f).padding(bottom = 6.dp)) {
                    if (note.isEmpty()) Text("添加备注…", style = MaterialTheme.typography.bodyMedium, color = cs.outline)
                    BasicTextField(
                        note, { note = it.replace("\n", "").take(60) }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
                        cursorBrush = SolidColor(cs.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "备注" },
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    val tint by animateColorAsState(if (income) extra.signed(1) else cs.onSurface, tween(220), label = "amountTint")
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f) }) {
                        Text(if (income) "+¥" else "−¥", style = MaterialTheme.typography.titleMedium.merge(NumberStyle), color = tint.copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 4.dp, end = 2.dp))
                        Text(
                            input.ifEmpty { "0" },
                            style = MaterialTheme.typography.displaySmall.merge(NumberStyle),
                            fontWeight = FontWeight.SemiBold,
                            color = if (input.isEmpty()) cs.outline else tint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { contentDescription = "金额" },
                        )
                    }
                    AnimatedVisibility(Money.isExpression(input.trimEnd('+', '−')), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Text("= " + (value?.let(Money::format) ?: "…"), style = MaterialTheme.typography.labelLarge.merge(NumberStyle), color = cs.primary)
                    }
                }
            }

            CategoryGrid(income, category, onPick = { key ->
                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                if (income) incomeCategory = key else expenseCategory = key
            })

            // The account and the day, each a tap away.
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(onClick = { picking = true }, modifier = Modifier.weight(1f, fill = false)) {
                    if (account != null) AccountAvatar(account, extra.group(l.groupById[account.groupId]?.colorIndex ?: 0), size = 22.dp)
                    else Icon(Icons.Outlined.AccountBalanceWallet, null, Modifier.size(18.dp), tint = cs.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(account?.name ?: "选择账户", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Chip(onClick = { pickingDate = true }) {
                    Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(16.dp), tint = cs.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(dayLabel(day), style = MaterialTheme.typography.labelLarge)
                }
            }
            // A bill dated before the account's latest balance record is kept, but that record already holds the money.
            val absorbed = account?.let { a -> l.valuationsOf(a.id).lastOrNull()?.takeIf { it.at >= at && it.at != a.baselineAt } }
            val beforeStart = account != null && at <= account.baselineAt
            AnimatedVisibility(absorbed != null || beforeStart, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, null, Modifier.size(14.dp), tint = cs.tertiary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            beforeStart -> "早于「${account?.name}」的期初，记不进这个账户"
                            else -> "早于 ${absorbed?.let { Dates.date(it.at) }} 的余额记录，记下来但不改当前余额"
                        },
                        style = MaterialTheme.typography.bodySmall, color = cs.tertiary,
                    )
                }
            }

            Keypad(
                again = existing == null,
                ready = value != null,
                onKey = { k ->
                    haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    val next = Bills.press(input, k)
                    if (next != input) {
                        input = next
                        scope.launch { pop.snapTo(1.05f); pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)) }
                    }
                },
                onClear = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); input = "" },
                onAgain = { commit(again = true) },
                onSave = { commit(again = false) },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }

    if (picking) {
        ListPickerDialog(
            title = if (income) "收进哪个账户" else "从哪个账户付",
            items = accounts,
            label = { it.name },
            subtitle = { a ->
                val balance = if (hide) MASKED else Money.format(l.toDisplay(a.id, l.currentBalance(a.id)))
                listOfNotNull(l.groupById[a.groupId]?.name, balance).joinToString(" · ")
            },
            leading = { a -> AccountAvatar(a, extra.group(l.groupById[a.groupId]?.colorIndex ?: 0), size = 32.dp) },
            onPick = { a -> accountId = a.id; accountPicked = true; picking = false },
            onDismiss = { picking = false },
        )
    }
    if (pickingDate) {
        DatePickDialog(initial = day, onDismiss = { pickingDate = false }, onPick = { d -> date = d.toString(); pickingDate = false })
    }
    if (confirmDelete && existing != null) {
        ConfirmDialog(
            title = "删除这笔${if (existing.kind == FlowKind.INCOME) "收入" else "支出"}？",
            text = "账户余额会一起重新推算，删除后可以撤销。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = { confirmDelete = false; vm.delete(existing, hide, onDismiss) },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** The 支出 | 收入 switch, for tests. */
internal const val KIND_TAG = "bill-kind"

private fun dayLabel(day: LocalDate): String {
    val today = AppClock.today()
    return when (day) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        today.minusDays(2) -> "前天"
        else -> if (day.year == today.year) "${day.monthValue}月${day.dayOfMonth}日" else Dates.date(Dates.endOfDay(day))
    }
}

@Composable
private fun Chip(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.clip(RoundedCornerShape(50)).background(cs.onSurface.copy(alpha = 0.06f)).clickable(onClick = onClick).padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** Two rows of five: the picked one fills with its colour and springs up a little. */
@Composable
private fun CategoryGrid(income: Boolean, picked: String, onPick: (String) -> Unit) {
    AnimatedContent(income, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "categories") { inc ->
        Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Bills.categories(inc).chunked(5).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { c ->
                        val on = c.key == picked
                        val color = c.color()
                        val scale by animateFloatAsState(if (on) 1.1f else 1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium), label = "pick")
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { onPick(c.key) }.padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier
                                    .size(42.dp)
                                    .graphicsLayer { scaleX = scale; scaleY = scale }
                                    .clip(CircleShape)
                                    .background(if (on) Brush.linearGradient(listOf(color, color.copy(alpha = 0.72f))) else SolidColor(color.copy(alpha = 0.13f))),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(c.icon(), null, tint = if (on) Color.White else color, modifier = Modifier.size(21.dp))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                c.label, style = MaterialTheme.typography.labelMedium,
                                color = if (on) color else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (on) FontWeight.SemiBold else null,
                            )
                        }
                    }
                    repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * The sheet's own keys. ⌫ held clears; the bottom row is 再记 (save and go on with the next) or,
 * when editing, 清空, then 保存, which lights up once the amount is something to save.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Keypad(again: Boolean, ready: Boolean, onKey: (String) -> Unit, onClear: () -> Unit, onAgain: () -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val extra = AppTheme.extra
    val rows = listOf(listOf("1", "2", "3", "⌫"), listOf("4", "5", "6", "+"), listOf("7", "8", "9", "−"))
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    Key(
                        Modifier.weight(1f),
                        onClick = { onKey(k) },
                        onLongClick = if (k == "⌫") onClear else null,
                        tint = if (k.first().isDigit()) null else cs.primary.copy(alpha = 0.10f),
                        description = if (k == "⌫") "退格" else k,
                    ) {
                        if (k == "⌫") Icon(Icons.AutoMirrored.Outlined.Backspace, null, tint = cs.primary, modifier = Modifier.size(22.dp))
                        else Text(k, style = MaterialTheme.typography.titleLarge.merge(NumberStyle), color = if (k.first().isDigit()) cs.onSurface else cs.primary)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key(Modifier.weight(1f), onClick = if (again) onAgain else onClear, tint = cs.primary.copy(alpha = 0.10f), description = if (again) "再记" else "清空") {
                Text(if (again) "再记" else "清空", style = MaterialTheme.typography.titleSmall, color = cs.primary)
            }
            Key(Modifier.weight(1f), onClick = { onKey("0") }, description = "0") {
                Text("0", style = MaterialTheme.typography.titleLarge.merge(NumberStyle), color = cs.onSurface)
            }
            Key(Modifier.weight(1f), onClick = { onKey(".") }, description = ".") {
                Text(".", style = MaterialTheme.typography.titleLarge.merge(NumberStyle), color = cs.onSurface)
            }
            val lit by animateFloatAsState(if (ready) 1f else 0.45f, tween(200), label = "ready")
            Box(
                Modifier
                    .weight(1f)
                    .height(52.dp)
                    .graphicsLayer { alpha = lit }
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(extra.heroStart, extra.heroEnd)))
                    .clickable(onClick = onSave)
                    .semantics { contentDescription = "保存" },
                contentAlignment = Alignment.Center,
            ) {
                Text("保存", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = extra.onHero)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Key(
    modifier: Modifier,
    onClick: () -> Unit,
    description: String,
    onLongClick: (() -> Unit)? = null,
    tint: Color? = null,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .height(52.dp)
            .pressScale(source, pressed = 0.92f)
            .clip(RoundedCornerShape(16.dp))
            .background(tint ?: cs.onSurface.copy(alpha = 0.055f))
            .combinedClickable(interactionSource = source, indication = androidx.compose.material3.ripple(), onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Nothing to spend from yet: 记账 goes through an account. */
@Composable
private fun NoAccount(onAddAccount: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.AccountBalanceWallet, null, Modifier.size(40.dp), tint = cs.primary)
        Spacer(Modifier.height(10.dp))
        Text("先建一个账户", style = MaterialTheme.typography.titleMedium)
        Text(
            "每笔支出和收入都记在一个账户上，账户余额会跟着变。",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
        )
        if (onAddAccount != null) TextButton(onClick = onAddAccount, modifier = Modifier.padding(top = 8.dp)) { Text("新建账户") }
    }
}
