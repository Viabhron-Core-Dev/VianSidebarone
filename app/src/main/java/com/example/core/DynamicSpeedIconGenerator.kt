package com.example.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.drawable.IconCompat

/**
 * DynamicSpeedIconGenerator: Generates high-fidelity, crisp dynamic speed icons
 * rendered onto a fixed 96x96 ARGB_8888 canvas using precise font metrics and anti-aliasing.
 * Produces an immutable bitmap snapshot per generation to prevent asynchronous SystemUI corruption.
 */
class DynamicSpeedIconGenerator(private val context: Context) {

    companion object {
        private const val ICON_SIZE = 96
    }

    // Reusable Paint instances with high-quality anti-aliasing & subpixel text
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    /**
     * Renders numeric speed and unit (e.g. "531" and "K", or "1.2" and "M")
     * onto a 96x96 immutable bitmap snapshot and wraps into an IconCompat.
     */
    @Synchronized
    fun generateSpeedIcon(speedValue: String, speedUnit: String): IconCompat {
        val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val w = ICON_SIZE.toFloat()
        val h = ICON_SIZE.toFloat()
        val cx = w / 2f

        // Adjust top text size based on character count to prevent any horizontal clipping
        speedPaint.textSize = when {
            speedValue.length > 3 -> 34f
            speedValue.length == 3 -> 42f
            speedValue.length == 2 -> 46f
            else -> 50f
        }

        val topCenterY = h * 0.35f
        val speedFm = speedPaint.fontMetrics
        val speedBaseline = topCenterY - (speedFm.ascent + speedFm.descent) / 2f
        canvas.drawText(speedValue, cx, speedBaseline, speedPaint)

        // Bottom unit text
        unitPaint.textSize = 34f
        val bottomCenterY = h * 0.75f
        val unitFm = unitPaint.fontMetrics
        val unitBaseline = bottomCenterY - (unitFm.ascent + unitFm.descent) / 2f
        canvas.drawText(speedUnit, cx, unitBaseline, unitPaint)

        return IconCompat.createWithBitmap(bitmap)
    }

    fun onTrimMemory() {
        // Lightweight paints and metrics only, no retained persistent heap buffers
    }
}

