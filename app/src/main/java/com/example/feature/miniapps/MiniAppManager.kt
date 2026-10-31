package com.example.feature.miniapps

import android.content.Context
import com.example.feature.floating.FloatingMiniAppBridge

/**
 * MiniAppManager: Clean facade entry point for triggering and toggling floating mini-apps
 * from Sidebar elements, Hybrid Grid items, Apps grid items, and gesture handlers.
 */
object MiniAppManager {
    fun toggleApp(context: Context, pageType: String) {
        FloatingMiniAppBridge.getInstance(context).toggleMiniApp(pageType)
    }
}
