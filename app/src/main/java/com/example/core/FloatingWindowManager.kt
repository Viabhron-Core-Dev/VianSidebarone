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
        log("WINDOW_REGISTER id=${window.windowId} type=${window.windowType} bounds=${window.bounds} zOrder=${window.zOrder} total=${windows.size}")
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

        log("WINDOW_UNREGISTER id=$windowId remaining=${windows.size}")
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
        if (window.state == WindowState.DESTROYED) return false
        val toElevate = LinkedHashSet<String>()
        collectDescendants(windowId, toElevate)
        toElevate.add(windowId)

        val currentIdx = zOrderedWindowIds.indexOf(windowId)
        val hasVisibleAttachedAbove = if (currentIdx >= 0) {
            ((currentIdx + 1) until zOrderedWindowIds.size).any { idx ->
                val otherId = zOrderedWindowIds[idx]
                if (otherId in toElevate) return@any false
                val other = windows[otherId]
                other != null && other.isAttached && other.isVisible
            }
        } else {
            false
        }
        val wasAlreadyTop = zOrderedWindowIds.lastOrNull() == windowId

        var orderChanged = false
        val lastIdx = zOrderedWindowIds.size - 1
        for (id in toElevate) {
            if (zOrderedWindowIds.indexOf(id) != lastIdx) {
                orderChanged = true
            }
            zOrderedWindowIds.remove(id)
        }
        zOrderedWindowIds.add(windowId)
        for (id in toElevate) {
            if (id != windowId) {
                zOrderedWindowIds.add(id)
            }
        }

        recomputeZOrders()

        val refreshPerformed = orderChanged && !wasAlreadyTop && hasVisibleAttachedAbove
        log("WINDOW_BRING_TO_FRONT id=$windowId alreadyTop=$wasAlreadyTop hasVisibleAttachedAbove=$hasVisibleAttachedAbove orderChanged=$orderChanged zOrder=${window.zOrder} refreshPerformed=$refreshPerformed")

        // Only refresh WindowManager view order when another visible attached window actually sits above this window!
        // Never remove and re-add if already visually on top, which destroys active SurfaceViews / touch streams.
        if (refreshPerformed) {
            for (id in toElevate) {
                val w = windows[id] ?: continue
                if (w.isAttached && w.isVisible) {
                    refreshOverlayZOrder(w)
                }
            }
        }

        if (window.state != WindowState.DETACHED && window.state != WindowState.DESTROYED) {
            setFocus(windowId)
        }
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

        if (oldFocus != null) {
            log("WINDOW_FOCUS id=${oldFocus.windowId} focused=false")
            oldFocus.notifyFocusChanged(false)
        }
        if (newFocus != null) {
            log("WINDOW_FOCUS id=${newFocus.windowId} focused=true")
            newFocus.notifyFocusChanged(true)
        }
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

    @Synchronized
    internal fun refreshOverlayZOrder(windowId: String): Boolean {
        val window = windows[windowId] ?: return false
        return refreshOverlayZOrder(window)
    }

    private fun refreshOverlayZOrder(window: FloatingWindow): Boolean {
        if (window.state == WindowState.DESTROYED) return false
        val view = window.getView() ?: return false
        val wasAttached = view.isAttachedToWindow || window.isOverlayViewAttached
        if (!wasAttached) return false

        val previousState = window.state
        val previousVisibility = if (previousState == WindowState.HIDDEN) View.GONE else view.visibility

        try {
            windowManager.removeViewImmediate(view)
            window.isOverlayViewAttached = view.isAttachedToWindow
        } catch (e: IllegalArgumentException) {
            if (view.isAttachedToWindow) {
                window.isOverlayViewAttached = true
                logError("Failed to remove overlay during Z-order refresh for ${window.windowId}: ${e.message}", e)
                return false
            }
            window.isOverlayViewAttached = false
        } catch (e: Exception) {
            window.isOverlayViewAttached = view.isAttachedToWindow || window.isOverlayViewAttached
            logError("Failed to remove overlay during Z-order refresh for ${window.windowId}: ${e.message}", e)
            return false
        }

        if (window.isAttached) {
            logError("Overlay view remained attached after removal during Z-order refresh for ${window.windowId}")
            return false
        }

        if (!canDrawOverlays()) {
            window.isOverlayViewAttached = false
            window.isFolded = false
            if (previousState != WindowState.DETACHED) {
                window.notifyStateChanged(previousState, WindowState.DETACHED)
                window.onDetach(windowManager)
            }
            if (focusedWindowId == window.windowId) {
                setFocus(getTopVisibleWindow()?.windowId)
            }
            logError("WINDOW_ZORDER_REATTACH_FAILURE id=${window.windowId}: overlay permission denied")
            return false
        }

        return try {
            view.visibility = previousVisibility
            windowManager.addView(view, window.layoutParams)
            window.isOverlayViewAttached = true
            if (window.state != previousState) {
                window.state = previousState
            }
            true
        } catch (e: Exception) {
            window.isOverlayViewAttached = view.isAttachedToWindow
            if (!window.isAttached) {
                window.isFolded = false
                if (previousState != WindowState.DETACHED) {
                    window.notifyStateChanged(previousState, WindowState.DETACHED)
                    window.onDetach(windowManager)
                }
                if (focusedWindowId == window.windowId) {
                    setFocus(getTopVisibleWindow()?.windowId)
                }
            }
            logError("Failed to re-add overlay during Z-order refresh for ${window.windowId}: ${e.message}", e)
            false
        }
    }

    // Show / Hide / Attach / Detach
    @Synchronized
    fun attachOverlay(windowId: String): Boolean {
        val window = windows[windowId] ?: return false
        if (window.state == WindowState.DESTROYED) return false

        val existingView = window.getView()
        if (existingView?.isAttachedToWindow == true || window.isOverlayViewAttached) {
            if (existingView != null) {
                window.isOverlayViewAttached = true
                return true
            }
            window.isOverlayViewAttached = false
        }

        if (!canDrawOverlays()) {
            logError("WINDOW_ATTACH_FAILURE id=$windowId: overlay permission denied")
            return false
        }

        return try {
            val container = window.getOrCreateContainerView()
            if (container.isAttachedToWindow) {
                window.isOverlayViewAttached = true
                val oldState = window.state
                if (oldState == WindowState.CREATED || oldState == WindowState.DETACHED || oldState == WindowState.FOLDED) {
                    window.isFolded = false
                    window.notifyStateChanged(oldState, WindowState.ATTACHED)
                    window.onAttach(windowManager)
                }
                return true
            }
            windowManager.addView(container, window.layoutParams)
            window.isOverlayViewAttached = true
            window.isFolded = false
            val oldState = window.state
            if (oldState != WindowState.ATTACHED) {
                window.notifyStateChanged(oldState, WindowState.ATTACHED)
            }
            window.onAttach(windowManager)
            log("WINDOW_ATTACH id=$windowId bounds=${window.bounds} type=${window.windowType}")
            true
        } catch (e: Exception) {
            window.isOverlayViewAttached = window.getView()?.isAttachedToWindow == true
            logError("WINDOW_ATTACH_FAILURE id=$windowId: ${e.message}", e)
            false
        }
    }

    @Synchronized
    fun detachOverlay(windowId: String): Boolean {
        val window = windows[windowId] ?: return false
        val view = window.getView()
        if (!window.isAttached) {
            if (window.state == WindowState.ATTACHED || window.state == WindowState.VISIBLE) {
                window.notifyStateChanged(window.state, WindowState.DETACHED)
                window.onDetach(windowManager)
                if (focusedWindowId == windowId) {
                    setFocus(getTopVisibleWindow()?.windowId)
                }
            }
            return true
        }

        return try {
            if (view != null) {
                try {
                    windowManager.removeViewImmediate(view)
                } catch (e: IllegalArgumentException) {
                    if (view.isAttachedToWindow) {
                        throw e
                    }
                }
            }
            window.isOverlayViewAttached = view?.isAttachedToWindow == true
            if (window.isAttached) {
                logError("WINDOW_DETACH_FAILURE id=$windowId: view remained attached")
                return false
            }
            window.isFolded = false
            val oldState = window.state
            if (oldState != WindowState.DETACHED) {
                window.notifyStateChanged(oldState, WindowState.DETACHED)
                window.onDetach(windowManager)
            }

            if (focusedWindowId == windowId) {
                setFocus(getTopVisibleWindow()?.windowId)
            }
            log("WINDOW_DETACH id=$windowId")
            true
        } catch (e: Exception) {
            window.isOverlayViewAttached = (view?.isAttachedToWindow == true) || window.isOverlayViewAttached
            logError("WINDOW_DETACH_FAILURE id=$windowId: ${e.message}", e)
            false
        }
    }

    @Synchronized
    fun showWindow(windowId: String): Boolean {
        val window = windows[windowId] ?: return false
        if (window.state == WindowState.DESTROYED) return false

        val wasFolded = window.isFolded
        if (!window.isAttached && !attachOverlay(windowId)) return false

        var container = window.getView()
        if (container == null) {
            window.isOverlayViewAttached = false
            if (!attachOverlay(windowId)) return false
            container = window.getView()
        }
        val attachedContainer = container ?: return false
        attachedContainer.visibility = View.VISIBLE
        window.isFolded = false

        val oldState = window.state
        if (oldState != WindowState.VISIBLE) {
            window.notifyStateChanged(oldState, WindowState.VISIBLE)
            window.onShow()
        }
        if (wasFolded) {
            window.onFold(false)
        }

        bringToFront(windowId)

        for (child in getChildren(windowId)) {
            showWindow(child.windowId)
        }
        return true
    }

    @Synchronized
    fun hideWindow(windowId: String): Boolean {
        val window = windows[windowId] ?: return false
        if (window.state == WindowState.DESTROYED) return false
        window.getView()?.visibility = View.GONE

        val oldState = window.state
        if (oldState != WindowState.HIDDEN) {
            window.notifyStateChanged(oldState, WindowState.HIDDEN)
            window.onHide()
        }

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
        if (window.state == WindowState.DESTROYED) return false
        if (window.isFolded && !window.isAttached && window.state == WindowState.FOLDED) return true

        val view = window.getView()
        if (view != null && window.isAttached) {
            try {
                windowManager.removeViewImmediate(view)
                window.isOverlayViewAttached = view.isAttachedToWindow
            } catch (e: IllegalArgumentException) {
                if (view.isAttachedToWindow) {
                    window.isOverlayViewAttached = true
                    logError("Fold detach failure for $windowId: ${e.message}", e)
                    return false
                }
                window.isOverlayViewAttached = false
            } catch (e: Exception) {
                window.isOverlayViewAttached = view.isAttachedToWindow || window.isOverlayViewAttached
                logError("Fold detach failure for $windowId: ${e.message}", e)
                return false
            }
            if (window.isAttached) {
                logError("Fold detach failure for $windowId: view remained attached")
                return false
            }
        } else if (view == null) {
            window.isOverlayViewAttached = false
        }

        window.isFolded = true
        val oldState = window.state
        if (oldState != WindowState.FOLDED) {
            window.notifyStateChanged(oldState, WindowState.FOLDED)
            window.onFold(true)
        }

        if (focusedWindowId == windowId) {
            setFocus(getTopVisibleWindow()?.windowId)
        }
        return true
    }

    @Synchronized
    fun unfoldWindow(windowId: String): Boolean {
        val window = windows[windowId] ?: return false
        if (window.state == WindowState.DESTROYED) return false
        if (!window.isFolded && window.isAttached && window.state == WindowState.VISIBLE) return true

        if (window.isAttached) {
            val container = window.getView()
            if (container != null) {
                container.visibility = View.VISIBLE
                window.isFolded = false
                val oldState = window.state
                if (oldState != WindowState.VISIBLE) {
                    window.notifyStateChanged(oldState, WindowState.VISIBLE)
                }
                window.onFold(false)
                bringToFront(windowId)
                return true
            }
            window.isOverlayViewAttached = false
        }

        if (!canDrawOverlays()) {
            logError("WINDOW_UNFOLD_ATTACH_FAILURE id=$windowId: overlay permission denied")
            return false
        }

        val container = window.getOrCreateContainerView()
        val previousVisibility = container.visibility
        return try {
            container.visibility = View.VISIBLE
            if (!container.isAttachedToWindow) {
                windowManager.addView(container, window.layoutParams)
            }
            window.isOverlayViewAttached = true
            window.isFolded = false
            window.onAttach(windowManager)

            val oldState = window.state
            if (oldState != WindowState.VISIBLE) {
                window.notifyStateChanged(oldState, WindowState.VISIBLE)
            }
            window.onFold(false)
            bringToFront(windowId)
            true
        } catch (e: Exception) {
            window.isOverlayViewAttached = container.isAttachedToWindow
            if (!window.isAttached) {
                container.visibility = previousVisibility
                window.isFolded = true
            }
            logError("Unfold attach error for $windowId: ${e.message}", e)
            false
        }
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
            log("TrimMemory level=$level: folding dormant overlay windows and releasing element runtime instances")
            com.example.feature.element.ElementRuntimeResolver.getInstance(appContext).releaseAll()
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

        internal fun resetForTesting() {
            synchronized(this) {
                instance?.unregisterAll()
                instance = null
            }
        }
    }
}
