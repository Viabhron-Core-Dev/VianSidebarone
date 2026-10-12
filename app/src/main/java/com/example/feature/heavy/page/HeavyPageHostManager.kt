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
                // If a window for this pageId already exists, unmount it first
                unmountPageInternal(pageId)

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
                            // Dynamic height report from Calculator drawer
                            LogKeeper.log(context, TAG, "Calculator height changed to $newH in :heavy")
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

                windowManager.addView(container, lp)
                val holder = PageWindowHolder(pageId, pageType, container, lp, isAttached = true)
                activeWindows[pageId] = holder
                LogKeeper.log(context, TAG, "Mounted page '$pageId' ($pageType) at ($x, $y) size ${width}x$height in :heavy")
            } catch (e: Throwable) {
                LogKeeper.logCrash(context, TAG, e)
            }
        }

        return IpcResult.success("Mounted $pageId")
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
            if (!holder.isAttached) return@post
            try {
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
                    LogKeeper.log(context, TAG, "Updated bounds for '$pageId' to ($x, $y) ${width}x$height in :heavy")
                }
            } catch (e: Throwable) {
                LogKeeper.logCrash(context, TAG, e)
            }
        }

        return IpcResult.success("Bounds updated")
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

        @Volatile
        private var instance: HeavyPageHostManager? = null

        fun getInstance(context: Context): HeavyPageHostManager {
            return instance ?: synchronized(this) {
                instance ?: HeavyPageHostManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
