package com.spektrafilm.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/**
 * Fit-to-screen image view with pinch zoom, pan and double-tap zoom.
 * Swapping in a bitmap of the same size keeps the current zoom, so live
 * preview updates don't jump. Press-and-hold triggers [onHold] (compare).
 */
class ZoomImageView(context: Context) : ImageView(context) {

    var onHold: ((Boolean) -> Unit)? = null

    private val current = Matrix()
    private var bmpW = 0
    private var bmpH = 0
    private var holding = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val s = d.scaleFactor
            val z = zoom()
            val target = (z * s).coerceIn(1f, 12f)
            current.postScale(target / z, target / z, d.focusX, d.focusY)
            clampAndApply()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (zoom() > 1.01f) {
                current.postTranslate(-dx, -dy)
                clampAndApply()
            }
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom() > 1.01f) {
                resetFit()
            } else {
                current.postScale(3f, 3f, e.x, e.y)
                clampAndApply()
            }
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            holding = true
            onHold?.invoke(true)
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    fun setBitmapKeepZoom(bmp: Bitmap?) {
        val sameSize = bmp != null && bmp.width == bmpW && bmp.height == bmpH
        super.setImageBitmap(bmp)
        bmpW = bmp?.width ?: 0
        bmpH = bmp?.height ?: 0
        if (!sameSize) resetFit() else imageMatrix = Matrix(current)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetFit()
    }

    private fun fitMatrix(): Matrix = Matrix().apply {
        if (bmpW > 0 && width > 0) {
            setRectToRect(
                RectF(0f, 0f, bmpW.toFloat(), bmpH.toFloat()),
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                Matrix.ScaleToFit.CENTER,
            )
        }
    }

    fun resetFit() {
        current.set(fitMatrix())
        imageMatrix = Matrix(current)
    }

    private fun zoom(): Float {
        val fit = FloatArray(9).also { fitMatrix().getValues(it) }[Matrix.MSCALE_X]
        val cur = FloatArray(9).also { current.getValues(it) }[Matrix.MSCALE_X]
        return if (fit > 0) cur / fit else 1f
    }

    private fun clampAndApply() {
        if (zoom() <= 1f) {
            current.set(fitMatrix())
        } else {
            val r = RectF(0f, 0f, bmpW.toFloat(), bmpH.toFloat())
            current.mapRect(r)
            var dx = 0f
            var dy = 0f
            if (r.width() <= width) dx = width / 2f - r.centerX()
            else if (r.left > 0) dx = -r.left
            else if (r.right < width) dx = width - r.right
            if (r.height() <= height) dy = height / 2f - r.centerY()
            else if (r.top > 0) dy = -r.top
            else if (r.bottom < height) dy = height - r.bottom
            current.postTranslate(dx, dy)
        }
        imageMatrix = Matrix(current)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(e)
        if ((e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) && holding) {
            holding = false
            onHold?.invoke(false)
        }
        return true
    }
}
