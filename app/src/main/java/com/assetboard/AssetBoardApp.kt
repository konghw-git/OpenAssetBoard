package com.assetboard

import android.app.Application
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.assetboard.data.AppDatabase
import com.assetboard.data.BackupManager
import com.assetboard.data.BackupWorker
import com.assetboard.data.LedgerRepository
import com.assetboard.data.SettingsStore
import com.assetboard.widget.NetWorthWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.build(context)
    val settings = SettingsStore(context)
    private val refreshWidget: () -> Unit = { appScope.launch { NetWorthWidget.refresh(context) } }
    val repository = LedgerRepository(db, appScope, onChanged = refreshWidget)
    val backup = BackupManager(context, db, settings, onRestored = refreshWidget)
}

class AssetBoardApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily-backup",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS).build(),
        )
    }
}

object BuildConfigInfo {
    fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
}
