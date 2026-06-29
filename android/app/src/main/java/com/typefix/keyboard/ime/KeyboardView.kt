package com.typefix.keyboard.ime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import coil.ImageLoader
import coil.decode.ImageDecoderDecoder
import coil.load
import com.typefix.keyboard.R
import com.typefix.keyboard.inference.GifClient
import kotlin.math.hypot

/** Callbacks the IME implements to react to keyboard events. */
interface KeyboardListener {
    fun onChar(text: String)
    fun onBackspace()
    fun onBackspacePressed()
    fun onBackspaceReleased()
    fun onEnter()
    fun onFix()
    fun onUndo()
    fun onGestureWord(word: String)
    fun onOpenSettings()
    fun onSwitchKeyboard()
    fun onEmojiPanelShown()
    fun onEmojiSearchQuery(query: String)
    fun onGifPanelShown()
    fun onGifSearchQuery(query: String, immediate: Boolean)
    fun onGifSelected(gifUrl: String)
    fun onMic()
    fun onHideKeyboard()
    fun onToggleAutoMode()
    fun onCancelFix()
    fun onToneFix()
    /** Move the text caret by [steps] (negative = left) — space-bar trackpad. */
    fun onCursorMove(steps: Int)
    /** The visible content panel changed (so in-flight panel work can be stopped). */
    fun onContentPanelChanged()
    /** A suggestion/typo-fix chip was tapped — replace the current word with it. */
    fun onSuggestionPicked(word: String)
}

/**
 * A QWERTY keyboard styled like the Samsung Keyboard, with:
 *  - a top toolbar of icons that doubles as the status/HUD + Fix + Undo area,
 *  - an always-on number row, white rounded keys, gray function keys,
 *  - key-press preview bubbles and long-press accents/symbols,
 *  - basic swipe/gesture typing,
 *  - emoji and clipboard panels,
 *  - light and dark themes (via resources).
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class KeyboardView(
    context: Context,
    private val listener: KeyboardListener,
) : LinearLayout(context) {

    private val toolbarIcons: LinearLayout
    private val suggestionRow: LinearLayout
    private var lastSuggestions: List<String> = emptyList()
    private var suggestionsCollapsed = false
    private val statusRow: LinearLayout
    private val statusLabel: TextView
    private val undoButton: TextView
    private val cancelButton: TextView
    private val toneButton: TextView
    private val contentContainer: FrameLayout
    private val keyRows: LinearLayout

    private val letterKeys = mutableListOf<TextView>()
    private val gestureKeys = mutableListOf<Pair<TextView, Char>>()
    private var shiftKey: ImageView? = null

    private var shifted = false
    private var capsLock = false
    private var lastShiftTapAt = 0L
    private var symbols = false
    private var symbolsPage = 0

    private var emojiSuggestions: List<String> = emptyList()
    private var emojiSuggestedRow: LinearLayout? = null
    private var emojiPanelOpen = false

    private enum class SearchMode { NONE, EMOJI, GIF }

    private var searchMode = SearchMode.NONE
    private val searchQuery = StringBuilder()
    private var searchResultsRow: LinearLayout? = null
    private var searchQueryLabel: TextView? = null

    // Which content panel is showing, so a toolbar icon can toggle it closed.
    private var activePanel = "keyboard"
    private var searchCaretOn = true
    private val caretBlink = object : Runnable {
        override fun run() {
            searchCaretOn = !searchCaretOn
            renderSearchLabel()
            postDelayed(this, 500)
        }
    }
    private var gifLoadingAnimator: ValueAnimator? = null
    private var emojiLoadingAnimator: ValueAnimator? = null

    // Tiles marked with this tag (emoji/GIF results) get a celebratory haptic when
    // picked instead of the normal key-down tick.
    private val panelTileTag = Any()
    private val hitLoc = IntArray(2)

    // Clipboard panel multi-select (long-press to enter).
    private var clipSelecting = false
    private val clipSelected = linkedSetOf<String>()

    private var micLongPressed = false
    private var micRunnable: Runnable? = null

    private val gifLoader: ImageLoader by lazy {
        ImageLoader.Builder(context).components { add(ImageDecoderDecoder.Factory()) }.build()
    }

    /** Wide/unfolded devices (e.g. Z Fold) get a center gap + split spacebar. */
    private val wide: Boolean get() = resources.configuration.screenWidthDp >= 600

    /** Left/right margin. Split mode mirrors Samsung (~5.2% of width). */
    private fun sidePadding(): Int =
        if (wide) dp((resources.configuration.screenWidthDp * 0.052f).toInt()) else dp(12)

    private val colBg = color(R.color.kb_bg)
    private val colText = color(R.color.kb_key_text)
    private val colTextSecondary = color(R.color.kb_key_text_secondary)
    private val colAccent = color(R.color.kb_accent)
    private val colIcon = color(R.color.kb_icon)
    private val colLetterKey = color(R.color.kb_key_letter)

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Per-finger touch state, keyed by pointer id, so several keys can be
    // pressed/held at once (fast rollover typing) without cross-talk.
    private class CharTouch(
        val view: TextView,
        val baseChar: String,
        val gestureEligible: Boolean,
        val downScreenX: Float,
        val downScreenY: Float,
    ) {
        var gesturing = false
        var longPressActive = false
        var lastCrossedChar: Char? = null
        val crossed = StringBuilder()
        var longPressRunnable: Runnable? = null
        // Raw finger samples (screen coords) for the shape-based swipe decoder.
        val points = ArrayList<FloatArray>(64)
    }

    private val charTouches = HashMap<Int, CharTouch>()

    private var previewPopup: PopupWindow? = null
    private var previewText: TextView? = null
    private var alternatesPopup: PopupWindow? = null

    private var countdownBar: View? = null
    private var countdownAnimator: ValueAnimator? = null

    private var autoModeOn = false
    private var sparkleIcon: ImageView? = null
    private var sparkleDot: View? = null
    private var holdPopup: PopupWindow? = null
    private var holdView: HoldProgressView? = null
    private var holdAnimator: ValueAnimator? = null
    private var holdCancelled = false
    private var holdCompleted = false
    private var sparkleHoldRunnable: Runnable? = null
    private var nextHoldTick = 0f

    private val revertStatus = Runnable { showIconsBar() }

    init {
        orientation = VERTICAL
        setBackgroundColor(colBg)
        // No horizontal padding on the root so the action bar is full-bleed; the
        // side margin is applied to the keys (contentContainer) instead.
        setPadding(0, 0, 0, dp(8))
        // Let a second finger hit another key while one is still held (fast typing).
        isMotionEventSplittingEnabled = true

        // Render edge-to-edge (full width) and only pad the bottom for the nav
        // bar / home pill so keys clear it. Without this, the IME framework
        // leaves a transparent gap on the side in landscape (the app shows
        // through), which is the "not full width" issue on wide screens.
        fitsSystemWindows = false
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(0, 0, 0, maxOf(dp(6), bars.bottom) + dp(2))
            // Keys get the Samsung-style side margin + a small gap below the action bar.
            contentContainer.setPadding(sidePadding(), dp(6), sidePadding(), 0)
            insets
        }

        toolbarIcons = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusLabel = TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        undoButton = TextView(context).apply {
            text = "↶ Undo"
            isAllCaps = false
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setTextColor(colAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = pill(color(R.color.kb_func_active))
            visibility = GONE
            setOnClickListener {
                listener.onUndo()
                showIconsBar()
            }
        }
        cancelButton = TextView(context).apply {
            text = "✕ Cancel"
            isAllCaps = false
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setTextColor(colText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = pill(color(R.color.kb_key_func))
            visibility = GONE
            setOnClickListener {
                listener.onCancelFix()
                showIconsBar()
            }
        }
        toneButton = TextView(context).apply {
            text = "Soften"
            isAllCaps = false
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setTextColor(colAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = pill(color(R.color.kb_func_active))
            visibility = GONE
            setOnClickListener {
                keyHaptic()
                listener.onToneFix()
                showIconsBar()
            }
        }
        statusRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(color(R.color.kb_actionbar))
            visibility = GONE
            addView(statusLabel, LayoutParams(0, MATCH, 1f))
            addView(toneButton, LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) })
            addView(cancelButton, LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) })
            addView(undoButton, LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) })
        }
        buildToolbar()
        suggestionRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(color(R.color.kb_actionbar))
            visibility = GONE
        }
        val toolbar = FrameLayout(context).apply {
            setBackgroundColor(color(R.color.kb_actionbar))
            addView(toolbarIcons, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(suggestionRow, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(statusRow, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        addView(toolbar, LayoutParams(MATCH, dp(46)))

        countdownBar = View(context).apply {
            setBackgroundColor(colAccent)
            visibility = GONE
            pivotX = 0f
            scaleX = 0f
        }
        addView(countdownBar, LayoutParams(MATCH, dp(3)))

        keyRows = LinearLayout(context).apply {
            orientation = VERTICAL
            isMotionEventSplittingEnabled = true
        }
        contentContainer = FrameLayout(context).apply {
            isMotionEventSplittingEnabled = true
            // Keys are inset (Samsung side margin) with a small gap below the bar;
            // the action bar above stays full-bleed.
            setPadding(sidePadding(), dp(6), sidePadding(), 0)
        }
        addView(contentContainer, LayoutParams(MATCH, WRAP))

        addView(buildBottomSystemBar(), LayoutParams(MATCH, dp(42)))

        renderKeys()
        showKeyboard()
    }

    /**
     * Samsung-style strip below the keys with the mic (speech-to-text) on the
     * left. We intentionally don't add a "hide keyboard" button here — Android
     * already provides one (plus the input-method switcher) in its own IME
     * navigation row, so a second one would be redundant.
     */
    private fun buildBottomSystemBar(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Just a centered mic. No custom hide button — the OS already shows an
        // unremovable down-arrow to dismiss the keyboard, so ours was redundant.
        // Centering keeps it clear of the bottom-left !#1 key and the OS
        // keyboard-switcher icon in that corner.
        addView(View(context), LayoutParams(0, MATCH, 1f))
        // Mic: tap = speech-to-text, hold = switch keyboard.
        val mic = ImageView(context).apply {
            setImageResource(R.drawable.ic_kb_mic)
            setColorFilter(colIcon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(9), dp(11), dp(9))
            background = drawable(R.drawable.key_flat_bg)
            setOnTouchListener { v, e -> handleMicTouch(v, e) }
        }
        addView(mic, LayoutParams(dp(52), MATCH))
        addView(View(context), LayoutParams(0, MATCH, 1f))
    }

    private fun handleMicTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.isPressed = true
                micLongPressed = false
                val r = Runnable {
                    micLongPressed = true
                    listener.onSwitchKeyboard()
                }
                micRunnable = r
                postDelayed(r, 450)
            }
            MotionEvent.ACTION_UP -> {
                v.isPressed = false
                micRunnable?.let { removeCallbacks(it) }
                if (!micLongPressed) listener.onMic()
            }
            MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                micRunnable?.let { removeCallbacks(it) }
            }
        }
        return true
    }

    // ---- Status / HUD / Undo ----

    fun setStatus(text: String, color: Int) {
        removeCallbacks(revertStatus)
        statusLabel.text = text
        statusLabel.setTextColor(color)
        undoButton.visibility = GONE
        cancelButton.visibility = GONE
        toneButton.visibility = GONE
        statusRow.visibility = VISIBLE
        toolbarIcons.visibility = INVISIBLE
        suggestionRow.visibility = GONE
    }

    /** "⚠ This may sound defensive · Soften" — shown when a tone issue is found. */
    fun showTone(label: String, color: Int) {
        removeCallbacks(revertStatus)
        statusLabel.text = "\u26A0  $label"
        statusLabel.setTextColor(color)
        undoButton.visibility = GONE
        cancelButton.visibility = GONE
        toneButton.visibility = VISIBLE
        statusRow.visibility = VISIBLE
        toolbarIcons.visibility = INVISIBLE
        suggestionRow.visibility = GONE
        postDelayed(revertStatus, 8000)
    }

    fun clearTone() {
        if (toneButton.visibility == VISIBLE) showIconsBar()
    }

    fun flash(text: String, color: Int) {
        setStatus(text, color)
        postDelayed(revertStatus, 1600)
    }

    fun hint(text: String) = flash(text, colTextSecondary)

    /** Persistent "Thinking… ✕ Cancel" shown while a fix is in flight. */
    fun showThinking() {
        removeCallbacks(revertStatus)
        statusLabel.text = "Thinking…"
        statusLabel.setTextColor(colAccent)
        undoButton.visibility = GONE
        toneButton.visibility = GONE
        cancelButton.visibility = VISIBLE
        statusRow.visibility = VISIBLE
        toolbarIcons.visibility = INVISIBLE
        suggestionRow.visibility = GONE
    }

    /** Shows a persistent "Fixed · Undo" affordance until the user types or undoes. */
    fun showUndo(label: String, color: Int) {
        removeCallbacks(revertStatus)
        statusLabel.text = label
        statusLabel.setTextColor(color)
        undoButton.visibility = VISIBLE
        cancelButton.visibility = GONE
        toneButton.visibility = GONE
        statusRow.visibility = VISIBLE
        toolbarIcons.visibility = INVISIBLE
        suggestionRow.visibility = GONE
        postDelayed(revertStatus, 6000)
    }

    fun clearUndo() = showIconsBar()

    private fun showIconsBar() {
        removeCallbacks(revertStatus)
        statusRow.visibility = GONE
        undoButton.visibility = GONE
        cancelButton.visibility = GONE
        toneButton.visibility = GONE
        refreshActionBar()
    }

    // ---- Live suggestions (autocomplete + typo fix) ----

    /** Sets the typo-fix/autocomplete chips. Empty hides them (and un-collapses). */
    fun setSuggestions(list: List<String>) {
        lastSuggestions = list
        if (list.isEmpty()) suggestionsCollapsed = false
        if (statusRow.visibility != VISIBLE) refreshActionBar()
    }

    private fun clearSuggestions() {
        lastSuggestions = emptyList()
        suggestionsCollapsed = false
        if (statusRow.visibility != VISIBLE) {
            suggestionRow.visibility = GONE
            toolbarIcons.visibility = VISIBLE
        }
    }

    /** Status > suggestion chips > icons. */
    private fun refreshActionBar() {
        if (statusRow.visibility == VISIBLE) {
            suggestionRow.visibility = GONE
            return
        }
        if (lastSuggestions.isNotEmpty() && !suggestionsCollapsed) {
            renderSuggestionChips(lastSuggestions)
            suggestionRow.visibility = VISIBLE
            toolbarIcons.visibility = INVISIBLE
        } else {
            suggestionRow.visibility = GONE
            toolbarIcons.visibility = VISIBLE
        }
    }

    private fun renderSuggestionChips(list: List<String>) {
        suggestionRow.removeAllViews()
        // "‹" collapses back to the icon toolbar (Samsung-style).
        suggestionRow.addView(TextView(context).apply {
            text = "‹"
            gravity = Gravity.CENTER
            setTextColor(colIcon)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            background = drawable(R.drawable.key_flat_bg)
            setOnClickListener { keyHaptic(); suggestionsCollapsed = true; refreshActionBar() }
        }, LinearLayout.LayoutParams(dp(40), MATCH))
        list.take(3).forEachIndexed { i, word ->
            if (i > 0) suggestionRow.addView(View(context).apply {
                setBackgroundColor(color(R.color.kb_key_func))
            }, LinearLayout.LayoutParams(dp(1), dp(22)))
            suggestionRow.addView(TextView(context).apply {
                text = word
                gravity = Gravity.CENTER
                isAllCaps = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(colText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                background = drawable(R.drawable.key_flat_bg)
                setOnClickListener { keyHaptic(); listener.onSuggestionPicked(word) }
            }, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
    }

    // ---- Toolbar ----

    private fun buildToolbar() {
        fun toolIcon(iconRes: Int, tint: Int, onClick: () -> Unit): ImageView =
            ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(tint)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(10), dp(9), dp(10), dp(9))
                background = drawable(R.drawable.key_flat_bg)
                isClickable = true
                setOnClickListener { onClick() }
            }

        fun add(view: View) = toolbarIcons.addView(view, LinearLayout.LayoutParams(0, MATCH, 1f))

        add(buildSparkle())
        add(toolIcon(R.drawable.ic_kb_emoji, colIcon) { togglePanel("emoji") })
        add(toolIcon(R.drawable.ic_kb_gif, colIcon) { togglePanel("gif") })
        add(toolIcon(R.drawable.ic_kb_clipboard, colIcon) { togglePanel("clipboard") })
        // Just Settings — switching/hiding the keyboard already live in the OS bar.
        add(toolIcon(R.drawable.ic_kb_settings, colIcon) { listener.onOpenSettings() })
    }

    /** Tapping a toolbar icon opens its panel; tapping it again closes it. */
    private fun togglePanel(name: String) {
        if (activePanel == name) {
            showKeyboard()
            return
        }
        when (name) {
            "emoji" -> showEmoji()
            "gif" -> showSearch(SearchMode.GIF)
            "clipboard" -> showClipboard()
        }
    }

    /** ✨ key: tap = Fix now; hold 2s = toggle Auto (with a popup above the finger). */
    private fun buildSparkle(): View {
        val frame = FrameLayout(context)
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_kb_sparkle)
            setColorFilter(colAccent)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = drawable(R.drawable.key_flat_bg)
        }
        sparkleIcon = icon
        frame.addView(icon, FrameLayout.LayoutParams(MATCH, MATCH))

        val dot = View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colAccent) }
            visibility = if (autoModeOn) VISIBLE else GONE
        }
        sparkleDot = dot
        frame.addView(dot, FrameLayout.LayoutParams(dp(7), dp(7)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(9)
            marginEnd = dp(12)
        })

        frame.setOnTouchListener { _, e -> handleSparkleTouch(e) }
        return frame
    }

    private fun handleSparkleTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                holdCompleted = false
                holdCancelled = false
                sparkleIcon?.isPressed = true
                val rawX = e.rawX
                val rawY = e.rawY
                val r = Runnable { startSparkleHold(rawX, rawY) }
                sparkleHoldRunnable = r
                postDelayed(r, 200)
            }
            MotionEvent.ACTION_UP -> {
                sparkleIcon?.isPressed = false
                sparkleHoldRunnable?.let { removeCallbacks(it) }
                when {
                    holdCompleted -> { /* already toggled */ }
                    holdAnimator?.isRunning == true -> cancelSparkleHold() // released early
                    else -> listener.onFix() // quick tap
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                sparkleIcon?.isPressed = false
                sparkleHoldRunnable?.let { removeCallbacks(it) }
                if (!holdCompleted) cancelSparkleHold()
            }
        }
        return true
    }

    private fun startSparkleHold(rawX: Float, rawY: Float) {
        holdCancelled = false
        holdCompleted = false
        val view = HoldProgressView(
            context,
            bgColor = colLetterKey,
            trackColor = color(R.color.kb_key_func),
            accentColor = colAccent,
            textColor = colText,
            subTextColor = colTextSecondary,
        ).apply {
            caption = "Keep holding…"
            centerGlyph = "\u2728"
            progress = 0f
        }
        holdView = view
        val w = dp(150)
        val h = dp(140)
        val popup = PopupWindow(view, w, h, false).apply {
            isClippingEnabled = false
            isTouchable = false
            elevation = dp(8).toFloat()
        }
        holdPopup = popup
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val margin = dp(6)
        // Center on the finger, but keep the whole card on screen.
        val maxX = (width - w - margin).coerceAtLeast(margin)
        val x = (rawX - loc[0] - w / 2).toInt().coerceIn(margin, maxX)
        var y = (rawY - loc[1] - h - dp(24)).toInt()
        // Don't let it run off the top of the screen either.
        val screenTop = loc[1] + y
        if (screenTop < dp(8)) y += dp(8) - screenTop
        popup.showAtLocation(this, Gravity.NO_GRAVITY, x, y)

        nextHoldTick = 0.06f
        holdAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            interpolator = LinearInterpolator()
            addUpdateListener {
                val p = it.animatedValue as Float
                holdView?.progress = p
                // Ticks that start ~0.3s in and accelerate toward the end.
                if (p >= nextHoldTick) {
                    Haptics.tick(context, 9, (60 + p * 170).toInt())
                    val gap = (0.16f - 0.13f * p).coerceAtLeast(0.03f)
                    nextHoldTick = p + gap
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!holdCancelled) completeSparkleHold()
                }
            })
            start()
        }
    }

    private fun completeSparkleHold() {
        holdCompleted = true
        Haptics.doubleStrong(context)
        val turningOn = !autoModeOn
        holdView?.apply {
            progress = 1f
            centerGlyph = "\u2713"
            caption = if (turningOn) "Auto ON" else "Auto OFF"
            animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).withEndAction {
                animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }.start()
        }
        listener.onToggleAutoMode()
        postDelayed({ dismissHoldPopup() }, 750)
    }

    private fun cancelSparkleHold() {
        holdCancelled = true
        holdAnimator?.cancel()
        dismissHoldPopup()
    }

    private fun dismissHoldPopup() {
        holdAnimator = null
        holdPopup?.dismiss()
        holdPopup = null
        holdView = null
    }

    /** Reflects whether Auto mode is on (dot + subtle active background on ✨). */
    fun setAutoModeIndicator(enabled: Boolean) {
        autoModeOn = enabled
        sparkleDot?.visibility = if (enabled) VISIBLE else GONE
        sparkleIcon?.background = drawable(if (enabled) R.drawable.key_func_active_bg else R.drawable.key_flat_bg)
        if (!enabled) cancelAutoCountdown()
    }

    /** A subtle accent line that fills across [durationMs] before an auto fix. */
    fun startAutoCountdown(durationMs: Long) {
        val bar = countdownBar ?: return
        countdownAnimator?.cancel()
        bar.visibility = VISIBLE
        bar.scaleX = 0f
        countdownAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { bar.scaleX = it.animatedValue as Float }
            start()
        }
    }

    fun cancelAutoCountdown() {
        countdownAnimator?.cancel()
        countdownAnimator = null
        countdownBar?.scaleX = 0f
        countdownBar?.visibility = GONE
    }

    // ---- Content panels ----

    /** Reset to the normal letter layout — called when the keyboard is re-shown so
     *  it never reopens to the panel/symbols page it was last on. */
    fun resetToLetters() {
        symbols = false
        symbolsPage = 0
        shifted = false
        capsLock = false
        showKeyboard()
        renderKeys()
    }

    /** Auto-capitalization: the IME sets the one-shot shift based on the caret
     *  context (start of text / after sentence punctuation). Ignored while caps
     *  lock is on, symbols are showing, or a panel/search is open. */
    fun setAutoShift(on: Boolean) {
        if (capsLock || symbols || searchMode != SearchMode.NONE || activePanel != "keyboard") return
        if (shifted == on) return
        shifted = on
        applyShiftCase()
    }

    private fun showKeyboard() {
        listener.onContentPanelChanged()
        clearSuggestions()
        emojiPanelOpen = false
        searchMode = SearchMode.NONE
        activePanel = "keyboard"
        emojiSuggestedRow = null
        emojiLoadingAnimator?.cancel()
        emojiLoadingAnimator = null
        if (keyRows.parent !== contentContainer) {
            (keyRows.parent as? ViewGroup)?.removeView(keyRows)
            contentContainer.removeAllViews()
            contentContainer.addView(keyRows, FrameLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private fun showEmoji() {
        listener.onContentPanelChanged()
        clearSuggestions()
        emojiPanelOpen = true
        searchMode = SearchMode.NONE
        activePanel = "emoji"
        (keyRows.parent as? ViewGroup)?.removeView(keyRows)
        contentContainer.removeAllViews()
        contentContainer.addView(buildEmojiPanel(), FrameLayout.LayoutParams(MATCH, WRAP))
        listener.onEmojiPanelShown()
    }

    private fun showClipboard() {
        listener.onContentPanelChanged()
        clearSuggestions()
        emojiPanelOpen = false
        searchMode = SearchMode.NONE
        activePanel = "clipboard"
        emojiSuggestedRow = null
        clipSelecting = false
        clipSelected.clear()
        captureCurrentClip()
        rebuildClipboard()
    }

    /** Records the current system clip into history (we can read it while shown). */
    private fun captureCurrentClip() {
        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
        val current = runCatching {
            clip?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull()
        ClipboardHistory.add(context, current)
    }

    private fun rebuildClipboard() {
        if (activePanel != "clipboard") return
        contentContainer.removeAllViews()
        contentContainer.addView(buildClipboardPanel(), FrameLayout.LayoutParams(MATCH, WRAP))
    }

    /** Called by the IME with context-based (local or LLM) emoji suggestions. */
    fun setEmojiSuggestions(suggestions: List<String>) {
        emojiLoadingAnimator?.cancel()
        emojiLoadingAnimator = null
        emojiSuggestions = suggestions
        if (emojiPanelOpen) refreshEmojiSuggestions()
    }

    /** Shimmer in the suggested row while the model finds contextual emojis. */
    fun setEmojiSuggestionsLoading() {
        val row = emojiSuggestedRow ?: return
        shimmerRow(row, count = 8, tileW = 40, tileH = 40)
    }

    private fun refreshEmojiSuggestions() {
        val rowView = emojiSuggestedRow ?: return
        rowView.removeAllViews()
        if (emojiSuggestions.isEmpty()) {
            rowView.visibility = GONE
            return
        }
        rowView.visibility = VISIBLE
        emojiSuggestions.take(8).forEach { emoji ->
            rowView.addView(TextView(context).apply {
                text = emoji
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                background = drawable(R.drawable.key_flat_bg)
                tag = panelTileTag
                setOnClickListener { pickHaptic(); listener.onChar(emoji) }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
    }

    /** A reusable shimmer of placeholder tiles in [row] (LLM/loading state). */
    private fun shimmerRow(row: LinearLayout, count: Int, tileW: Int, tileH: Int) {
        emojiLoadingAnimator?.cancel()
        row.visibility = VISIBLE
        row.removeAllViews()
        val tiles = ArrayList<View>(count)
        repeat(count) {
            val tile = View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(color(R.color.kb_key_func))
                }
                alpha = 0.35f
            }
            tiles.add(tile)
            row.addView(tile, LinearLayout.LayoutParams(dp(tileW), dp(tileH)).apply {
                marginStart = dp(3); marginEnd = dp(3)
            })
        }
        emojiLoadingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val t = a.animatedFraction
                tiles.forEachIndexed { i, tl ->
                    val ph = (t + i * 0.18f) % 1f
                    tl.alpha = 0.25f + 0.45f * (1f - kotlin.math.abs(0.5f - ph) * 2f)
                }
            }
            start()
        }
    }

    private fun buildEmojiPanel(): View = LinearLayout(context).apply {
        orientation = VERTICAL

        // Tap-to-search bar.
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill(color(R.color.kb_key_func))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            addView(TextView(context).apply {
                text = "\uD83D\uDD0D  Search emoji"
                setTextColor(colTextSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }, LayoutParams(0, WRAP, 1f))
            setOnClickListener { showSearch(SearchMode.EMOJI) }
        }, LayoutParams(MATCH, dp(40)).apply { setMargins(dp(8), dp(6), dp(8), dp(2)) })

        val scroll = ScrollView(context)
        val list = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }

        list.addView(TextView(context).apply {
            text = "Suggested"
            setTextColor(colAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), dp(8), dp(8), dp(4))
        })
        val suggestedRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        emojiSuggestedRow = suggestedRow
        list.addView(suggestedRow)
        refreshEmojiSuggestions()

        EmojiData.categories.forEach { (name, emojis) ->
            list.addView(TextView(context).apply {
                text = name
                setTextColor(colTextSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(8), dp(8), dp(8), dp(4))
            })
            emojis.chunked(8).forEach { rowEmojis ->
                list.addView(LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    rowEmojis.forEach { emoji ->
                        addView(TextView(context).apply {
                            text = emoji
                            gravity = Gravity.CENTER
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                            background = drawable(R.drawable.key_flat_bg)
                            tag = panelTileTag
                            setOnClickListener { pickHaptic(); listener.onChar(emoji) }
                        }, LinearLayout.LayoutParams(0, dp(44), 1f))
                    }
                })
            }
        }
        scroll.addView(list)
        addView(scroll, LayoutParams(MATCH, dp(168)))
        addView(panelBottomBar())
    }

    private fun searchHint(): String =
        if (searchMode == SearchMode.GIF) "\uD83D\uDD0D  Search GIFs" else "\uD83D\uDD0D  Search emoji"

    private fun showSearch(mode: SearchMode) {
        listener.onContentPanelChanged()
        clearSuggestions()
        searchMode = mode
        emojiPanelOpen = false
        activePanel = if (mode == SearchMode.GIF) "gif" else "emoji"
        searchQuery.setLength(0)
        contentContainer.removeAllViews()
        (keyRows.parent as? ViewGroup)?.removeView(keyRows)

        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill(color(R.color.kb_key_func))
            setPadding(dp(14), dp(6), dp(8), dp(6))
            val label = TextView(context).apply {
                text = searchHint()
                setTextColor(colTextSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            searchQueryLabel = label
            addView(label, LayoutParams(0, WRAP, 1f))
            addView(TextView(context).apply {
                text = "✕"
                gravity = Gravity.CENTER
                setTextColor(colIcon)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                background = drawable(R.drawable.key_flat_bg)
                setOnClickListener { exitSearch() }
            }, LayoutParams(dp(36), dp(32)))
        }

        val resScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val resRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        searchResultsRow = resRow
        resScroll.addView(resRow)

        val resultsHeight = if (mode == SearchMode.GIF) dp(120) else dp(52)
        val container = LinearLayout(context).apply { orientation = VERTICAL }
        container.addView(bar, LayoutParams(MATCH, dp(44)).apply { setMargins(dp(8), dp(6), dp(8), dp(2)) })
        container.addView(resScroll, LayoutParams(MATCH, resultsHeight))
        container.addView(keyRows, LayoutParams(MATCH, WRAP))
        contentContainer.addView(container, FrameLayout.LayoutParams(MATCH, WRAP))

        searchCaretOn = true
        removeCallbacks(caretBlink)
        postDelayed(caretBlink, 500)

        if (mode == SearchMode.GIF) listener.onGifPanelShown()
        updateSearch()
    }

    private fun exitSearch() {
        searchMode = SearchMode.NONE
        searchResultsRow = null
        searchQueryLabel = null
        removeCallbacks(caretBlink)
        gifLoadingAnimator?.cancel()
        emojiLoadingAnimator?.cancel()
        emojiLoadingAnimator = null
        // The ✕ on any panel search returns to the normal keyboard.
        showKeyboard()
    }

    private fun renderSearchLabel() {
        val label = searchQueryLabel ?: return
        val q = searchQuery.toString()
        val caret = if (searchCaretOn) "|" else " "
        if (q.isEmpty()) {
            label.text = "\uD83D\uDD0D  $caret"
            label.setTextColor(colTextSecondary)
        } else {
            label.text = "\uD83D\uDD0D  $q$caret"
            label.setTextColor(colText)
        }
    }

    private fun updateSearch() {
        renderSearchLabel()
        val q = searchQuery.toString()
        when (searchMode) {
            SearchMode.EMOJI -> {
                renderEmojiResults(EmojiSearchIndex.search(q))
                if (q.isNotBlank()) listener.onEmojiSearchQuery(q)
            }
            SearchMode.GIF -> if (q.isNotBlank()) listener.onGifSearchQuery(q, immediate = false)
            SearchMode.NONE -> {}
        }
    }

    /** Shimmer in the emoji search row while the model finds semantic matches. */
    fun setEmojiSearchLoading() {
        if (searchMode != SearchMode.EMOJI) return
        val row = searchResultsRow ?: return
        shimmerRow(row, count = 8, tileW = 44, tileH = 44)
    }

    /** LLM-based semantic emoji results from the IME (falls back to local). */
    fun setEmojiSearchResults(results: List<String>) {
        if (searchMode != SearchMode.EMOJI) return
        emojiLoadingAnimator?.cancel()
        emojiLoadingAnimator = null
        if (results.isNotEmpty()) renderEmojiResults(results)
        else renderEmojiResults(EmojiSearchIndex.search(searchQuery.toString()))
    }

    /** Shimmering placeholder tiles while GIFs are being fetched. */
    fun setGifLoading() {
        if (searchMode != SearchMode.GIF) return
        val row = searchResultsRow ?: return
        gifLoadingAnimator?.cancel()
        row.removeAllViews()
        val tiles = mutableListOf<View>()
        repeat(5) {
            val tile = View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(color(R.color.kb_key_func))
                }
                alpha = 0.35f
            }
            tiles.add(tile)
            row.addView(tile, LinearLayout.LayoutParams(dp(150), MATCH).apply { marginEnd = dp(4) })
        }
        // A wave of pulsing opacity across the tiles ("shimmer").
        gifLoadingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val t = a.animatedFraction
                tiles.forEachIndexed { i, tile ->
                    val phase = (t + i * 0.18f) % 1f
                    tile.alpha = 0.25f + 0.45f * (1f - kotlin.math.abs(0.5f - phase) * 2f)
                }
            }
            start()
        }
    }

    /** GIF results from the IME. */
    fun setGifResults(results: List<GifClient.Gif>) {
        if (searchMode != SearchMode.GIF) return
        val row = searchResultsRow ?: return
        gifLoadingAnimator?.cancel()
        gifLoadingAnimator = null
        row.removeAllViews()
        results.forEach { gif ->
            row.addView(ImageView(context).apply {
                // Fit the whole GIF (sized to its aspect) instead of cropping the
                // top/bottom — the preview now matches what gets inserted.
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = drawable(R.drawable.key_flat_bg)
                load(gif.previewUrl, gifLoader)
                tag = panelTileTag
                setOnClickListener { pickHaptic(); listener.onGifSelected(gif.gifUrl) }
            }, LinearLayout.LayoutParams(WRAP, MATCH).apply { marginEnd = dp(4) })
        }
    }

    private fun renderEmojiResults(results: List<String>) {
        val row = searchResultsRow ?: return
        row.removeAllViews()
        results.forEach { emoji ->
            row.addView(TextView(context).apply {
                text = emoji
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                background = drawable(R.drawable.key_flat_bg)
                tag = panelTileTag
                setOnClickListener { pickHaptic(); listener.onChar(emoji) }
            }, LinearLayout.LayoutParams(dp(48), MATCH))
        }
    }

    private fun buildClipboardPanel(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        val items = ClipboardHistory.items(context)

        // Header: a hint, or selection actions while selecting.
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(8), dp(4))
            if (clipSelecting) {
                addView(TextView(context).apply {
                    text = "${clipSelected.size} selected"
                    setTextColor(colText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                }, LayoutParams(0, WRAP, 1f))
                addView(clipAction("Delete", colAccent) {
                    ClipboardHistory.remove(context, clipSelected.toSet())
                    clipSelecting = false; clipSelected.clear(); rebuildClipboard()
                })
                addView(clipAction("Cancel", colTextSecondary) {
                    clipSelecting = false; clipSelected.clear(); rebuildClipboard()
                })
            } else {
                addView(TextView(context).apply {
                    text = "Recent  ·  long-press to select"
                    setTextColor(colTextSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                }, LayoutParams(0, WRAP, 1f))
                if (items.isNotEmpty()) addView(clipAction("Clear all", colTextSecondary) {
                    ClipboardHistory.clear(context); rebuildClipboard()
                })
            }
        }, LayoutParams(MATCH, dp(34)))

        val scroll = ScrollView(context)
        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        if (items.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "Nothing yet. Copies you make while the keyboard is open show up here."
                setTextColor(colTextSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(8), dp(16), dp(8), dp(16))
            })
        } else {
            items.forEach { item -> list.addView(clipItemView(item)) }
        }
        scroll.addView(list)
        addView(scroll, LayoutParams(MATCH, dp(172)))
        addView(panelBottomBar())
    }

    private fun clipAction(label: String, color: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            isAllCaps = false
            gravity = Gravity.CENTER
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = drawable(R.drawable.key_flat_bg)
            setOnClickListener { keyHaptic(); onClick() }
        }

    private fun clipItemView(item: String): View = TextView(context).apply {
        text = item
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(colText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = drawable(
            if (clipSelecting && item in clipSelected) R.drawable.key_func_active_bg
            else R.drawable.key_letter_bg
        )
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }
        setOnClickListener {
            if (clipSelecting) {
                if (!clipSelected.add(item)) clipSelected.remove(item)
                rebuildClipboard()
            } else {
                keyHaptic()
                ClipboardHistory.add(context, item) // bump to most-recent
                listener.onChar(item)
            }
        }
        setOnLongClickListener {
            if (!clipSelecting) {
                clipSelecting = true
                clipSelected.clear()
                clipSelected.add(item)
                rebuildClipboard()
            }
            true
        }
    }

    private fun panelBottomBar(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        layoutParams = LayoutParams(MATCH, dp(50)).apply { topMargin = dp(2) }
        val abc = textView("ABC", colText, 14f).apply {
            background = drawable(R.drawable.key_func_bg)
            setOnClickListener { showKeyboard() }
        }
        addView(abc, LinearLayout.LayoutParams(0, MATCH, 2f).apply { marginStart = dp(2); marginEnd = dp(2) })
        val spacer = View(context)
        addView(spacer, LinearLayout.LayoutParams(0, MATCH, 5f))
        // Same backspace as the main keyboard: tap deletes one, hold accelerates,
        // and a selection is deleted whole.
        addView(backspaceKey(), LinearLayout.LayoutParams(0, MATCH, 2f).apply {
            marginStart = dp(2); marginEnd = dp(2)
        })
    }

    // ---- Keys ----

    private fun renderKeys() {
        keyRows.removeAllViews()
        letterKeys.clear()
        gestureKeys.clear()
        shiftKey = null
        if (symbols) renderSymbols() else renderLetters()
        applyShiftCase()
    }

    private fun renderLetters() {
        keyRows.addView(numberRow())
        keyRows.addView(letterRow(ROW1, indent = 0f, splitAfter = 5))
        keyRows.addView(letterRow(ROW2, indent = 0.5f, splitAfter = 5))
        keyRows.addView(thirdRow(ROW3, lettersPage = true))
        keyRows.addView(bottomRow())
    }

    private fun renderSymbols() {
        val rows = if (symbolsPage == 0) SYM_PAGE1 else SYM_PAGE2
        keyRows.addView(symbolRow(rows[0]))
        keyRows.addView(symbolRow(rows[1]))
        keyRows.addView(symbolThirdRow(rows[2]))
        keyRows.addView(symbolsBottomRow())
    }

    /** Symbols 3rd row mirrors the letters' third row: page-cycle on the left
     *  (where shift is) and backspace on the right — same spot as normal keys. */
    private fun symbolThirdRow(keys: List<String>): View = row(45).apply {
        addCell(this, textView("${symbolsPage + 1}/2", colText, 14f).apply {
            background = keyBg(R.drawable.key_func_bg)
            setOnClickListener { symbolsPage = (symbolsPage + 1) % 2; renderKeys() }
        }, 1.5f)
        keys.forEach { k ->
            addCell(this, charKey(k, R.drawable.key_letter_bg, colText, 18f, gestureEligible = false), 1f)
        }
        addCell(this, backspaceKey(), 1.5f)
    }

    private fun numberRow(): View = row(36).apply {
        NUMBERS.forEachIndexed { i, n ->
            addCell(this, charKey(n, R.drawable.key_letter_bg, colText, 16f, gestureEligible = false), 1f)
            if (wide && i == 4) addCenterGap(this)
        }
    }

    private fun letterRow(keys: List<String>, indent: Float, splitAfter: Int): View = row(45).apply {
        // The half-key indent applies in split mode too — Samsung staggers the
        // home row (a starts ~½ key right of q), so the indent + a matching
        // trailing gap keep every letter key the same width across rows.
        if (indent > 0f) addCell(this, View(context), indent)
        keys.forEachIndexed { i, k ->
            val key = charKey(k, R.drawable.key_letter_bg, colText, 19f, gestureEligible = true)
            letterKeys.add(key)
            gestureKeys.add(key to k[0])
            addCell(this, key, 1f)
            if (wide && i == splitAfter - 1) addCenterGap(this)
        }
        if (indent > 0f) addCell(this, View(context), indent)
    }

    private fun addCenterGap(row: LinearLayout) = addCell(row, View(context), CENTER_GAP)

    private fun symbolRow(keys: List<String>): View = row(45).apply {
        keys.forEach { k ->
            addCell(this, charKey(k, R.drawable.key_letter_bg, colText, 18f, gestureEligible = false), 1f)
        }
    }

    private fun thirdRow(middle: List<String>, lettersPage: Boolean): View = row(45).apply {
        val shift = iconKey(R.drawable.ic_kb_shift, R.drawable.key_func_bg) {
            val now = System.currentTimeMillis()
            when {
                now - lastShiftTapAt < 300 -> { capsLock = true; shifted = true } // double-tap
                capsLock -> { capsLock = false; shifted = false }                 // turn caps off
                else -> shifted = !shifted                                        // one-shot shift
            }
            lastShiftTapAt = now
            applyShiftCase()
        }
        shiftKey = shift
        // ~1.5 key-widths, matching Samsung (z sits ~1.5 keys in on the bottom row).
        addCell(this, shift, 1.5f)
        middle.forEachIndexed { i, k ->
            val key = charKey(k, R.drawable.key_letter_bg, colText, 19f, gestureEligible = lettersPage)
            if (lettersPage) {
                letterKeys.add(key)
                gestureKeys.add(key to k[0])
            }
            addCell(this, key, 1f)
            if (wide && i == 3) addCenterGap(this)
        }
        addCell(this, backspaceKey(), 1.5f)
    }

    private fun backspaceKey(): ImageView = ImageView(context).apply {
        setImageResource(R.drawable.ic_kb_backspace)
        setColorFilter(colIcon)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val p = dp(13)
        setPadding(p, p, p, p)
        background = keyBg(R.drawable.key_func_bg)
        setOnTouchListener { v, e -> handleBackspaceTouch(v, e) }
    }

    private fun bottomRow(): View = row(45).apply {
        addCell(this, textView("!#1", colText, 14f).apply {
            background = keyBg(R.drawable.key_func_bg)
            setOnClickListener { symbols = true; symbolsPage = 0; renderKeys() }
        }, 1.5f)
        addCell(this, charKey(",", R.drawable.key_letter_bg, colText, 18f, gestureEligible = false), 1f)
        if (wide) {
            // !#1(1.5)+,(1)+space(2.5)+gap+space(2.5)+.(1)+⏎(1.5) = 10 + gap, so the
            // bottom row lines up on the same grid as the letter rows.
            addCell(this, spaceKey(), 2.5f)
            addCenterGap(this)
            addCell(this, spaceKey(), 2.5f)
        } else {
            addCell(this, spaceKey(), 5f)
        }
        addCell(this, charKey(".", R.drawable.key_letter_bg, colText, 18f, gestureEligible = false), 1f)
        addCell(this, iconKey(R.drawable.ic_kb_enter, R.drawable.key_func_bg) { handleEnter() }, 1.5f)
    }

    /** Symbols pages keep their symbol rows full-width (so the shifted symbols
     *  line up under the numbers), so ABC / page-cycle / backspace / enter live
     *  together down here. */
    private fun symbolsBottomRow(): View = row(45).apply {
        addCell(this, textView("ABC", colText, 14f).apply {
            background = keyBg(R.drawable.key_func_bg)
            setOnClickListener { symbols = false; renderKeys() }
        }, 1.5f)
        addCell(this, charKey(",", R.drawable.key_letter_bg, colText, 18f, gestureEligible = false), 1f)
        addCell(this, spaceKey(), 5f)
        addCell(this, charKey(".", R.drawable.key_letter_bg, colText, 18f, gestureEligible = false), 1f)
        // Enter on the far right — same spot as the letters' bottom row.
        addCell(this, iconKey(R.drawable.ic_kb_enter, R.drawable.key_func_bg) { handleEnter() }, 1.5f)
    }

    private fun spaceKey(): TextView = textView("English (US)", colTextSecondary, 13f).apply {
        background = keyBg(R.drawable.key_letter_bg)
        setOnTouchListener { v, e -> handleSpaceTouch(v as TextView, e) }
    }

    /** Space bar: a normal tap types a space; dragging left/right turns it into
     *  a trackpad that moves the text caret (Samsung-style), landing where you
     *  release. */
    private var spaceAnchorX = 0f
    private var spaceCursorMode = false
    private fun handleSpaceTouch(v: TextView, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.isPressed = true
                spaceAnchorX = e.rawX
                spaceCursorMode = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!spaceCursorMode && kotlin.math.abs(e.rawX - spaceAnchorX) > touchSlop) {
                    spaceCursorMode = true
                    spaceAnchorX = e.rawX
                }
                if (spaceCursorMode) {
                    // Shorter travel per character => the caret moves faster.
                    val step = dp(6).toFloat()
                    val steps = ((e.rawX - spaceAnchorX) / step).toInt()
                    if (steps != 0) {
                        listener.onCursorMove(steps)
                        spaceAnchorX += steps * step
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                v.isPressed = false
                if (!spaceCursorMode) commitChar(" ")
                spaceCursorMode = false
            }
            MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                spaceCursorMode = false
            }
        }
        return true
    }

    // ---- Touch handling for character keys (preview, long-press, gesture) ----

    private fun charKey(
        baseChar: String,
        bgRes: Int,
        textColor: Int,
        sizeSp: Float,
        gestureEligible: Boolean,
    ): TextView {
        val key = textView(baseChar, textColor, sizeSp)
        key.background = keyBg(bgRes)
        key.setOnTouchListener { v, e -> handleCharTouch(v as TextView, baseChar, gestureEligible, e) }
        return key
    }

    /** Screen coordinates of a pointer, derived from the key view's on-screen
     *  position (works for all pointers without needing API 29 getRawX(index)). */
    private fun pointerScreenXY(anchor: View, e: MotionEvent, pointerIndex: Int): Pair<Float, Float> {
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        return (loc[0] + e.getX(pointerIndex)) to (loc[1] + e.getY(pointerIndex))
    }

    private fun handleCharTouch(v: TextView, baseChar: String, gestureEligible: Boolean, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                val id = e.getPointerId(idx)
                val (sx, sy) = pointerScreenXY(v, e, idx)
                val st = CharTouch(v, baseChar, gestureEligible, sx, sy)
                charTouches[id] = st
                v.isPressed = true
                if (gestureEligible) {
                    st.crossed.append(baseChar.lowercase())
                    st.lastCrossedChar = baseChar.lowercase().firstOrNull()
                    st.points.add(floatArrayOf(sx, sy))
                }
                showPreview(v, currentCase(baseChar))
                scheduleLongPress(st)
            }
            MotionEvent.ACTION_MOVE -> {
                for (pi in 0 until e.pointerCount) {
                    val st = charTouches[e.getPointerId(pi)] ?: continue
                    val (sx, sy) = pointerScreenXY(st.view, e, pi)
                    val moved = hypot(sx - st.downScreenX, sy - st.downScreenY)
                    if (!st.gesturing && st.gestureEligible && moved > touchSlop * 2) {
                        st.gesturing = true
                        cancelLongPress(st)
                    }
                    if (st.gesturing) {
                        val last = st.points.lastOrNull()
                        if (last == null || hypot(sx - last[0], sy - last[1]) > dp(2) && st.points.size < 300) {
                            st.points.add(floatArrayOf(sx, sy))
                        }
                        val ch = nearestLetterChar(sx, sy)
                        if (ch != null && ch != st.lastCrossedChar) {
                            st.crossed.append(ch)
                            st.lastCrossedChar = ch
                            previewText?.text = if (shifted) ch.uppercaseChar().toString() else ch.toString()
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val st = charTouches.remove(e.getPointerId(e.actionIndex)) ?: return true
                st.view.isPressed = false
                cancelLongPress(st)
                hidePreview()
                if (st.longPressActive) return true
                if (st.gesturing && st.gestureEligible && st.crossed.length >= 2 && searchMode == SearchMode.NONE) {
                    // Prefer the shape-based decode; fall back to the crossed-key
                    // heuristic, then to just the starting letter.
                    val centers = buildGestureCenters()
                    val keyW = averageKeyWidth()
                    val word = GestureDecoder.decodeGesture(st.points, centers, keyW)
                        ?: GestureDecoder.decode(st.crossed.toString())
                    if (word != null) listener.onGestureWord(word) else commitChar(st.baseChar)
                } else {
                    commitChar(st.baseChar)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                charTouches.entries.filter { it.value.view === v }.toList().forEach {
                    cancelLongPress(it.value)
                    charTouches.remove(it.key)
                }
                hidePreview()
            }
        }
        return true
    }

    private fun commitChar(baseChar: String) {
        if (searchMode != SearchMode.NONE) {
            searchQuery.append(baseChar.lowercase())
            updateSearch()
            return
        }
        listener.onChar(currentCase(baseChar))
        // One-shot shift clears after a letter; caps lock stays on.
        if (shifted && !capsLock && baseChar.length == 1 && baseChar[0] in 'a'..'z') {
            shifted = false
            applyShiftCase()
        }
    }

    /** Very light tick on every press anywhere on the keyboard (keys, toolbar,
     *  panels, results). One tick per finger-down. Respects the vibration setting. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val a = ev.actionMasked
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
            // Emoji/GIF tiles buzz when picked (onClick), not on touch-down.
            if (activePanel == "keyboard" || !isOverPanelTile(ev.rawX, ev.rawY)) keyHaptic()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun keyHaptic() = Haptics.tick(context, 18, 160)

    /** Celebratory buzz when an emoji/GIF is chosen. */
    private fun pickHaptic() = Haptics.pick(context)

    private fun isOverPanelTile(rawX: Float, rawY: Float): Boolean = findPanelTile(this, rawX, rawY)

    private fun findPanelTile(view: View, rawX: Float, rawY: Float): Boolean {
        if (view.visibility != VISIBLE) return false
        view.getLocationOnScreen(hitLoc)
        if (rawX < hitLoc[0] || rawX > hitLoc[0] + view.width ||
            rawY < hitLoc[1] || rawY > hitLoc[1] + view.height
        ) return false
        if (view.tag === panelTileTag) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (findPanelTile(view.getChildAt(i), rawX, rawY)) return true
            }
        }
        return false
    }


    private fun handleBackspaceTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.isPressed = true
                // In search mode, backspace edits the query (single delete).
                if (searchMode != SearchMode.NONE) handleBackspace()
                else listener.onBackspacePressed()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                if (searchMode == SearchMode.NONE) listener.onBackspaceReleased()
            }
        }
        return true
    }

    private fun handleBackspace() {
        if (searchMode != SearchMode.NONE) {
            if (searchQuery.isNotEmpty()) {
                searchQuery.deleteCharAt(searchQuery.length - 1)
                updateSearch()
            } else {
                exitSearch()
            }
            return
        }
        listener.onBackspace()
    }

    private fun handleEnter() {
        // In GIF search, Enter runs the search now (instead of waiting for the
        // idle debounce); other panels just close back to the keyboard.
        if (searchMode == SearchMode.GIF) {
            val q = searchQuery.toString()
            if (q.isNotBlank()) listener.onGifSearchQuery(q, immediate = true)
            return
        }
        if (searchMode != SearchMode.NONE) {
            exitSearch()
            return
        }
        listener.onEnter()
    }

    /** Letter -> on-screen key center, for the shape-based swipe decoder. */
    private fun buildGestureCenters(): HashMap<Char, FloatArray> {
        val m = HashMap<Char, FloatArray>(32)
        val loc = IntArray(2)
        for ((view, ch) in gestureKeys) {
            if (view.width == 0) continue
            view.getLocationOnScreen(loc)
            m[ch] = floatArrayOf(loc[0] + view.width / 2f, loc[1] + view.height / 2f)
        }
        return m
    }

    private fun averageKeyWidth(): Float {
        var sum = 0f
        var count = 0
        for ((view, _) in gestureKeys) if (view.width > 0) { sum += view.width; count++ }
        return if (count > 0) sum / count else dp(34).toFloat()
    }

    private fun nearestLetterChar(rawX: Float, rawY: Float): Char? {
        var nearest: Char? = null
        var nearestDist = Float.MAX_VALUE
        val loc = IntArray(2)
        for ((view, ch) in gestureKeys) {
            if (view.width == 0) continue
            view.getLocationOnScreen(loc)
            val cx = loc[0] + view.width / 2f
            val cy = loc[1] + view.height / 2f
            if (rawX >= loc[0] && rawX <= loc[0] + view.width && rawY >= loc[1] && rawY <= loc[1] + view.height) {
                return ch
            }
            val d = hypot(rawX - cx, rawY - cy)
            if (d < nearestDist) { nearestDist = d; nearest = ch }
        }
        return if (nearestDist < dp(60).toFloat()) nearest else null
    }

    // ---- Preview & long-press popups ----

    private fun showPreview(anchor: View, text: String) {
        val tv = previewText ?: TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(colText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            background = popupBg()
        }.also { previewText = it }
        tv.text = text
        val w = anchor.width + dp(20)
        val h = anchor.height + dp(28)
        val popup = previewPopup ?: PopupWindow(tv, w, h, false).apply {
            isClippingEnabled = false
            isTouchable = false
        }.also { previewPopup = it }
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val x = loc[0] + anchor.width / 2 - w / 2
        val y = loc[1] - h - dp(2)
        if (popup.isShowing) popup.update(x, y, w, h) else popup.showAtLocation(this, Gravity.NO_GRAVITY, x, y)
    }

    private fun hidePreview() {
        previewPopup?.dismiss()
    }

    private fun scheduleLongPress(st: CharTouch) {
        cancelLongPress(st)
        val alts = Accents.alternatesFor(st.baseChar)
        if (alts.isEmpty()) return
        val r = Runnable {
            st.longPressActive = true
            hidePreview()
            showAlternates(st.view, st.baseChar, alts)
        }
        st.longPressRunnable = r
        postDelayed(r, 320)
    }

    private fun cancelLongPress(st: CharTouch) {
        st.longPressRunnable?.let { removeCallbacks(it) }
        st.longPressRunnable = null
    }

    private fun showAlternates(anchor: View, baseChar: String, alts: List<String>) {
        val options = (listOf(baseChar) + alts).distinct()
        val rowView = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = popupBg()
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        options.forEach { opt ->
            rowView.addView(TextView(context).apply {
                text = if (shifted) opt.uppercase() else opt
                gravity = Gravity.CENTER
                setTextColor(colText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                background = drawable(R.drawable.key_flat_bg)
                setOnClickListener {
                    keyHaptic()
                    listener.onChar(if (shifted) opt.uppercase() else opt)
                    if (shifted && !capsLock && baseChar.length == 1 && baseChar[0] in 'a'..'z') {
                        shifted = false; applyShiftCase()
                    }
                    alternatesPopup?.dismiss()
                }
            }, LinearLayout.LayoutParams(dp(40), dp(46)).apply { marginStart = dp(2); marginEnd = dp(2) })
        }
        val w = options.size * dp(44) + dp(8)
        val h = dp(56)
        // Must NOT be focusable: a focusable popup steals focus from the edited
        // field and the system hides the IME. Touchable + outside-touchable lets
        // us still receive taps and dismiss on an outside tap.
        val popup = PopupWindow(rowView, w, h, false).apply {
            isClippingEnabled = false
            isTouchable = true
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener { alternatesPopup = null }
        }
        alternatesPopup = popup
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val x = (loc[0] + anchor.width / 2 - w / 2).coerceAtLeast(dp(2))
        val y = loc[1] - h - dp(2)
        popup.showAtLocation(this, Gravity.NO_GRAVITY, x, y)
    }

    // ---- Shared key factories ----

    private fun textView(label: String, textColor: Int, sizeSp: Float): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            isAllCaps = false
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            isClickable = true
            isFocusable = true
        }

    private fun iconKey(iconRes: Int, bgRes: Int, onClick: () -> Unit): ImageView =
        ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(colIcon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val p = dp(13)
            setPadding(p, p, p, p)
            background = keyBg(bgRes)
            isClickable = true
            setOnClickListener { onClick() }
        }

    // ---- Layout helpers ----

    private fun row(heightDp: Int): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        // No top margin between rows: that strip would be a dead zone. The visual
        // gap is created by the inset key backgrounds instead, so the row height
        // is bumped to keep the same pitch while the whole row stays touchable.
        isMotionEventSplittingEnabled = true
        // The extra height (beyond the key) plus keyBg's vertical inset sets the
        // gap between rows; tuned so keys stay the same height but rows breathe.
        layoutParams = LayoutParams(MATCH, dp(heightDp) + dp(8))
    }

    /**
     * Cells tile their row edge-to-edge (no margins) so there are no dead spots
     * between keys. Each key's visible rounded background is inset via [keyBg];
     * the touch target fills the entire cell.
     */
    private fun addCell(row: LinearLayout, view: View, weight: Float) {
        row.addView(view, LinearLayout.LayoutParams(0, MATCH, weight))
    }

    /** Wraps a key drawable in an inset so the visible key is smaller than its
     *  (fully touchable) cell — this is what creates the gaps between keys. Split
     *  mode uses a wider horizontal gap to match the Samsung keyboard. */
    private fun keyBg(res: Int): InsetDrawable {
        val h = if (wide) dp(6) else dp(2)
        return InsetDrawable(drawable(res), h, dp(4), h, dp(4))
    }

    private fun currentCase(key: String) = if (shifted && !symbols) key.uppercase() else key

    private fun applyShiftCase() {
        if (!symbols) {
            letterKeys.forEach {
                it.text = if (shifted) it.text.toString().uppercase() else it.text.toString().lowercase()
            }
        }
        shiftKey?.apply {
            background = keyBg(if (shifted) R.drawable.key_func_active_bg else R.drawable.key_func_bg)
            setColorFilter(if (shifted) colAccent else colIcon)
        }
    }

    private fun pill(fill: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(fill)
    }

    private fun popupBg(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(colLetterKey)
        setStroke(dp(1), Color.parseColor("#22000000"))
    }

    private fun color(res: Int) = ContextCompat.getColor(context, res)
    private fun drawable(res: Int) = ContextCompat.getDrawable(context, res)

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val CENTER_GAP = 3.2f

        private val NUMBERS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        private val ROW1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
        private val ROW2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
        private val ROW3 = listOf("z", "x", "c", "v", "b", "n", "m")

        // Page 1: shifted number symbols line up under the numbers; the 3rd row is
        // 7 keys so the page-cycle (left) and backspace (right) match the letters.
        private val SYM_PAGE1 = listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")"),
            listOf("-", "+", "=", "/", "'", "\"", "?"),
        )
        // Page 2: less-common symbols and currency.
        private val SYM_PAGE2 = listOf(
            listOf("~", "`", "|", "•", "·", "…", "°", "©", "®", "™"),
            listOf("£", "€", "¥", "¢", "§", "¶", "÷", "×", "±", "≈"),
            listOf("\\", ";", ":", "{", "}", "[", "]"),
        )
    }
}
