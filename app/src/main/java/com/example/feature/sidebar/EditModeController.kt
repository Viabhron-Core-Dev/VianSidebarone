package com.example.feature.sidebar

import android.content.Context
import com.example.core.HandleManager
import com.example.core.LogKeeper
import com.example.core.PageManager
import com.example.core.SidebarContainer
import com.example.core.SidebarPage
import com.example.feature.element.AddElementRequest
import com.example.feature.element.AddElementResult
import com.example.feature.element.ElementPlacementManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * EditModeState: Immutable state representing the active edit session for a Sidebar container and page.
 * Strictly scoped to the exact containerId and pageId.
 */
data class EditModeState(
    val containerId: String,
    val pageId: String,
    val handleId: String,
    val gesture: String,
    val enteredAtMillis: Long = System.currentTimeMillis()
)

/**
 * EditModeController: Lightweight coordinator for entering, tracking, and exiting Edit Mode
 * on a specific SidebarContainer and SidebarPage in the Main process.
 *
 * Guarantees:
 * 1. Main-process runtime functionality, completely decoupled from Settings.
 * 2. Strictly scoped to exact containerId and pageId (never handleId alone).
 * 3. Coordinates with [ElementPlacementManager] for adding elements to the active page while in edit mode.
 * 4. Safely releases/clears edit state without leaving orphaned flags or memory leaks.
 */
class EditModeController private constructor(private val context: Context) {

    private val handleManager: HandleManager = HandleManager.getInstance(context)
    private val pageManager: PageManager = PageManager.getInstance(context)
    private val placementManager: ElementPlacementManager = ElementPlacementManager.getInstance(context)

    private val _editState = MutableStateFlow<EditModeState?>(null)
    val editState: StateFlow<EditModeState?> = _editState.asStateFlow()

    /**
     * Enters edit mode for a specific container and page.
     * Validates that both the container and page exist.
     * Returns the created EditModeState, or null if validation fails.
     */
    fun enterEditMode(containerId: String, pageId: String): EditModeState? {
        val container = handleManager.getContainer(containerId)
        if (container == null) {
            LogKeeper.log(context, TAG, "Cannot enter edit mode: Container '$containerId' not found.")
            return null
        }

        val page = pageManager.getPage(containerId, pageId)
        if (page == null) {
            LogKeeper.log(context, TAG, "Cannot enter edit mode: Page '$pageId' not found in container '$containerId'.")
            return null
        }

        val state = EditModeState(
            containerId = container.containerId,
            pageId = page.pageId,
            handleId = container.handleId,
            gesture = container.gesture
        )

        _editState.value = state
        SidebarManager.getInstance(context).onEditModeChanged(state)
        LogKeeper.log(context, TAG, "Entered edit mode for container='${state.containerId}', page='${state.pageId}'")
        return state
    }

    /**
     * Convenience overload using strongly-typed models.
     */
    fun enterEditMode(container: SidebarContainer, page: SidebarPage): EditModeState? {
        return enterEditMode(container.containerId, page.pageId)
    }

    /**
     * Exits edit mode.
     */
    fun exitEditMode() {
        val prev = _editState.value
        if (prev != null) {
            _editState.value = null
            SidebarManager.getInstance(context).onEditModeChanged(null)
            LogKeeper.log(context, TAG, "Exited edit mode for container='${prev.containerId}', page='${prev.pageId}'")
        }
    }

    /**
     * Reports whether edit mode is currently active.
     */
    fun isEditModeActive(): Boolean = _editState.value != null

    /**
     * Checks if edit mode is active for a specific container and page.
     */
    fun isEditing(containerId: String, pageId: String): Boolean {
        val current = _editState.value ?: return false
        return current.containerId == containerId && current.pageId == pageId
    }

    /**
     * Returns the ID of the container currently being edited, or null.
     */
    fun getEditingContainerId(): String? = _editState.value?.containerId

    /**
     * Returns the ID of the page currently being edited, or null.
     */
    fun getEditingPageId(): String? = _editState.value?.pageId

    /**
     * Returns the active EditModeState, or null.
     */
    fun getActiveEditState(): EditModeState? = _editState.value

    /**
     * Requests adding an Element to the currently edited page.
     * Validates through ElementActionRegistry via [ElementPlacementManager].
     * Fails immediately if edit mode is not active.
     */
    fun addElementToCurrentPage(
        actionKey: String,
        position: Int = -1,
        customTitle: String? = null,
        customIconUri: String? = null,
        config: Map<String, String> = emptyMap()
    ): AddElementResult {
        val current = _editState.value
            ?: return AddElementResult.Failure("Cannot add element: Edit mode is not active.")

        val request = AddElementRequest(
            containerId = current.containerId,
            pageId = current.pageId,
            actionKey = actionKey,
            position = position,
            customTitle = customTitle,
            customIconUri = customIconUri,
            config = config
        )

        val result = placementManager.addElement(request)
        if (result is AddElementResult.Success) {
            SidebarManager.getInstance(context).onElementsChanged(current.containerId, current.pageId)
        }
        return result
    }

    /**
     * Safely clears/releases edit state.
     */
    fun clearEditState() {
        exitEditMode()
    }

    /**
     * Lifecycle release hook.
     */
    fun release() {
        clearEditState()
    }

    companion object {
        private const val TAG = "EditModeController"

        @Volatile
        private var instance: EditModeController? = null

        fun getInstance(context: Context): EditModeController {
            return instance ?: synchronized(this) {
                instance ?: EditModeController(context.applicationContext).also { instance = it }
            }
        }
    }
}
