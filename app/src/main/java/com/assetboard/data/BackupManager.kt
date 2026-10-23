package com.assetboard.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.assetboard.AssetBoardApp
import com.assetboard.domain.Ledger
import com.assetboard.domain.LedgerException
import com.assetboard.domain.Rules
import com.assetboard.domain.calendar.WorkdayCalendar
import com.assetboard.domain.ensure
import com.assetboard.domain.todo.TodoInvariants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Format 2 adds the todo and diary tables, format 3 the daily habit checks, format 4 the health
 * profile and weigh-ins, format 5 the notes, format 6 the notes' order and the weekly reviews,
 * format 7 the net worth goal, format 8 the habits themselves (a file without them reads as the
 * five built in), format 9 what was eaten, format 10 the diary's mood and the habits' rest days,
 * format 11 each habit's rubric; older files read with the newer tables empty and the newer fields unset. Device-local
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
    /** Empty in backups from before habits could change, and while the five built in are unedited. */
    val habits: List<com.assetboard.domain.Habit> = emptyList(),
    val healthProfile: HealthProfile? = null,
    val bodyMeasurements: List<BodyMeasurement> = emptyList(),
    val foodEntries: List<FoodEntry> = emptyList(),
    val noteTopics: List<NoteTopic> = emptyList(),
    val notes: List<Note> = emptyList(),
    val noteSources: List<NoteSource> = emptyList(),
    val noteDrafts: List<NoteDraft> = emptyList(),
    val weeklyReviews: List<WeeklyReview> = emptyList(),
    val weeklyDrafts: List<WeeklyDraft> = emptyList(),
    /** Cents; null for none. */
    val netWorthGoal: Long? = null,
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
    val healthRecords: Int = 0,
    val currentHealthRecords: Int = 0,
    val foodEntries: Int = 0,
    val currentFoodEntries: Int = 0,
    val notes: Int = 0,
    val currentNotes: Int = 0,
    val weeks: Int = 0,
    val currentWeeks: Int = 0,
    /** The habits the backup itself carries; empty in files before format 8. */
    val habits: Int = 0,
    /** Whether the backup names a net worth goal; files before format 7 do not. */
    val hasGoal: Boolean = false,
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
    private val notes: NoteRepository,
    /** The diary and note editors whose unsaved text a restore commits first. */
    private val editors: EditorRegistry,
    private val calendar: () -> WorkdayCalendar,
    private val effects: RestoreEffects,
    private val onRestored: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val weeks: WeekRepository = WeekRepository(db, gate, editors, clock),
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    /** Fault injection for tests: thrown inside the overwrite transaction, after the inserts. */
    @Volatile internal var failInsideRestoreTransaction = false

    suspend fun exportJson(): String = json.encodeToString(BackupFile.serializer(), current())

    /** Everything a backup holds, read in one transaction. */
    private suspend fun current(): BackupFile {
        val dao = db.dao()
        val todo = db.todoDao()
        val d = db.diaryDao()
        return db.withTransaction {
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
                habits = db.habitDao().habits(),
                healthProfile = db.healthDao().profile(),
                bodyMeasurements = db.healthDao().all(),
                foodEntries = db.foodDao().all(),
                noteTopics = db.noteDao().allTopics(),
                notes = db.noteDao().all(),
                noteSources = db.noteDao().allSources(),
                noteDrafts = db.noteDao().allDrafts(),
                weeklyReviews = db.weekDao().all(),
                netWorthGoal = GoalRepository.parse(db.metaDao().get(GoalRepository.KEY)),
                weeklyDrafts = db.weekDao().allDrafts(),
            )
        }
    }

    /**
     * One backup at a time: the automatic, manual and pre-install ones write the same file, and
     * interleaving their side files could leave no complete copy.
     */
    private val backupLock = Mutex()

    /**
     * Returns the written file's display name. A failed automatic or manual backup leaves its
     * reason in [SettingsStore.backupError] until one works again, so it never fails unseen.
     */
    suspend fun backupNow(kind: BackupKind): String = backupLock.withLock {
        withContext(Dispatchers.IO) {
            try {
                writeBackup(kind)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (kind != BackupKind.PRE_RESTORE) {
                    val reason = (e as? LedgerException)?.message ?: e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    settings.setBackupError(reason, clock())
                }
                throw e
            }
        }
    }

    private suspend fun writeBackup(kind: BackupKind): String {
        val tree = settings.backupTreeUri.value ?: throw LedgerException("请先选择备份目录")
        val dir = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(tree)) }.getOrNull()
            ?.takeIf { it.canWrite() } ?: throw LedgerException("备份目录不可写，请重新选择")
        val name = replaceInFolder(dir, kind.fileName, exportJson())
        val legacy = if (kind == BackupKind.PRE_RESTORE) legacyPreRestore else legacyBackup
        dir.listFiles().filter { f -> f.name?.let(legacy::matches) == true }.forEach { runCatching { it.delete() } }
        // Leftovers of runs that failed before this one; after it, the new file is complete.
        staleParts(dir, kind.fileName).forEach { runCatching { it.delete() } }
        if (kind != BackupKind.PRE_RESTORE) settings.setLastBackupAt(clock())
        return name
    }

    /** Side files an earlier run left behind for [name]. */
    private fun staleParts(dir: DocumentFile, name: String): List<DocumentFile> {
        val base = name.removeSuffix(".json")
        return dir.listFiles().filter { f ->
            f.name?.let { it.startsWith("$base.partial-") || it.startsWith("$base.previous-") } == true
        }
    }

    /**
     * Writes [text] as [name], replacing the old file only once the new one is complete: the
     * complete text goes into a side file of its own name, which is then renamed over the old one,
     * with the old file moved aside until that worked. Whatever fails, one complete copy is left
     * behind: the new one, the old one, or the side file the error names.
     */
    internal fun replaceInFolder(dir: DocumentFile, name: String, text: String): String {
        val base = name.removeSuffix(".json")
        // Unique to this run: another run's side file may be the only complete copy left.
        val tag = newId().take(8)
        val partName = "$base.partial-$tag.json"
        val asideName = "$base.previous-$tag.json"
        val part = dir.createFile("application/json", partName)
            ?: throw LedgerException("无法在备份目录创建文件")
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
        } else if (rename(old, asideName)) {
            if (rename(part, name)) {
                old.delete()
                return part.name ?: name
            }
            // The old file is back where it was; from here the new text goes in beside it.
            rename(old, name)
        }
        // The provider cannot rename (or the new file would not take the name): write in place.
        // The complete new text is already in the side file, which the error names and the next
        // successful backup clears.
        val doc = dir.findFile(name) ?: runCatching { dir.createFile("application/json", name) }.getOrNull()
            ?: throw LedgerException("无法在备份目录创建文件，新备份暂存为 ${part.name ?: partName}")
        try {
            writeDoc(doc.uri, text)
        } catch (e: Exception) {
            throw LedgerException("无法写入备份文件，完整的新备份暂存为 ${part.name ?: partName}")
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
            healthRecords = file.bodyMeasurements.size,
            currentHealthRecords = db.healthDao().all().size,
            foodEntries = file.foodEntries.size,
            currentFoodEntries = db.foodDao().all().size,
            notes = file.notes.count { it.deletedAt == null },
            currentNotes = db.noteDao().all().count { it.deletedAt == null },
            weeks = file.weeklyReviews.count { it.deletedAt == null && it.body.isNotBlank() },
            currentWeeks = db.weekDao().all().count { it.deletedAt == null && it.body.isNotBlank() },
            habits = file.habits.size,
            hasGoal = file.netWorthGoal != null,
        )
    }

    /** Every check, before anything is touched: JSON, versions, ledger rules, todo, diary and note invariants. */
    private fun validate(file: BackupFile) {
        fun <T> unique(items: List<T>, id: (T) -> Any, what: String) =
            ensure(items.map(id).toSet().size == items.size) { "备份里有重复的$what" }
        unique(file.groups, { it.id }, "分组")
        unique(file.accounts, { it.id }, "账户")
        unique(file.snapshots, { it.id }, "盘点")
        unique(file.valuations, { it.id }, "估值")
        unique(file.flows, { it.id }, "流水")
        unique(file.snapshotItems, { it.snapshotId to it.accountId }, "盘点明细")
        // The schema's unique indexes: a clash would only fail inside the overwrite transaction.
        unique(file.snapshots, { it.at }, "同一时刻的盘点")
        unique(file.valuations, { it.accountId to it.at }, "同一账户同一时刻的估值")
        unique(file.habitChecks, { it.date to it.habit }, "每日指标")
        for (e in file.diaryEntries) ensure(e.mood == null || com.assetboard.domain.Mood.of(e.mood) != null) { "备份里有无效的心情" }
        unique(file.habits, { it.key }, "每日打卡")
        checkHabits(file.habits)
        val habitKeys = file.habits.ifEmpty { com.assetboard.domain.Habit.DEFAULTS }.map { it.key }.toSet()
        for (h in file.habitChecks) {
            ensure(h.habit in habitKeys) { "备份里有未知的每日指标「${h.habit}」" }
            ensure(runCatching { java.time.LocalDate.parse(h.date) }.isSuccess) { "备份里有日期无效的每日指标" }
        }
        unique(file.bodyMeasurements, { it.id }, "健康记录")
        unique(file.bodyMeasurements, { it.date }, "同一天的健康记录")
        for (m in file.bodyMeasurements) {
            ensure(runCatching { java.time.LocalDate.parse(m.date) }.isSuccess) { "备份里有日期无效的健康记录" }
            HealthRepository.checkMeasurement(m.weightG, m.bodyFatBp)
        }
        file.healthProfile?.let { p ->
            ensure(p.id == "self") { "备份里的健康档案无效" }
            // Only the format and the range; a young birthday never fails a restore.
            HealthRepository.checkProfile(p.birthDate, p.heightMm, p.targetWeightG, java.time.LocalDate.MAX)
        }
        unique(file.foodEntries, { it.id }, "饮食记录")
        for (e in file.foodEntries) FoodRepository.check(e)
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
        NoteRepository.checkBackup(file.noteTopics, file.notes, file.noteSources, file.noteDrafts)
        WeekRepository.checkBackup(file.weeklyReviews, file.weeklyDrafts)
        ensure(file.netWorthGoal == null || file.netWorthGoal > 0) { "备份里的目标资产无效" }
    }

    /** What [HabitRepository] would have allowed: the days each one belongs to read as dates. Empty is the five built in. */
    private fun checkHabits(habits: List<com.assetboard.domain.Habit>) {
        if (habits.isEmpty()) return
        fun date(s: String?) = s?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() ?: throw LedgerException("备份里有日期无效的每日打卡") }
        for (h in habits) {
            ensure(h.key.isNotBlank()) { "备份里有每日打卡缺少标识" }
            ensure(h.label.isNotBlank() && h.label.length <= com.assetboard.domain.Habit.MAX_LABEL) { "备份里有名字无效的每日打卡" }
            ensure(h.rubric.length <= com.assetboard.domain.Habit.MAX_RUBRIC) { "备份里有打卡标准过长的每日打卡" }
            val from = date(h.addedOn)
            val until = date(h.removedOn)
            // Removed on the day it was added is fine: it then belongs to no day but keeps its checks.
            ensure(from == null || until == null || !until.isBefore(from)) { "备份里有每日打卡的移除日期早于添加日期" }
        }
        val active = habits.count { it.active }
        ensure(active in 1..com.assetboard.domain.Habit.MAX_ACTIVE) { "备份里的每日打卡数量无效" }
    }

    /**
     * Full-overwrite restore, as the coordinator of the write gate:
     * 1. freeze: new writes are rejected, running ones finish, unsaved diary and note text is
     *    committed through [flushEditors] (or the restore is cancelled);
     * 2. validate the whole file; 3. save a copy of the current data;
     * 4. one transaction: wipe, insert, bump the epoch;
     * 5. only after the commit: alarms, notifications, widgets; 6. unfreeze, whatever happened.
     * Once the transaction committed, the screens are rebuilt even if the restore is cancelled,
     * and the reminders are marked for rebuilding until the effects have run.
     * Returns where the pre-restore copy went.
     */
    suspend fun restore(uri: Uri): String = withContext(Dispatchers.IO) {
        val text = read(uri)
        replaceAll { parse(text).also(::validate) }
    }

    /**
     * Back to a fresh install: every record goes, by the same path as a restore of an empty
     * backup, so the current data is saved as the pre-restore copy first. Settings, the backup
     * folder and the holiday cache stay. Returns where that copy went.
     */
    suspend fun reset(): String = withContext(Dispatchers.IO) {
        replaceAll {
            BackupFile(
                formatVersion = FORMAT_VERSION, schemaVersion = AppDatabase.SCHEMA_VERSION, exportedAt = clock(),
                groups = emptyList(), accounts = emptyList(), snapshots = emptyList(),
                valuations = emptyList(), snapshotItems = emptyList(), flows = emptyList(),
            )
        }
    }

    /**
     * Keeps the groups, the accounts and their current balances, and drops the ledger's past:
     * every valuation, stocktake and flow. Today becomes each account's new baseline, and an
     * investment's cost restarts at its current value. Todos, diary, notes and the rest stay as
     * they are. For data that started from someone else's numbers. Returns where the copy went.
     */
    suspend fun resetLedgerHistory(): String = withContext(Dispatchers.IO) {
        val now = clock()
        replaceAll { withoutLedgerHistory(current(), now).also(::validate) }
    }

    /** Steps 1 to 6 of [restore]; [load] runs once the editors are flushed and must throw on a bad file. */
    private suspend fun replaceAll(load: suspend () -> BackupFile): String {
        gate.freeze()
        var committed = false
        try {
            flushEditors()
            val file = load()
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
            return savedTo
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
        val n = db.noteDao()
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
        db.habitDao().clearHabits()
        db.healthDao().clearProfile()
        db.healthDao().clear()
        db.foodDao().clear()
        n.clearDrafts()
        n.clearSources()
        n.clear()
        n.clearTopics()
        db.weekDao().clearDrafts()
        db.weekDao().clear()
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
        db.habitDao().putHabits(file.habits)
        db.habitDao().insertAll(file.habitChecks)
        file.healthProfile?.let { db.healthDao().insertProfiles(listOf(it)) }
        db.healthDao().insertAll(file.bodyMeasurements)
        db.foodDao().insertAll(file.foodEntries)
        // Parents first: foreign keys are checked row by row, so the wrong order fails the restore.
        n.insertTopics(file.noteTopics)
        // Before format 6 notes had no hand-set order: they take the one they showed in.
        n.insertAll(if (file.formatVersion >= 6) file.notes else file.notes.sortedByDescending { it.updatedAt }.mapIndexed { i, x -> x.copy(position = i.toLong()) })
        n.insertSources(file.noteSources)
        n.insertDrafts(file.noteDrafts)
        db.weekDao().insertAll(file.weeklyReviews)
        db.weekDao().insertDrafts(file.weeklyDrafts)
        db.metaDao().delete(GoalRepository.KEY)
        file.netWorthGoal?.let { db.metaDao().put(AppMeta(GoalRepository.KEY, it.toString())) }
    }

    // ------------------------------------------------------------ the coordinator's private write channel

    /**
     * The one write allowed while the gate is frozen: committing the editors' unsaved text. It
     * runs DAO transactions directly (the coordinator already holds the write side), never
     * through [WriteGate.write]. Body first, under the session's `body_rev`; a draft when that
     * does not hold. Text that lands in neither cancels the restore.
     */
    private suspend fun flushEditors() {
        for (editor in editors) {
            val p = editor.pendingForRestore() ?: continue
            val body = runCatching { db.withTransaction { writeBody(p) } }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            if (body != null) {
                editor.flushedByRestore(body.first, body.second)
                continue
            }
            val drafted = runCatching { db.withTransaction { writeDraft(p) } }
                .onFailure { if (it is CancellationException) throw it }
                .isSuccess
            if (!drafted) {
                throw LedgerException(
                    when (p) {
                        is PendingText.Diary -> "有未保存的日记，恢复已取消"
                        is PendingText.Note -> "有未保存的笔记，恢复已取消"
                        is PendingText.Week -> "有未保存的周复盘，恢复已取消"
                    },
                )
            }
            val id = when (p) {
                is PendingText.Diary -> p.entryId
                is PendingText.Note -> p.noteId
                is PendingText.Week -> p.reviewId
            }
            editor.flushedByRestore(id, null)
        }
    }

    /** (row id, new body_rev), or null when the version condition does not hold. */
    private suspend fun writeBody(p: PendingText): Pair<String, Long>? = when (p) {
        is PendingText.Diary -> writeDiaryBody(p)
        is PendingText.Note -> writeNoteBody(p)
        is PendingText.Week -> writeWeekBody(p)
    }

    private suspend fun writeDraft(p: PendingText) {
        val reason = "恢复备份前保存冲突"
        when (p) {
            is PendingText.Diary -> {
                if (diary.failDraftWrites) throw IllegalStateException("模拟写盘失败")
                val d = db.diaryDao()
                d.insertDraft(DiaryDraft(newId(), p.date, p.entryId?.takeIf { d.entry(it) != null }, p.text, reason, clock()))
            }
            is PendingText.Note -> {
                if (notes.failDraftWrites) throw IllegalStateException("模拟写盘失败")
                val n = db.noteDao()
                n.insertDraft(NoteDraft(newId(), p.noteId?.takeIf { n.note(it) != null }, p.text, reason, clock()))
            }
            is PendingText.Week -> {
                if (weeks.failDraftWrites) throw IllegalStateException("模拟写盘失败")
                val w = db.weekDao()
                w.insertDraft(WeeklyDraft(newId(), p.weekStart, p.reviewId?.takeIf { w.review(it) != null }, p.text, reason, clock()))
            }
        }
    }

    /** Like a diary entry: an existing row at the session's version, or a new or empty one for the week. */
    private suspend fun writeWeekBody(p: PendingText.Week): Pair<String, Long>? {
        if (weeks.failBodyWrites) throw IllegalStateException("模拟写盘失败")
        val w = db.weekDao()
        val now = clock()
        if (p.reviewId != null) {
            return if (w.updateBody(p.reviewId, p.text, p.expectedRev ?: -1, now) == 1) p.reviewId to (p.expectedRev!! + 1) else null
        }
        val existing = w.liveByWeek(p.weekStart)
        return when {
            existing == null -> {
                val r = WeeklyReview(newId(), p.weekStart, p.text, bodyRev = 1, createdAt = now, updatedAt = now)
                w.insert(r)
                r.id to 1L
            }
            existing.body.isEmpty() && w.updateBody(existing.id, p.text, existing.bodyRev, now) == 1 -> existing.id to existing.bodyRev + 1
            else -> null
        }
    }

    private suspend fun writeDiaryBody(p: PendingText.Diary): Pair<String, Long>? {
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

    /** A new note is inserted; an existing one takes the text only at the session's version. Its sources land with it. */
    private suspend fun writeNoteBody(p: PendingText.Note): Pair<String, Long>? {
        if (notes.failBodyWrites) throw IllegalStateException("模拟写盘失败")
        val n = db.noteDao()
        val now = clock()
        val written = when {
            p.noteId == null -> {
                val note = Note(newId(), p.topicId?.takeIf { n.topic(it) != null }, p.text, bodyRev = 1, position = n.minPosition() - 1, createdAt = now, updatedAt = now)
                n.insert(note)
                note.id to 1L
            }
            n.updateBody(p.noteId, p.text, p.expectedRev ?: -1, now) == 1 -> p.noteId to (p.expectedRev!! + 1)
            else -> return null
        }
        for (s in p.sources) n.insertSource(NoteSource(written.first, s.kind, s.ref, now))
        return written
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
            } catch (e: CancellationException) {
                part.delete()
                throw e
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
        const val FORMAT_VERSION = 11

        /**
         * [file] with each account's current balance as its only valuation at [now], and one
         * stocktake at [now] over the accounts a stocktake would take: the trend then starts
         * today rather than at the next manual stocktake.
         */
        internal fun withoutLedgerHistory(file: BackupFile, now: Long): BackupFile {
            val accounts = file.accounts.map { a ->
                a.copy(baselineAt = now, baselineCost = if (a.isInvestment) balanceOf(a, file, now) else null)
            }
            val valuations = accounts.map { a -> Valuation(newId(), a.id, now, balanceOf(a, file, now), note = "期初", createdAt = now) }
            val snapshot = Snapshot(newId(), now, "数据初始化")
            val items = accounts.filter { !it.isDisabled }.map { a ->
                SnapshotItem(snapshot.id, a.id, valuations.first { it.accountId == a.id }.id, included = a.includeInTotal)
            }
            return file.copy(
                accounts = accounts,
                valuations = valuations,
                snapshots = listOf(snapshot),
                snapshotItems = items,
                flows = emptyList(),
            )
        }

        /** The balance as of [now]: a valuation dated later is not today's balance. */
        internal fun balanceOf(a: Account, file: BackupFile, now: Long = Long.MAX_VALUE): Long =
            Ledger.balanceAt(a.id, now, file.valuations, file.flows)
                ?: a.baselineCost
                ?: file.valuations.firstOrNull { it.accountId == a.id }?.value
                ?: 0L
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AssetBoardApp).container
        if (container.settings.backupTreeUri.value == null) return Result.success()
        return try {
            container.backup.backupNow(BackupKind.AUTO)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
