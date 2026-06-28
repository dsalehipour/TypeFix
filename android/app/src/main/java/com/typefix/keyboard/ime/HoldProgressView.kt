package com.typefix.keyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View

/**
 * The little card shown above the finger while holding the ✨ sparkle to toggle
 * Auto mode: a circular progress ring around a center glyph, with a caption.
 * Set [progress] (0..1), [centerGlyph], and [caption]; the host animates them.
 */
class HoldProgressView(
    context: Context,
    private val bgColor: Int,
    private val trackColor: Int,
    private val accentColor: Int,
    private val textColor: Int,
    private val subTextColor: Int,
) : View(context) {

    var progress: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    var centerGlyph: String = "\u2728"
        set(value) { field = value; invalidate() }

    var caption: String = "Keep holding…"
        set(value) { field = value; invalidate() }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = trackColor
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(6f)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accentColor
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(6f)
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        textSize = dp(26f)
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = subTextColor
        textAlign = Paint.Align.CENTER
        textSize = dp(13f)
    }

    private val arc = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radiusCorner = dp(20f)
        canvas.drawRoundRect(0f, 0f, w, h, radiusCorner, radiusCorner, bgPaint)

        val cx = w / 2f
        val cy = h * 0.40f
        val ringRadius = dp(30f)
        arc.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)

        canvas.drawArc(arc, 0f, 360f, false, trackPaint)
        canvas.drawArc(arc, -90f, 360f * progress, false, progressPaint)

        val glyphBaseline = cy - (glyphPaint.descent() + glyphPaint.ascent()) / 2f
        canvas.drawText(centerGlyph, cx, glyphBaseline, glyphPaint)

        canvas.drawText(caption, cx, h * 0.84f, captionPaint)
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
    )
}
