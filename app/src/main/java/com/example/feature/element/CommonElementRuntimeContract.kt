package com.example.feature.element

import android.content.Context
import android.view.View

/**
 * ElementRenderContext: Context provided by the Sidebar container rendering layer
 * when asking an Element to render itself into the active page UI.
 *
 * Guarantees:
 * 1. Element instances NEVER determine their own container/page scope.
 * 2. Scope isolation: exact handleId, gesture, containerId, pageId, and placement metadata are supplied by runtime.
 * 3. Supports edit mode flag and click/action callbacks.
 */
data class ElementRenderContext(
    val context: Context,
    val placement: ElementPlacement,
    val executionContext: ElementExecutionContext,
    val isEditMode: Boolean = false,
    val onActionTriggered: ((ElementPlacement) -> Unit)? = null,
    val onRemoveRequested: ((ElementPlacement) -> Unit)? = null
) {
    val placementId: String get() = placement.placementId
    val actionKey: String get() = placement.actionKey
    val containerId: String get() = placement.containerId
    val pageId: String get() = placement.pageId
    val position: Int get() = placement.position
    val customTitle: String? get() = placement.customTitle
    val customIconUri: String? get() = placement.customIconUri
    val config: Map<String, String> get() = placement.config
}

/**
 * CommonElementRuntimeContract: Unified lifecycle, execution, and rendering contract
 * for all modular Elements in the Main process.
 *
 * Extends [ElementActionContract] to support:
 * 1. Lifecycle management: init, release, and teardown.
 * 2. Availability checking: verifying runtime conditions before display/execution.
 * 3. Execution/Activation: handling gesture/tap triggers with complete [ElementExecutionContext].
 * 4. Rendering: rendering an interactive view representation into the active Sidebar page on demand.
 * 5. Category classification across all 6 Element categories.
 *
 * Implementations are instantiated STRICTLY ON DEMAND by [ElementRuntimeResolver].
 */
interface CommonElementRuntimeContract : ElementActionContract {

    /**
     * Initializes any lightweight state needed by the element instance.
     * Guaranteed to be called before rendering or execution if instance is newly created.
     */
    fun onInitialize(context: Context) {}

    /**
     * Renders a View representation of this placed element into the current Sidebar page.
     * If this method returns null, the caller falls back to the canonical standard element tile.
     *
     * @param renderContext strongly-typed execution and placement scope provided by the runtime.
     * @return an interactive [View] suitable for grid/page layout, or null for default tile rendering.
     */
    fun renderSidebarElement(renderContext: ElementRenderContext): View? = null

    /**
     * Releases any resources, listeners, or view references held by this element instance.
     * Called when the Sidebar container closes, page is destroyed, or memory is trimmed.
     */
    fun onRelease() {}
}
