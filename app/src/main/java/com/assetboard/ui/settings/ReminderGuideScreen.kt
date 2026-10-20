package com.assetboard.ui.settings

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.assetboard.ui.components.GlassScaffold
import com.assetboard.ui.components.glassBarColors
import com.assetboard.reminder.ReminderScheduler
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.SectionCard
import com.assetboard.ui.components.verticalScrollWithIndicator

private enum class Check { OK, MISSING, UNKNOWN }

/** Opens the first of [intents] that resolves, else the app's own settings page. */
private fun Context.openFirst(vararg intents: Intent) {
    for (i in intents) {
        val ok = runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (ok) return
    }
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Walks through everything a reminder needs on a vivo phone. The system parts are checked
 * live; the vivo-specific ones cannot be read by an app, so they only link to the right page.
 */
@Composable
fun ReminderGuideScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val reminders = container.reminders
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        container.settings.setBool(container.settings.reminderGuideSeen, true)
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { tick++ }
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val pkg = context.packageName
    val cs = MaterialTheme.colorScheme

    // Re-read every time the screen resumes (returning from a settings page).
    val (notif, exact, fullScreen, battery) = remember(tick) {
        listOf(
            if (ReminderScheduler.hasNotificationPermission(context) && reminders.notificationsEnabled()) Check.OK else Check.MISSING,
            if (reminders.exactAllowed()) Check.OK else Check.MISSING,
            if (reminders.fullScreenAllowed()) Check.OK else Check.MISSING,
            if (context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg)) Check.OK else Check.UNKNOWN,
        )
    }

    GlassScaffold(
        topBar = {
            TopAppBar(
                title = { Text("提醒设置检查") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                colors = glassBarColors(),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScrollWithIndicator(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "vivo 会主动清理后台。按下面逐项打开，提醒才能准时；做完后用最下面的按钮各测一次。",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp),
            )
            Step("1. 通知权限", "没有它，任何提醒都无法显示。", notif) {
                if (Build.VERSION.SDK_INT >= 33 && !ReminderScheduler.hasNotificationPermission(context)) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.openFirst(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
            }
            Step("2. 精确闹钟", "让提醒在设定的那一分钟响，而不是被推迟。", exact) {
                if (Build.VERSION.SDK_INT >= 31) context.openFirst(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg")))
            }
            Step("3. 全屏通知", "强提醒要在锁屏上全屏显示，需要这个权限。", fullScreen) {
                if (Build.VERSION.SDK_INT >= 34) context.openFirst(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$pkg")))
            }
            Step("4. 自启动", "i 管家 → 应用管理 → 自启动，打开「日盈」。重启后才能补发提醒。", Check.UNKNOWN) {
                context.openFirst(
                    Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
                    Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),
                    Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
                )
            }
            Step("5. 后台耗电", "设置 → 电池 → 后台耗电管理 → 日盈 → 允许后台高耗电。", battery) {
                context.openFirst(
                    Intent().setComponent(ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")),
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                )
            }
            Step("6. 锁屏显示", "应用详情 → 权限 → 锁屏显示，打开后强提醒能在锁屏上亮出来。", Check.UNKNOWN) {
                context.openFirst(
                    Intent("permission.intent.action.softPermissionDetail").putExtra("packagename", pkg),
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
                )
            }
            SectionCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
                Text("测试", style = MaterialTheme.typography.titleSmall)
                Text("点按钮后锁屏等 15 秒，看提醒是否按时出现。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { reminders.scheduleTest(strong = false) }, modifier = Modifier.weight(1f)) { Text("测试普通提醒") }
                    Button(onClick = { reminders.scheduleTest(strong = true) }, modifier = Modifier.weight(1f)) { Text("测试强提醒") }
                }
            }
        }
    }
}

@Composable
private fun Step(title: String, body: String, check: Check, onFix: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val (icon: ImageVector, tint) = when (check) {
        Check.OK -> Icons.Default.CheckCircle to cs.primary
        Check.MISSING -> Icons.Outlined.WarningAmber to cs.error
        Check.UNKNOWN -> Icons.Outlined.Info to cs.onSurfaceVariant
    }
    SectionCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 10.dp).weight(1f))
            TextButton(onClick = onFix) { Text(if (check == Check.OK) "查看" else "去设置") }
        }
        Text(body, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 30.dp, end = 12.dp, bottom = 6.dp))
    }
}
