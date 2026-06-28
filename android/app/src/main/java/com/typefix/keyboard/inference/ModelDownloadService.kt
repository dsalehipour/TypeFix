package com.typefix.keyboard.inference

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.typefix.keyboard.R
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps a model download alive while the screen is off
 * or the app is backgrounded. It holds a partial wake lock and shows a progress
 * notification, and stops itself when [ModelDownloads] goes idle.
 */
class ModelDownloadService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "typefix:model-download")
            .apply {
                setReferenceCounted(false)
                acquire(2 * 60 * 60 * 1000L) // safety cap; released in onDestroy
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundCompat(ModelDownloads.state.value)
        lifecycleScope.launch {
            ModelDownloads.state.collect { s ->
                if (!s.active) stopSelf() else updateNotification(s)
            }
        }
        return START_NOT_STICKY
    }

    private fun updateNotification(s: ModelDownloads.State) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(s))
    }

    private fun startForegroundCompat(s: ModelDownloads.State) {
        val notification = buildNotification(s)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(s: ModelDownloads.State): Notification {
        val pct = (s.progress * 100).toInt().coerceIn(0, 100)
        return NotificationCompat.Builder(this, ensureChannel())
            .setContentTitle("Downloading model")
            .setContentText("${s.label.ifBlank { "Model" }} · $pct%")
            .setSmallIcon(R.drawable.ic_typefix)
            .setOngoing(true)
            .setProgress(100, pct, s.progress <= 0f)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureChannel(): String {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return CHANNEL_ID
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "typefix_download"
        private const val NOTIFICATION_ID = 43

        fun start(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ModelDownloadService::class.java))
        }
    }
}
