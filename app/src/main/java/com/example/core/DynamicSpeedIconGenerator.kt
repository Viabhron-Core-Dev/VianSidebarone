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
 * DynamicSpeedIconGenerator: Generates high-fidelity, crisp dynamic speed icons
 * matching native status-bar icon pixel dimensions, device display density,
 * condensed bold typography, and integer-snapped baselines.
 * Produces an immutable bitmap snapshot per generation to prevent asynchronous SystemUI corruption.
 */
class DynamicSpeedIconGenerator(private val context: Context) {

    private val density = context.resources.displayMetrics.density
    private val densityDpi = context.resources.displayMetrics.densityDpi

    // Determine target system status bar icon size (e.g. 44px on Redmi/Xiaomi, or 24dp fallback)
    private val iconSize: Int = run {
        @SuppressLint("DiscouragedApi", "InternalInsetResource")
        val resId = context.resources.getIdentifier("status_bar_icon_size", "dimen", "android")
        if (resId > 0) {
            val dim = context.resources.getDimensionPixelSize(resId)
            if (dim > 0) dim else (24 * density).toInt()
        } else {
            (24 * density).toInt()
        }
    }.coerceAtLeast(1)

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

        // Adjust top text size based on character count to prevent any horizontal clipping
        speedPaint.textSize = when {
            speedValue.length > 3 -> h * 0.38f
            speedValue.length == 3 -> h * 0.44f
            speedValue.length == 2 -> h * 0.48f
            else -> h * 0.52f
        }

        val topCenterY = h * 0.32f
        val speedFm = speedPaint.fontMetrics
        val speedBaseline = Math.round(topCenterY - (speedFm.ascent + speedFm.descent) / 2f).toFloat()
        canvas.drawText(speedValue, cx, speedBaseline, speedPaint)

        // Bottom unit text
        unitPaint.textSize = h * 0.36f
        val bottomCenterY = h * 0.76f
        val unitFm = unitPaint.fontMetrics
        val unitBaseline = Math.round(bottomCenterY - (unitFm.ascent + unitFm.descent) / 2f).toFloat()
        canvas.drawText(speedUnit, cx, unitBaseline, unitPaint)

        return IconCompat.createWithBitmap(bitmap)
    }

    fun onTrimMemory() {
        // Lightweight paints and metrics only, no retained persistent heap buffers
    }
}


