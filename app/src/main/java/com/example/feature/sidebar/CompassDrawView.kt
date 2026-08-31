package com.example.feature.sidebar

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class CompassDrawView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var azimuth: Float = 0f

    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        style = Paint.Style.STROKE
    }
    private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22FFFFFF")
        style = Paint.Style.STROKE
    }
    private val northNeedlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252")
        style = Paint.Style.FILL
    }
    private val southNeedlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        style = Paint.Style.FILL
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val subTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFFFFF")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaintCardinal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val textPaintIntercardinal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0BEC5")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val centerPivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BB86FC")
        style = Paint.Style.FILL
    }
    private val centerPivotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
    }

    private val needlePathNorth = Path()
    private val needlePathNorthBevel = Path()
    private val needlePathSouth = Path()
    private val needlePathSouthBevel = Path()

    fun setAzimuth(azimuth: Float) {
        this.azimuth = azimuth
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = Math.min(cx, cy)
        if (maxRadius <= 10f) return

        val padding = maxRadius * 0.06f
        val radius = maxRadius - padding

        // Dynamic stroke and text sizes strictly proportional to radius (shrink not crop)
        outerRingPaint.strokeWidth = Math.max(2f, radius * 0.02f)
        innerRingPaint.strokeWidth = Math.max(1f, radius * 0.01f)
        centerPivotRingPaint.strokeWidth = Math.max(1.5f, radius * 0.015f)

        // Draw Outer Base Ring & Inner Ring
        canvas.drawCircle(cx, cy, radius, outerRingPaint)
        canvas.drawCircle(cx, cy, radius * 0.72f, innerRingPaint)

        canvas.save()
        // Rotate dial with azimuth
        canvas.rotate(-azimuth, cx, cy)

        val cardinalTextSize = Math.max(10f, radius * 0.12f)
        val interTextSize = Math.max(8f, radius * 0.075f)

        textPaintCardinal.textSize = cardinalTextSize
        textPaintIntercardinal.textSize = interTextSize

        // Draw Ticks & Directions (N, NE, E, SE, S, SW, W, NW)
        for (degree in 0 until 360 step 5) {
            canvas.save()
            canvas.rotate(degree.toFloat(), cx, cy)

            when {
                degree % 90 == 0 -> {
                    // Cardinal: 0 -> N, 90 -> E, 180 -> S, 270 -> W
                    tickPaint.strokeWidth = Math.max(2f, radius * 0.025f)
                    tickPaint.color = if (degree == 0) Color.parseColor("#FF5252") else Color.WHITE
                    val tickLen = radius * 0.10f
                    canvas.drawLine(cx, cy - radius, cx, cy - radius + tickLen, tickPaint)

                    val label = when (degree) {
                        0 -> "N"
                        90 -> "E"
                        180 -> "S"
                        270 -> "W"
                        else -> ""
                    }
                    textPaintCardinal.color = if (degree == 0) Color.parseColor("#FF5252") else Color.WHITE
                    val textY = cy - radius + tickLen + cardinalTextSize * 0.9f
                    canvas.drawText(label, cx, textY, textPaintCardinal)
                }
                degree % 45 == 0 -> {
                    // Intercardinal: 45 -> NE, 135 -> SE, 225 -> SW, 315 -> NW
                    tickPaint.strokeWidth = Math.max(1.5f, radius * 0.018f)
                    tickPaint.color = Color.parseColor("#B0BEC5")
                    val tickLen = radius * 0.08f
                    canvas.drawLine(cx, cy - radius, cx, cy - radius + tickLen, tickPaint)

                    val label = when (degree) {
                        45 -> "NE"
                        135 -> "SE"
                        225 -> "SW"
                        315 -> "NW"
                        else -> ""
                    }
                    val textY = cy - radius + tickLen + interTextSize * 0.9f
                    canvas.drawText(label, cx, textY, textPaintIntercardinal)
                }
                degree % 15 == 0 -> {
                    // Medium tick
                    tickPaint.strokeWidth = Math.max(1.2f, radius * 0.012f)
                    tickPaint.color = Color.parseColor("#80FFFFFF")
                    val tickLen = radius * 0.05f
                    canvas.drawLine(cx, cy - radius, cx, cy - radius + tickLen, tickPaint)
                }
                else -> {
                    // Minor tick (every 5 deg)
                    subTickPaint.strokeWidth = Math.max(1f, radius * 0.008f)
                    val tickLen = radius * 0.03f
                    canvas.drawLine(cx, cy - radius, cx, cy - radius + tickLen, subTickPaint)
                }
            }
            canvas.restore()
        }

        // Draw Stylized Needle (North = Red, South = Silver)
        val needleWidth = Math.max(4f, radius * 0.085f)
        val needleLength = radius * 0.65f

        needlePathNorth.reset()
        needlePathNorth.moveTo(cx, cy - needleLength)
        needlePathNorth.lineTo(cx + needleWidth, cy)
        needlePathNorth.lineTo(cx, cy - needleWidth * 0.4f)
        needlePathNorth.close()
        northNeedlePaint.color = Color.parseColor("#FF5252")
        canvas.drawPath(needlePathNorth, northNeedlePaint)

        needlePathNorthBevel.reset()
        needlePathNorthBevel.moveTo(cx, cy - needleLength)
        needlePathNorthBevel.lineTo(cx - needleWidth, cy)
        needlePathNorthBevel.lineTo(cx, cy - needleWidth * 0.4f)
        needlePathNorthBevel.close()
        northNeedlePaint.color = Color.parseColor("#D32F2F")
        canvas.drawPath(needlePathNorthBevel, northNeedlePaint)

        needlePathSouth.reset()
        needlePathSouth.moveTo(cx, cy + needleLength)
        needlePathSouth.lineTo(cx + needleWidth, cy)
        needlePathSouth.lineTo(cx, cy + needleWidth * 0.4f)
        needlePathSouth.close()
        southNeedlePaint.color = Color.parseColor("#ECEFF1")
        canvas.drawPath(needlePathSouth, southNeedlePaint)

        needlePathSouthBevel.reset()
        needlePathSouthBevel.moveTo(cx, cy + needleLength)
        needlePathSouthBevel.lineTo(cx - needleWidth, cy)
        needlePathSouthBevel.lineTo(cx, cy + needleWidth * 0.4f)
        needlePathSouthBevel.close()
        southNeedlePaint.color = Color.parseColor("#B0BEC5")
        canvas.drawPath(needlePathSouthBevel, southNeedlePaint)

        canvas.restore()

        // Center Pivot
        val centerDotRadius = Math.max(3f, radius * 0.05f)
        canvas.drawCircle(cx, cy, centerDotRadius, centerPivotPaint)
        canvas.drawCircle(cx, cy, centerDotRadius, centerPivotRingPaint)
    }
}

