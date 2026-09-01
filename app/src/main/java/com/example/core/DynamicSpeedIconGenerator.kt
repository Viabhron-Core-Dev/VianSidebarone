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
 * bold condensed typography, and integer-snapped baselines.
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
        isDither = false
        isFilterBitmap = false
    }

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        hinting = Paint.HINTING_ON
        isDither = false
        isFilterBitmap = false
    }

    /**
     * Renders numeric speed and unit (e.g. "531" and "K", or "1.2" and "M")
     * onto an exact-size immutable bitmap snapshot and wraps into an IconCompat.
     */
    @Synchronized
    fun generateSpeedIcon(speedValue: String, speedUnit: String): IconCompat {
        val bitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        bitmap.density = densityDpi
        val canvas = Canvas(bitmap)

        val w = iconSize.toFloat()
        val h = iconSize.toFloat()
        val cx = Math.round(w / 2f).toFloat()

        // Top line: Speed number
        // Calibrate font size relative to 24dp canvas so text stays bold, legible, and unclipped
        speedPaint.textSize = when {
            speedValue.length > 3 -> h * 0.42f
            speedValue.length == 3 -> h * 0.48f
            speedValue.length == 2 -> h * 0.52f
            else -> h * 0.56f
        }

        val topCenterY = h * 0.33f
        val speedFm = speedPaint.fontMetrics
        val speedBaseline = Math.round(topCenterY - (speedFm.ascent + speedFm.descent) / 2f).toFloat()
        canvas.drawText(speedValue, cx, speedBaseline, speedPaint)

        // Bottom line: Unit (K, M, G, B, etc.)
        unitPaint.textSize = h * 0.38f
        val bottomCenterY = h * 0.77f
        val unitFm = unitPaint.fontMetrics
        val unitBaseline = Math.round(bottomCenterY - (unitFm.ascent + unitFm.descent) / 2f).toFloat()
        canvas.drawText(speedUnit, cx, unitBaseline, unitPaint)

        return IconCompat.createWithBitmap(bitmap)
    }

    fun onTrimMemory() {
        // Lightweight paints and metrics only, no retained persistent heap buffers
    }
}


