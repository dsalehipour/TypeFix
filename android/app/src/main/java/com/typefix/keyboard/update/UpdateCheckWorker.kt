package com.typefix.keyboard.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodic background check (via WorkManager) that notifies the user when a
 * newer release exists — even while the app is closed. Runs roughly every few
 * hours when the device has a network connection; Android may delay it during
 * Doze, which is fine for a non-urgent update nudge.
 */
class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val info = UpdateChecker.findNewer()
        if (info != null) UpdateNotifier.notifyIfNew(applicationContext, info)
        Result.success()
    } catch (e: Exception) {
        // Transient network/API failure — let WorkManager retry with backoff.
        Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "typefix-update-check"
    }
}
