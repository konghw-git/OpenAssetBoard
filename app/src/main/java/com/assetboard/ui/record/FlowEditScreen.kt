package com.assetboard.ui.record

import com.assetboard.domain.AppClock
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assetboard.ui.components.FieldShape
import com.assetboard.ui.components.glassFieldColors
import com.assetboard.ui.components.GlassScaffold
import com.assetboard.ui.components.glassBarColors
import com.assetboard.AppContainer
import com.assetboard.data.Account
import com.assetboard.data.FeeOn
import com.assetboard.data.FlowKind
import com.assetboard.data.MoneyFlow
import com.assetboard.data.newId
import com.assetboard.domain.LedgerData
import com.assetboard.domain.LedgerException
import com.assetboard.domain.Money
import com.assetboard.ui.ActionViewModel
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.SegmentedControl
import com.assetboard.ui.components.AccountAvatar
import com.assetboard.ui.components.CalcKeys
import com.assetboard.ui.components.ConfirmDialog
import com.assetboard.ui.components.DateField
import com.assetboard.ui.components.ErrorDialog
import com.assetboard.ui.components.ListPickerDialog
import com.assetboard.ui.components.ListRow
import com.assetboard.ui.components.SectionCard
import com.assetboard.ui.components.SelectField
import com.assetboard.ui.theme.AppTheme
import com.assetboard.ui.theme.NumberStyle
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.assetboard.ui.components.verticalScrollWithIndicator

class FlowEditViewModel(
    private val container: AppContainer,
    private val contextAccountId: String?,
    private val flowId: String?,
    initialKind: String?,
) : ActionViewModel(container) {
    private val repo = container.repository

    var loaded by mutableStateOf(false)
    var accounts by mutableStateOf<List<Account>>(emptyList())
    var kind by mutableStateOf(runCatching { FlowKind.valueOf(initialKind ?: "") }.getOrDefault(FlowKind.TRANSFER))
    var fromId by mutableStateOf<String?>(null)
    var toId by mutableStateOf<String?>(null)
    var amountInput by mutableStateOf("")
    var feeInput by mutableStateOf("")
    var feeOn by mutableStateOf(kind.defaultFeeOn)
    var at by mutableStateOf(AppClock.millis())
    var note by mutableStateOf("")
    private var createdAt: Long? = null
    private var ledger: LedgerData? = null

    init {
        viewModelScope.launch {
            val l = repo.ledger.filterNotNull().first()
            ledger = l
            val f = flowId?.let { id -> l.flows.firstOrNull { it.id == id } }
            if (f != null) {
                kind = f.kind
                fromId = f.fromAccountId
                toId = f.toAccountId
                amountInput = Money.toInput(f.amount)
                feeInput = if (f.fee > 0) Money.toInput(f.fee) else ""
                feeOn = f.feeOn
                at = f.at
                note = f.note.orEmpty()
                createdAt = f.createdAt
            } else {
                applyKind(kind)
            }
            accounts = l.accounts.filter { !it.isDisabled || it.id == fromId || it.id == toId || it.id == contextAccountId }
            loaded = true
        }
    }

    val isNew: Boolean get() = flowId == null

    fun account(id: String?): Account? = id?.let { i -> accounts.firstOrNull { it.id == i } }

    private fun isLiability(a: Account) = ledger?.isLiability(a.id) == true

    /** Puts the account this screen was opened from on the side the kind implies; keeps the other side. */
    fun applyKind(k: FlowKind) {
        kind = k
        feeOn = k.defaultFeeOn
        if (!isNew) return
        val x = contextAccountId?.let { ledger?.accountById?.get(it) } ?: return
        val other = listOf(fromId, toId).firstOrNull { it != null && it != x.id }
        val xOnFrom = when (k) {
            FlowKind.BUY -> !x.isInvestment
            FlowKind.SELL, FlowKind.DIVIDEND -> x.isInvestment
            FlowKind.TRANSFER, FlowKind.EXTERNAL_OUT, FlowKind.EXPENSE -> true
            FlowKind.REPAY -> !isLiability(x)
            FlowKind.BORROW -> isLiability(x)
            FlowKind.EXTERNAL_IN, FlowKind.INCOME -> false
        }
        val otherSide = when (k) {
            FlowKind.EXTERNAL_IN, FlowKind.EXTERNAL_OUT, FlowKind.EXPENSE, FlowKind.INCOME -> null
            else -> other ?: suggestCounterpart(k, x)
        }
        if (xOnFrom) { fromId = x.id; toId = otherSide } else { toId = x.id; fromId = otherSide }
    }

    /** A sensible other side so two-account flows never default to "outside the app". */
    private fun suggestCounterpart(k: FlowKind, x: Account): String? {
        val l = ledger ?: return null
        val candidates = l.accounts.filter { !it.isDisabled && it.id != x.id }
        fun cash() = candidates.filter { !it.isInvestment && !l.isLiability(it.id) }.maxByOrNull { l.currentBalance(it.id) }
        fun investment() = candidates.filter { it.isInvestment }.maxByOrNull { l.currentBalance(it.id) }
        fun debt() = candidates.filter { l.isLiability(it.id) }.minByOrNull { l.currentBalance(it.id) }
        val pick = when (k) {
            FlowKind.BUY, FlowKind.SELL, FlowKind.DIVIDEND -> if (x.isInvestment) cash() else investment()
            FlowKind.REPAY, FlowKind.BORROW -> if (l.isLiability(x.id)) cash() else debt()
            else -> cash()
        }
        return pick?.id
    }

    fun swap() {
        val f = fromId
        fromId = toId
        toId = f
    }

    /** A second tap while the first is still writing would record the same flow twice. */
    var saving by mutableStateOf(false)
        private set

    fun save(onDone: () -> Unit) {
        if (saving) return
        saving = true
        action(onSuccess = onDone, onFailure = { saving = false }) {
            val amount = Money.parse(amountInput) ?: throw LedgerException("金额格式不对")
            val fee = if (feeInput.isBlank()) 0L else Money.parse(feeInput) ?: throw LedgerException("手续费格式不对")
            repo.saveFlow(
                MoneyFlow(
                    id = flowId ?: newId(),
                    kind = kind,
                    fromAccountId = fromId,
                    toAccountId = toId,
                    amount = amount,
                    fee = fee,
                    feeOn = feeOn,
                    at = at,
                    note = note.ifBlank { null },
                    createdAt = createdAt ?: AppClock.millis(),
                ),
            )
        }
    }

    fun delete(onDone: () -> Unit) {
        val id = flowId ?: return
        action(onDone) { repo.deleteFlow(id) }
    }
}

@Composable
fun FlowEditScreen(accountId: String?, flowId: String?, initialKind: String?, onBack: () -> Unit) {
    val container = LocalContainer.current
    val vm: FlowEditViewModel = viewModel(key = "flow-$accountId-$flowId-$initialKind") { FlowEditViewModel(container, accountId, flowId, initialKind) }
    val error by vm.error.collectAsStateWithLifecycle()
    ErrorDialog(error) { vm.error.value = null }
    val cs = MaterialTheme.colorScheme
    val extra = AppTheme.extra
    val groupColor: (Account) -> Int = { a -> container.repository.ledger.value?.groupById?.get(a.groupId)?.colorIndex ?: 0 }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    GlassScaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "记一笔" + vm.kind.label else "编辑流水") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { TextButton(onClick = { vm.save(onBack) }, enabled = vm.loaded && !vm.saving) { Text("保存") } },
                colors = glassBarColors(),
            )
        },
    ) { padding ->
        if (!vm.loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@GlassScaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScrollWithIndicator(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard(contentPadding = PaddingValues(16.dp)) {
                Text("类型", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 支出 and 收入 are 记账's, with categories, on their own sheet.
                    FlowKind.entries.filter { !it.isBill || it == vm.kind }.forEach { k ->
                        FilterChip(selected = vm.kind == k, onClick = { vm.applyKind(k) }, label = { Text(k.label) })
                    }
                }
            }
            SectionCard(contentPadding = PaddingValues(20.dp)) {
                SelectField("转出", vm.account(vm.fromId)?.name ?: "外部（app 之外）", onClick = { picking = "from" })
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    IconButton(onClick = vm::swap) { Icon(Icons.Default.SwapVert, "交换转出和转入") }
                }
                SelectField("转入", vm.account(vm.toId)?.name ?: "外部（app 之外）", onClick = { picking = "to" })
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    vm.amountInput, { vm.amountInput = it },
                    label = { Text("金额（转出方付出）") },
                    singleLine = true,
                    isError = vm.amountInput.isNotEmpty() && Money.parse(vm.amountInput) == null,
                    textStyle = MaterialTheme.typography.headlineSmall.merge(NumberStyle),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    shape = FieldShape,
                    colors = glassFieldColors(),
                )
                CalcKeys(vm.amountInput, { vm.amountInput = it }, Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    vm.feeInput, { vm.feeInput = it },
                    label = { Text("手续费") },
                    placeholder = { Text("0") },
                    singleLine = true,
                    isError = vm.feeInput.isNotEmpty() && Money.parse(vm.feeInput) == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    shape = FieldShape,
                    colors = glassFieldColors(),
                )
                val fee = Money.parse(vm.feeInput) ?: 0L
                if (fee > 0) {
                    Spacer(Modifier.height(10.dp))
                    Text("手续费算谁的成本", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    SegmentedControl(listOf(FeeOn.FROM, FeeOn.TO), vm.feeOn, { if (it == FeeOn.FROM) "转出方" else "转入方" }, { vm.feeOn = it })
                    Text(
                        "只影响投资账户的累计盈亏：申购费算基金的，赎回费也算基金的。",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                val amount = Money.parse(vm.amountInput)
                if (amount != null && amount > 0) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        buildString {
                            vm.account(vm.fromId)?.let { append("${it.name} −${Money.format(amount)}") }
                            if (vm.fromId != null && vm.toId != null) append("　→　")
                            vm.account(vm.toId)?.let { append("${it.name} +${Money.format(amount - fee)}") }
                        },
                        style = MaterialTheme.typography.bodyMedium.merge(NumberStyle),
                        color = cs.primary,
                    )
                }
            }
            SectionCard(contentPadding = PaddingValues(20.dp)) {
                DateField("时间", vm.at, { vm.at = it }, supportingText = "只选日期时记为当天 23:59:59")
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(vm.note, { vm.note = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth(), shape = FieldShape, colors = glassFieldColors())
            }
            if (!vm.isNew) {
                SectionCard {
                    ListRow(
                        title = "删除这笔流水",
                        onClick = { confirmDelete = true },
                        chevron = false,
                        trailing = { Text("删除", color = cs.error, style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
        }
    }

    picking?.let { side ->
        val options: List<Account?> = listOf<Account?>(null) + vm.accounts
        ListPickerDialog(
            title = if (side == "from") "转出账户" else "转入账户",
            items = options,
            label = { it?.name ?: "外部（app 之外）" },
            subtitle = { it?.purpose ?: if (it == null) "工资、消费等 app 不记录的去处" else null },
            leading = { a -> if (a != null) AccountAvatar(a, extra.group(groupColor(a)), size = 32.dp) },
            onPick = { a ->
                if (side == "from") vm.fromId = a?.id else vm.toId = a?.id
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "删除流水？",
            text = "两端账户的余额会一起重新推算。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = { confirmDelete = false; vm.delete(onBack) },
            onDismiss = { confirmDelete = false },
        )
    }
}
