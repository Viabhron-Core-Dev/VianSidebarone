package com.example.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.drawable.IconCompat

/**
 * DynamicSpeedIconGenerator: Ultra-lightweight status-bar dynamic speed icon generator.
 * Renders directly at the target status-bar icon pixel size with proper font metrics.
 * Produces an immutable bitmap snapshot per generation to prevent asynchronous SystemUI corruption.
 */
class DynamicSpeedIconGenerator(private val context: Context) {

    private val density = context.resources.displayMetrics.density

    // Determine target system status bar icon size (e.g. 24dp or OEM specific status_bar_icon_size)
    private val iconSize: Int = run {
        @SuppressLint("DiscouragedApi", "InternalInsetResource")
        val resId = context.resources.getIdentifier("status_bar_icon_size", "dimen", "android")
        if (resId > 0) {
            val dim = context.resources.getDimensionPixelSize(resId)
            if (dim > 0) dim else (24 * density).toInt()
        } else {
            (24 * density).toInt()
        }
    }

    // Reusable Paint instances with anti-aliasing
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    /**
     * Renders numeric speed and unit (e.g. "531" and "K", or "1.2" and "M")
     * onto an exact-size immutable bitmap snapshot and wraps into an IconCompat.
     */
    @Synchronized
    fun generateSpeedIcon(speedValue: String, speedUnit: String): IconCompat {
        val size = iconSize.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val w = size.toFloat()
        val h = size.toFloat()
        val cx = w / 2f

        // Top line: Speed number
        speedPaint.textSize = if (speedValue.length > 3) h * 0.44f else h * 0.50f
        val speedFm = speedPaint.fontMetrics
        val topCenterY = h * 0.30f
        val speedBaseline = topCenterY - (speedFm.ascent + speedFm.descent) / 2f
        canvas.drawText(speedValue, cx, speedBaseline, speedPaint)

        // Bottom line: Unit (K, M, G, B, etc.)
        unitPaint.textSize = h * 0.38f
        val unitFm = unitPaint.fontMetrics
        val bottomCenterY = h * 0.76f
        val unitBaseline = bottomCenterY - (unitFm.ascent + unitFm.descent) / 2f
        canvas.drawText(speedUnit, cx, unitBaseline, unitPaint)

        return IconCompat.createWithBitmap(bitmap)
    }

    fun onTrimMemory() {
        // Paints and metrics are lightweight and retained, no large persistent heap buffers
    }
}

