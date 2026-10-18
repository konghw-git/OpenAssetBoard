package com.assetboard

import android.app.Application
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.assetboard.data.AppDatabase
import com.assetboard.data.BackupManager
import com.assetboard.data.BackupWorker
import com.assetboard.data.DiaryRepository
import com.assetboard.data.HabitRepository
import com.assetboard.data.HolidayRepository
import com.assetboard.data.LedgerRepository
import com.assetboard.data.MaintenanceWorker
import com.assetboard.data.SettingsStore
import com.assetboard.data.TodoRepository
import com.assetboard.data.WeatherService
import com.assetboard.data.WriteGate
import com.assetboard.reminder.ReminderScheduler
import com.assetboard.widget.NetWorthWidget
import com.assetboard.widget.TodoWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Hand-wired dependencies. The todo, diary, network and reminder parts are lazy and fail on their
 * own: nothing in them may keep the asset screens from starting.
 */
class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.build(context)
    val settings = SettingsStore(context)
    val gate = WriteGate(db, crashOnMissingEpoch = BuildConfig.DEBUG)
    private val refreshWidget: () -> Unit = { appScope.launch { NetWorthWidget.refresh(context) } }
    val repository = LedgerRepository(db, gate, appScope, onChanged = refreshWidget)

    val holidays by lazy { HolidayRepository(context, db) }
    val reminders by lazy { ReminderScheduler(context, this) }
    val todos by lazy {
        TodoRepository(db, gate, appScope, calendar = { holidays.table.value }) { ids ->
            reminders.onTodosChanged(ids)
            appScope.launch { TodoWidget.refresh(context) }
        }
    }
    val diary by lazy { DiaryRepository(db, gate) }
    val habits by lazy { HabitRepository(db, gate) }
    val weather by lazy { WeatherService(context, settings) }

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
            context, db, settings, gate, diary,
            calendar = { holidays.table.value },
            effects = reminders,
            onRestored = {
                _restoreGeneration.value++
                refreshWidget()
            },
        )
    }
}

class AssetBoardApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
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
        // New modules start in the background; a failure there only affects that module.
        container.appScope.launch {
            runCatching { container.holidays.init() }
            runCatching { container.reminders.onAppStart() }
        }
    }
}

object BuildConfigInfo {
    fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
}
