package com.example.feature.sidebar

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.example.core.HandleManager
import com.example.core.PageManager
import com.example.core.SidebarPage
import kotlinx.coroutines.CoroutineScope

/**
 * SidebarPageFactory: Factory and lifecycle manager for instantiating Sidebar page views.
 *
 * Extracts page creation out of SidebarView to establish a clean boundary between the
 * high-level Sidebar coordinator (window, IME, keyboard, sizing, gestures) and the
 * dedicated page / element runtime.
 */
interface SidebarPageFactory {
    fun createPageView(
        context: Context,
        config: SidebarPage,
        physicalHandleId: String,
        containerId: String,
        viewScope: CoroutineScope,
        onClose: () -> Unit,
        setDimmed: (Boolean) -> Unit,
        onHeightChanged: (Int) -> Unit
    ): View

    fun destroy()
}

/**
 * Default implementation of SidebarPageFactory creating existing modular page views.
 */
class DefaultSidebarPageFactory(
    private val prefs: SharedPreferences
) : SidebarPageFactory {

    private val appsManagers = mutableMapOf<String, SidebarAppsManager>()

    override fun createPageView(
        context: Context,
        config: SidebarPage,
        physicalHandleId: String,
        containerId: String,
        viewScope: CoroutineScope,
        onClose: () -> Unit,
        setDimmed: (Boolean) -> Unit,
        onHeightChanged: (Int) -> Unit
    ): View {
        return when (config.type) {
            "calculator" -> HeavyPagePlaceholderView(context, config, containerId) { newHeight ->
                onHeightChanged(newHeight)
            }
            "compass" -> CompassPageView(context) { newHeight ->
                onHeightChanged(newHeight)
            }
            "apps", "apps_grid" -> {
                val prefKey = "sidebar_apps_${containerId}_${config.id}"
                if (!prefs.contains(prefKey)) {
                    val legacyHandleKey = "sidebar_apps_${physicalHandleId}_${config.id}"
                    val legacyVal = prefs.getString(legacyHandleKey, null)
                        ?: if (containerId == "sidebar" || containerId == "handle_1_swipe_left") prefs.getString("sidebar_apps_${config.id}", null) else null
                    if (legacyVal != null) {
                        prefs.edit().putString(prefKey, legacyVal).apply()
                    }
                }
                val manager = appsManagers.getOrPut(prefKey) {
                    SidebarAppsManager(context, prefs, viewScope, prefKey) {}
                }
                manager.ensureLoaded()
                val p = AppsPageView(
                    context, physicalHandleId, config, manager, viewScope,
                    onCloseSidebar = { onClose() },
                    onDimSidebar = { dimmed -> setDimmed(dimmed) },
                    onHeightChanged = { newHeight -> onHeightChanged(newHeight) },
                    containerId = containerId
                )
                p.updateData(manager.activeItems)
                p
            }
            "hybrid_grid", "default_hybrid", "tools" -> {
                if (isResidentDefaultHybrid(context, config, physicalHandleId, containerId)) {
                    HybridGridPageView(
                        context, "default_hybrid", viewScope, containerId,
                        onClose = { onClose() },
                        onDimSidebar = { dimmed -> setDimmed(dimmed) }
                    ) { newHeight ->
                        onHeightChanged(newHeight)
                    }
                } else {
                    HeavyPagePlaceholderView(context, config, containerId) { newHeight ->
                        onHeightChanged(newHeight)
                    }
                }
            }
            "widgets_grid", "widgets" -> {
                WidgetsGridPageView(context, config.id, viewScope, containerId) { newHeight ->
                    onHeightChanged(newHeight)
                }
            }
            "app_tracker" -> {
                AppTrackerPageView(
                    context,
                    containerId = containerId,
                    onCloseSidebar = { onClose() },
                    onAppSelected = { pkgName ->
                        try {
                            val pm = context.packageManager
                            val launchIntent = pm.getLaunchIntentForPackage(pkgName)
                            if (launchIntent != null) {
                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(launchIntent)
                            } else {
                                val detailsIntent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = android.net.Uri.parse("package:$pkgName")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(detailsIntent)
                            }
                        } catch (_: Exception) {}
                        onClose()
                    },
                    onHeightChanged = { newHeight -> onHeightChanged(newHeight) }
                )
            }
            "media_player" -> {
                MediaPlayerPageView(context, onCloseSidebar = { onClose() }) { newHeight ->
                    onHeightChanged(newHeight)
                }
            }
            "widget", "single_widget" -> {
                WidgetPageView(context, config.id) { newHeight ->
                    onHeightChanged(newHeight)
                }
            }
            "scheduler" -> SchedulerPageView(context, viewScope) { newHeight ->
                onHeightChanged(newHeight)
            }
            "notifications", "notification" -> NotificationPageView(
                context,
                onCloseSidebar = { onClose() },
                onHideApp = { /* No-op */ }
            ) { newHeight ->
                onHeightChanged(newHeight)
            }
            "resources_tracker" -> ResourcesTrackerPageView(context, viewScope) { newHeight ->
                onHeightChanged(newHeight)
            }
            else -> {
                TextView(context).apply {
                    text = "Page: ${config.title}\nType: ${config.type}\n(Not Implemented)"
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
            }
        }
    }

    override fun destroy() {
        appsManagers.values.forEach { it.destroy() }
        appsManagers.clear()
    }

    companion object {
        /**
         * Enforces the Main/Heavy Sidebar split residency rule for Home Grid:
         *
         * MAIN owns exactly ONE resident page:
         * Handle 1 -> primary/default gesture (Swipe Left) -> first/default container -> default_hybrid.
         *
         * Every other Home Grid (any secondary Home Grid, generated hybrid_grid_*,
         * Home Grid on Handle 2+, Home Grid on another gesture, Home Grid on another container)
         * belongs to :heavy and is represented by HeavyPagePlaceholderView.
         */
        fun isResidentDefaultHybrid(
            context: Context,
            config: SidebarPage,
            physicalHandleId: String,
            containerId: String
        ): Boolean {
            if (config.id != "default_hybrid") return false
            val cleanHandleId = PageManager.getCleanHandleId(
                if (physicalHandleId.isNotBlank()) physicalHandleId else containerId
            )
            if (cleanHandleId != "handle_1" && cleanHandleId != "sidebar") {
                return false
            }
            return containerId == "sidebar" ||
                    containerId == "handle_1" ||
                    containerId == "handle_1_swipe_left"
        }
    }
}
