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
 * Speed Paint: Color.WHITE, ANTI_ALIAS_FLAG only, TextSize=68px, TextAlign=CENTER, sans-serif-condensed BOLD
 * Unit Paint: Color.WHITE, ANTI_ALIAS_FLAG only, TextSize=36px, TextAlign=CENTER, Typeface.DEFAULT_BOLD
 * Safe speed width: 88px (horizontal clipping safeguard for 3-digit values)
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
        private const val DEFAULT_SPEED_TEXT_SIZE = 68f
        private const val DEFAULT_UNIT_TEXT_SIZE = 36f
        private const val SAFE_SPEED_WIDTH = 88f
    }

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

    private var lastDensityBefore = 0
    private var lastDensityAfter = 0

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

        // Horizontal-fit safeguard: measure text width at default 68px size
        speedPaint.textSize = DEFAULT_SPEED_TEXT_SIZE
        val measuredWidth = speedPaint.measureText(speedValue)
        if (measuredWidth > SAFE_SPEED_WIDTH) {
            val fittedSize = DEFAULT_SPEED_TEXT_SIZE * (SAFE_SPEED_WIDTH / measuredWidth)
            speedPaint.textSize = fittedSize
        }

        // Top line: Speed number at reference baseline x=48, y=52
        canvas.drawText(speedValue, SPEED_BASELINE_X, SPEED_BASELINE_Y, speedPaint)

        // Reset speedPaint textSize back to default
        speedPaint.textSize = DEFAULT_SPEED_TEXT_SIZE

        // Bottom line: Unit at reference baseline x=48, y=95
        canvas.drawText(formattedUnit, UNIT_BASELINE_X, UNIT_BASELINE_Y, unitPaint)

        // Density isolation experiment:
        // Switch bitmap density metadata from default inherited device density to Bitmap.DENSITY_NONE (0)
        // while leaving all 96x96 pixel dimensions and pixel values completely unchanged.
        val densityBefore = bitmap.density
        bitmap.density = Bitmap.DENSITY_NONE
        val densityAfter = bitmap.density

        lastDensityBefore = densityBefore
        lastDensityAfter = densityAfter

        return bitmap
    }

    /**
     * Exposes native android.graphics.drawable.Icon created directly from the 96x96 ARGB_8888 bitmap.
     */
    @Synchronized
    fun generateSpeedIcon(speedValue: String, speedUnit: String): Icon {
        val bitmap = generateSpeedBitmap(speedValue, speedUnit)
        val icon = Icon.createWithBitmap(bitmap)
        val iconType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) "${icon.type}" else "TYPE_BITMAP"

        // Diagnostic immediately before Icon return logging actual width, height, density, and values
        val now = System.currentTimeMillis()
        if (speedValue != lastLoggedDiagnosticValue || speedUnit != lastLoggedDiagnosticUnit || (now - lastDiagnosticLogTimestamp) > 30000L) {
            lastLoggedDiagnosticValue = speedValue
            lastLoggedDiagnosticUnit = speedUnit
            lastDiagnosticLogTimestamp = now

            // Inspect actual bitmap alpha pixels (no threshold cleanup applied)
            val pixels = IntArray(BASE_SIZE * BASE_SIZE)
            bitmap.getPixels(pixels, 0, BASE_SIZE, 0, 0, BASE_SIZE, BASE_SIZE)

            var minAlpha = 255
            var maxAlpha = 0
            var countSemiTransparentAlpha = 0
            var countOpaqueWhite = 0
            var countOtherOpaque = 0
            var minX = BASE_SIZE
            var maxX = -1
            var minY = BASE_SIZE
            var maxY = -1

            for (y in 0 until BASE_SIZE) {
                for (x in 0 until BASE_SIZE) {
                    val pixel = pixels[y * BASE_SIZE + x]
                    val a = (pixel ushr 24) and 0xFF
                    if (a > 0) {
                        if (a < minAlpha) minAlpha = a
                        if (a > maxAlpha) maxAlpha = a
                        if (a in 1..254) {
                            countSemiTransparentAlpha++
                        } else if (a == 255) {
                            val r = (pixel ushr 16) and 0xFF
                            val g = (pixel ushr 8) and 0xFF
                            val b = pixel and 0xFF
                            if (r == 255 && g == 255 && b == 255) {
                                countOpaqueWhite++
                            } else {
                                countOtherOpaque++
                            }
                        }
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            if (maxAlpha == 0) minAlpha = 0

            val hasPixelsOutsideGlyphBounds = minX < 2 || maxX > 93 || minY < 0 || maxY > 95
            val glyphBoundsStr = if (maxX >= minX && maxY >= minY) "x=[$minX..$maxX],y=[$minY..$maxY]" else "none"

            LogKeeper.log(
                context,
                "IconDiagnostics",
                "IconCreation -> bmp=${bitmap.width}x${bitmap.height}, densityBefore=$lastDensityBefore, densityAfter=${bitmap.density}, iconType=$iconType, resized=false, val='$speedValue', unit='$speedUnit', alphaCleanup=NONE, minAlpha=$minAlpha, maxAlpha=$maxAlpha, semiTransparentAlphaCount=$countSemiTransparentAlpha, opaqueWhiteCount=$countOpaqueWhite, otherOpaqueCount=$countOtherOpaque, glyphBounds=$glyphBoundsStr, pixelsOutsideGlyphRegion=$hasPixelsOutsideGlyphBounds"
            )
        }

        return icon
    }

    fun onTrimMemory() {
        // Lightweight paints only, no retained persistent heap buffers
    }
}




