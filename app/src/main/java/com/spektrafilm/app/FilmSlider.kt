package com.spektrafilm.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Photo-editor style slider: label + value on top, a thin track whose fill
 * starts at the default value, a tick at the default, double-tap to reset.
 */
@SuppressLint("ViewConstructor")
class FilmSlider(context: Context, private val def: SliderDef) : View(context) {

    var value: Double = def.default
        private set
    var onChange: ((Double) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEXT; textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
    }
    private val valuePaint = Paint(labelPaint).apply {
        color = Ui.ACCENT; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.RIGHT
    }
    private val hintPaint = Paint(labelPaint).apply {
        color = Ui.TEXT_DIM; textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3A3A3A") }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8A8A8A") }
    private val rect = RectF()
    private var dragging = false

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            setValue(def.default, fromUser = true)
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            return true
        }
    })

    init {
        minimumHeight = (72 * density).roundToInt()
        isHapticFeedbackEnabled = true
    }

    fun setValue(v: Double, fromUser: Boolean = false) {
        val snapped = snap(v)
        if (snapped == value) { invalidate(); return }
        val crossedDefault = (value - def.default) * (snapped - def.default) < 0 || snapped == def.default
        value = snapped
        invalidate()
        if (fromUser) {
            if (crossedDefault) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onChange?.invoke(value)
        }
    }

    private fun snap(v: Double): Double {
        val c = v.coerceIn(def.min, def.max)
        val steps = ((c - def.min) / def.step).roundToInt()
        return (def.min + steps * def.step).coerceIn(def.min, def.max)
    }

    private val pad get() = 20 * density
    private val trackY get() = height * 0.68f
    private fun xOf(v: Double) = (pad + (width - 2 * pad) * ((v - def.min) / (def.max - def.min))).toFloat()
    private fun valueAt(x: Float) = def.min + (def.max - def.min) * ((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)

    fun formatted(v: Double = value): String {
        val s = String.format(Locale.US, "%.${def.decimals}f", v)
        val signed = if (def.min < 0 && v > 0) "+$s" else s
        return signed + def.unit
    }

    override fun onDraw(canvas: Canvas) {
        val top = 22 * density
        canvas.drawText(def.label, pad, top, labelPaint)
        canvas.drawText(formatted(), width - pad, top, valuePaint)
        if (def.hint.isNotEmpty()) canvas.drawText(def.hint, pad, top + 17 * density, hintPaint)

        val y = trackY
        val th = 2.5f * density
        rect.set(pad, y - th / 2, width - pad, y + th / 2)
        canvas.drawRoundRect(rect, th, th, trackPaint)
        val x0 = xOf(def.default)
        val x1 = xOf(value)
        rect.set(minOf(x0, x1), y - th / 2, maxOf(x0, x1), y + th / 2)
        canvas.drawRect(rect, fillPaint)
        canvas.drawRect(x0 - density, y - 6 * density, x0 + density, y + 6 * density, tickPaint)
        canvas.drawCircle(x1, y, (if (dragging) 11f else 9f) * density, thumbPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = abs(e.x - xOf(value)) < 36 * density
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.x - xOf(value)) < 36 * density) dragging = true
                if (dragging) setValue(valueAt(e.x), fromUser = true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // A plain tap on the track jumps there.
                if (!dragging && e.actionMasked == MotionEvent.ACTION_UP && e.eventTime - e.downTime < 250) {
                    setValue(valueAt(e.x), fromUser = true)
                }
                dragging = false
                invalidate()
            }
        }
        return true
    }
}
