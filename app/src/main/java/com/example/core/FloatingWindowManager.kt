package com.example.core

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/**
 * FloatingWindowManager: Centralized Main-process Hierarchy Window Manager.
 *
 * Provides:
 * 1. Centralized registry for all runtime managed overlay windows.
 * 2. Deterministic Z-order tracking (index 0 = bottom, last = top).
 * 3. Focus management with automatic focus shifts on bringToFront/close.
 * 4. Parent/child hierarchical window cascades (hide/show/close propagation).
 * 5. Safe overlay attach/detach to Android WindowManager (TYPE_APPLICATION_OVERLAY).
 * 6. Dormant folding and onTrimMemory hooks to release GPU/composition memory during RAM pressure.
 * 7. Collision detection and magnetic snapping to screen edges and adjacent windows.
 */
class FloatingWindowManager private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val windowManager: WindowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val windows = LinkedHashMap<String, FloatingWindow>()
    private val zOrderedWindowIds = mutableListOf<String>()

    var focusedWindowId: String? = null
        private set

    private val displayMetrics: DisplayMetrics
        get() = appContext.resources.displayMetrics

    val screenWidth: Int get() = displayMetrics.widthPixels
    val screenHeight: Int get() = displayMetrics.heightPixels

    private fun log(msg: String) {
        LogKeeper.log(appContext, TAG, msg)
    }

    private fun logError(msg: String, throwable: Throwable? = null) {
        LogKeeper.logError(appContext, TAG, msg, throwable)
    }

    // Registration & Removal
    @Synchronized
    fun registerWindow(window: FloatingWindow): Boolean {
        if (windows.containsKey(window.windowId)) {
            log("Window already registered: ${window.windowId}")
            return false
        }

        window.manager = this
        windows[window.windowId] = window
        zOrderedWindowIds.add(window.windowId)
        recomputeZOrders()

        window.notifyStateChanged(WindowState.CREATED, WindowState.CREATED)
        window.onRegister(this)
        log("Window registered: ${window.windowId}, total=${windows.size}")
        return true
    }

    @Synchronized
    fun unregisterWindow(windowId: String): Boolean {
        val window = windows[windowId] ?: return false

        // Detach children
        for (child in getChildren(windowId)) {
            child.parentId = null
        }

        // Detach from parent
        window.parentId?.let { pId ->
            windows[pId]?.childIds?.remove(windowId)
        }

        // Detach overlay view from Android WindowManager if attached
        if (window.isAttached) {
            detachOverlay(windowId)
        }

        zOrderedWindowIds.remove(windowId)

        if (focusedWindowId == windowId) {
            focusedWindowId = null
            window.notifyFocusChanged(false)
            getTopVisibleWindow()?.let { setFocus(it.windowId) }
        }

        recomputeZOrders()

        window.notifyStateChanged(window.state, WindowState.DESTROYED)
        window.onDestroy()
        window.manager = null
        windows.remove(windowId)

        log("Window unregistered: $windowId, remaining=${windows.size}")
        return true
    }

    @Synchronized
    fun unregisterAll() {
        val allIds = ArrayList(zOrderedWindowIds)
        for (id in allIds.reversed()) {
            unregisterWindow(id)
        }
    }

    fun getWindow(windowId: String): FloatingWindow? = windows[windowId]

    fun getAllWindows(): List<FloatingWindow> = windows.values.toList()

    // Hierarchy (Parent / Child)
    @Synchronized
    fun setParent(childId: String, parentId: String?): Boolean {
        val child = windows[childId] ?: return false
        if (parentId != null) {
            if (parentId == childId) return false
            val parent = windows[parentId] ?: return false

            // Prevent cyclic hierarchy
            var current: FloatingWindow? = parent
            while (current != null) {
                if (current.windowId == childId) {
                    log("Cycle detected between $childId and $parentId")
                    return false
                }
                current = current.parentId?.let { windows[it] }
            }

            child.parentId?.let { oldPId ->
                windows[oldPId]?.childIds?.remove(childId)
            }
            child.parentId = parentId
            parent.childIds.add(childId)
        } else {
            child.parentId?.let { oldPId ->
                windows[oldPId]?.childIds?.remove(childId)
            }
            child.parentId = null
        }
        return true
    }

    fun getChildren(parentId: String): List<FloatingWindow> {
        val parent = windows[parentId] ?: return emptyList()
        return parent.childIds.mapNotNull { windows[it] }
    }

    fun getParent(childId: String): FloatingWindow? {
        val child = windows[childId] ?: return null
        return child.parentId?.let { windows[it] }
    }

    // Z-Order & Focus
    @Synchronized
    fun bringToFront(windowId: String): Boolean {
        val window = windows[windowId] ?: return false

        val toElevate = LinkedHashSet<String>()
        collectDescendants(windowId, toElevate)
        toElevate.add(windowId)

        for (id in toElevate) {
            zOrderedWindowIds.remove(id)
        }
        zOrderedWindowIds.add(windowId)
        for (id in toElevate) {
            if (id != windowId) {
                zOrderedWindowIds.add(id)
            }
        }

        recomputeZOrders()

        for (id in toElevate) {
            val w = windows[id] ?: continue
            if (w.isAttached && w.isVisible) {
                refreshOverlayZOrder(w)
            }
        }

        setFocus(windowId)
        return true
    }

    @Synchronized
    fun sendToBack(windowId: String): Boolean {
        val window = windows[windowId] ?: return false
        zOrderedWindowIds.remove(windowId)
        zOrderedWindowIds.add(0, windowId)
        recomputeZOrders()

        if (focusedWindowId == windowId) {
            setFocus(getTopVisibleWindow()?.windowId)
        }
        return true
    }

    @Synchronized
    fun setFocus(windowId: String?): Boolean {
        if (focusedWindowId == windowId) return true

        val oldFocus = focusedWindowId?.let { windows[it] }
        focusedWindowId = windowId
        val newFocus = windowId?.let { windows[it] }

        oldFocus?.notifyFocusChanged(false)
        newFocus?.notifyFocusChanged(true)
        return true
    }

    fun getFocusedWindow(): FloatingWindow? = focusedWindowId?.let { windows[it] }

    fun getTopWindow(): FloatingWindow? =
        zOrderedWindowIds.lastOrNull()?.let { windows[it] }

    fun getTopVisibleWindow(): FloatingWindow? {
        for (id in zOrderedWindowIds.reversed()) {
            val w = windows[id]
            if (w != null && w.isVisible) {
                return w
            }
        }
        return null
    }

    fun getWindowsInZOrder(): List<FloatingWindow> =
        zOrderedWindowIds.mapNotNull { windows[it] }

    private fun recomputeZOrders() {
        for ((index, id) in zOrderedWindowIds.withIndex()) {
            val w = windows[id] ?: continue
            if (w.zOrder != index) {
                w.notifyZOrderChanged(index)
            }
        }
    }

    private fun collectDescendants(parentId: String, outSet: MutableSet<String>) {
        val parent = windows[parentId] ?: return
        for (childId in parent.childIds) {
            if (outSet.add(childId)) {
                collectDescendants(childId, outSet)
            }
        }
    }

    private fun refreshOverlayZOrder(window: FloatingWindow) {
        val view = window.getView() ?: return
        try {
            windowManager.removeViewImmediate(view)
            windowManager.addView(view, window.layoutParams)
        } catch (e: Exception) {
            log("Failed to refresh Z-order for ${window.windowId}: ${e.message}")
        }
    }

    // Show / Hide / Attach / Detach
    @Synchronized
    fun attachOverlay(windowId: String): Boolean {
        val window = windows[windowId] ?: return false
        if (window.isAttached) return true

        if (!canDrawOverlays()) {
            log("Cannot attach overlay: permission denied")
            return false
        }

        return try {
            val container = window.getOrCreateContainerView()
            windowManager.addView(container, window.layoutParams)
            val oldState = window.state
            window.notifyStateChanged(oldState, WindowState.ATTACHED)
            window.onAttach(windowManager)
            true
        } catch (e: Exception) {
            logError("Failed to attach overlay for $windowId: ${e.message}", e)
            false
        }
    }

    @Synchronized
    fun detachOverlay(windowId: String): Boolean {
        val window = windows[windowId] ?: return false
        if (!window.isAttached) return true

        return try {
            val view = window.getView()
            if (view != null) {
                windowManager.removeViewImmediate(view)
            }
            val oldState = window.state
            window.notifyStateChanged(oldState, WindowState.DETACHED)
            window.onDetach(windowManager)

            if (focusedWindowId == windowId) {
                setFocus(getTopVisibleWindow()?.windowId)
            }
            true
        } catch (e: Exception) {
            log("Failed to detach overlay for $windowId: ${e.message}")
            false
        }
    }

    @Synchronized
    fun showWindow(windowId: String): Boolean {
        val window = windows[windowId] ?: return false

        if (!window.isAttached && !attachOverlay(windowId)) return false

        val container = window.getView() ?: return false
        container.visibility = View.VISIBLE
        window.isFolded = false

        val oldState = window.state
        window.notifyStateChanged(oldState, WindowState.VISIBLE)
        window.onShow()

        bringToFront(windowId)

        for (child in getChildren(windowId)) {
            showWindow(child.windowId)
        }
        return true
    }

    @Synchronized
    fun hideWindow(windowId: String): Boolean {
        val window = windows[windowId] ?: return false
        window.getView()?.visibility = View.GONE

        val oldState = window.state
        window.notifyStateChanged(oldState, WindowState.HIDDEN)
        window.onHide()

        for (child in getChildren(windowId)) {
            hideWindow(child.windowId)
        }

        if (focusedWindowId == windowId) {
            setFocus(getTopVisibleWindow()?.windowId)
        }
        return true
    }

    // Dormant Folding & Memory Trim (Historical Reference: RECEIPTS_008 & 009)
    @Synchronized
    fun foldWindow(windowId: String): Boolean {
        val window = windows[windowId] ?: return false
        if (window.isFolded) return true

        val view = window.getView()
        if (view != null && window.isAttached) {
            try {
                windowManager.removeViewImmediate(view)
            } catch (e: Exception) {
                log("Fold detach error: ${e.message}")
            }
        }

        window.isFolded = true
        val oldState = window.state
        window.notifyStateChanged(oldState, WindowState.FOLDED)
        window.onFold(true)

        if (focusedWindowId == windowId) {
            setFocus(getTopVisibleWindow()?.windowId)
        }
        return true
    }

    @Synchronized
    fun unfoldWindow(windowId: String): Boolean {
        val window = windows[windowId] ?: return false
        if (!window.isFolded) return true

        window.isFolded = false
        window.notifyStateChanged(window.state, WindowState.VISIBLE)
        window.onFold(false)

        val container = window.getOrCreateContainerView()
        container.visibility = View.VISIBLE
        try {
            windowManager.addView(container, window.layoutParams)
        } catch (e: Exception) {
            log("Unfold attach error: ${e.message}")
        }

        bringToFront(windowId)
        return true
    }

    @Synchronized
    fun toggleFold(windowId: String): Boolean {
        val window = windows[windowId] ?: return false
        return if (window.isFolded) unfoldWindow(windowId) else foldWindow(windowId)
    }

    @Synchronized
    fun foldAllExceptActive() {
        val activeId = focusedWindowId
        for (window in windows.values) {
            if (window.windowId != activeId && window.isVisible && !window.isFolded) {
                foldWindow(window.windowId)
            }
        }
    }

    @Synchronized
    fun foldAll() {
        for (window in windows.values) {
            if (window.isVisible && !window.isFolded) {
                foldWindow(window.windowId)
            }
        }
    }

    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
        ) {
            log("TrimMemory level=$level: folding dormant overlay windows")
            foldAllExceptActive()
        }
    }

    // Bounds & Collision / Magnetic Snapping (Historical Reference: RECEIPTS_008)
    @Synchronized
    fun updateBounds(windowId: String, newBounds: WindowBounds): Boolean {
        val window = windows[windowId] ?: return false
        window.setWindowBounds(newBounds)

        val view = window.getView()
        if (view != null && window.isAttached) {
            try {
                windowManager.updateViewLayout(view, window.layoutParams)
            } catch (e: Exception) {
                log("Failed to update layout for $windowId: ${e.message}")
                return false
            }
        }
        return true
    }

    fun checkCollisions(
        movingWindowId: String,
        candidateBounds: WindowBounds,
        snapThresholdPx: Int = MagneticSnapper.DEFAULT_SNAP_THRESHOLD_PX
    ): WindowBounds {
        return MagneticSnapper.checkCollisions(
            movingWindowId = movingWindowId,
            candidateBounds = candidateBounds,
            allWindows = windows.values,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            snapThresholdPx = snapThresholdPx
        )
    }

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(appContext)
        } else {
            true
        }
    }

    companion object {
        private const val TAG = "FloatingWindowManager"

        @Volatile
        private var instance: FloatingWindowManager? = null

        fun getInstance(context: Context): FloatingWindowManager {
            return instance ?: synchronized(this) {
                instance ?: FloatingWindowManager(context).also { instance = it }
            }
        }
    }
}
