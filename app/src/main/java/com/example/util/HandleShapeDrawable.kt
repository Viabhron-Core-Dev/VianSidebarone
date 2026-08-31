package com.example.util

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

enum class HandleEdge {
    LEFT, RIGHT, TOP, BOTTOM
}

enum class HandleShape {
    RECTANGLE,
    ROUNDED_RECT,
    HALF_OVAL,
    TRIANGLE,
    PILL,
    LINE
}

/**
 * HandleShapeDrawable: Hardware-accelerated geometry renderer for trigger handles.
 * Dynamically renders custom shapes based on the edge alignment without bitmap allocations.
 */
class HandleShapeDrawable(
    private var shape: HandleShape = HandleShape.ROUNDED_RECT,
    private var edge: HandleEdge = HandleEdge.LEFT,
    private var color: Int = Color.parseColor("#80000000")
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = this@HandleShapeDrawable.color
    }

    private val path = Path()
    private val rectF = RectF()

    fun updateConfig(newShape: HandleShape, newEdge: HandleEdge, newColor: Int) {
        if (this.shape != newShape || this.edge != newEdge || this.color != newColor) {
            this.shape = newShape
            this.edge = newEdge
            this.color = newColor
            paint.color = newColor
            invalidatePath()
            invalidateSelf()
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        invalidatePath()
    }

    private fun invalidatePath() {
        path.reset()
        val bounds = bounds
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0 || h <= 0) return

        rectF.set(0f, 0f, w, h)

        when (shape) {
            HandleShape.RECTANGLE -> {
                path.addRect(rectF, Path.Direction.CW)
            }
            HandleShape.ROUNDED_RECT -> {
                val radius = Math.min(w, h) / 3f
                val radii = when (edge) {
                    HandleEdge.LEFT -> floatArrayOf(
                        0f, 0f,
                        radius, radius,
                        radius, radius,
                        0f, 0f
                    )
                    HandleEdge.RIGHT -> floatArrayOf(
                        radius, radius,
                        0f, 0f,
                        0f, 0f,
                        radius, radius
                    )
                    HandleEdge.TOP -> floatArrayOf(
                        0f, 0f,
                        0f, 0f,
                        radius, radius,
                        radius, radius
                    )
                    HandleEdge.BOTTOM -> floatArrayOf(
                        radius, radius,
                        radius, radius,
                        0f, 0f,
                        0f, 0f
                    )
                }
                path.addRoundRect(rectF, radii, Path.Direction.CW)
            }
            HandleShape.HALF_OVAL -> {
                when (edge) {
                    HandleEdge.LEFT -> {
                        path.moveTo(0f, 0f)
                        path.cubicTo(w * 1.3f, 0f, w * 1.3f, h, 0f, h)
                        path.close()
                    }
                    HandleEdge.RIGHT -> {
                        path.moveTo(w, 0f)
                        path.cubicTo(-w * 0.3f, 0f, -w * 0.3f, h, w, h)
                        path.close()
                    }
                    HandleEdge.TOP -> {
                        path.moveTo(0f, 0f)
                        path.cubicTo(0f, h * 1.3f, w, h * 1.3f, w, 0f)
                        path.close()
                    }
                    HandleEdge.BOTTOM -> {
                        path.moveTo(0f, h)
                        path.cubicTo(0f, -h * 0.3f, w, -h * 0.3f, w, h)
                        path.close()
                    }
                }
            }
            HandleShape.TRIANGLE -> {
                when (edge) {
                    HandleEdge.LEFT -> {
                        path.moveTo(0f, 0f)
                        path.lineTo(w, h / 2f)
                        path.lineTo(0f, h)
                        path.close()
                    }
                    HandleEdge.RIGHT -> {
                        path.moveTo(w, 0f)
                        path.lineTo(0f, h / 2f)
                        path.lineTo(w, h)
                        path.close()
                    }
                    HandleEdge.TOP -> {
                        path.moveTo(0f, 0f)
                        path.lineTo(w / 2f, h)
                        path.lineTo(w, 0f)
                        path.close()
                    }
                    HandleEdge.BOTTOM -> {
                        path.moveTo(0f, h)
                        path.lineTo(w / 2f, 0f)
                        path.lineTo(w, h)
                        path.close()
                    }
                }
            }
            HandleShape.PILL -> {
                val radius = Math.min(w, h) / 2f
                path.addRoundRect(rectF, radius, radius, Path.Direction.CW)
            }
            HandleShape.LINE -> {
                val stroke = (Math.min(w, h) / 4f).coerceAtLeast(4f)
                val strokeRadii = floatArrayOf(
                    stroke, stroke, stroke, stroke,
                    stroke, stroke, stroke, stroke
                )
                path.addRoundRect(rectF, strokeRadii, Path.Direction.CW)
            }
        }
    }

    override fun draw(canvas: Canvas) {
        if (!path.isEmpty) {
            canvas.drawPath(path, paint)
        } else {
            canvas.drawRect(bounds, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
