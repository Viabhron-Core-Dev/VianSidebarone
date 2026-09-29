package com.example.feature.system_hub

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.example.core.LogKeeper
import com.example.feature.system_hub.accessibility.AccessibilityActionRegistry
import com.example.feature.system_hub.accessibility.AccessibilityActionResult

/**
 * VianSideAccessibilityService: Central coordinator for Accessibility-dependent features.
 *
 * Architecture:
 * - Central service coordinates and dispatches; does NOT contain monolithic when(action) logic.
 * - Actions are encapsulated in modular [AccessibilityActionModule] implementations via [AccessibilityActionRegistry].
 * - On-demand loading: modules load only when requested and unload when idle/completed.
 * - Distinguishes service status, unavailable capabilities, execution errors, and success.
 */
open class VianSideAccessibilityService : AccessibilityService() {

    private val registry: AccessibilityActionRegistry
        get() = AccessibilityActionRegistry.getInstance()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        LogKeeper.writeLog(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (isForceStopping) {
            val rootNode = rootInActiveWindow ?: return

            val forceStopNodes = rootNode.findAccessibilityNodeInfosByText("Force stop")
            var buttonIsDisabled = false
            for (node in forceStopNodes) {
                if (node.isClickable && !node.isEnabled) {
                    buttonIsDisabled = true
                    node.recycle()
                    break
                } else if (node.parent?.isClickable == true && node.parent?.isEnabled == false) {
                    buttonIsDisabled = true
                    node.recycle()
                    break
                }
                node.recycle()
            }

            if (buttonIsDisabled) {
                performSystemGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
    }

    override fun onInterrupt() {
        // Accessibility service interrupt callback
    }

    override fun onUnbind(intent: Intent?): Boolean {
        registry.unloadAll()
        instance = null
        LogKeeper.writeLog(TAG, "Service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        registry.unloadAll()
        instance = null
        LogKeeper.writeLog(TAG, "Service destroyed")
    }

    /**
     * Executes an Accessibility action through the modular registry.
     * Returns a rich [AccessibilityActionResult] distinguishing success, module unavailable,
     * failure, and service disconnected states.
     */
    fun executeAction(action: String, params: Map<String, String> = emptyMap()): AccessibilityActionResult {
        LogKeeper.writeLog(TAG, "executeAction requested: $action")
        return registry.dispatch(this, action, params)
    }

    /**
     * Backwards-compatible execution returning Boolean.
     */
    fun performAction(action: String): Boolean {
        return executeAction(action) is AccessibilityActionResult.Success
    }

    /**
     * Delegates to Android performGlobalAction, open for testing without hardware binder.
     */
    open fun performSystemGlobalAction(action: Int): Boolean {
        return performGlobalAction(action)
    }

    companion object {
        private const val TAG = "VianSideAccessibility"

        @Volatile
        var instance: VianSideAccessibilityService? = null
            internal set

        @Volatile
        var isForceStopping: Boolean = false
    }
}
