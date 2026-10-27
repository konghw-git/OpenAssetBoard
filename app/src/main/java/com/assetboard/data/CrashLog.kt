package com.assetboard.data

import com.assetboard.domain.AppClock
import com.assetboard.domain.LedgerException
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.assetboard.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A short local record of what went wrong, so a crash or a reminder that never rang leaves a
 * trace: uncaught exceptions with their stack, and (Android 11 on) the times the system ended the
 * process for a reason worth knowing, such as an ANR, low memory or a force stop. It stays in the
 * app's own files and goes nowhere by itself; 导出诊断 in settings shares it, with the version and
 * the phone, when asked. The newest [MAX_BYTES] are kept.
 */
object CrashLog {
    private const val FILE = "crash.log"
    private const val MAX_BYTES = 64 * 1024
    private const val KEY_EXIT_SEEN = "exit_reason_seen_at"
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Records an uncaught exception before the app goes down as it would have. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { append(app, "崩溃 · 线程 ${thread.name} · ${BuildConfig.VERSION_NAME}\n" + e.stackTraceToString()) }
            previous?.uncaughtException(thread, e)
        }
    }

    /** The process ends since the last start that the system reports as not a normal exit. */
    fun recordExitReasons(context: Context, settings: SettingsStore) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val infos = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 10) }.getOrNull() ?: return
        val seen = settings.long(KEY_EXIT_SEEN)
        val fresh = infos.filter { it.timestamp > seen && it.reason in Exits.NOTED }.sortedBy { it.timestamp }
        // Marked seen on disk before they are written: a crash on start comes back before an
        // asynchronous save lands, and wrote the same exits again on every start.
        infos.maxOfOrNull { it.timestamp }?.let { settings.putLong(KEY_EXIT_SEEN, it, now = true) }
        fresh.forEach { info ->
            append(context, "退出 · ${Exits.name(info.reason)}" + info.description?.let { " · $it" }.orEmpty(), info.timestamp)
        }
    }

    /** A line of its own, such as an update that failed and why. */
    fun note(context: Context, text: String) {
        runCatching { append(context.applicationContext, text) }
    }

    fun read(context: Context): String = runCatching { file(context).readText() }.getOrDefault("")

    fun clear(context: Context) {
        file(context).delete()
    }

    /**
     * Everything 导出诊断 shares: the build, the phone, the settings that bear on reminders and
     * backups (never any of the user's records), and the log. Written to a file to share, off the
     * main thread; a cache that cannot be written throws rather than taking the app down.
     */
    suspend fun diagnostics(context: Context, settings: SettingsStore): File = withContext(Dispatchers.IO) {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val text = buildString {
            appendLine("日盈 Gain 诊断信息")
            appendLine("生成于 ${time(AppClock.millis())}")
            appendLine()
            appendLine("版本 ${info?.versionName} (${info?.longVersionCode}) · ${BuildConfig.BUILD_TYPE} · commit ${BuildConfig.COMMIT_SHA}")
            appendLine("手机 ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("允许联网 ${settings.networkEnabled.value} · 天气 ${settings.weatherEnabled.value} · 提醒走闹钟 ${settings.allRemindersAsAlarms.value}")
            appendLine("备份目录 ${if (settings.backupTreeUri.value != null) "已设置" else "未设置"} · 上次备份 ${settings.lastBackupAt.value?.let(::time) ?: "无"}")
            settings.backupError.value?.let { appendLine("上次备份失败 ${time(it.at)}：${it.reason}") }
            appendLine()
            appendLine("—— 记录 ——")
            append(read(context).ifBlank { "（没有记录）\n" })
        }
        val dir = File(context.cacheDir, "diagnostics")
        if (!dir.isDirectory && !dir.mkdirs()) throw LedgerException("无法创建诊断文件")
        File(dir, "rizing-diagnostics.txt").apply { writeText(text) }
    }

    private fun file(context: Context) = File(context.filesDir, FILE)

    @Synchronized
    private fun append(context: Context, text: String, at: Long = AppClock.millis()) {
        val f = file(context)
        f.appendText("[${time(at)}] $text\n\n")
        if (f.length() > MAX_BYTES) {
            // Keep the newer half, from the start of an entry.
            val kept = f.readText().takeLast(MAX_BYTES / 2)
            f.writeText(kept.substring(kept.indexOf("\n[").let { if (it < 0) 0 else it + 1 }))
        }
    }

    private fun time(at: Long): String = stamp.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

    /** The exit reasons worth a line, and their names; Android 11 has them. */
    @RequiresApi(Build.VERSION_CODES.R)
    private object Exits {
        val NOTED = setOf(
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_USER_REQUESTED,
        )

        fun name(reason: Int): String = when (reason) {
            ApplicationExitInfo.REASON_ANR -> "无响应（ANR）"
            ApplicationExitInfo.REASON_CRASH -> "崩溃"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生崩溃"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足被系统结束"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "耗用资源过多被系统结束"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "启动失败"
            ApplicationExitInfo.REASON_SIGNALED -> "被系统信号结束（常见于后台清理）"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "被强行停止（用户或系统）"
            else -> "原因 $reason"
        }
    }
}
