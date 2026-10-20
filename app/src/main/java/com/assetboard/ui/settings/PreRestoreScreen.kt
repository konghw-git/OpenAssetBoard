package com.assetboard.ui.settings

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assetboard.ui.components.GlassScaffold
import com.assetboard.ui.components.glassBarColors
import com.assetboard.AppContainer
import com.assetboard.data.BackupPreview
import com.assetboard.data.PreRestoreCopy
import com.assetboard.domain.Dates
import com.assetboard.ui.ActionViewModel
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.ConfirmDialog
import com.assetboard.ui.components.ErrorDialog
import com.assetboard.ui.components.ListRow
import com.assetboard.ui.components.SectionCard
import kotlinx.coroutines.flow.MutableStateFlow
import com.assetboard.ui.components.scrollIndicator
import androidx.compose.foundation.lazy.rememberLazyListState

/** Inspect, confirm, restore: shared by "restore from backup" and the pre-restore copies. */
open class RestoreViewModel(protected val container: AppContainer) : ActionViewModel(container) {
    val pending = MutableStateFlow<Pair<Uri, BackupPreview>?>(null)

    fun inspect(uri: Uri) = action { pending.value = uri to container.backup.inspect(uri) }

    fun confirm() {
        val (uri, _) = pending.value ?: return
        pending.value = null
        action {
            val saved = container.backup.restore(uri)
            container.announce("已恢复。恢复前的数据另存为 ${saved.substringAfterLast('/')}")
        }
    }
}

class PreRestoreViewModel(container: AppContainer) : RestoreViewModel(container) {
    val copies = MutableStateFlow<List<PreRestoreCopy>?>(null)

    fun load() = action { copies.value = container.backup.preRestoreCopies() }
}

@Composable
fun RestoreConfirm(vm: RestoreViewModel) {
    val pending by vm.pending.collectAsStateWithLifecycle()
    val (_, p) = pending ?: return
    val text = if (p.formatVersion == 1) {
        "此备份来自旧版本，不含待办、日记和笔记。恢复后将清空当前 ${p.currentTodos} 条待办、${p.currentDiaries} 篇日记、${p.currentNotes} 篇笔记、${p.currentWeeks} 篇周复盘。\n\n恢复前会自动另存一份当前数据，可以在「恢复前副本」里一键还原。"
    } else {
        "备份于 ${Dates.dateTime(p.exportedAt)}：${p.accounts} 个账户、${p.todos} 条待办、${p.diaries} 篇日记、${p.notes} 篇笔记、${p.weeks} 篇周复盘、${p.healthRecords} 条健康记录。" +
            (if (p.formatVersion < 3) "这份备份来自旧版本，不含每日指标，恢复后打卡记录会清空。" else "") +
            (if (p.formatVersion < 4 && p.currentHealthRecords > 0) "这份备份不含健康数据，恢复后当前 ${p.currentHealthRecords} 条健康记录会清空。" else "") +
            (if (p.formatVersion < 5 && p.currentNotes > 0) "这份备份不含笔记，恢复后当前 ${p.currentNotes} 篇笔记会清空。" else "") +
            (if (p.formatVersion < 6 && p.currentWeeks > 0) "这份备份不含周复盘，恢复后当前 ${p.currentWeeks} 篇周复盘会清空。" else "") +
            "\n\n当前的全部数据会被替换。恢复前会自动另存一份当前数据。"
    }
    ConfirmDialog(
        title = "用这份备份覆盖全部数据？",
        text = text,
        confirmLabel = "覆盖恢复",
        destructive = true,
        onConfirm = vm::confirm,
        onDismiss = { vm.pending.value = null },
    )
}

@Composable
fun PreRestoreScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val vm: PreRestoreViewModel = viewModel { PreRestoreViewModel(container) }
    val copies by vm.copies.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    ErrorDialog(error) { vm.error.value = null }
    LaunchedEffect(Unit) { vm.load() }
    RestoreConfirm(vm)
    val cs = MaterialTheme.colorScheme
    GlassScaffold(
        topBar = {
            TopAppBar(
                title = { Text("恢复前副本") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                colors = glassBarColors(),
            )
        },
    ) { padding ->
        val barState = rememberLazyListState()
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).scrollIndicator(barState), state = barState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "每次恢复备份之前，当时的全部数据会自动另存一份，新的覆盖旧的，只留最近一次。点一下即可回到那时；这次恢复同样会先另存，所以可以来回切换。",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            val list = copies
            if (list != null && list.isEmpty()) {
                item { Text("还没有恢复前副本", color = cs.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
            }
            items(list.orEmpty(), key = { it.uri.toString() }) { c ->
                SectionCard {
                    ListRow(
                        title = "保存于 " + Dates.dateTime(c.savedAt),
                        subtitle = if (c.inBackupFolder) "在备份目录" else "在 app 私有目录",
                        leading = { Icon(Icons.Outlined.History, null, tint = cs.primary) },
                        onClick = { vm.inspect(c.uri) },
                    )
                }
            }
        }
    }
}
