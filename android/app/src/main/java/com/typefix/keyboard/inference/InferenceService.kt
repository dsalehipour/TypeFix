package com.typefix.keyboard.inference

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.typefix.keyboard.R
import com.typefix.keyboard.settings.AppSettings
import kotlinx.coroutines.launch

/**
 * Keeps the app process (and therefore the loaded on-device model) alive while
 * the keyboard is in use, so we don't reload the model on every keypress
 * session. Mirrors PrivateLM's `InferenceService`.
 */
class InferenceService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundCompat()

        val settings = AppSettings.get(this).snapshot()
        if (settings.provider.isLocal) {
            val id = settings.localModelId.ifBlank { settings.model }
            if (ModelManager.isInstalled(this, id)) {
                lifecycleScope.launch {
                    runCatching { InferenceController.ensureLoaded(applicationContext, id) }
                }
            }
        }
        return START_STICKY
    }

    /**
     * Android 14+ (`shortService`) and Android 15+ (`dataSync`) enforce a runtime
     * budget on foreground services — a `dataSync` FGS may only run ~6 hours per
     * 24h, after which the system calls onTimeout and, if we don't stop, kills the
     * process with a ForegroundServiceDidNotStopInTimeException. That's what made
     * TypeFix "crash overnight" when left running. Stop cleanly instead; the
     * keyboard restarts this service (and reloads the model on demand) next time
     * it's used.
     */
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "Foreground service timed out; stopping to avoid a crash.")
        stopSelfSafely()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service (type=$fgsType) timed out; stopping to avoid a crash.")
        stopSelfSafely()
    }

    private fun stopSelfSafely() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { stopSelf() }
    }

    private fun startForegroundCompat() {
        val channelId = ensureChannel()
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("TypeFix")
            .setContentText("On-device model ready")
            .setSmallIcon(R.drawable.ic_typefix)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel(): String {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "TypeFix model", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return CHANNEL_ID
    }

    companion object {
        private const val TAG = "InferenceService"
        private const val CHANNEL_ID = "typefix_model"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, InferenceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, InferenceService::class.java))
        }
    }
}
