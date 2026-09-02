package com.example.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build

/**
 * DynamicSpeedIconGenerator: Generates high-fidelity, crisp dynamic speed icons
 * using a 96x96 baseline supersampled canvas with condensed bold typography.
 * Formats speed as two lines: prominent number on top, clear unit (B/s, kB/s, MB/s, GB/s) below.
 * Produces an immutable bitmap snapshot per generation to prevent asynchronous SystemUI corruption.
 */
class DynamicSpeedIconGenerator(private val context: Context) {

    companion object {
        private const val BASE_SIZE = 96
    }

    private val textBounds = Rect()

    // Reusable Paint instances with high-quality anti-aliasing and subpixel text rendering
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = -0.02f
    }

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = -0.02f
    }

    private var lastLoggedDiagnosticValue = ""
    private var lastLoggedDiagnosticUnit = ""
    private var lastDiagnosticLogTimestamp = 0L

    /**
     * Renders numeric speed and unit (e.g. "0" and "kB/s", "531" and "kB/s", or "1.2" and "MB/s")
     * onto a raw 96x96 ARGB_8888 bitmap snapshot matching the reference status-bar layout and glyph scale.
     */
    @Synchronized
    fun generateSpeedBitmap(speedValue: String, speedUnit: String): Bitmap {
        val bitmap = Bitmap.createBitmap(BASE_SIZE, BASE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val w = BASE_SIZE.toFloat()
        val h = BASE_SIZE.toFloat()
        val cx = w / 2f

        // Normalize unit string to standard status-bar unit notation
        val formattedUnit = when (speedUnit) {
            "K", "k", "KB", "KB/s", "kb/s" -> "kB/s"
            "M", "m", "MB" -> "MB/s"
            "G", "g", "GB" -> "GB/s"
            "B", "b" -> "B/s"
            else -> if (speedUnit.endsWith("/s", ignoreCase = true)) speedUnit else "$speedUnit/s"
        }

        // --- TOP LINE: Speed Number ---
        // Calibrate baseline text sizes on 96x96 canvas (reference uses ~65px)
        val initialSpeedTextSize = when {
            speedValue.length >= 4 -> 50f
            speedValue.length == 3 -> 58f
            speedValue.length == 2 -> 64f
            else -> 65f
        }
        speedPaint.textSize = initialSpeedTextSize

        // Horizontal boundary constraint for number
        val maxNumWidth = w - 4f
        var measuredNumWidth = speedPaint.measureText(speedValue)
        if (measuredNumWidth > maxNumWidth && measuredNumWidth > 0f) {
            speedPaint.textSize *= (maxNumWidth / measuredNumWidth)
            measuredNumWidth = speedPaint.measureText(speedValue)
        }

        // Reference baseline for 65px text on 96x96 canvas is around y=52
        speedPaint.getTextBounds(speedValue, 0, speedValue.length, textBounds)
        val speedBaseline = if (!textBounds.isEmpty) {
            (2f - textBounds.top).coerceIn(48f, 54f)
        } else {
            52f
        }
        canvas.drawText(speedValue, cx, speedBaseline, speedPaint)

        // --- BOTTOM LINE: Unit (kB/s, MB/s, GB/s, B/s) ---
        // Reference unit baseline text size is 40px on 96x96 canvas
        val initialUnitTextSize = 40f
        unitPaint.textSize = initialUnitTextSize

        val maxUnitWidth = w - 4f
        var measuredUnitWidth = unitPaint.measureText(formattedUnit)
        if (measuredUnitWidth > maxUnitWidth && measuredUnitWidth > 0f) {
            unitPaint.textSize *= (maxUnitWidth / measuredUnitWidth)
            measuredUnitWidth = unitPaint.measureText(formattedUnit)
        }

        // Reference baseline for 40px unit text on 96x96 canvas is around y=94..95
        unitPaint.getTextBounds(formattedUnit, 0, formattedUnit.length, textBounds)
        val unitBaseline = if (!textBounds.isEmpty) {
            (h - 1f - textBounds.bottom).coerceIn(90f, 95f)
        } else {
            94f
        }
        canvas.drawText(formattedUnit, cx, unitBaseline, unitPaint)

        return bitmap
    }

    /**
     * Exposes native android.graphics.drawable.Icon created directly from the 96x96 ARGB_8888 bitmap.
     * Zero IconCompat wrapping, zero application-side resizing, zero density manipulation.
     */
    @Synchronized
    fun generateSpeedIcon(speedValue: String, speedUnit: String): Icon {
        val bitmap = generateSpeedBitmap(speedValue, speedUnit)
        val icon = Icon.createWithBitmap(bitmap)

        // Diagnostics telemetry (throttled)
        val now = System.currentTimeMillis()
        if (speedValue != lastLoggedDiagnosticValue || speedUnit != lastLoggedDiagnosticUnit || (now - lastDiagnosticLogTimestamp) > 30000L) {
            lastLoggedDiagnosticValue = speedValue
            lastLoggedDiagnosticUnit = speedUnit
            lastDiagnosticLogTimestamp = now
            val iconTypeStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) "${icon.type}" else "TYPE_BITMAP"
            LogKeeper.log(
                context,
                "IconDiagnostics",
                "IconDelivery -> bmp=${bitmap.width}x${bitmap.height}, bmpDensity=${bitmap.density}, devDpi=${context.resources.displayMetrics.densityDpi}, iconType=$iconTypeStr, noIntermediateResize=true, iconCompatInvolved=false, val='$speedValue', unit='$speedUnit'"
            )
        }

        return icon
    }

    fun onTrimMemory() {
        // Lightweight paints and metrics only, no retained persistent heap buffers
    }
}




