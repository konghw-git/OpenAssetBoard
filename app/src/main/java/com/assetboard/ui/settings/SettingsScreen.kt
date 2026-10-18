package com.assetboard.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assetboard.AppContainer
import com.assetboard.BuildConfigInfo
import com.assetboard.data.BackupKind
import com.assetboard.data.ThemeMode
import com.assetboard.domain.Dates
import com.assetboard.ui.ActionViewModel
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.ConfirmDialog
import com.assetboard.ui.components.ErrorDialog
import com.assetboard.ui.components.ListRow
import com.assetboard.ui.components.RowDivider
import com.assetboard.ui.components.SectionCard
import com.assetboard.ui.components.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow

class SettingsViewModel(private val container: AppContainer) : ActionViewModel() {
    val message = MutableStateFlow<String?>(null)

    fun backupNow() = action { message.value = "已备份：" + container.backup.backupNow(BackupKind.MANUAL) }

    fun restore(uri: Uri) = action {
        val saved = container.backup.restore(uri)
        message.value = "已恢复。恢复前的数据另存为 $saved"
    }
}

/** "content://…/tree/primary%3ADocuments%2FAssetBoard" -> "内部存储/Documents/AssetBoard" */
private fun readableTree(uri: String): String {
    val seg = Uri.parse(uri).lastPathSegment ?: return uri
    return seg.replaceFirst("primary:", "内部存储/")
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onGroups: () -> Unit) {
    val container = LocalContainer.current
    val settings = container.settings
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container) }
    val context = LocalContext.current
    val theme by settings.themeMode.collectAsStateWithLifecycle()
    val upIsRed by settings.upIsRed.collectAsStateWithLifecycle()
    val tree by settings.backupTreeUri.collectAsStateWithLifecycle()
    val lastBackup by settings.lastBackupAt.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingRestore by rememberSaveable { mutableStateOf<String?>(null) }
    ErrorDialog(error) { vm.error.value = null }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.message.value = null
        }
    }

    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            settings.setBackupTreeUri(uri.toString())
        }
    }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingRestore = uri.toString()
    }
    val cs = MaterialTheme.colorScheme

    Scaffold(
        containerColor = cs.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard {
                SectionHeader("备份")
                ListRow(
                    title = "备份目录",
                    subtitle = tree?.let(::readableTree) ?: "未设置。建议选「文档」下新建的文件夹，卸载 app 后仍保留",
                    leading = { Icon(Icons.Outlined.Folder, null, tint = cs.primary) },
                    onClick = { pickTree.launch(null) },
                )
                RowDivider(startIndent = 58.dp)
                ListRow(
                    title = "立即备份",
                    subtitle = lastBackup?.let { "上次备份 " + Dates.dateTime(it) } ?: "还没有备份过",
                    leading = { Icon(Icons.Outlined.Backup, null, tint = cs.primary) },
                    onClick = { if (tree == null) pickTree.launch(null) else vm.backupNow() },
                )
                RowDivider(startIndent = 58.dp)
                ListRow(
                    title = "从备份恢复",
                    subtitle = "整库覆盖，恢复前自动另存当前数据",
                    leading = { Icon(Icons.Outlined.Restore, null, tint = cs.primary) },
                    onClick = { pickBackup.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                )
                Text(
                    "每天自动备份一次，保留最近 10 份。手机厂商的后台限制可能让自动备份推迟，改完数据后建议手动点一次「立即备份」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
                )
            }

            SectionCard {
                SectionHeader("账户")
                ListRow(
                    title = "分组管理",
                    subtitle = "名称、颜色、顺序、资产 / 负债属性",
                    leading = { Icon(Icons.Outlined.Category, null, tint = cs.primary) },
                    onClick = onGroups,
                )
            }

            SectionCard(contentPadding = PaddingValues(bottom = 16.dp)) {
                SectionHeader("外观")
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text("主题", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { i, m ->
                            SegmentedButton(selected = theme == m, onClick = { settings.setThemeMode(m) }, shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size)) { Text(m.label) }
                        }
                    }
                    Text("涨跌颜色", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(true to "红涨绿跌", false to "绿涨红跌").forEachIndexed { i, (v, label) ->
                            SegmentedButton(selected = upIsRed == v, onClick = { settings.setUpIsRed(v) }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                        }
                    }
                }
            }

            Text(
                "资产看板 ${BuildConfigInfo.versionName(context)} · 数据只保存在本机",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }

    pendingRestore?.let { uri ->
        ConfirmDialog(
            title = "用这份备份覆盖全部数据？",
            text = "当前的所有账户和记录会被替换。恢复前会自动另存一份当前数据。",
            confirmLabel = "覆盖恢复",
            destructive = true,
            onConfirm = { pendingRestore = null; vm.restore(Uri.parse(uri)) },
            onDismiss = { pendingRestore = null },
        )
    }
}
