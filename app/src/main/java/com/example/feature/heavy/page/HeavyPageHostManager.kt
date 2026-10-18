package com.example.feature.heavy.page

import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import com.example.core.LogKeeper
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyEvent
import com.example.core.ipc.HeavyEventType
import com.example.core.ipc.HeavyProcessHost
import com.example.core.ipc.IpcErrorCode
import com.example.core.ipc.IpcResult
import com.example.feature.sidebar.CalculatorPageView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * HeavyPageHostManager: Heavy-process runtime manager for hosting non-home Sidebar page views.
 *
 * Runs exclusively in :heavy.
 * When Main selects a Heavy-owned page (e.g. Calculator), HeavyPageHostManager:
 * 1. Instantiates the real PageView (e.g. CalculatorPageView) using Heavy's Context.
 * 2. On API 30+, embeds it via SurfaceControlViewHost without any TYPE_APPLICATION_OVERLAY window.
 * 3. On legacy API 26-29, mounts it into a dedicated TYPE_APPLICATION_OVERLAY window.
 * 4. Keeps Calculator fully interactive (touches handled in :heavy) without polluting Main process heap.
 * 5. Unmounts and tears down cleanly when navigated away or when Sidebar closes.
 */
class HeavyPageHostManager private constructor(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeWindows = ConcurrentHashMap<String, PageWindowHolder>()
    private val activeEmbeddedHosts = ConcurrentHashMap<String, EmbeddedHostHolder>()
    private val lastReportedHeights = ConcurrentHashMap<String, Int>()

    private data class PageWindowHolder(
        val pageId: String,
        val pageType: String,
        val containerView: FrameLayout,
        val layoutParams: WindowManager.LayoutParams,
        var isAttached: Boolean = false,
        var isRevealed: Boolean = false
    )

    private class EmbeddedHostHolder(
        val pageId: String,
        val pageType: String,
        val host: Any, // SurfaceControlViewHost on API 30+
        val pageView: View,
        var width: Int,
        var height: Int
    )

    fun getActivePageCount(): Int = activeWindows.size + activeEmbeddedHosts.size

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
        val readyToShow = command.payload["readyToShow"] == "true"

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
                    updateBoundsInternal(existing, x, y, width, height, readyToShow)
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

                container.addView(pageView)

                val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

                // If not ready to show, maintain initial hidden bootstrap state (invisible and non-touchable)
                if (!readyToShow) {
                    container.visibility = View.INVISIBLE
                    container.alpha = 0f
                    flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }

                val lp = WindowManager.LayoutParams(
                    width,
                    height,
                    windowType,
                    flags,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    this.x = x
                    this.y = y
                }

                windowManager.addView(container, lp)
                val holder = PageWindowHolder(pageId, pageType, container, lp, isAttached = true, isRevealed = readyToShow)
                activeWindows[pageId] = holder
                if (readyToShow) {
                    LogKeeper.log(context, TAG, "Mounted page '$pageId' ($pageType) at ($x, $y) size ${width}x$height in :heavy")
                } else {
                    LogKeeper.log(context, TAG, "Mounted hidden bootstrap page '$pageId' ($pageType) at ($x, $y) size ${width}x$height in :heavy")
                }
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
        val readyToShow = command.payload["readyToShow"] == "true"

        mainHandler.post {
            val holder = activeWindows[pageId] ?: return@post
            updateBoundsInternal(holder, x, y, width, height, readyToShow)
        }

        return IpcResult.success("Bounds updated")
    }

    private fun updateBoundsInternal(holder: PageWindowHolder, x: Int, y: Int, width: Int, height: Int, readyToShow: Boolean): Boolean {
        if (!holder.isAttached) return false
        return try {
            var layoutChanged = false
            if (holder.layoutParams.x != x || holder.layoutParams.y != y) {
                holder.layoutParams.x = x
                holder.layoutParams.y = y
                layoutChanged = true
            }
            if (width > 0 && holder.layoutParams.width != width) {
                holder.layoutParams.width = width
                layoutChanged = true
            }
            if (height > 0 && holder.layoutParams.height != height) {
                holder.layoutParams.height = height
                layoutChanged = true
            }
            // Reveal if readyToShow is requested and page was previously hidden
            if (readyToShow && !holder.isRevealed) {
                holder.isRevealed = true
                holder.containerView.visibility = View.VISIBLE
                holder.containerView.alpha = 1f
                holder.layoutParams.flags = holder.layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                layoutChanged = true
                LogKeeper.log(context, TAG, "Revealed existing page '${holder.pageId}' in :heavy")
            }

            if (layoutChanged) {
                windowManager.updateViewLayout(holder.containerView, holder.layoutParams)
                LogKeeper.log(context, TAG, "Applied final bounds for '${holder.pageId}' to ($x, $y) ${width}x$height in :heavy (isRevealed=${holder.isRevealed})")
            } else {
                LogKeeper.log(context, TAG, "Bounds unchanged for '${holder.pageId}' ($x, $y) ${width}x$height in :heavy; update suppressed")
            }
            layoutChanged
        } catch (e: Throwable) {
            LogKeeper.logCrash(context, TAG, e)
            false
        }
    }

    private fun revealPageInternal(holder: PageWindowHolder) {
        holder.isRevealed = true
        holder.containerView.visibility = View.VISIBLE
        holder.containerView.alpha = 1f
        holder.layoutParams.flags = holder.layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        windowManager.updateViewLayout(holder.containerView, holder.layoutParams)
        LogKeeper.log(context, TAG, "Revealed existing page '${holder.pageId}' in :heavy")
    }

    // Testing / internal hooks
    internal fun isPageRevealed(pageId: String): Boolean = activeWindows[pageId]?.isRevealed ?: false
    internal fun isPageAttached(pageId: String): Boolean = activeWindows[pageId]?.isAttached ?: false
    internal fun isEmbeddedHostActive(pageId: String): Boolean = activeEmbeddedHosts.containsKey(pageId)

    /**
     * Creates a SurfaceControlViewHost in :heavy and returns a SurfacePackage in a Bundle (API 30+).
     */
    fun createSurfacePackage(params: Bundle): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            LogKeeper.log(context, TAG, "createSurfacePackage not supported on SDK < 30")
            return null
        }
        return createSurfacePackageApi30(params)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun createSurfacePackageApi30(params: Bundle): Bundle? {
        val pageId = params.getString("pageId") ?: ""
        val pageType = params.getString("pageType") ?: ""
        val hostToken = params.getBinder("hostToken")
        val displayId = params.getInt("displayId", android.view.Display.DEFAULT_DISPLAY)
        val width = params.getInt("width", 0)
        val height = params.getInt("height", 0)
        val backgroundColor = params.getInt("backgroundColor", 0)
        val isRight = params.getBoolean("isRight", true)

        if (pageId.isEmpty() || hostToken == null || width <= 0 || height <= 0) {
            LogKeeper.log(context, TAG, "Invalid createSurfacePackage params: pageId=$pageId, token=$hostToken, w=$width, h=$height")
            return null
        }

        // Idempotency: if already created, update bounds and return existing SurfacePackage
        val existing = activeEmbeddedHosts[pageId]
        if (existing != null) {
            LogKeeper.log(context, TAG, "Embedded host for '$pageId' already exists; updating dimensions ${width}x$height")
            mainHandler.post {
                try {
                    (existing.host as? android.view.SurfaceControlViewHost)?.relayout(width, height)
                    if (backgroundColor != 0) {
                        (existing.pageView as? CalculatorPageView)?.applyThemeBackground(backgroundColor, isRight)
                    }
                } catch (e: Throwable) {
                    LogKeeper.logCrash(context, TAG, e)
                }
            }
            val sp = (existing.host as? android.view.SurfaceControlViewHost)?.surfacePackage
            return Bundle().apply {
                putBoolean("success", true)
                putParcelable("surfacePackage", sp)
            }
        }

        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
        val display = displayManager?.getDisplay(displayId)
        if (display == null) {
            LogKeeper.log(context, TAG, "Cannot find displayId=$displayId for embedded page '$pageId'")
            return null
        }

        var resultBundle: Bundle? = null
        val latch = CountDownLatch(1)

        mainHandler.post {
            try {
                LogKeeper.log(context, TAG, "Creating SurfaceControlViewHost for '$pageId' ($pageType) size ${width}x$height in :heavy")
                val host = android.view.SurfaceControlViewHost(context, display, hostToken)
                val pageView: View = when (pageType) {
                    "calculator" -> {
                        CalculatorPageView(context) { newH ->
                            reportDesiredPageHeight(pageId, newH)
                        }.apply {
                            if (backgroundColor != 0) {
                                applyThemeBackground(backgroundColor, isRight)
                            }
                        }
                    }
                    else -> {
                        LogKeeper.log(context, TAG, "Unsupported embedded page type '$pageType'")
                        latch.countDown()
                        return@post
                    }
                }

                host.setView(pageView, width, height)
                val surfacePackage = host.surfacePackage
                val holder = EmbeddedHostHolder(pageId, pageType, host, pageView, width, height)
                activeEmbeddedHosts[pageId] = holder

                resultBundle = Bundle().apply {
                    putBoolean("success", true)
                    putParcelable("surfacePackage", surfacePackage)
                }
                LogKeeper.log(context, TAG, "SurfaceControlViewHost created and SurfacePackage generated for '$pageId'")
            } catch (e: Throwable) {
                LogKeeper.logCrash(context, TAG, e)
            } finally {
                latch.countDown()
            }
        }

        try {
            latch.await(1500L, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            LogKeeper.logCrash(context, TAG, e)
        }

        return resultBundle
    }

    /**
     * Updates dimensions of an active SurfaceControlViewHost without destroying/recreating the view.
     */
    fun relayoutSurfaceHost(pageId: String, width: Int, height: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val holder = activeEmbeddedHosts[pageId] ?: return false
        if (width <= 0 || height <= 0) return false
        if (holder.width == width && holder.height == height) return true
        holder.width = width
        holder.height = height

        mainHandler.post {
            try {
                LogKeeper.log(context, TAG, "Relayouting SurfaceControlViewHost for '$pageId' to ${width}x$height in :heavy")
                (holder.host as? android.view.SurfaceControlViewHost)?.relayout(width, height)
            } catch (e: Throwable) {
                LogKeeper.logCrash(context, TAG, e)
            }
        }
        return true
    }

    /**
     * Releases an active SurfaceControlViewHost and tears down its View hierarchy.
     */
    fun releaseSurfaceHost(pageId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val holder = activeEmbeddedHosts.remove(pageId) ?: return false
        lastReportedHeights.remove(pageId)

        mainHandler.post {
            try {
                LogKeeper.log(context, TAG, "Releasing SurfaceControlViewHost for '$pageId' in :heavy")
                (holder.host as? android.view.SurfaceControlViewHost)?.release()
            } catch (e: Throwable) {
                LogKeeper.logCrash(context, TAG, e)
            }
        }
        return true
    }

    /**
     * Unmounts and tears down an active Heavy page window.
     */
    fun unmountPage(command: HeavyCommand): IpcResult {
        val pageId = command.payload["pageId"] ?: command.targetId ?: ""
        mainHandler.post {
            unmountPageInternal(pageId)
            releaseSurfaceHost(pageId)
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
            val embeddedKeys = ArrayList(activeEmbeddedHosts.keys)
            for (key in embeddedKeys) {
                releaseSurfaceHost(key)
            }
        }
    }

    private fun unmountPageInternal(pageId: String) {
        lastReportedHeights.remove(pageId)
        val holder = activeWindows.remove(pageId) ?: return
        if (holder.isAttached) {
            try {
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
