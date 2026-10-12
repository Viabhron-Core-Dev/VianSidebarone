package com.example.feature.heavy.page

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.example.core.LogKeeper
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyEvent
import com.example.core.ipc.HeavyEventType
import com.example.core.ipc.HeavyProcessHost
import com.example.core.ipc.IpcErrorCode
import com.example.core.ipc.IpcResult
import com.example.feature.sidebar.CalculatorPageView
import java.util.concurrent.ConcurrentHashMap

/**
 * HeavyPageHostManager: Heavy-process runtime manager for hosting non-home Sidebar page views.
 *
 * Runs exclusively in :heavy.
 * When Main selects a Heavy-owned page (e.g. Calculator), HeavyPageHostManager:
 * 1. Instantiates the real PageView (e.g. CalculatorPageView) using Heavy's Context.
 * 2. Mounts it into a dedicated TYPE_APPLICATION_OVERLAY window matching Main's placeholder bounds.
 * 3. Keeps Calculator fully interactive (touches handled in :heavy) without polluting Main process heap.
 * 4. Unmounts and tears down cleanly when navigated away or when Sidebar closes.
 */
class HeavyPageHostManager private constructor(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeWindows = ConcurrentHashMap<String, PageWindowHolder>()
    private val lastReportedHeights = ConcurrentHashMap<String, Int>()

    private data class PageWindowHolder(
        val pageId: String,
        val pageType: String,
        val containerView: FrameLayout,
        val layoutParams: WindowManager.LayoutParams,
        var isAttached: Boolean = false
    )

    fun getActivePageCount(): Int = activeWindows.size

    /**
     * Mounts a Heavy-owned page view over the given screen coordinates.
     * Defensive and idempotent: if the page is already mounted, updates its bounds
     * without destroying or recreating the existing View.
     */
    fun mountPage(command: HeavyCommand): IpcResult {
        val pageId = command.payload["pageId"] ?: command.targetId ?: ""
        val pageType = command.payload["pageType"] ?: ""
        val x = command.payload["x"]?.toIntOrNull() ?: 0
        val y = command.payload["y"]?.toIntOrNull() ?: 0
        val width = command.payload["width"]?.toIntOrNull() ?: 0
        val height = command.payload["height"]?.toIntOrNull() ?: 0

        if (pageId.isEmpty() || pageType.isEmpty() || width <= 0 || height <= 0) {
            return IpcResult.error(
                IpcErrorCode.MARSHAL_ERROR,
                "Invalid mount parameters: pageId='$pageId', type='$pageType', w=$width, h=$height"
            )
        }

        mainHandler.post {
            try {
                // Idempotency check: if page is already mounted and attached, preserve it and update bounds
                val existing = activeWindows[pageId]
                if (existing != null && existing.isAttached) {
                    LogKeeper.log(context, TAG, "Page '$pageId' already mounted in :heavy; updating bounds idempotently")
                    updateBoundsInternal(existing, x, y, width, height)
                    return@post
                }

                if (!Settings.canDrawOverlays(context)) {
                    LogKeeper.log(context, TAG, "Cannot mount page '$pageId': overlay permission denied in :heavy")
                    return@post
                }

                val container = FrameLayout(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }

                // Instantiate the page view in :heavy
                val pageView: View = when (pageType) {
                    "calculator" -> {
                        LogKeeper.log(context, TAG, "Instantiating CalculatorPageView in :heavy for pageId='$pageId'")
                        CalculatorPageView(context) { newH ->
                            reportDesiredPageHeight(pageId, newH)
                        }
                    }
                    else -> {
                        LogKeeper.log(context, TAG, "Unsupported Heavy page type '$pageType'")
                        return@post
                    }
                }

                // Synchronously measure page view with target width to report height immediately
                val measureW = if (width > 0) width else (280 * context.resources.displayMetrics.density).toInt()
                pageView.measure(
                    View.MeasureSpec.makeMeasureSpec(measureW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val immediateH = pageView.measuredHeight
                if (immediateH > 0 && immediateH != height) {
                    reportDesiredPageHeight(pageId, immediateH)
                }

                container.addView(pageView)

                val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val lp = WindowManager.LayoutParams(
                    width,
                    height,
                    windowType,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    this.x = x
                    this.y = y
                }

                container.alpha = 0f
                windowManager.addView(container, lp)
                val holder = PageWindowHolder(pageId, pageType, container, lp, isAttached = true)
                activeWindows[pageId] = holder
                container.animate().alpha(1f).setDuration(120).start()
                LogKeeper.log(context, TAG, "Mounted page '$pageId' ($pageType) at ($x, $y) size ${width}x$height in :heavy")
            } catch (e: Throwable) {
                LogKeeper.logCrash(context, TAG, e)
            }
        }

        return IpcResult.success("Mounted $pageId")
    }

    /**
     * Reports real content height calculated by a Heavy page back to Main across Binder.
     */
    private fun reportDesiredPageHeight(pageId: String, newHeight: Int) {
        if (newHeight <= 0) return
        val previous = lastReportedHeights[pageId]
        if (previous == newHeight) return
        lastReportedHeights[pageId] = newHeight

        LogKeeper.log(context, TAG, "Reporting desired height $newHeight for page '$pageId' from :heavy to Main")
        val event = HeavyEvent(
            eventId = "height_${pageId}_${System.currentTimeMillis()}",
            type = HeavyEventType.STATE_UPDATED,
            sourceId = pageId,
            payload = mapOf(
                "action" to ACTION_PAGE_HEIGHT_CHANGED,
                "pageId" to pageId,
                "height" to newHeight.toString()
            )
        )
        HeavyProcessHost.getInstance(context).reportEvent(event)
    }

    /**
     * Updates screen coordinates and dimensions for an active Heavy page window.
     */
    fun updatePageBounds(command: HeavyCommand): IpcResult {
        val pageId = command.payload["pageId"] ?: command.targetId ?: ""
        val x = command.payload["x"]?.toIntOrNull() ?: 0
        val y = command.payload["y"]?.toIntOrNull() ?: 0
        val width = command.payload["width"]?.toIntOrNull() ?: 0
        val height = command.payload["height"]?.toIntOrNull() ?: 0

        mainHandler.post {
            val holder = activeWindows[pageId] ?: return@post
            updateBoundsInternal(holder, x, y, width, height)
        }

        return IpcResult.success("Bounds updated")
    }

    private fun updateBoundsInternal(holder: PageWindowHolder, x: Int, y: Int, width: Int, height: Int): Boolean {
        if (!holder.isAttached) return false
        return try {
            var changed = false
            if (holder.layoutParams.x != x || holder.layoutParams.y != y) {
                holder.layoutParams.x = x
                holder.layoutParams.y = y
                changed = true
            }
            if (width > 0 && holder.layoutParams.width != width) {
                holder.layoutParams.width = width
                changed = true
            }
            if (height > 0 && holder.layoutParams.height != height) {
                holder.layoutParams.height = height
                changed = true
            }
            if (changed) {
                windowManager.updateViewLayout(holder.containerView, holder.layoutParams)
                LogKeeper.log(context, TAG, "Updated bounds for '${holder.pageId}' to ($x, $y) ${width}x$height in :heavy")
            } else {
                LogKeeper.log(context, TAG, "Bounds unchanged for '${holder.pageId}' ($x, $y) ${width}x$height in :heavy; update suppressed")
            }
            changed
        } catch (e: Throwable) {
            LogKeeper.logCrash(context, TAG, e)
            false
        }
    }

    /**
     * Unmounts and tears down an active Heavy page window.
     */
    fun unmountPage(command: HeavyCommand): IpcResult {
        val pageId = command.payload["pageId"] ?: command.targetId ?: ""
        mainHandler.post {
            unmountPageInternal(pageId)
        }
        return IpcResult.success("Unmounted $pageId")
    }

    /**
     * Unmounts all active Heavy page windows immediately.
     */
    fun unmountAll() {
        mainHandler.post {
            val keys = ArrayList(activeWindows.keys)
            for (key in keys) {
                unmountPageInternal(key)
            }
        }
    }

    private fun unmountPageInternal(pageId: String) {
        lastReportedHeights.remove(pageId)
        val holder = activeWindows.remove(pageId) ?: return
        if (holder.isAttached) {
            try {
                holder.containerView.animate().cancel()
                windowManager.removeViewImmediate(holder.containerView)
                holder.isAttached = false
                LogKeeper.log(context, TAG, "Unmounted page window '$pageId' in :heavy")
            } catch (e: Throwable) {
                LogKeeper.log(context, TAG, "Failed to remove window for '$pageId': ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "HeavyPageHostManager"
        const val ACTION_PAGE_HEIGHT_CHANGED = "page_height_changed"

        @Volatile
        private var instance: HeavyPageHostManager? = null

        fun getInstance(context: Context): HeavyPageHostManager {
            return instance ?: synchronized(this) {
                instance ?: HeavyPageHostManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
