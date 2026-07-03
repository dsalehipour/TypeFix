package com.typefix.keyboard.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.typefix.keyboard.R
import com.typefix.keyboard.ui.SettingsActivity

/**
 * Posts a "new version available" notification found by the background
 * [UpdateCheckWorker]. Tapping it opens Settings and jumps straight to a fresh
 * update check so the Updates card shows the download button.
 */
object UpdateNotifier {

    private const val CHANNEL_ID = "typefix_updates"
    private const val NOTIFICATION_ID = 44
    private const val PREFS = "typefix_update"
    private const val KEY_NOTIFIED = "notifiedVersion"

    fun notifyIfNew(context: Context, info: UpdateChecker.UpdateInfo) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Don't re-nag every 6h for the same version the user already saw.
        if (prefs.getString(KEY_NOTIFIED, null) == info.versionName) return
        // Respect the user's notification choice (POST_NOTIFICATIONS on 13+).
        val manager = NotificationManagerCompat.from(app)
        if (!manager.areNotificationsEnabled()) return

        ensureChannel(app)
        val tap = PendingIntent.getActivity(
            app,
            0,
            Intent(app, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_SHOW_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val bigText = buildString {
            append("Version ${info.versionName} is available. Tap to update.")
            if (info.notes.isNotBlank()) {
                append("\n\n")
                append(info.notes.lineSequence().take(4).joinToString("\n"))
            }
        }
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_typefix)
            .setContentTitle("TypeFix update available")
            .setContentText("Tap to update to v${info.versionName}.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
            prefs.edit().putString(KEY_NOTIFIED, info.versionName).apply()
        } catch (_: SecurityException) {
            // Permission revoked between the check above and now; ignore.
        }
    }

    private fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Notifies you when a new TypeFix version is available." }
            )
        }
    }
}
