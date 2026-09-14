package com.example.feature.element

import android.content.Context

/**
 * ElementCategory: Categories of modular elements/actions supported by the Vian runtime.
 * Provides broad classification across full-screen content, another-app links, Android widgets,
 * screen overlays, tools/actions, and sidebar/page contents.
 */
enum class ElementCategory {
    FULL_SCREEN_CONTENT,
    ANOTHER_APP_LINK,
    ANDROID_WIDGET,
    SCREEN_OVERLAY,
    TOOL_ACTION,
    SIDEBAR_PAGE_CONTENT
}

/**
 * ElementExecutionContext: Context passed during on-demand execution of an Element or action.
 * Carries origin metadata (triggering handle, gesture, and container if applicable).
 */
data class ElementExecutionContext(
    val context: Context,
    val actionKey: String,
    val handleId: String? = null,
    val gesture: String? = null,
    val containerId: String? = null,
    val pageId: String? = null,
    val extras: Map<String, Any?> = emptyMap()
)

/**
 * ElementDescriptor: Lightweight metadata describing an Element/action.
 * Used for registry queries, capabilities reflection, and picker lists without
 * instantiating the actual Element implementation.
 */
data class ElementDescriptor(
    val actionKey: String,
    val displayName: String,
    val category: ElementCategory,
    val description: String = "",
    val iconResId: Int = 0,
    val isAvailable: Boolean = true
)

/**
 * ElementActionContract: Lightweight lifecycle and execution contract for an Element/action in the Main process.
 * Implementations are instantiated strictly on demand when an action is executed or inspected.
 */
interface ElementActionContract {
    /**
     * The stable descriptor for this element/action.
     */
    val descriptor: ElementDescriptor

    /**
     * Checks whether the element is currently executable/available in this runtime environment
     * (e.g. required hardware sensors present, required permissions granted, or target app installed).
     */
    fun isAvailable(context: Context): Boolean = descriptor.isAvailable

    /**
     * Executes the action in the Main process on demand.
     * Returns true if execution was handled, false otherwise.
     */
    fun execute(executionContext: ElementExecutionContext): Boolean
}

/**
 * ElementActionFactory: On-demand factory interface to ensure lazy instantiation.
 * The registry stores factories or lambda suppliers, never eagerly instantiating element objects.
 */
fun interface ElementActionFactory {
    fun create(): ElementActionContract
}
