package com.example.feature.sidebar

import android.content.Context
import android.content.Intent
import com.example.core.SidebarPage

/**
 * SidebarEditNavigator: Dedicated navigation coordinator for launching page-specific edit and
 * configuration screens from the Sidebar container.
 *
 * Extracts page editing routing out of SidebarView to maintain a clean boundary between the
 * high-level Sidebar window coordinator and individual page customization destinations.
 */
object SidebarEditNavigator {

    /**
     * Builds the explicit Intent to open the appropriate edit/configuration destination for a page.
     */
    fun createEditIntent(
        context: Context,
        pageConfig: SidebarPage,
        containerId: String = "sidebar",
        physicalHandleId: String = "handle_1"
    ): Intent {
        return when (pageConfig.type) {
            "apps" -> {
                Intent(context, com.example.SidebarEditActivity::class.java).apply {
                    putExtra("PAGE_ID", pageConfig.id)
                    putExtra("CONTAINER_ID", containerId)
                    putExtra("HANDLE_ID", physicalHandleId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            "widgets_grid" -> {
                Intent(context, com.example.WidgetsGridEditActivity::class.java).apply {
                    putExtra("PAGE_ID", pageConfig.id)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            "hybrid_grid", "default_hybrid" -> {
                Intent(context, com.example.HybridGridEditActivity::class.java).apply {
                    putExtra("PAGE_ID", pageConfig.id)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            "app_tracker" -> {
                Intent(context, com.example.AppTrackerSettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            "notifications", "notification" -> {
                Intent(context, com.example.NotificationHistoryActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            "scheduler", "short_reminders", "reminder", "reminders" -> {
                Intent(context, com.example.feature.settings.TagManagementActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            "calculator", "compass", "resources_tracker", "media_player", "widget" -> {
                Intent(context, com.example.SettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("start_route", "pages_${containerId}|edit_page:${pageConfig.id}")
                }
            }
            else -> {
                Intent(context, com.example.SettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("start_route", "pages_${containerId}")
                }
            }
        }
    }

    /**
     * Dispatches intent launch and triggers container close callback.
     */
    fun navigateToEditScreen(
        context: Context,
        pageConfig: SidebarPage,
        containerId: String = "sidebar",
        physicalHandleId: String = "handle_1",
        onClose: () -> Unit
    ) {
        val intent = createEditIntent(context, pageConfig, containerId, physicalHandleId)
        context.startActivity(intent)
        onClose()
    }
}
