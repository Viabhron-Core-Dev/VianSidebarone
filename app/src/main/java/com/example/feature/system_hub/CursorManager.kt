package com.example.feature.system_hub

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
        private set
    private var isPaused = false
    private var isGlassShield = false

    private var pointerX = 0f
    private var pointerY = 0f

    private var screenWidth = 0
    private var screenHeight = 0

    private val overlayType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    fun start() {
        if (isRunning) return
        isRunning = true
        isPaused = false

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
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

        pointerView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        clickRippleView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        controlView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        trackpadView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }

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
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = pointerX.toInt()
            y = pointerY.toInt()
        }

        try {
            windowManager.addView(pointerView, params)
        } catch (e: Exception) {
            LogKeeper.logError(service, "Cursor", "Error adding pointerView", e)
        }
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
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (pointerX - sizePx / 2f).toInt()
            y = (pointerY - sizePx / 2f).toInt()
        }

        try {
            windowManager.addView(clickRippleView, params)
        } catch (e: Exception) {
            LogKeeper.logError(service, "Cursor", "Error adding clickRippleView", e)
        }
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
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (100 * density).toInt()
        }

        controlView = layout
        try {
            windowManager.addView(controlView, params)
        } catch (e: Exception) {
            LogKeeper.logError(service, "Cursor", "Error adding controlView", e)
        }
    }

    private fun createTrackpadView() {
        trackpadView = FrameLayout(service).apply {
            val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    LogKeeper.writeLog("Cursor", "Single tap on trackpad -> click at ($pointerX, $pointerY)")
                    performClick(pointerX, pointerY)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    LogKeeper.writeLog("Cursor", "Double tap on trackpad -> click at ($pointerX, $pointerY)")
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
                gestureDetector.onTouchEvent(event)
                true
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager.addView(trackpadView, params)
        } catch (e: Exception) {
            LogKeeper.logError(service, "Cursor", "Error adding trackpadView", e)
        }
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
        } catch (_: Exception) {}
    }

    private fun updatePointerPosition() {
        val params = pointerView?.layoutParams as? WindowManager.LayoutParams ?: return
        params.x = pointerX.toInt()
        params.y = pointerY.toInt()
        try {
            windowManager.updateViewLayout(pointerView, params)
        } catch (_: Exception) {}

        clickRippleView?.let { ripple ->
            val rParams = ripple.layoutParams as? WindowManager.LayoutParams ?: return@let
            val density = service.resources.displayMetrics.density
            val sizePx = (36 * density).toInt()
            rParams.x = (pointerX - sizePx / 2f).toInt()
            rParams.y = (pointerY - sizePx / 2f).toInt()
            try {
                windowManager.updateViewLayout(ripple, rParams)
            } catch (_: Exception) {}
        }
    }

    private fun showClickAnimation() {
        pointerView?.animate()
            ?.scaleX(0.70f)
            ?.scaleY(0.70f)
            ?.setDuration(70)
            ?.withEndAction {
                pointerView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(70)?.start()
            }
            ?.start()

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
        }
    }

    private fun performLongClick(x: Float, y: Float) {
        showClickAnimation()

        val path = Path()
        path.moveTo(x, y)
        val gestureBuilder = GestureDescription.Builder()
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
        }
    }
}
