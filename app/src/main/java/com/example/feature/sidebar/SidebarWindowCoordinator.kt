package com.example.feature.sidebar

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.example.core.FloatingWindowManager
import com.example.core.LogKeeper
import com.example.util.HandleEdge

/**
 * SidebarWindowCoordinator: Main-process coordinator managing the overlay window lifecycle
 * for the Sidebar container UI via the authoritative [FloatingWindowManager].
 *
 * Guarantees:
 * 1. Delegates overlay attachment, Z-ordering, and hierarchy to [FloatingWindowManager].
 * 2. Guaranteed Main-thread synchronization for all View hierarchy operations.
 * 3. Prevents duplicate Sidebar windows or views from accumulating.
 * 4. Observes overlay permission before attaching views.
 * 5. Clean teardown and memory reclamation upon dismissal.
 */
class SidebarWindowCoordinator private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val floatingWindowManager: FloatingWindowManager =
        FloatingWindowManager.getInstance(appContext)
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    private var activeSidebarWindow: SidebarWindow? = null
    private var activeEdge: HandleEdge? = null

    /**
     * Attaches and displays the Sidebar overlay for the active container state and docking edge.
     */
    fun showSidebar(state: SidebarRuntimeState, edge: HandleEdge) {
        runOnMain {
            if (!canDrawOverlays()) {
                LogKeeper.log(appContext, TAG, "Cannot attach SidebarWindow: overlay permission not granted.")
                return@runOnMain
            }

            val currentWindow = activeSidebarWindow
            val currentView = currentWindow?.sidebarView

            // If already attached with matching edge, update state in place and bring to front
            if (currentWindow != null && currentView != null && activeEdge == edge && currentWindow.isAttached) {
                currentView.updateState(state)
                floatingWindowManager.bringToFront(SidebarWindow.WINDOW_ID)
                return@runOnMain
            }

            // If edge changed or previously attached, detach cleanly first to avoid duplicate windows
            detachSidebarImmediate()

            try {
                val window = SidebarWindow(
                    windowId = SidebarWindow.WINDOW_ID,
                    context = appContext,
                    edge = edge,
                    physicalHandleId = state.handleId,
                    containerId = state.containerId,
                    defaultPageIndex = state.currentPageIndex,
                    onCloseRequested = {
                        closeSidebar()
                    }
                )

                activeSidebarWindow = window
                activeEdge = edge

                floatingWindowManager.registerWindow(window)
                floatingWindowManager.showWindow(window.windowId)
                floatingWindowManager.bringToFront(window.windowId)

                window.sidebarView?.updateState(state)
                window.sidebarView?.animateIn()
                LogKeeper.log(appContext, TAG, "SidebarWindow attached successfully via FloatingWindowManager for container=${state.containerId}, edge=$edge")
            } catch (e: Exception) {
                LogKeeper.logError(appContext, TAG, "Failed to attach SidebarWindow overlay", e)
                activeSidebarWindow = null
                activeEdge = null
            }
        }
    }

    /**
     * Updates the currently displayed Sidebar view with new runtime state.
     */
    fun updateSidebar(state: SidebarRuntimeState) {
        runOnMain {
            val window = activeSidebarWindow
            if (window != null && window.isAttached) {
                window.sidebarView?.updateState(state)
            }
        }
    }

    /**
     * Closes the Sidebar overlay with slide-out animation and detaches cleanly.
     */
    fun closeSidebar() {
        runOnMain {
            val window = activeSidebarWindow ?: return@runOnMain
            val view = window.sidebarView ?: return@runOnMain
            if (!window.isAttached) return@runOnMain

            view.closeWithAnimation {
                detachSidebarImmediate()
            }
        }
    }

    /**
     * Immediately unregisters and detaches the active view via FloatingWindowManager without animation.
     */
    fun detachSidebarImmediate() {
        runOnMain {
            val window = activeSidebarWindow
            if (window != null) {
                try {
                    floatingWindowManager.unregisterWindow(window.windowId)
                    LogKeeper.log(appContext, TAG, "SidebarWindow unregistered from FloatingWindowManager.")
                } catch (e: Exception) {
                    LogKeeper.logError(appContext, TAG, "Failed to unregister SidebarWindow", e)
                }
                activeSidebarWindow = null
                activeEdge = null
            }
        }
    }

    fun isSidebarVisible(): Boolean = activeSidebarWindow?.isAttached == true && activeSidebarWindow?.sidebarView != null

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(appContext)
        } else {
            true
        }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    companion object {
        private const val TAG = "SidebarWindowCoordinator"

        @Volatile
        private var instance: SidebarWindowCoordinator? = null

        fun getInstance(context: Context): SidebarWindowCoordinator {
            return instance ?: synchronized(this) {
                instance ?: SidebarWindowCoordinator(context.applicationContext).also { instance = it }
            }
        }
    }
}
