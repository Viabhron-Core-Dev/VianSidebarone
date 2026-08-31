package com.example.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.graphics.drawable.IconCompat

/**
 * DynamicSpeedIconGenerator: Ultra-lightweight status-bar dynamic speed icon generator.
 * Eliminates repeated bitmap/canvas allocations by reusing persistent rendering buffers.
 */
class DynamicSpeedIconGenerator(private val context: Context) {

    private val density = context.resources.displayMetrics.density

    // Determine target system status bar icon size (usually 24dp = 48-72px depending on density/OEM)
    private val iconSize: Int = run {
        @SuppressLint("DiscouragedApi", "InternalInsetResource")
        val resId = context.resources.getIdentifier("status_bar_icon_size", "dimen", "android")
        if (resId > 0) {
            val dim = context.resources.getDimensionPixelSize(resId)
            if (dim > 0) dim else (24 * density).toInt()
        } else {
            (24 * density).toInt()
        }
    }.coerceAtLeast(48)

    // Reusable rendering buffers
    private var cachedBitmap: Bitmap? = null
    private var cachedCanvas: Canvas? = null

    // Reusable Paint instances
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

    private val boundsRect = Rect()

    init {
        ensureBuffers()
    }

    private fun ensureBuffers() {
        if (cachedBitmap == null || cachedBitmap?.isRecycled == true) {
            val bmp = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
            cachedBitmap = bmp
            cachedCanvas = Canvas(bmp)
        }
    }

    /**
     * Renders numeric speed and unit (e.g. "1.2" and "M", or "450" and "K")
     * onto the reusable bitmap and wraps into an IconCompat.
     */
    @Synchronized
    fun generateSpeedIcon(speedValue: String, speedUnit: String): IconCompat {
        ensureBuffers()
        val bitmap = cachedBitmap ?: Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        val canvas = cachedCanvas ?: Canvas(bitmap)

        // Clear previous frame
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val w = iconSize.toFloat()
        val h = iconSize.toFloat()
        val cx = w / 2f

        // Top line: Speed number
        val numTextSize = if (speedValue.length > 3) h * 0.42f else h * 0.48f
        speedPaint.textSize = numTextSize
        speedPaint.getTextBounds(speedValue, 0, speedValue.length, boundsRect)
        val numY = h * 0.46f

        canvas.drawText(speedValue, cx, numY, speedPaint)

        // Bottom line: Unit (K, M, G, B, etc.)
        val unitTextSize = h * 0.36f
        unitPaint.textSize = unitTextSize
        val unitY = h * 0.88f

        canvas.drawText(speedUnit, cx, unitY, unitPaint)

        return IconCompat.createWithBitmap(bitmap)
    }

    fun onTrimMemory() {
        synchronized(this) {
            cachedBitmap?.recycle()
            cachedBitmap = null
            cachedCanvas = null
        }
    }
}
