package com.assetboard.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.draw.shadow
import com.assetboard.R
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import com.assetboard.ui.components.GlassScaffold
import com.assetboard.ui.components.glassBarColors
import com.assetboard.ui.components.GlassSnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assetboard.AppContainer
import com.assetboard.BuildConfigInfo
import com.assetboard.data.BackupKind
import com.assetboard.data.ThemeMode
import com.assetboard.data.SettingsStore
import androidx.compose.material.icons.outlined.BlurOn
import com.assetboard.domain.Dates
import com.assetboard.ui.ActionViewModel
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.SegmentedControl
import com.assetboard.ui.components.ConfirmDialog
import com.assetboard.ui.components.ErrorDialog
import com.assetboard.ui.components.IconTile
import com.assetboard.ui.components.ListRow
import com.assetboard.ui.components.RowDivider
import com.assetboard.ui.components.SectionCard
import com.assetboard.ui.components.SectionHeader
import com.assetboard.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.assetboard.ui.components.verticalScrollWithIndicator

class SettingsViewModel(container: AppContainer) : RestoreViewModel(container) {
    val message = MutableStateFlow<String?>(null)

    fun backupNow() = action { message.value = "已备份，覆盖了 " + container.backup.backupNow(BackupKind.MANUAL) }

    fun updateHolidays() = action {
        container.holidays.updateOnline()
        message.value = "节假日数据已检查"
    }

    fun reset(scope: ResetScope) = action {
        val saved = when (scope) {
            ResetScope.LEDGER_HISTORY -> container.backup.resetLedgerHistory()
            ResetScope.EVERYTHING -> container.backup.reset()
        }
        container.announce("${scope.done}。原来的数据另存为 ${saved.substringAfterLast('/')}，可在「恢复前副本」里找回")
    }
}

enum class ResetScope(val title: String, val subtitle: String, val confirmTitle: String, val confirmText: String, val confirmLabel: String, val done: String) {
    LEDGER_HISTORY(
        "清空资产历史",
        "保留账户和当前余额，以今天为新的期初",
        "清空资产历史？",
        "分组、账户和每个账户现在的余额都保留，今天成为新的期初。此前的估值、盘点和转账买卖流水全部删除，趋势图从今天开始画，投资账户的收益从 0 重新计。待办、日记、笔记等其他数据不受影响。",
        "清空历史",
        "已清空资产历史",
    ),
    EVERYTHING(
        "彻底清空",
        "删除全部数据，回到刚安装时的样子",
        "清空全部数据？",
        "所有资产、待办、日记、笔记、周复盘、打卡和健康记录都会删除，包括回收站，app 回到刚安装时的样子。设置和备份目录保留。",
        "全部清空",
        "已彻底清空",
    ),
}

/** "content://…/tree/primary%3ADocuments%2FAssetBoard" -> "内部存储/Documents/AssetBoard" */
private fun readableTree(uri: String): String {
    val seg = Uri.parse(uri).lastPathSegment ?: return uri
    return seg.replaceFirst("primary:", "内部存储/")
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onGroups: () -> Unit,
    onTrash: () -> Unit = {},
    onPreRestore: () -> Unit = {},
    onGuide: () -> Unit = {},
) {
    val container = LocalContainer.current
    val settings = container.settings
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container) }
    val context = LocalContext.current
    val theme by settings.themeMode.collectAsStateWithLifecycle()
    val upIsRed by settings.upIsRed.collectAsStateWithLifecycle()
    val glassMode by settings.glass.collectAsStateWithLifecycle()
    val tree by settings.backupTreeUri.collectAsStateWithLifecycle()
    val lastBackup by settings.lastBackupAt.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val networkOn by settings.networkEnabled.collectAsStateWithLifecycle()
    val weatherOn by settings.weatherEnabled.collectAsStateWithLifecycle()
    val holidayOn by settings.holidayUpdateEnabled.collectAsStateWithLifecycle()
    val allAlarms by settings.allRemindersAsAlarms.collectAsStateWithLifecycle()
    val diaryOn by settings.diaryReminderEnabled.collectAsStateWithLifecycle()
    val diaryAt by settings.diaryReminderMinutes.collectAsStateWithLifecycle()
    val holidayYears = container.holidays.table.collectAsStateWithLifecycle().value.years
    var pickDiaryTime by remember { mutableStateOf(false) }
    val updater = container.updater
    val update by updater.state.collectAsStateWithLifecycle()
    val autoUpdate by settings.autoUpdateCheck.collectAsStateWithLifecycle()
    var editMirror by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf<ResetScope?>(null) }
    val snackbar = remember { SnackbarHostState() }
    ErrorDialog(error) { vm.error.value = null }
    RestoreConfirm(vm)
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
        if (uri != null) vm.inspect(uri)
    }
    val cs = MaterialTheme.colorScheme
    val palette = AppTheme.extra

    GlassScaffold(
        snackbarHost = { GlassSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                colors = glassBarColors(),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScrollWithIndicator(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AboutCard(BuildConfigInfo.versionName(context), lastBackup, onBackup = { if (tree == null) pickTree.launch(null) else vm.backupNow() })

            SectionCard {
                SectionHeader("备份")
                ListRow(
                    title = "备份目录",
                    subtitle = tree?.let(::readableTree) ?: "未设置。建议选「文档」下新建的文件夹，卸载 app 后仍保留",
                    leading = { IconTile(Icons.Outlined.Folder, palette.group(1)) },
                    onClick = { pickTree.launch(null) },
                )
                RowDivider(startIndent = 68.dp)
                ListRow(
                    title = "立即备份",
                    subtitle = lastBackup?.let { "上次备份 " + Dates.dateTime(it) } ?: "还没有备份过",
                    leading = { IconTile(Icons.Outlined.Backup, palette.group(0)) },
                    onClick = { if (tree == null) pickTree.launch(null) else vm.backupNow() },
                )
                RowDivider(startIndent = 68.dp)
                ListRow(
                    title = "从备份恢复",
                    subtitle = "整库覆盖，恢复前自动另存当前数据",
                    leading = { IconTile(Icons.Outlined.Restore, palette.group(2)) },
                    onClick = { pickBackup.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                )
                RowDivider(startIndent = 68.dp)
                ListRow(
                    title = "恢复前副本",
                    subtitle = "每次恢复前自动另存的数据，点一下就能回去",
                    leading = { IconTile(Icons.Outlined.Layers, palette.group(4)) },
                    onClick = onPreRestore,
                )
                Text(
                    "备份包含资产、待办（含回收站和日志）、日记和草稿。每天自动备份一次，和手动备份一样写进同一个文件 asset-board-backup.json，新的覆盖旧的，目录里不会越积越多。手机厂商的后台限制可能让自动备份推迟，改完数据后建议手动点一次「立即备份」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
                )
            }

            SectionCard {
                SectionHeader("待办与日记")
                ListRow(
                    title = "提醒设置检查",
                    subtitle = "通知、精确闹钟、全屏、vivo 自启动与后台",
                    leading = { IconTile(Icons.Outlined.NotificationsActive, palette.group(3)) },
                    onClick = onGuide,
                )
                RowDivider(startIndent = 68.dp)
                SwitchRow(Icons.Outlined.Alarm, palette.group(2), "所有提醒走闹钟通道", "深度休眠时也准时\n状态栏会显示闹钟图标", allAlarms) {
                    settings.setBool(settings.allRemindersAsAlarms, it)
                    container.appScope.launch { runCatching { container.reminders.syncAll(fromBoot = false) } }
                }
                RowDivider(startIndent = 68.dp)
                SwitchRow(Icons.Outlined.EditNote, palette.group(5), "每晚提醒写日记", "今天已经写过就不提醒", diaryOn) {
                    settings.setBool(settings.diaryReminderEnabled, it)
                    container.reminders.scheduleDiaryReminder()
                }
                if (diaryOn) {
                    ListRow(
                        title = "提醒时间",
                        leading = { Spacer(Modifier.width(34.dp)) },
                        trailing = { Text("%02d:%02d".format(diaryAt / 60, diaryAt % 60), color = cs.onSurfaceVariant) },
                        onClick = { pickDiaryTime = true },
                    )
                }
                RowDivider(startIndent = 68.dp)
                ListRow(
                    title = "回收站",
                    subtitle = "删除的待办、清单和日记保留 30 天",
                    leading = { IconTile(Icons.Outlined.Delete, Color(0xFF8A8F98)) },
                    onClick = onTrash,
                )
            }

            SectionCard {
                SectionHeader("联网")
                SwitchRow(Icons.Outlined.Public, palette.group(1), "允许联网", "关闭后不发起任何网络请求：节假日用内置数据，不显示天气", networkOn) {
                    settings.setBool(settings.networkEnabled, it)
                }
                if (networkOn) {
                    RowDivider(startIndent = 68.dp)
                    SwitchRow(Icons.Outlined.WbSunny, palette.group(2), "日记天气", "写日记时用大致位置取当天天气（Open-Meteo）", weatherOn) {
                        settings.setBool(settings.weatherEnabled, it)
                    }
                    RowDivider(startIndent = 68.dp)
                    SwitchRow(Icons.Outlined.Event, palette.group(3), "节假日自动更新", "每年 11 月起每周检查一次新一年的放假安排，只下载不上传", holidayOn) {
                        settings.setBool(settings.holidayUpdateEnabled, it)
                    }
                    if (holidayOn) {
                        ListRow(
                            title = "立即检查节假日",
                            subtitle = holidayYears.takeIf { it.isNotEmpty() }?.let { "已有 " + it.sorted().joinToString("、") + " 年" },
                            leading = { Spacer(Modifier.width(34.dp)) },
                            onClick = vm::updateHolidays,
                        )
                    }
                }
                Text(
                    "资产、待办、日记的内容永远不会离开手机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
                )
            }

            SectionCard {
                SectionHeader("应用更新")
                ListRow(
                    title = "检查更新",
                    subtitle = updateSummary(update, updater.currentVersion),
                    leading = { IconTile(Icons.Outlined.SystemUpdate, palette.group(0)) },
                    onClick = updater::checkNow,
                )
                RowDivider(startIndent = 68.dp)
                SwitchRow(Icons.Outlined.Autorenew, palette.group(2), "自动检查", "每天第一次打开时看看有没有新版本，有才提示", autoUpdate) {
                    settings.setBool(settings.autoUpdateCheck, it)
                }
                RowDivider(startIndent = 68.dp)
                ListRow(
                    title = "下载镜像",
                    subtitle = updater.mirror.ifEmpty { "未设置，直连 GitHub" },
                    leading = { IconTile(Icons.Outlined.Speed, palette.group(5)) },
                    onClick = { editMirror = true },
                )
            }

            SectionCard {
                SectionHeader("账户")
                ListRow(
                    title = "分组管理",
                    subtitle = "名称、颜色、顺序、资产 / 负债属性",
                    leading = { IconTile(Icons.Outlined.Category, palette.group(4)) },
                    onClick = onGroups,
                )
            }

            SectionCard {
                SectionHeader("数据初始化")
                ResetScope.entries.forEachIndexed { i, scope ->
                    if (i > 0) RowDivider(startIndent = 68.dp)
                    ListRow(
                        title = scope.title,
                        subtitle = scope.subtitle,
                        leading = {
                            IconTile(if (scope == ResetScope.EVERYTHING) Icons.Outlined.RestartAlt else Icons.Outlined.History, cs.error)
                        },
                        onClick = { confirmReset = scope },
                    )
                }
                Text(
                    "账户里混进了别人的数据、又已经改成自己的余额时，用「清空资产历史」，改过的余额不会白费。执行前都会自动另存一份当前数据，可以在「恢复前副本」里一键还原。",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
                )
            }

            SectionCard(contentPadding = PaddingValues(bottom = 16.dp)) {
                SectionHeader("外观")
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                        IconTile(Icons.Outlined.Palette, palette.group(6), size = 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("主题", style = MaterialTheme.typography.bodyMedium)
                    }
                    SegmentedControl(ThemeMode.entries, theme, { it.label }, settings::setThemeMode)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                        IconTile(Icons.AutoMirrored.Outlined.TrendingUp, palette.group(3), size = 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("涨跌颜色", style = MaterialTheme.typography.bodyMedium)
                    }
                    SegmentedControl(listOf(true, false), upIsRed, { if (it) "红涨绿跌" else "绿涨红跌" }, settings::setUpIsRed)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                        IconTile(Icons.Outlined.BlurOn, palette.group(5), size = 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("卡片玻璃", style = MaterialTheme.typography.bodyMedium)
                            Text("各页的卡片：清透能看见背后的颜色，深色更沉稳、对比更高", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    SegmentedControl(listOf(SettingsStore.GLASS_CLEAR, SettingsStore.GLASS_DEEP), glassMode, { if (it == SettingsStore.GLASS_DEEP) "深色" else "清透" }, settings::setGlass)
                }
            }

        }
    }

    confirmReset?.let { scope ->
        ConfirmDialog(
            title = scope.confirmTitle,
            text = scope.confirmText + "\n\n执行前会自动另存一份当前数据，可以在「恢复前副本」里一键还原。",
            confirmLabel = scope.confirmLabel,
            destructive = true,
            onConfirm = { confirmReset = null; vm.reset(scope) },
            onDismiss = { confirmReset = null },
        )
    }
    if (editMirror) {
        MirrorDialog(updater.mirror, onDismiss = { editMirror = false }) { updater.mirror = it; editMirror = false }
    }
    if (pickDiaryTime) {
        com.assetboard.ui.todo.TimePickDialog(
            initial = java.time.LocalTime.of(diaryAt / 60, diaryAt % 60),
            onDismiss = { pickDiaryTime = false },
            onPick = {
                pickDiaryTime = false
                settings.setDiaryReminderMinutes(it.hour * 60 + it.minute)
                container.reminders.scheduleDiaryReminder()
            },
        )
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, color: Color, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListRow(
        title = title,
        subtitle = subtitle,
        leading = { IconTile(icon, color) },
        trailing = { Switch(checked, onChange) },
        onClick = { onChange(!checked) },
        chevron = false,
    )
}

/** The app at a glance: name, version, and the backup state with a one-tap backup. */
@Composable
private fun AboutCard(version: String, lastBackup: Long?, onBackup: () -> Unit) {
    val extra = AppTheme.extra
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.linearGradient(listOf(extra.heroStart, extra.heroEnd)))
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The launcher icon itself: its foreground is drawn on a 108 grid with the art inside 66,
        // so it is drawn larger than the tile and clipped to it.
        Box(
            Modifier.size(52.dp).shadow(6.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(colorResource(R.color.ic_launcher_background)),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, modifier = Modifier.requiredSize(52.dp * 108f / 66f))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("日盈", style = MaterialTheme.typography.titleLarge, color = extra.onHero)
                Text(" Gain", style = MaterialTheme.typography.titleSmall, color = extra.onHeroMuted, modifier = Modifier.padding(bottom = 2.dp))
            }
            Text("日日行动，日日有盈", style = MaterialTheme.typography.labelMedium, color = extra.onHero.copy(alpha = 0.85f))
            Text("$version · 数据只保存在本机", style = MaterialTheme.typography.bodySmall, color = extra.onHeroMuted)
            Text(
                lastBackup?.let { "上次备份 " + Dates.relative(it) + " " + Dates.time(it) } ?: "还没有备份过",
                style = MaterialTheme.typography.bodySmall, color = extra.onHeroMuted,
            )
        }
        Text(
            "备份",
            style = MaterialTheme.typography.labelLarge,
            color = extra.heroStart,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(extra.onHero).clickable(onClick = onBackup).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
