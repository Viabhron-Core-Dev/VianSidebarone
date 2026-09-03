package com.example.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build

/**
 * DynamicSpeedIconGenerator: Generates dynamic status-bar speed icons matching
 * the NetSpeed Indicator reference implementation geometry and Paint configuration.
 *
 * Controlled Experiment: 48x48 ARGB_8888 bitmap directly matching the 24dp notification-icon
 * target at density=2.0 (320dpi) on Redmi A5 to eliminate downsampling blur in the status bar.
 *
 * Baseline: 48x48 ARGB_8888 bitmap
 * Explicit density: context.resources.displayMetrics.densityDpi (320 dpi)
 * Speed Paint: Color.WHITE, ANTI_ALIAS_FLAG only, TextSize=34px, TextAlign=CENTER, sans-serif-condensed BOLD
 * Unit Paint: Color.WHITE, ANTI_ALIAS_FLAG only, TextSize=18px, TextAlign=CENTER, Typeface.DEFAULT_BOLD
 * Safe speed width: 44px (guaranteeing >= 2px horizontal margin on 48px canvas)
 * Speed baseline: x=24, y=26
 * Unit baseline: x=24, y=47
 * Clears bitmap with PorterDuff.Mode.CLEAR before each render.
 */
class DynamicSpeedIconGenerator(private val context: Context) {

    companion object {
        private const val BASE_SIZE = 48
        private const val SPEED_BASELINE_X = 24f
        private const val SPEED_BASELINE_Y = 26f
        private const val UNIT_BASELINE_X = 24f
        private const val UNIT_BASELINE_Y = 47f
        private const val DEFAULT_SPEED_TEXT_SIZE = 34f
        private const val DEFAULT_UNIT_TEXT_SIZE = 18f
        private const val SAFE_SPEED_WIDTH = 44f
    }

    private val targetDensityDpi = context.resources.displayMetrics.densityDpi

    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
        isSubpixelText = false
        isFilterBitmap = false
        isDither = false
        letterSpacing = 0f
        textSize = DEFAULT_SPEED_TEXT_SIZE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        clearShadowLayer()
    }

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
        isSubpixelText = false
        isFilterBitmap = false
        isDither = false
        letterSpacing = 0f
        textSize = DEFAULT_UNIT_TEXT_SIZE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        clearShadowLayer()
    }

    private var lastLoggedDiagnosticValue = ""
    private var lastLoggedDiagnosticUnit = ""
    private var lastDiagnosticLogTimestamp = 0L

    /**
     * Renders numeric speed and unit onto a 48x48 ARGB_8888 bitmap with explicit device density.
     */
    @Synchronized
    fun generateSpeedBitmap(speedValue: String, speedUnit: String): Bitmap {
        val bitmap = Bitmap.createBitmap(BASE_SIZE, BASE_SIZE, Bitmap.Config.ARGB_8888).apply {
            density = targetDensityDpi
        }
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // Normalize unit string to standard status-bar unit notation
        val formattedUnit = when (speedUnit) {
            "K", "k", "KB", "KB/s", "kb/s" -> "kB/s"
            "M", "m", "MB" -> "MB/s"
            "G", "g", "GB" -> "GB/s"
            "B", "b" -> "B/s"
            else -> if (speedUnit.endsWith("/s", ignoreCase = true)) speedUnit else "$speedUnit/s"
        }

        // Horizontal-fit safeguard: measure text width at default 34px size
        speedPaint.textSize = DEFAULT_SPEED_TEXT_SIZE
        val measuredWidth = speedPaint.measureText(speedValue)
        if (measuredWidth > SAFE_SPEED_WIDTH) {
            val fittedSize = DEFAULT_SPEED_TEXT_SIZE * (SAFE_SPEED_WIDTH / measuredWidth)
            speedPaint.textSize = fittedSize
        }

        // Top line: Speed number at baseline x=24, y=26
        canvas.drawText(speedValue, SPEED_BASELINE_X, SPEED_BASELINE_Y, speedPaint)

        // Reset speedPaint textSize back to default
        speedPaint.textSize = DEFAULT_SPEED_TEXT_SIZE

        // Bottom line: Unit at baseline x=24, y=47
        canvas.drawText(formattedUnit, UNIT_BASELINE_X, UNIT_BASELINE_Y, unitPaint)

        return bitmap
    }

    /**
     * Exposes native android.graphics.drawable.Icon created directly from the 48x48 ARGB_8888 bitmap.
     */
    @Synchronized
    fun generateSpeedIcon(speedValue: String, speedUnit: String): Icon {
        val bitmap = generateSpeedBitmap(speedValue, speedUnit)

        // Diagnostic immediately before Icon.createWithBitmap() logging actual width, height, density, and values
        val now = System.currentTimeMillis()
        if (speedValue != lastLoggedDiagnosticValue || speedUnit != lastLoggedDiagnosticUnit || (now - lastDiagnosticLogTimestamp) > 30000L) {
            lastLoggedDiagnosticValue = speedValue
            lastLoggedDiagnosticUnit = speedUnit
            lastDiagnosticLogTimestamp = now

            val measuredSpeedWidth = speedPaint.measureText(speedValue)
            val measuredUnitWidth = unitPaint.measureText(
                when (speedUnit) {
                    "K", "k", "KB", "KB/s", "kb/s" -> "kB/s"
                    "M", "m", "MB" -> "MB/s"
                    "G", "g", "GB" -> "GB/s"
                    "B", "b" -> "B/s"
                    else -> if (speedUnit.endsWith("/s", ignoreCase = true)) speedUnit else "$speedUnit/s"
                }
            )

            // Inspect actual bitmap pixels to find nontransparent glyph bounds and edge margins
            val pixels = IntArray(BASE_SIZE * BASE_SIZE)
            bitmap.getPixels(pixels, 0, BASE_SIZE, 0, 0, BASE_SIZE, BASE_SIZE)

            var minAlpha = 255
            var maxAlpha = 0
            var countNonTransparent = 0
            var minX = BASE_SIZE
            var maxX = -1
            var minY = BASE_SIZE
            var maxY = -1

            for (y in 0 until BASE_SIZE) {
                for (x in 0 until BASE_SIZE) {
                    val pixel = pixels[y * BASE_SIZE + x]
                    val a = (pixel ushr 24) and 0xFF
                    if (a > 0) {
                        countNonTransparent++
                        if (a < minAlpha) minAlpha = a
                        if (a > maxAlpha) maxAlpha = a
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            if (maxAlpha == 0) minAlpha = 0

            val leftMargin = if (maxX >= minX) minX else -1
            val rightMargin = if (maxX >= minX) (BASE_SIZE - 1 - maxX) else -1
            val topMargin = if (maxY >= minY) minY else -1
            val bottomMargin = if (maxY >= minY) (BASE_SIZE - 1 - maxY) else -1

            val touchesAnyEdge = (minX == 0 || maxX == BASE_SIZE - 1 || minY == 0 || maxY == BASE_SIZE - 1)
            val glyphBoundsStr = if (maxX >= minX && maxY >= minY) "x=[$minX..$maxX],y=[$minY..$maxY]" else "none"

            LogKeeper.log(
                context,
                "IconDiagnostics",
                "IconCreation -> bmp=${bitmap.width}x${bitmap.height}, density=${bitmap.density}, " +
                "speedTextSize=${DEFAULT_SPEED_TEXT_SIZE}, unitTextSize=${DEFAULT_UNIT_TEXT_SIZE}, " +
                "measuredSpeedWidth=$measuredSpeedWidth, measuredUnitWidth=$measuredUnitWidth, " +
                "val='$speedValue', unit='$speedUnit', minAlpha=$minAlpha, maxAlpha=$maxAlpha, " +
                "glyphBounds=$glyphBoundsStr, margins=[L=$leftMargin, R=$rightMargin, T=$topMargin, B=$bottomMargin], " +
                "touchesEdge=$touchesAnyEdge"
            )
        }

        val icon = Icon.createWithBitmap(bitmap)
        return icon
    }

    fun onTrimMemory() {
        // Lightweight paints only, no retained persistent heap buffers
    }
}




