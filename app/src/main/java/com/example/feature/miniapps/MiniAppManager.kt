package com.example.feature.miniapps

import android.content.Context
import com.example.core.LogKeeper
import com.example.feature.floating.FloatingMiniAppBridge

/**
 * MiniAppManager: Clean facade entry point for triggering and toggling floating mini-apps
 * from Sidebar elements, Hybrid Grid items, Apps grid items, and gesture handlers.
 */
object MiniAppManager {
    fun toggleApp(context: Context, pageType: String, instanceId: String? = null) {
        val caller = Throwable().stackTrace.getOrNull(1)?.let { "${it.className}.${it.methodName}:${it.lineNumber}" } ?: "unknown"
        LogKeeper.log(
            context,
            "MiniAppLaunchRouting",
            "ROUTING_DIAG: MINI_APP_MANAGER_TOGGLE pageType=$pageType instanceId=$instanceId caller=$caller"
        )
        FloatingMiniAppBridge.getInstance(context).toggleMiniApp(pageType, instanceId)
    }
}
