package com.example.feature.sidebar
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

class BubbleDrawable(private val innerBitmap: Bitmap?) : Drawable() {
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40FFFFFF") // Transparent bubble
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    
    override fun draw(canvas: Canvas) {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterX() // use square
        val radius = Math.min(bounds.width(), bounds.height()) / 2f
        
        canvas.drawCircle(cx, cy, radius, bubblePaint)
        canvas.drawCircle(cx, cy, radius - 1.5f, borderPaint)
        
        if (innerBitmap != null) {
            val innerSize = radius * 1.2f
            val left = cx - innerSize / 2f
            val top = cy - innerSize / 2f
            val destRect = Rect(left.toInt(), top.toInt(), (left + innerSize).toInt(), (top + innerSize).toInt())
            canvas.drawBitmap(innerBitmap, null, destRect, null)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
