package com.typefix.keyboard.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a downloaded APK to Android's system package installer. Android always
 * shows its own confirmation UI for a sideloaded install — there is no silent
 * path for an ordinary app — so the most we can do is take the user straight to
 * that "Update" screen (or, the first time, to the "allow this source" toggle).
 */
object ApkInstaller {

    fun install(context: Context, apk: File) {
        val app = context.applicationContext
        // Android 8+: the app needs the user's per-source "install unknown apps"
        // toggle. If it isn't granted yet, send them there instead of failing.
        if (!app.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${app.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(settings)
            return
        }

        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", apk)
        val install = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.startActivity(install)
    }
}
