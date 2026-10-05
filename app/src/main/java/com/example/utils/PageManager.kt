package com.example.utils

import android.content.Context
import android.content.SharedPreferences
import com.example.core.HandleManager
import com.example.core.OverlaySyncManager
import com.example.core.PageManager as CorePageManager
import com.example.core.SidebarPage as CoreSidebarPage

/**
 * Backward-compatibility typealias pointing to the authoritative SidebarPage model in com.example.core.
 */
typealias SidebarPage = CoreSidebarPage

/**
 * Backward-compatibility adapter for legacy PageManager callers.
 * Delegates 100% of page loading, parsing, and persistence to the authoritative com.example.core.PageManager.
 */
object PageManager {

    fun getCleanHandleId(handleOrContainerId: String): String =
        HandleManager.getCleanHandleId(handleOrContainerId)

    /**
     * Retrieves the isolated list of pages for a specific container.
     * Delegates to authoritative CorePageManager parsing logic.
     */
    fun getPages(prefs: SharedPreferences, rawHandleId: String): List<SidebarPage> {
        return CorePageManager.parsePagesFromPrefs(prefs, rawHandleId)
    }

    /**
     * Saves pages to SharedPreferences.
     */
    fun savePages(prefs: SharedPreferences, rawHandleId: String, pages: List<SidebarPage>, context: Context? = null) {
        if (context != null) {
            CorePageManager.getInstance(context).savePageStack(rawHandleId, pages)
        } else {
            CorePageManager.savePagesToPrefs(prefs, rawHandleId, pages)
        }
    }

    fun getDefaultPageIndex(prefs: SharedPreferences, rawHandleId: String): Int {
        val cleanHandleId = getCleanHandleId(rawHandleId)
        return prefs.getInt(
            "handle_${rawHandleId}_default_page_index",
            prefs.getInt("handle_${cleanHandleId}_default_page_index", prefs.getInt("sidebar_default_page_index", 0))
        )
    }

    fun saveDefaultPageIndex(prefs: SharedPreferences, rawHandleId: String, index: Int, context: Context? = null) {
        prefs.edit().putInt("handle_${rawHandleId}_default_page_index", index).apply()
        if (context != null) {
            OverlaySyncManager.syncInt(context, "handle_${rawHandleId}_default_page_index", index)
        }
    }

    fun isPageTypePresent(prefs: SharedPreferences, pageType: String): Boolean {
        for ((key, value) in prefs.all) {
            if (value is String && (key.endsWith("_pages") || key == "sidebar_pages" || key.contains("pages") || key.contains("handle"))) {
                if (value.contains("\"$pageType\"") || value.contains(":$pageType") || value.contains("/$pageType") || value.contains(pageType)) {
                    return true
                }
            }
        }
        return false
    }
}
