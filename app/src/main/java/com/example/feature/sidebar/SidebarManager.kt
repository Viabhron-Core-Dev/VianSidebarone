package com.example.feature.sidebar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.example.core.GestureTarget
import com.example.core.HandleManager
import com.example.core.LogKeeper
import com.example.core.PageManager
import com.example.core.SidebarContainer
import com.example.core.SidebarPage
import com.example.feature.element.ElementPlacement
import com.example.feature.element.ElementPlacementManager
import com.example.util.HandleEdge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SidebarRuntimeState: Snapshot of the active Sidebar container runtime.
 * Exposes the active container, its ordered page stack metadata, currently selected page,
 * placed elements for the active page, and edit mode state for future Sidebar UI consumption.
 *
 * Lightweight data structure without any Android Views, ViewPager, or page content.
 */
data class SidebarRuntimeState(
    val container: SidebarContainer,
    val pages: List<SidebarPage>,
    val currentPage: SidebarPage?,
    val currentPageIndex: Int = 0,
    val isOpen: Boolean = true,
    val isEditMode: Boolean = false,
    val currentElements: List<ElementPlacement> = emptyList()
) {
    val containerId: String get() = container.containerId
    val handleId: String get() = container.handleId
    val gesture: String get() = container.gesture
    val pagesKey: String get() = container.pagesKey

    val totalPages: Int get() = pages.size
    val hasPages: Boolean get() = pages.isNotEmpty()
    val canGoNext: Boolean get() = currentPageIndex < pages.size - 1
    val canGoPrevious: Boolean get() = currentPageIndex > 0
    val firstPage: SidebarPage? get() = pages.firstOrNull()
    val currentPageId: String? get() = currentPage?.pageId
    val currentPageType: String? get() = currentPage?.pageType
    val currentPageTitle: String? get() = currentPage?.title
}

/**
 * SidebarManager: Main-process Sidebar runtime coordinator and boundary.
 *
 * Orchestrates the transition:
 * Handle -> Gesture -> SidebarContainer -> Page Stack -> Placed Elements
 *
 * Responsibilities:
 * 1. Receives open requests for handleId + gesture (via method call or broadcast).
 * 2. Validates explicit containerId against canonical "${handleId}_${gesture}" computation.
 * 3. Enforces GestureTarget distinction (verifies gesture is configured for ACTION_OPEN_SIDEBAR).
 * 4. Resolves the independent SidebarContainer.
 * 5. Loads page stack metadata lazily via PageManager (no page UI or view instantiation).
 * 6. Restores the persisted selected page for this container (survives close/reopen and process recreation).
 * 7. Loads scoped element placements for the active container + page via ElementPlacementManager.
 * 8. Synchronizes seamlessly with EditModeController.
 * 9. Exposes the resolved SidebarRuntimeState conforming to [SidebarPageRuntimeContract].
 */
class SidebarManager private constructor(private val context: Context) : SidebarPageRuntimeContract {

    private val handleManager: HandleManager = HandleManager.getInstance(context)
    private val pageManager: PageManager = PageManager.getInstance(context)
    private val placementManager: ElementPlacementManager = ElementPlacementManager.getInstance(context)

    private val _activeState = MutableStateFlow<SidebarRuntimeState?>(null)
    override val activeState: StateFlow<SidebarRuntimeState?> = _activeState.asStateFlow()

    private var isReceiverRegistered = false

    /**
     * BroadcastReceiver listening for HandleService gesture dispatches.
     */
    val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == ACTION_OPEN_SIDEBAR) {
                val handleId = intent.getStringExtra(EXTRA_HANDLE_ID) ?: return
                val gesture = intent.getStringExtra(EXTRA_GESTURE) ?: return
                val explicitContainerId = intent.getStringExtra(EXTRA_CONTAINER_ID)

                // If already open for this exact container, avoid duplicate reload
                val current = _activeState.value
                val expectedContainerId = HandleManager.getContainerId(handleId, gesture)
                if (current != null && current.isOpen && current.containerId == expectedContainerId) {
                    return
                }

                openContainer(handleId, gesture, explicitContainerId)
            }
        }
    }

    /**
     * Resolves and opens a SidebarContainer directly from a GestureTarget.Container.
     */
    fun openTarget(
        handleId: String,
        gesture: String,
        target: GestureTarget.Container
    ): SidebarRuntimeState? {
        return openContainer(handleId, gesture, target.containerId)
    }

    /**
     * Resolves and opens a SidebarContainer directly.
     */
    fun openContainer(container: SidebarContainer): SidebarRuntimeState? {
        return openContainer(container.handleId, container.gesture, container.containerId)
    }

    /**
     * Resolves and opens a SidebarContainer by its ID (handleId_gesture).
     */
    fun openContainerById(containerId: String): SidebarRuntimeState? {
        val lastUnderscore = containerId.lastIndexOf('_')
        if (lastUnderscore > 0) {
            val handleId = containerId.substring(0, lastUnderscore)
            val gesture = containerId.substring(lastUnderscore + 1)
            return openContainer(handleId, gesture, containerId)
        }
        return null
    }

    /**
     * Opens and resolves the independent SidebarContainer for the given handle + gesture.
     *
     * Validates explicit container IDs against canonical handleId + gesture calculations.
     * Rejects requests if the gesture is not mapped to ACTION_OPEN_SIDEBAR.
     *
     * Returns the resolved SidebarRuntimeState, or null if resolution/validation fails.
     */
    override fun openContainer(
        handleId: String,
        gesture: String,
        explicitContainerId: String?
    ): SidebarRuntimeState? {
        val expectedContainerId = HandleManager.getContainerId(handleId, gesture)

        // Validate explicit containerId against expected canonical ID
        if (explicitContainerId != null && explicitContainerId != expectedContainerId) {
            LogKeeper.log(
                context,
                "SidebarManager",
                "Mismatched container ID received: '$explicitContainerId' does not match expected canonical '$expectedContainerId' for handle='$handleId', gesture='$gesture'. Enforcing canonical container ID."
            )
        }

        // Validate handle existence and enabled state
        val handle = handleManager.getHandle(handleId)
        if (handle == null || !handle.enabled) {
            LogKeeper.log(context, "SidebarManager", "Cannot open: handle '$handleId' missing or disabled.")
            return null
        }

        // Validate gesture target distinction (must be ACTION_OPEN_SIDEBAR)
        val action = handle.getActionForGesture(gesture)
        if (action != HandleManager.ACTION_OPEN_SIDEBAR) {
            LogKeeper.log(context, "SidebarManager", "Gesture '$gesture' on '$handleId' maps to '$action', not OPEN_SIDEBAR.")
            return null
        }

        // Resolve independent SidebarContainer
        val container = handleManager.resolveContainer(handleId, gesture)
        if (container == null || !container.enabled) {
            LogKeeper.log(
                context,
                "SidebarManager",
                "Failed to resolve enabled SidebarContainer for handle '$handleId', gesture='$gesture'."
            )
            return null
        }

        // Load ordered page stack metadata lazily via PageManager
        val pages = pageManager.getPageStack(container)

        // Restore persisted selected page for this container, or default to first page
        val savedPageId = pageManager.getSelectedPageId(container.containerId)
        val (initialPage, initialIndex) = if (savedPageId != null) {
            val idx = pages.indexOfFirst { it.pageId == savedPageId }
            if (idx >= 0) {
                pages[idx] to idx
            } else {
                pages.firstOrNull() to 0
            }
        } else {
            pages.firstOrNull() to 0
        }

        // If no prior selection was saved and a page exists, persist it as default
        if (savedPageId == null && initialPage != null) {
            pageManager.saveSelectedPageId(container.containerId, initialPage.pageId)
        }

        // Load placed elements for the active container page
        val elements = if (initialPage != null) {
            placementManager.getElementsForPage(container.containerId, initialPage.pageId)
        } else {
            emptyList()
        }

        val editController = EditModeController.getInstance(context)
        val isEditing = initialPage != null && editController.isEditing(container.containerId, initialPage.pageId)

        val state = SidebarRuntimeState(
            container = container,
            pages = pages,
            currentPage = initialPage,
            currentPageIndex = initialIndex,
            isOpen = true,
            isEditMode = isEditing,
            currentElements = elements
        )

        _activeState.value = state
        val edge = handleManager.getHandle(container.handleId)?.edge ?: HandleEdge.RIGHT
        SidebarWindowCoordinator.getInstance(context).showSidebar(state, edge)
        return state
    }

    /**
     * Selects a page by index within the currently active container.
     * Persists the selected page ID, updates scoped element placements,
     * and seamlessly shifts EditMode if active on this container.
     */
    override fun selectPage(index: Int): Boolean {
        val current = _activeState.value ?: return false
        if (index in current.pages.indices) {
            val selectedPage = current.pages[index]

            // Persist selected page for this container
            pageManager.saveSelectedPageId(current.containerId, selectedPage.pageId)

            // Seamlessly transfer Edit Mode scope if currently editing this container
            val editController = EditModeController.getInstance(context)
            if (editController.isEditModeActive() && editController.getEditingContainerId() == current.containerId) {
                editController.enterEditMode(current.containerId, selectedPage.pageId)
            }

            // Load scoped elements for the newly selected page
            val elements = placementManager.getElementsForPage(current.containerId, selectedPage.pageId)
            val isEditing = editController.isEditing(current.containerId, selectedPage.pageId)

            val newState = current.copy(
                currentPageIndex = index,
                currentPage = selectedPage,
                isEditMode = isEditing,
                currentElements = elements
            )
            _activeState.value = newState
            SidebarWindowCoordinator.getInstance(context).updateSidebar(newState)
            return true
        }
        return false
    }

    /**
     * Selects a page by its stable pageId within the currently active container.
     */
    override fun selectPageById(pageId: String): Boolean {
        val current = _activeState.value ?: return false
        val index = current.pages.indexOfFirst { it.pageId == pageId }
        return if (index >= 0) {
            selectPage(index)
        } else {
            false
        }
    }

    /**
     * Closes the active container runtime state.
     */
    override fun closeContainer() {
        EditModeController.getInstance(context).exitEditMode()
        com.example.feature.element.ElementRuntimeResolver.getInstance(context).releaseAll()
        SidebarWindowCoordinator.getInstance(context).closeSidebar()
        val current = _activeState.value ?: return
        _activeState.value = current.copy(isOpen = false, isEditMode = false)
    }

    /**
     * Alias for [closeContainer] for callers expecting closeSidebar.
     */
    fun closeSidebar() {
        closeContainer()
    }

    /**
     * Dismisses the active container runtime state completely.
     */
    override fun dismiss() {
        EditModeController.getInstance(context).exitEditMode()
        com.example.feature.element.ElementRuntimeResolver.getInstance(context).releaseAll()
        SidebarWindowCoordinator.getInstance(context).detachSidebarImmediate()
        _activeState.value = null
    }

    /**
     * Cleanly releases the active container runtime state.
     */
    fun release() {
        dismiss()
    }

    /**
     * Advances to the next page in the active container.
     */
    override fun nextPage(): Boolean {
        val current = _activeState.value ?: return false
        return selectPage(current.currentPageIndex + 1)
    }

    /**
     * Moves to the previous page in the active container.
     */
    override fun previousPage(): Boolean {
        val current = _activeState.value ?: return false
        return selectPage(current.currentPageIndex - 1)
    }

    /**
     * Convenience inspection accessors conforming to [SidebarPageRuntimeContract].
     */
    fun getActiveState(): SidebarRuntimeState? = _activeState.value
    override fun getActiveContainer(): SidebarContainer? = _activeState.value?.container
    override fun getActivePages(): List<SidebarPage> = _activeState.value?.pages ?: emptyList()
    override fun getCurrentPage(): SidebarPage? = _activeState.value?.currentPage
    override fun getCurrentPageIndex(): Int = _activeState.value?.currentPageIndex ?: -1
    override fun isSidebarOpen(): Boolean = _activeState.value?.isOpen == true

    /**
     * Retrieves the placed elements for the currently active page.
     */
    override fun getCurrentPageElements(): List<ElementPlacement> =
        _activeState.value?.currentElements ?: emptyList()

    /**
     * Retrieves the runtime state snapshot for the currently active page.
     */
    override fun getActivePageState(): SidebarPageState? {
        val current = _activeState.value ?: return null
        val page = current.currentPage ?: return null
        return SidebarPageState(
            page = page,
            containerId = current.containerId,
            elements = current.currentElements,
            isSelected = true,
            isEditing = current.isEditMode
        )
    }

    /**
     * Retrieves the runtime state snapshot for any page in the active container.
     */
    override fun getPageState(pageId: String): SidebarPageState? {
        val current = _activeState.value ?: return null
        val page = current.pages.firstOrNull { it.pageId == pageId } ?: return null
        val elements = placementManager.getElementsForPage(current.containerId, pageId)
        val isSelected = current.currentPage?.pageId == pageId
        val isEditing = EditModeController.getInstance(context).isEditing(current.containerId, pageId)
        return SidebarPageState(
            page = page,
            containerId = current.containerId,
            elements = elements,
            isSelected = isSelected,
            isEditing = isEditing
        )
    }

    /**
     * Refreshes the active container's page stack and placed elements from persistence.
     */
    override fun refreshPages(): SidebarRuntimeState? {
        val current = _activeState.value ?: return null
        val pages = pageManager.getPageStack(current.container)
        val savedPageId = pageManager.getSelectedPageId(current.containerId) ?: current.currentPage?.pageId
        val idx = pages.indexOfFirst { it.pageId == savedPageId }.let { if (it >= 0) it else 0 }
        val page = pages.getOrNull(idx)
        val elements = if (page != null) {
            placementManager.getElementsForPage(current.containerId, page.pageId)
        } else {
            emptyList()
        }
        val isEditing = page != null && EditModeController.getInstance(context).isEditing(current.containerId, page.pageId)

        val newState = current.copy(
            pages = pages,
            currentPage = page,
            currentPageIndex = idx,
            isEditMode = isEditing,
            currentElements = elements
        )
        _activeState.value = newState
        SidebarWindowCoordinator.getInstance(context).updateSidebar(newState)
        return newState
    }

    /**
     * Toggles Edit Mode for the currently active container page.
     */
    override fun toggleEditMode(): Boolean {
        val current = _activeState.value ?: return false
        val page = current.currentPage ?: return false
        val editController = EditModeController.getInstance(context)
        return if (editController.isEditing(current.containerId, page.pageId)) {
            editController.exitEditMode()
            false
        } else {
            editController.enterEditMode(current.containerId, page.pageId) != null
        }
    }

    /**
     * Enters Edit Mode for the currently active container page.
     */
    override fun enterEditMode(): EditModeState? {
        val current = _activeState.value ?: return null
        val page = current.currentPage ?: return null
        return EditModeController.getInstance(context).enterEditMode(current.containerId, page.pageId)
    }

    /**
     * Exits Edit Mode.
     */
    override fun exitEditMode() {
        EditModeController.getInstance(context).exitEditMode()
    }

    /**
     * Reports whether the currently active page is in Edit Mode.
     */
    override fun isCurrentPageInEditMode(): Boolean = _activeState.value?.isEditMode == true

    /**
     * Callback invoked by EditModeController whenever Edit Mode state changes.
     */
    fun onEditModeChanged(editState: EditModeState?) {
        val current = _activeState.value ?: return
        val isEditingThisPage = editState != null &&
            editState.containerId == current.containerId &&
            editState.pageId == current.currentPage?.pageId
        if (current.isEditMode != isEditingThisPage) {
            val updated = current.copy(isEditMode = isEditingThisPage)
            _activeState.value = updated
            SidebarWindowCoordinator.getInstance(context).updateSidebar(updated)
        }
    }

    /**
     * Callback invoked whenever placed elements are added, removed, or reordered on a page.
     */
    fun onElementsChanged(containerId: String, pageId: String) {
        val current = _activeState.value ?: return
        if (current.containerId == containerId && current.currentPage?.pageId == pageId) {
            val updatedElements = placementManager.getElementsForPage(containerId, pageId)
            val updated = current.copy(currentElements = updatedElements)
            _activeState.value = updated
            SidebarWindowCoordinator.getInstance(context).updateSidebar(updated)
        }
    }

    /**
     * Registers the BroadcastReceiver to listen for com.example.action.OPEN_SIDEBAR.
     */
    fun registerReceiver(context: Context) {
        if (isReceiverRegistered) return
        val filter = IntentFilter(ACTION_OPEN_SIDEBAR)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isReceiverRegistered = true
    }

    /**
     * Unregisters the BroadcastReceiver.
     */
    fun unregisterReceiver(context: Context) {
        if (!isReceiverRegistered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            // Ignored if already unregistered
        }
        isReceiverRegistered = false
    }

    companion object {
        const val ACTION_OPEN_SIDEBAR = "com.example.action.OPEN_SIDEBAR"
        const val EXTRA_HANDLE_ID = "handle_id"
        const val EXTRA_GESTURE = "gesture"
        const val EXTRA_CONTAINER_ID = "container_id"

        @Volatile
        private var instance: SidebarManager? = null

        fun getInstance(context: Context): SidebarManager {
            return instance ?: synchronized(this) {
                instance ?: SidebarManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
