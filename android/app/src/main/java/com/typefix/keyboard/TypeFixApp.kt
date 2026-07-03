package com.typefix.keyboard

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.typefix.keyboard.settings.AppSettings
import com.typefix.keyboard.update.UpdateCheckWorker
import java.util.concurrent.TimeUnit

class TypeFixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Warm the settings singleton.
        AppSettings.get(this)
        scheduleUpdateChecks()
    }

    /**
     * Enqueue a periodic background update check that notifies the user when a
     * newer release exists, even when the app is closed. Unique + KEEP so it's
     * scheduled once and survives reboots.
     */
    private fun scheduleUpdateChecks() {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            UpdateCheckWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
