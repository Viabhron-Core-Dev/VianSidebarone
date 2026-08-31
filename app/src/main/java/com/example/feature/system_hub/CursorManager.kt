package com.example.feature.system_hub

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import com.example.R
import com.example.core.LogKeeper
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class CursorManager(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var pointerView: ImageView? = null
    private var clickRippleView: View? = null
    private var controlView: View? = null
    private var trackpadView: View? = null
    
    var isRunning = false
    private var isPaused = false
    private var isGlassShield = false // Default to comfortable Trackpad box mode so touch never leaks
    
    private var pointerX = 0f
    private var pointerY = 0f
    
    private var screenWidth = 0
    private var screenHeight = 0
    
    fun start() {
        if (isRunning) return
        isRunning = true
        isPaused = false
        
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        
        pointerX = screenWidth / 2f
        pointerY = screenHeight / 2f
        
        LogKeeper.writeLog("Cursor", "Started virtual cursor (screen: ${screenWidth}x${screenHeight})")
        
        createPointerView()
        createClickRippleView()
        createTrackpadView()
        createControlView()
        updateTrackpadLayout()
    }
    
    fun stop() {
        if (!isRunning) return
        isRunning = false
        
        LogKeeper.writeLog("Cursor", "Stopped virtual cursor")
        
        pointerView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        clickRippleView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        controlView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        trackpadView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        
        pointerView = null
        clickRippleView = null
        controlView = null
        trackpadView = null
    }
    
    private fun createPointerView() {
        pointerView = ImageView(service).apply {
            setImageResource(R.drawable.ic_cursor_pointer)
        }
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or 
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = pointerX.toInt()
            y = pointerY.toInt()
        }
        
        windowManager.addView(pointerView, params)
    }

    private fun createClickRippleView() {
        val density = service.resources.displayMetrics.density
        val sizePx = (36 * density).toInt()
        
        clickRippleView = View(service).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#444CAF50"))
                setStroke((2 * density).toInt(), Color.parseColor("#FF4CAF50"))
            }
            visibility = View.GONE
        }
        
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or 
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (pointerX - sizePx / 2f).toInt()
            y = (pointerY - sizePx / 2f).toInt()
        }
        
        windowManager.addView(clickRippleView, params)
    }
    
    private fun createControlView() {
        val density = service.resources.displayMetrics.density
        val layout = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE222222"))
                cornerRadius = 24f * density
                setStroke((1 * density).toInt(), Color.parseColor("#44FFFFFF"))
            }
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            gravity = Gravity.CENTER
        }
        
        val btnPause = ImageButton(service).apply {
            setImageResource(android.R.drawable.ic_media_pause)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            setOnClickListener {
                isPaused = !isPaused
                setImageResource(if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
                trackpadView?.visibility = if (isPaused) View.GONE else View.VISIBLE
                LogKeeper.writeLog("Cursor", "Cursor paused: $isPaused")
            }
        }

        val btnClick = ImageButton(service).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.parseColor("#4CAF50"))
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            setOnClickListener {
                performClick(pointerX, pointerY)
            }
        }
        
        val btnMode = ImageButton(service).apply {
            setImageResource(if (isGlassShield) android.R.drawable.ic_menu_crop else android.R.drawable.ic_menu_gallery)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            setOnClickListener {
                isGlassShield = !isGlassShield
                setImageResource(if (isGlassShield) android.R.drawable.ic_menu_crop else android.R.drawable.ic_menu_gallery)
                updateTrackpadLayout()
                LogKeeper.writeLog("Cursor", "Switched mode: isGlassShield=$isGlassShield")
            }
        }
        
        val btnExit = ImageButton(service).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            setOnClickListener { stop() }
        }
        
        layout.addView(btnPause)
        layout.addView(btnClick)
        layout.addView(btnMode)
        layout.addView(btnExit)
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (100 * density).toInt()
        }
        
        controlView = layout
        windowManager.addView(controlView, params)
    }
    
    private fun createTrackpadView() {
        val density = service.resources.displayMetrics.density
        
        trackpadView = FrameLayout(service).apply {
            var lastX = 0f
            var lastY = 0f
            var isDraggingCursor = false

            val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean {
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    // Single tap on trackpad triggers a real hardware-level click at cursor location
                    LogKeeper.writeLog("Cursor", "Single tap on trackpad -> click at ($pointerX, $pointerY)")
                    performClick(pointerX, pointerY)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    // Double tap on trackpad triggers click at cursor location
                    LogKeeper.writeLog("Cursor", "Double tap detected on trackpad -> click at ($pointerX, $pointerY)")
                    performClick(pointerX, pointerY)
                    return true
                }

                override fun onDoubleTapEvent(e: MotionEvent): Boolean {
                    if (e.action == MotionEvent.ACTION_UP) {
                        performClick(pointerX, pointerY)
                    }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    LogKeeper.writeLog("Cursor", "Long press on trackpad -> long click at ($pointerX, $pointerY)")
                    performLongClick(pointerX, pointerY)
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    pointerX -= distanceX * 1.35f
                    pointerY -= distanceY * 1.35f
                    
                    pointerX = max(0f, min(screenWidth.toFloat(), pointerX))
                    pointerY = max(0f, min(screenHeight.toFloat(), pointerY))
                    
                    updatePointerPosition()
                    return true
                }
            })

            setOnTouchListener { _, event ->
                // Always consume all touches so touches NEVER leak through to background apps
                gestureDetector.onTouchEvent(event)
                true
            }
        }
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        
        windowManager.addView(trackpadView, params)
    }
    
    private fun updateTrackpadLayout() {
        val params = trackpadView?.layoutParams as? WindowManager.LayoutParams ?: return
        val density = service.resources.displayMetrics.density
        
        if (isGlassShield) {
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = 0
            trackpadView?.setBackgroundColor(Color.TRANSPARENT)
        } else {
            val sizeWidth = (280 * density).toInt()
            val sizeHeight = (280 * density).toInt()
            params.width = sizeWidth
            params.height = sizeHeight
            params.gravity = Gravity.BOTTOM or Gravity.END
            params.y = (170 * density).toInt()
            params.x = (16 * density).toInt()
            trackpadView?.background = GradientDrawable().apply {
                setColor(Color.parseColor("#66222222"))
                cornerRadius = 16f * density
                setStroke((1.5f * density).toInt(), Color.parseColor("#AA00E676"))
            }
        }
        
        try {
            windowManager.updateViewLayout(trackpadView, params)
        } catch (e: Exception) {}
    }
    
    private fun updatePointerPosition() {
        val params = pointerView?.layoutParams as? WindowManager.LayoutParams ?: return
        params.x = pointerX.toInt()
        params.y = pointerY.toInt()
        try {
            windowManager.updateViewLayout(pointerView, params)
        } catch (e: Exception) {}
        
        clickRippleView?.let { ripple ->
            val rParams = ripple.layoutParams as? WindowManager.LayoutParams ?: return@let
            val density = service.resources.displayMetrics.density
            val sizePx = (36 * density).toInt()
            rParams.x = (pointerX - sizePx / 2f).toInt()
            rParams.y = (pointerY - sizePx / 2f).toInt()
            try {
                windowManager.updateViewLayout(ripple, rParams)
            } catch (e: Exception) {}
        }
    }
    
    private fun showClickAnimation() {
        // Visual tap animation feedback on the cursor pointer
        pointerView?.animate()
            ?.scaleX(0.70f)
            ?.scaleY(0.70f)
            ?.setDuration(70)
            ?.withEndAction {
                pointerView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(70)?.start()
            }
            ?.start()

        // Visual click ripple ring at the pointer tip
        clickRippleView?.let { ripple ->
            ripple.visibility = View.VISIBLE
            ripple.alpha = 1f
            ripple.scaleX = 0.5f
            ripple.scaleY = 0.5f
            ripple.animate()
                ?.scaleX(1.4f)
                ?.scaleY(1.4f)
                ?.alpha(0f)
                ?.setDuration(220)
                ?.withEndAction {
                    ripple.visibility = View.GONE
                }
                ?.start()
        }
    }

    private fun performClick(x: Float, y: Float) {
        showClickAnimation()

        val path = Path()
        path.moveTo(x, y)
        val gestureBuilder = GestureDescription.Builder()
        // 50ms realistic finger tap
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        
        try {
            service.dispatchGesture(
                gestureBuilder.build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        LogKeeper.writeLog("Cursor", "Tap gesture completed at ($x, $y)")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        LogKeeper.writeLog("Cursor", "Tap gesture cancelled at ($x, $y)")
                    }
                },
                mainHandler
            )
        } catch (e: Exception) {
            LogKeeper.writeLog("Cursor", "Tap dispatch error: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun performLongClick(x: Float, y: Float) {
        showClickAnimation()

        val path = Path()
        path.moveTo(x, y)
        val gestureBuilder = GestureDescription.Builder()
        // 600ms realistic long press
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 600))
        
        try {
            service.dispatchGesture(
                gestureBuilder.build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        LogKeeper.writeLog("Cursor", "Long press completed at ($x, $y)")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        LogKeeper.writeLog("Cursor", "Long press cancelled at ($x, $y)")
                    }
                },
                mainHandler
            )
        } catch (e: Exception) {
            LogKeeper.writeLog("Cursor", "Long press dispatch error: ${e.message}")
            e.printStackTrace()
        }
    }
}
