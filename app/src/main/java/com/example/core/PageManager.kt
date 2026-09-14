package com.example.core

import android.content.Context

/**
 * Standard known page type constants for the Sidebar runtime.
 */
object PageTypes {
    const val HYBRID = "default_hybrid"
    const val APPS = "apps"
    const val WIDGETS = "widgets"
    const val MEDIA = "media_player"
    const val TOOLS = "tools"
    const val APP_TRACKER = "app_tracker"
    const val CALCULATOR = "calculator"
    const val COMPASS = "compass"

    val ALL_DEFAULT_TYPES = listOf(
        HYBRID,
        APPS,
        WIDGETS,
        MEDIA,
        TOOLS,
        APP_TRACKER,
        CALCULATOR,
        COMPASS
    )

    fun resolveDefaultTitle(pageType: String): String = when (pageType) {
        HYBRID -> "Hybrid Grid"
        APPS -> "Apps"
        WIDGETS -> "Widgets"
        MEDIA -> "Media Player"
        TOOLS -> "Quick Tools"
        APP_TRACKER -> "App Tracker"
        CALCULATOR -> "Calculator"
        COMPASS -> "Compass"
        else -> pageType.replace('_', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    fun resolvePageType(pageId: String): String = when {
        pageId == HYBRID || pageId.contains("hybrid") -> HYBRID
        pageId == APPS || pageId.contains("apps") -> APPS
        pageId == WIDGETS || pageId.contains("widget") -> WIDGETS
        pageId == MEDIA || pageId.contains("media") -> MEDIA
        pageId == TOOLS || pageId.contains("tool") -> TOOLS
        pageId == APP_TRACKER || pageId.contains("tracker") -> APP_TRACKER
        pageId == CALCULATOR || pageId.contains("calc") -> CALCULATOR
        pageId == COMPASS || pageId.contains("compass") -> COMPASS
        else -> pageId
    }
}

/**
 * SidebarPage: Lightweight runtime metadata for a single page in a container's page stack.
 *
 * Contains stable identity, page type token for on-demand UI loading, human-readable title,
 * and ordering position. Zero UI views or heavy allocations.
 */
data class SidebarPage(
    val pageId: String,
    val pageType: String = PageTypes.resolvePageType(pageId),
    val title: String = PageTypes.resolveDefaultTitle(pageType),
    val order: Int = 0
) {
    val isFirst: Boolean get() = order == 0
}

/**
 * PageManager: Lightweight coordinator for container-owned page stacks in the Main runtime.
 *
 * Operates on top of Handle -> Gesture -> SidebarContainer identity.
 * Strictly persists ordered page IDs per container under "handle_${containerId}_pages"
 * through HandleManager to guarantee total isolation between gestures and handles.
 *
 * Pages are kept purely as on-demand runtime data structures (no UI views instantiated).
 */
class PageManager private constructor(private val context: Context) {

    private val handleManager: HandleManager = HandleManager.getInstance(context)

    /**
     * Loads the ordered page stack for an independent container.
     * Returns a list of SidebarPage models reflecting the stored order (0-indexed).
     */
    fun getPageStack(
        containerId: String,
        defaultPageIds: List<String> = listOf(HandleManager.DEFAULT_PAGE_HYBRID)
    ): List<SidebarPage> {
        val pageIds = handleManager.getPagesForContainer(containerId, defaultPageIds)
        return pageIds.mapIndexed { index, pageId ->
            SidebarPage(
                pageId = pageId,
                pageType = PageTypes.resolvePageType(pageId),
                title = PageTypes.resolveDefaultTitle(PageTypes.resolvePageType(pageId)),
                order = index
            )
        }
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
    fun getPageIds(containerId: String): List<String> {
        return handleManager.getPagesForContainer(containerId)
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
     * Saves the ordered page stack for a container.
     * Extracts page IDs and persists strictly under "handle_${containerId}_pages".
     */
    fun savePageOrder(containerId: String, pages: List<SidebarPage>) {
        val pageIds = pages.map { it.pageId }
        handleManager.savePagesForContainer(containerId, pageIds)
    }

    /**
     * Convenience overload for SidebarContainer instance.
     */
    fun savePageOrder(container: SidebarContainer, pages: List<SidebarPage>) {
        savePageOrder(container.containerId, pages)
    }

    /**
     * Saves an explicit list of ordered page IDs for a container.
     */
    fun savePageIds(containerId: String, pageIds: List<String>) {
        handleManager.savePagesForContainer(containerId, pageIds)
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
    fun addPage(containerId: String, pageId: String, position: Int = -1): List<SidebarPage> {
        val currentIds = getPageIds(containerId).toMutableList()
        currentIds.remove(pageId)
        if (position in 0..currentIds.size) {
            currentIds.add(position, pageId)
        } else {
            currentIds.add(pageId)
        }
        handleManager.savePagesForContainer(containerId, currentIds)
        return getPageStack(containerId)
    }

    /**
     * Convenience overload for SidebarContainer instance.
     */
    fun addPage(container: SidebarContainer, pageId: String, position: Int = -1): List<SidebarPage> {
        return addPage(container.containerId, pageId, position)
    }

    /**
     * Removes a page by ID from the container's page stack.
     * Returns the updated ordered page stack.
     */
    fun removePage(containerId: String, pageId: String): List<SidebarPage> {
        val currentIds = getPageIds(containerId).toMutableList()
        currentIds.remove(pageId)
        handleManager.savePagesForContainer(containerId, currentIds)
        if (getSelectedPageId(containerId) == pageId) {
            saveSelectedPageId(containerId, currentIds.firstOrNull())
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
     */
    fun getSelectedPageId(containerId: String): String? =
        handleManager.getSelectedPageForContainer(containerId)

    /**
     * Convenience overload for SidebarContainer instance.
     */
    fun getSelectedPageId(container: SidebarContainer): String? =
        getSelectedPageId(container.containerId)

    /**
     * Persists the selected page ID for an independent container.
     */
    fun saveSelectedPageId(containerId: String, pageId: String?) {
        handleManager.saveSelectedPageForContainer(containerId, pageId)
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
        val currentIds = getPageIds(containerId).toMutableList()
        if (fromIndex in currentIds.indices && toIndex in currentIds.indices && fromIndex != toIndex) {
            val item = currentIds.removeAt(fromIndex)
            currentIds.add(toIndex, item)
            handleManager.savePagesForContainer(containerId, currentIds)
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

    companion object {
        @Volatile
        private var instance: PageManager? = null

        fun getInstance(context: Context): PageManager {
            return instance ?: synchronized(this) {
                instance ?: PageManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
