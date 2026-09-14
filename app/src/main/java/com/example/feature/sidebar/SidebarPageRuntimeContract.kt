package com.example.feature.sidebar

import com.example.core.SidebarContainer
import com.example.core.SidebarPage
import com.example.feature.element.ElementPlacement
import kotlinx.coroutines.flow.StateFlow

/**
 * SidebarPageState: Detailed runtime state snapshot for a single page in the active container.
 * Scopes metadata and element placements strictly to containerId + pageId.
 * Zero UI views or Composable allocations.
 */
data class SidebarPageState(
    val page: SidebarPage,
    val containerId: String,
    val elements: List<ElementPlacement> = emptyList(),
    val isSelected: Boolean = false,
    val isEditing: Boolean = false
) {
    val pageId: String get() = page.pageId
    val pageType: String get() = page.pageType
    val title: String get() = page.title
    val order: Int get() = page.order
    val elementCount: Int get() = elements.size
    val isFirst: Boolean get() = page.isFirst
}

/**
 * SidebarPageRuntimeContract: Contract defining the runtime operations and state exposure
 * for a Sidebar and its pages in the Main process.
 *
 * Exposes everything required for a future Sidebar UI without coupling to any specific
 * visual framework (Compose or XML).
 *
 * Guarantees:
 * 1. Independent page stacks per containerId (handleId + gesture).
 * 2. Stable page IDs and ordered page stack.
 * 3. Restores last selected page upon open, surviving close/reopen and process recreation.
 * 4. Page switching without losing the container identity.
 * 5. Elements strictly scoped to containerId + pageId.
 * 6. Synchronized with EditModeController.
 */
interface SidebarPageRuntimeContract {

    /**
     * Observable stream of the active Sidebar container runtime state.
     */
    val activeState: StateFlow<SidebarRuntimeState?>

    /**
     * Opens a container for the given handle and gesture.
     */
    fun openContainer(
        handleId: String,
        gesture: String,
        explicitContainerId: String? = null
    ): SidebarRuntimeState?

    /**
     * Closes the active container without destroying the runtime state object.
     */
    fun closeContainer()

    /**
     * Completely dismisses the active container and releases state.
     */
    fun dismiss()

    /**
     * Selects a page by its 0-based order index in the active container.
     */
    fun selectPage(index: Int): Boolean

    /**
     * Selects a page by its stable pageId in the active container.
     */
    fun selectPageById(pageId: String): Boolean

    /**
     * Advances to the next page in the active container.
     */
    fun nextPage(): Boolean

    /**
     * Moves to the previous page in the active container.
     */
    fun previousPage(): Boolean

    /**
     * Retrieves the active SidebarContainer, or null if closed/idle.
     */
    fun getActiveContainer(): SidebarContainer?

    /**
     * Retrieves the ordered page stack of the active container.
     */
    fun getActivePages(): List<SidebarPage>

    /**
     * Retrieves the currently selected SidebarPage, or null.
     */
    fun getCurrentPage(): SidebarPage?

    /**
     * Retrieves the 0-based index of the currently selected page.
     */
    fun getCurrentPageIndex(): Int

    /**
     * Retrieves the full runtime state snapshot for the active page, including scoped elements.
     */
    fun getActivePageState(): SidebarPageState?

    /**
     * Retrieves the runtime state for a specific pageId in the active container.
     */
    fun getPageState(pageId: String): SidebarPageState?

    /**
     * Retrieves the placed elements for the currently active page.
     */
    fun getCurrentPageElements(): List<ElementPlacement>

    /**
     * Refreshes the page stack and elements from persistence for the active container.
     */
    fun refreshPages(): SidebarRuntimeState?

    /**
     * Toggles Edit Mode for the currently active page.
     */
    fun toggleEditMode(): Boolean

    /**
     * Enters Edit Mode for the currently active page.
     */
    fun enterEditMode(): EditModeState?

    /**
     * Exits Edit Mode.
     */
    fun exitEditMode()

    /**
     * Reports whether the Sidebar container is currently open.
     */
    fun isSidebarOpen(): Boolean

    /**
     * Reports whether the currently active page is in Edit Mode.
     */
    fun isCurrentPageInEditMode(): Boolean
}
