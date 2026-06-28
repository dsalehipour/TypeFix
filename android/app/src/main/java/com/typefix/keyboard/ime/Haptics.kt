package com.typefix.keyboard.ime

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.typefix.keyboard.settings.AppSettings

/**
 * Thin wrapper over the platform vibrator. Every call is gated by the user's
 * `vibrationEnabled` setting (on by default), so callers don't have to check.
 */
object Haptics {

    private fun enabled(context: Context): Boolean =
        AppSettings.get(context).snapshot().vibrationEnabled

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** A single short tick. [amplitude] is 1..255 (falls back to default if unsupported). */
    fun tick(context: Context, durationMs: Long, amplitude: Int) {
        if (!enabled(context)) return
        val v = vibrator(context) ?: return
        if (!v.hasVibrator()) return
        val amp = if (v.hasAmplitudeControl()) amplitude.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
        v.vibrate(VibrationEffect.createOneShot(durationMs.coerceAtLeast(1), amp))
    }

    /** A waveform (timings alternate off/on starting with off). */
    fun waveform(context: Context, timings: LongArray, amplitudes: IntArray?) {
        if (!enabled(context)) return
        val v = vibrator(context) ?: return
        if (!v.hasVibrator()) return
        val effect = if (amplitudes != null && v.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(timings, amplitudes, -1)
        } else {
            VibrationEffect.createWaveform(timings, -1)
        }
        v.vibrate(effect)
    }

    /** Two stronger, quick buzzes — used to confirm the Auto toggle flipped. */
    fun doubleStrong(context: Context) {
        waveform(
            context,
            timings = longArrayOf(0, 40, 70, 55),
            amplitudes = intArrayOf(0, 210, 0, 255),
        )
    }

    fun cancel(context: Context) {
        vibrator(context)?.cancel()
    }
}
