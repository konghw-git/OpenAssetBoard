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
import com.assetboard.domain.calendar.WorkdayCalendar
import com.assetboard.domain.ensure
import com.assetboard.domain.todo.TodoInvariants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Format 2 adds the todo and diary tables, format 3 the daily habit checks; older files read
 * with the newer tables empty. Device-local
 * state (the data epoch, the holiday cache, settings) is never part of a backup.
 */
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
    val todoLists: List<TodoList> = emptyList(),
    val todoSections: List<TodoSection> = emptyList(),
    val todos: List<Todo> = emptyList(),
    val checklistItems: List<ChecklistItem> = emptyList(),
    val diaryEntries: List<DiaryEntry> = emptyList(),
    val diaryDrafts: List<DiaryDraft> = emptyList(),
    val habitChecks: List<HabitCheck> = emptyList(),
)

/**
 * Each kind is one file that every new backup overwrites: automatic and manual backups share one,
 * the copy saved before a restore has its own.
 */
enum class BackupKind(val fileName: String) {
    AUTO("asset-board-backup.json"),
    MANUAL("asset-board-backup.json"),
    PRE_RESTORE("asset-board-prerestore.json"),
}

/** Timestamped files from versions that kept a new file per backup; removed after the next one. */
private val legacyBackup = Regex("""asset-board-(auto-)?\d{8}-\d{6}-\d{3}\.json""")
private val legacyPreRestore = Regex("""asset-board-prerestore-\d{8}-\d{6}-\d{3}\.json""")

/** What the confirmation dialog says before overwriting. */
data class BackupPreview(
    val formatVersion: Int,
    val exportedAt: Long,
    val accounts: Int,
    val todos: Int,
    val diaries: Int,
    val currentTodos: Int,
    val currentDiaries: Int,
)

/** A saved copy of the data as it was right before a restore. */
data class PreRestoreCopy(val name: String, val uri: Uri, val inBackupFolder: Boolean, val savedAt: Long)

/** Side effects of a committed restore: alarms, notifications, widgets. */
interface RestoreEffects {
    /** Stop ringing, withdraw notifications, cancel the alarms of [oldTodoIds], schedule the new data, refresh widgets. */
    suspend fun afterRestore(oldTodoIds: List<String>)
}

/**
 * Full JSON export into a user-picked folder (survives uninstall), always into the same file so
 * the folder never fills up, and full-overwrite restore.
 * Merging is deliberately unsupported. The restore is the coordinator of the write gate: see
 * [restore].
 */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val gate: WriteGate,
    private val diary: DiaryRepository,
    private val calendar: () -> WorkdayCalendar,
    private val effects: RestoreEffects,
    private val onRestored: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    /** Fault injection for tests: thrown inside the overwrite transaction, after the inserts. */
    @Volatile internal var failInsideRestoreTransaction = false

    suspend fun exportJson(): String {
        val dao = db.dao()
        val todo = db.todoDao()
        val d = db.diaryDao()
        val file = db.withTransaction {
            BackupFile(
                formatVersion = FORMAT_VERSION,
                schemaVersion = AppDatabase.SCHEMA_VERSION,
                exportedAt = clock(),
                groups = dao.groups(),
                accounts = dao.accounts(),
                snapshots = dao.snapshots(),
                valuations = dao.valuations(),
                snapshotItems = dao.snapshotItems(),
                flows = dao.flows(),
                todoLists = todo.allLists(),
                todoSections = todo.allSections(),
                todos = todo.allTodos(),
                checklistItems = todo.allChecklist(),
                diaryEntries = d.all(),
                diaryDrafts = d.allDrafts(),
                habitChecks = db.habitDao().all(),
            )
        }
        return json.encodeToString(BackupFile.serializer(), file)
    }

    /** Returns the written file's display name. */
    suspend fun backupNow(kind: BackupKind): String = withContext(Dispatchers.IO) {
        val tree = settings.backupTreeUri.value ?: throw LedgerException("请先选择备份目录")
        val dir = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(tree)) }.getOrNull()
            ?.takeIf { it.canWrite() } ?: throw LedgerException("备份目录不可写，请重新选择")
        val name = replaceInFolder(dir, kind.fileName, exportJson())
        val legacy = if (kind == BackupKind.PRE_RESTORE) legacyPreRestore else legacyBackup
        dir.listFiles().filter { f -> f.name?.let(legacy::matches) == true }.forEach { runCatching { it.delete() } }
        if (kind != BackupKind.PRE_RESTORE) settings.setLastBackupAt(clock())
        name
    }

    /**
     * Writes [text] as [name], replacing the old file only once the new one is complete: into a
     * side file first, then renamed over, with the old file moved aside until that worked.
     * Providers that cannot rename get the file rewritten in place. Whatever fails, either the
     * old file or the complete side file is left behind.
     */
    internal fun replaceInFolder(dir: DocumentFile, name: String, text: String): String {
        val base = name.removeSuffix(".json")
        val partName = "$base.partial.json"
        val asideName = "$base.previous.json"
        dir.findFile(partName)?.delete()
        val part = dir.createFile("application/json", partName) ?: throw LedgerException("无法在备份目录创建文件")
        try {
            writeDoc(part.uri, text)
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        fun rename(doc: DocumentFile, to: String) = runCatching { doc.renameTo(to) }.getOrDefault(false)
        val old = dir.findFile(name)
        if (old == null) {
            if (rename(part, name)) return part.name ?: name
        } else {
            dir.findFile(asideName)?.delete()
            if (rename(old, asideName)) {
                if (rename(part, name)) {
                    old.delete()
                    return part.name ?: name
                }
                rename(old, name)
            }
        }
        val doc = dir.findFile(name) ?: dir.createFile("application/json", name)
            ?: throw LedgerException("无法在备份目录创建文件，新备份暂存为 $partName")
        try {
            writeDoc(doc.uri, text)
        } catch (e: Exception) {
            throw LedgerException("无法写入备份文件，新备份暂存为 $partName")
        }
        part.delete()
        dir.findFile(asideName)?.delete()
        return doc.name ?: name
    }

    private fun writeDoc(uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
            ?: throw LedgerException("无法写入备份文件")
    }

    private fun read(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: throw LedgerException("无法读取文件")

    private fun parse(text: String): BackupFile {
        val file = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: Exception) {
            throw LedgerException("不是有效的备份文件")
        }
        ensure(file.formatVersion in 1..FORMAT_VERSION) { "备份格式版本 ${file.formatVersion} 不受支持" }
        ensure(file.schemaVersion <= AppDatabase.SCHEMA_VERSION) { "备份来自更新版本的 app，请先升级" }
        return file
    }

    suspend fun inspect(uri: Uri): BackupPreview = withContext(Dispatchers.IO) {
        val file = parse(read(uri))
        BackupPreview(
            formatVersion = file.formatVersion,
            exportedAt = file.exportedAt,
            accounts = file.accounts.size,
            todos = file.todos.count { it.deletedAt == null && it.kind != TodoKind.COMPLETION },
            diaries = file.diaryEntries.count { it.deletedAt == null },
            currentTodos = db.todoDao().liveTodos().size,
            currentDiaries = db.diaryDao().all().count { it.deletedAt == null },
        )
    }

    /** Every check, before anything is touched: JSON, versions, ledger rules, todo and diary invariants. */
    private fun validate(file: BackupFile) {
        fun <T> unique(items: List<T>, id: (T) -> Any, what: String) =
            ensure(items.map(id).toSet().size == items.size) { "备份里有重复的$what" }
        unique(file.groups, { it.id }, "分组")
        unique(file.accounts, { it.id }, "账户")
        unique(file.snapshots, { it.id }, "盘点")
        unique(file.valuations, { it.id }, "估值")
        unique(file.flows, { it.id }, "流水")
        unique(file.snapshotItems, { it.snapshotId to it.accountId }, "盘点明细")
        unique(file.habitChecks, { it.date to it.habit }, "每日指标")
        for (h in file.habitChecks) {
            ensure(com.assetboard.domain.Habit.of(h.habit) != null) { "备份里有未知的每日指标「${h.habit}」" }
            ensure(runCatching { java.time.LocalDate.parse(h.date) }.isSuccess) { "备份里有日期无效的每日指标" }
        }
        val groupIds = file.groups.map { it.id }.toSet()
        val byId = file.accounts.associateBy { it.id }
        val snapshotIds = file.snapshots.map { it.id }.toSet()
        val valuationIds = file.valuations.map { it.id }.toSet()
        for (a in file.accounts) ensure(a.groupId in groupIds) { "账户「${a.name}」指向不存在的分组" }
        for (v in file.valuations) {
            ensure(v.accountId in byId) { "有估值指向不存在的账户" }
            ensure(v.snapshotId == null || v.snapshotId in snapshotIds) { "有估值指向不存在的盘点" }
        }
        val snapshotAt = file.snapshots.associate { it.id to it.at }
        val valuationById = file.valuations.associateBy { it.id }
        for (v in file.valuations) {
            // A stocktake's own valuations move with it and always sit at its instant.
            ensure(v.snapshotId == null || v.at == snapshotAt[v.snapshotId]) { "有估值与所属盘点的时间不一致" }
        }
        for (i in file.snapshotItems) {
            ensure(i.snapshotId in snapshotIds && i.accountId in byId && i.valuationId in valuationIds) { "有盘点明细的引用不完整" }
            val v = valuationById.getValue(i.valuationId)
            ensure(v.accountId == i.accountId) { "有盘点明细引用了其他账户的估值" }
            ensure(v.at == snapshotAt[i.snapshotId]) { "有盘点明细引用了其他时间的估值" }
            ensure(v.snapshotId == null || v.snapshotId == i.snapshotId) { "有盘点明细引用了其他盘点的估值" }
        }
        for (f in file.flows) Rules.checkFlow(f, f.fromAccountId?.let(byId::get), f.toAccountId?.let(byId::get))
        TodoInvariants.checkAll(file.todoLists, file.todoSections, file.todos, file.checklistItems, file.diaryEntries, file.diaryDrafts, calendar())
    }

    /**
     * Full-overwrite restore, as the coordinator of the write gate:
     * 1. freeze: new writes are rejected, running ones finish, unsaved diary text is committed
     *    through [flushDiaryEditors] (or the restore is cancelled);
     * 2. validate the whole file; 3. save a copy of the current data;
     * 4. one transaction: wipe, insert, bump the epoch;
     * 5. only after the commit: alarms, notifications, widgets; 6. unfreeze, whatever happened.
     * Once the transaction committed, the screens are rebuilt even if the restore is cancelled,
     * and the reminders are marked for rebuilding until the effects have run.
     * Returns where the pre-restore copy went.
     */
    suspend fun restore(uri: Uri): String = withContext(Dispatchers.IO) {
        val text = read(uri)
        gate.freeze()
        var committed = false
        try {
            flushDiaryEditors()
            val file = parse(text)
            validate(file)
            val savedTo = savePreRestoreCopy()
            val oldIds = db.todoDao().allTodos().map { it.id }
            val epoch = db.withTransaction {
                overwrite(file)
                if (failInsideRestoreTransaction) throw IllegalStateException("模拟写入失败")
                gate.bumpEpochInTransaction()
            }
            gate.epochCommitted(epoch)
            committed = true
            // Set before the effects and cleared after them: a cancellation or crash in between
            // still gets the reminders rebuilt on the next app start.
            settings.setRescheduleNeeded(true)
            try {
                effects.afterRestore(oldIds)
                settings.setRescheduleNeeded(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The data is restored; reminders are rebuilt on the next app start.
            }
            savedTo
        } catch (e: Exception) {
            if (!committed) gate.invalidateCache()
            throw e
        } finally {
            gate.unfreeze()
            // Screens bound to the old epoch could not save anything; they are rebuilt either way.
            if (committed) onRestored()
        }
    }

    private suspend fun overwrite(file: BackupFile) {
        val dao = db.dao()
        val todo = db.todoDao()
        val d = db.diaryDao()
        // Children first, so RESTRICT never fires while wiping.
        dao.clearSnapshotItems()
        dao.clearFlows()
        dao.clearValuations()
        dao.clearSnapshots()
        dao.clearAccounts()
        dao.clearGroups()
        todo.clearChecklist()
        d.clearDrafts()
        todo.clearTodos()
        todo.clearSections()
        todo.clearLists()
        d.clear()
        db.habitDao().clear()
        dao.insertGroups(file.groups)
        dao.insertAccounts(file.accounts)
        dao.insertSnapshots(file.snapshots)
        dao.insertValuations(file.valuations)
        dao.insertSnapshotItems(file.snapshotItems)
        dao.insertFlows(file.flows)
        todo.insertLists(file.todoLists)
        todo.insertSections(file.todoSections)
        // Completion records point at their sources, so sources go in first.
        todo.insertTodos(file.todos.sortedBy { if (it.kind == TodoKind.COMPLETION) 1 else 0 })
        todo.insertChecklistItems(file.checklistItems)
        d.insertAll(file.diaryEntries)
        d.insertDrafts(file.diaryDrafts)
        db.habitDao().insertAll(file.habitChecks)
    }

    // ------------------------------------------------------------ the coordinator's private write channel

    /**
     * The one write allowed while the gate is frozen: committing the diary editors' unsaved text.
     * It runs DAO transactions directly (the coordinator already holds the write side), never
     * through [WriteGate.write]. Body first, under the session's `body_rev`; a draft when that
     * does not hold. Text that lands in neither cancels the restore.
     */
    private suspend fun flushDiaryEditors() {
        for (editor in diary.editors) {
            val p = editor.pendingForRestore() ?: continue
            val body = runCatching { db.withTransaction { writeBody(p) } }.getOrNull()
            if (body != null) {
                editor.flushedByRestore(body.first, body.second)
                continue
            }
            val drafted = runCatching {
                db.withTransaction {
                    if (diary.failDraftWrites) throw IllegalStateException("模拟写盘失败")
                    val d = db.diaryDao()
                    d.insertDraft(DiaryDraft(newId(), p.date, p.entryId?.takeIf { d.entry(it) != null }, p.text, "恢复备份前保存冲突", clock()))
                }
            }.isSuccess
            if (!drafted) throw LedgerException("有未保存的日记，恢复已取消")
            editor.flushedByRestore(p.entryId, null)
        }
    }

    /** (entry id, new body_rev), or null when the version condition does not hold. */
    private suspend fun writeBody(p: PendingDiaryText): Pair<String, Long>? {
        if (diary.failBodyWrites) throw IllegalStateException("模拟写盘失败")
        val d = db.diaryDao()
        val now = clock()
        if (p.entryId != null) {
            return if (d.updateBody(p.entryId, p.text, p.expectedRev ?: -1, now) == 1) p.entryId to (p.expectedRev!! + 1) else null
        }
        val existing = d.liveByDate(p.date)
        return when {
            existing == null -> {
                val e = DiaryEntry(newId(), p.date, p.text, bodyRev = 1, createdAt = now, updatedAt = now)
                d.insert(e)
                e.id to 1L
            }
            existing.body.isEmpty() && d.updateBody(existing.id, p.text, existing.bodyRev, now) == 1 -> existing.id to existing.bodyRev + 1
            else -> null
        }
    }

    // ------------------------------------------------------------ pre-restore copies

    private val localCopies get() = File(context.filesDir, "prerestore")

    private suspend fun savePreRestoreCopy(): String =
        if (settings.backupTreeUri.value != null) {
            backupNow(BackupKind.PRE_RESTORE)
        } else {
            val dir = localCopies.apply { mkdirs() }
            val f = File(dir, BackupKind.PRE_RESTORE.fileName)
            val part = File(dir, f.name + ".partial")
            try {
                part.writeText(exportJson())
                if (!part.renameTo(f)) throw IllegalStateException("重命名失败")
            } catch (e: Exception) {
                part.delete()
                throw LedgerException("无法保存恢复前副本：" + (e.message ?: ""))
            }
            dir.listFiles()?.filter { legacyPreRestore.matches(it.name) }?.forEach { it.delete() }
            f.absolutePath
        }

    /** The copy in the backup folder and the one in private storage, newest first. */
    suspend fun preRestoreCopies(): List<PreRestoreCopy> = withContext(Dispatchers.IO) {
        fun isCopy(name: String?) = name == BackupKind.PRE_RESTORE.fileName || name?.let(legacyPreRestore::matches) == true
        val local = localCopies.listFiles().orEmpty().filter { isCopy(it.name) }
            .map { PreRestoreCopy(it.name, Uri.fromFile(it), inBackupFolder = false, savedAt = it.lastModified()) }
        val folder = settings.backupTreeUri.value?.let { tree ->
            runCatching {
                DocumentFile.fromTreeUri(context, Uri.parse(tree))?.listFiles().orEmpty()
                    .filter { isCopy(it.name) }
                    .map { PreRestoreCopy(it.name!!, it.uri, inBackupFolder = true, savedAt = it.lastModified()) }
            }.getOrNull()
        }.orEmpty()
        (local + folder).sortedByDescending { it.savedAt }
    }

    companion object {
        const val FORMAT_VERSION = 3
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
