package com.example.feature.sidebar

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.view.ViewParent
import androidx.viewpager2.widget.ViewPager2
import com.example.core.LogKeeper
import com.example.core.SidebarPage
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyCommandType
import com.example.core.ipc.HeavyConnectionListener
import com.example.core.ipc.HeavyEvent
import com.example.core.ipc.HeavyProcessConnectionManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * HeavyPagePlaceholderView: Lightweight Main-process placeholder for pages hosted in :heavy (e.g. Calculator).
 *
 * Runs exclusively in the Main process (com.example).
 * Responsibilities:
 * 1. Occupies the ViewPager2 page slot with generic bootstrap height derived from host geometry,
 *    never collapsing to an empty TextView's intrinsic font padding.
 * 2. Emits zero heavy allocations (no calculator evaluation engine, no heavy views).
 * 3. Idempotently tracks page selection and mounts Heavy only when ViewPager scroll state settles at IDLE.
 * 4. Listens for Heavy page-height events across Binder (HeavyProcessConnectionManager), applying the
 *    real desired height to Main's Sidebar layout without measurement loops.
 * 5. Stabilizes geometry dispatch, suppressing transient per-frame coordinates during ViewPager settling.
 * 6. On unselection or detachment, cleanly unmounts the Heavy window and releases its Heavy consumer.
 */
class HeavyPagePlaceholderView(
    context: Context,
    private val config: SidebarPage,
    private val containerId: String,
    private val onHeightChanged: ((Int) -> Unit)? = null
) : FrameLayout(context), SidebarPageControllable, HeavyConnectionListener {

    private val connectionManager = HeavyProcessConnectionManager.getInstance(context)
    private val consumerTag = "HeavyPage_${config.id}_${UUID.randomUUID().toString().take(6)}"

    private var isSelected = false
    private var isMountRequested = false
    private var isMounted = false
    private var isScrollIdle = true

    private var desiredHeightPx = cachedDesiredHeights[config.id] ?: -1
    private val lastDispatchedRect = Rect()

    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var pendingStableUpdateRunnable: Runnable? = null

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        // Clean transparent placeholder view; no dummy text view child to prevent intrinsic collapse
        connectionManager.addListener(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        connectionManager.addListener(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        connectionManager.removeListener(this)
        if (isSelected || isMountRequested) {
            onPageUnselected()
        }
    }

    fun getBootstrapHeightPx(): Int {
        val cached = cachedDesiredHeights[config.id]
        if (cached != null && cached > 0) return cached
        if (config.useCustomSettings && config.height > 0) {
            return (config.height * context.resources.displayMetrics.density).toInt()
        }
        val defaultDp = when (config.type) {
            "calculator" -> 260
            "compass" -> 270
            else -> 260
        }
        return (defaultDp * context.resources.displayMetrics.density).toInt()
    }

    fun getCurrentHeightPx(): Int = if (desiredHeightPx > 0) desiredHeightPx else getBootstrapHeightPx()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (desiredHeightPx > 0) {
            desiredHeightPx
        } else if (layoutParams.height > 0) {
            layoutParams.height
        } else {
            getBootstrapHeightPx()
        }
        setMeasuredDimension(width, height)
    }

    override fun onPageSelected() {
        if (isSelected && isMountRequested) {
            LogKeeper.log(context, TAG, "Suppressed duplicate onPageSelected for '${config.id}' (already active/mounting)")
            return
        }
        isSelected = true
        isMountRequested = true
        LogKeeper.log(context, TAG, "Placeholder onPageSelected for '${config.id}' in Main (mount requested)")

        // Acquire connection to Heavy on demand
        connectionManager.acquireConsumer(consumerTag)

        if (isScrollIdle) {
            scheduleStableMountOrUpdate(debounceMs = 50L)
        }
    }

    override fun onPageUnselected() {
        if (!isSelected && !isMountRequested) return
        isSelected = false
        isMountRequested = false
        val wasMounted = isMounted
        isMounted = false
        LogKeeper.log(context, TAG, "Placeholder onPageUnselected for '${config.id}' in Main (wasMounted=$wasMounted)")

        cancelPendingStableUpdate()
        removeLayoutMonitoring()
        if (wasMounted) {
            dispatchUnmountCommand()
        }
        connectionManager.releaseConsumer(consumerTag)
    }

    override fun onPageScrollStateChanged(state: Int) {
        val idle = (state == ViewPager2.SCROLL_STATE_IDLE)
        isScrollIdle = idle
        if (idle && isSelected) {
            scheduleStableMountOrUpdate(debounceMs = 40L)
        }
    }

    // --- IpcConnectionListener implementation ---

    override fun onHeavyEvent(event: HeavyEvent) {
        val action = event.payload["action"]
        val targetPageId = event.payload["pageId"] ?: event.sourceId
        if (action == "page_height_changed" && targetPageId == config.id) {
            val reportedH = event.payload["height"]?.toIntOrNull() ?: return
            if (reportedH > 0 && reportedH != desiredHeightPx) {
                cachedDesiredHeights[config.id] = reportedH
                post {
                    applyDesiredHeight(reportedH)
                }
            }
        }
    }

    private fun applyDesiredHeight(newHeight: Int) {
        if (newHeight <= 0 || newHeight == desiredHeightPx) return
        LogKeeper.log(context, TAG, "Applying desired height $newHeight for '${config.id}' in Main")
        desiredHeightPx = newHeight
        requestLayout()
        onHeightChanged?.invoke(newHeight)
        // Check for bounds adjustment once Main layout stabilizes
        scheduleStableMountOrUpdate(debounceMs = 50L)
    }

    // --- Geometry Synchronization ---

    private fun cancelPendingStableUpdate() {
        pendingStableUpdateRunnable?.let { removeCallbacks(it) }
        pendingStableUpdateRunnable = null
    }

    private fun isHostAnimating(): Boolean {
        var p: ViewParent? = parent
        while (p != null) {
            if (p is View && (p.translationX != 0f || p.translationY != 0f)) {
                return true
            }
            p = p.parent
        }
        return false
    }

    private fun findParentViewPager(): ViewPager2? {
        var p: ViewParent? = parent
        while (p != null) {
            if (p is ViewPager2) return p
            p = p.parent
        }
        return null
    }

    private fun scheduleStableMountOrUpdate(debounceMs: Long = 40L) {
        cancelPendingStableUpdate()
        val r = Runnable {
            if (!isSelected) return@Runnable

            // If ViewPager2 is still dragging or settling, wait for SCROLL_STATE_IDLE
            val vp = findParentViewPager()
            if (vp != null && vp.scrollState != ViewPager2.SCROLL_STATE_IDLE) {
                isScrollIdle = false
                return@Runnable
            }
            if (!isScrollIdle) return@Runnable

            // If host sidebar is in the middle of sliding in (animateIn), defer until settled
            if (isHostAnimating()) {
                LogKeeper.log(context, TAG, "Host animating for '${config.id}'; deferring geometry synchronization")
                scheduleStableMountOrUpdate(debounceMs = 50L)
                return@Runnable
            }

            val rect = getCurrentScreenRect() ?: run {
                // If not yet measured, retry shortly once
                if (isSelected && isScrollIdle) {
                    scheduleStableMountOrUpdate(debounceMs = 50L)
                }
                return@Runnable
            }
            if (rect.width() <= 0 || rect.height() <= 0) return@Runnable

            if (!isMounted) {
                dispatchMountCommand(rect)
                setupLayoutMonitoring()
            } else {
                if (rect.left == lastDispatchedRect.left &&
                    rect.top == lastDispatchedRect.top &&
                    rect.width() == lastDispatchedRect.width() &&
                    rect.height() == lastDispatchedRect.height()) {
                    LogKeeper.log(context, TAG, "Bounds unchanged for '${config.id}' $rect; update suppressed")
                    return@Runnable
                }
                dispatchBoundsUpdate(rect)
            }
        }
        pendingStableUpdateRunnable = r
        if (debounceMs <= 0L) {
            post(r)
        } else {
            postDelayed(r, debounceMs)
        }
    }

    private fun setupLayoutMonitoring() {
        removeLayoutMonitoring()
        val observer = viewTreeObserver
        if (observer.isAlive) {
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                if (isSelected && isScrollIdle && !isHostAnimating()) {
                    // Debounce layout shifts (orientation, keyboard, sidebar height adjustments)
                    scheduleStableMountOrUpdate(debounceMs = 60L)
                }
            }
            layoutListener = listener
            observer.addOnGlobalLayoutListener(listener)
        }
    }

    private fun removeLayoutMonitoring() {
        layoutListener?.let { listener ->
            try {
                if (viewTreeObserver.isAlive) {
                    viewTreeObserver.removeOnGlobalLayoutListener(listener)
                }
            } catch (_: Throwable) {}
        }
        layoutListener = null
    }

    private fun getCurrentScreenRect(): Rect? {
        if (!isAttachedToWindow || width <= 0 || height <= 0) return null
        val location = IntArray(2)
        getLocationOnScreen(location)
        val x = location[0]
        val y = location[1]
        return Rect(x, y, x + width, y + height)
    }

    private fun dispatchMountCommand(rect: Rect) {
        lastDispatchedRect.set(rect)
        isMounted = true

        val command = HeavyCommand(
            commandId = "mount_${config.id}_${System.currentTimeMillis()}",
            type = HeavyCommandType.MOUNT_PAGE,
            targetId = config.id,
            payload = mapOf(
                "pageId" to config.id,
                "pageType" to config.type,
                "containerId" to containerId,
                "x" to rect.left.toString(),
                "y" to rect.top.toString(),
                "width" to rect.width().toString(),
                "height" to rect.height().toString()
            )
        )

        val result = connectionManager.sendCommand(command, autoConnect = true)
        LogKeeper.log(context, TAG, "Dispatched MOUNT_PAGE for '${config.id}' at $rect to :heavy. Result: ${result.success}")
    }

    private fun dispatchBoundsUpdate(rect: Rect) {
        lastDispatchedRect.set(rect)

        val command = HeavyCommand(
            commandId = "bounds_${config.id}_${System.currentTimeMillis()}",
            type = HeavyCommandType.UPDATE_PAGE_BOUNDS,
            targetId = config.id,
            payload = mapOf(
                "pageId" to config.id,
                "x" to rect.left.toString(),
                "y" to rect.top.toString(),
                "width" to rect.width().toString(),
                "height" to rect.height().toString()
            )
        )

        connectionManager.sendCommand(command, autoConnect = false)
        LogKeeper.log(context, TAG, "Dispatched stable UPDATE_PAGE_BOUNDS for '${config.id}' to $rect in :heavy")
    }

    private fun dispatchUnmountCommand() {
        val command = HeavyCommand(
            commandId = "unmount_${config.id}_${System.currentTimeMillis()}",
            type = HeavyCommandType.UNMOUNT_PAGE,
            targetId = config.id,
            payload = mapOf(
                "pageId" to config.id
            )
        )
        connectionManager.sendCommand(command, autoConnect = false)
        LogKeeper.log(context, TAG, "Dispatched UNMOUNT_PAGE for '${config.id}' to :heavy")
    }

    // Testing hooks
    internal fun getDesiredHeightPx(): Int = desiredHeightPx
    internal fun isMountedState(): Boolean = isMounted
    internal fun isMountRequestedState(): Boolean = isMountRequested
    internal fun getLastDispatchedRect(): Rect = Rect(lastDispatchedRect)

    companion object {
        private const val TAG = "HeavyPagePlaceholder"
        private val cachedDesiredHeights = ConcurrentHashMap<String, Int>()

        internal fun getCachedDesiredHeight(pageId: String): Int? = cachedDesiredHeights[pageId]
        internal fun setCachedDesiredHeight(pageId: String, height: Int) {
            cachedDesiredHeights[pageId] = height
        }
        internal fun clearCachedDesiredHeights() {
            cachedDesiredHeights.clear()
        }
    }
}
