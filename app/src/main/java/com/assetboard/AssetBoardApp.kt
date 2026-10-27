package com.assetboard

import android.app.Application
import android.content.Context
import android.os.StrictMode
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.assetboard.data.AccountManager
import com.assetboard.data.AppDatabase
import com.assetboard.data.AppUpdater
import com.assetboard.data.BackupKind
import com.assetboard.data.BackupManager
import com.assetboard.data.BackupWorker
import com.assetboard.data.CrashLog
import com.assetboard.data.DiaryRepository
import com.assetboard.data.EditorRegistry
import com.assetboard.data.FoodRepository
import com.assetboard.data.GoalRepository
import com.assetboard.data.HabitRepository
import com.assetboard.data.HealthRepository
import com.assetboard.data.HolidayRepository
import com.assetboard.data.LedgerRepository
import com.assetboard.data.MaintenanceWorker
import com.assetboard.data.NoteRepository
import com.assetboard.data.Profile
import com.assetboard.data.ProfileStore
import com.assetboard.data.SettingsStore
import com.assetboard.data.SyncGateway
import com.assetboard.data.SyncWorker
import com.assetboard.data.TodoRepository
import com.assetboard.data.WeatherService
import com.assetboard.data.WeekRepository
import com.assetboard.data.WriteGate
import com.assetboard.reminder.ReminderScheduler
import com.assetboard.data.CountdownRepository
import com.assetboard.widget.CountdownWidget
import com.assetboard.widget.NetWorthWidget
import com.assetboard.widget.NoteWidget
import com.assetboard.widget.TodoWidget
import com.assetboard.widget.WidgetDay
import com.assetboard.widget.WidgetRefresh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Hand-wired dependencies for one local profile. The todo, diary, note, network and reminder
 * parts are lazy and fail on their own: nothing in them may keep the asset screens from starting.
 * Switching accounts builds a new container around a different database file; nothing here is
 * shared between profiles except [profiles] and the device settings.
 */
class AppContainer(
    private val context: Context,
    val profile: Profile = Profile.local(),
    dbName: String? = null,
    val profiles: ProfileStore = ProfileStore(context),
    private val onSwitchProfile: suspend (Profile) -> Unit = {},
    private val syncApi: SyncGateway? = null,
) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.build(context, dbName)
    val settings = SettingsStore(context)
    val gate = WriteGate(db, crashOnMissingEpoch = BuildConfig.DEBUG)

    init {
        // A database opened for a profile has no epoch of its own yet. Give it one no other
        // profile on this device has used, before any alarm or widget token is minted from it.
        gate.ensureEpochAtLeast(profiles.nextProfileEpoch())
    }

    private val refreshWidget: () -> Unit = { WidgetRefresh.request(context, appScope, ::NetWorthWidget) }
    val repository = LedgerRepository(db, gate, appScope, onChanged = refreshWidget)

    val holidays by lazy { HolidayRepository(context, db) }
    val reminders by lazy { ReminderScheduler(context, this) }
    val todos by lazy {
        TodoRepository(db, gate, appScope, calendar = { holidays.table.value }) { ids ->
            reminders.onTodosChanged(ids)
            WidgetRefresh.request(context, appScope, ::TodoWidget)
        }
    }
    /** Every open diary and note editor; a restore commits their unsaved text first. */
    val editors = EditorRegistry()
    val diary by lazy { DiaryRepository(db, gate, editors = editors) }
    val notes by lazy { NoteRepository(db, gate, editors) { WidgetRefresh.request(context, appScope, ::NoteWidget) } }
    val countdowns by lazy { CountdownRepository(db, gate) { WidgetRefresh.request(context, appScope, ::CountdownWidget) } }
    val weeks by lazy { WeekRepository(db, gate, editors) }
    val habits by lazy { HabitRepository(db, gate) }
    val health by lazy { HealthRepository(db, gate) }
    val food by lazy { FoodRepository(db, gate) }
    val goal by lazy { GoalRepository(db, gate) }
    val weather by lazy { WeatherService(context, settings) }
    val updater by lazy { AppUpdater(context, settings, appScope) { backup.backupNow(BackupKind.AUTO) } }

    private val _restoreGeneration = MutableStateFlow(0)
    /** Bumped after each successful restore: every tab returns to its home with fresh ViewModels. */
    val restoreGeneration: StateFlow<Int> = _restoreGeneration.asStateFlow()

    private val _announcements = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4)
    /** Messages for the app shell, which outlives the screens that a restore rebuilds. */
    val announcements: SharedFlow<String> = _announcements.asSharedFlow()

    fun announce(message: String) {
        _announcements.tryEmit(message)
    }

    fun announcementHandled() {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        _announcements.resetReplayCache()
    }

    val backup by lazy {
        BackupManager(
            context, db, settings, gate, diary, notes, editors,
            calendar = { holidays.table.value },
            effects = reminders,
            onRestored = {
                profiles.raiseProfileEpoch(gate.epochNow())
                _restoreGeneration.value++
                appScope.launch { WidgetRefresh.all(context) }
            },
            weeks = weeks,
            profileId = profile.id,
        )
    }

    val account by lazy {
        AccountManager(
            context, settings, profiles, profile, backup, appScope, onSwitchProfile,
            syncApi ?: com.assetboard.data.SyncApi { settings.networkEnabled.value },
        )
    }

    /**
     * Holds the gate and commits unsaved text before another profile's database is opened. The
     * old container is never used again whether this succeeds or not; on failure the caller
     * unfreezes it and stays put.
     */
    suspend fun prepareForProfileSwitch() {
        gate.freeze()
        try {
            backup.flushEditorsForSwitch()
            reminders.cancelEverything(db.todoDao().allTodos().map { it.id })
        } catch (t: Throwable) {
            gate.unfreeze()
            throw t
        }
    }

    fun close() {
        appScope.cancel()
        runCatching { db.close() }
    }
}

class AssetBoardApp : Application() {
    private lateinit var profiles: ProfileStore
    @Volatile private var activeContainer: AppContainer? = null

    /** The profile shown right now; widget receivers and reminders read it every time. */
    val container: AppContainer get() = activeContainer ?: error("AppContainer is not ready")

    private val _sessionGeneration = MutableStateFlow(0)
    /** Bumped after a profile switch; activities rebuild their composition from it. */
    val sessionGeneration: StateFlow<Int> = _sessionGeneration.asStateFlow()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val switchLock = Mutex()

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        if (BuildConfig.DEBUG) strictMode()
        // Before anything opens Room: moves the pre-account database to the first profile's name.
        profiles = ProfileStore(this)
        val active = profiles.active()
        activeContainer = AppContainer(this, active, active.dbFile, profiles, ::switchProfile)
        WidgetDay.schedule(this)
        container.appScope.launch { runCatching { CrashLog.recordExitReasons(this@AssetBoardApp, container.settings) } }
        val work = WorkManager.getInstance(this)
        work.enqueueUniquePeriodicWork(
            "daily-backup",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS).build(),
        )
        work.enqueueUniquePeriodicWork(
            "daily-maintenance",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS).build(),
        )
        work.enqueueUniquePeriodicWork(
            "daily-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
        // New modules start in the background; a failure there only affects that module.
        container.appScope.launch {
            runCatching { container.holidays.init() }
            runCatching { container.reminders.onAppStart() }
            // Widgets placed by an older build get the profile key they did not carry.
            runCatching { WidgetRefresh.all(this@AssetBoardApp) }
        }
        container.account.onAppStart()
    }

    /**
     * Switches to another local profile: flush, cancel the old profile's alarms, open the new
     * database, swap the container, rebuild the UI, schedule the new profile's alarms. The old
     * database is closed a moment later so the composition being torn down can finish reading.
     */
    suspend fun switchProfile(target: Profile) = switchLock.withLock {
        val old = activeContainer ?: return@withLock
        if (old.profile.id == target.id) {
            profiles.setActive(target.id)
            return@withLock
        }
        old.prepareForProfileSwitch()
        withContext(NonCancellable) {
            profiles.setActive(target.id)
            val next = try {
                AppContainer(this@AssetBoardApp, target, target.dbFile, profiles, ::switchProfile)
            } catch (e: Exception) {
                profiles.setActive(old.profile.id)
                old.gate.unfreeze()
                throw e
            }
            activeContainer = next
            _sessionGeneration.value += 1
            appScope.launch {
                delay(2_000)
                old.close()
            }
            next.appScope.launch {
                runCatching { next.reminders.onAppStart() }
                runCatching { WidgetRefresh.all(this@AssetBoardApp) }
                // A profile reached by signing in should show its data now, not after the
                // startup delay.
                runCatching { next.account.syncNow() }
            }
        }
    }

    /**
     * Debug builds only: disk and network on the main thread, and SQLite cursors or streams left
     * open, show up in logcat. Logged, never fatal, so it only tells.
     */
    private fun strictMode() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder().detectLeakedSqlLiteObjects().detectLeakedClosableObjects().detectActivityLeaks().penaltyLog().build(),
        )
    }
}

object BuildConfigInfo {
    fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
}