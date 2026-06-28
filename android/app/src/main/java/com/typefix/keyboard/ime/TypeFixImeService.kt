package com.typefix.keyboard.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.typefix.keyboard.BuildConfig
import com.typefix.keyboard.R
import com.typefix.keyboard.inference.Corrector
import com.typefix.keyboard.inference.GifClient
import com.typefix.keyboard.inference.InferenceService
import com.typefix.keyboard.inference.ModelManager
import com.typefix.keyboard.model.CorrectionMode
import com.typefix.keyboard.settings.AppSettings
import com.typefix.keyboard.ui.MicPermissionActivity
import com.typefix.keyboard.ui.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The TypeFix keyboard. Because an IME owns the [android.view.inputmethod.InputConnection],
 * reading and replacing text is clean — no event tap, no backspace/paste hack
 * like the macOS version needs.
 *
 * Behavior mirrors macOS TypeFix:
 *  - Manual mode: tap the ✨ Fix key to correct the current line.
 *  - Auto mode: correction fires after you pause typing (autoDelay), once the
 *    current line passes autoMinChars.
 *  - Password fields are never sent to a model.
 */
class TypeFixImeService : InputMethodService(), KeyboardListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var settings: AppSettings
    private var keyboard: KeyboardView? = null

    private var isSecureField = false
    private var autoJob: Job? = null
    private var correcting = false
    private var correctionJob: Job? = null
    private var thinkingJob: Job? = null
    private var writingJob: Job? = null
    private var backspaceJob: Job? = null
    private var gifSearchJob: Job? = null
    private var emojiSearchJob: Job? = null
    private var toneJob: Job? = null
    private var toneTarget: String? = null
    private var toneFlag: String? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var listening = false

    // The most recent fix, kept so the action-bar Undo can revert it.
    private var lastOriginal: String? = null
    private var lastCorrected: String? = null

    private val green = Color.parseColor("#37D67A")
    private val red = Color.parseColor("#FF6B6B")
    private val orange = Color.parseColor("#FF9F43")
    private val gray = Color.parseColor("#9AA0A6")
    private val accent = Color.parseColor("#5B6BF5")

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings.get(this)
        // Warm the swipe-typing dictionary off the UI thread.
        scope.launch { withContext(Dispatchers.Default) { GestureDecoder.ensureLoaded(applicationContext) } }
    }

    override fun onCreateInputView(): View {
        // Draw edge-to-edge, and paint the whole IME window with the keyboard
        // background colour. In landscape the framework insets the input view on
        // the side (leaving a transparent strip that shows the app behind); a
        // window background fills that strip so the keyboard reads as full-width.
        window?.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            w.setBackgroundDrawable(ColorDrawable(ContextCompat.getColor(this, R.color.kb_bg)))
            // Let the IME window span the full display width in landscape instead
            // of being inset by the side gesture-nav panel.
            w.addFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            )
            w.attributes = w.attributes.apply { width = WindowManager.LayoutParams.MATCH_PARENT }
        }
        val view = KeyboardView(this, this)
        keyboard = view
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        isSecureField = info != null && isPasswordField(info)

        val s = settings.snapshot()
        if (s.provider.isLocal) {
            val id = s.localModelId.ifBlank { s.model }
            // Keep the model warm via a foreground service. Starting an FGS from
            // an IME can be blocked by background-start rules; the model still
            // loads in-process on first use, so failure here is non-fatal.
            if (ModelManager.isInstalled(this, id)) {
                runCatching { InferenceService.start(this) }
            }
        }

        keyboard?.setAutoModeIndicator(s.correctionMode == CorrectionMode.AUTO)
        keyboard?.hint(
            when {
                isSecureField -> "Password field · TypeFix off"
                s.correctionMode == CorrectionMode.AUTO -> "Auto · hold ✨ to toggle"
                else -> "Manual · tap ✨ Fix · hold to arm Auto"
            }
        )
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        autoJob?.cancel()
        backspaceJob?.cancel()
        toneJob?.cancel()
        cancelInFlightFix()
        keyboard?.cancelAutoCountdown()
    }

    override fun onDestroy() {
        autoJob?.cancel()
        backspaceJob?.cancel()
        toneJob?.cancel()
        stopAllHaptics()
        speechRecognizer?.destroy()
        speechRecognizer = null
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    // ---- KeyboardListener ----

    override fun onChar(text: String) {
        cancelInFlightFix()
        clearLastFix()
        keyboard?.clearTone()
        currentInputConnection?.commitText(text, 1)
        recordWordIfBoundary(text)
        scheduleAutoCorrection()
        scheduleToneCheck()
    }

    /** Feeds completed words to phrase memory so niche vocabulary gets learned. */
    private fun recordWordIfBoundary(committed: String) {
        if (!settings.snapshot().phraseMemoryEnabled) return
        val c = committed.lastOrNull() ?: return
        if (!(c.isWhitespace() || c in ".,!?;:")) return
        val before = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString() ?: return
        var end = before.length
        while (end > 0 && (before[end - 1].isWhitespace() || before[end - 1] in ".,!?;:\"')(")) end--
        if (end == 0) return
        var start = end
        while (start > 0 && !before[start - 1].isWhitespace()) start--
        val word = before.substring(start, end)
        if (word.isNotEmpty()) PhraseMemory.record(applicationContext, word)
    }

    override fun onBackspace() {
        cancelInFlightFix()
        clearLastFix()
        keyboard?.clearTone()
        if (!deleteSelectionIfAny()) currentInputConnection?.deleteSurroundingText(1, 0)
        scheduleAutoCorrection()
        scheduleToneCheck()
    }

    /** If text is highlighted, delete the whole selection and report true. */
    private fun deleteSelectionIfAny(): Boolean {
        val ic = currentInputConnection ?: return false
        val selected = ic.getSelectedText(0)
        if (selected.isNullOrEmpty()) return false
        ic.commitText("", 1) // replacing the selection with "" deletes it
        return true
    }

    override fun onBackspacePressed() {
        cancelInFlightFix()
        clearLastFix()
        backspaceJob?.cancel()
        // A highlighted selection is deleted whole on the very first press.
        if (deleteSelectionIfAny()) return
        backspaceJob = scope.launch {
            currentInputConnection?.deleteSurroundingText(1, 0) // immediate (also the single-tap case)
            delay(350) // hold threshold before auto-repeat kicks in
            // Hold-to-erase stays character-by-character and just accelerates — no
            // jump to whole-word deletion (that felt unpredictable).
            var interval = 90L
            while (isActive) {
                val ic = currentInputConnection ?: break
                if (ic.getTextBeforeCursor(1, 0).isNullOrEmpty()) break
                ic.deleteSurroundingText(1, 0)
                delay(interval)
                interval = (interval - 8).coerceAtLeast(34) // accelerate
            }
        }
    }

    override fun onBackspaceReleased() {
        backspaceJob?.cancel()
        backspaceJob = null
        scheduleAutoCorrection()
    }

    override fun onEnter() {
        autoJob?.cancel()
        val ic = currentInputConnection ?: return
        // Honor editor actions (Send/Search/Go) when present.
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
        if (action != null && action != EditorInfo.IME_ACTION_NONE &&
            (currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0)
        ) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
    }

    override fun onFix() {
        if (correcting) { onCancelFix(); return }
        runCorrection(force = true)
    }

    override fun onCancelFix() {
        correctionJob?.cancel()
        stopAllHaptics()
        correcting = false
        keyboard?.flash("Cancelled", gray)
    }

    override fun onToneFix() {
        val target = toneTarget ?: return
        val flag = toneFlag ?: return
        keyboard?.setStatus("Rewriting…", accent)
        scope.launch {
            try {
                val rewritten = Corrector.rewriteForTone(applicationContext, target, flag, settings.snapshot())
                if (rewritten != null && rewritten != target && replaceTrailing(target, rewritten)) {
                    lastOriginal = target
                    lastCorrected = rewritten
                    keyboard?.showUndo("Toned down", green)
                } else {
                    keyboard?.flash("Couldn't rewrite", orange)
                }
            } finally {
                toneTarget = null
                toneFlag = null
            }
        }
    }

    /** When tone check is on, flag the draft's tone a moment after you pause typing. */
    private fun scheduleToneCheck() {
        val s = settings.snapshot()
        if (!s.toneCheckEnabled || isSecureField) return
        toneJob?.cancel()
        toneJob = scope.launch {
            delay(1300)
            val before = currentInputConnection?.getTextBeforeCursor(MAX_CONTEXT, 0)?.toString() ?: return@launch
            val target = before.substringAfterLast('\n').trim()
            if (target.length < 12) return@launch
            val flag = Corrector.checkTone(applicationContext, target, s) ?: return@launch
            toneTarget = target
            toneFlag = flag
            keyboard?.showTone(Corrector.toneLabels[flag] ?: return@launch, orange)
        }
    }

    override fun onUndo() {
        val original = lastOriginal ?: return
        val corrected = lastCorrected ?: return
        val ic = currentInputConnection
        if (ic != null) {
            val nowTrailing = ic.getTextBeforeCursor(corrected.length, 0)?.toString()
            if (nowTrailing == corrected) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(corrected.length, 0)
                ic.commitText(original, 1)
                ic.endBatchEdit()
                keyboard?.flash("Reverted", gray)
            }
        }
        clearLastFix()
    }

    override fun onGestureWord(crossedKeys: String) {
        clearLastFix()
        scope.launch {
            val word = withContext(Dispatchers.Default) { GestureDecoder.decode(crossedKeys) }
            if (word != null) {
                currentInputConnection?.commitText("$word ", 1)
            } else {
                // Short/ambiguous swipe that didn't resolve to a word — just type
                // the starting letter so the gesture isn't lost entirely.
                currentInputConnection?.commitText(crossedKeys.take(1), 1)
            }
            scheduleAutoCorrection()
        }
    }

    override fun onOpenSettings() {
        startActivity(
            Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun onSwitchKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
    }

    override fun onHideKeyboard() {
        requestHideSelf(0)
    }

    override fun onToggleAutoMode() {
        val next = if (settings.correctionMode == CorrectionMode.AUTO) {
            CorrectionMode.MANUAL
        } else {
            CorrectionMode.AUTO
        }
        settings.correctionMode = next
        keyboard?.setAutoModeIndicator(next == CorrectionMode.AUTO)
        if (next != CorrectionMode.AUTO) {
            autoJob?.cancel()
            keyboard?.cancelAutoCountdown()
        }
    }

    override fun onEmojiPanelShown() {
        val text = currentInputConnection?.getTextBeforeCursor(200, 0)?.toString().orEmpty()
        // Instant, offline suggestions first…
        keyboard?.setEmojiSuggestions(EmojiSuggester.suggest(text))
        // …then refine with the model if one is configured and ready.
        if (text.isNotBlank()) {
            scope.launch {
                val llm = Corrector.suggestEmojis(applicationContext, text, settings.snapshot())
                if (llm.isNotEmpty()) keyboard?.setEmojiSuggestions(llm)
            }
        }
    }

    override fun onEmojiSearchQuery(query: String) {
        // Local fuzzy results already show instantly per keystroke. The LLM
        // refine is debounced (and the prior one cancelled) so we don't queue a
        // slow on-device search for every character typed.
        emojiSearchJob?.cancel()
        emojiSearchJob = scope.launch {
            delay(600)
            val results = Corrector.searchEmojis(applicationContext, query, settings.snapshot())
            if (results.isNotEmpty()) keyboard?.setEmojiSearchResults(results)
        }
    }

    override fun onGifPanelShown() {
        val key = klipyKey()
        if (key.isBlank()) {
            keyboard?.flash("GIF search unavailable", orange)
            return
        }
        keyboard?.setGifLoading()
        val s = settings.snapshot()
        if (s.gifIntentEnabled) {
            val text = currentInputConnection?.getTextBeforeCursor(200, 0)?.toString().orEmpty()
            val intent = ReactionIntent.gifQueryFor(text)
            if (intent != null) {
                scope.launch { keyboard?.setGifResults(GifClient.search(intent, key)) }
                return
            }
        }
        scope.launch { keyboard?.setGifResults(GifClient.featured(key)) }
    }

    override fun onGifSearchQuery(query: String, immediate: Boolean) {
        val key = klipyKey()
        if (key.isBlank()) return
        gifSearchJob?.cancel()
        keyboard?.setGifLoading()
        gifSearchJob = scope.launch {
            // Wait for a typing pause (Enter sends immediate=true) so we don't
            // hammer the API on every keystroke.
            if (!immediate) delay(1000)
            keyboard?.setGifResults(GifClient.search(query, key))
        }
    }

    override fun onCursorMove(steps: Int) {
        if (steps == 0) return
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps).coerceAtMost(60)) { sendDownUpKeyEvents(code) }
    }

    /** The user's own KLIPY key if set, otherwise the bundled one. */
    private fun klipyKey(): String =
        settings.snapshot().klipyApiKey.ifBlank { BuildConfig.KLIPY_API_KEY }

    override fun onGifSelected(gifUrl: String) {
        scope.launch { insertGif(gifUrl) }
    }

    private suspend fun insertGif(gifUrl: String) {
        val ic = currentInputConnection ?: return
        val editorInfo = currentInputEditorInfo
        val supported = editorInfo?.let { EditorInfoCompat.getContentMimeTypes(it) }.orEmpty()
        val acceptsGif = supported.any { it == "image/gif" || it == "image/*" || it == "*/*" }

        if (!acceptsGif) {
            // The target app can't accept inline GIFs — copy the link instead.
            (getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("gif", gifUrl))
            keyboard?.flash("App can't embed GIFs · link copied", orange)
            return
        }

        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(cacheDir, "gifs").apply { mkdirs() }
                val out = java.io.File(dir, "tf_${System.currentTimeMillis()}.gif")
                GifClient.download(gifUrl, out)
                out
            }.getOrNull()
        } ?: run {
            keyboard?.flash("Couldn't load GIF", orange)
            return
        }

        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val info = InputContentInfoCompat(
            uri,
            android.content.ClipDescription("gif", arrayOf("image/gif")),
            null,
        )
        val flags = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
        val ok = InputConnectionCompat.commitContent(ic, editorInfo!!, info, flags, null)
        keyboard?.flash(if (ok) "GIF inserted" else "Couldn't insert GIF", if (ok) green else orange)
    }

    // ---- Speech to text ----

    override fun onMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            startActivity(
                Intent(this, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            keyboard?.flash("Allow the mic, then tap again", orange)
            return
        }
        if (listening) { stopListening(); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            keyboard?.flash("Speech recognition unavailable", orange)
            return
        }
        startListening()
    }

    private fun startListening() {
        val recognizer = speechRecognizer
            ?: SpeechRecognizer.createSpeechRecognizer(this).also { speechRecognizer = it }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { keyboard?.setStatus("Listening…", red) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { keyboard?.setStatus("Transcribing…", red) }
            override fun onError(error: Int) {
                listening = false
                keyboard?.flash("Didn't catch that", orange)
            }
            override fun onResults(results: Bundle?) {
                listening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) {
                    keyboard?.flash("Nothing heard", orange)
                    return
                }
                clearLastFix()
                if (settings.snapshot().voiceCleanupEnabled) {
                    keyboard?.setStatus("Cleaning up…", accent)
                    scope.launch {
                        val cleaned = Corrector.cleanupVoice(applicationContext, text, settings.snapshot()) ?: text
                        currentInputConnection?.commitText("$cleaned ", 1)
                        keyboard?.flash("Inserted", green)
                    }
                } else {
                    currentInputConnection?.commitText("$text ", 1)
                    keyboard?.flash("Inserted", green)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        listening = true
        recognizer.startListening(intent)
    }

    private fun stopListening() {
        listening = false
        speechRecognizer?.stopListening()
    }

    // ---- Correction ----

    private fun scheduleAutoCorrection() {
        val s = settings.snapshot()
        if (s.correctionMode != CorrectionMode.AUTO || isSecureField) {
            keyboard?.cancelAutoCountdown()
            return
        }
        autoJob?.cancel()
        // Only show the countdown when the current line is long enough to fix.
        val before = currentInputConnection?.getTextBeforeCursor(MAX_CONTEXT, 0)?.toString().orEmpty()
        val target = before.substringAfterLast('\n').trim()
        if (target.length >= s.autoMinChars) keyboard?.startAutoCountdown(s.autoDelayMs)
        else keyboard?.cancelAutoCountdown()
        autoJob = scope.launch {
            delay(s.autoDelayMs)
            runCorrection(force = false)
        }
    }

    private fun runCorrection(force: Boolean) {
        if (correcting) return
        if (isSecureField) {
            keyboard?.flash("Password field · TypeFix off", orange)
            return
        }
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(MAX_CONTEXT, 0)?.toString() ?: return
        val target = before.substringAfterLast('\n')
        if (target.trim().isEmpty()) return

        val s = settings.snapshot()
        if (!force && s.correctionMode == CorrectionMode.AUTO && target.trim().length < s.autoMinChars) {
            return
        }

        correcting = true
        keyboard?.cancelAutoCountdown()
        keyboard?.showThinking()
        startThinkingHaptics()
        correctionJob = scope.launch {
            try {
                val result = Corrector.correct(applicationContext, target, s)
                when (result) {
                    is Corrector.Result.Fixed -> {
                        if (result.changed && replaceTrailing(target, result.text)) {
                            lastOriginal = target
                            lastCorrected = result.text
                            // Switch from the sparse "thinking" beat to a fast,
                            // dense "writing" flurry while the new text lands.
                            stopThinkingHaptics()
                            keyboard?.setStatus("Writing…", accent)
                            startWritingHaptics()
                            delay((result.text.length * 14L).coerceIn(220L, 700L))
                            stopWritingHaptics()
                            keyboard?.showUndo(
                                if (result.possibleTypo) "Fixed · check spelling" else "Fixed",
                                if (result.possibleTypo) orange else green,
                            )
                        } else {
                            keyboard?.flash("Looks good", green)
                        }
                    }
                    is Corrector.Result.Failed -> keyboard?.flash(result.message, orange)
                }
            } finally {
                stopAllHaptics()
                correcting = false
            }
        }
    }

    private fun cancelInFlightFix() {
        if (correcting) {
            correctionJob?.cancel()
            stopAllHaptics()
            correcting = false
        }
    }

    /**
     * "Thinking" beat: methodical and sparse — slower, evenly spaced ticks, like
     * someone deliberately mulling it over. Loops until we move to the writing
     * phase or the fix is cancelled.
     */
    private fun startThinkingHaptics() {
        thinkingJob?.cancel()
        thinkingJob = scope.launch {
            while (isActive) {
                Haptics.tick(applicationContext, (10..16).random().toLong(), (110..160).random())
                delay((200..340).random().toLong())
            }
        }
    }

    /**
     * "Writing" flurry: fast and dense — many quick, snappy ticks in rapid
     * succession, like the message being typed out. Plays only while the new
     * text is landing.
     */
    private fun startWritingHaptics() {
        writingJob?.cancel()
        writingJob = scope.launch {
            while (isActive) {
                Haptics.tick(applicationContext, (5..10).random().toLong(), (130..220).random())
                delay((24..58).random().toLong())
            }
        }
    }

    private fun stopThinkingHaptics() {
        thinkingJob?.cancel()
        thinkingJob = null
        Haptics.cancel(applicationContext)
    }

    private fun stopWritingHaptics() {
        writingJob?.cancel()
        writingJob = null
        Haptics.cancel(applicationContext)
    }

    private fun stopAllHaptics() {
        thinkingJob?.cancel()
        thinkingJob = null
        writingJob?.cancel()
        writingJob = null
        Haptics.cancel(applicationContext)
    }

    private fun clearLastFix() {
        if (lastOriginal != null || lastCorrected != null) {
            lastOriginal = null
            lastCorrected = null
            keyboard?.clearUndo()
        }
    }

    /**
     * Replaces [original] (the trailing text we corrected) with [corrected].
     * Re-checks that the trailing text is still what we corrected, so a user who
     * kept typing during inference doesn't get the wrong region clobbered.
     */
    private fun replaceTrailing(original: String, corrected: String): Boolean {
        val ic = currentInputConnection ?: return false
        val nowTrailing = ic.getTextBeforeCursor(original.length, 0)?.toString()
        if (nowTrailing != original) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(original.length, 0)
        ic.commitText(corrected, 1)
        ic.endBatchEdit()
        return true
    }

    private fun isPasswordField(info: EditorInfo): Boolean {
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    companion object {
        private const val MAX_CONTEXT = 4000
    }
}
