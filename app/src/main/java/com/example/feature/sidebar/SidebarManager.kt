package com.example.feature.sidebar

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.WindowManager
import com.example.core.FloatingWindowManager
import com.example.util.AppLogger
import com.example.utils.PageManager

class SidebarManager(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val windowManager: WindowManager,
    private val onClosed: (() -> Unit)? = null
) {
    private var sidebarView: SidebarView? = null
    
    fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val handleId = intent.getStringExtra("handleId") ?: "sidebar"
        val gesture = intent.getStringExtra("gesture") ?: "swipe_left"
        val containerId = intent.getStringExtra("containerId") ?: "${handleId}_${gesture}"
        
        AppLogger.d("SidebarManager", "handleIntent: action=$action handleId=$handleId gesture=$gesture containerId=$containerId")
        
        when (action) {
            "com.example.ACTION_CLOSE_SIDEBAR", "CLOSE_SIDEBAR" -> {
                closeSidebar()
            }
            "com.example.ACTION_TOGGLE_SIDEBAR" -> {
                toggleSidebar(handleId, containerId)
            }
            "com.example.ACTION_OPEN_PAGE" -> {
                val pageId = intent.getStringExtra("pageType")
                toggleSidebar(handleId, containerId, pageId)
            }
            "com.example.ACTION_EXECUTE_ELEMENT", "EXECUTE_ACTION" -> {
                val actionId = intent.getStringExtra("ACTION_ID") ?: intent.getStringExtra("elementId") ?: return
                AppLogger.d("SidebarManager", "Execute action/element: $actionId")
                com.example.core.LogKeeper.writeLog("SidebarManager", "Execute action: $actionId")
                when (actionId) {
                    "system:force_stop_running_apps", "force_stop_running_apps" -> {
                        closeSidebar()
                        com.example.utils.AppTrackerHelper.startForceStopSequence(context)
                    }
                    "system:dictionary_floating", "system:dictionary_full" -> {
                        closeSidebar()
                        com.example.feature.miniapps.MiniAppManager.toggleApp(context, "dictionary")
                    }
                    "system:translation_floating" -> {
                        closeSidebar()
                        com.example.feature.miniapps.MiniAppManager.toggleApp(context, "translation")
                    }
                    "system:hybrid_grid_floating" -> {
                        closeSidebar()
                        com.example.feature.miniapps.MiniAppManager.toggleApp(context, "hybrid_grid")
                    }
                    "system:audio_record", "audio_record" -> {
                        closeSidebar()
                        com.example.feature.system_hub.AudioRecordFloatingPanel.toggle(context)
                    }
                    else -> {
                        val isSystemAction = actionId.startsWith("system:")
                        val systemActionKey = actionId.removePrefix("system:")
                        val accessibilityService = com.example.feature.system_hub.VianSideAccessibilityService.instance
                        if (isSystemAction && accessibilityService != null && accessibilityService.performAction(systemActionKey)) {
                            com.example.core.LogKeeper.writeLog("SidebarManager", "Handled system action via Accessibility: $systemActionKey")
                            closeSidebar()
                        } else if (actionId.endsWith("_floating") || actionId.startsWith("page_window:")) {
                            val pageType = actionId.removePrefix("page_window:").removePrefix("system:").removeSuffix("_floating")
                            closeSidebar()
                            com.example.feature.miniapps.MiniAppManager.toggleApp(context, pageType)
                        } else {
                            com.example.core.LogKeeper.writeLog("SidebarManager", "Ignored unrecognized action: $actionId")
                        }
                    }
                }
            }
        }
    }
    
    private fun toggleSidebar(physicalHandleId: String, containerId: String, targetPageId: String? = null) {
        if (sidebarView?.windowToken != null && sidebarView?.containerId == containerId) {
            closeSidebar()
        } else {
            showSidebar(physicalHandleId, containerId, targetPageId)
        }
    }
    
    private fun showSidebar(physicalHandleId: String, containerId: String, targetPageId: String? = null) {
        sidebarView?.detach()
        sidebarView = null
        
        // Fetch pages strictly for this isolated container
        val pages = PageManager.getPages(prefs, containerId).toMutableList()
        val cleanTargetId = targetPageId?.removePrefix("open_page:")
        
        var targetIndex = 0
        if (cleanTargetId != null) {
            val effectiveTarget = if (cleanTargetId.startsWith("default_hybrid")) "hybrid_grid" else cleanTargetId
            val idx = pages.indexOfFirst { 
                it.id == cleanTargetId || it.type == cleanTargetId || it.type == effectiveTarget || (effectiveTarget == "hybrid_grid" && (it.id.startsWith("default_hybrid") || it.type == "hybrid_grid"))
            }
            if (idx != -1) {
                targetIndex = idx
            } else {
                val pageTitle = when(effectiveTarget) {
                    "apps" -> "Apps Grid"
                    "widgets_grid" -> "Widgets Grid"
                    "hybrid_grid" -> "Home Grid"
                    "app_tracker" -> "App Tracker"
                    "resources_tracker" -> "Resources Tracker"
                    "media_player" -> "Media Player"
                    "calculator" -> "Calculator"
                    "scheduler" -> "Short Reminders"
                    "compass" -> "Compass"
                    "notifications", "notification" -> "Notifications"
                    else -> effectiveTarget.replace("_", " ").replaceFirstChar { it.uppercase() }
                }
                val newPage = com.example.utils.SidebarPage.createDefault(
                    id = if (effectiveTarget == "hybrid_grid") "default_hybrid_$containerId" else java.util.UUID.randomUUID().toString(),
                    type = effectiveTarget,
                    title = pageTitle
                )
                // If it was a default placeholder container, replace it; otherwise prepend as the face
                if (pages.size == 1 && pages[0].type == "hybrid_grid" && effectiveTarget != "hybrid_grid" && !prefs.contains("handle_${containerId}_pages")) {
                    pages.clear()
                    pages.add(newPage)
                    targetIndex = 0
                } else {
                    pages.add(0, newPage)
                    targetIndex = 0
                }
                PageManager.savePages(prefs, containerId, pages)
            }
        } else {
            targetIndex = PageManager.getDefaultPageIndex(prefs, containerId).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        }
        
        sidebarView = SidebarView(context, prefs, windowManager, physicalHandleId, containerId, pages, targetIndex) {
            closeSidebar()
        }
        sidebarView?.attach()
    }
    
    fun closeSidebar() {
        val current = sidebarView
        if (current != null) {
            sidebarView = null
            current.closeWithAnimation {
                current.detach()
                onClosed?.invoke()
            }
        } else {
            onClosed?.invoke()
        }
    }

    fun onTrimMemory(level: Int) {
        // Release any heavy cached resources if needed
    }
}
