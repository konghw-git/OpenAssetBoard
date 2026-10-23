package com.assetboard.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.assetboard.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A published version: its number, what changed, and where its APK is. */
data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long, val page: String)

/** A GitHub download accelerator: [prefix] goes in front of the release URL; empty for GitHub itself. */
data class Mirror(val name: String, val prefix: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    /** [progress] is 0..1, or below zero while the size is unknown. */
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    /** [backupFailed] is why the backup before installing did not work; the install waited for the user. */
    data class Ready(val release: Release, val file: File, val backupFailed: String? = null) : UpdateState
    data class Failed(val message: String, val release: Release? = null) : UpdateState
}

@Serializable
private data class GhAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
)

@Serializable
private data class GhRelease(
    @SerialName("tag_name") val tag: String,
    val body: String? = null,
    @SerialName("html_url") val page: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
)

/** Version names as "0.8.0" or "v0.8.0"; a missing part counts as zero, a suffix is ignored. */
object Versions {
    private fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").substringBefore('-').split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    fun compare(a: String, b: String): Int {
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0
}

/**
 * Updates from the project's GitHub releases: the latest release's APK is downloaded into the
 * app's cache, checked (this app, a newer version, the same signing key as the installed one) and
 * handed to the system installer, which keeps the data as an ordinary upgrade does. Nothing goes
 * out but the two requests; with networking off in settings nothing goes out at all. A mirror,
 * when set, is put in front of the download URL; the signature check is what makes that safe.
 */
class AppUpdater(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    /** Saved before the installer opens, so an upgrade never starts without a fresh backup. */
    private val backup: suspend () -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Whether the update dialog is up: always after a manual check, after an automatic one only for news. */
    val prompt = MutableStateFlow(false)
    private var job: Job? = null

    val currentVersion: String get() = BuildConfig.VERSION_NAME.removeSuffix("-debug")

    /** At most once a day, quietly: only a version not set aside brings the dialog up. */
    fun checkAuto() {
        if (!settings.networkEnabled.value || !settings.autoUpdateCheck.value) return
        val now = System.currentTimeMillis()
        if (now - settings.long(KEY_LAST_CHECK) < 20 * 3600_000L) return
        settings.putLong(KEY_LAST_CHECK, now)
        launchCheck { r -> r != null && r.version != settings.string(KEY_IGNORED) }
    }

    fun checkNow() {
        prompt.value = true
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Ready) return
        if (!settings.networkEnabled.value) {
            _state.value = UpdateState.Failed("联网已关闭，可以在设置 › 网络里打开")
            return
        }
        launchCheck { true }
    }

    private fun launchCheck(show: (Release?) -> Boolean) {
        if (job?.isActive == true) return
        _state.value = UpdateState.Checking
        job = scope.launch {
            val next = try {
                val r = latest()
                if (r != null && Versions.isNewer(r.version, currentVersion)) UpdateState.Available(r) else UpdateState.UpToDate
            } catch (e: Exception) {
                UpdateState.Failed("检查失败：${reason(e)}")
            }
            _state.value = next
            if (show((next as? UpdateState.Available)?.release)) prompt.value = true
        }
    }

    private suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val text = get(URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")) { it.readBytes().decodeToString() }
        val r = json.decodeFromString(GhRelease.serializer(), text)
        if (r.draft || r.prerelease) return@withContext null
        val apk = r.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return@withContext null
        Release(r.tag.removePrefix("v"), r.body.orEmpty().trim(), apk.url, apk.size, r.page)
    }

    fun ignore(r: Release) {
        settings.putString(KEY_IGNORED, r.version)
        dismiss()
    }

    fun dismiss() {
        prompt.value = false
        if (_state.value !is UpdateState.Downloading && _state.value !is UpdateState.Ready) _state.value = UpdateState.Idle
    }

    fun download(r: Release) {
        if (job?.isActive == true) return
        if (!settings.networkEnabled.value) {
            _state.value = UpdateState.Failed("联网已关闭，可以在设置 › 联网里打开", r)
            return
        }
        _state.value = UpdateState.Downloading(r, if (r.size > 0) 0f else -1f)
        val run = scope.launch {
            _state.value = try {
                val file = fetch(r)
                verify(file)
                UpdateState.Ready(r, file)
            } catch (e: CancellationException) {
                // The user cancelled: cancel() already put the state back.
                throw e
            } catch (e: Exception) {
                UpdateState.Failed("下载失败：${reason(e)}", r)
            }
        }
        job = run
    }

    fun cancel() {
        job?.cancel()
        _state.value = UpdateState.Idle
        prompt.value = false
    }

    private suspend fun fetch(r: Release): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, "OpenAssetBoard-${r.version}.apk.part")
        val mirror = normalizeMirror(settings.string(KEY_MIRROR).orEmpty())
        get(URL(mirror + r.apkUrl), timeoutMs = 30_000) { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                var shown = -1
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    val pct = if (r.size > 0) (total * 100 / r.size).toInt() else -1
                    if (pct != shown) {
                        shown = pct
                        _state.value = UpdateState.Downloading(r, if (pct < 0) -1f else pct / 100f)
                    }
                }
                if (r.size > 0 && total != r.size) throw IOException("文件不完整")
            }
        }
        File(dir, part.name.removeSuffix(".part")).also { if (!part.renameTo(it)) throw IOException("无法保存安装包") }
    }

    /** This app, newer than what runs, and signed with the same key: anything else is not installed. */
    private fun verify(file: File) {
        val pm = context.packageManager
        val apk = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: throw IOException("安装包已损坏")
        if (apk.packageName != context.packageName) {
            throw IOException(if (BuildConfig.DEBUG) "调试版不能在线升级到正式版" else "这不是本应用的安装包")
        }
        val mine = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (apk.longVersionCode <= mine.longVersionCode) throw IOException("安装包的版本不比当前新")
        val theirs = signers(apk) ?: throw IOException("安装包没有签名信息，已拒绝安装")
        if (theirs != signers(mine)) throw IOException("安装包的签名与当前应用不一致，已拒绝安装")
    }

    private fun signers(p: PackageInfo): Set<String>? =
        p.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()

    /**
     * Backs up first when a backup folder is set, then opens the system installer (or, the first
     * time, the permission page). A failed backup stops there and says why; [skipBackup] is the
     * user going on without one.
     */
    fun install(file: File, skipBackup: Boolean = false) {
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        scope.launch {
            if (!skipBackup && settings.backupTreeUri.value != null) {
                val failed = runCatching { backup() }.exceptionOrNull()
                if (failed is CancellationException) throw failed
                if (failed != null) {
                    val reason = failed.message ?: failed.javaClass.simpleName
                    _state.value = (_state.value as? UpdateState.Ready)?.copy(backupFailed = reason) ?: UpdateState.Failed("安装前没能备份：$reason")
                    prompt.value = true
                    return@launch
                }
            }
            (_state.value as? UpdateState.Ready)?.takeIf { it.backupFailed != null }?.let { _state.value = it.copy(backupFailed = null) }
            // Android 10+ drops an activity started from the background: the installer opens on the
            // main thread, and only while the app is in front.
            withContext(Dispatchers.Main.immediate) {
                if (!inForeground()) {
                    _state.value = UpdateState.Failed("备份已完成。回到 app 后再点一次安装", downloading())
                    return@withContext
                }
                try {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (e: Exception) {
                    _state.value = UpdateState.Failed("无法打开系统安装器：" + (e.message ?: e.javaClass.simpleName), downloading())
                }
            }
        }
    }

    /** Shows [s] in the dialog as if a check had led there; for screenshots. */
    @androidx.annotation.VisibleForTesting
    internal fun present(s: UpdateState) {
        _state.value = s
        prompt.value = true
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    var mirror: String
        get() = settings.string(KEY_MIRROR).orEmpty()
        set(value) {
            settings.putString(KEY_MIRROR, normalizeMirror(value).ifEmpty { null })
            _mirrorState.value = normalizeMirror(value)
        }

    private val _mirrorState = MutableStateFlow(settings.string(KEY_MIRROR).orEmpty())
    /** The mirror in use, for the settings row to follow. */
    val mirrorState: StateFlow<String> = _mirrorState.asStateFlow()

    /** Each mirror's last probe, by prefix: milliseconds to the first 64 KB, or -1 for one that failed. */
    private val _mirrorSpeeds = MutableStateFlow<Map<String, Long>>(emptyMap())
    val mirrorSpeeds: StateFlow<Map<String, Long>> = _mirrorSpeeds.asStateFlow()
    private val _testingMirrors = MutableStateFlow(false)
    val testingMirrors: StateFlow<Boolean> = _testingMirrors.asStateFlow()

    /**
     * Times a small download through GitHub itself and every known mirror at once, filling
     * [mirrorSpeeds] as each answers, and makes the fastest the mirror in use. [onDone] gets its
     * prefix, or null when nothing answered (or networking is off).
     */
    fun testMirrors(onDone: (String?) -> Unit = {}) {
        if (_testingMirrors.value) return
        if (!settings.networkEnabled.value) {
            onDone(null)
            return
        }
        _testingMirrors.value = true
        _mirrorSpeeds.value = emptyMap()
        scope.launch {
            // The release's own APK where one is known; a small file from the repository otherwise.
            val probe = downloading()?.apkUrl ?: "https://github.com/${BuildConfig.UPDATE_REPO}/raw/main/README.md"
            val results = withContext(Dispatchers.IO) {
                coroutineScope {
                    MIRRORS.map { m ->
                        async {
                            val ms = probeMirror(m.prefix + probe)
                            _mirrorSpeeds.value = _mirrorSpeeds.value + (m.prefix to ms)
                            m.prefix to ms
                        }
                    }.awaitAll()
                }
            }.toMap()
            val best = results.filterValues { it >= 0 }.minByOrNull { it.value }?.key
            if (best != null) mirror = best
            _testingMirrors.value = false
            onDone(best)
        }
    }

    /** Milliseconds to fetch the first 64 KB of [url], or -1 when it fails or takes too long. */
    private fun probeMirror(url: String): Long {
        val start = System.nanoTime()
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 6_000
                c.readTimeout = 6_000
                c.instanceFollowRedirects = true
                c.setRequestProperty("Range", "bytes=0-65535")
                c.setRequestProperty("User-Agent", "OpenAssetBoard/$currentVersion")
                if (c.responseCode !in 200..299) return -1
                c.inputStream.use { input ->
                    val buf = ByteArray(16 * 1024)
                    var total = 0
                    while (total < 65_536) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                    }
                }
                (System.nanoTime() - start) / 1_000_000
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            -1
        }
    }

    private inline fun <T> get(url: URL, timeoutMs: Int = 15_000, read: (java.io.InputStream) -> T): T {
        val c = url.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = true
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "OpenAssetBoard/$currentVersion")
            val code = c.responseCode
            if (code !in 200..299) throw IOException(if (code == 403 || code == 429) "请求太频繁，稍后再试" else "服务器返回 $code")
            return c.inputStream.use(read)
        } finally {
            c.disconnect()
        }
    }

    /** A prefix for the release URL: https only, and ending in a slash so it simply replaces the host. */
    private fun normalizeMirror(raw: String): String {
        val v = raw.trim()
        if (v.isEmpty()) return ""
        return if (v.endsWith("/")) v else "$v/"
    }

    /** Whether any activity is resumed; starting the installer from the background would be dropped. */
    private fun inForeground(): Boolean {
        val manager = context.getSystemService(android.app.ActivityManager::class.java) ?: return true
        return manager.runningAppProcesses.orEmpty().any {
            // A visible activity: a foreground service alone does not let the installer start.
            it.pid == android.os.Process.myPid() && it.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    /** The release this install is for, for a failure after the download. */
    private fun downloading(): Release? = when (val s = _state.value) {
        is UpdateState.Ready -> s.release
        is UpdateState.Downloading -> s.release
        is UpdateState.Failed -> s.release
        is UpdateState.Available -> s.release
        else -> null
    }

    private fun reason(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "连不上网络"
        is java.net.SocketTimeoutException -> "连接超时，可以换个网络或设置下载镜像"
        is IOException -> e.message ?: "网络错误"
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        private const val DIR = "updates"
        private const val KEY_LAST_CHECK = "update_last_check"
        private const val KEY_IGNORED = "update_ignored"
        private const val KEY_MIRROR = "update_mirror"

        /** GitHub itself, then accelerators in common use on the mainland; the speed test sorts them out. */
        val MIRRORS = listOf(
            Mirror("直连 GitHub", ""),
            Mirror("ghfast.top", "https://ghfast.top/"),
            Mirror("gh-proxy.com", "https://gh-proxy.com/"),
            Mirror("ghproxy.net", "https://ghproxy.net/"),
            Mirror("hub.gitmirror.com", "https://hub.gitmirror.com/"),
            Mirror("github.moeyy.xyz", "https://github.moeyy.xyz/"),
            Mirror("gh.llkk.cc", "https://gh.llkk.cc/"),
        )

        /** What a mirror prefix reads as: its name when known, its host otherwise. */
        fun mirrorName(prefix: String): String =
            MIRRORS.firstOrNull { it.prefix == prefix }?.name ?: prefix.removePrefix("https://").removePrefix("http://").trimEnd('/')
    }
}
