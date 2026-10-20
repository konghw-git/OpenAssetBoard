package com.assetboard.ui.account

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import com.assetboard.data.AccountDraft
import com.assetboard.data.AccountGroup
import com.assetboard.data.AccountKind
import com.assetboard.domain.Dates
import com.assetboard.domain.LedgerException
import com.assetboard.domain.Money
import com.assetboard.ui.ActionViewModel
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.DateField
import com.assetboard.ui.components.DatePickDialog
import com.assetboard.ui.components.Dot
import com.assetboard.ui.components.ErrorDialog
import com.assetboard.ui.components.ListPickerDialog
import com.assetboard.ui.components.ListRow
import com.assetboard.ui.components.RowDivider
import com.assetboard.ui.components.SectionCard
import com.assetboard.ui.components.SectionHeader
import com.assetboard.ui.components.SelectField
import com.assetboard.ui.theme.AppTheme
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.assetboard.ui.components.verticalScrollWithIndicator

class AccountEditViewModel(
    private val container: AppContainer,
    val accountId: String?,
    initialGroupId: String?,
) : ActionViewModel(container) {
    private val repo = container.repository

    var loaded by mutableStateOf(false)
    var original by mutableStateOf<Account?>(null)
    var groups by mutableStateOf<List<AccountGroup>>(emptyList())
    var canDelete by mutableStateOf(false)
    var balanceDisplay by mutableStateOf(0L)
    var baselineDisplay by mutableStateOf(0L)

    var name by mutableStateOf("")
    var purpose by mutableStateOf("")
    var groupId by mutableStateOf(initialGroupId)
    var kind by mutableStateOf(AccountKind.NORMAL)
    var institution by mutableStateOf("")
    var cardTail by mutableStateOf("")
    var code by mutableStateOf("")
    var lockedUntil by mutableStateOf<Long?>(null)
    var includeInTotal by mutableStateOf(true)
    var note by mutableStateOf("")

    // New accounts only.
    var baselineAt by mutableStateOf(System.currentTimeMillis())
    var baselineInput by mutableStateOf("0")
    var costInput by mutableStateOf("")

    init {
        viewModelScope.launch {
            val l = repo.ledger.filterNotNull().first()
            groups = l.groups
            val a = accountId?.let { l.accountById[it] }
            if (a != null) {
                original = a
                name = a.name
                purpose = a.purpose.orEmpty()
                groupId = a.groupId
                kind = a.kind
                institution = a.institution.orEmpty()
                cardTail = a.cardTail.orEmpty()
                code = a.code.orEmpty()
                lockedUntil = a.lockedUntil
                includeInTotal = a.includeInTotal
                note = a.note.orEmpty()
                balanceDisplay = l.toDisplay(a.id, l.currentBalance(a.id))
                baselineDisplay = l.toDisplay(a.id, l.valuationsOf(a.id).firstOrNull { it.at == a.baselineAt }?.value ?: 0L)
                canDelete = repo.canDelete(a.id)
            } else if (groupId == null || groups.none { it.id == groupId }) {
                groupId = groups.firstOrNull()?.id
            }
            loaded = true
        }
    }

    val group: AccountGroup? get() = groups.firstOrNull { it.id == groupId }
    val isLiability: Boolean get() = group?.isLiability == true

    /** Groups this account may move into: never across the asset / liability line. */
    fun selectableGroups(): List<AccountGroup> {
        val a = original ?: return groups
        val liab = groups.firstOrNull { it.id == a.groupId }?.isLiability
        return groups.filter { it.isLiability == liab }
    }

    private fun draft() = AccountDraft(
        groupId = groupId ?: "",
        name = name,
        purpose = purpose,
        kind = kind,
        institution = institution,
        cardTail = cardTail,
        code = code,
        lockedUntil = if (kind == AccountKind.INVESTMENT) lockedUntil else null,
        includeInTotal = includeInTotal,
        note = note,
    )

    fun save(onCreated: (String) -> Unit, onSaved: () -> Unit) {
        val id = accountId
        if (id != null) {
            action(onSaved) { repo.updateAccount(id, draft()) }
            return
        }
        action {
            val base = Money.parse(baselineInput) ?: throw LedgerException("期初金额格式不对")
            val cost = costInput.takeIf { it.isNotBlank() }?.let { Money.parse(it) ?: throw LedgerException("期初成本格式不对") }
            val newId = repo.createAccount(draft(), baselineAt, base, cost)
            onCreated(newId)
        }
    }

    fun setDisabled(disabled: Boolean, onDone: () -> Unit) {
        val id = accountId ?: return
        action(onDone) { repo.setDisabled(id, disabled) }
    }

    fun delete(onDone: () -> Unit) {
        val id = accountId ?: return
        action(onDone) { repo.deleteAccount(id) }
    }

    fun updateBaseline(at: Long, valueInput: String, costInputText: String, onDone: () -> Unit) {
        val id = accountId ?: return
        action(onDone) {
            val v = Money.parse(valueInput) ?: throw LedgerException("期初金额格式不对")
            val c = costInputText.takeIf { it.isNotBlank() }?.let { Money.parse(it) ?: throw LedgerException("期初成本格式不对") }
            repo.updateBaseline(id, at, v, c)
            val l = repo.loadOnce()
            original = l.accountById[id]
            baselineDisplay = v
        }
    }
}

@Composable
fun AccountEditScreen(
    accountId: String?,
    initialGroupId: String?,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    onDeleted: () -> Unit,
    onRecordOutflow: (String) -> Unit,
) {
    val container = LocalContainer.current
    val vm: AccountEditViewModel = viewModel(key = "account-edit-$accountId") { AccountEditViewModel(container, accountId, initialGroupId) }
    val error by vm.error.collectAsStateWithLifecycle()
    ErrorDialog(error) { vm.error.value = null }
    val cs = MaterialTheme.colorScheme
    val extra = AppTheme.extra
    val isNew = accountId == null

    var pickGroup by rememberSaveable { mutableStateOf(false) }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    var pickLock by rememberSaveable { mutableStateOf(false) }
    var baselineDialog by rememberSaveable { mutableStateOf(false) }
    var disableDialog by rememberSaveable { mutableStateOf(false) }
    var deleteDialog by rememberSaveable { mutableStateOf(false) }

    GlassScaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "新建账户" else "账户设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { TextButton(onClick = { vm.save(onCreated, onBack) }, enabled = vm.loaded) { Text("保存") } },
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
            SectionCard(contentPadding = PaddingValues(20.dp)) {
                OutlinedTextField(vm.name, { vm.name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = FieldShape, colors = glassFieldColors())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    vm.purpose, { vm.purpose = it },
                    label = { Text("用途") },
                    placeholder = { Text("如：日常消费、周转资金") },
                    supportingText = { Text("显示在名称下方的灰字") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = FieldShape,
                    colors = glassFieldColors(),
                )
                Spacer(Modifier.height(4.dp))
                SelectField(
                    label = "分组",
                    value = vm.group?.let { it.name + if (it.isLiability) "（负债）" else "" } ?: "请选择",
                    onClick = { pickGroup = true },
                )
                Spacer(Modifier.height(14.dp))
                Text("类型", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(AccountKind.NORMAL to "普通", AccountKind.INVESTMENT to "投资").forEachIndexed { i, (k, label) ->
                        SegmentedButton(
                            selected = vm.kind == k,
                            onClick = { vm.kind = k },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(label) }
                    }
                }
                if (vm.kind == AccountKind.INVESTMENT) {
                    Text(
                        "投资账户会显示累计盈亏和净投入，可记申购、赎回、分红。",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            if (isNew) {
                SectionCard(contentPadding = PaddingValues(20.dp)) {
                    Text("期初", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "从这一刻开始记录。期初之前不能补记流水；以后要补更早的数据，可以把期初前移。",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                    )
                    DateField("期初时间", vm.baselineAt, { vm.baselineAt = it })
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        vm.baselineInput, { vm.baselineInput = it },
                        label = { Text(if (vm.isLiability) "期初欠款" else if (vm.kind == AccountKind.INVESTMENT) "期初市值" else "期初余额") },
                        singleLine = true,
                        isError = Money.parse(vm.baselineInput) == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        shape = FieldShape,
                        colors = glassFieldColors(),
                    )
                    if (vm.kind == AccountKind.INVESTMENT) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            vm.costInput, { vm.costInput = it },
                            label = { Text("期初成本") },
                            placeholder = { Text("留空则等于期初市值") },
                            supportingText = { Text("这笔钱当初实际投入了多少。填了，盈亏从买入时算起。") },
                            singleLine = true,
                            isError = vm.costInput.isNotBlank() && Money.parse(vm.costInput) == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                            shape = FieldShape,
                            colors = glassFieldColors(),
                        )
                    }
                }
            } else {
                val a = vm.original!!
                SectionCard {
                    SectionHeader("期初")
                    ListRow(
                        title = Dates.dateTime(a.baselineAt),
                        subtitle = buildString {
                            append((if (vm.isLiability) "欠款 " else "余额 ") + Money.format(vm.baselineDisplay))
                            if (a.isInvestment && a.baselineCost != null) append(" · 成本 " + Money.format(if (vm.isLiability) -a.baselineCost else a.baselineCost))
                        },
                        trailing = { TextButton(onClick = { baselineDialog = true }) { Text("修改") } },
                    )
                }
            }

            SectionCard {
                ListRow(
                    title = "更多信息",
                    subtitle = "机构、卡号后四位" + if (vm.kind == AccountKind.INVESTMENT) "、代码、锁定日期" else "",
                    onClick = { moreOpen = !moreOpen },
                    chevron = false,
                    trailing = { Icon(if (moreOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = cs.onSurfaceVariant) },
                )
                AnimatedVisibility(moreOpen) {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(vm.institution, { vm.institution = it }, label = { Text("机构") }, placeholder = { Text("如：中国农业银行、支付宝") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = FieldShape, colors = glassFieldColors())
                        OutlinedTextField(
                            vm.cardTail, { t -> vm.cardTail = t.filter { it.isDigit() }.take(4) },
                            label = { Text("卡号后四位") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            shape = FieldShape,
                            colors = glassFieldColors(),
                        )
                        if (vm.kind == AccountKind.INVESTMENT) {
                            OutlinedTextField(vm.code, { vm.code = it }, label = { Text("基金 / 股票代码") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = FieldShape, colors = glassFieldColors())
                            SelectField(
                                label = "锁定至",
                                value = vm.lockedUntil?.let { Dates.date(it) } ?: "不锁定",
                                onClick = { pickLock = true },
                                supportingText = "持有期、定期等取不出来的日期，首页会显示剩余天数",
                                icon = if (vm.lockedUntil != null) {
                                    { IconButton(onClick = { vm.lockedUntil = null }) { Icon(Icons.Default.Close, "清除") } }
                                } else null,
                            )
                        }
                    }
                }
            }

            SectionCard {
                ListRow(
                    title = "计入净资产",
                    subtitle = "关掉后照常记录，但不算进总资产和净资产，比如替别人保管的钱",
                    trailing = { Switch(checked = vm.includeInTotal, onCheckedChange = { vm.includeInTotal = it }) },
                    chevron = false,
                )
                RowDivider()
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    OutlinedTextField(vm.note, { vm.note = it }, label = { Text("备注") }, minLines = 2, modifier = Modifier.fillMaxWidth(), shape = FieldShape, colors = glassFieldColors())
                }
            }

            if (!isNew) {
                val a = vm.original!!
                SectionCard {
                    ListRow(
                        title = if (a.isDisabled) "重新启用" else "停用账户",
                        subtitle = if (a.isDisabled) "回到首页并重新计入" else "收进「已停用」，不再计入净资产，历史保留",
                        onClick = {
                            if (a.isDisabled) vm.setDisabled(false, onBack)
                            else if (vm.balanceDisplay != 0L) disableDialog = true
                            else vm.setDisabled(true, onBack)
                        },
                    )
                    RowDivider()
                    ListRow(
                        title = "删除账户",
                        subtitle = if (vm.canDelete) "只有期初记录，可以删除" else "已有记录，只能停用",
                        onClick = if (vm.canDelete) ({ deleteDialog = true }) else null,
                        chevron = false,
                        trailing = {
                            Text("删除", color = if (vm.canDelete) cs.error else cs.outline, style = MaterialTheme.typography.labelLarge)
                        },
                    )
                }
            }
        }
    }

    if (pickGroup) {
        ListPickerDialog(
            title = "选择分组",
            items = vm.selectableGroups(),
            label = { it.name },
            subtitle = { if (it.isLiability) "负债" else null },
            leading = { Dot(extra.group(it.colorIndex)) },
            onPick = { vm.groupId = it.id; pickGroup = false },
            onDismiss = { pickGroup = false },
        )
    }
    if (pickLock) {
        DatePickDialog(
            initial = vm.lockedUntil?.let { Dates.localDate(it) } ?: java.time.LocalDate.now().plusMonths(6),
            onDismiss = { pickLock = false },
            onPick = { vm.lockedUntil = Dates.endOfDay(it); pickLock = false },
            maxDate = null,
        )
    }
    if (baselineDialog) {
        BaselineDialog(vm, onDismiss = { baselineDialog = false })
    }
    if (disableDialog) {
        AlertDialog(
            onDismissRequest = { disableDialog = false },
            title = { Text("停用账户？") },
            text = {
                Text(
                    "余额仍为 ${Money.format(vm.balanceDisplay)}，停用后不计入净资产。" +
                        "\n\n如果这笔钱已经转走，请先记一笔转出，否则净资产会凭空少掉这笔钱。",
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { disableDialog = false; onRecordOutflow(vm.accountId!!) }) { Text("记一笔转出") }
                    TextButton(onClick = { disableDialog = false; vm.setDisabled(true, onBack) }) { Text("仍然停用") }
                }
            },
            dismissButton = { TextButton(onClick = { disableDialog = false }) { Text("取消") } },
        )
    }
    if (deleteDialog) {
        com.assetboard.ui.components.ConfirmDialog(
            title = "删除账户？",
            text = "账户和它的期初记录会被删除，无法恢复。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = { deleteDialog = false; vm.delete(onDeleted) },
            onDismiss = { deleteDialog = false },
        )
    }
}

@Composable
private fun BaselineDialog(vm: AccountEditViewModel, onDismiss: () -> Unit) {
    val a = vm.original ?: return
    var at by rememberSaveable { mutableStateOf(a.baselineAt) }
    var value by rememberSaveable { mutableStateOf(Money.toInput(vm.baselineDisplay)) }
    var cost by rememberSaveable {
        mutableStateOf(a.baselineCost?.let { Money.toInput(if (vm.isLiability) -it else it) }.orEmpty())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改期初") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "期初只能前移。前移后，新期初到旧期初之间的申购、赎回需要补记；原期初记录保留为一条普通记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DateField(
                    label = "期初时间",
                    at = at,
                    onPick = { picked -> at = if (Dates.localDate(picked) == Dates.localDate(a.baselineAt)) a.baselineAt else picked },
                    maxDate = Dates.localDate(a.baselineAt),
                )
                OutlinedTextField(
                    value, { value = it },
                    label = { Text(if (vm.isLiability) "期初欠款" else "期初金额") },
                    singleLine = true,
                    isError = Money.parse(value) == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = FieldShape,
                    colors = glassFieldColors(),
                )
                if (a.isInvestment) {
                    OutlinedTextField(
                        cost, { cost = it },
                        label = { Text("期初成本") },
                        placeholder = { Text("留空则等于期初金额") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = FieldShape,
                        colors = glassFieldColors(),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.updateBaseline(at, value, cost, onDismiss) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
