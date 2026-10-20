package com.assetboard.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assetboard.BuildConfig
import com.assetboard.data.AppUpdater
import com.assetboard.data.Release
import com.assetboard.data.UpdateState
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.verticalScrollWithIndicator

/** What [UpdateState] reads as in the settings row. */
fun updateSummary(s: UpdateState, current: String): String = when (s) {
    UpdateState.Checking -> "正在检查…"
    UpdateState.UpToDate -> "当前 $current，已是最新"
    is UpdateState.Available -> "发现新版本 ${s.release.version}"
    is UpdateState.Downloading -> if (s.progress < 0) "正在下载 ${s.release.version}…" else "正在下载 ${s.release.version} · ${(s.progress * 100).toInt()}%"
    is UpdateState.Ready -> "${s.release.version} 已下载，点这里安装"
    is UpdateState.Failed -> s.message
    UpdateState.Idle -> "当前 $current"
}

/**
 * The update dialog, over whatever is on screen: from a check in settings, or on start when a
 * new version is out. Closing it while downloading lets the download go on.
 */
@Composable
fun UpdatePrompt() {
    val updater = LocalContainer.current.updater
    val show by updater.prompt.collectAsStateWithLifecycle()
    if (!show) return
    val state by updater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val openPage: (Release?) -> Unit = { r ->
        val url = r?.page ?: "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest"
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
    val s = state
    AlertDialog(
        onDismissRequest = updater::dismiss,
        title = {
            Text(
                when (s) {
                    is UpdateState.Available -> "发现新版本 ${s.release.version}"
                    is UpdateState.Downloading -> "正在下载 ${s.release.version}"
                    is UpdateState.Ready -> "${s.release.version} 可以安装了"
                    UpdateState.UpToDate -> "已是最新版本"
                    is UpdateState.Failed -> "没能完成"
                    else -> "检查更新"
                },
            )
        },
        text = { UpdateBody(s, updater) },
        confirmButton = {
            when (s) {
                is UpdateState.Available -> TextButton(onClick = { updater.download(s.release) }) { Text("下载更新") }
                is UpdateState.Downloading -> TextButton(onClick = updater::dismiss) { Text("后台下载") }
                is UpdateState.Ready -> TextButton(onClick = { updater.install(s.file) }) { Text(if (updater.canInstall()) "安装" else "去允许安装") }
                is UpdateState.Failed -> TextButton(onClick = { s.release?.let(updater::download) ?: updater.checkNow() }) { Text("重试") }
                UpdateState.Checking -> {}
                else -> TextButton(onClick = updater::dismiss) { Text("好") }
            }
        },
        dismissButton = {
            Row {
                when (s) {
                    is UpdateState.Available -> {
                        TextButton(onClick = { updater.ignore(s.release) }) { Text("跳过这版") }
                        TextButton(onClick = updater::dismiss) { Text("稍后") }
                    }
                    is UpdateState.Downloading -> TextButton(onClick = updater::cancel) { Text("取消下载") }
                    is UpdateState.Ready -> TextButton(onClick = updater::dismiss) { Text("稍后") }
                    is UpdateState.Failed -> {
                        TextButton(onClick = { openPage(s.release) }) { Text("用浏览器下载") }
                        TextButton(onClick = updater::dismiss) { Text("关闭") }
                    }
                    UpdateState.Checking -> TextButton(onClick = updater::cancel) { Text("取消") }
                    else -> {}
                }
            }
        },
    )
}

@Composable
private fun UpdateBody(s: UpdateState, updater: AppUpdater) {
    val cs = MaterialTheme.colorScheme
    val current = updater.currentVersion
    when (s) {
        UpdateState.Checking, UpdateState.Idle -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("正在查看最新版本…")
        }
        UpdateState.UpToDate -> Text("当前 $current 就是最新的。")
        is UpdateState.Available -> Column {
            Text(
                "$current → ${s.release.version}" + (s.release.size.takeIf { it > 0 }?.let { " · " + megabytes(it) } ?: ""),
                style = MaterialTheme.typography.labelLarge, color = cs.primary,
            )
            val notes = releaseNotes(s.release.notes)
            if (notes.isNotEmpty()) {
                Text(
                    notes,
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp).heightIn(max = 260.dp).verticalScrollWithIndicator(rememberScrollState()),
                )
            }
            Text("覆盖安装，数据都会保留。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        }
        is UpdateState.Downloading -> Column {
            if (s.progress < 0) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator({ s.progress }, Modifier.fillMaxWidth())
            Text(
                (if (s.progress < 0) "" else "${(s.progress * 100).toInt()}% · ") + "可以先关掉这个窗口，下载会在后台继续",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
            )
        }
        is UpdateState.Ready -> Column {
            Text("安装包已校验：是本应用、版本更新、签名一致。")
            Text(
                "安装前会先备份一次（已设置备份目录时），再打开系统的安装界面。" +
                    if (!updater.canInstall()) "\n\n第一次需要在系统页面里允许本应用「安装未知应用」，允许后返回再点安装。" else "",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
        }
        is UpdateState.Failed -> Column {
            Text(s.message)
            Text(
                "也可以用浏览器打开发布页下载安装包；下载慢可以在设置 › 应用更新里填一个下载镜像。",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun megabytes(bytes: Long): String = "%.1f MB".format(bytes / 1_048_576.0)

/** The release's Markdown as plain lines: headings and emphasis marks dropped, list items as bullets. */
internal fun releaseNotes(md: String): String = md.lineSequence()
    .map { it.trimEnd() }
    .map { l ->
        val t = l.trimStart()
        when {
            t.startsWith("#") -> t.trimStart('#').trim()
            t.startsWith("- ") || t.startsWith("* ") -> "• " + t.drop(2)
            else -> l
        }.replace("**", "").replace("`", "")
    }
    .joinToString("\n")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()

/** A prefix put in front of the download URL, for networks where GitHub downloads crawl. Empty for none. */
@Composable
fun MirrorDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("下载镜像") },
        text = {
            Column {
                Text(
                    "填一个 GitHub 加速地址的前缀（以 https:// 开头、以 / 结尾），下载地址会接在它后面。留空就直连 GitHub。安装前会校验签名，镜像改不了安装包。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    text, { text = it.trim() }, singleLine = true,
                    placeholder = { Text("https://example.com/") },
                    isError = text.isNotEmpty() && !text.startsWith("https://"),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isEmpty() || text.startsWith("https://")) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
