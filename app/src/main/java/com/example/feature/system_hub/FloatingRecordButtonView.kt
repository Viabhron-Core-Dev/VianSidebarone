package com.example.feature.system_hub

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import android.graphics.drawable.GradientDrawable

@SuppressLint("ViewConstructor")
class FloatingRecordButtonView(
    context: Context,
    private val prefs: SharedPreferences,
    private val windowManager: WindowManager,
    private val onClick: () -> Unit
) : FrameLayout(context) {

    private var layoutParams: WindowManager.LayoutParams
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private val clickSlop = 10f
    private var isAttached = false
    private val textView: TextView

    init {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val density = context.resources.displayMetrics.density
        val sizePx = (50 * density).toInt()

        layoutParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            x = prefs.getInt("record_btn_x", (20 * density).toInt())
            y = prefs.getInt("record_btn_y", (100 * density).toInt())
        }

        textView = TextView(context).apply {
            text = "REC"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setPadding(8, 8, 8, 8)
        }
        
        addView(textView)
        setRecordingState(false)
        setupDrag()
    }

    fun setRecordingState(isRecording: Boolean) {
        val background = GradientDrawable()
        background.shape = GradientDrawable.OVAL
        if (isRecording) {
            background.setColor(Color.RED)
            textView.text = "STOP"
        } else {
            background.setColor(Color.GRAY)
            textView.text = "REC"
        }
        textView.background = background
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = initialTouchX - event.rawX
                    val dy = initialTouchY - event.rawY
                    if (abs(dx) > clickSlop || abs(dy) > clickSlop) {
                        isDragging = true
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        if (isAttached) {
                            windowManager.updateViewLayout(this, layoutParams)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onClick()
                    } else {
                        prefs.edit()
                            .putInt("record_btn_x", layoutParams.x)
                            .putInt("record_btn_y", layoutParams.y)
                            .apply()
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun attach() {
        if (!isAttached) {
            try {
                windowManager.addView(this, layoutParams)
                isAttached = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun detach() {
        if (isAttached) {
            try {
                windowManager.removeView(this)
                isAttached = false
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
