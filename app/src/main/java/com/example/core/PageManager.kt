package com.example.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Standard known page type constants for the Sidebar runtime.
 */
object PageTypes {
    const val HYBRID = "default_hybrid"
    const val HYBRID_GRID = "hybrid_grid"
    const val APPS = "apps"
    const val WIDGETS = "widgets"
    const val WIDGETS_GRID = "widgets_grid"
    const val MEDIA = "media_player"
    const val TOOLS = "tools"
    const val APP_TRACKER = "app_tracker"
    const val CALCULATOR = "calculator"
    const val COMPASS = "compass"
    const val SCHEDULER = "scheduler"
    const val NOTIFICATIONS = "notifications"
    const val RESOURCES_TRACKER = "resources_tracker"

    val ALL_DEFAULT_TYPES = listOf(
        HYBRID_GRID,
        APPS,
        WIDGETS_GRID,
        MEDIA,
        TOOLS,
        APP_TRACKER,
        CALCULATOR,
        COMPASS,
        SCHEDULER,
        NOTIFICATIONS,
        RESOURCES_TRACKER
    )

    fun resolveDefaultTitle(pageType: String): String = when (pageType) {
        HYBRID, HYBRID_GRID -> "Home Grid"
        APPS -> "Apps"
        WIDGETS, WIDGETS_GRID, "widget" -> "Widgets"
        MEDIA -> "Media Player"
        TOOLS -> "Quick Tools"
        APP_TRACKER -> "App Tracker"
        CALCULATOR -> "Calculator"
        COMPASS -> "Compass"
        SCHEDULER -> "Scheduler"
        NOTIFICATIONS, "notification" -> "Notifications"
        RESOURCES_TRACKER -> "Resources Tracker"
        else -> pageType.replace('_', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    fun resolvePageType(pageId: String): String = when {
        pageId == HYBRID || pageId == HYBRID_GRID || pageId.contains("hybrid") -> HYBRID_GRID
        pageId == APPS || pageId.contains("apps") -> APPS
        pageId == WIDGETS || pageId == WIDGETS_GRID || pageId.contains("widget") -> WIDGETS_GRID
        pageId == MEDIA || pageId.contains("media") -> MEDIA
        pageId == TOOLS || pageId.contains("tool") -> TOOLS
        pageId == APP_TRACKER || (pageId.contains("tracker") && !pageId.contains("resource")) -> APP_TRACKER
        pageId == CALCULATOR || pageId.contains("calc") -> CALCULATOR
        pageId == COMPASS || pageId.contains("compass") -> COMPASS
        pageId == SCHEDULER || pageId.contains("schedul") || pageId.contains("reminder") -> SCHEDULER
        pageId == NOTIFICATIONS || pageId.contains("notif") -> NOTIFICATIONS
        pageId == RESOURCES_TRACKER || pageId.contains("resource") -> RESOURCES_TRACKER
        else -> pageId
    }
}

/**
 * SidebarPage: Authoritative runtime and persistent data model for a single page in a container's page stack.
 *
 * Holds identity, page type token for on-demand UI loading, human-readable title,
 * ordering position, and visual/layout configuration. Supports bidirectional JSON serialization.
 */
data class SidebarPage(
    val pageId: String,
    val pageType: String = PageTypes.resolvePageType(pageId),
    val title: String = PageTypes.resolveDefaultTitle(pageType),
    val order: Int = 0,
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
    val id: String get() = pageId
    val type: String get() = pageType
    val isFirst: Boolean get() = order == 0

    constructor(id: String, type: String, title: String) : this(
        pageId = id,
        pageType = type,
        title = title,
        order = 0
    )

    constructor(id: String, type: String, title: String, order: Int) : this(
        pageId = id,
        pageType = type,
        title = title,
        order = order
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", pageId)
        put("pageId", pageId)
        put("type", pageType)
        put("pageType", pageType)
        put("title", title)
        put("order", order)
        put("iconName", iconName)
        put("customIconBase64", customIconBase64)
        put("isSystem", isSystem)
        put("isAppGroup", isAppGroup)
        val arr = JSONArray()
        appGroupPackageNames.forEach { arr.put(it) }
        put("appGroupPackageNames", arr)
        put("isDirectAction", isDirectAction)
        put("directActionKey", directActionKey)
        put("isMiniApp", isMiniApp)
        put("miniAppType", miniAppType)
        put("useCustomSettings", useCustomSettings)
        put("width", width)
        put("height", height)
        put("wrapContentHeight", wrapContentHeight)
        put("transparency", transparency.toDouble())
        put("gridColumns", gridColumns)
        put("stickAlignment", stickAlignment)
    }

    companion object {
        fun fromJson(obj: JSONObject, orderIndex: Int = 0): SidebarPage {
            val rawId = obj.optString("id").ifEmpty { obj.optString("pageId", "") }
            val rawType = obj.optString("type").ifEmpty { obj.optString("pageType", "") }
            val sanitizedType = if (rawType == "default_hybrid") PageTypes.HYBRID_GRID else if (rawType.isNotEmpty()) rawType else PageTypes.resolvePageType(rawId)
            val rawTitle = obj.optString("title", "")
            val sanitizedTitle = if (rawTitle.isBlank() || rawTitle.equals("default_hybrid", ignoreCase = true)) {
                PageTypes.resolveDefaultTitle(sanitizedType)
            } else if (rawTitle == "Home Grid" && !rawId.startsWith("default_hybrid")) {
                "Hybrid"
            } else {
                rawTitle
            }

            val appGroups = mutableListOf<String>()
            val arr = obj.optJSONArray("appGroupPackageNames")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    appGroups.add(arr.getString(i))
                }
            }

            return SidebarPage(
                pageId = rawId,
                pageType = sanitizedType,
                title = sanitizedTitle,
                order = obj.optInt("order", orderIndex),
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

        fun createDefault(id: String, type: String = PageTypes.HYBRID_GRID, title: String = "Home Grid", order: Int = 0): SidebarPage {
            return SidebarPage(
                pageId = id,
                pageType = type,
                title = title,
                order = order
            )
        }
    }
}

/**
 * PageManager: Authoritative coordinator for container-owned page stacks in the Main runtime.
 *
 * Operates on top of Handle -> Gesture -> SidebarContainer identity.
 * Strictly persists ordered page metadata per container under "handle_${containerId}_pages"
 * with seamless dual-format migration (JSON arrays & comma-separated IDs).
 */
class PageManager internal constructor(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
    private val handleManager: HandleManager = HandleManager.getInstance(context)

    /**
     * Loads the ordered page stack for an independent container.
     * Returns a list of SidebarPage models reflecting the stored order (0-indexed).
     */
    fun getPageStack(
        containerId: String,
        defaultPageIds: List<String> = listOf(HandleManager.DEFAULT_PAGE_HYBRID)
    ): List<SidebarPage> {
        val isolatedKey = HandleManager.getContainerPagesKey(containerId)
        val cleanContainerId = getCleanHandleId(containerId)

        // 1. Primary isolated container key
        var raw = prefs.getString(isolatedKey, null)

        // 2. Fallback to clean handle key if this is primary gesture (swipe_left)
        if (raw == null && (containerId == cleanContainerId || containerId.endsWith("_swipe_left"))) {
            raw = prefs.getString("handle_${cleanContainerId}_pages", null)
                ?: if (cleanContainerId == "sidebar") prefs.getString("sidebar_pages", null) else null
        }

        val isFirstHandlePrimary = (containerId == "sidebar" || containerId == "handle_1" ||
            containerId == "handle_1_swipe_left" || containerId == handleManager.getFirstHandlePrimaryContainerId())

        val defaultPageId = if (isFirstHandlePrimary) {
            "default_hybrid"
        } else {
            "default_hybrid_$containerId"
        }

        val defaultPage = SidebarPage.createDefault(
            id = defaultPageId,
            type = PageTypes.HYBRID_GRID,
            title = "Home Grid",
            order = 0
        )

        // Ensure default hybrid grid elements exist in storage
        if (!prefs.contains("hybrid_grid_$defaultPageId")) {
            val jsonStr = """[{"id": "system:ebook_reader", "cols": 1, "rows": 1, "x": 0, "y": 0}, {"id": "system:log_keeper", "cols": 1, "rows": 1, "x": 1, "y": 0}]"""
            prefs.edit().putString("hybrid_grid_$defaultPageId", jsonStr).apply()
            prefs.edit().putInt("hybrid_grid_cols_$defaultPageId", 3).apply()
            prefs.edit().putBoolean("handle_${containerId}_sidebar_wrap_content", true).apply()
        }

        if (raw.isNullOrBlank()) {
            return listOf(defaultPage)
        }

        val parsed = parseRawPagesString(raw, defaultPageId)
        val result = if (parsed.isEmpty()) listOf(defaultPage) else parsed

        // Auto-migrate to isolated container key if loaded from fallback key or legacy comma-separated string
        if (raw != prefs.getString(isolatedKey, null) || !raw.trim().startsWith("[")) {
            savePageStack(containerId, result)
        }

        return result
    }

    /**
     * Convenience overload for SidebarContainer instance.
     */
    fun getPageStack(
        container: SidebarContainer,
        defaultPageIds: List<String> = listOf(HandleManager.DEFAULT_PAGE_HYBRID)
    ): List<SidebarPage> = getPageStack(container.containerId, defaultPageIds)

    /**
     * Retrieves the raw list of page IDs for a container.
     */
    fun getPageIds(containerId: String, defaultPageIds: List<String> = listOf(HandleManager.DEFAULT_PAGE_HYBRID)): List<String> {
        return getPageStack(containerId, defaultPageIds).map { it.pageId }
    }

    /**
     * Convenience overload for SidebarContainer instance.
     */
    fun getPageIds(container: SidebarContainer): List<String> = getPageIds(container.containerId)

    /**
     * Resolves the first/default page in the container's page stack.
     */
    fun getFirstPage(containerId: String): SidebarPage? {
        return getPageStack(containerId).firstOrNull()
    }

    /**
     * Convenience overload for SidebarContainer instance.
     */
    fun getFirstPage(container: SidebarContainer): SidebarPage? {
        return getFirstPage(container.containerId)
    }

    /**
     * Resolves a specific page by ID within the container's page stack.
     */
    fun getPage(containerId: String, pageId: String): SidebarPage? {
        return getPageStack(containerId).firstOrNull { it.pageId == pageId }
    }

    /**
     * Convenience overload for SidebarContainer instance.
     */
    fun getPage(container: SidebarContainer, pageId: String): SidebarPage? {
        return getPage(container.containerId, pageId)
    }

    /**
     * Saves the full ordered page stack for a container.
     * Persists as a standardized JSON array strictly under "handle_${containerId}_pages" and synchronizes across processes.
     */
    fun savePageStack(containerId: String, pages: List<SidebarPage>) {
        val arr = JSONArray()
        val seenIds = mutableSetOf<String>()
        pages.filter { it.pageId.isNotBlank() && seenIds.add(it.pageId) }
            .forEachIndexed { index, page ->
                arr.put(page.copy(order = index).toJson())
            }
        val json = arr.toString()
        val key = HandleManager.getContainerPagesKey(containerId)
        prefs.edit().putString(key, json).apply()
        OverlaySyncManager.syncString(context, key, json)
        LogKeeper.log(context, TAG, "Saved ${pages.size} pages for container '$containerId' to key '$key'")
    }

    /**
     * Convenience overload for SidebarContainer instance.
     */
    fun savePageStack(container: SidebarContainer, pages: List<SidebarPage>) {
        savePageStack(container.containerId, pages)
    }

    /**
     * Saves the ordered page stack for a container (alias for savePageStack).
     */
    fun savePageOrder(containerId: String, pages: List<SidebarPage>) {
        savePageStack(containerId, pages)
    }

    /**
     * Convenience overload for SidebarContainer instance.
     */
    fun savePageOrder(container: SidebarContainer, pages: List<SidebarPage>) {
        savePageOrder(container.containerId, pages)
    }

    /**
     * Saves an explicit list of ordered page IDs for a container, preserving existing page configurations.
     */
    fun savePageIds(containerId: String, pageIds: List<String>) {
        val existingMap = getPageStack(containerId).associateBy { it.pageId }
        val newPages = pageIds.mapIndexed { index, id ->
            val existing = existingMap[id]
            if (existing != null) {
                existing.copy(order = index)
            } else {
                val type = PageTypes.resolvePageType(id)
                val title = PageTypes.resolveDefaultTitle(type)
                SidebarPage.createDefault(id = id, type = type, title = title, order = index)
            }
        }
        savePageStack(containerId, newPages)
    }

    /**
     * Convenience overload for SidebarContainer instance.
     */
    fun savePageIds(container: SidebarContainer, pageIds: List<String>) {
        savePageIds(container.containerId, pageIds)
    }

    /**
     * Adds a page to the container's page stack.
     * If already present, moves it to the desired position to avoid duplicates within a stack.
     * Position < 0 or >= size appends to the end.
     * Returns the updated ordered page stack.
     */
    fun addPage(
        containerId: String,
        pageId: String,
        position: Int = -1,
        pageType: String = PageTypes.resolvePageType(pageId),
        title: String = PageTypes.resolveDefaultTitle(pageType)
    ): List<SidebarPage> {
        val current = getPageStack(containerId).toMutableList()
        val existingIndex = current.indexOfFirst { it.pageId == pageId }
        val pageToAdd = if (existingIndex >= 0) {
            current.removeAt(existingIndex)
        } else {
            SidebarPage.createDefault(id = pageId, type = pageType, title = title, order = current.size)
        }

        if (position in 0..current.size) {
            current.add(position, pageToAdd)
        } else {
            current.add(pageToAdd)
        }
        savePageStack(containerId, current)
        return getPageStack(containerId)
    }

    /**
     * Convenience overload for SidebarContainer instance.
     */
    fun addPage(container: SidebarContainer, pageId: String, position: Int = -1): List<SidebarPage> {
        return addPage(container.containerId, pageId, position)
    }

    /**
     * Renames a page in the container's page stack.
     * Returns the updated ordered page stack.
     */
    fun renamePage(containerId: String, pageId: String, newTitle: String): List<SidebarPage> {
        val current = getPageStack(containerId).toMutableList()
        val index = current.indexOfFirst { it.pageId == pageId }
        if (index >= 0) {
            val existing = current[index]
            current[index] = existing.copy(title = newTitle.trim())
            savePageStack(containerId, current)
        }
        return getPageStack(containerId)
    }

    /**
     * Convenience overload for SidebarContainer instance.
     */
    fun renamePage(container: SidebarContainer, pageId: String, newTitle: String): List<SidebarPage> =
        renamePage(container.containerId, pageId, newTitle)

    /**
     * Removes a page by ID from the container's page stack.
     * Returns the updated ordered page stack.
     */
    fun removePage(containerId: String, pageId: String): List<SidebarPage> {
        val current = getPageStack(containerId).toMutableList()
        current.removeAll { it.pageId == pageId }
        savePageStack(containerId, current)
        if (getSelectedPageId(containerId) == pageId) {
            saveSelectedPageId(containerId, current.firstOrNull()?.pageId)
        }
        return getPageStack(containerId)
    }

    /**
     * Convenience overload for SidebarContainer instance.
     */
    fun removePage(container: SidebarContainer, pageId: String): List<SidebarPage> {
        return removePage(container.containerId, pageId)
    }

    /**
     * Retrieves the persisted selected page ID for an independent container.
     * Auto-migrates from clean handle fallback or legacy default_page_index if not set.
     */
    fun getSelectedPageId(containerId: String): String? {
        val key = HandleManager.getContainerSelectedPageKey(containerId)
        val saved = prefs.getString(key, null)
        if (saved != null) return saved

        val cleanContainerId = getCleanHandleId(containerId)
        // Check fallback for default gesture or clean handle
        val fallbackSelected = if (containerId == cleanContainerId || containerId.endsWith("_swipe_left")) {
            prefs.getString("handle_${cleanContainerId}_selected_page", null)
                ?: if (cleanContainerId == "sidebar") prefs.getString("sidebar_selected_page", null) else null
        } else null

        if (fallbackSelected != null) {
            saveSelectedPageId(containerId, fallbackSelected)
            return fallbackSelected
        }

        // Check legacy default_page_index
        val legacyIndex = prefs.getInt("handle_${containerId}_default_page_index", -1)
            .takeIf { it >= 0 }
            ?: prefs.getInt("handle_${cleanContainerId}_default_page_index", -1).takeIf { it >= 0 }
            ?: prefs.getInt("sidebar_default_page_index", -1).takeIf { it >= 0 }

        if (legacyIndex != null) {
            val stack = getPageStack(containerId)
            val page = stack.getOrNull(legacyIndex) ?: stack.firstOrNull()
            if (page != null) {
                saveSelectedPageId(containerId, page.pageId)
                return page.pageId
            }
        }

        return null
    }

    /**
     * Convenience overload for SidebarContainer instance.
     */
    fun getSelectedPageId(container: SidebarContainer): String? =
        getSelectedPageId(container.containerId)

    /**
     * Persists the selected page ID for an independent container.
     */
    fun saveSelectedPageId(containerId: String, pageId: String?) {
        val key = HandleManager.getContainerSelectedPageKey(containerId)
        if (pageId != null) {
            prefs.edit().putString(key, pageId).apply()
            OverlaySyncManager.syncString(context, key, pageId)
        } else {
            prefs.edit().remove(key).apply()
        }
        LogKeeper.log(context, TAG, "Saved selected page '$pageId' for container '$containerId'")
    }

    /**
     * Convenience overload for SidebarContainer instance.
     */
    fun saveSelectedPageId(container: SidebarContainer, pageId: String?) {
        saveSelectedPageId(container.containerId, pageId)
    }

    /**
     * Reorders pages by moving an item from fromIndex to toIndex.
     * Returns the updated ordered page stack.
     */
    fun reorderPages(containerId: String, fromIndex: Int, toIndex: Int): List<SidebarPage> {
        val current = getPageStack(containerId).toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices && fromIndex != toIndex) {
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            savePageStack(containerId, current)
        }
        return getPageStack(containerId)
    }

    /**
     * Convenience overload for SidebarContainer instance.
     */
    fun reorderPages(container: SidebarContainer, fromIndex: Int, toIndex: Int): List<SidebarPage> {
        return reorderPages(container.containerId, fromIndex, toIndex)
    }

    /**
     * Retrieves the placed elements for a specific page in an independent container.
     */
    fun getElementsForPage(containerId: String, pageId: String): List<com.example.feature.element.ElementPlacement> {
        return com.example.feature.element.ElementPlacementManager.getInstance(context).getElementsForPage(containerId, pageId)
    }

    /**
     * Convenience overload for SidebarContainer and SidebarPage instances.
     */
    fun getElementsForPage(container: SidebarContainer, page: SidebarPage): List<com.example.feature.element.ElementPlacement> {
        return getElementsForPage(container.containerId, page.pageId)
    }

    /**
     * Checks whether a specific page type exists anywhere in configured pages across containers.
     */
    fun isPageTypePresent(pageType: String): Boolean {
        return isPageTypePresentInPrefs(prefs, pageType)
    }

    companion object {
        private const val TAG = "PageManager"

        @Volatile
        private var instance: PageManager? = null

        fun getInstance(context: Context): PageManager {
            return instance ?: synchronized(this) {
                instance ?: PageManager(context.applicationContext).also { instance = it }
            }
        }

        @androidx.annotation.VisibleForTesting
        fun resetForTesting() {
            instance = null
        }

        fun isPageTypePresentInPrefs(prefs: SharedPreferences, pageType: String): Boolean {
            for ((key, value) in prefs.all) {
                if (value is String && (key.endsWith("_pages") || key == "sidebar_pages" || key.contains("pages") || key.contains("handle"))) {
                    if (value.contains("\"$pageType\"") || value.contains(":$pageType") || value.contains("/$pageType") || value.contains(pageType)) {
                        return true
                    }
                }
            }
            return false
        }

        fun parseRawPagesString(raw: String, defaultPageId: String = "default_hybrid"): List<SidebarPage> {
            val trimmed = raw.trim()
            val list = mutableListOf<SidebarPage>()
            val seenIds = mutableSetOf<String>()

            if (trimmed.startsWith("[")) {
                try {
                    val arr = JSONArray(trimmed)
                    for (i in 0 until arr.length()) {
                        val item = arr.get(i)
                        if (item is JSONObject) {
                            val page = SidebarPage.fromJson(item, i)
                            if (page.pageId.isNotBlank() && seenIds.add(page.pageId)) {
                                list.add(page)
                            }
                        } else if (item is String && item.isNotBlank()) {
                            val id = item.trim()
                            if (seenIds.add(id)) {
                                val type = PageTypes.resolvePageType(id)
                                val title = PageTypes.resolveDefaultTitle(type)
                                list.add(SidebarPage.createDefault(id, type, title, list.size))
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            if (list.isEmpty() && !trimmed.startsWith("[")) {
                val tokens = trimmed.split(",")
                    .map { it.trim().trim('"', '\'') }
                    .filter { it.isNotEmpty() }
                tokens.forEachIndexed { index, token ->
                    if (seenIds.add(token)) {
                        val type = PageTypes.resolvePageType(token)
                        val title = PageTypes.resolveDefaultTitle(type)
                        list.add(SidebarPage.createDefault(token, type, title, index))
                    }
                }
            }

            return list
        }

        fun getCleanHandleId(handleOrContainerId: String): String {
            return when {
                handleOrContainerId.contains("_swipe_") -> handleOrContainerId.substringBefore("_swipe_")
                handleOrContainerId.endsWith("_tap") -> handleOrContainerId.removeSuffix("_tap")
                handleOrContainerId.endsWith("_double_tap") -> handleOrContainerId.removeSuffix("_double_tap")
                handleOrContainerId.endsWith("_long_press") -> handleOrContainerId.removeSuffix("_long_press")
                else -> handleOrContainerId
            }
        }

        fun parsePagesFromPrefs(prefs: SharedPreferences, rawHandleId: String): List<SidebarPage> {
            val cleanHandleId = getCleanHandleId(rawHandleId)
            val containerSpecificJson = prefs.getString("handle_${rawHandleId}_pages", null)
            val handlePagesJson = if (containerSpecificJson == null && rawHandleId == cleanHandleId) {
                prefs.getString("handle_${cleanHandleId}_pages", null)
                    ?: if (cleanHandleId == "sidebar") prefs.getString("sidebar_pages", null) else null
            } else null

            val pagesJson = containerSpecificJson ?: handlePagesJson
            val defaultPageId = if (rawHandleId == "sidebar" || rawHandleId == "sidebar_swipe_left") "default_hybrid" else "default_hybrid_$rawHandleId"
            val defaultPage = SidebarPage.createDefault(defaultPageId, PageTypes.HYBRID_GRID, "Home Grid", 0)

            if (pagesJson == null) return listOf(defaultPage)
            val parsed = parseRawPagesString(pagesJson, defaultPageId)
            val result = if (parsed.isEmpty()) listOf(defaultPage) else parsed
            if (containerSpecificJson == null || !pagesJson.trim().startsWith("[")) {
                savePagesToPrefs(prefs, rawHandleId, result)
            }
            return result
        }

        fun savePagesToPrefs(prefs: SharedPreferences, rawHandleId: String, pages: List<SidebarPage>) {
            val arr = JSONArray()
            val seenIds = mutableSetOf<String>()
            pages.filter { it.pageId.isNotBlank() && seenIds.add(it.pageId) }
                .forEachIndexed { index, page ->
                    arr.put(page.copy(order = index).toJson())
                }
            prefs.edit().putString("handle_${rawHandleId}_pages", arr.toString()).apply()
        }
    }
}
