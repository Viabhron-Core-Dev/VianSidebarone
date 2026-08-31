package com.example.util

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

class HandleShapeDrawable(
    private val color: Int,
    private val shape: String,
    private val edge: String
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@HandleShapeDrawable.color
        style = Paint.Style.FILL
    }
    private val path = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        path.reset()
        rect.set(0f, 0f, w, h)

        when (shape) {
            "triangle" -> {
                when (edge) {
                    "right" -> {
                        path.moveTo(w, 0f)
                        path.lineTo(0f, h / 2)
                        path.lineTo(w, h)
                    }
                    "left" -> {
                        path.moveTo(0f, 0f)
                        path.lineTo(w, h / 2)
                        path.lineTo(0f, h)
                    }
                    "bottom" -> {
                        path.moveTo(0f, h)
                        path.lineTo(w / 2, 0f)
                        path.lineTo(w, h)
                    }
                    else -> path.addRect(rect, Path.Direction.CW)
                }
                path.close()
            }
            "half_oval" -> {
                when (edge) {
                    "right" -> {
                        rect.set(0f, 0f, w * 2, h)
                    }
                    "left" -> {
                        rect.set(-w, 0f, w, h)
                    }
                    "bottom" -> {
                        rect.set(0f, 0f, w, h * 2)
                    }
                }
                path.addOval(rect, Path.Direction.CW)
            }
            "rounded_rect" -> {
                val radius = Math.min(w, h) / 4f
                val radii = when (edge) {
                    "right" -> floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
                    "left" -> floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
                    "bottom" -> floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
                    else -> floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
                }
                path.addRoundRect(rect, radii, Path.Direction.CW)
            }
            "slanted_block" -> {
                val d = Math.min(w * 0.577f, h / 2f)
                val dw = Math.min(h * 0.577f, w / 2f)
                when (edge) {
                    "right" -> {
                        path.moveTo(w, 0f)
                        path.lineTo(0f, d)
                        path.lineTo(0f, h - d)
                        path.lineTo(w, h)
                    }
                    "left" -> {
                        path.moveTo(0f, 0f)
                        path.lineTo(w, d)
                        path.lineTo(w, h - d)
                        path.lineTo(0f, h)
                    }
                    "bottom" -> {
                        path.moveTo(0f, h)
                        path.lineTo(dw, 0f)
                        path.lineTo(w - dw, 0f)
                        path.lineTo(w, h)
                    }
                    else -> path.addRect(rect, Path.Direction.CW)
                }
                path.close()
            }
            else -> { // "rectangle"
                path.addRect(rect, Path.Direction.CW)
            }
        }
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT", "android.graphics.PixelFormat"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
