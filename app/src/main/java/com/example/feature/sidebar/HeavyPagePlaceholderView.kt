package com.example.feature.sidebar

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import com.example.core.LogKeeper
import com.example.core.SidebarPage
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyCommandType
import com.example.core.ipc.HeavyProcessConnectionManager
import java.util.UUID

/**
 * HeavyPagePlaceholderView: Lightweight Main-process placeholder for pages hosted in :heavy (e.g. Calculator).
 *
 * Runs exclusively in the Main process (com.example).
 * Responsibilities:
 * 1. Occupies the ViewPager2 page slot with standard width/height and layout parameters.
 * 2. Emits zero heavy allocations (no calculator evaluation engine, no heavy views).
 * 3. On page selection, calculates its precise on-screen coordinates (accounting for Sidebar overlay position)
 *    and issues a MOUNT_PAGE IPC command to :heavy via HeavyProcessConnectionManager.
 * 4. Listens for bounds/layout shifts and dispatches UPDATE_PAGE_BOUNDS to keep the Heavy window aligned.
 * 5. On page unselection or detach, issues an UNMOUNT_PAGE IPC command to cleanly tear down the Heavy window.
 */
class HeavyPagePlaceholderView(
    context: Context,
    private val config: SidebarPage,
    private val containerId: String,
    private val onHeightChanged: ((Int) -> Unit)? = null
) : FrameLayout(context), SidebarPageControllable {

    private val connectionManager = HeavyProcessConnectionManager.getInstance(context)
    private var isSelected = false
    private val lastDispatchedRect = Rect()
    private val consumerTag = "HeavyPage_${config.id}_${UUID.randomUUID().toString().take(6)}"

    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // Visual subtle placeholder in Main beneath the transparent Heavy overlay
        val placeholderText = TextView(context).apply {
            text = ""
            setTextColor(Color.TRANSPARENT)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(placeholderText)

        // Initial default height report (calibrated for standard Calculator 260dp)
        post {
            val density = resources.displayMetrics.density
            val defaultHeight = (260 * density).toInt()
            onHeightChanged?.invoke(defaultHeight)
        }
    }

    override fun onPageSelected() {
        isSelected = true
        LogKeeper.log(context, TAG, "Calculator placeholder onPageSelected for '${config.id}' in Main")

        // Acquire connection to Heavy on demand
        connectionManager.acquireConsumer(consumerTag)

        // Wait for layout so global coordinates can be accurately determined
        post {
            if (isSelected) {
                dispatchMountCommand()
                setupLayoutMonitoring()
            }
        }
    }

    override fun onPageUnselected() {
        isSelected = false
        LogKeeper.log(context, TAG, "Calculator placeholder onPageUnselected for '${config.id}' in Main")
        removeLayoutMonitoring()
        dispatchUnmountCommand()
        connectionManager.releaseConsumer(consumerTag)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (isSelected) {
            onPageUnselected()
        }
    }

    private fun setupLayoutMonitoring() {
        removeLayoutMonitoring()
        val observer = viewTreeObserver
        if (observer.isAlive) {
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                if (isSelected) {
                    dispatchBoundsUpdateIfChanged()
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

    private fun dispatchMountCommand() {
        val rect = getCurrentScreenRect() ?: run {
            // If not yet measured, retry shortly
            postDelayed({
                if (isSelected) dispatchMountCommand()
            }, 50L)
            return
        }

        lastDispatchedRect.set(rect)

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
        LogKeeper.log(context, TAG, "Dispatched MOUNT_PAGE for '${config.id}' to :heavy. Result: ${result.success}")
    }

    private fun dispatchBoundsUpdateIfChanged() {
        val rect = getCurrentScreenRect() ?: return
        if (rect == lastDispatchedRect) return

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

    companion object {
        private const val TAG = "HeavyPagePlaceholder"
    }
}
