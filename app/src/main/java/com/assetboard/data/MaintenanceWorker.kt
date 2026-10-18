package com.assetboard.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.assetboard.AssetBoardApp
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Daily: purge trash batches older than 30 days, refresh holidays (weekly, from November), re-sync reminders. */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AssetBoardApp).container
        // Bound once, when the task starts.
        val epoch = container.gate.bound()
        runCatching { withContext(epoch) { container.todos.purgeExpired() } }
        val settings = container.settings
        val today = LocalDate.now()
        val lastCheck = settings.long(KEY_HOLIDAY_CHECK)
        val weekly = System.currentTimeMillis() - lastCheck > 6 * 24 * 3600_000L
        if (settings.holidayUpdateAllowed && weekly && (today.monthValue >= 11 || !container.holidays.table.value.hasYear(today.year))) {
            runCatching { container.holidays.updateOnline(today) }
            settings.putLong(KEY_HOLIDAY_CHECK, System.currentTimeMillis())
        }
        runCatching { container.reminders.syncAll(fromBoot = false) }
        return Result.success()
    }

    private companion object {
        const val KEY_HOLIDAY_CHECK = "holiday_checked_at"
    }
}
