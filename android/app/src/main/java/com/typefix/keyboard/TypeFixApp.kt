package com.typefix.keyboard

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.typefix.keyboard.settings.AppSettings
import com.typefix.keyboard.ui.SettingsActivity
import com.typefix.keyboard.update.UpdateCheckWorker
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

class TypeFixApp : Application() {

    // How many of our activities are currently in the foreground. If we crash
    // while the user is looking at our own UI, we bounce it back automatically.
    private var foregroundActivities = 0

    override fun onCreate() {
        super.onCreate()
        // Warm the settings singleton.
        AppSettings.get(this)
        installCrashHandler()
        scheduleUpdateChecks()
    }

    /**
     * Last-resort crash recovery. An uncaught exception normally kills the whole
     * process. The keyboard (IME) is rebound by the system automatically on the
     * next text-field focus, so it effectively self-restarts. For our own UI we
     * also schedule a relaunch a moment after the crash — but only when one of
     * our activities was on screen, so we never yank the user out of another app
     * just because a background job threw. We still chain to the platform's
     * default handler so the process terminates cleanly (and OEM crash reporting
     * still fires).
     */
    private fun installCrashHandler() {
        registerActivityLifecycleCallbacks(object : SimpleActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { foregroundActivities++ }
            override fun onActivityStopped(activity: Activity) {
                foregroundActivities = (foregroundActivities - 1).coerceAtLeast(0)
            }
        })

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception — recovering", throwable)
            if (foregroundActivities > 0) runCatching { scheduleUiRestart() }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                exitProcess(2)
            }
        }
    }

    /** Relaunch the settings screen ~500ms after a crash via a one-shot alarm. */
    private fun scheduleUiRestart() {
        val intent = Intent(this, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            this,
            RESTART_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.set(AlarmManager.RTC, System.currentTimeMillis() + 500, pending)
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

    companion object {
        private const val TAG = "TypeFixApp"
        private const val RESTART_REQUEST_CODE = 4210
    }
}

/** Only the two lifecycle callbacks we care about; the rest are no-ops. */
private interface SimpleActivityLifecycleCallbacks : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
