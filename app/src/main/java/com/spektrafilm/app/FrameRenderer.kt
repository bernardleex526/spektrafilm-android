package com.spektrafilm.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decorative borders drawn around the rendered photo. Purely cosmetic and
 * generic: no manufacturer marks. Film rebates take the colours they would
 * have in the chosen output path:
 *  - printed negative: rebate prints near-black, edge text comes out light;
 *  - scanned negative: orange mask base, bright sprocket holes;
 *  - scanned slide: black base, bright holes.
 */
object FrameRenderer {

    enum class Look { PRINT, SCAN_NEGATIVE, SCAN_POSITIVE }

    data class Spec(
        val style: String,
        /** 0..1 slider; scales border thickness 0.5x..1.5x. */
        val size: Double,
        val text: Boolean,
        val look: Look,
        val frameNumber: Int,
        val isoLabel: String,
    )

    fun apply(src: Bitmap, spec: Spec): Bitmap = when (spec.style) {
        "white" -> white(src, spec)
        "film35" -> film(src, spec, wide = false)
        "film120" -> film(src, spec, wide = true)
        "instant" -> instant(src, spec)
        else -> src
    }

    private fun scale(spec: Spec) = 0.5f + spec.size.toFloat()

    private fun white(src: Bitmap, spec: Spec): Bitmap {
        val b = (min(src.width, src.height) * 0.06f * scale(spec)).roundToInt().coerceAtLeast(2)
        val out = Bitmap.createBitmap(src.width + 2 * b, src.height + 2 * b, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.parseColor("#F3F1EC"))
            drawBitmap(src, b.toFloat(), b.toFloat(), null)
        }
        return out
    }

    private fun instant(src: Bitmap, spec: Spec): Bitmap {
        val s = scale(spec)
        val side = (src.width * 0.055f * s).roundToInt().coerceAtLeast(2)
        val bottom = (src.width * 0.24f * s).roundToInt().coerceAtLeast(side)
        val out = Bitmap.createBitmap(src.width + 2 * side, src.height + side + bottom, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.parseColor("#F1EFE8"))
            drawBitmap(src, side.toFloat(), side.toFloat(), null)
        }
        return out
    }

    /**
     * 35mm: 24mm frame on 35mm stock -> 5.5mm rebate per side with
     * 2.8x1.98mm perforations at 4.75mm pitch. 120: 56mm frame on 61mm
     * stock, no perforations, text on one edge.
     */
    private fun film(src: Bitmap, spec: Spec, wide: Boolean): Bitmap {
        val landscape = src.width >= src.height
        val shortPx = min(src.width, src.height).toFloat()
        val longPx = max(src.width, src.height).toFloat()
        val mm = shortPx / if (wide) 56f else 24f
        val s = scale(spec)
        val rebate = (if (wide) 3.2f else 5.5f) * mm * s
        val gap = (if (wide) 3.0f else 1.8f) * mm * s

        val (base, hole, ink) = when (spec.look) {
            Look.PRINT -> Triple(Color.parseColor("#120F0D"), Color.parseColor("#030303"), Color.parseColor("#D8C29A"))
            Look.SCAN_NEGATIVE -> Triple(Color.parseColor("#C86E35"), Color.parseColor("#F6F3EC"), Color.parseColor("#5B2A0E"))
            Look.SCAN_POSITIVE -> Triple(Color.parseColor("#0A0A0A"), Color.parseColor("#F2F0EA"), Color.parseColor("#E9D79A"))
        }

        // Work in "landscape strip" coordinates, then rotate for portrait.
        val stripW = (longPx + 2 * gap).roundToInt()
        val stripH = (shortPx + 2 * rebate).roundToInt()
        val strip = Bitmap.createBitmap(stripW, stripH, Bitmap.Config.ARGB_8888)
        val c = Canvas(strip)
        c.drawColor(base)
        val photo = if (landscape) src else rotate(src, -90f)
        c.drawBitmap(photo, gap, rebate, null)
        if (photo !== src) photo.recycle()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (!wide) {
            paint.color = hole
            val pw = 1.98f * mm
            val ph = 2.8f * mm
            val pitch = 4.75f * mm
            val inset = (rebate - ph) * 0.42f
            val r = 0.5f * mm
            var x = (stripW % pitch) / 2f
            while (x + pw <= stripW) {
                c.drawRoundRect(RectF(x, inset, x + pw, inset + ph), r, r, paint)
                c.drawRoundRect(RectF(x, stripH - inset - ph, x + pw, stripH - inset), r, r, paint)
                x += pitch
            }
        }
        if (spec.text) {
            paint.color = ink
            paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            paint.textSize = (if (wide) 1.9f else 1.35f) * mm * s.coerceAtMost(1.2f)
            val n = spec.frameNumber
            val textY = if (wide) stripH - rebate * 0.32f else stripH - (rebate - 2.8f * mm) * 0.12f - paint.textSize * 0.15f
            val numY = if (wide) rebate * 0.68f else textY
            c.drawText("SPEKTRAFILM ${spec.isoLabel}".trim(), stripW * 0.12f, textY, paint)
            c.drawText("▸ $n", stripW * 0.70f, numY, paint)
            if (!wide) c.drawText("${n}A", stripW * 0.93f, numY, paint)
            else c.drawText("$n", stripW * 0.08f, rebate * 0.68f, paint)
        }
        return if (landscape) strip else rotate(strip, 90f).also { strip.recycle() }
    }

    private fun rotate(b: Bitmap, deg: Float): Bitmap {
        val m = android.graphics.Matrix().apply { postRotate(deg) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    /** "400" from names like "Kodak Portra 400" or "Ultramax 400"; empty if none. */
    fun isoFrom(name: String): String = Regex("""\b(\d{2,4})(?:[DT]\b)?""").findAll(name)
        .map { it.groupValues[1] }.lastOrNull { it.toInt() in 6..3200 } ?: ""
}
