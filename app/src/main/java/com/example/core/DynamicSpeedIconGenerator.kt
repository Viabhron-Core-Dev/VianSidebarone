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
 * Baseline: 96x96 ARGB_8888 bitmap
 * Speed Paint: Color.WHITE, AntiAlias=true, TextSize=68px, TextAlign=CENTER, sans-serif-condensed BOLD
 * Unit Paint: Color.WHITE, AntiAlias=true, TextSize=36px, TextAlign=CENTER, Typeface.DEFAULT_BOLD
 * Speed baseline: x=48, y=52
 * Unit baseline: x=48, y=95
 * Clears bitmap with PorterDuff.Mode.CLEAR before each render.
 */
class DynamicSpeedIconGenerator(private val context: Context) {

    companion object {
        private const val BASE_SIZE = 96
        private const val SPEED_BASELINE_X = 48f
        private const val SPEED_BASELINE_Y = 52f
        private const val UNIT_BASELINE_X = 48f
        private const val UNIT_BASELINE_Y = 95f
    }

    private val speedPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        textSize = 68f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    private val unitPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        textSize = 36f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private var lastLoggedDiagnosticValue = ""
    private var lastLoggedDiagnosticUnit = ""
    private var lastDiagnosticLogTimestamp = 0L

    /**
     * Renders numeric speed and unit onto a 96x96 ARGB_8888 bitmap matching the reference geometry.
     */
    @Synchronized
    fun generateSpeedBitmap(speedValue: String, speedUnit: String): Bitmap {
        val bitmap = Bitmap.createBitmap(BASE_SIZE, BASE_SIZE, Bitmap.Config.ARGB_8888)
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

        // Top line: Speed number at reference baseline x=48, y=52
        canvas.drawText(speedValue, SPEED_BASELINE_X, SPEED_BASELINE_Y, speedPaint)

        // Bottom line: Unit at reference baseline x=48, y=95
        canvas.drawText(formattedUnit, UNIT_BASELINE_X, UNIT_BASELINE_Y, unitPaint)

        return bitmap
    }

    /**
     * Exposes native android.graphics.drawable.Icon created directly from the 96x96 ARGB_8888 bitmap.
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
            LogKeeper.log(
                context,
                "IconDiagnostics",
                "IconCreation -> bmpWidth=${bitmap.width}, bmpHeight=${bitmap.height}, bmpDensity=${bitmap.density}, speedTextSize=68px, unitTextSize=36px, speedBaseline=(48,52), unitBaseline=(48,95), val='$speedValue', unit='$speedUnit'"
            )
        }

        val icon = Icon.createWithBitmap(bitmap)
        return icon
    }

    fun onTrimMemory() {
        // Lightweight paints only, no retained persistent heap buffers
    }
}




