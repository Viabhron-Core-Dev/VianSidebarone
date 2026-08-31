package com.example.feature.miniapps.reader

import android.content.Intent


import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

import com.example.util.Utils
import com.example.feature.sidebar.BubbleDrawable
// import com.example.utils.getEdgeFlag

import kotlin.math.abs

class ReaderHandleView(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val windowManager: WindowManager
) {

    private val handleId = "reader"
    private val prefix = "handle_${handleId}_"

    private var handleView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isAttached = false
    
    fun attach() {
        if (isAttached) return
        
        handleView = View(context)
        
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        
        updatePosition()
        setupListeners()
        
        windowManager.addView(handleView, layoutParams)
        isAttached = true
    }
    
    fun setVisibility(visible: Boolean) {
        handleView?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun detach() {
        if (!isAttached) return
        if (handleView != null) {
            try {
                windowManager.removeView(handleView)
            } catch (e: Exception) {}
        }
        isAttached = false
    }
    
    fun updatePosition() {
        if (handleView == null || layoutParams == null) return
        
        val edgeStr = prefs.getString("${prefix}edge", "right") ?: "right"
val gravity = Gravity.END
        
        val yPos = prefs.getInt("${prefix}y", 50)
        
        layoutParams?.gravity = gravity or Gravity.TOP
        layoutParams?.y = (Utils.getScreenHeight(context) * (yPos / 100f)).toInt()
        
        val heightDp = try {
            prefs.getInt("${prefix}height", if (handleId == "reader") 60 else 120)
        } catch (e: Exception) {
            val str = prefs.getString("${prefix}height", "medium") ?: "medium"
            val map = mapOf("small" to 60, "medium" to 120, "large" to 200, "xlarge" to 300)
            map[str] ?: (if (handleId == "reader") 60 else 120)
        }
        
        val widthDp = try {
            prefs.getInt("${prefix}width", if (handleId == "reader") 16 else 6)
        } catch (e: Exception) {
            val str = prefs.getString("${prefix}width", "medium") ?: "medium"
            val map = mapOf("small" to 4, "medium" to 6, "large" to 10, "xlarge" to 16)
            map[str] ?: (if (handleId == "reader") 16 else 6)
        }
        
        val heightPx = Utils.dpToPx(context, heightDp)
        val widthPx = Utils.dpToPx(context, widthDp)
        
        layoutParams?.height = heightPx
        layoutParams?.width = widthPx
        
        val colorInt = try {
            val c = prefs.all["${prefix}color"]
            when (c) {
                is Int -> c
                is String -> android.graphics.Color.parseColor(c)
                else -> android.graphics.Color.GRAY
            }
        } catch(e: Exception) { android.graphics.Color.GRAY }
        
        val shapeStr = prefs.getString("${prefix}shape", "rectangle") ?: "rectangle"
        val edgeStrForShape = prefs.getString("${prefix}edge", "right") ?: "right"
        handleView?.background = BubbleDrawable(null)
        
        if (isAttached) {
            windowManager.updateViewLayout(handleView, layoutParams)
        }
    }
    
    private fun setupListeners() {
        val gestureDetector = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent): Boolean {
                return true
            }
            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                handleAction("tap")
                return true
            }
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                handleAction("double_tap")
                return true
            }
            override fun onLongPress(e: android.view.MotionEvent) {
                handleAction("long_press")
            }
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null) {
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
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
        val intent = android.content.Intent(context, com.example.feature.miniapps.reader.FloatingReaderService::class.java)
        intent.putExtra("UNFOLD", true)
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }
}
