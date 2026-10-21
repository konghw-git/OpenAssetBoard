package com.assetboard.ui.record

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
import androidx.compose.material3.CircularProgressIndicator
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
import com.assetboard.domain.Dates
import com.assetboard.domain.LedgerException
import com.assetboard.domain.Money
import com.assetboard.ui.ActionViewModel
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.AccountAvatar
import com.assetboard.ui.components.ConfirmDialog
import com.assetboard.ui.components.DateField
import com.assetboard.ui.components.ErrorDialog
import com.assetboard.ui.components.ListRow
import com.assetboard.ui.components.Pill
import com.assetboard.ui.components.SectionCard
import com.assetboard.ui.theme.AppTheme
import com.assetboard.ui.theme.NumberStyle
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.assetboard.ui.components.verticalScrollWithIndicator

class ValuationEditViewModel(
    private val container: AppContainer,
    private val accountId: String,
    private val valuationId: String?,
) : ActionViewModel(container) {
    private val repo = container.repository

    var loaded by mutableStateOf(false)
    var account by mutableStateOf<Account?>(null)
    var groupColor by mutableStateOf(0)
    var isLiability by mutableStateOf(false)
    var inStocktake by mutableStateOf(false)
    var isBaseline by mutableStateOf(false)
    var at by mutableStateOf(System.currentTimeMillis())
    var input by mutableStateOf("")
    var note by mutableStateOf("")

    init {
        viewModelScope.launch {
            val l = repo.ledger.filterNotNull().first()
            val a = l.accountById[accountId] ?: return@launch
            account = a
            groupColor = l.groupById[a.groupId]?.colorIndex ?: 0
            isLiability = l.isLiability(a.id)
            val v = valuationId?.let { l.valuationById[it] }
            if (v != null) {
                at = v.at
                input = Money.toInput(l.toDisplay(a.id, v.value))
                note = v.note.orEmpty().takeIf { it != "期初" }.orEmpty()
                inStocktake = v.snapshotId != null || l.snapshotItems.any { it.valuationId == v.id }
                isBaseline = l.isBaseline(v)
            } else {
                input = Money.toInput(l.toDisplay(a.id, l.currentBalance(a.id)))
            }
            loaded = true
        }
    }

    /** What the ledger would say at [at] without this record, in display sign. */
    fun implied(): Long? {
        val l = repo.ledger.value ?: return null
        val a = account ?: return null
        val existing = valuationId?.let { l.valuationById[it] }
        val raw = if (existing != null && existing.at == at) l.impliedBefore(existing) else l.balanceAt(a.id, at)
        return raw?.let { l.toDisplay(a.id, it) }
    }

    fun save(onDone: () -> Unit) {
        action(onDone) {
            val value = Money.parse(input) ?: throw LedgerException("金额格式不对")
            if (valuationId == null) repo.addValuation(accountId, at, value, note)
            else repo.updateValuation(valuationId, at, value, note)
        }
    }

    fun delete(onDone: () -> Unit) {
        val id = valuationId ?: return
        action(onDone) { repo.deleteValuation(id) }
    }
}

@Composable
fun ValuationEditScreen(accountId: String, valuationId: String?, onBack: () -> Unit) {
    val container = LocalContainer.current
    val vm: ValuationEditViewModel = viewModel(key = "valuation-$accountId-$valuationId") { ValuationEditViewModel(container, accountId, valuationId) }
    val error by vm.error.collectAsStateWithLifecycle()
    ErrorDialog(error) { vm.error.value = null }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val extra = AppTheme.extra
    val a = vm.account
    val noun = when {
        a?.isInvestment == true -> "市值"
        vm.isLiability -> "欠款"
        else -> "余额"
    }

    GlassScaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (valuationId == null) "更新$noun" else "编辑记录") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { TextButton(onClick = { vm.save(onBack) }, enabled = vm.loaded && !vm.isBaseline) { Text("保存") } },
                colors = glassBarColors(),
            )
        },
    ) { padding ->
        if (!vm.loaded || a == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@GlassScaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScrollWithIndicator(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard {
                ListRow(
                    title = a.name,
                    subtitle = a.purpose,
                    leading = { AccountAvatar(a, extra.group(vm.groupColor)) },
                    chevron = false,
                    trailing = {
                        when {
                            vm.isBaseline -> Pill("期初", cs.secondary)
                            vm.inStocktake -> Pill("盘点", cs.primary)
                        }
                    },
                )
            }
            SectionCard(contentPadding = PaddingValues(20.dp)) {
                OutlinedTextField(
                    value = vm.input,
                    onValueChange = { vm.input = it },
                    label = { Text(noun) },
                    singleLine = true,
                    isError = Money.parse(vm.input) == null,
                    enabled = !vm.isBaseline,
                    textStyle = MaterialTheme.typography.headlineSmall.merge(NumberStyle),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    shape = FieldShape,
                    colors = glassFieldColors(),
                )
                val implied = vm.implied()
                val parsed = Money.parse(vm.input)
                if (implied != null) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("此前推算 ${Money.format(implied)}", style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = cs.onSurfaceVariant)
                        if (parsed != null && parsed != implied) {
                            val d = parsed - implied
                            Spacer(Modifier.width(8.dp))
                            Pill(Money.format(d, signed = true), extra.signed(if (vm.isLiability) -d else d))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                DateField(
                    label = "时间",
                    at = vm.at,
                    onPick = { vm.at = it },
                    enabled = !vm.inStocktake && !vm.isBaseline,
                    supportingText = when {
                        vm.isBaseline -> "期初请在账户设置里修改"
                        vm.inStocktake -> "属于一次盘点，日期随那次盘点修改"
                        else -> "只选日期时记为当天 23:59:59；同一时刻的流水视为已包含在估值里"
                    },
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(vm.note, { vm.note = it }, label = { Text("备注") }, enabled = !vm.isBaseline, modifier = Modifier.fillMaxWidth(), shape = FieldShape, colors = glassFieldColors())
            }
            if (valuationId != null && !vm.inStocktake && !vm.isBaseline) {
                SectionCard {
                    ListRow(
                        title = "删除这条记录",
                        onClick = { confirmDelete = true },
                        chevron = false,
                        trailing = { Text("删除", color = cs.error, style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
            Text(
                if (a.isInvestment) "市值变化里没被申购、赎回、分红解释的部分，会计入累计盈亏。"
                else "两次记录之间没被转账解释的变化，就是没逐笔记的日常收支。",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "删除记录？",
            text = "删除后，这段时间的余额会按上一条记录和流水重新推算。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = { confirmDelete = false; vm.delete(onBack) },
            onDismiss = { confirmDelete = false },
        )
    }
}
