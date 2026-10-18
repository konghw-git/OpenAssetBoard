package com.assetboard.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.assetboard.AssetBoardApp
import com.assetboard.domain.LedgerException
import com.assetboard.domain.Rules
import com.assetboard.domain.ensure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class BackupFile(
    val formatVersion: Int,
    val schemaVersion: Int,
    val exportedAt: Long,
    val groups: List<AccountGroup>,
    val accounts: List<Account>,
    val snapshots: List<Snapshot>,
    val valuations: List<Valuation>,
    val snapshotItems: List<SnapshotItem>,
    val flows: List<MoneyFlow>,
)

enum class BackupKind(val prefix: String) { AUTO("asset-board-auto-"), MANUAL("asset-board-"), PRE_RESTORE("asset-board-prerestore-") }

/**
 * Full JSON export into a user-picked folder (survives uninstall), and full-overwrite restore.
 * Merging is deliberately unsupported.
 */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val onRestored: () -> Unit,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    suspend fun exportJson(): String {
        val dao = db.dao()
        val file = db.withTransaction {
            BackupFile(
                formatVersion = FORMAT_VERSION,
                schemaVersion = AppDatabase.SCHEMA_VERSION,
                exportedAt = System.currentTimeMillis(),
                groups = dao.groups(),
                accounts = dao.accounts(),
                snapshots = dao.snapshots(),
                valuations = dao.valuations(),
                snapshotItems = dao.snapshotItems(),
                flows = dao.flows(),
            )
        }
        return json.encodeToString(BackupFile.serializer(), file)
    }

    /** Returns the written file's display name. */
    suspend fun backupNow(kind: BackupKind): String = withContext(Dispatchers.IO) {
        val tree = settings.backupTreeUri.value ?: throw LedgerException("请先选择备份目录")
        val dir = DocumentFile.fromTreeUri(context, Uri.parse(tree))
            ?.takeIf { it.canWrite() } ?: throw LedgerException("备份目录不可写，请重新选择")
        val name = kind.prefix + stamp() + ".json"
        val text = exportJson()
        val doc = dir.createFile("application/json", name) ?: throw LedgerException("无法在备份目录创建文件")
        context.contentResolver.openOutputStream(doc.uri, "wt")?.use { it.write(text.toByteArray()) }
            ?: throw LedgerException("无法写入备份文件")
        if (kind == BackupKind.AUTO) prune(dir)
        settings.setLastBackupAt(System.currentTimeMillis())
        doc.name ?: name
    }

    private fun prune(dir: DocumentFile) {
        dir.listFiles()
            .filter { it.name?.startsWith(BackupKind.AUTO.prefix) == true }
            .sortedByDescending { it.name }
            .drop(KEEP_AUTO)
            .forEach { it.delete() }
    }

    /**
     * Validate, save a copy of the current data, then replace everything in one transaction.
     * Any failure leaves the database untouched.
     */
    suspend fun restore(uri: Uri): String = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: throw LedgerException("无法读取文件")
        val file = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: Exception) {
            throw LedgerException("不是有效的备份文件")
        }
        ensure(file.formatVersion == FORMAT_VERSION) { "备份格式版本 ${file.formatVersion} 不受支持" }
        ensure(file.schemaVersion <= AppDatabase.SCHEMA_VERSION) { "备份来自更新版本的 app，请先升级" }

        val savedTo = savePreRestoreCopy()

        val dao = db.dao()
        db.withTransaction {
            dao.clearSnapshotItems()
            dao.clearFlows()
            dao.clearValuations()
            dao.clearSnapshots()
            dao.clearAccounts()
            dao.clearGroups()
            dao.insertGroups(file.groups)
            dao.insertAccounts(file.accounts)
            dao.insertSnapshots(file.snapshots)
            dao.insertValuations(file.valuations)
            dao.insertSnapshotItems(file.snapshotItems)
            val byId = file.accounts.associateBy { it.id }
            for (f in file.flows) Rules.checkFlow(f, f.fromAccountId?.let(byId::get), f.toAccountId?.let(byId::get))
            dao.insertFlows(file.flows)
        }
        onRestored()
        savedTo
    }

    private suspend fun savePreRestoreCopy(): String =
        if (settings.backupTreeUri.value != null) {
            backupNow(BackupKind.PRE_RESTORE)
        } else {
            val dir = File(context.filesDir, "prerestore").apply { mkdirs() }
            val f = File(dir, BackupKind.PRE_RESTORE.prefix + stamp() + ".json")
            f.writeText(exportJson())
            f.absolutePath
        }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    companion object {
        const val FORMAT_VERSION = 1
        const val KEEP_AUTO = 10
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AssetBoardApp).container
        if (container.settings.backupTreeUri.value == null) return Result.success()
        return try {
            container.backup.backupNow(BackupKind.AUTO)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
