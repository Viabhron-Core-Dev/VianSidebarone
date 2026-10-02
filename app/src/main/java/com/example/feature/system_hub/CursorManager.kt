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

class CursorManager(
    private val service: AccessibilityService,
    internal var gestureDispatcher: ((GestureDescription?, AccessibilityService.GestureResultCallback?, Handler?) -> Boolean)? = null
) {
    private val windowManager: WindowManager? = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pointerView: ImageView? = null
    private var clickRippleView: View? = null
    private var controlView: View? = null
    private var trackpadView: View? = null

    var isRunning = false
        private set
    private var isPaused = false
    private var isGlassShield = true
    private var isDoubleTapPending = false

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
        windowManager?.let { wm ->
            @Suppress("DEPRECATION")
            wm.defaultDisplay?.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }

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

        pointerView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        clickRippleView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        controlView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        trackpadView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }

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
            windowManager?.addView(pointerView, params)
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
            windowManager?.addView(clickRippleView, params)
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
                handleSingleTap()
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
            windowManager?.addView(controlView, params)
        } catch (e: Exception) {
            LogKeeper.logError(service, "Cursor", "Error adding controlView", e)
        }
    }

    private fun createTrackpadView() {
        trackpadView = FrameLayout(service).apply {
            var lastTapUpTime = 0L
            var lastTapUpX = 0f
            var lastTapUpY = 0f
            var isDownInTapRange = false
            var downInitialX = 0f
            var downInitialY = 0f

            val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    // Single tap on the trackpad is completely inert (NO ACTION)
                    LogKeeper.writeLog("Cursor", "Single tap on trackpad ignored (inert)")
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    // Physical second tap DOWN - do NOT inject click yet
                    isDoubleTapPending = true
                    LogKeeper.writeLog("Cursor", "tap detected: double tap DOWN at ($pointerX, $pointerY), awaiting physical release")
                    return true
                }

                override fun onDoubleTapEvent(e: MotionEvent): Boolean {
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        if (isDoubleTapPending) {
                            isDoubleTapPending = false
                            LogKeeper.writeLog("Cursor", "tap detected: double tap UP released at ($pointerX, $pointerY) -> scheduling single click")
                            postDelayedHandler(Runnable {
                                performClick(pointerX, pointerY)
                            }, 16L)
                            return true
                        }
                    } else if (e.actionMasked == MotionEvent.ACTION_CANCEL) {
                        isDoubleTapPending = false
                    }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (isDoubleTapPending) return
                    val (targetX, targetY) = getDisplayCoordinates(pointerX, pointerY)
                    LogKeeper.writeLog("Cursor", "tap detected: long press at ($targetX, $targetY)")
                    performLongClick(targetX, targetY)
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    if (isDoubleTapPending) {
                        // Prevent accidental cursor drift during double tap
                        return true
                    }
                    pointerX -= distanceX * 1.35f
                    pointerY -= distanceY * 1.35f

                    pointerX = max(0f, min(screenWidth.toFloat(), pointerX))
                    pointerY = max(0f, min(screenHeight.toFloat(), pointerY))

                    updatePointerPosition()
                    return true
                }
            })

            setOnTouchListener { _, event ->
                val density = service.resources.displayMetrics.density
                val maxTapDist = 28f * density
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downInitialX = event.x
                        downInitialY = event.y
                        isDownInTapRange = true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = kotlin.math.abs(event.x - downInitialX)
                        val dy = kotlin.math.abs(event.y - downInitialY)
                        if (dx > maxTapDist || dy > maxTapDist) {
                            isDownInTapRange = false
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        val now = System.currentTimeMillis()
                        val dx = kotlin.math.abs(event.x - downInitialX)
                        val dy = kotlin.math.abs(event.y - downInitialY)
                        if (isDownInTapRange && dx <= maxTapDist && dy <= maxTapDist) {
                            val timeSinceLastTap = now - lastTapUpTime
                            val distFromLastTap = kotlin.math.hypot(
                                (event.x - lastTapUpX).toDouble(),
                                (event.y - lastTapUpY).toDouble()
                            ).toFloat()
                            if (timeSinceLastTap in 40L..380L && distFromLastTap <= maxTapDist * 1.5f) {
                                // Direct double tap detected on release if GestureDetector was not consumed
                                if (!isDoubleTapPending) {
                                    LogKeeper.writeLog("Cursor", "direct touch double-tap UP released at ($pointerX, $pointerY)")
                                    postDelayedHandler(Runnable {
                                        performClick(pointerX, pointerY)
                                    }, 16L)
                                }
                                lastTapUpTime = 0L
                            } else {
                                lastTapUpTime = now
                                lastTapUpX = event.x
                                lastTapUpY = event.y
                            }
                        } else {
                            lastTapUpTime = 0L
                        }
                        isDownInTapRange = false
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        isDownInTapRange = false
                        lastTapUpTime = 0L
                    }
                }
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
            windowManager?.addView(trackpadView, params)
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
            windowManager?.updateViewLayout(trackpadView, params)
        } catch (_: Exception) {}
    }

    private fun updatePointerPosition() {
        val params = pointerView?.layoutParams as? WindowManager.LayoutParams ?: return
        params.x = pointerX.toInt()
        params.y = pointerY.toInt()
        try {
            windowManager?.updateViewLayout(pointerView, params)
        } catch (_: Exception) {}

        clickRippleView?.let { ripple ->
            val rParams = ripple.layoutParams as? WindowManager.LayoutParams ?: return@let
            val density = service.resources.displayMetrics.density
            val sizePx = (36 * density).toInt()
            rParams.x = (pointerX - sizePx / 2f).toInt()
            rParams.y = (pointerY - sizePx / 2f).toInt()
            try {
                windowManager?.updateViewLayout(ripple, rParams)
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

    internal fun getDisplayCoordinates(x: Float, y: Float): Pair<Float, Float> {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { service.display } catch (_: Exception) { windowManager?.defaultDisplay }
        } else {
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        val maxW = if (metrics.widthPixels > 0) metrics.widthPixels else (if (screenWidth > 0) screenWidth else 1080)
        val maxH = if (metrics.heightPixels > 0) metrics.heightPixels else (if (screenHeight > 0) screenHeight else 1920)

        val targetX = x.coerceIn(0f, (maxW - 1).toFloat())
        val targetY = y.coerceIn(0f, (maxH - 1).toFloat())
        return Pair(targetX, targetY)
    }

    internal fun handleSingleTap() {
        LogKeeper.writeLog("Cursor", "tap detected: single tap at ($pointerX, $pointerY)")
        performClick(pointerX, pointerY)
    }

    internal fun handleDoubleTap() {
        LogKeeper.writeLog("Cursor", "tap detected: double tap at ($pointerX, $pointerY)")
        performClick(pointerX, pointerY)
    }

    private fun buildGestureSafely(builder: GestureDescription.Builder): GestureDescription? {
        return try {
            val buildMethod = builder.javaClass.getMethod("build")
            buildMethod.invoke(builder) as? GestureDescription
        } catch (_: Throwable) {
            null
        }
    }

    private fun dispatchGestureToService(
        gesture: GestureDescription?,
        callback: AccessibilityService.GestureResultCallback?
    ): Boolean {
        if (gesture == null) {
            return gestureDispatcher?.invoke(null, callback, mainHandler) ?: false
        }
        return gestureDispatcher?.invoke(gesture, callback, mainHandler)
            ?: service.dispatchGesture(gesture, callback, mainHandler)
    }

    internal var postDelayedHandler: (Runnable, Long) -> Unit = { r, delay ->
        if (gestureDispatcher != null) {
            r.run()
        } else {
            mainHandler.postDelayed(r, delay)
        }
    }

    internal fun setTrackpadTouchable(touchable: Boolean) {
        val view = trackpadView ?: return
        val wm = windowManager ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val isCurrentlyNotTouchable = (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
        val desiredNotTouchable = !touchable
        if (isCurrentlyNotTouchable == desiredNotTouchable) return

        if (desiredNotTouchable) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {}
    }

    internal fun performClick(x: Float, y: Float, onFinished: ((Boolean) -> Unit)? = null) {
        showClickAnimation()

        val (targetX, targetY) = getDisplayCoordinates(x, y)
        val path = Path().apply {
            moveTo(targetX, targetY)
            lineTo(targetX, targetY)
        }
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayId = try { service.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY } catch (_: Exception) { android.view.Display.DEFAULT_DISPLAY }
            gestureBuilder.setDisplayId(displayId)
        }

        // Temporarily disable trackpad touch reception and visibility so the synthetic gesture passes directly to the underlying app
        setTrackpadTouchable(false)
        trackpadView?.visibility = View.GONE
        var restored = false
        val restoreTouchableOnce = {
            if (!restored) {
                restored = true
                trackpadView?.visibility = View.VISIBLE
                setTrackpadTouchable(true)
            }
        }
        postDelayedHandler(Runnable {
            restoreTouchableOnce()
        }, 400L)

        // Delay dispatch slightly to ensure WindowManager has processed window touchability update
        postDelayedHandler(Runnable {
            try {
                val gesture = try {
                    gestureBuilder.build()
                } catch (_: Throwable) {
                    buildGestureSafely(gestureBuilder)
                }
                val accepted = dispatchGestureToService(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            postDelayedHandler(Runnable { restoreTouchableOnce() }, 50L)
                            LogKeeper.writeLog("Cursor", "gesture accepted/completed: single click at ($targetX, $targetY)")
                            onFinished?.invoke(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            restoreTouchableOnce()
                            LogKeeper.writeLog("Cursor", "gesture cancelled: single click at ($targetX, $targetY)")
                            onFinished?.invoke(false)
                        }
                    }
                )
                LogKeeper.writeLog("Cursor", "gesture dispatched: single click at ($targetX, $targetY), accepted=$accepted")
                if (!accepted) {
                    restoreTouchableOnce()
                    onFinished?.invoke(false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                restoreTouchableOnce()
                LogKeeper.writeLog("Cursor", "gesture dispatch error at ($targetX, $targetY): ${e.message}")
                onFinished?.invoke(false)
            }
        }, 80L)
    }

    internal fun performDoubleClick(x: Float, y: Float, onFinished: ((Boolean) -> Unit)? = null) {
        showClickAnimation()

        val (targetX, targetY) = getDisplayCoordinates(x, y)

        val path1 = Path().apply {
            moveTo(targetX, targetY)
            lineTo(targetX, targetY)
        }
        val gestureBuilder1 = GestureDescription.Builder()
        gestureBuilder1.addStroke(GestureDescription.StrokeDescription(path1, 0, 40))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayId = try { service.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY } catch (_: Exception) { android.view.Display.DEFAULT_DISPLAY }
            gestureBuilder1.setDisplayId(displayId)
        }

        setTrackpadTouchable(false)
        trackpadView?.visibility = View.GONE
        var restored = false
        val restoreTouchableOnce = {
            if (!restored) {
                restored = true
                trackpadView?.visibility = View.VISIBLE
                setTrackpadTouchable(true)
            }
        }
        postDelayedHandler(Runnable {
            restoreTouchableOnce()
        }, 500L)

        postDelayedHandler(Runnable {
            try {
                val gesture1 = try {
                    gestureBuilder1.build()
                } catch (_: Throwable) {
                    buildGestureSafely(gestureBuilder1)
                }
                val accepted1 = dispatchGestureToService(
                    gesture1,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            LogKeeper.writeLog("Cursor", "gesture accepted/completed: double click [1/2] at ($targetX, $targetY)")

                            // Second click gesture dispatched after short interval
                            postDelayedHandler(Runnable {
                                val path2 = Path().apply {
                                    moveTo(targetX, targetY)
                                    lineTo(targetX, targetY)
                                }
                                val gestureBuilder2 = GestureDescription.Builder()
                                gestureBuilder2.addStroke(GestureDescription.StrokeDescription(path2, 0, 40))
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    val displayId = try { service.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY } catch (_: Exception) { android.view.Display.DEFAULT_DISPLAY }
                                    gestureBuilder2.setDisplayId(displayId)
                                }

                                try {
                                    val gesture2 = try {
                                        gestureBuilder2.build()
                                    } catch (_: Throwable) {
                                        buildGestureSafely(gestureBuilder2)
                                    }
                                    val accepted2 = dispatchGestureToService(
                                        gesture2,
                                        object : AccessibilityService.GestureResultCallback() {
                                            override fun onCompleted(gd: GestureDescription?) {
                                                restoreTouchableOnce()
                                                LogKeeper.writeLog("Cursor", "gesture accepted/completed: double click [2/2] at ($targetX, $targetY)")
                                                onFinished?.invoke(true)
                                            }

                                            override fun onCancelled(gd: GestureDescription?) {
                                                restoreTouchableOnce()
                                                LogKeeper.writeLog("Cursor", "gesture cancelled: double click [2/2] at ($targetX, $targetY)")
                                                onFinished?.invoke(false)
                                            }
                                        }
                                    )
                                    LogKeeper.writeLog("Cursor", "gesture dispatched: double click [2/2] at ($targetX, $targetY), accepted=$accepted2")
                                    if (!accepted2) {
                                        restoreTouchableOnce()
                                        onFinished?.invoke(false)
                                    }
                                } catch (e: Exception) {
                                    restoreTouchableOnce()
                                    LogKeeper.writeLog("Cursor", "gesture dispatch error on double click [2/2]: ${e.message}")
                                    onFinished?.invoke(false)
                                }
                            }, 70L)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            restoreTouchableOnce()
                            LogKeeper.writeLog("Cursor", "gesture cancelled: double click [1/2] at ($targetX, $targetY)")
                            onFinished?.invoke(false)
                        }
                    }
                )
                LogKeeper.writeLog("Cursor", "gesture dispatched: double click [1/2] at ($targetX, $targetY), accepted=$accepted1")
                if (!accepted1) {
                    restoreTouchableOnce()
                    onFinished?.invoke(false)
                }
            } catch (e: Exception) {
                restoreTouchableOnce()
                LogKeeper.writeLog("Cursor", "gesture dispatch error on double click [1/2]: ${e.message}")
                onFinished?.invoke(false)
            }
        }, 80L)
    }

    internal fun performLongClick(x: Float, y: Float) {
        showClickAnimation()

        val (targetX, targetY) = getDisplayCoordinates(x, y)
        val path = Path().apply {
            moveTo(targetX, targetY)
            lineTo(targetX, targetY)
        }
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 600))

        setTrackpadTouchable(false)
        var restored = false
        val restoreTouchableOnce = {
            if (!restored) {
                restored = true
                setTrackpadTouchable(true)
            }
        }
        postDelayedHandler(Runnable {
            restoreTouchableOnce()
        }, 800L)

        try {
            val gesture = buildGestureSafely(gestureBuilder)
            val accepted = dispatchGestureToService(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        restoreTouchableOnce()
                        LogKeeper.writeLog("Cursor", "gesture accepted/completed: long press at ($targetX, $targetY)")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        restoreTouchableOnce()
                        LogKeeper.writeLog("Cursor", "gesture cancelled: long press at ($targetX, $targetY)")
                    }
                }
            )
            LogKeeper.writeLog("Cursor", "gesture dispatched: long press at ($targetX, $targetY), accepted=$accepted")
            if (!accepted) {
                restoreTouchableOnce()
            }
        } catch (e: Exception) {
            restoreTouchableOnce()
            LogKeeper.writeLog("Cursor", "Long press dispatch error: ${e.message}")
        }
    }
}
