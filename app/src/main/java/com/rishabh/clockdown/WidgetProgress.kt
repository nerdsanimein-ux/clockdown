package com.rishabh.clockdown

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/**
 * Progress for the Timer widget. A widget can't run drawing code, so the ring, dots and bars are painted into small
 * pictures here (same shapes as the in-app card, see ProgressVisual) and handed to the widget.
 */
object WidgetProgress {
    private const val RING_DP = 40
    private const val STRIP_DP = 10

    /** A soft outline for the see-through styles, so the shapes stay visible over any wallpaper. Null = none needed. */
    fun halo(variant: Variant): Int? = when (variant) {
        Variant.GLASS, Variant.THIN -> 0xB3000000.toInt() // white shapes get a dark halo
        Variant.LIGHTGLASS, Variant.THINLIGHT -> 0xE6FFFFFF.toInt() // dark shapes get a pale one
        else -> null
    }

    private fun paint(color: Int, halo: Int?, d: Float, stroke: Float? = null) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        if (halo != null) setShadowLayer(1.5f * d, 0f, 0.5f * d, halo)
        if (stroke != null) { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
    }

    private fun track(fg: Int) = ColorUtils.setAlphaComponent(fg, 0x48)

    fun ring(ctx: Context, progress: Float, fg: Int, halo: Int?): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val size = (RING_DP * d).roundToInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val stroke = size * 0.16f
        val inset = stroke / 2 + 2 * d // room for the halo
        val box = RectF(inset, inset, size - inset, size - inset)
        c.drawArc(box, 0f, 360f, false, paint(track(fg), halo, d, stroke))
        if (progress > 0f) c.drawArc(box, -90f, 360f * progress, false, paint(fg, halo, d, stroke))
        return bmp
    }

    /** Dots (one row) or bars (rounded segments), [widthDp] wide. */
    fun strip(ctx: Context, dots: Boolean, progress: Float, fg: Int, halo: Int?, widthDp: Int): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val w = (widthDp * d).roundToInt()
        val h = (STRIP_DP * d).roundToInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val on = paint(fg, halo, d)
        val off = paint(track(fg), halo, d)
        val pad = 1.5f * d
        if (dots) {
            val n = 20
            val cell = w.toFloat() / n
            val r = minOf(cell * 0.32f, h / 2f - pad)
            val filled = (progress * n).roundToInt()
            for (i in 0 until n) c.drawCircle((i + 0.5f) * cell, h / 2f, r, if (i < filled) on else off)
        } else {
            val n = 10
            val gap = w * 0.025f
            val bw = (w - gap * (n - 1)) / n
            val filled = (progress * n).roundToInt()
            for (i in 0 until n) {
                val x = i * (bw + gap)
                c.drawRoundRect(RectF(x, pad, x + bw, h - pad), 2.5f * d, 2.5f * d, if (i < filled) on else off) // squarer than the dots
            }
        }
        return bmp
    }
}
