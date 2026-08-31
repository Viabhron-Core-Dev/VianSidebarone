package com.example.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import kotlin.math.abs

class TriggerHandleView(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val windowManager: WindowManager,
    val handleId: String
) {
    private var handleView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val prefix = "handle_${handleId}_"

    fun attach() {
        if (handleView != null) return

        val isEnabled = prefs.getBoolean("${prefix}enabled", true)
        if (!isEnabled) return

        val edge = prefs.getString("${prefix}edge", "right") ?: "right"
        val widthDp = prefs.getInt("${prefix}width", 15)
        val heightDp = prefs.getInt("${prefix}height", 200)
        val yOffsetDp = prefs.getInt("${prefix}y_offset", 0)
        val colorHex = prefs.getString("${prefix}color", "#FF5252") ?: "#FF5252"
        val alpha = prefs.getFloat("${prefix}alpha", 0.5f)

        val density = context.resources.displayMetrics.density
        val widthPx = (widthDp * density).toInt()
        val heightPx = (heightDp * density).toInt()
        val yOffsetPx = (yOffsetDp * density).toInt()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (edge == "left") Gravity.START or Gravity.CENTER_VERTICAL else Gravity.END or Gravity.CENTER_VERTICAL
            y = yOffsetPx
        }

        handleView = View(context).apply {
            val baseColor = try {
                Color.parseColor(colorHex)
            } catch (e: Exception) {
                Color.RED
            }
            val alphaInt = (alpha * 255).toInt().coerceIn(0, 255)
            val finalColor = Color.argb(alphaInt, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))

            background = GradientDrawable().apply {
                setColor(finalColor)
                val cornerRadius = 8f * density
                cornerRadii = if (edge == "left") {
                    floatArrayOf(0f, 0f, cornerRadius, cornerRadius, cornerRadius, cornerRadius, 0f, 0f)
                } else {
                    floatArrayOf(cornerRadius, cornerRadius, 0f, 0f, 0f, 0f, cornerRadius, cornerRadius)
                }
            }
        }

        setupGestures()

        try {
            windowManager.addView(handleView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun detach() {
        if (handleView != null) {
            try {
                windowManager.removeView(handleView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            handleView = null
        }
    }

    fun updatePosition() {
        detach()
        attach()
    }

    fun setVisibility(visible: Boolean?) {
        if (visible == null) {
            handleView?.visibility = View.VISIBLE
        } else {
            handleView?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                handleAction("tap")
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                handleAction("double_tap")
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                handleAction("long_press")
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 != null) {
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (abs(dx) > abs(dy)) {
                        if (dx > 50) handleAction("swipe_right")
                        else if (dx < -50) handleAction("swipe_left")
                    } else {
                        if (dy > 50) handleAction("swipe_down")
                        else if (dy < -50) handleAction("swipe_up")
                    }
                    return true
                }
                return false
            }
        })
        
        handleView?.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }
    }

    private fun handleAction(gesture: String) {
        val action = prefs.getString("$prefix$gesture", "none") ?: "none"
        com.example.core.LogKeeper.writeLog("Handle", "Handle ($handleId) gesture: $gesture -> action: $action")
        
        val sidebarIntent = Intent().apply {
            setClassName(context, "com.example.service.SidebarService")
            putExtra("handleId", handleId)
            putExtra("gesture", gesture)
            putExtra("containerId", "${handleId}_$gesture")
        }

        when {
            action == "toggle_sidebar" -> {
                sidebarIntent.action = "com.example.ACTION_TOGGLE_SIDEBAR"
                context.startService(sidebarIntent)
            }
            action == "toggle_reader" -> {
                val intent = Intent().apply {
                    setClassName(context, "com.example.service.FloatingReaderService")
                    putExtra("UNFOLD", true)
                }
                ContextCompat.startForegroundService(context, intent)
            }
            action.startsWith("open_page:") -> {
                val pageType = action.removePrefix("open_page:")
                sidebarIntent.action = "com.example.ACTION_OPEN_PAGE"
                sidebarIntent.putExtra("pageType", pageType)
                context.startService(sidebarIntent)
            }
            action.startsWith("open_element:") || action.startsWith("element:") -> {
                val elementId = action.removePrefix("open_element:").removePrefix("element:")
                sidebarIntent.action = "com.example.ACTION_EXECUTE_ELEMENT"
                sidebarIntent.putExtra("elementId", elementId)
                context.startService(sidebarIntent)
            }
            action.startsWith("system:") -> {
                sidebarIntent.action = "com.example.ACTION_EXECUTE_ELEMENT"
                sidebarIntent.putExtra("elementId", action)
                context.startService(sidebarIntent)
            }
            action.startsWith("open_") -> {
                val pageType = action.removePrefix("open_")
                sidebarIntent.action = "com.example.ACTION_OPEN_PAGE"
                sidebarIntent.putExtra("pageType", pageType)
                context.startService(sidebarIntent)
            }
            action.startsWith("action_") || action.startsWith("action:") -> {
                val sysAction = action.removePrefix("action_").removePrefix("action:")
                val accIntent = Intent("com.example.ACTION_ACCESSIBILITY_PERFORM").apply {
                    putExtra("action", sysAction)
                }
                context.sendBroadcast(accIntent)
            }
        }
    }
}
