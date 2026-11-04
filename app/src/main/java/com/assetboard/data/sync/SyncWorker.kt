package com.assetboard.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.assetboard.AssetBoardApp
import kotlinx.coroutines.CancellationException

/**
 * The background sync for when the app is closed. It runs the same merge as the account page, so
 * it may upload as well as download; WorkManager's shortest interval is 15 minutes, and the
 * shorter settings are served by the in-app ticker while a screen is open.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AssetBoardApp).container
        if (!container.account.isSignedIn()) return Result.success()
        return try {
            when (container.account.syncNow()) {
                // A dropped connection or a 5xx comes back later with WorkManager's backoff.
                SyncOutcome.RETRY -> Result.retry()
                else -> Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The state carries the reason; retrying an auth failure would only drain the battery.
            Result.success()
        }
    }
}