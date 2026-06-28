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
    fun onGestureWord(crossedKeys: String)
    fun onOpenSettings()
    fun onSwitchKeyboard()
    fun onEmojiPanelShown()
    fun onEmojiSearchQuery(query: String)
    fun onGifPanelShown()
    fun onGifSearchQuery(query: String)
    fun onGifSelected(gifUrl: String)
    fun onMic()
    fun onHideKeyboard()
    fun onToggleAutoMode()
    fun onCancelFix()
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
    private val statusRow: LinearLayout
    private val statusLabel: TextView
    private val undoButton: TextView
    private val cancelButton: TextView
    private val contentContainer: FrameLayout
    private val keyRows: LinearLayout

    private val letterKeys = mutableListOf<TextView>()
    private val gestureKeys = mutableListOf<Pair<TextView, Char>>()
    private var shiftKey: ImageView? = null

    private var shifted = false
    private var symbols = false

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

    private var micLongPressed = false
    private var micRunnable: Runnable? = null

    private val gifLoader: ImageLoader by lazy {
        ImageLoader.Builder(context).components { add(ImageDecoderDecoder.Factory()) }.build()
    }

    /** Wide/unfolded devices (e.g. Z Fold) get a center gap + split spacebar. */
    private val wide: Boolean get() = resources.configuration.screenWidthDp >= 600

    private val colBg = color(R.color.kb_bg)
    private val colText = color(R.color.kb_key_text)
    private val colTextSecondary = color(R.color.kb_key_text_secondary)
    private val colAccent = color(R.color.kb_accent)
    private val colIcon = color(R.color.kb_icon)
    private val colLetterKey = color(R.color.kb_key_letter)

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var downRawX = 0f
    private var downRawY = 0f
    private var gesturing = false
    private val crossed = StringBuilder()
    private var lastCrossedChar: Char? = null
    private var longPressActive = false
    private var longPressRunnable: Runnable? = null

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
        setPadding(dp(3), dp(4), dp(3), dp(8))

        // Render edge-to-edge (full width) and only pad the bottom for the nav
        // bar / home pill so keys clear it. Without this, the IME framework
        // leaves a transparent gap on the side in landscape (the app shows
        // through), which is the "not full width" issue on wide screens.
        fitsSystemWindows = false
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime()
            )
            // On wide/unfolded screens leave blank space on the left/right so the
            // keys don't run to the edges (easier for thumbs to reach).
            val side = if (wide) dp(56) else dp(3)
            v.setPadding(side, dp(4), side, maxOf(dp(8), bars.bottom))
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
        statusRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(color(R.color.kb_actionbar))
            visibility = GONE
            addView(statusLabel, LayoutParams(0, MATCH, 1f))
            addView(cancelButton, LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) })
            addView(undoButton, LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) })
        }
        buildToolbar()
        val toolbar = FrameLayout(context).apply {
            setBackgroundColor(color(R.color.kb_actionbar))
            addView(toolbarIcons, FrameLayout.LayoutParams(MATCH, MATCH))
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

        keyRows = LinearLayout(context).apply { orientation = VERTICAL }
        contentContainer = FrameLayout(context)
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
        // Mic: tap = speech-to-text, hold = switch keyboard (replaces a separate
        // keyboard-switch icon).
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
        // Down arrow = hide keyboard.
        val hide = ImageView(context).apply {
            setImageResource(R.drawable.ic_kb_arrow_down)
            setColorFilter(colIcon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(9), dp(11), dp(9))
            background = drawable(R.drawable.key_flat_bg)
            setOnClickListener { listener.onHideKeyboard() }
        }
        addView(hide, LayoutParams(dp(52), MATCH))
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
        statusRow.visibility = VISIBLE
        toolbarIcons.visibility = INVISIBLE
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
        cancelButton.visibility = VISIBLE
        statusRow.visibility = VISIBLE
        toolbarIcons.visibility = INVISIBLE
    }

    /** Shows a persistent "Fixed · Undo" affordance until the user types or undoes. */
    fun showUndo(label: String, color: Int) {
        removeCallbacks(revertStatus)
        statusLabel.text = label
        statusLabel.setTextColor(color)
        undoButton.visibility = VISIBLE
        cancelButton.visibility = GONE
        statusRow.visibility = VISIBLE
        toolbarIcons.visibility = INVISIBLE
        postDelayed(revertStatus, 6000)
    }

    fun clearUndo() = showIconsBar()

    private fun showIconsBar() {
        removeCallbacks(revertStatus)
        statusRow.visibility = GONE
        undoButton.visibility = GONE
        cancelButton.visibility = GONE
        toolbarIcons.visibility = VISIBLE
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
        add(toolIcon(R.drawable.ic_kb_language, colIcon) { listener.onSwitchKeyboard() })
        val moreIcon = toolIcon(R.drawable.ic_kb_more, colIcon) {}
        moreIcon.setOnClickListener { showOverflowMenu(moreIcon) }
        add(moreIcon)
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

    /** ⋯ overflow menu: settings now lives here (plus switch/hide). */
    private fun showOverflowMenu(anchor: View) {
        val menu = LinearLayout(context).apply {
            orientation = VERTICAL
            background = popupBg()
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val items = listOf<Pair<String, () -> Unit>>(
            "Settings" to { listener.onOpenSettings() },
            "Switch keyboard" to { listener.onSwitchKeyboard() },
            "Hide keyboard" to { listener.onHideKeyboard() },
        )
        val popup = PopupWindow(menu, dp(200), ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isClippingEnabled = false
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        items.forEach { (label, action) ->
            menu.addView(TextView(context).apply {
                text = label
                setTextColor(colText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = drawable(R.drawable.key_flat_bg)
                setOnClickListener { popup.dismiss(); action() }
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        popup.showAsDropDown(anchor, -dp(150), 0)
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

    private fun showKeyboard() {
        emojiPanelOpen = false
        searchMode = SearchMode.NONE
        activePanel = "keyboard"
        emojiSuggestedRow = null
        if (keyRows.parent !== contentContainer) {
            (keyRows.parent as? ViewGroup)?.removeView(keyRows)
            contentContainer.removeAllViews()
            contentContainer.addView(keyRows, FrameLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private fun showEmoji() {
        emojiPanelOpen = true
        searchMode = SearchMode.NONE
        activePanel = "emoji"
        (keyRows.parent as? ViewGroup)?.removeView(keyRows)
        contentContainer.removeAllViews()
        contentContainer.addView(buildEmojiPanel(), FrameLayout.LayoutParams(MATCH, WRAP))
        listener.onEmojiPanelShown()
    }

    private fun showClipboard() {
        emojiPanelOpen = false
        searchMode = SearchMode.NONE
        activePanel = "clipboard"
        emojiSuggestedRow = null
        contentContainer.removeAllViews()
        contentContainer.addView(buildClipboardPanel(), FrameLayout.LayoutParams(MATCH, WRAP))
    }

    /** Called by the IME with context-based (local or LLM) emoji suggestions. */
    fun setEmojiSuggestions(suggestions: List<String>) {
        emojiSuggestions = suggestions
        if (emojiPanelOpen) refreshEmojiSuggestions()
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
                setOnClickListener { listener.onChar(emoji) }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
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
                            setOnClickListener { listener.onChar(emoji) }
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

        if (mode == SearchMode.GIF) listener.onGifPanelShown()
        updateSearch()
    }

    private fun exitSearch() {
        searchMode = SearchMode.NONE
        searchResultsRow = null
        searchQueryLabel = null
        showEmoji()
    }

    private fun updateSearch() {
        val q = searchQuery.toString()
        searchQueryLabel?.text = if (q.isEmpty()) searchHint() else q
        searchQueryLabel?.setTextColor(if (q.isEmpty()) colTextSecondary else colText)
        when (searchMode) {
            SearchMode.EMOJI -> {
                renderEmojiResults(EmojiSearchIndex.search(q))
                if (q.isNotBlank()) listener.onEmojiSearchQuery(q)
            }
            SearchMode.GIF -> if (q.isNotBlank()) listener.onGifSearchQuery(q)
            SearchMode.NONE -> {}
        }
    }

    /** LLM-based semantic emoji results from the IME. */
    fun setEmojiSearchResults(results: List<String>) {
        if (searchMode == SearchMode.EMOJI && results.isNotEmpty()) renderEmojiResults(results)
    }

    /** GIF results (Tenor) from the IME. */
    fun setGifResults(results: List<GifClient.Gif>) {
        if (searchMode != SearchMode.GIF) return
        val row = searchResultsRow ?: return
        row.removeAllViews()
        results.forEach { gif ->
            row.addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = drawable(R.drawable.key_flat_bg)
                load(gif.previewUrl, gifLoader)
                setOnClickListener { listener.onGifSelected(gif.gifUrl) }
            }, LinearLayout.LayoutParams(dp(150), MATCH).apply { marginEnd = dp(4) })
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
                setOnClickListener { listener.onChar(emoji) }
            }, LinearLayout.LayoutParams(dp(48), MATCH))
        }
    }

    private fun buildClipboardPanel(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
        val text = runCatching {
            clip?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull()

        val body = TextView(context).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        if (text.isNullOrBlank()) {
            body.text = "Clipboard is empty"
            body.setTextColor(colTextSecondary)
        } else {
            body.text = text
            body.setTextColor(colText)
            body.background = drawable(R.drawable.key_letter_bg)
            body.setOnClickListener { listener.onChar(text) }
        }
        addView(LinearLayout(context).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(body, LayoutParams(MATCH, dp(190)))
        }, LayoutParams(MATCH, dp(206)))
        addView(panelBottomBar())
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
        val back = ImageView(context).apply {
            setImageResource(R.drawable.ic_kb_backspace)
            setColorFilter(colIcon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = drawable(R.drawable.key_func_bg)
            setOnClickListener { listener.onBackspace() }
        }
        addView(back, LinearLayout.LayoutParams(0, MATCH, 2f).apply { marginStart = dp(2); marginEnd = dp(2) })
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
        keyRows.addView(symbolRow(SYM_ROW1))
        keyRows.addView(symbolRow(SYM_ROW2))
        keyRows.addView(thirdRow(SYM_ROW3, lettersPage = false))
        keyRows.addView(bottomRow())
    }

    private fun numberRow(): View = row(40).apply {
        NUMBERS.forEachIndexed { i, n ->
            addCell(this, charKey(n, R.drawable.key_letter_bg, colText, 16f, gestureEligible = false), 1f)
            if (wide && i == 4) addCenterGap(this)
        }
    }

    private fun letterRow(keys: List<String>, indent: Float, splitAfter: Int): View = row(50).apply {
        if (!wide && indent > 0f) addCell(this, View(context), indent)
        keys.forEachIndexed { i, k ->
            val key = charKey(k, R.drawable.key_letter_bg, colText, 19f, gestureEligible = true)
            letterKeys.add(key)
            gestureKeys.add(key to k[0])
            addCell(this, key, 1f)
            if (wide && i == splitAfter - 1) addCenterGap(this)
        }
        if (!wide && indent > 0f) addCell(this, View(context), indent)
    }

    private fun addCenterGap(row: LinearLayout) = addCell(row, View(context), CENTER_GAP)

    private fun symbolRow(keys: List<String>): View = row(50).apply {
        keys.forEach { k ->
            addCell(this, charKey(k, R.drawable.key_letter_bg, colText, 18f, gestureEligible = false), 1f)
        }
    }

    private fun thirdRow(middle: List<String>, lettersPage: Boolean): View = row(50).apply {
        val shift = iconKey(R.drawable.ic_kb_shift, R.drawable.key_func_bg) {
            shifted = !shifted
            applyShiftCase()
        }
        shiftKey = shift
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
        val backspace = ImageView(context).apply {
            setImageResource(R.drawable.ic_kb_backspace)
            setColorFilter(colIcon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val p = dp(13)
            setPadding(p, p, p, p)
            background = drawable(R.drawable.key_func_bg)
            setOnTouchListener { v, e -> handleBackspaceTouch(v, e) }
        }
        addCell(this, backspace, 1.5f)
    }

    private fun bottomRow(): View = row(50).apply {
        val toggleLabel = if (symbols) "ABC" else "!#1"
        addCell(this, textView(toggleLabel, colText, 14f).apply {
            background = drawable(R.drawable.key_func_bg)
            setOnClickListener { symbols = !symbols; renderKeys() }
        }, 1.5f)
        addCell(this, charKey(",", R.drawable.key_letter_bg, colText, 18f, gestureEligible = false), 1f)
        if (wide) {
            addCell(this, spaceKey(), 3.6f)
            addCenterGap(this)
            addCell(this, spaceKey(), 3.6f)
        } else {
            addCell(this, spaceKey(), 5f)
        }
        addCell(this, charKey(".", R.drawable.key_letter_bg, colText, 18f, gestureEligible = false), 1f)
        addCell(this, iconKey(R.drawable.ic_kb_enter, R.drawable.key_func_bg) { handleEnter() }, 1.5f)
    }

    private fun spaceKey(): TextView = textView("English (US)", colTextSecondary, 13f).apply {
        background = drawable(R.drawable.key_letter_bg)
        setOnClickListener { commitChar(" ") }
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
        key.background = drawable(bgRes)
        key.setOnTouchListener { v, e -> handleCharTouch(v as TextView, baseChar, gestureEligible, e) }
        return key
    }

    private fun handleCharTouch(v: TextView, baseChar: String, gestureEligible: Boolean, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.isPressed = true
                downRawX = e.rawX
                downRawY = e.rawY
                gesturing = false
                longPressActive = false
                if (gestureEligible) {
                    crossed.setLength(0)
                    crossed.append(baseChar.lowercase())
                    lastCrossedChar = baseChar.lowercase().firstOrNull()
                }
                showPreview(v, currentCase(baseChar))
                scheduleLongPress(v, baseChar)
            }
            MotionEvent.ACTION_MOVE -> {
                val moved = hypot(e.rawX - downRawX, e.rawY - downRawY)
                if (!gesturing && gestureEligible && moved > touchSlop * 2) {
                    gesturing = true
                    cancelLongPressTimer()
                }
                if (gesturing) {
                    val ch = nearestLetterChar(e.rawX, e.rawY)
                    if (ch != null && ch != lastCrossedChar) {
                        crossed.append(ch)
                        lastCrossedChar = ch
                        previewText?.text = if (shifted) ch.uppercaseChar().toString() else ch.toString()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                v.isPressed = false
                cancelLongPressTimer()
                if (longPressActive) { hidePreview(); return true }
                hidePreview()
                if (gesturing && gestureEligible && crossed.length >= 3 && searchMode == SearchMode.NONE) {
                    listener.onGestureWord(crossed.toString())
                } else {
                    commitChar(baseChar)
                }
                gesturing = false
            }
            MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                cancelLongPressTimer()
                hidePreview()
                gesturing = false
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
        if (shifted && baseChar.length == 1 && baseChar[0] in 'a'..'z') {
            shifted = false
            applyShiftCase()
        }
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
        if (searchMode != SearchMode.NONE) {
            exitSearch()
            return
        }
        listener.onEnter()
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

    private fun scheduleLongPress(anchor: View, baseChar: String) {
        cancelLongPressTimer()
        val alts = Accents.alternatesFor(baseChar)
        if (alts.isEmpty()) return
        val r = Runnable {
            longPressActive = true
            hidePreview()
            showAlternates(anchor, baseChar, alts)
        }
        longPressRunnable = r
        postDelayed(r, 320)
    }

    private fun cancelLongPressTimer() {
        longPressRunnable?.let { removeCallbacks(it) }
        longPressRunnable = null
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
                    listener.onChar(if (shifted) opt.uppercase() else opt)
                    if (shifted && baseChar.length == 1 && baseChar[0] in 'a'..'z') {
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
            setOnDismissListener { longPressActive = false }
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
            background = drawable(bgRes)
            isClickable = true
            setOnClickListener { onClick() }
        }

    // ---- Layout helpers ----

    private fun row(heightDp: Int): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        layoutParams = LayoutParams(MATCH, dp(heightDp)).apply { topMargin = dp(5) }
    }

    private fun addCell(row: LinearLayout, view: View, weight: Float) {
        row.addView(view, LinearLayout.LayoutParams(0, MATCH, weight).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        })
    }

    private fun currentCase(key: String) = if (shifted && !symbols) key.uppercase() else key

    private fun applyShiftCase() {
        if (!symbols) {
            letterKeys.forEach {
                it.text = if (shifted) it.text.toString().uppercase() else it.text.toString().lowercase()
            }
        }
        shiftKey?.apply {
            background = drawable(if (shifted) R.drawable.key_func_active_bg else R.drawable.key_func_bg)
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

        private val SYM_ROW1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        private val SYM_ROW2 = listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/")
        private val SYM_ROW3 = listOf("*", "\"", "'", ":", ";", "!", "?")
    }
}
