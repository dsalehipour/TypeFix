package com.typefix.keyboard.ime

import android.Manifest
import android.content.ClipboardManager
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
    private var gifSuggestJob: Job? = null
    private var emojiSearchJob: Job? = null
    private var emojiSuggestJob: Job? = null
    private var suggestJob: Job? = null
    private var toneJob: Job? = null
    private var toneTarget: String? = null
    private var toneFlag: String? = null

    // Offline autocorrect-on-space: the word we just auto-replaced (with the
    // original the user typed) so backspacing back to it can revert it, plus the
    // set of words the user reverted so we never re-fix them this session.
    private var pendingAutoFixOriginal: String? = null
    private var pendingAutoFixApplied: String? = null
    private val rejectedAutoFix = HashSet<String>()
    // Caret position right after the applied fix (absolute), so we revert OUR fix
    // and never a later identical word — works no matter how far the user typed
    // and then backspaced. -1 = no anchor / position unknown.
    private var autoFixAnchor = -1
    private var lastSelEnd = 0
    private var selectionKnown = false
    // Phrase memory: a word the user just reverted an autocorrect on. If they keep
    // it (move past it with a space), that's one "kept revert" toward learning it.
    private var pendingKeepWord: String? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var listening = false
    // The user's intent to keep dictating: stays true across the automatic
    // session restarts that let them talk for as long as they want, until they
    // tap the mic again.
    private var wantListening = false

    // The most recent fix, kept so the action-bar Undo can revert it.
    private var lastOriginal: String? = null
    private var lastCorrected: String? = null

    // Recent-copy "tap to paste" chip in the action bar: the latest clip text, when
    // it was recorded, and whether the user dismissed it this empty-field session.
    private var lastClipText: String? = null
    private var lastClipAtMs = 0L
    private var clipSuggestionDismissed = false

    private val green = Color.parseColor("#37D67A")
    private val red = Color.parseColor("#FF6B6B")
    private val orange = Color.parseColor("#FF9F43")
    private val gray = Color.parseColor("#9AA0A6")
    private val accent = Color.parseColor("#5B6BF5")

    private val clipboardManager by lazy {
        getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
    }
    private val clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener { captureClipboard() }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings.get(this)
        // Warm the swipe-typing dictionary off the UI thread.
        scope.launch { withContext(Dispatchers.Default) { GestureDecoder.ensureLoaded(applicationContext) } }
        // Build a recent-clipboard history (Android only exposes the current clip).
        runCatching { clipboardManager.addPrimaryClipChangedListener(clipChangedListener) }
    }

    /** Records the current clip into history when readable (keyboard shown / on copy). */
    private fun captureClipboard() {
        val text = runCatching {
            clipboardManager.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull()
        ClipboardHistory.add(applicationContext, text)
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isNotEmpty() && trimmed != lastClipText) {
            // A fresh copy: remember it and re-offer the paste chip.
            lastClipText = trimmed
            lastClipAtMs = System.currentTimeMillis()
            clipSuggestionDismissed = false
        }
        refreshClipSuggestion()
    }

    /**
     * Shows or hides the "tap to paste" chip. We only offer it while the field is
     * empty (the user hasn't started typing) and there's a recent, non-dismissed
     * copy. Emptying the field again re-arms it.
     */
    private fun refreshClipSuggestion() {
        val kb = keyboard ?: return
        if (isSecureField || !isFieldEmpty()) {
            // Typing into the field re-arms the chip for when it's cleared again.
            clipSuggestionDismissed = false
            kb.setClipboardSuggestion(null)
            return
        }
        if (clipSuggestionDismissed) {
            kb.setClipboardSuggestion(null)
            return
        }
        kb.setClipboardSuggestion(currentOfferableClip())
    }

    /** True when the edited field has no text at all (caret has nothing around it). */
    private fun isFieldEmpty(): Boolean {
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(1, 0)
        val after = ic.getTextAfterCursor(1, 0)
        return before.isNullOrEmpty() && after.isNullOrEmpty()
    }

    /** The recent clip to offer, or null if there's none or it's gone stale. */
    private fun currentOfferableClip(): String? {
        val text = lastClipText?.takeIf { it.isNotEmpty() } ?: return null
        if (System.currentTimeMillis() - lastClipAtMs > CLIP_SUGGESTION_TTL_MS) return null
        return text
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
        // A new field starts with no revertable autocorrect pending.
        clearPendingAutoFix()
        pendingKeepWord = null
        val selEnd = info?.initialSelEnd ?: -1
        selectionKnown = selEnd >= 0
        lastSelEnd = if (selEnd >= 0) selEnd else 0
        // Always reveal the normal letter keyboard, never the last panel/symbols page.
        keyboard?.resetToLetters()
        // A new field re-arms the paste chip; captureClipboard then offers it if the
        // field is empty and there's a recent copy.
        clipSuggestionDismissed = false
        captureClipboard()

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

        // The autocorrect validity dictionary is large; warm it off-thread so the
        // first space doesn't hitch while it loads.
        if (s.autocorrectOnSpace) {
            scope.launch { withContext(Dispatchers.Default) { GestureDecoder.ensureValidLoaded(applicationContext) } }
        }

        keyboard?.setAutoModeIndicator(s.correctionMode == CorrectionMode.AUTO)
        // Show the action buttons immediately — no transient hint text on open.
        refreshAutoCaps()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        selectionKnown = true
        lastSelEnd = newSelEnd
        // A real (multi-character) selection means the user is selecting text — drop
        // any pending autocorrect revert so we don't fire it in the wrong place.
        if (newSelStart != newSelEnd) {
            clearPendingAutoFix()
            pendingKeepWord = null
        }
        refreshClipSuggestion()
        refreshAutoCaps()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        autoJob?.cancel()
        backspaceJob?.cancel()
        toneJob?.cancel()
        if (wantListening) stopListening()
        cancelInFlightFix()
        keyboard?.cancelAutoCountdown()
    }

    override fun onDestroy() {
        autoJob?.cancel()
        backspaceJob?.cancel()
        toneJob?.cancel()
        wantListening = false
        runCatching { clipboardManager.removePrimaryClipChangedListener(clipChangedListener) }
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
        // Offline autocorrect: fix an obvious typo the moment the user hits space.
        // The pending fix is kept alive across further typing so the user can keep
        // typing and later backspace all the way back to revert it.
        val didAutoFix = text == " " && maybeAutoFixOnSpace()
        if (!didAutoFix) currentInputConnection?.commitText(text, 1)
        maybeConfirmKeptRevert(text)
        updateSuggestions()
        refreshClipSuggestion()
        refreshAutoCaps()
        scheduleAutoCorrection()
        scheduleToneCheck()
    }

    /** When [AppSettings.autocorrectOnSpace] is on, replace a just-typed typo with
     *  a confident offline fix and commit the space. Returns true if it fired. */
    private fun maybeAutoFixOnSpace(): Boolean {
        if (!settings.snapshot().autocorrectOnSpace || isSecureField) return false
        val ic = currentInputConnection ?: return false
        val after = ic.getTextAfterCursor(1, 0)?.toString().orEmpty()
        if (after.isNotEmpty() && !after[0].isWhitespace()) return false // mid-word, leave it
        val before = ic.getTextBeforeCursor(48, 0)?.toString().orEmpty()
        val word = before.takeLastWhile { !it.isWhitespace() }
        // Letters, or letters with a stray digit ("hav3") — but never pure numbers
        // or anything with punctuation. (autoFix itself gates short words: only the
        // explicit fix map applies below 3 letters, e.g. "im", "i".)
        if (word.isEmpty() || !word.all { it.isLetterOrDigit() } || word.none { it.isLetter() }) return false
        if (word.lowercase() in rejectedAutoFix) return false
        if (settings.snapshot().protectedWords.any { it.equals(word, ignoreCase = true) }) return false
        // A word phrase memory has learned (reverted & kept 3x) is never re-fixed.
        if (settings.snapshot().phraseMemoryEnabled &&
            PhraseMemory.isLearned(applicationContext, word)
        ) return false
        val fixLower = GestureDecoder.autoFix(applicationContext, word) ?: return false
        val fixed = applyCaseLike(word, fixLower)
        if (fixed == word) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText("$fixed ", 1)
        ic.endBatchEdit()
        pendingAutoFixOriginal = word
        pendingAutoFixApplied = fixed
        // Anchor = caret position right after "fixed " so we can recognise it later
        // even after the user keeps typing and backspaces back to it.
        autoFixAnchor = if (selectionKnown) lastSelEnd - word.length + fixed.length + 1 else -1
        return true
    }

    /** Reverts the pending autocorrect when the user backspaces back to it, and
     *  remembers not to fix that word again. Returns true if it handled the press. */
    private fun revertAutoFixIfPending(): Boolean {
        val applied = pendingAutoFixApplied ?: return false
        val original = pendingAutoFixOriginal ?: return false
        val ic = currentInputConnection ?: return false
        // Only OUR fix instance: when we know the caret position it must be exactly
        // at the anchor (otherwise it's a different, identical word the user typed).
        if (selectionKnown && autoFixAnchor >= 0 && lastSelEnd != autoFixAnchor) return false
        val before = ic.getTextBeforeCursor(applied.length + 1, 0)?.toString().orEmpty()
        if (before != "$applied ") return false // the fixed word + its space is gone
        pendingAutoFixApplied = null
        pendingAutoFixOriginal = null
        autoFixAnchor = -1
        ic.beginBatchEdit()
        ic.deleteSurroundingText(applied.length + 1, 0)
        ic.commitText(original, 1) // restore what they typed, caret right after it
        ic.endBatchEdit()
        rejectedAutoFix.add(original.lowercase())
        // If the user now keeps this word, it counts toward phrase-memory learning.
        if (settings.snapshot().phraseMemoryEnabled) pendingKeepWord = original
        return true
    }

    private fun clearPendingAutoFix() {
        pendingAutoFixOriginal = null
        pendingAutoFixApplied = null
        autoFixAnchor = -1
    }

    private fun refreshAutoCaps() {
        keyboard?.setAutoShift(shouldAutoCap())
    }

    /** True when the next character should be capitalized (start of text or after
     *  a sentence terminator) for an ordinary text field. */
    private fun shouldAutoCap(): Boolean {
        if (isSecureField || !autoCapAllowedForField()) return false
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(3, 0)?.toString() ?: return false
        if (before.isEmpty()) return true                 // start of the field
        if (!before.last().isWhitespace()) return false   // mid-word
        val trimmed = before.trimEnd()
        if (trimmed.isEmpty()) return true                // only whitespace before (new line/start)
        return trimmed.last() in charArrayOf('.', '!', '?', '\n')
    }

    private fun autoCapAllowedForField(): Boolean {
        val type = currentInputEditorInfo?.inputType ?: return true
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return when (type and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            -> false
            else -> true
        }
    }

    /** Carry the original word's capitalization onto the corrected word (looking at
     *  letters only, so a stray digit doesn't throw off ALL-CAPS detection). */
    private fun applyCaseLike(original: String, fixedLower: String): String {
        val letters = original.filter { it.isLetter() }
        return when {
            letters.length > 1 && letters.all { it.isUpperCase() } -> fixedLower.uppercase()
            letters.firstOrNull()?.isUpperCase() == true -> fixedLower.replaceFirstChar { it.uppercase() }
            else -> fixedLower
        }
    }

    /** Live typo-fix/autocomplete chips for the word currently being typed. */
    private fun updateSuggestions() {
        suggestJob?.cancel()
        if (isSecureField) { keyboard?.setSuggestions(emptyList()); return }
        val before = currentInputConnection?.getTextBeforeCursor(48, 0)?.toString().orEmpty()
        val word = before.takeLastWhile { !it.isWhitespace() }
        if (word.length < 2 || !word.all { it.isLetter() }) {
            keyboard?.setSuggestions(emptyList())
            return
        }
        suggestJob = scope.launch {
            val sugg = withContext(Dispatchers.Default) { GestureDecoder.suggest(word) }
            keyboard?.setSuggestions(sugg)
        }
    }

    override fun onSuggestionPicked(word: String) {
        cancelInFlightFix()
        clearPendingAutoFix()
        pendingKeepWord = null
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(48, 0)?.toString().orEmpty()
        val partial = before.takeLastWhile { !it.isWhitespace() }
        ic.beginBatchEdit()
        if (partial.isNotEmpty()) ic.deleteSurroundingText(partial.length, 0)
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
        keyboard?.setSuggestions(emptyList())
        refreshClipSuggestion()
        refreshAutoCaps()
        scheduleAutoCorrection()
        scheduleToneCheck()
    }

    override fun onClipboardPaste(text: String) {
        cancelInFlightFix()
        clearLastFix()
        keyboard?.clearTone()
        clearPendingAutoFix()
        pendingKeepWord = null
        currentInputConnection?.commitText(text, 1)
        ClipboardHistory.add(applicationContext, text) // bump to most-recent
        // It's been pasted: drop the chip until the next copy.
        clipSuggestionDismissed = true
        keyboard?.setClipboardSuggestion(null)
        updateSuggestions()
        refreshAutoCaps()
        scheduleAutoCorrection()
        scheduleToneCheck()
    }

    override fun onClipboardSuggestionDismissed() {
        clipSuggestionDismissed = true
        keyboard?.setClipboardSuggestion(null)
    }

    /** Phrase memory learns a word once the user reverts its autocorrect and keeps
     *  it [PhraseMemory.THRESHOLD] times. This confirms the "keep": after a revert
     *  set [pendingKeepWord], a space/punctuation that follows the still-intact word
     *  counts it; editing or deleting the word cancels the pending keep. */
    private fun maybeConfirmKeptRevert(committed: String) {
        val word = pendingKeepWord ?: return
        val c = committed.lastOrNull() ?: return
        if (c.isWhitespace() || c in ".,!?;:") {
            pendingKeepWord = null
            if (!settings.snapshot().phraseMemoryEnabled) return
            // Confirm the reverted word is still sitting right before this boundary.
            val before = currentInputConnection?.getTextBeforeCursor(word.length + 6, 0)?.toString().orEmpty()
            val trimmed = before.dropLastWhile { it.isWhitespace() || it in ".,!?;:" }
            val tail = trimmed.takeLastWhile { !it.isWhitespace() }
            if (tail.equals(word, ignoreCase = true)) {
                PhraseMemory.recordKeptRevert(applicationContext, word)
            }
        } else if (c.isLetterOrDigit() || c == '\'') {
            // They're extending/altering the word — no longer a clean keep.
            pendingKeepWord = null
        }
    }

    override fun onBackspace() {
        cancelInFlightFix()
        clearLastFix()
        keyboard?.clearTone()
        // A backspace right after an autocorrect reverts the fix instead of deleting.
        if (!revertAutoFixIfPending()) {
            pendingKeepWord = null // a real delete cancels a pending phrase-memory keep
            if (!deleteSelectionIfAny()) currentInputConnection?.deleteSurroundingText(1, 0)
        }
        updateSuggestions()
        refreshClipSuggestion()
        refreshAutoCaps()
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
        // A backspace right after an autocorrect reverts the fix instead of deleting.
        if (revertAutoFixIfPending()) {
            updateSuggestions()
            refreshAutoCaps()
            scheduleAutoCorrection()
            scheduleToneCheck()
            return
        }
        pendingKeepWord = null // a real delete cancels a pending phrase-memory keep
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
                Haptics.tick(applicationContext, 5, 45) // a tick per character erased while held
                delay(interval)
                interval = (interval - 8).coerceAtLeast(34) // accelerate
            }
        }
    }

    override fun onBackspaceReleased() {
        backspaceJob?.cancel()
        backspaceJob = null
        updateSuggestions()
        refreshClipSuggestion()
        refreshAutoCaps()
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

    override fun canUndo(): Boolean = lastOriginal != null && lastCorrected != null

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

    override fun onGestureWord(word: String) {
        clearLastFix()
        clearPendingAutoFix()
        val ic = currentInputConnection ?: return
        // Capitalize like a normal first letter at a sentence start.
        val out = if (shouldAutoCap()) word.replaceFirstChar { it.uppercase() } else word
        ic.commitText("$out ", 1)
        updateSuggestions()
        refreshClipSuggestion()
        refreshAutoCaps()
        scheduleAutoCorrection()
        scheduleToneCheck()
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

    /** The recent bit of what the user is writing, used to seed emoji/GIF context.
     *  Scoped to the current line and the last couple of sentences so suggestions
     *  reflect the current thought instead of reaching back across the whole draft. */
    private fun recentMessageContext(): String {
        // Only look a short way back, and never across a line break (earlier lines
        // are usually a different message/paragraph).
        var t = currentInputConnection?.getTextBeforeCursor(120, 0)?.toString().orEmpty()
        t = t.substringAfterLast('\n')
        // Keep just the current sentence-ish tail (after an earlier . ! ?), so a
        // finished sentence before the current one doesn't skew the vibe.
        val cut = t.dropLast(1).indexOfLast { it in ".!?" }
        if (cut >= 0) t = t.substring(cut + 1)
        return t.trim()
    }

    override fun onEmojiPanelShown() {
        val text = recentMessageContext()
        val s = settings.snapshot()
        emojiSuggestJob?.cancel()
        // No model (or empty message) → just the instant offline suggestions.
        if (text.isBlank() || !Corrector.isBackendReady(applicationContext, s)) {
            keyboard?.setEmojiSuggestions(EmojiSuggester.suggest(text))
            return
        }
        // Otherwise show a loading shimmer + the "thinking" haptics while the model
        // picks emojis that fit the message, then a brief "done" buzz.
        emojiSuggestJob = scope.launch {
            keyboard?.setEmojiSuggestionsLoading()
            startThinkingHaptics()
            val llm = try {
                Corrector.suggestEmojis(applicationContext, text, s)
            } finally {
                stopThinkingHaptics()
            }
            if (llm.isNotEmpty()) {
                keyboard?.setEmojiSuggestions(llm)
                panelDoneHaptics()
            } else {
                keyboard?.setEmojiSuggestions(EmojiSuggester.suggest(text))
            }
        }
    }

    override fun onEmojiSearchQuery(query: String) {
        // Local fuzzy results already show instantly per keystroke. The LLM refine
        // is debounced (and the prior cancelled). When it runs we show a loading
        // shimmer + the same thinking/done haptics as a correction.
        val s = settings.snapshot()
        emojiSearchJob?.cancel()
        if (!Corrector.isBackendReady(applicationContext, s)) return
        emojiSearchJob = scope.launch {
            delay(600)
            keyboard?.setEmojiSearchLoading()
            startThinkingHaptics()
            val results = try {
                Corrector.searchEmojis(applicationContext, query, s)
            } finally {
                stopThinkingHaptics()
            }
            keyboard?.setEmojiSearchResults(results) // empty restores local matches
            if (results.isNotEmpty()) panelDoneHaptics()
        }
    }

    /** Brief "writing" flurry to signal a panel model task finished (mirrors a fix). */
    private suspend fun panelDoneHaptics() {
        startWritingHaptics()
        delay(200)
        stopWritingHaptics()
    }

    override fun onContentPanelChanged() {
        // Leaving/switching a panel: stop any in-flight panel work + its haptics so
        // the "thinking" buzz doesn't linger after the panel closes.
        emojiSuggestJob?.cancel(); emojiSuggestJob = null
        emojiSearchJob?.cancel(); emojiSearchJob = null
        gifSearchJob?.cancel(); gifSearchJob = null
        gifSuggestJob?.cancel(); gifSuggestJob = null
        if (!correcting) {
            stopThinkingHaptics()
            stopWritingHaptics()
        }
    }

    override fun onGifPanelShown() {
        val key = klipyKey()
        if (key.isBlank()) {
            keyboard?.flash("GIF search unavailable", orange)
            return
        }
        val s = settings.snapshot()
        val text = recentMessageContext()
        keyboard?.setGifLoading()
        gifSuggestJob?.cancel()
        gifSuggestJob = scope.launch {
            // Contextual suggestions like emoji: an LLM-derived query from the
            // message (with the same thinking/done haptics), then the offline vibe
            // map as a fallback, then trending if neither applies.
            var query: String? = null
            if (text.isNotBlank() && Corrector.isBackendReady(applicationContext, s)) {
                startThinkingHaptics()
                query = try {
                    Corrector.suggestGifQuery(applicationContext, text, s)
                } finally {
                    stopThinkingHaptics()
                }
            }
            if (query.isNullOrBlank() && s.gifIntentEnabled) query = ReactionIntent.gifQueryFor(text)
            val results = if (!query.isNullOrBlank()) GifClient.search(query, key)
            else GifClient.featured(key)
            keyboard?.setGifResults(results)
            if (!query.isNullOrBlank() && results.isNotEmpty()) panelDoneHaptics()
        }
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
        repeat(kotlin.math.abs(steps).coerceAtMost(60)) {
            sendDownUpKeyEvents(code)
            Haptics.tick(applicationContext, 5, 45) // a tick per character the cursor moves
        }
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
        if (wantListening) { stopListening(); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            keyboard?.flash("Speech recognition unavailable", orange)
            return
        }
        wantListening = true
        startListeningSession()
    }

    /** One recognition session. While [wantListening] is true we transparently
     *  restart after each result/silence so the user can dictate indefinitely. */
    private fun startListeningSession() {
        val recognizer = speechRecognizer
            ?: SpeechRecognizer.createSpeechRecognizer(this).also { speechRecognizer = it }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { keyboard?.setStatus("Listening… tap mic to stop", red) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                listening = false
                when {
                    !wantListening -> {}
                    // Permission / fatal: stop and tell the user.
                    error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        wantListening = false
                        keyboard?.flash("Mic permission needed", orange)
                    }
                    // No speech / timeout / busy → just listen again (keeps it alive).
                    else -> restartListeningSoon()
                }
            }
            override fun onResults(results: Bundle?) {
                listening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) {
                    if (wantListening) restartListeningSoon() else keyboard?.flash("Nothing heard", orange)
                    return
                }
                clearLastFix()
                if (settings.snapshot().voiceCleanupEnabled) {
                    keyboard?.setStatus("Cleaning up…", accent)
                    scope.launch {
                        val cleaned = Corrector.cleanupVoice(applicationContext, text, settings.snapshot()) ?: text
                        currentInputConnection?.commitText("$cleaned ", 1)
                        // Restart only after committing, so chunks stay in order.
                        if (wantListening) startListeningSession() else keyboard?.flash("Done", green)
                    }
                } else {
                    currentInputConnection?.commitText("$text ", 1)
                    if (wantListening) restartListeningSoon() else keyboard?.flash("Done", green)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Don't end the session on a short pause (best-effort; OEM-dependent).
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 6000L)
        }
        listening = true
        recognizer.startListening(intent)
    }

    private fun restartListeningSoon() {
        if (!wantListening) return
        // A small gap avoids ERROR_RECOGNIZER_BUSY when reusing the recognizer.
        scope.launch {
            delay(180)
            if (wantListening) startListeningSession()
        }
    }

    private fun stopListening() {
        wantListening = false
        listening = false
        runCatching { speechRecognizer?.cancel() }
        keyboard?.flash("Stopped", gray)
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

        // How long after a copy we keep offering the "tap to paste" chip (1 hour).
        private const val CLIP_SUGGESTION_TTL_MS = 60L * 60L * 1000L
    }
}
