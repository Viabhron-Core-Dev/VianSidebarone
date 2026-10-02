package com.example.feature.system_hub

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class SelectionShape(val displayName: String) {
    RECTANGLE("Rectangle"),
    SQUARE("Square"),
    CIRCLE("Circle"),
    CUSTOM("Custom")
}

data class NormalizedPoint(
    val x: Float,
    val y: Float
)

data class NormalizedCropBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

object ScannerSelectionHelper {

    /**
     * Calculates normalized crop bounds from screen pixel coordinates and selected shape.
     */
    fun calculateBounds(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        canvasWidth: Float,
        canvasHeight: Float,
        shape: SelectionShape
    ): NormalizedCropBounds {
        if (canvasWidth <= 0f || canvasHeight <= 0f) {
            return NormalizedCropBounds(0f, 0f, 1f, 1f)
        }

        val rawLeft = min(startX, endX)
        val rawTop = min(startY, endY)
        val rawRight = max(startX, endX)
        val rawBottom = max(startY, endY)

        val (adjLeft, adjTop, adjRight, adjBottom) = when (shape) {
            SelectionShape.RECTANGLE, SelectionShape.CUSTOM -> {
                listOf(rawLeft, rawTop, rawRight, rawBottom)
            }
            SelectionShape.SQUARE, SelectionShape.CIRCLE -> {
                val w = kotlin.math.abs(endX - startX)
                val h = kotlin.math.abs(endY - startY)
                val side = max(w, h).coerceAtLeast(10f)

                val maxSideX = if (endX >= startX) (canvasWidth - startX) else startX
                val maxSideY = if (endY >= startY) (canvasHeight - startY) else startY
                val finalSide = min(side, min(maxSideX, maxSideY)).coerceAtLeast(10f)

                val l = if (endX >= startX) startX else (startX - finalSide)
                val t = if (endY >= startY) startY else (startY - finalSide)
                val r = l + finalSide
                val b = t + finalSide

                listOf(l, t, r, b)
            }
        }

        return NormalizedCropBounds(
            left = (adjLeft / canvasWidth).coerceIn(0f, 1f),
            top = (adjTop / canvasHeight).coerceIn(0f, 1f),
            right = (adjRight / canvasWidth).coerceIn(0f, 1f),
            bottom = (adjBottom / canvasHeight).coerceIn(0f, 1f)
        )
    }

    /**
     * Extracts and crops the selected region from originalBitmap.
     * In CIRCLE mode, pixels outside the circular area are masked transparent.
     * In CUSTOM mode, pixels outside the polygon are masked transparent.
     */
    fun createCroppedBitmap(
        originalBitmap: Bitmap,
        bounds: NormalizedCropBounds,
        shape: SelectionShape,
        customVertices: List<NormalizedPoint>? = null
    ): Bitmap {
        val bw = originalBitmap.width
        val bh = originalBitmap.height

        if (shape == SelectionShape.CUSTOM && customVertices != null && customVertices.size >= 3) {
            val minX = (customVertices.minOf { it.x } * bw).roundToInt().coerceIn(0, bw - 1)
            val minY = (customVertices.minOf { it.y } * bh).roundToInt().coerceIn(0, bh - 1)
            val maxX = (customVertices.maxOf { it.x } * bw).roundToInt().coerceIn(minX + 1, bw)
            val maxY = (customVertices.maxOf { it.y } * bh).roundToInt().coerceIn(minY + 1, bh)

            val w = (maxX - minX).coerceAtLeast(1)
            val h = (maxY - minY).coerceAtLeast(1)

            val rectBitmap = Bitmap.createBitmap(originalBitmap, minX, minY, w, h)
            val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            val path = android.graphics.Path().apply {
                val first = customVertices[0]
                moveTo((first.x * bw) - minX, (first.y * bh) - minY)
                for (i in 1 until customVertices.size) {
                    val v = customVertices[i]
                    lineTo((v.x * bw) - minX, (v.y * bh) - minY)
                }
                close()
            }

            canvas.drawPath(path, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(rectBitmap, 0f, 0f, paint)

            if (rectBitmap != originalBitmap) {
                rectBitmap.recycle()
            }
            return output
        }

        val x = (bounds.left * bw).roundToInt().coerceIn(0, bw - 1)
        val y = (bounds.top * bh).roundToInt().coerceIn(0, bh - 1)
        val w = ((bounds.right - bounds.left) * bw).roundToInt().coerceIn(1, bw - x)
        val h = ((bounds.bottom - bounds.top) * bh).roundToInt().coerceIn(1, bh - y)

        val rectBitmap = Bitmap.createBitmap(originalBitmap, x, y, w, h)

        return if (shape == SelectionShape.CIRCLE) {
            val size = min(w, h).coerceAtLeast(1)
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val radius = size / 2f
            canvas.drawCircle(radius, radius, radius, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            val srcRect = Rect(
                (w - size) / 2,
                (h - size) / 2,
                (w - size) / 2 + size,
                (h - size) / 2 + size
            )
            val dstRect = Rect(0, 0, size, size)
            canvas.drawBitmap(rectBitmap, srcRect, dstRect, paint)
            if (rectBitmap != originalBitmap) {
                rectBitmap.recycle()
            }
            output
        } else {
            rectBitmap
        }
    }

    /**
     * Shares the cropped bitmap via standard Android intent.
     */
    fun shareBitmap(context: Context, bitmap: Bitmap) {
        try {
            val shareFile = File(context.cacheDir, "scan_share_${System.currentTimeMillis()}.png")
            FileOutputStream(shareFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", shareFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Scanned Selection"))
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to share: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
