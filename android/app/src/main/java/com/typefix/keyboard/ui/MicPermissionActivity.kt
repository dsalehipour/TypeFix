package com.typefix.keyboard.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * A tiny, transparent activity whose only job is to request the microphone
 * permission on behalf of the keyboard (an IME can't request runtime
 * permissions itself). It finishes immediately after the user responds.
 */
class MicPermissionActivity : ComponentActivity() {

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMic.launch(Manifest.permission.RECORD_AUDIO)
    }
}
