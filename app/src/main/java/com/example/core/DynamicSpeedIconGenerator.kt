package com.example.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.drawable.IconCompat
import kotlin.math.roundToInt

/**
 * DynamicSpeedIconGenerator: Generates high-fidelity, crisp dynamic speed icons
 * matching Android SystemUI's standard 24dp small-icon grid, exact display density,
 * bold condensed typography, high optical glyph fill, and integer-snapped baselines.
 * Produces an immutable bitmap snapshot per generation to prevent asynchronous SystemUI corruption.
 */
class DynamicSpeedIconGenerator(private val context: Context) {

    private val density = context.resources.displayMetrics.density
    private val densityDpi = context.resources.displayMetrics.densityDpi

    // Standard Android Small Icon dimension is 24dp (e.g., 48px at 320dpi / 2.0x density)
    private val iconSize: Int = (24f * density).roundToInt().coerceAtLeast(24)

    // Reusable Paint instances with high-quality anti-aliasing, font hinting & subpixel text
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        hinting = Paint.HINTING_ON
        letterSpacing = -0.02f
        isDither = false
        isFilterBitmap = false
    }

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        hinting = Paint.HINTING_ON
        letterSpacing = -0.02f
        isDither = false
        isFilterBitmap = false
    }

    /**
     * Renders numeric speed and unit (e.g. "0" and "kB/s", "531" and "kB/s", or "1.2" and "MB/s")
     * onto an exact-size immutable bitmap snapshot matching the reference status-bar layout and glyph scale.
     */
    @Synchronized
    fun generateSpeedIcon(speedValue: String, speedUnit: String): IconCompat {
        val bitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        bitmap.density = densityDpi
        val canvas = Canvas(bitmap)

        val w = iconSize.toFloat()
        val h = iconSize.toFloat()
        val cx = (w / 2f).roundToInt().toFloat()

        // Normalize unit string to standard status-bar unit notation
        val formattedUnit = when (speedUnit) {
            "K", "k", "KB", "KB/s", "kb/s" -> "kB/s"
            "M", "m", "MB" -> "MB/s"
            "G", "g", "GB" -> "GB/s"
            "B", "b" -> "B/s"
            else -> if (speedUnit.endsWith("/s", ignoreCase = true)) speedUnit else "$speedUnit/s"
        }

        // Top line: Speed number
        // Calibrate font size to maximize optical glyph size while ensuring clean horizontal fit
        speedPaint.textSize = when {
            speedValue.length >= 4 -> h * 0.44f
            speedValue.length == 3 -> h * 0.52f
            speedValue.length == 2 -> h * 0.58f
            else -> h * 0.62f
        }

        // Ensure number fits horizontally within the canvas with a 1px boundary margin
        val maxNumWidth = w - 2f
        val measuredNumWidth = speedPaint.measureText(speedValue)
        if (measuredNumWidth > maxNumWidth && measuredNumWidth > 0f) {
            speedPaint.textSize *= (maxNumWidth / measuredNumWidth)
        }

        val speedFm = speedPaint.fontMetrics
        // Align top of glyphs to the top margin (1px)
        val speedBaseline = (1f - speedFm.ascent).roundToInt().toFloat()
        canvas.drawText(speedValue, cx, speedBaseline, speedPaint)

        // Bottom line: Unit (kB/s, MB/s, GB/s, B/s)
        // Sized to be prominent, legible, and fill the lower section
        unitPaint.textSize = h * 0.38f

        val maxUnitWidth = w - 2f
        val measuredUnitWidth = unitPaint.measureText(formattedUnit)
        if (measuredUnitWidth > maxUnitWidth && measuredUnitWidth > 0f) {
            unitPaint.textSize *= (maxUnitWidth / measuredUnitWidth)
        }

        val unitFm = unitPaint.fontMetrics
        // Align bottom of unit glyphs to the bottom margin (1px)
        val unitBaseline = (h - 1f - unitFm.descent).roundToInt().toFloat()
        canvas.drawText(formattedUnit, cx, unitBaseline, unitPaint)

        return IconCompat.createWithBitmap(bitmap)
    }

    fun onTrimMemory() {
        // Lightweight paints and metrics only, no retained persistent heap buffers
    }
}



