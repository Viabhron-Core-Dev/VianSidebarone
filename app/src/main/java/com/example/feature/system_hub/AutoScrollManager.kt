package com.example.feature.system_hub

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import android.widget.ImageView
import com.example.R
import com.example.core.LogKeeper

class AutoScrollManager(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var floatingView: View? = null
    private val handler = Handler(Looper.getMainLooper())

    private var isScrolling = true
    private var speed = 3 // 1 to 10
    var isRunning = false
        private set
    private var btnPausePlay: ImageButton? = null

    private fun updatePlayIcon() {
        if (isScrolling) {
            btnPausePlay?.setImageResource(android.R.drawable.ic_media_pause)
        } else {
            btnPausePlay?.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    private val screenHeight: Int
    private val screenWidth: Int

    init {
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        screenHeight = displayMetrics.heightPixels
        screenWidth = displayMetrics.widthPixels
    }

    private val scrollRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!isRunning || !isScrolling) return

            val swipePath = Path()
            val startY = screenHeight * 0.5f
            val endY = screenHeight * 0.15f
            val x = screenWidth / 2f
            swipePath.moveTo(x, startY)
            swipePath.lineTo(x, endY)

            // Speed: lower duration = faster
            val duration = Math.max(500L, 5000L - (speed * 400L))

            val gestureBuilder = GestureDescription.Builder()
            val stroke = GestureDescription.StrokeDescription(swipePath, 0, duration)
            gestureBuilder.addStroke(stroke)

            try {
                service.dispatchGesture(gestureBuilder.build(), object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        super.onCompleted(gestureDescription)
                        if (isRunning && isScrolling) {
                            handler.post(scrollRunnable)
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        super.onCancelled(gestureDescription)
                        if (isRunning && isScrolling) {
                            isScrolling = false
                            handler.post { updatePlayIcon() }
                        }
                    }
                }, null)
            } catch (e: Exception) {
                LogKeeper.logError(service, "AutoScrollManager", "Gesture dispatch failed", e)
                stop()
            }
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        showFloatingControls()
        isScrolling = true
        handler.post(scrollRunnable)
        LogKeeper.writeLog("AutoScrollManager", "Auto-scroll started")
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        isScrolling = false
        handler.removeCallbacks(scrollRunnable)
        removeFloatingControls()
        LogKeeper.writeLog("AutoScrollManager", "Auto-scroll stopped")
    }

    private fun isScreenScrollable(): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false
        val queue = java.util.LinkedList<AccessibilityNodeInfo>()
        queue.add(rootNode)
        var found = false
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            if (node.isScrollable) {
                found = true
                node.recycle()
                break
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    queue.add(child)
                }
            }
            node.recycle()
        }
        while (queue.isNotEmpty()) {
            queue.poll()?.recycle()
        }
        return found
    }

    private fun showFloatingControls() {
        if (floatingView != null) return

        val inflater = LayoutInflater.from(service)
        floatingView = inflater.inflate(R.layout.overlay_auto_scroll, null)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        layoutParams.y = 150

        btnPausePlay = floatingView?.findViewById(R.id.btn_pause_play)
        val btnSlower = floatingView?.findViewById<ImageButton>(R.id.btn_slower)
        val btnFaster = floatingView?.findViewById<ImageButton>(R.id.btn_faster)
        val btnExit = floatingView?.findViewById<ImageButton>(R.id.btn_exit)
        val ivIndicator = floatingView?.findViewById<ImageView>(R.id.iv_scroll_indicator)

        val checkScrollRunnable = object : Runnable {
            override fun run() {
                if (!isRunning) return
                if (isScreenScrollable()) {
                    ivIndicator?.setColorFilter(android.graphics.Color.GREEN)
                } else {
                    ivIndicator?.setColorFilter(android.graphics.Color.RED)
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(checkScrollRunnable)

        btnPausePlay?.setOnClickListener {
            isScrolling = !isScrolling
            updatePlayIcon()
            if (isScrolling) {
                handler.post(scrollRunnable)
            }
        }

        btnSlower?.setOnClickListener {
            if (speed > 1) speed--
        }

        btnFaster?.setOnClickListener {
            if (speed < 10) speed++
        }

        btnExit?.setOnClickListener {
            stop()
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        floatingView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY - (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(floatingView, layoutParams)
                    } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(floatingView, layoutParams)
        } catch (e: Exception) {
            LogKeeper.logError(service, "AutoScrollManager", "Error adding floatingView", e)
        }
    }

    private fun removeFloatingControls() {
        floatingView?.let {
            try {
                if (it.isAttachedToWindow) {
                    windowManager.removeView(it)
                }
            } catch (_: Exception) {}
            floatingView = null
        }
    }
}
