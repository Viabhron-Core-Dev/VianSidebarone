package com.example.utils

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class SidebarPage(
    val id: String,
    val type: String,
    val title: String,
    val iconName: String = "",
    val customIconBase64: String = "",
    val isSystem: Boolean = false,
    val isAppGroup: Boolean = false,
    val appGroupPackageNames: List<String> = emptyList(),
    val isDirectAction: Boolean = false,
    val directActionKey: String = "",
    val isMiniApp: Boolean = false,
    val miniAppType: String = "",
    val useCustomSettings: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val wrapContentHeight: Boolean = true,
    val transparency: Float = 0.9f,
    val gridColumns: Int = 3,
    val stickAlignment: String = "bottom"
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("type", type)
        obj.put("title", title)
        obj.put("iconName", iconName)
        obj.put("customIconBase64", customIconBase64)
        obj.put("isSystem", isSystem)
        obj.put("isAppGroup", isAppGroup)
        val arr = JSONArray()
        appGroupPackageNames.forEach { arr.put(it) }
        obj.put("appGroupPackageNames", arr)
        obj.put("isDirectAction", isDirectAction)
        obj.put("directActionKey", directActionKey)
        obj.put("isMiniApp", isMiniApp)
        obj.put("miniAppType", miniAppType)
        obj.put("useCustomSettings", useCustomSettings)
        obj.put("width", width)
        obj.put("height", height)
        obj.put("wrapContentHeight", wrapContentHeight)
        obj.put("transparency", transparency.toDouble())
        obj.put("gridColumns", gridColumns)
        obj.put("stickAlignment", stickAlignment)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): SidebarPage {
            val appGroups = mutableListOf<String>()
            val arr = obj.optJSONArray("appGroupPackageNames")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    appGroups.add(arr.getString(i))
                }
            }
            return SidebarPage(
                id = obj.getString("id"),
                type = obj.getString("type"),
                title = obj.getString("title"),
                iconName = obj.optString("iconName", ""),
                customIconBase64 = obj.optString("customIconBase64", ""),
                isSystem = obj.optBoolean("isSystem", false),
                isAppGroup = obj.optBoolean("isAppGroup", false),
                appGroupPackageNames = appGroups,
                isDirectAction = obj.optBoolean("isDirectAction", false),
                directActionKey = obj.optString("directActionKey", ""),
                isMiniApp = obj.optBoolean("isMiniApp", false),
                miniAppType = obj.optString("miniAppType", ""),
                useCustomSettings = obj.optBoolean("useCustomSettings", false),
                width = obj.optInt("width", 0),
                height = obj.optInt("height", 0),
                wrapContentHeight = obj.optBoolean("wrapContentHeight", true),
                transparency = obj.optDouble("transparency", 0.9).toFloat(),
                gridColumns = obj.optInt("gridColumns", 3),
                stickAlignment = obj.optString("stickAlignment", "bottom")
            )
        }

        fun createDefault(id: String, type: String, title: String): SidebarPage {
            return SidebarPage(
                id = id,
                type = type,
                title = title
            )
        }
    }
}

object PageManager {
    fun getCleanHandleId(handleOrContainerId: String): String {
        return when {
            handleOrContainerId.contains("_swipe_") -> handleOrContainerId.substringBefore("_swipe_")
            handleOrContainerId.endsWith("_tap") -> handleOrContainerId.removeSuffix("_tap")
            handleOrContainerId.endsWith("_double_tap") -> handleOrContainerId.removeSuffix("_double_tap")
            handleOrContainerId.endsWith("_long_press") -> handleOrContainerId.removeSuffix("_long_press")
            else -> handleOrContainerId
        }
    }

    /**
     * Retrieves the isolated list of pages for a specific container (e.g. "handle_1_swipe_left" or "sidebar_tap").
     * Each gesture container maintains its own independent page deck.
     */
    fun getPages(prefs: SharedPreferences, rawHandleId: String): List<SidebarPage> {
        val cleanHandleId = getCleanHandleId(rawHandleId)
        // Check container-specific first so each gesture maintains its isolated container
        val containerSpecificJson = prefs.getString("handle_${rawHandleId}_pages", null)
        val handlePagesJson = if (containerSpecificJson == null && rawHandleId == cleanHandleId) {
            prefs.getString("handle_${cleanHandleId}_pages", null)
                ?: if (cleanHandleId == "sidebar") prefs.getString("sidebar_pages", null) else null
        } else null

        val pagesJson = containerSpecificJson ?: handlePagesJson
        
        val defaultPageId = if (rawHandleId == "sidebar" || rawHandleId == "sidebar_swipe_left") "default_hybrid" else "default_hybrid_$rawHandleId"
        val defaultPage = SidebarPage(id = defaultPageId, type = "hybrid_grid", title = "Home Grid")
        if (!prefs.contains("hybrid_grid_" + defaultPageId)) {
            val jsonStr = "[{\"id\": \"system:ebook_reader\", \"cols\": 1, \"rows\": 1, \"x\": 0, \"y\": 0}, {\"id\": \"system:log_keeper\", \"cols\": 1, \"rows\": 1, \"x\": 1, \"y\": 0}]"
            prefs.edit().putString("hybrid_grid_" + defaultPageId, jsonStr).apply()
            prefs.edit().putInt("hybrid_grid_cols_$defaultPageId", 3).apply()
            prefs.edit().putBoolean("handle_${rawHandleId}_sidebar_wrap_content", true).apply()
        }
        if (pagesJson == null) {
            // Default setup for a fresh container
            return listOf(defaultPage)
        }
        val list = mutableListOf<SidebarPage>()
        val seenIds = mutableSetOf<String>()
        try {
            val arr = JSONArray(pagesJson)
            for (i in 0 until arr.length()) {
                val page = SidebarPage.fromJson(arr.getJSONObject(i))
                val sanitizedType = if (page.type == "default_hybrid") "hybrid_grid" else page.type
                val sanitizedTitle = if (page.id.startsWith("default_hybrid") || page.title.equals("default_hybrid", ignoreCase = true)) {
                    "Home Grid"
                } else if (page.title == "Home Grid" && !page.id.startsWith("default_hybrid")) {
                    "Hybrid"
                } else {
                    page.title
                }
                val sanitizedPage = if (sanitizedType != page.type || sanitizedTitle != page.title) {
                    page.copy(type = sanitizedType, title = sanitizedTitle)
                } else page
                
                // Only prevent duplicate default home grid if one with defaultPageId is already present
                val isDuplicateDefaultHomeGrid = (sanitizedPage.id == defaultPageId && list.any { it.id == defaultPageId })
                
                if (sanitizedPage.type != "dictionary" && sanitizedPage.type != "pwa_loader" && !isDuplicateDefaultHomeGrid && seenIds.add(sanitizedPage.id)) {
                    list.add(sanitizedPage)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return listOf(defaultPage)
        }
        
        return if (list.isEmpty()) listOf(defaultPage) else list
    }

    fun savePages(prefs: SharedPreferences, rawHandleId: String, pages: List<SidebarPage>, context: Context? = null) {
        val arr = JSONArray()
        val seenIds = mutableSetOf<String>()
        pages.filter { seenIds.add(it.id) }.forEach { arr.put(it.toJson()) }
        val json = arr.toString()
        prefs.edit().putString("handle_${rawHandleId}_pages", json).commit()
        if (context != null) {
            com.example.core.OverlaySyncManager.syncString(context, "handle_${rawHandleId}_pages", json)
        }
    }

    fun getDefaultPageIndex(prefs: SharedPreferences, rawHandleId: String): Int {
        val cleanHandleId = getCleanHandleId(rawHandleId)
        return prefs.getInt("handle_${rawHandleId}_default_page_index", prefs.getInt("handle_${cleanHandleId}_default_page_index", prefs.getInt("sidebar_default_page_index", 0)))
    }

    fun saveDefaultPageIndex(prefs: SharedPreferences, rawHandleId: String, index: Int, context: Context? = null) {
        prefs.edit().putInt("handle_${rawHandleId}_default_page_index", index).commit()
        if (context != null) {
            com.example.core.OverlaySyncManager.syncInt(context, "handle_${rawHandleId}_default_page_index", index)
        }
    }

    fun isPageTypePresent(prefs: SharedPreferences, pageType: String): Boolean {
        for ((key, value) in prefs.all) {
            if (value is String && (key.endsWith("_pages") || key == "sidebar_pages" || key.contains("pages") || key.contains("handle"))) {
                if (value.contains("\"$pageType\"") || value.contains(":$pageType") || value.contains("/$pageType")) {
                    return true
                }
                try {
                    val arr = JSONArray(value)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val type = obj.optString("type")
                        val id = obj.optString("id")
                        if (type == pageType || id.contains(pageType)) {
                            return true
                        }
                    }
                } catch (e: Exception) {}
            }
        }
        return false
    }
}
