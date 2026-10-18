package com.assetboard.ui.stocktake

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assetboard.AppContainer
import com.assetboard.data.Account
import com.assetboard.data.AccountGroup
import com.assetboard.data.StocktakeEntry
import com.assetboard.domain.Dates
import com.assetboard.domain.LedgerData
import com.assetboard.domain.Money
import com.assetboard.ui.ActionViewModel
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.AccountAvatar
import com.assetboard.ui.components.ConfirmDialog
import com.assetboard.ui.components.DatePickDialog
import com.assetboard.ui.components.Dot
import com.assetboard.ui.components.ErrorDialog
import com.assetboard.ui.components.MoneyText
import com.assetboard.ui.components.Pill
import com.assetboard.ui.components.SectionCard
import com.assetboard.ui.theme.AppTheme
import com.assetboard.ui.theme.NumberStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class StockRow(
    val account: Account,
    val group: AccountGroup?,
    /** Value the form started from, debts positive. */
    val original: Long,
    val input: String,
    val included: Boolean,
) {
    val parsed: Long? get() = Money.parse(input)
    val changed: Boolean get() = parsed != null && parsed != original
    val isLiability: Boolean get() = group?.isLiability == true
    fun stored(display: Long) = if (isLiability) -display else display
}

data class StocktakeUi(
    val loaded: Boolean = false,
    val editing: Boolean = false,
    val at: Long = System.currentTimeMillis(),
    val rows: List<StockRow> = emptyList(),
) {
    val changedCount: Int get() = rows.count { it.changed }
    val valid: Boolean get() = rows.isNotEmpty() && rows.all { it.parsed != null }
    val netBefore: Long get() = rows.filter { it.included }.sumOf { it.stored(it.original) }
    val netAfter: Long get() = rows.filter { it.included }.sumOf { it.stored(it.parsed ?: it.original) }
}

data class DeleteImpact(val name: String, val before: Long, val after: Long)

class StocktakeViewModel(private val container: AppContainer, private val snapshotId: String?) : ActionViewModel(container) {
    private val repo = container.repository
    private val _ui = MutableStateFlow(StocktakeUi(editing = snapshotId != null))
    val ui = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val l = repo.ledger.filterNotNull().first()
            _ui.value = if (snapshotId != null) loadExisting(l, snapshotId) else loadNew(l, System.currentTimeMillis(), emptyMap())
        }
    }

    private fun sortKey(l: LedgerData, a: Account) = (l.groupById[a.groupId]?.sortOrder ?: 0) * 100_000 + a.sortOrder

    private fun loadNew(l: LedgerData, at: Long, keep: Map<String, String>): StocktakeUi {
        val rows = l.stocktakeAccounts(at).sortedBy { sortKey(l, it) }.map { a ->
            val display = l.toDisplay(a.id, l.balanceAt(a.id, at) ?: 0L)
            StockRow(a, l.groupById[a.groupId], display, keep[a.id] ?: Money.toInput(display), a.includeInTotal)
        }
        return StocktakeUi(loaded = true, editing = false, at = at, rows = rows)
    }

    private fun loadExisting(l: LedgerData, id: String): StocktakeUi {
        val snap = l.snapshots.firstOrNull { it.id == id } ?: return StocktakeUi(loaded = true, editing = true)
        val rows = l.itemsOf(id).mapNotNull { item ->
            val a = l.accountById[item.accountId] ?: return@mapNotNull null
            val v = l.valuationById[item.valuationId] ?: return@mapNotNull null
            val display = l.toDisplay(a.id, v.value)
            StockRow(a, l.groupById[a.groupId], display, Money.toInput(display), item.included)
        }.sortedBy { sortKey(l, it.account) }
        return StocktakeUi(loaded = true, editing = true, at = snap.at, rows = rows)
    }

    fun onInput(accountId: String, text: String) {
        val clean = text.filter { it.isDigit() || it == '.' || it == '-' }
        _ui.update { s -> s.copy(rows = s.rows.map { if (it.account.id == accountId) it.copy(input = clean) else it }) }
    }

    /** A new stocktake re-reads balances at the chosen moment; rows the user already edited keep their input. */
    fun setDate(at: Long) {
        val s = _ui.value
        if (s.editing) {
            _ui.value = s.copy(at = at)
            return
        }
        val l = repo.ledger.value ?: return
        val keep = s.rows.filter { it.changed }.associate { it.account.id to it.input }
        _ui.value = loadNew(l, at, keep)
    }

    fun save(onDone: () -> Unit) {
        val s = _ui.value
        val entries = s.rows.map { StocktakeEntry(it.account.id, it.parsed ?: it.original) }
        action(onDone) {
            if (snapshotId == null) repo.createSnapshot(s.at, entries) else repo.updateSnapshot(snapshotId, s.at, entries)
        }
    }

    fun deleteImpact(): List<DeleteImpact> {
        val id = snapshotId ?: return emptyList()
        val l = repo.ledger.value ?: return emptyList()
        val after = l.withoutSnapshot(id)
        return l.itemsOf(id).mapNotNull { item ->
            val a = l.accountById[item.accountId] ?: return@mapNotNull null
            val b = l.toDisplay(a.id, l.currentBalance(a.id))
            val c = l.toDisplay(a.id, after.currentBalance(a.id))
            if (b != c) DeleteImpact(a.name, b, c) else null
        }
    }

    fun delete(onDone: () -> Unit) {
        val id = snapshotId ?: return
        action(onDone) { repo.deleteSnapshot(id) }
    }
}

@Composable
fun StocktakeScreen(snapshotId: String?, onBack: () -> Unit) {
    val container = LocalContainer.current
    val vm: StocktakeViewModel = viewModel(key = "stocktake-$snapshotId") { StocktakeViewModel(container, snapshotId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    ErrorDialog(error) { vm.error.value = null }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (ui.editing) "编辑盘点" else "盘点") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    if (ui.editing) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "删除这次盘点") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (ui.loaded && ui.rows.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Button(
                        onClick = { vm.save(onBack) },
                        enabled = ui.valid,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 10.dp).height(52.dp),
                    ) {
                        Text(
                            when {
                                !ui.valid -> "有金额格式不对"
                                ui.changedCount == 0 -> "确认无变化，保存"
                                else -> "保存盘点 · 改了 ${ui.changedCount} 项"
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (!ui.loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        if (ui.rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text("这个时刻没有可盘点的账户。账户只能在期初之后盘点。", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SummaryCard(ui, onPickDate = { pickDate = true })
            ui.rows.groupBy { it.group?.id }.forEach { (_, rows) ->
                GroupBlock(rows, onInput = vm::onInput)
            }
        }
    }

    if (pickDate) {
        DatePickDialog(
            initial = Dates.localDate(ui.at),
            onDismiss = { pickDate = false },
            onPick = { d ->
                pickDate = false
                vm.setDate(Dates.pickedAt(d))
            },
            maxDate = LocalDate.now(),
        )
    }
    if (confirmDelete) {
        val impact = vm.deleteImpact()
        val text = buildString {
            append("净值曲线会少一个点，这次盘点写入的记录一并删除。")
            if (impact.isEmpty()) append("\n\n各账户当前余额不受影响。")
            else {
                append("\n\n以下账户的当前余额会变化：")
                impact.forEach { append("\n· ${it.name}：${Money.format(it.before)} → ${Money.format(it.after)}") }
            }
        }
        ConfirmDialog(
            title = "删除这次盘点？",
            text = text,
            confirmLabel = "删除",
            destructive = true,
            onConfirm = { confirmDelete = false; vm.delete(onBack) },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun SummaryCard(ui: StocktakeUi, onPickDate: () -> Unit) {
    val extra = AppTheme.extra
    val diff = ui.netAfter - ui.netBefore
    SectionCard(contentPadding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("盘点时间", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            AssistChip(
                onClick = onPickDate,
                label = { Text(if (Dates.daysBetween(ui.at, System.currentTimeMillis()) == 0L) "今天 ${Dates.time(ui.at)}" else Dates.date(ui.at)) },
                leadingIcon = { Icon(Icons.Default.CalendarMonth, null, Modifier.padding(start = 2.dp)) },
            )
        }
        Spacer(Modifier.height(10.dp))
        Text("净资产", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyText(ui.netAfter, style = MaterialTheme.typography.headlineMedium)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text(
                if (ui.editing) "原 " + Money.format(ui.netBefore) else "盘点前 " + Money.format(ui.netBefore),
                style = MaterialTheme.typography.bodySmall.merge(NumberStyle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (diff != 0L) {
                Spacer(Modifier.width(8.dp))
                Pill(Money.format(diff, signed = true), extra.signed(diff))
            }
        }
        if (!ui.editing && Dates.daysBetween(ui.at, System.currentTimeMillis()) > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "正在补录历史盘点：预填的是当天的余额。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun GroupBlock(rows: List<StockRow>, onInput: (String, String) -> Unit) {
    val extra = AppTheme.extra
    val group = rows.first().group
    SectionCard {
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(extra.group(group?.colorIndex ?: 0), 8.dp)
            Spacer(Modifier.width(8.dp))
            Text(group?.name ?: "", style = MaterialTheme.typography.titleSmall)
            if (group?.isLiability == true) {
                Spacer(Modifier.width(6.dp))
                Text("填欠款金额", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(Modifier.padding(start = 74.dp), color = MaterialTheme.colorScheme.outlineVariant)
            StockRowItem(r, onInput)
        }
    }
}

@Composable
private fun StockRowItem(r: StockRow, onInput: (String, String) -> Unit) {
    val extra = AppTheme.extra
    val focus = LocalFocusManager.current
    val cs = MaterialTheme.colorScheme
    val invalid = r.parsed == null
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (r.changed) cs.primaryContainer.copy(alpha = 0.35f) else cs.surfaceContainer)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountAvatar(r.account.name, extra.group(r.group?.colorIndex ?: 0), muted = !r.included)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.account.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = if (r.changed) "原 " + Money.format(r.original) else r.account.purpose.orEmpty()
            if (sub.isNotEmpty() || !r.included) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall.merge(NumberStyle),
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!r.included) Pill("不计入", cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            BasicTextField(
                value = r.input,
                onValueChange = { onInput(r.account.id, it) },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.merge(NumberStyle).copy(color = cs.onSurface, textAlign = TextAlign.End),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                modifier = Modifier
                    .widthIn(min = 120.dp, max = 160.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(cs.surfaceContainerHigh)
                    .border(1.dp, if (invalid) cs.error else if (r.changed) cs.primary else cs.surfaceContainerHigh, MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            )
            if (r.changed) {
                val d = r.stored(r.parsed!!) - r.stored(r.original)
                Text(
                    Money.format(d, signed = true),
                    style = MaterialTheme.typography.labelMedium.merge(NumberStyle),
                    color = extra.signed(d),
                    modifier = Modifier.padding(top = 3.dp, end = 4.dp),
                )
            }
        }
    }
}
