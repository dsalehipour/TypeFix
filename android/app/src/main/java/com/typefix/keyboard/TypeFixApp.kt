package com.typefix.keyboard

import android.app.Application
import com.typefix.keyboard.settings.AppSettings

class TypeFixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Warm the settings singleton.
        AppSettings.get(this)
    }
}
