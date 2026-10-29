package com.example.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * SquircleHelper provides presentation-layer squircle (superellipse) clipping
 * for PWA icons, website/link favicons, custom user icons, and image-based elements.
 *
 * It uses the standard Android launcher squircle cubic Bezier curvature,
 * matching modern Android M3 adaptive icon ergonomics without modifying
 * underlying raw source files on disk.
 */
object SquircleHelper {

    /**
     * Builds an exact Android squircle Path for the given bounding dimensions.
     */
    fun createSquirclePath(width: Float, height: Float): Path {
        val path = Path()
        val w = width
        val h = height

        path.moveTo(0.5f * w, 0f)
        path.cubicTo(0.1f * w, 0f, 0f, 0.1f * h, 0f, 0.5f * h)
        path.cubicTo(0f, 0.9f * h, 0.1f * w, 1f * h, 0.5f * w, 1f * h)
        path.cubicTo(0.9f * w, 1f * h, 1f * w, 0.9f * h, 1f * w, 0.5f * h)
        path.cubicTo(1f * w, 0.1f * h, 0.9f * w, 0f, 0.5f * w, 0f)
        path.close()

        return path
    }

    /**
     * Clips the provided [source] bitmap into a smooth squircle shape.
     * Raw disk files remain untouched; this operates entirely in-memory at presentation time.
     */
    fun toSquircleBitmap(source: Bitmap): Bitmap {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) {
            return source
        }

        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val path = createSquirclePath(width.toFloat(), height.toFloat())

        // Step 1: Draw squircle mask
        canvas.drawPath(path, paint)

        // Step 2: Mask source image inside squircle
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, 0f, 0f, paint)
        paint.xfermode = null

        return output
    }

    /**
     * Converts a Drawable to a squircle-clipped BitmapDrawable.
     */
    fun toSquircleDrawable(context: Context, drawable: Drawable): Drawable {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val squircleBmp = toSquircleBitmap(drawable.bitmap)
            return BitmapDrawable(context.resources, squircleBmp)
        }
        val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
        val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        val squircleBmp = toSquircleBitmap(bmp)
        return BitmapDrawable(context.resources, squircleBmp)
    }
}
