package com.example.feature.sidebar

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.viewpager2.widget.ViewPager2
import com.example.core.LogKeeper
import com.example.core.SidebarPage
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyCommandType
import com.example.core.ipc.HeavyConnectionListener
import com.example.core.ipc.HeavyEvent
import com.example.core.ipc.HeavyProcessConnectionManager
import java.util.UUID

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
    private var isRevealed = false
    private var hasReceivedDesiredHeight = false
    private var isScrollIdle = true

    private var desiredHeightPx = -1
    private val lastDispatchedRect = Rect()

    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var pendingStableUpdateRunnable: Runnable? = null
    private var isFallbackRevealTriggered = false
    private var fallbackRevealRunnable: Runnable? = null

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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (desiredHeightPx > 0) {
            desiredHeightPx
        } else if (layoutParams.height > 0) {
            layoutParams.height
        } else {
            // Generic bootstrap strategy: derive available content height from host container geometry
            // If parent has valid height, adopt it; otherwise derive a comfortable fraction (45%) of screen height
            val parentH = (parent as? View)?.height?.takeIf { it > 0 } ?: (parent as? View)?.measuredHeight?.takeIf { it > 0 }
            parentH ?: ((context.resources.displayMetrics.heightPixels * 0.45f).toInt())
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
        isRevealed = false
        hasReceivedDesiredHeight = false
        isFallbackRevealTriggered = false
        LogKeeper.log(context, TAG, "Placeholder onPageSelected for '${config.id}' in Main (mount requested)")

        // Acquire connection to Heavy on demand
        connectionManager.acquireConsumer(consumerTag)

        if (isScrollIdle) {
            scheduleStableMountOrUpdate(debounceMs = 0L)
        }
    }

    override fun onPageUnselected() {
        if (!isSelected && !isMountRequested) return
        isSelected = false
        isMountRequested = false
        val wasMounted = isMounted
        isMounted = false
        isRevealed = false
        hasReceivedDesiredHeight = false
        isFallbackRevealTriggered = false
        LogKeeper.log(context, TAG, "Placeholder onPageUnselected for '${config.id}' in Main (wasMounted=$wasMounted)")

        cancelPendingStableUpdate()
        cancelFallbackReveal()
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
            if (reportedH > 0) {
                post {
                    applyDesiredHeight(reportedH)
                }
            }
        }
    }

    private fun applyDesiredHeight(newHeight: Int) {
        if (newHeight <= 0) return
        hasReceivedDesiredHeight = true
        val changed = (newHeight != desiredHeightPx)
        if (changed) {
            LogKeeper.log(context, TAG, "Applying desired height $newHeight for '${config.id}' in Main")
            desiredHeightPx = newHeight
            requestLayout()
            onHeightChanged?.invoke(newHeight)
        } else {
            LogKeeper.log(context, TAG, "Desired height $newHeight confirmed unchanged for '${config.id}' in Main")
        }
        // Layout shift triggered or confirmed; schedule stable bounds update and reveal
        scheduleStableMountOrUpdate(debounceMs = if (changed) 50L else 0L)
    }

    // --- Geometry Synchronization ---

    private fun cancelPendingStableUpdate() {
        pendingStableUpdateRunnable?.let { removeCallbacks(it) }
        pendingStableUpdateRunnable = null
    }

    private fun scheduleFallbackReveal() {
        cancelFallbackReveal()
        val r = Runnable {
            if (isSelected && isMounted && !isRevealed) {
                LogKeeper.log(context, TAG, "Fallback reveal triggered for '${config.id}' in Main after timeout")
                isFallbackRevealTriggered = true
                scheduleStableMountOrUpdate(debounceMs = 0L)
            }
        }
        fallbackRevealRunnable = r
        postDelayed(r, 350L)
    }

    private fun cancelFallbackReveal() {
        fallbackRevealRunnable?.let { removeCallbacks(it) }
        fallbackRevealRunnable = null
        isFallbackRevealTriggered = false
    }

    private fun scheduleStableMountOrUpdate(debounceMs: Long = 40L) {
        cancelPendingStableUpdate()
        val r = Runnable {
            if (!isSelected || !isScrollIdle) return@Runnable
            val rect = getCurrentScreenRect() ?: run {
                // If not yet measured, retry shortly once
                if (isSelected && isScrollIdle) {
                    scheduleStableMountOrUpdate(debounceMs = 50L)
                }
                return@Runnable
            }
            if (rect.width() <= 0 || rect.height() <= 0) return@Runnable

            if (!isMounted) {
                // Initial bootstrap mount: always hidden (readyToShow=false)
                dispatchMountCommand(rect)
                setupLayoutMonitoring()
                scheduleFallbackReveal()
            } else {
                // Stable bounds update: reveal when desired height is received, already known, or on fallback timeout
                val readyToReveal = !isRevealed && (hasReceivedDesiredHeight || desiredHeightPx > 0 || isFallbackRevealTriggered)
                val boundsChanged = (rect.left != lastDispatchedRect.left ||
                        rect.top != lastDispatchedRect.top ||
                        rect.width() != lastDispatchedRect.width() ||
                        rect.height() != lastDispatchedRect.height())

                if (!boundsChanged && isRevealed) {
                    LogKeeper.log(context, TAG, "Bounds unchanged for '${config.id}' $rect and already revealed; update suppressed")
                    return@Runnable
                }

                val willBeRevealed = readyToReveal || isRevealed
                dispatchBoundsUpdate(rect, readyToShow = willBeRevealed)
                if (readyToReveal) {
                    isRevealed = true
                    cancelFallbackReveal()
                }
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
                if (isSelected && isScrollIdle) {
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
        isRevealed = false

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
                "height" to rect.height().toString(),
                "readyToShow" to "false"
            )
        )

        val result = connectionManager.sendCommand(command, autoConnect = true)
        LogKeeper.log(context, TAG, "Dispatched bootstrap MOUNT_PAGE for '${config.id}' at $rect (readyToShow=false) to :heavy. Result: ${result.success}")
    }

    private fun dispatchBoundsUpdate(rect: Rect, readyToShow: Boolean) {
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
                "height" to rect.height().toString(),
                "readyToShow" to if (readyToShow) "true" else "false"
            )
        )

        connectionManager.sendCommand(command, autoConnect = false)
        LogKeeper.log(context, TAG, "Dispatched stable UPDATE_PAGE_BOUNDS for '${config.id}' to $rect (readyToShow=$readyToShow) in :heavy")
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
    internal fun isRevealedState(): Boolean = isRevealed
    internal fun hasReceivedDesiredHeightState(): Boolean = hasReceivedDesiredHeight
    internal fun getLastDispatchedRect(): Rect = Rect(lastDispatchedRect)

    companion object {
        private const val TAG = "HeavyPagePlaceholder"
    }
}
