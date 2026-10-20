package com.assetboard.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assetboard.ui.components.FieldShape
import com.assetboard.ui.components.glassFieldColors
import com.assetboard.ui.components.GlassScaffold
import com.assetboard.ui.components.glassBarColors
import com.assetboard.AppContainer
import com.assetboard.data.AccountGroup
import com.assetboard.ui.ActionViewModel
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.Dot
import com.assetboard.ui.components.ErrorDialog
import com.assetboard.ui.components.ListRow
import com.assetboard.ui.components.RowDivider
import com.assetboard.ui.components.SectionCard
import com.assetboard.ui.theme.AppTheme
import com.assetboard.ui.components.scrollIndicator
import androidx.compose.foundation.lazy.rememberLazyListState

class GroupsViewModel(private val container: AppContainer) : ActionViewModel(container) {
    private val repo = container.repository
    fun save(id: String?, name: String, liability: Boolean, color: Int, onDone: () -> Unit) = action(onDone) {
        if (id == null) repo.createGroup(name, liability, color) else repo.updateGroup(id, name, liability, color)
    }
    fun delete(id: String, onDone: () -> Unit) = action(onDone) { repo.deleteGroup(id) }
    fun move(id: String, delta: Int) = action { repo.moveGroup(id, delta) }
}

@Composable
fun GroupsScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val vm: GroupsViewModel = viewModel { GroupsViewModel(container) }
    val ledger by container.repository.ledger.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    ErrorDialog(error) { vm.error.value = null }
    val groups = ledger?.groups.orEmpty()
    val counts = ledger?.accounts.orEmpty().groupingBy { it.groupId }.eachCount()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val extra = AppTheme.extra
    val cs = MaterialTheme.colorScheme

    GlassScaffold(
        topBar = {
            TopAppBar(
                title = { Text("分组管理") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                colors = glassBarColors(),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "新建分组") }
        },
    ) { padding ->
        val barState = rememberLazyListState()
        LazyColumn(Modifier.fillMaxSize().padding(padding).scrollIndicator(barState), state = barState, contentPadding = PaddingValues(16.dp)) {
            item {
                SectionCard {
                    groups.forEachIndexed { i, g ->
                        if (i > 0) RowDivider(startIndent = 50.dp)
                        ListRow(
                            title = g.name,
                            subtitle = (if (g.isLiability) "负债" else "资产") + " · ${counts[g.id] ?: 0} 个账户",
                            leading = { Dot(extra.group(g.colorIndex), 14.dp) },
                            onClick = { editing = g.id },
                            chevron = false,
                            trailing = {
                                Row {
                                    IconButton(onClick = { vm.move(g.id, -1) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "上移") }
                                    IconButton(onClick = { vm.move(g.id, 1) }, enabled = i < groups.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "下移") }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    val target = groups.firstOrNull { it.id == editing }
    if (creating || target != null) {
        GroupDialog(
            group = target,
            accountCount = target?.let { counts[it.id] } ?: 0,
            nextColor = groups.size,
            onSave = { name, liab, color -> vm.save(target?.id, name, liab, color) { creating = false; editing = null } },
            onDelete = target?.let { g -> { vm.delete(g.id) { editing = null } } },
            onDismiss = { creating = false; editing = null },
        )
    }
}

@Composable
private fun GroupDialog(
    group: AccountGroup?,
    accountCount: Int,
    nextColor: Int,
    onSave: (String, Boolean, Int) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val extra = AppTheme.extra
    var name by rememberSaveable { mutableStateOf(group?.name.orEmpty()) }
    var liability by rememberSaveable { mutableStateOf(group?.isLiability ?: false) }
    var color by rememberSaveable { mutableIntStateOf(group?.colorIndex ?: (nextColor % extra.groupPalette.size)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (group == null) "新建分组" else "编辑分组") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = FieldShape, colors = glassFieldColors())
                Spacer(Modifier.height(14.dp))
                Text("颜色", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    extra.groupPalette.forEachIndexed { i, c ->
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(2.dp, if (i == color) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                                .clickable { color = i },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (i == color) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                ListRow(
                    title = "负债分组",
                    subtitle = if (accountCount > 0) "已有账户，不能切换" else "里面的账户按欠款记，计入总负债",
                    chevron = false,
                    trailing = { Switch(checked = liability, onCheckedChange = { liability = it }, enabled = accountCount == 0) },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, liability, color) }) { Text("保存") } },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete, enabled = accountCount == 0) {
                        Text("删除", color = if (accountCount == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
