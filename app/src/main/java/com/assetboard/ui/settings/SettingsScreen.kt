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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
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
import androidx.compose.material.icons.outlined.BugReport
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
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.assetboard.ui.components.GlassScaffold
import com.assetboard.ui.components.glassBarColors
import com.assetboard.ui.components.GlassSnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.paint
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assetboard.AppContainer
import com.assetboard.BuildConfigInfo
import com.assetboard.data.AppUpdater
import com.assetboard.data.BackupKind
import com.assetboard.data.ThemeMode
import com.assetboard.data.SettingsStore
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Widgets
import com.assetboard.domain.Dates
import com.assetboard.ui.ActionViewModel
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.SegmentedControl
import com.assetboard.ui.components.appear
import com.assetboard.ui.components.rememberBackupProblem
import androidx.compose.material3.TextButton
import com.assetboard.ui.components.ConfirmDialog
import com.assetboard.ui.components.ErrorDialog
import com.assetboard.ui.components.IconTile
import com.assetboard.ui.components.heroSurface
import com.assetboard.ui.components.ListRow
import com.assetboard.ui.components.RowDivider
import com.assetboard.ui.components.SectionCard
import com.assetboard.ui.components.SectionHeader
import com.assetboard.ui.components.tilt
import com.assetboard.ui.theme.AppTheme
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.assetboard.ui.components.verticalScrollWithIndicator

class SettingsViewModel(container: AppContainer) : RestoreViewModel(container) {
    val message = MutableStateFlow<String?>(null)

    fun backupNow() = action { message.value = "已备份，覆盖了 " + container.backup.backupNow(BackupKind.MANUAL) }

    fun updateHolidays() = action {
        val updated = container.holidays.updateOnline()
        message.value = if (updated) "节假日数据已检查" else "没能更新节假日数据，请检查网络后再试"
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

/** Where a divider starts under a row with an [IconTile]: the row's padding, the tile and the gap. */
private val RowIndent = 64.dp

/**
 * The settings, most used first: appearance at the top, the about card at the bottom. The cards
 * arrive as a cascade, and each row is as low as its text allows.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onGroups: () -> Unit,
    onTrash: () -> Unit = {},
    onPreRestore: () -> Unit = {},
    onGuide: () -> Unit = {},
    onSyncAccount: () -> Unit = {},
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
    val account by container.account.state.collectAsStateWithLifecycle()
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
    val mirror by updater.mirrorState.collectAsStateWithLifecycle()
    val mirrorSpeeds by updater.mirrorSpeeds.collectAsStateWithLifecycle()
    var editMirror by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf<ResetScope?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Appearance first: the one card most people come back to, and three choices on three rows.
            SectionCard(Modifier.appear(0), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 6.dp)) {
                SectionHeader("外观")
                ChoiceRow(Icons.Outlined.Palette, palette.group(6), "主题", null, 196.dp) {
                    SegmentedControl(ThemeMode.entries, theme, { it.label }, settings::setThemeMode, height = 26.dp, textStyle = MaterialTheme.typography.labelMedium)
                }
                RowDivider(startIndent = RowIndent)
                ChoiceRow(Icons.AutoMirrored.Outlined.TrendingUp, palette.group(3), "涨跌颜色", null, 150.dp) {
                    SegmentedControl(listOf(true, false), upIsRed, { if (it) "红涨绿跌" else "绿涨红跌" }, settings::setUpIsRed, height = 26.dp, textStyle = MaterialTheme.typography.labelMedium)
                }
                RowDivider(startIndent = RowIndent)
                ChoiceRow(Icons.Outlined.BlurOn, palette.group(5), "卡片玻璃", "清透见底色，深色更沉稳", 116.dp) {
                    SegmentedControl(
                        listOf(SettingsStore.GLASS_CLEAR, SettingsStore.GLASS_DEEP), glassMode,
                        { if (it == SettingsStore.GLASS_DEEP) "深色" else "清透" }, settings::setGlass,
                        height = 26.dp, textStyle = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            SectionCard(Modifier.appear(1)) {
                SectionHeader("备份")
                // What put the red dot on the tabs' settings button, and the way out of it.
                rememberBackupProblem()?.let { problem ->
                    val failure by settings.backupError.collectAsStateWithLifecycle()
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 4.dp).fillMaxWidth().clip(MaterialTheme.shapes.medium)
                            .background(cs.errorContainer).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(problem, style = MaterialTheme.typography.bodyMedium, color = cs.onErrorContainer)
                            failure?.let { Text(Dates.dateTime(it.at), style = MaterialTheme.typography.labelSmall, color = cs.onErrorContainer.copy(alpha = 0.75f)) }
                        }
                        val lost = problem.startsWith("备份目录的授权")
                        TextButton(onClick = { if (lost || tree == null) pickTree.launch(null) else vm.backupNow() }) {
                            Text(if (lost) "重新选择" else "立即备份", color = cs.onErrorContainer)
                        }
                    }
                }
                ListRow(
                    title = "备份目录",
                    subtitle = tree?.let(::readableTree) ?: "未设置。建议选「文档」下新建的文件夹，卸载后仍保留",
                    leading = { IconTile(Icons.Outlined.Folder, palette.group(1)) },
                    onClick = { pickTree.launch(null) },
                )
                RowDivider(startIndent = RowIndent)
                ListRow(
                    title = "立即备份",
                    subtitle = lastBackup?.let { "上次备份 " + Dates.dateTime(it) } ?: "还没有备份过",
                    leading = { IconTile(Icons.Outlined.Backup, palette.group(0)) },
                    onClick = { if (tree == null) pickTree.launch(null) else vm.backupNow() },
                )
                RowDivider(startIndent = RowIndent)
                ListRow(
                    title = "从备份恢复",
                    subtitle = "整库覆盖，恢复前自动另存当前数据",
                    leading = { IconTile(Icons.Outlined.Restore, palette.group(2)) },
                    onClick = { pickBackup.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                )
                RowDivider(startIndent = RowIndent)
                ListRow(
                    title = "恢复前副本",
                    subtitle = "每次恢复前自动另存的数据，点一下就能回去",
                    leading = { IconTile(Icons.Outlined.Layers, palette.group(4)) },
                    onClick = onPreRestore,
                )
                Footnote("每天自动备份一次，和手动备份写进同一个文件 asset-board-backup.json，新的覆盖旧的。厂商的后台限制可能让自动备份推迟，改完数据后建议手动点一次「立即备份」。")
            }

            SectionCard(Modifier.appear(2)) {
                SectionHeader("账号与同步")
                if (account.signedIn) {
                    ListRow(
                        title = account.username ?: "已登录",
                        subtitle = if (account.conflict != null) {
                            "有冲突需要处理"
                        } else {
                            "云端版本 ${account.revision}" +
                                (account.lastSyncAt?.let { " · 上次同步 " + Dates.relative(it) } ?: " · 还没有同步过")
                        },
                        leading = { IconTile(Icons.Outlined.Person, palette.group(4)) },
                        onClick = onSyncAccount,
                    )
                    RowDivider(startIndent = RowIndent)
                    ListRow(
                        title = "立即同步",
                        subtitle = when {
                            account.busy -> "同步中…"
                            !networkOn -> "已关闭联网，无法同步"
                            else -> "上传本机改动，或下载云端的新版本"
                        },
                        leading = { IconTile(Icons.Outlined.Sync, palette.group(1)) },
                        onClick = { if (networkOn) container.appScope.launch { container.account.syncNow() } },
                    )
                } else {
                    ListRow(
                        title = "登录账号",
                        subtitle = "登录后同步到你自己的服务器；不登录只在本机",
                        leading = { IconTile(Icons.Outlined.Person, palette.group(4)) },
                        onClick = onSyncAccount,
                    )
                }
                account.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.error,
                        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 4.dp),
                    )
                }
                Footnote("账号由服务器管理员创建，没有注册；登录后可在账号页修改密码，忘了密码只能请管理员在服务器上重置。")
            }

            SectionCard(Modifier.appear(3)) {
                SectionHeader("待办与日记")
                ListRow(
                    title = "提醒设置检查",
                    subtitle = "通知、精确闹钟、全屏、vivo 自启动与后台",
                    leading = { IconTile(Icons.Outlined.NotificationsActive, palette.group(3)) },
                    onClick = onGuide,
                )
                RowDivider(startIndent = RowIndent)
                SwitchRow(Icons.Outlined.Alarm, palette.group(2), "所有提醒走闹钟通道", "深度休眠时也准时，状态栏会显示闹钟图标", allAlarms) {
                    settings.setBool(settings.allRemindersAsAlarms, it)
                    container.appScope.launch { runCatching { container.reminders.syncAll(fromBoot = false) } }
                }
                RowDivider(startIndent = RowIndent)
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
                RowDivider(startIndent = RowIndent)
                ListRow(
                    title = "倒数日",
                    subtitle = "生日、纪念日、考试，显示在桌面小组件上",
                    leading = { IconTile(Icons.Outlined.HourglassTop, palette.group(3)) },
                    onClick = { context.startActivity(com.assetboard.WidgetSheetActivity.intent(context, com.assetboard.WidgetSheetActivity.COUNTDOWN_LIST)) },
                )
                RowDivider(startIndent = RowIndent)
                ListRow(
                    title = "桌面小组件",
                    subtitle = "待办、笔记、倒数日、资产看板，在桌面上直接改",
                    leading = { IconTile(Icons.Outlined.Widgets, palette.group(0)) },
                    onClick = { context.startActivity(com.assetboard.WidgetSheetActivity.intent(context, com.assetboard.WidgetSheetActivity.WIDGET_GALLERY)) },
                )
                RowDivider(startIndent = RowIndent)
                ListRow(
                    title = "回收站",
                    subtitle = "删除的待办、清单和日记保留 30 天",
                    leading = { IconTile(Icons.Outlined.Delete, Color(0xFF8A8F98)) },
                    onClick = onTrash,
                )
            }

            SectionCard(Modifier.appear(4)) {
                SectionHeader("联网")
                SwitchRow(Icons.Outlined.Public, palette.group(1), "允许联网", "关闭后不发起任何网络请求：节假日用内置数据，不显示天气", networkOn) {
                    settings.setBool(settings.networkEnabled, it)
                }
                if (networkOn) {
                    RowDivider(startIndent = RowIndent)
                    SwitchRow(Icons.Outlined.WbSunny, palette.group(2), "日记天气", "写日记时用大致位置取当天天气（Open-Meteo）", weatherOn) {
                        settings.setBool(settings.weatherEnabled, it)
                    }
                    RowDivider(startIndent = RowIndent)
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
                Footnote("不登录账号时，资产、待办、日记只在本机；登录后整份数据会同步到你自己的服务器。")
            }

            SectionCard(Modifier.appear(5)) {
                SectionHeader("应用更新")
                ListRow(
                    title = "检查更新",
                    subtitle = updateSummary(update, updater.currentVersion),
                    leading = { IconTile(Icons.Outlined.SystemUpdate, palette.group(0)) },
                    onClick = updater::checkNow,
                )
                RowDivider(startIndent = RowIndent)
                SwitchRow(Icons.Outlined.Autorenew, palette.group(2), "自动检查", "每天第一次打开时看看有没有新版本，有才提示", autoUpdate) {
                    settings.setBool(settings.autoUpdateCheck, it)
                }
                RowDivider(startIndent = RowIndent)
                ListRow(
                    title = "下载镜像",
                    subtitle = AppUpdater.mirrorName(mirror) + (mirrorSpeeds[mirror]?.takeIf { it >= 0 }?.let { " · $it ms" } ?: "") +
                        if (mirror.isEmpty()) " · 国内慢可以测速选一个" else "",
                    leading = { IconTile(Icons.Outlined.Speed, palette.group(5)) },
                    onClick = { editMirror = true },
                )
                RowDivider(startIndent = RowIndent)
                ListRow(
                    title = "导出诊断",
                    subtitle = "版本、机型和崩溃记录，不含你的数据，可发给开发者排查",
                    leading = { IconTile(Icons.Outlined.BugReport, palette.group(3)) },
                    onClick = {
                        // Off the main thread, and a cache that cannot be written says so.
                        scope.launch {
                            runCatching { shareDiagnostics(context, settings) }
                                .onFailure { snackbar.showSnackbar("无法生成诊断文件：${it.message ?: "写入失败"}") }
                        }
                    },
                )
            }

            SectionCard(Modifier.appear(6)) {
                SectionHeader("账户")
                ListRow(
                    title = "分组管理",
                    subtitle = "名称、颜色、顺序、资产 / 负债属性",
                    leading = { IconTile(Icons.Outlined.Category, palette.group(4)) },
                    onClick = onGroups,
                )
            }

            SectionCard(Modifier.appear(7)) {
                SectionHeader("数据初始化")
                ResetScope.entries.forEachIndexed { i, scope ->
                    if (i > 0) RowDivider(startIndent = RowIndent)
                    ListRow(
                        title = scope.title,
                        subtitle = scope.subtitle,
                        leading = {
                            IconTile(if (scope == ResetScope.EVERYTHING) Icons.Outlined.RestartAlt else Icons.Outlined.History, cs.error)
                        },
                        onClick = { confirmReset = scope },
                    )
                }
                Footnote("账户里混进了别人的数据、又已经改成自己的余额时，用「清空资产历史」。执行前都会自动另存一份当前数据，可在「恢复前副本」里一键还原。")
            }

            AboutCard(BuildConfigInfo.versionName(context), lastBackup, Modifier.appear(8), onBackup = { if (tree == null) pickTree.launch(null) else vm.backupNow() })
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
        MirrorDialog(updater) { editMirror = false }
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

/** The diagnostics as a text file, to whatever the user shares it with. */
private suspend fun shareDiagnostics(context: android.content.Context, settings: SettingsStore) {
    val file = com.assetboard.data.CrashLog.diagnostics(context, settings)
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "日盈诊断信息")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "导出诊断"))
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

/** A choice on one row: the icon and name at the start, the segmented [control] at the end, [controlWidth] wide. */
@Composable
private fun ChoiceRow(icon: ImageVector, color: Color, title: String, subtitle: String?, controlWidth: Dp, control: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, color, size = 30.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(controlWidth)) { control() }
    }
}

/** The small print under a card's rows. */
@Composable
private fun Footnote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 8.dp),
    )
}

/** The app at a glance: name, version, and the backup state with a one-tap backup. */
@Composable
private fun AboutCard(version: String, lastBackup: Long?, modifier: Modifier = Modifier, onBackup: () -> Unit) {
    val extra = AppTheme.extra
    Row(
        modifier
            .fillMaxWidth()
            .tilt()
            .heroSurface(MaterialTheme.shapes.extraLarge, extra)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The launcher icon itself: its foreground is drawn on a 108 grid with the art inside 66,
        // so it is drawn larger than the tile and clipped to it.
        Box(
            Modifier.size(52.dp).shadow(6.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
                .paint(painterResource(R.drawable.ic_launcher_background), contentScale = ContentScale.FillBounds),
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
            Text("$version · 数据默认只保存在本机", style = MaterialTheme.typography.bodySmall, color = extra.onHeroMuted)
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
