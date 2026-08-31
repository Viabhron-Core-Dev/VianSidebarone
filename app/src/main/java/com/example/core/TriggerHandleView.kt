package com.example.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.example.util.HandleEdge
import com.example.util.HandleShapeDrawable

/**
 * TriggerHandleView: Floating touch-responsive edge overlay view.
 * Performs edge attachment, gesture recognition, and drag repositioning.
 */
@SuppressLint("ViewConstructor")
class TriggerHandleView(
    context: Context,
    private var config: HandleConfig,
    private val onGestureAction: (actionKey: String, handleConfig: HandleConfig) -> Unit
) : View(context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val shapeDrawable = HandleShapeDrawable(config.shape, config.edge, config.color)
    private var isAttached = false
    private var isDragging = false

    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    )

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (!isDragging) {
                onGestureAction(config.onTapAction, config)
                return true
            }
            return false
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!isDragging) {
                onGestureAction(config.onDoubleTapAction, config)
                return true
            }
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            if (config.onLongPressAction == HandleManager.ACTION_MOVE_HANDLE) {
                isDragging = true
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            } else {
                onGestureAction(config.onLongPressAction, config)
            }
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (isDragging || e1 == null) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y

            if (Math.abs(dx) > Math.abs(dy)) {
                if (dx > 50) {
                    onGestureAction(config.onSwipeRightAction, config)
                    return true
                } else if (dx < -50) {
                    onGestureAction(config.onSwipeLeftAction, config)
                    return true
                }
            } else {
                if (dy > 50) {
                    onGestureAction(config.onSwipeDownAction, config)
                    return true
                } else if (dy < -50) {
                    onGestureAction(config.onSwipeUpAction, config)
                    return true
                }
            }
            return false
        }
    })

    init {
        background = shapeDrawable
        updateDimensions()
    }

    fun updateConfig(newConfig: HandleConfig) {
        this.config = newConfig
        shapeDrawable.updateConfig(config.shape, config.edge, config.color)
        updateDimensions()
        if (isAttached) {
            windowManager.updateViewLayout(this, layoutParams)
        }
    }

    private fun updateDimensions() {
        val density = resources.displayMetrics.density
        val w = (config.widthDp * density).toInt()
        val h = (config.heightDp * density).toInt()

        layoutParams.width = w
        layoutParams.height = h

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        when (config.edge) {
            HandleEdge.LEFT -> {
                layoutParams.gravity = Gravity.START or Gravity.TOP
                layoutParams.x = 0
                layoutParams.y = ((screenH - h) * config.positionPercent).toInt()
            }
            HandleEdge.RIGHT -> {
                layoutParams.gravity = Gravity.END or Gravity.TOP
                layoutParams.x = 0
                layoutParams.y = ((screenH - h) * config.positionPercent).toInt()
            }
            HandleEdge.TOP -> {
                layoutParams.gravity = Gravity.TOP or Gravity.START
                layoutParams.y = 0
                layoutParams.x = ((screenW - w) * config.positionPercent).toInt()
            }
            HandleEdge.BOTTOM -> {
                layoutParams.gravity = Gravity.BOTTOM or Gravity.START
                layoutParams.y = 0
                layoutParams.x = ((screenW - w) * config.positionPercent).toInt()
            }
        }
    }

    fun attachToWindow() {
        if (!isAttached) {
            try {
                windowManager.addView(this, layoutParams)
                isAttached = true
            } catch (e: Exception) {
                LogKeeper.logError(context, "TriggerHandleView", "Failed to add handle view", e)
            }
        }
    }

    fun detachFromWindow() {
        if (isAttached) {
            try {
                windowManager.removeView(this)
                isAttached = false
            } catch (e: Exception) {
                LogKeeper.logError(context, "TriggerHandleView", "Failed to remove handle view", e)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)

        if (isDragging) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val dm = resources.displayMetrics
                    if (config.edge == HandleEdge.LEFT || config.edge == HandleEdge.RIGHT) {
                        val rawY = event.rawY
                        val newPercent = (rawY / dm.heightPixels).coerceIn(0.05f, 0.95f)
                        layoutParams.y = ((dm.heightPixels - layoutParams.height) * newPercent).toInt()
                        windowManager.updateViewLayout(this, layoutParams)
                    } else {
                        val rawX = event.rawX
                        val newPercent = (rawX / dm.widthPixels).coerceIn(0.05f, 0.95f)
                        layoutParams.x = ((dm.widthPixels - layoutParams.width) * newPercent).toInt()
                        windowManager.updateViewLayout(this, layoutParams)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    val dm = resources.displayMetrics
                    val finalPercent = if (config.edge == HandleEdge.LEFT || config.edge == HandleEdge.RIGHT) {
                        (layoutParams.y.toFloat() / (dm.heightPixels - layoutParams.height)).coerceIn(0.05f, 0.95f)
                    } else {
                        (layoutParams.x.toFloat() / (dm.widthPixels - layoutParams.width)).coerceIn(0.05f, 0.95f)
                    }
                    HandleManager.getInstance(context).updateHandlePosition(config.id, finalPercent)
                }
            }
            return true
        }

        return true
    }
}
