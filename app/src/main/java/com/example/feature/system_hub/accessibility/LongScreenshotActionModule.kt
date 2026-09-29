package com.example.feature.system_hub.accessibility

import android.os.Build
import com.example.feature.system_hub.LongScreenshotManager
import com.example.feature.system_hub.VianSideAccessibilityService

/**
 * Modular action handler for Long Screenshot scrolling capture and stitching.
 */
class LongScreenshotActionModule(
    var sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT }
) : AccessibilityActionModule {

    override val actionId: String = "long_screenshot"
    override val displayName: String = "Long Screenshot"
    override val isOneShot: Boolean = true
    override val isActive: Boolean = false

    override fun isAvailable(service: VianSideAccessibilityService?): Boolean {
        return sdkIntProvider() >= Build.VERSION_CODES.R
    }

    override fun execute(
        service: VianSideAccessibilityService,
        params: Map<String, String>
    ): AccessibilityActionResult {
        if (sdkIntProvider() < Build.VERSION_CODES.R) {
            return AccessibilityActionResult.Failed("Long screenshot requires Android 11+")
        }

        return try {
            val manager = LongScreenshotManager(service)
            manager.start()
            AccessibilityActionResult.Success
        } catch (e: Throwable) {
            AccessibilityActionResult.Failed("Long screenshot failed: ${e.message}")
        }
    }
}
