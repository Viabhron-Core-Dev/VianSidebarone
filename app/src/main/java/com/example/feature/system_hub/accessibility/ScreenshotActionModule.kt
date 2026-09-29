package com.example.feature.system_hub.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import com.example.feature.system_hub.VianSideAccessibilityService

/**
 * Modular action handler for standard system screenshot.
 */
class ScreenshotActionModule(
    var sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT }
) : AccessibilityActionModule {

    override val actionId: String = "screenshot"
    override val displayName: String = "Screenshot"
    override val isOneShot: Boolean = true
    override val isActive: Boolean = false

    override fun isAvailable(service: VianSideAccessibilityService?): Boolean {
        return sdkIntProvider() >= Build.VERSION_CODES.P
    }

    override fun execute(
        service: VianSideAccessibilityService,
        params: Map<String, String>
    ): AccessibilityActionResult {
        if (sdkIntProvider() >= Build.VERSION_CODES.P) {
            val success = service.performSystemGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            return if (success) {
                AccessibilityActionResult.Success
            } else {
                AccessibilityActionResult.Failed("Screenshot global action failed")
            }
        }
        return AccessibilityActionResult.Failed("Screenshot requires Android 9.0+")
    }
}
