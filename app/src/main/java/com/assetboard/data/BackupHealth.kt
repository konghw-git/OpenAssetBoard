package com.assetboard.data

import android.content.Context
import androidx.core.net.toUri

/**
 * What the settings entry warns about, if anything. With a backup folder chosen: the folder's
 * permission gone (a reinstall, the folder moved, the system tidying up), the last backup failed,
 * or none has worked for [STALE_DAYS] days (the daily job held back by the phone). Without a
 * folder there is nothing to watch; the backup card says so itself.
 */
object BackupHealth {
    const val STALE_DAYS = 3
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    fun problem(context: Context, tree: String?, lastAt: Long?, failure: BackupFailure?, now: Long): String? {
        if (tree == null) return null
        if (!granted(context, tree)) return "备份目录的授权已失效，请重新选择目录"
        failure?.let { return "备份失败：" + it.reason }
        val days = lastAt?.let { (now - it) / DAY_MS } ?: return null
        return if (days >= STALE_DAYS) "已经 $days 天没有备份成功" else null
    }

    /** Whether the folder can still be written; a check that cannot run counts as yes. */
    private fun granted(context: Context, tree: String): Boolean = runCatching {
        val uri = tree.toUri()
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
    }.getOrDefault(true)
}
