package com.example.feature.system_hub

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * PrivacyCurtainManager: Full-screen screen darkening / Anti-Peep overlay with a resizable
 * transparent rectangular viewing window.
 *
 * Architecture:
 * - 4-Panel Dark Overlay (Top, Bottom, Left, Right) positioned around the clear window.
 * - Because the clear rectangular region contains no overlay view, Android natively routes
 *   all touches inside the clear box directly to the underlying applications.
 * - Swiping up/down on any dark panel repositions the clear viewing window.
 * - Long-pressing any dark panel acts as the quick exit gate (dismisses the curtain).
 * - Control pill with opacity toggle, resize handle, and dismiss button.
 */
object PrivacyCurtainManager {

    var isEnabled: Boolean = false
        private set

    // Curtain opacity between 0.30f and 1.0f (default 0.90f)
    var opacity: Float = 0.90f
        private set

    // Current transparent aperture bounds
    var windowRect: Rect = Rect()
        private set

    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400

    // Views for 4 outer dark panels
    private var topPanel: FrameLayout? = null
    private var bottomPanel: FrameLayout? = null
    private var leftPanel: FrameLayout? = null
    private var rightPanel: FrameLayout? = null

    // Overlay control bar / resize handle
    private var controlPill: LinearLayout? = null
    private var resizeHandle: FrameLayout? = null

    private var windowManager: WindowManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private const val MIN_WINDOW_WIDTH_DP = 160
    private const val MIN_WINDOW_HEIGHT_DP = 100
    private const val LONG_PRESS_TIMEOUT_MS = 600L

    fun toggle(context: Context) {
        if (isEnabled) {
            disable(context)
        } else {
            enable(context)
        }
    }

    fun enable(context: Context) {
        if (isEnabled) return

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val metrics = context.resources.displayMetrics
        screenWidth = max(320, metrics.widthPixels)
        screenHeight = max(480, metrics.heightPixels)

        // Initialize default rectangular window centered in upper half
        val density = metrics.density
        val defaultWidth = min(screenWidth - (32 * density).toInt(), (340 * density).toInt())
        val defaultHeight = (180 * density).toInt()
        val defaultLeft = (screenWidth - defaultWidth) / 2
        val defaultTop = (screenHeight / 3) - (defaultHeight / 2)

        windowRect = Rect(
            defaultLeft,
            max(0, defaultTop),
            defaultLeft + defaultWidth,
            max(0, defaultTop) + defaultHeight
        )

        buildPanels(context)
        buildControls(context)
        addViewsToWindowManager()

        isEnabled = true
    }

    fun disable(context: Context? = null) {
        opacity = 0.90f
        if (!isEnabled && topPanel == null) return

        val wm = windowManager ?: (context?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)

        fun safeRemove(view: View?) {
            if (view != null && wm != null) {
                try {
                    wm.removeView(view)
                } catch (_: Throwable) {}
            }
        }

        safeRemove(topPanel)
        safeRemove(bottomPanel)
        safeRemove(leftPanel)
        safeRemove(rightPanel)
        safeRemove(controlPill)
        safeRemove(resizeHandle)

        topPanel = null
        bottomPanel = null
        leftPanel = null
        rightPanel = null
        controlPill = null
        resizeHandle = null
        opacity = 0.90f
        isEnabled = false
    }

    fun setOpacityLevel(newOpacity: Float, context: Context? = null) {
        opacity = newOpacity.coerceIn(0.30f, 1.0f)
        val argb = calculatePanelColor()
        topPanel?.setBackgroundColor(argb)
        bottomPanel?.setBackgroundColor(argb)
        leftPanel?.setBackgroundColor(argb)
        rightPanel?.setBackgroundColor(argb)
    }

    private fun calculatePanelColor(): Int {
        val alphaInt = (opacity * 255).toInt().coerceIn(0, 255)
        return Color.argb(alphaInt, 0, 0, 0)
    }

    private fun buildPanels(context: Context) {
        val darkColor = calculatePanelColor()
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        fun createDarkPanel(): FrameLayout {
            return FrameLayout(context).apply {
                setBackgroundColor(darkColor)
                setupPanelTouchListener(this, context, touchSlop)
            }
        }

        topPanel = createDarkPanel()
        bottomPanel = createDarkPanel()
        leftPanel = createDarkPanel()
        rightPanel = createDarkPanel()
    }

    private fun setupPanelTouchListener(panel: View, context: Context, touchSlop: Int) {
        var startY = 0f
        var lastY = 0f
        var isDragging = false
        var longPressRunnable: Runnable? = null

        panel.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    lastY = event.rawY
                    isDragging = false

                    // Schedule exit gate on long press
                    longPressRunnable = Runnable {
                        if (!isDragging && isEnabled) {
                            disable(context)
                        }
                    }
                    mainHandler.postDelayed(longPressRunnable!!, LONG_PRESS_TIMEOUT_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val currentY = event.rawY
                    val deltaTotalY = currentY - startY
                    if (!isDragging && abs(deltaTotalY) > touchSlop) {
                        isDragging = true
                        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    }

                    if (isDragging) {
                        val stepY = (currentY - lastY).toInt()
                        lastY = currentY
                        moveWindowVertical(stepY)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun moveWindowVertical(deltaY: Int) {
        val height = windowRect.height()
        var newTop = windowRect.top + deltaY
        var newBottom = newTop + height

        if (newTop < 0) {
            newTop = 0
            newBottom = newTop + height
        }
        if (newBottom > screenHeight) {
            newBottom = screenHeight
            newTop = newBottom - height
        }

        windowRect.set(windowRect.left, newTop, windowRect.right, newBottom)
        updateLayouts()
    }

    private fun buildControls(context: Context) {
        val density = context.resources.displayMetrics.density

        // Floating Control Pill (Opacity toggle & Close button)
        controlPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), (4 * density).toInt(), (12 * density).toInt(), (4 * density).toInt())
            setBackgroundColor(Color.parseColor("#EE222222"))

            // Opacity button
            val opacityText = TextView(context).apply {
                text = "${(opacity * 100).toInt()}%"
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
                setOnClickListener {
                    // Cycle opacity: 60% -> 80% -> 95% -> 100%
                    val nextOpacity = when {
                        opacity < 0.70f -> 0.80f
                        opacity < 0.88f -> 0.95f
                        opacity < 0.98f -> 1.00f
                        else -> 0.60f
                    }
                    setOpacityLevel(nextOpacity, context)
                    text = "${(nextOpacity * 100).toInt()}%"
                }
            }
            addView(opacityText)

            // Divider
            val div = View(context).apply {
                layoutParams = LinearLayout.LayoutParams((1 * density).toInt(), (14 * density).toInt()).apply {
                    setMargins((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                }
                setBackgroundColor(Color.parseColor("#66FFFFFF"))
            }
            addView(div)

            // Close button (X)
            val closeIcon = TextView(context).apply {
                text = "✕"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
                setOnClickListener {
                    disable(context)
                }
            }
            addView(closeIcon)
        }

        // Resize corner handle at bottom-right corner of the clear aperture
        resizeHandle = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#CC1DB954")) // Accent green
            val minWidthPx = (MIN_WINDOW_WIDTH_DP * density).toInt()
            val minHeightPx = (MIN_WINDOW_HEIGHT_DP * density).toInt()

            var startX = 0f
            var startY = 0f
            var initialRight = 0
            var initialBottom = 0

            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        initialRight = windowRect.right
                        initialBottom = windowRect.bottom
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - startX).toInt()
                        val dy = (event.rawY - startY).toInt()

                        var newRight = initialRight + dx
                        var newBottom = initialBottom + dy

                        newRight = newRight.coerceIn(windowRect.left + minWidthPx, screenWidth)
                        newBottom = newBottom.coerceIn(windowRect.top + minHeightPx, screenHeight)

                        windowRect.set(windowRect.left, windowRect.top, newRight, newBottom)
                        updateLayouts()
                        true
                    }
                    else -> true
                }
            }
        }
    }

    private fun addViewsToWindowManager() {
        val wm = windowManager ?: return

        try {
            wm.addView(topPanel, createPanelParams(0, 0, screenWidth, windowRect.top))
            wm.addView(bottomPanel, createPanelParams(0, windowRect.bottom, screenWidth, screenHeight - windowRect.bottom))
            wm.addView(leftPanel, createPanelParams(0, windowRect.top, windowRect.left, windowRect.height()))
            wm.addView(rightPanel, createPanelParams(windowRect.right, windowRect.top, screenWidth - windowRect.right, windowRect.height()))

            // Pill sits slightly above or below the window
            val pillParams = createInteractiveParams(
                x = windowRect.left + 8,
                y = max(8, windowRect.top - 48),
                width = WindowManager.LayoutParams.WRAP_CONTENT,
                height = WindowManager.LayoutParams.WRAP_CONTENT
            )
            wm.addView(controlPill, pillParams)

            // Resize handle at bottom-right
            val handleParams = createInteractiveParams(
                x = windowRect.right - 36,
                y = windowRect.bottom - 36,
                width = 36,
                height = 36
            )
            wm.addView(resizeHandle, handleParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateLayouts() {
        val wm = windowManager ?: return

        try {
            topPanel?.let {
                wm.updateViewLayout(it, createPanelParams(0, 0, screenWidth, windowRect.top))
            }
            bottomPanel?.let {
                wm.updateViewLayout(it, createPanelParams(0, windowRect.bottom, screenWidth, max(0, screenHeight - windowRect.bottom)))
            }
            leftPanel?.let {
                wm.updateViewLayout(it, createPanelParams(0, windowRect.top, windowRect.left, windowRect.height()))
            }
            rightPanel?.let {
                wm.updateViewLayout(it, createPanelParams(windowRect.right, windowRect.top, max(0, screenWidth - windowRect.right), windowRect.height()))
            }
            controlPill?.let {
                val pillY = if (windowRect.top > 54) windowRect.top - 46 else windowRect.bottom + 8
                wm.updateViewLayout(it, createInteractiveParams(windowRect.left + 8, pillY, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT))
            }
            resizeHandle?.let {
                wm.updateViewLayout(it, createInteractiveParams(windowRect.right - 36, windowRect.bottom - 36, 36, 36))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createPanelParams(x: Int, y: Int, width: Int, height: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            max(0, width),
            max(0, height),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    private fun createInteractiveParams(x: Int, y: Int, width: Int, height: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }
}
