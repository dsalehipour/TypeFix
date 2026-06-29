package com.typefix.keyboard.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.typefix.keyboard.inference.Corrector
import com.typefix.keyboard.settings.AppSettings
import kotlinx.coroutines.launch

/**
 * System-wide "Fix with TypeFix" action. Android doesn't let one keyboard add a
 * button to another keyboard's toolbar, but ACTION_PROCESS_TEXT adds an item to
 * the text-selection menu of ANY app — so you can keep using the Samsung keyboard
 * for typing and still run the TypeFix LLM fix on selected text:
 * select text → ⋮ → "Fix with TypeFix".
 */
class ProcessTextActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selected = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        val readOnly = intent?.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false) ?: false
        if (selected.isNullOrBlank()) {
            finish()
            return
        }

        val settings = AppSettings.get(this)
        val snapshot = settings.snapshot()
        if (!Corrector.isBackendReady(this, snapshot)) {
            toast("TypeFix: set up an on-device model or API key in Settings first")
            finish()
            return
        }

        setContentView(buildOverlay())

        lifecycleScope.launch {
            when (val result = Corrector.correct(applicationContext, selected, snapshot)) {
                is Corrector.Result.Fixed -> when {
                    !result.changed -> {
                        toast("TypeFix: looks good already")
                        finish()
                    }
                    readOnly -> {
                        // The field is read-only, so we can't replace it — copy instead.
                        copyToClipboard(result.text)
                        toast("TypeFix: copied the fix to your clipboard")
                        finish()
                    }
                    else -> {
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result.text),
                        )
                        finish()
                    }
                }
                is Corrector.Result.Failed -> {
                    toast("TypeFix: ${result.message}")
                    finish()
                }
            }
        }
    }

    /** A small centered "Fixing…" card over a dimmed backdrop. */
    private fun buildOverlay(): FrameLayout {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#66000000"))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = dp(20)
            setPadding(pad, dp(16), pad, dp(16))
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }
        card.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(24), dp(24)))
        card.addView(TextView(this).apply {
            text = "Fixing with TypeFix…"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(16), 0, 0, 0)
        })
        root.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER },
        )
        return root
    }

    private fun copyToClipboard(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("TypeFix", text))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
