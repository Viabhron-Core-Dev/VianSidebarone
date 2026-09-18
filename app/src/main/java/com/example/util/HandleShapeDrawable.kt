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
    LEFT, RIGHT, TOP, BOTTOM;

    companion object {
        fun fromString(str: String?): HandleEdge {
            if (str == null) return RIGHT
            return when (str.trim().lowercase()) {
                "right" -> RIGHT
                "left" -> LEFT
                "top" -> TOP
                "bottom" -> BOTTOM
                else -> try {
                    valueOf(str.uppercase())
                } catch (e: Exception) {
                    RIGHT
                }
            }
        }
    }
}

enum class HandleShape {
    SLANTED_BLOCK,
    ROUNDED_RECT,
    HALF_OVAL,
    TRIANGLE,
    RECTANGLE,
    PILL,
    LINE;

    companion object {
        fun fromString(str: String?): HandleShape {
            if (str == null) return SLANTED_BLOCK
            return when (str.trim().lowercase()) {
                "slanted_block", "slanted" -> SLANTED_BLOCK
                "half_oval", "oval" -> HALF_OVAL
                "rounded_rect", "rounded" -> ROUNDED_RECT
                "triangle" -> TRIANGLE
                "rectangle", "rect" -> RECTANGLE
                "pill" -> PILL
                "line" -> LINE
                else -> try {
                    valueOf(str.uppercase())
                } catch (e: Exception) {
                    SLANTED_BLOCK
                }
            }
        }
    }
}

/**
 * HandleShapeDrawable: Hardware-accelerated geometry renderer for trigger handles.
 * Dynamically renders custom shapes based on the edge alignment without bitmap allocations.
 * Fully aligned with the OG reference implementation.
 */
class HandleShapeDrawable(
    private var shape: HandleShape = HandleShape.SLANTED_BLOCK,
    private var edge: HandleEdge = HandleEdge.RIGHT,
    private var color: Int = Color.parseColor("#242962ff")
) : Drawable() {

    constructor(color: Int, shape: String, edge: String) : this(
        shape = HandleShape.fromString(shape),
        edge = HandleEdge.fromString(edge),
        color = color
    )

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
            HandleShape.SLANTED_BLOCK -> {
                val d = Math.min(w * 0.577f, h / 2f)
                val dw = Math.min(h * 0.577f, w / 2f)
                when (edge) {
                    HandleEdge.RIGHT -> {
                        path.moveTo(w, 0f)
                        path.lineTo(0f, d)
                        path.lineTo(0f, h - d)
                        path.lineTo(w, h)
                    }
                    HandleEdge.LEFT -> {
                        path.moveTo(0f, 0f)
                        path.lineTo(w, d)
                        path.lineTo(w, h - d)
                        path.lineTo(0f, h)
                    }
                    HandleEdge.BOTTOM -> {
                        path.moveTo(0f, h)
                        path.lineTo(dw, 0f)
                        path.lineTo(w - dw, 0f)
                        path.lineTo(w, h)
                    }
                    HandleEdge.TOP -> {
                        path.moveTo(0f, 0f)
                        path.lineTo(dw, h)
                        path.lineTo(w - dw, h)
                        path.lineTo(w, 0f)
                    }
                }
                path.close()
            }
            HandleShape.HALF_OVAL -> {
                when (edge) {
                    HandleEdge.RIGHT -> {
                        rectF.set(0f, 0f, w * 2, h)
                    }
                    HandleEdge.LEFT -> {
                        rectF.set(-w, 0f, w, h)
                    }
                    HandleEdge.BOTTOM -> {
                        rectF.set(0f, 0f, w, h * 2)
                    }
                    HandleEdge.TOP -> {
                        rectF.set(0f, -h, w, h)
                    }
                }
                path.addOval(rectF, Path.Direction.CW)
            }
            HandleShape.ROUNDED_RECT -> {
                val radius = Math.min(w, h) / 3f
                val radii = when (edge) {
                    HandleEdge.RIGHT -> floatArrayOf(
                        radius, radius,
                        0f, 0f,
                        0f, 0f,
                        radius, radius
                    )
                    HandleEdge.LEFT -> floatArrayOf(
                        0f, 0f,
                        radius, radius,
                        radius, radius,
                        0f, 0f
                    )
                    HandleEdge.BOTTOM -> floatArrayOf(
                        radius, radius,
                        radius, radius,
                        0f, 0f,
                        0f, 0f
                    )
                    HandleEdge.TOP -> floatArrayOf(
                        0f, 0f,
                        0f, 0f,
                        radius, radius,
                        radius, radius
                    )
                }
                path.addRoundRect(rectF, radii, Path.Direction.CW)
            }
            HandleShape.TRIANGLE -> {
                when (edge) {
                    HandleEdge.RIGHT -> {
                        path.moveTo(w, 0f)
                        path.lineTo(0f, h / 2f)
                        path.lineTo(w, h)
                    }
                    HandleEdge.LEFT -> {
                        path.moveTo(0f, 0f)
                        path.lineTo(w, h / 2f)
                        path.lineTo(0f, h)
                    }
                    HandleEdge.BOTTOM -> {
                        path.moveTo(0f, h)
                        path.lineTo(w / 2f, 0f)
                        path.lineTo(w, h)
                    }
                    HandleEdge.TOP -> {
                        path.moveTo(0f, 0f)
                        path.lineTo(w / 2f, h)
                        path.lineTo(w, 0f)
                    }
                }
                path.close()
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
            HandleShape.RECTANGLE -> {
                path.addRect(rectF, Path.Direction.CW)
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
