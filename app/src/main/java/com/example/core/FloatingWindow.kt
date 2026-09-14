package com.example.core

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * WindowState: Formal lifecycle states for runtime managed overlay windows.
 */
enum class WindowState {
    CREATED,
    ATTACHED,
    VISIBLE,
    HIDDEN,
    FOLDED,     // Dormant folded state: View layer detached to save GPU/composition memory while retaining state
    DETACHED,
    DESTROYED
}

/**
 * WindowBounds: Geometric bounds and coordinates for a floating window.
 */
data class WindowBounds(
    var x: Int = 0,
    var y: Int = 0,
    var width: Int = 0,
    var height: Int = 0
) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height

    fun intersects(other: WindowBounds): Boolean {
        return x < other.right && right > other.x && y < other.bottom && bottom > other.y
    }

    fun contains(px: Int, py: Int): Boolean {
        return px in x..right && py in y..bottom
    }

    fun copyBounds(): WindowBounds = WindowBounds(x, y, width, height)
}

/**
 * WindowLifecycleListener: Contract for observing window lifecycle and state transitions.
 */
interface WindowLifecycleListener {
    fun onStateChanged(window: FloatingWindow, oldState: WindowState, newState: WindowState) {}
    fun onFocusChanged(window: FloatingWindow, isFocused: Boolean) {}
    fun onZOrderChanged(window: FloatingWindow, zOrder: Int) {}
    fun onBoundsChanged(window: FloatingWindow, bounds: WindowBounds) {}
    fun onDestroyed(window: FloatingWindow) {}
}

/**
 * FloatingWindow: Foundational contract and base class for all managed runtime overlay windows.
 *
 * Implements:
 * 1. Safe WindowManager.LayoutParams creation (TYPE_APPLICATION_OVERLAY).
 * 2. Custom container FrameLayout intercepting touch events to automatically bring dormant windows to front.
 * 3. Parent/child hierarchy hooks (parentId, childIds).
 * 4. Deterministic Z-order and focus tracking integration.
 * 5. Dormant folding (detaching view layer during memory pressure while preserving window state).
 * 6. Leak-safe lifecycle hooks and cleanup.
 */
open class FloatingWindow(
    val windowId: String,
    val context: Context,
    val windowType: String = TYPE_GENERIC
) {
    // Window manager reference once registered
    var manager: FloatingWindowManager? = null
        internal set

    // Hierarchy relationships
    var parentId: String? = null
        internal set
    val childIds: MutableSet<String> = LinkedHashSet()

    // State tracking
    var state: WindowState = WindowState.CREATED
        internal set

    var zOrder: Int = 0
        internal set

    var isFocused: Boolean = false
        internal set

    var isFolded: Boolean = false
        internal set

    var bounds: WindowBounds = WindowBounds(100, 100, 600, 800)
        internal set

    // Convenience lifecycle callbacks
    var onFocusChange: ((Boolean) -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onStateChange: ((WindowState) -> Unit)? = null

    private val lifecycleListeners = mutableListOf<WindowLifecycleListener>()

    // Root container view and content
    private var containerView: FrameLayout? = null
    private var contentView: View? = null

    val isAttached: Boolean get() = state == WindowState.ATTACHED || state == WindowState.VISIBLE
    val isVisible: Boolean get() = state == WindowState.VISIBLE

    /**
     * Standard LayoutParams for Android overlay window.
     */
    val layoutParams: WindowManager.LayoutParams = WindowManager.LayoutParams(
        bounds.width,
        bounds.height,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = bounds.x
        y = bounds.y
    }

    /**
     * Creates or retrieves the root container view.
     * Intercepts touch events on dormant windows to instantly trigger bringToFront upon user tap.
     */
    open fun getOrCreateContainerView(): FrameLayout {
        if (containerView == null) {
            val container = object : FrameLayout(context.applicationContext) {
                override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                    if (!isFocused && ev.action == MotionEvent.ACTION_DOWN) {
                        manager?.bringToFront(this@FloatingWindow.windowId)
                    }
                    return super.onInterceptTouchEvent(ev)
                }
            }
            container.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            val content = createContentView(context.applicationContext)
            if (content != null) {
                contentView = content
                container.addView(content)
            }

            containerView = container
        }
        return containerView!!
    }

    /**
     * Subclasses override this to supply their custom content view.
     */
    open fun createContentView(context: Context): View? = null

    /**
     * Retrieves the root container view if instantiated.
     */
    open fun getView(): View? = containerView

    /**
     * Updates window frame coordinates and dimensions.
     */
    open fun setWindowBounds(newBounds: WindowBounds) {
        bounds = newBounds.copyBounds()
        layoutParams.x = bounds.x
        layoutParams.y = bounds.y
        layoutParams.width = bounds.width
        layoutParams.height = bounds.height
        notifyBoundsChanged(bounds)
    }

    // Lifecycle Listener Management
    fun addLifecycleListener(listener: WindowLifecycleListener) {
        if (!lifecycleListeners.contains(listener)) {
            lifecycleListeners.add(listener)
        }
    }

    fun removeLifecycleListener(listener: WindowLifecycleListener) {
        lifecycleListeners.remove(listener)
    }

    // Internal Lifecycle Hooks invoked by FloatingWindowManager
    internal fun notifyStateChanged(oldState: WindowState, newState: WindowState) {
        state = newState
        onStateChange?.invoke(newState)
        for (listener in lifecycleListeners) {
            listener.onStateChanged(this, oldState, newState)
        }
    }

    internal fun notifyFocusChanged(focused: Boolean) {
        isFocused = focused
        onFocusChange?.invoke(focused)
        onFocus(focused)
        for (listener in lifecycleListeners) {
            listener.onFocusChanged(this, focused)
        }
    }

    internal fun notifyZOrderChanged(newZOrder: Int) {
        zOrder = newZOrder
        onZOrder(newZOrder)
        for (listener in lifecycleListeners) {
            listener.onZOrderChanged(this, newZOrder)
        }
    }

    internal fun notifyBoundsChanged(newBounds: WindowBounds) {
        for (listener in lifecycleListeners) {
            listener.onBoundsChanged(this, newBounds)
        }
    }

    // Open hooks for subclasses
    open fun onRegister(manager: FloatingWindowManager) {}
    open fun onAttach(wm: WindowManager) {}
    open fun onShow() {}
    open fun onHide() {}
    open fun onFocus(focused: Boolean) {}
    open fun onZOrder(newZOrder: Int) {}
    open fun onFold(folded: Boolean) {}
    open fun onDetach(wm: WindowManager) {}

    open fun onDestroy() {
        onClose?.invoke()
        for (listener in lifecycleListeners) {
            listener.onDestroyed(this)
        }
        lifecycleListeners.clear()
        containerView?.removeAllViews()
        containerView = null
        contentView = null
        onClose = null
        onFocusChange = null
        onStateChange = null
    }

    companion object {
        const val TYPE_GENERIC = "generic"
        const val TYPE_PAGE = "page"
        const val TYPE_TOOL = "tool"
        const val TYPE_DIALOG = "dialog"
        const val TYPE_BUBBLE = "bubble"
    }
}
