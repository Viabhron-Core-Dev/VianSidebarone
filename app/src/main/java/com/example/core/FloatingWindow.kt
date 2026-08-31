package com.example.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.R
import kotlin.math.max
import kotlin.math.roundToInt

abstract class FloatingWindow(val context: Context, val title: String) {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    
    var view: View? = null
    var bubbleView: View? = null
    var layoutParams: WindowManager.LayoutParams? = null
    var bubbleLayoutParams: WindowManager.LayoutParams? = null
    
    var isFolded = false
    var isFullScreen = false

    var onClose: (() -> Unit)? = null
    
    private var preFullScreenWidth = 800
    private var preFullScreenHeight = 1000
    private var preFullScreenX = 100
    private var preFullScreenY = 100

    // Keyboard avoidance properties
    private var originalYBeforeKeyboard: Int? = null
    private var isShiftedForKeyboard = false
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    
    fun show() {
        if (isFolded) {
            unfold()
            return
        }
        
        if (view == null) {
            view = createContainerView()
            setupLayoutParams()
            setupKeyboardAvoidance()
            windowManager.addView(view, layoutParams)
        } else {
            view?.visibility = View.VISIBLE
        }
        FloatingWindowManager.bringToFront(this)
    }
    
    fun hide() {
        onClose?.invoke()
        removeKeyboardAvoidance()
        view?.let {
            windowManager.removeView(it)
            view = null
        }
        bubbleView?.let {
            windowManager.removeView(it)
            bubbleView = null
        }
    }

    private fun setupKeyboardAvoidance() {
        val currentView = view ?: return
        
        // Listen to focus changes on any child EditText to toggle FLAG_NOT_FOCUSABLE
        currentView.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus is EditText) {
                setWindowFocusable(true)
            } else {
                setWindowFocusable(false)
            }
        }

        // GlobalLayoutListener to detect when keyboard opens/closes
        globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val root = view ?: return@OnGlobalLayoutListener
            if (isFullScreen) return@OnGlobalLayoutListener

            val rect = Rect()
            root.getWindowVisibleDisplayFrame(rect)
            val screenHeight = context.resources.displayMetrics.heightPixels
            val keypadHeight = screenHeight - rect.bottom

            // Threshold of 150dp or ~0.15 screen height indicates soft keyboard is open
            if (keypadHeight > screenHeight * 0.15) {
                val currentY = layoutParams?.y ?: 0
                val currentHeight = layoutParams?.height ?: root.height

                if (!isShiftedForKeyboard) {
                    originalYBeforeKeyboard = currentY
                }

                val windowBottom = currentY + currentHeight
                val keyboardTop = screenHeight - keypadHeight

                // If bottom of window is covered by keyboard
                if (windowBottom > keyboardTop) {
                    val overlap = windowBottom - keyboardTop + 24 // extra margin
                    val targetY = max(0, currentY - overlap)
                    if (layoutParams?.y != targetY) {
                        layoutParams?.y = targetY
                        isShiftedForKeyboard = true
                        try {
                            windowManager.updateViewLayout(root, layoutParams)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            } else {
                // Keyboard closed -> return to original place
                if (isShiftedForKeyboard && originalYBeforeKeyboard != null) {
                    layoutParams?.y = originalYBeforeKeyboard!!
                    isShiftedForKeyboard = false
                    originalYBeforeKeyboard = null
                    try {
                        windowManager.updateViewLayout(root, layoutParams)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        currentView.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
    }

    private fun removeKeyboardAvoidance() {
        globalLayoutListener?.let { listener ->
            view?.viewTreeObserver?.removeOnGlobalLayoutListener(listener)
        }
        globalLayoutListener = null
    }

    fun setWindowFocusable(focusable: Boolean) {
        val root = view ?: return
        val params = layoutParams ?: return
        val currentFlags = params.flags
        val newFlags = if (focusable) {
            currentFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            currentFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (newFlags != currentFlags) {
            params.flags = newFlags
            try {
                windowManager.updateViewLayout(root, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun createContainerView(): View {
        // Custom wrapper to intercept all touches and bring window to front
        val interceptorWrapper = object : FrameLayout(context) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                if (ev.action == MotionEvent.ACTION_DOWN) {
                    if (FloatingWindowManager.focusedWindow != this@FloatingWindow) {
                        FloatingWindowManager.bringToFront(this@FloatingWindow)
                    }
                }
                return super.onInterceptTouchEvent(ev)
            }
        }
        
        val container = LayoutInflater.from(context).inflate(R.layout.layout_floating_window_container, interceptorWrapper, true)
        
        val tvTitle = container.findViewById<TextView>(R.id.window_title)
        tvTitle.text = title
        
        val contentFrame = container.findViewById<FrameLayout>(R.id.window_content)
        val content = createContentView()
        contentFrame.addView(content)
        
        val topBar = container.findViewById<View>(R.id.window_top_bar)
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var lastClickTime = 0L
        
        topBar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val clickTime = System.currentTimeMillis()
                    if (clickTime - lastClickTime < 300) {
                        toggleFullScreen(container, topBar)
                    }
                    lastClickTime = clickTime
                    
                    initialX = layoutParams?.x ?: 0
                    initialY = layoutParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    FloatingWindowManager.bringToFront(this)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isFullScreen) {
                        layoutParams?.x = initialX + (event.rawX - initialTouchX).toInt()
                        layoutParams?.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(this@FloatingWindow.view, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isFullScreen) {
                        if (isShiftedForKeyboard) {
                            originalYBeforeKeyboard = layoutParams?.y
                        }
                        FloatingWindowManager.checkCollisions(this@FloatingWindow)
                    }
                    true
                }
                else -> false
            }
        }
        
        val btnClose = container.findViewById<ImageView>(R.id.btn_close)
        val btnMinimize = container.findViewById<ImageView>(R.id.btn_minimize)
        val btnResize = container.findViewById<ImageView>(R.id.btn_resize)
        
        btnClose.setOnClickListener {
            FloatingWindowManager.removeWindow(this)
        }
        
        btnMinimize.setOnClickListener {
            fold()
        }
        
        var initialW = 0
        var initialH = 0
        btnResize.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialW = layoutParams?.width ?: 0
                    initialH = layoutParams?.height ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isFullScreen) {
                        layoutParams?.width = initialW + (event.rawX - initialTouchX).toInt()
                        layoutParams?.height = initialH + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(container, layoutParams)
                    }
                    true
                }
                else -> false
            }
        }
        
        return interceptorWrapper
    }
    
    private fun toggleFullScreen(container: View, topBar: View) {
        if (!isFullScreen) {
            preFullScreenWidth = layoutParams?.width ?: 800
            preFullScreenHeight = layoutParams?.height ?: 1000
            preFullScreenX = layoutParams?.x ?: 100
            preFullScreenY = layoutParams?.y ?: 100
            
            val metrics = context.resources.displayMetrics
            layoutParams?.width = metrics.widthPixels
            layoutParams?.height = metrics.heightPixels
            layoutParams?.x = 0
            layoutParams?.y = 0
            isFullScreen = true
        } else {
            layoutParams?.width = preFullScreenWidth
            layoutParams?.height = preFullScreenHeight
            layoutParams?.x = preFullScreenX
            layoutParams?.y = preFullScreenY
            isFullScreen = false
        }
        windowManager.updateViewLayout(container, layoutParams)
    }
    
    abstract fun createContentView(): View
    
    private fun setupLayoutParams() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        val metrics = context.resources.displayMetrics
        val w = (metrics.widthPixels * 0.85).toInt()
        val h = (metrics.heightPixels * 0.6).toInt()
        
        layoutParams = WindowManager.LayoutParams(
            w, h,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }
    }
    
    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun fold() {
        isFolded = true
        view?.let {
            windowManager.removeView(it)
            view = null
        }
        
        if (bubbleView == null) {
            bubbleView = LayoutInflater.from(context).inflate(R.layout.layout_floating_bubble, null)
            val icon = bubbleView?.findViewById<ImageView>(R.id.bubble_icon)
            
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var lastClickTime = 0L
            
            bubbleView?.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = bubbleLayoutParams?.x ?: 0
                        initialY = bubbleLayoutParams?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        lastClickTime = System.currentTimeMillis()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        bubbleLayoutParams?.x = initialX + (event.rawX - initialTouchX).toInt()
                        bubbleLayoutParams?.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(bubbleView, bubbleLayoutParams)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val clickTime = System.currentTimeMillis()
                        val dx = Math.abs(event.rawX - initialTouchX)
                        val dy = Math.abs(event.rawY - initialTouchY)
                        if (clickTime - lastClickTime < 300 && dx < 10 && dy < 10) {
                            unfold()
                        } else {
                            FloatingWindowManager.checkCollisions(this@FloatingWindow)
                        }
                        true
                    }
                    else -> false
                }
            }
            
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            
            bubbleLayoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = layoutParams?.x ?: 100
                y = layoutParams?.y ?: 100
            }
            
            windowManager.addView(bubbleView, bubbleLayoutParams)
        } else {
            bubbleView?.visibility = View.VISIBLE
        }
    }
    
    fun unfold() {
        isFolded = false
        bubbleView?.let {
            windowManager.removeView(it)
            bubbleView = null
        }
        show()
    }
    
    fun onTrimMemory(level: Int) {
        if (isFolded) {
            // Destroy bubble view to save memory, it will be recreated on unfold()
            bubbleView?.let {
                windowManager.removeView(it)
                bubbleView = null
            }
        }
    }
}
