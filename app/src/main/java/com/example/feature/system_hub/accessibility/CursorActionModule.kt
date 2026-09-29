package com.example.feature.system_hub.accessibility

import com.example.feature.system_hub.CursorManager
import com.example.feature.system_hub.VianSideAccessibilityService

/**
 * Modular action handler for Virtual Cursor & Trackpad overlay.
 * Stateful lifecycle: toggles on/off and unloads completely when stopped.
 */
class CursorActionModule : AccessibilityActionModule {

    override val actionId: String = "cursor"
    override val displayName: String = "Virtual Cursor"
    override val isOneShot: Boolean = false

    private var manager: CursorManager? = null

    override val isActive: Boolean
        get() = manager?.isRunning == true

    override fun onLoad(service: VianSideAccessibilityService) {
        if (manager == null) {
            manager = CursorManager(service)
        }
    }

    override fun execute(
        service: VianSideAccessibilityService,
        params: Map<String, String>
    ): AccessibilityActionResult {
        val currentManager = manager ?: CursorManager(service).also { manager = it }
        return try {
            if (currentManager.isRunning) {
                currentManager.stop()
            } else {
                currentManager.start()
            }
            AccessibilityActionResult.Success
        } catch (e: Throwable) {
            AccessibilityActionResult.Failed("Virtual cursor failed: ${e.message}")
        }
    }

    override fun onUnload() {
        manager?.stop()
        manager = null
    }
}
