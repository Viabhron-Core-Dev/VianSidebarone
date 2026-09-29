package com.example.feature.system_hub.accessibility

import com.example.feature.system_hub.AutoScrollManager
import com.example.feature.system_hub.VianSideAccessibilityService

/**
 * Modular action handler for Auto Scroll gesture overlay.
 * Stateful lifecycle: toggles on/off and unloads completely when stopped.
 */
class AutoScrollActionModule : AccessibilityActionModule {

    override val actionId: String = "auto_scroll"
    override val displayName: String = "Auto Scroll"
    override val isOneShot: Boolean = false

    private var manager: AutoScrollManager? = null

    override val isActive: Boolean
        get() = manager?.isRunning == true

    override fun onLoad(service: VianSideAccessibilityService) {
        if (manager == null) {
            manager = AutoScrollManager(service)
        }
    }

    override fun execute(
        service: VianSideAccessibilityService,
        params: Map<String, String>
    ): AccessibilityActionResult {
        val currentManager = manager ?: AutoScrollManager(service).also { manager = it }
        return try {
            if (currentManager.isRunning) {
                currentManager.stop()
            } else {
                currentManager.start()
            }
            AccessibilityActionResult.Success
        } catch (e: Throwable) {
            AccessibilityActionResult.Failed("Auto-scroll failed: ${e.message}")
        }
    }

    override fun onUnload() {
        manager?.stop()
        manager = null
    }
}
