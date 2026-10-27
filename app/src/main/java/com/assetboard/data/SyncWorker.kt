package com.assetboard.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.assetboard.AssetBoardApp
import kotlinx.coroutines.CancellationException

/**
 * The quiet background sync. It only ever downloads what the user's own device uploaded, and a
 * conflict is left for the account page: a worker never chooses which copy to keep.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AssetBoardApp).container
        if (!container.account.isSignedIn()) return Result.success()
        return try {
            container.account.syncNow()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The state carries the reason; retrying an auth failure would only drain the battery.
            Result.success()
        }
    }
}