package com.spektrafilm.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import kotlin.math.roundToInt

/** Shared colours and small view factories for the editor UI. */
object Ui {
    val BG = Color.parseColor("#0E0E0E")
    val PANEL = Color.parseColor("#161616")
    val CHIP = Color.parseColor("#242424")
    val TEXT = Color.parseColor("#EDEDED")
    val TEXT_DIM = Color.parseColor("#8C8C8C")
    val ACCENT = Color.parseColor("#E8A33D")

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    fun rounded(color: Int, radiusDp: Float, ctx: Context, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * ctx.resources.displayMetrics.density
            if (strokeColor != null) setStroke(dp(ctx, 1.5f), strokeColor)
        }

    /** Pill-shaped selectable chip. */
    fun chip(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        setPadding(dp(ctx, 14f), dp(ctx, 7f), dp(ctx, 14f), dp(ctx, 7f))
        styleChip(this, selected = false)
    }

    fun styleChip(v: TextView, selected: Boolean, modified: Boolean = false) {
        val ctx = v.context
        v.background = if (selected) rounded(ACCENT, 18f, ctx) else rounded(CHIP, 18f, ctx)
        v.setTextColor(if (selected) Color.BLACK else if (modified) ACCENT else TEXT)
        v.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    /** Flat text button used in the top bar. */
    fun barButton(ctx: Context, text: String, accent: Boolean = false): TextView = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(if (accent) Color.BLACK else TEXT)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(ctx, 16f), dp(ctx, 8f), dp(ctx, 16f), dp(ctx, 8f))
        background = if (accent) rounded(ACCENT, 20f, ctx) else rounded(Color.parseColor("#66000000"), 20f, ctx)
    }
}
