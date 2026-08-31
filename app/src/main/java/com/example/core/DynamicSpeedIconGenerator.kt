package com.example.core

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.graphics.drawable.IconCompat
import java.util.Locale

object DynamicSpeedIconGenerator {

    data class SpeedDisplay(val number: String, val unit: String)

    data class IconConfig(
        val font: String = "sans-serif-condensed",
        val isFakeBold: Boolean = true,
        val numScale: Float = 1.0f,
        val unitScale: Float = 1.0f,
        val letterSpacing: Float = -0.04f
    )

    private var activeConfig = IconConfig()

    private var cachedDensityDpi: Int = -1
    private var cachedSizePx: Int = -1
    private var cachedTypeface: Typeface? = null
    private var cachedNumPaint: Paint? = null
    private var cachedUnitPaint: Paint? = null
    private val cachedNumBounds = Rect()
    private val cachedUnitBounds = Rect()

    fun loadConfig(prefs: SharedPreferences): IconConfig {
        activeConfig = IconConfig()
        invalidatePaints()
        return activeConfig
    }

    fun updateActiveConfig(config: IconConfig) {
        activeConfig = config
        invalidatePaints()
    }

    fun invalidatePaints() {
        cachedTypeface = null
        cachedNumPaint = null
        cachedUnitPaint = null
        cachedSizePx = -1
        cachedDensityDpi = -1
    }

    fun onTrimMemory(level: Int) {
        invalidatePaints()
    }

    fun formatSpeed(bytesPerSec: Long, forcedUnit: String? = null): SpeedDisplay {
        if (bytesPerSec <= 0) {
            return SpeedDisplay("0", if (forcedUnit == "MB/s") "MB/s" else "KB/s")
        }

        val kbps = bytesPerSec / 1024.0
        val mbps = kbps / 1024.0

        return when (forcedUnit) {
            "KB/s" -> {
                val kb = ((bytesPerSec + 512) / 1024).toInt()
                SpeedDisplay(kb.toString(), "KB/s")
            }
            "MB/s" -> {
                val str = if (mbps < 10.0) String.format(Locale.US, "%.1f", mbps) else String.format(Locale.US, "%.0f", mbps)
                SpeedDisplay(str, "MB/s")
            }
            else -> {
                when {
                    bytesPerSec < 1000 -> {
                        SpeedDisplay("0", "KB/s")
                    }
                    bytesPerSec < 1000 * 1024 -> {
                        val kb = ((bytesPerSec + 512) / 1024).toInt()
                        SpeedDisplay(kb.toString(), "KB/s")
                    }
                    bytesPerSec < 100L * 1024 * 1024 -> {
                        val str = if (mbps < 10.0) String.format(Locale.US, "%.1f", mbps) else mbps.toInt().toString()
                        SpeedDisplay(str, "MB/s")
                    }
                    else -> {
                        val mb = (bytesPerSec / (1024 * 1024)).toInt()
                        SpeedDisplay(mb.toString(), "MB/s")
                    }
                }
            }
        }
    }

    fun getNotificationIconSize(context: Context): Int {
        if (cachedSizePx > 0) return cachedSizePx
        val res = context.resources
        val resId = res.getIdentifier("status_bar_icon_size", "dimen", "android")
        var size = -1
        if (resId > 0) {
            try {
                val s = res.getDimensionPixelSize(resId)
                if (s in 24..128) size = s
            } catch (e: Exception) {}
        }
        if (size <= 0) {
            val density = res.displayMetrics.density
            size = Math.round(24f * density).coerceAtLeast(24)
        }
        cachedSizePx = size
        return size
    }

    fun generateStatusBarBitmap(
        context: Context,
        bytesPerSec: Long,
        forcedUnit: String? = null,
        overrideConfig: IconConfig? = null
    ): Bitmap {
        val config = overrideConfig ?: activeConfig
        val display = formatSpeed(bytesPerSec, forcedUnit)
        
        val resources = context.resources
        val displayMetrics = resources.displayMetrics
        val densityDpi = displayMetrics.densityDpi

        val sizePx = getNotificationIconSize(context)

        // Always create a clean unshared bitmap so NotificationManager / SystemUI does not cache or tear dirty buffers
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
            this.density = densityDpi
        }
        val canvas = Canvas(bitmap)
        bitmap.eraseColor(Color.TRANSPARENT)

        renderIconToCanvas(canvas, sizePx, display, config)

        return bitmap
    }

    fun renderIconToCanvas(
        canvas: Canvas,
        sizePx: Int,
        display: SpeedDisplay,
        config: IconConfig
    ) {
        var tf = cachedTypeface
        if (tf == null) {
            tf = try {
                if (config.font.isNotEmpty()) {
                    Typeface.create(config.font, if (config.isFakeBold) Typeface.BOLD else Typeface.NORMAL)
                } else {
                    Typeface.create("sans-serif-condensed", if (config.isFakeBold) Typeface.BOLD else Typeface.NORMAL)
                }
            } catch (e: Exception) {
                if (config.isFakeBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            cachedTypeface = tf
        }

        var numPaint = cachedNumPaint
        if (numPaint == null) {
            numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                isFilterBitmap = false
                isDither = false
                style = Paint.Style.FILL
            }
            cachedNumPaint = numPaint
        }
        numPaint.typeface = tf
        numPaint.isFakeBoldText = config.isFakeBold
        numPaint.letterSpacing = config.letterSpacing

        var unitPaint = cachedUnitPaint
        if (unitPaint == null) {
            unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                isFilterBitmap = false
                isDither = false
                style = Paint.Style.FILL
            }
            cachedUnitPaint = unitPaint
        }
        unitPaint.typeface = tf
        unitPaint.isFakeBoldText = config.isFakeBold
        unitPaint.letterSpacing = config.letterSpacing

        val centerX = (sizePx / 2f)

        // Top Slot: Number text (Takes top ~62% of icon height, maximizing legibility with tight bounds)
        val maxNumW = (sizePx - 2f).coerceAtLeast(10f)
        val maxNumH = sizePx * 0.62f * config.numScale
        numPaint.textSize = maxNumH
        val numTextW = numPaint.measureText(display.number)
        val scaleNum = minOf(if (numTextW > 0f) maxNumW / numTextW else 1f, 1.0f)
        numPaint.textSize = (maxNumH * scaleNum).coerceAtLeast(8f)

        numPaint.getTextBounds(display.number, 0, display.number.length, cachedNumBounds)
        val numCenterY = (sizePx * 0.34f)
        val numBaseline = numCenterY + (cachedNumBounds.height() / 2f) - cachedNumBounds.bottom

        // Bottom Slot: Unit text (kB/s, MB/s)
        val maxUnitW = (sizePx - 2f).coerceAtLeast(10f)
        val maxUnitH = sizePx * 0.34f * config.unitScale
        unitPaint.textSize = maxUnitH
        val unitTextW = unitPaint.measureText(display.unit)
        val scaleUnit = minOf(if (unitTextW > 0f) maxUnitW / unitTextW else 1f, 1.0f)
        unitPaint.textSize = (maxUnitH * scaleUnit).coerceAtLeast(6f)

        unitPaint.getTextBounds(display.unit, 0, display.unit.length, cachedUnitBounds)
        val unitCenterY = (sizePx * 0.81f)
        val unitBaseline = unitCenterY + (cachedUnitBounds.height() / 2f) - cachedUnitBounds.bottom

        canvas.drawText(display.number, centerX, numBaseline, numPaint)
        canvas.drawText(display.unit, centerX, unitBaseline, unitPaint)
    }

    fun generateIconCompat(context: Context, bytesPerSec: Long, forcedUnit: String? = null): IconCompat {
        val bitmap = generateStatusBarBitmap(context, bytesPerSec, forcedUnit)
        return IconCompat.createWithBitmap(bitmap)
    }
}
