package com.example.feature.sidebar

import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import com.example.core.FloatingWindow
import com.example.core.FloatingWindowManager
import com.example.core.WindowBounds
import com.example.util.HandleEdge
import com.example.utils.SidebarPage

/**
 * SidebarWindow: FloatingWindow implementation for the Sidebar Page Container overlay.
 *
 * Integrates directly with [FloatingWindowManager] to ensure:
 * 1. Authoritative Z-order tracking alongside any runtime overlay windows.
 * 2. Proper focus management and hardware touch interception.
 * 3. Safe dormant folding and memory release during onTrimMemory events.
 * 4. Window lifecycle observation through standard FloatingWindow hooks.
 */
class SidebarWindow(
    windowId: String = WINDOW_ID,
    context: Context,
    val edge: HandleEdge,
    val physicalHandleId: String = "sidebar",
    val containerId: String = "sidebar",
    private val pageConfigs: List<SidebarPage>? = null,
    private val defaultPageIndex: Int = 0,
    private val onCloseRequested: () -> Unit
) : FloatingWindow(windowId, context, TYPE_PAGE) {

    var sidebarView: SidebarView? = null
        private set

    init {
        val density = context.resources.displayMetrics.density
        val widthPx = (198 * density).toInt()
        val screenHeight = context.resources.displayMetrics.heightPixels
        val screenWidth = context.resources.displayMetrics.widthPixels

        bounds = WindowBounds(
            x = if (edge == HandleEdge.RIGHT) screenWidth - widthPx else 0,
            y = 0,
            width = widthPx,
            height = screenHeight
        )

        layoutParams.width = widthPx
        layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
        layoutParams.gravity = if (edge == HandleEdge.RIGHT)
            Gravity.END or Gravity.CENTER_VERTICAL
        else
            Gravity.START or Gravity.CENTER_VERTICAL
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        layoutParams.x = 0
        layoutParams.y = 0
    }

    override fun getOrCreateContainerView(): FrameLayout {
        if (sidebarView == null) {
            val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val pages = pageConfigs ?: com.example.utils.PageManager.getPages(prefs, containerId)
            sidebarView = SidebarView(
                context = context,
                prefs = prefs,
                windowManager = wm,
                physicalHandleId = physicalHandleId,
                containerId = containerId,
                pageConfigs = pages,
                defaultPageIndex = defaultPageIndex,
                onClose = onCloseRequested
            )
        }
        return sidebarView!!
    }

    override fun getView(): FrameLayout? = sidebarView

    override fun onDestroy() {
        sidebarView?.removeAllViews()
        sidebarView = null
        super.onDestroy()
    }

    companion object {
        const val WINDOW_ID = "sidebar_container_overlay"
    }
}
