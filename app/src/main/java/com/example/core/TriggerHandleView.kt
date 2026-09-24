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
    private val onGestureAction: (actionKey: String, gesture: String, handleConfig: HandleConfig) -> Unit
) : View(context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val shapeDrawable = HandleShapeDrawable(config.shape, config.edge, config.color)
    private var isAttached = false
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
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    )

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var gestureHandled = false

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (!gestureHandled) {
                if (config.onTapAction != HandleManager.ACTION_NONE) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    onGestureAction(config.onTapAction, HandleGestures.TAP, config)
                    return true
                }
            }
            return false
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!gestureHandled) {
                if (config.onDoubleTapAction != HandleManager.ACTION_NONE) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    onGestureAction(config.onDoubleTapAction, HandleGestures.DOUBLE_TAP, config)
                    return true
                }
            }
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            if (!gestureHandled) {
                if (config.onLongPressAction != HandleManager.ACTION_NONE && config.onLongPressAction != HandleManager.ACTION_MOVE_HANDLE) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    onGestureAction(config.onLongPressAction, HandleGestures.LONG_PRESS, config)
                }
            }
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (gestureHandled) return false
            val startX = e1?.rawX ?: initialTouchX
            val startY = e1?.rawY ?: initialTouchY
            val dx = e2.rawX - startX
            val dy = e2.rawY - startY

            if (Math.abs(dx) > Math.abs(dy)) {
                if (dx > 40 && config.onSwipeRightAction != HandleManager.ACTION_NONE) {
                    gestureHandled = true
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    onGestureAction(config.onSwipeRightAction, HandleGestures.SWIPE_RIGHT, config)
                    return true
                } else if (dx < -40 && config.onSwipeLeftAction != HandleManager.ACTION_NONE) {
                    gestureHandled = true
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    onGestureAction(config.onSwipeLeftAction, HandleGestures.SWIPE_LEFT, config)
                    return true
                }
            } else {
                if (dy > 40 && config.onSwipeDownAction != HandleManager.ACTION_NONE) {
                    gestureHandled = true
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    onGestureAction(config.onSwipeDownAction, HandleGestures.SWIPE_DOWN, config)
                    return true
                } else if (dy < -40 && config.onSwipeUpAction != HandleManager.ACTION_NONE) {
                    gestureHandled = true
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    onGestureAction(config.onSwipeUpAction, HandleGestures.SWIPE_UP, config)
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
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                gestureHandled = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!gestureHandled) {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    val density = resources.displayMetrics.density
                    val swipeThresholdPx = 32f * density

                    if (Math.abs(dx) > swipeThresholdPx || Math.abs(dy) > swipeThresholdPx) {
                        if (Math.abs(dx) > Math.abs(dy)) {
                            if (dx < -swipeThresholdPx && config.onSwipeLeftAction != HandleManager.ACTION_NONE) {
                                gestureHandled = true
                                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                                onGestureAction(config.onSwipeLeftAction, HandleGestures.SWIPE_LEFT, config)
                            } else if (dx > swipeThresholdPx && config.onSwipeRightAction != HandleManager.ACTION_NONE) {
                                gestureHandled = true
                                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                                onGestureAction(config.onSwipeRightAction, HandleGestures.SWIPE_RIGHT, config)
                            }
                        } else {
                            if (dy > swipeThresholdPx && config.onSwipeDownAction != HandleManager.ACTION_NONE) {
                                gestureHandled = true
                                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                                onGestureAction(config.onSwipeDownAction, HandleGestures.SWIPE_DOWN, config)
                            } else if (dy < -swipeThresholdPx && config.onSwipeUpAction != HandleManager.ACTION_NONE) {
                                gestureHandled = true
                                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                                onGestureAction(config.onSwipeUpAction, HandleGestures.SWIPE_UP, config)
                            }
                        }
                    }
                }
            }
        }

        gestureDetector.onTouchEvent(event)
        return true
    }
}
