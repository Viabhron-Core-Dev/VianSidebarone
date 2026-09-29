package com.example.feature.system_hub.accessibility

import com.example.feature.system_hub.VianSideAccessibilityService

/**
 * Result model for Accessibility action execution, strictly distinguishing:
 * - Success: Action executed successfully
 * - Failed: Action attempted by module but failed (e.g. gesture cancelled, API error)
 * - Unavailable: Action or capability is not installed or unsupported on this device
 * - ServiceUnavailable: Accessibility service itself is not running / disabled
 */
sealed class AccessibilityActionResult {
    object Success : AccessibilityActionResult() {
        override fun toString(): String = "Success"
    }

    data class Failed(val reason: String) : AccessibilityActionResult() {
        override fun toString(): String = "Failed(reason='$reason')"
    }

    data class Unavailable(val reason: String) : AccessibilityActionResult() {
        override fun toString(): String = "Unavailable(reason='$reason')"
    }

    object ServiceUnavailable : AccessibilityActionResult() {
        override fun toString(): String = "ServiceUnavailable"
    }
}

/**
 * Modular Accessibility Action interface.
 * Each Accessibility capability / action is encapsulated in an independent module.
 */
interface AccessibilityActionModule {
    val actionId: String
    val displayName: String

    /**
     * Whether this action completes in a single shot (e.g. Back, Screenshot)
     * and should be unloaded immediately after execution.
     */
    val isOneShot: Boolean get() = true

    /**
     * For stateful/overlay actions (e.g. AutoScroll, Cursor), indicates if the module is actively running.
     */
    val isActive: Boolean get() = false

    /**
     * Checks if this action/module is executable in the current runtime environment.
     */
    fun isAvailable(service: VianSideAccessibilityService?): Boolean = true

    /**
     * Called when the module is loaded on-demand.
     */
    fun onLoad(service: VianSideAccessibilityService) {}

    /**
     * Executes the action using the active accessibility service.
     */
    fun execute(service: VianSideAccessibilityService, params: Map<String, String> = emptyMap()): AccessibilityActionResult

    /**
     * Called when the module is unloaded to clean up resources, views, or handlers.
     */
    fun onUnload() {}
}

/**
 * Factory for on-demand lazy instantiation of AccessibilityActionModules.
 */
fun interface AccessibilityModuleFactory {
    fun create(): AccessibilityActionModule
}
