package com.example.feature.system_hub.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import com.example.feature.system_hub.VianSideAccessibilityService

/**
 * Modular action handler for Android system global navigation actions:
 * Back, Home, Recents, Notifications, Quick Settings, Lock Screen, Split Screen.
 */
class GlobalNavigationActionModule(
    override val actionId: String,
    override val displayName: String,
    private val globalActionCode: Int,
    var sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT }
) : AccessibilityActionModule {

    override val isOneShot: Boolean = true
    override val isActive: Boolean = false

    override fun isAvailable(service: VianSideAccessibilityService?): Boolean {
        if (service == null) return false
        if (actionId == "splitscreen" && sdkIntProvider() < Build.VERSION_CODES.N) {
            return false
        }
        return true
    }

    override fun execute(
        service: VianSideAccessibilityService,
        params: Map<String, String>
    ): AccessibilityActionResult {
        if (actionId == "splitscreen" && sdkIntProvider() < Build.VERSION_CODES.N) {
            return AccessibilityActionResult.Failed("Split screen requires Android 7.0+")
        }

        val success = service.performSystemGlobalAction(globalActionCode)
        return if (success) {
            AccessibilityActionResult.Success
        } else {
            AccessibilityActionResult.Failed("System rejected global action '$actionId'")
        }
    }

    companion object {
        fun createBack(): GlobalNavigationActionModule =
            GlobalNavigationActionModule("back", "Back", AccessibilityService.GLOBAL_ACTION_BACK)

        fun createHome(): GlobalNavigationActionModule =
            GlobalNavigationActionModule("home", "Home", AccessibilityService.GLOBAL_ACTION_HOME)

        fun createRecents(): GlobalNavigationActionModule =
            GlobalNavigationActionModule("recents", "Recents", AccessibilityService.GLOBAL_ACTION_RECENTS)

        fun createNotifications(): GlobalNavigationActionModule =
            GlobalNavigationActionModule("notifications", "Notifications", AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)

        fun createQuickSettings(): GlobalNavigationActionModule =
            GlobalNavigationActionModule("quick_settings", "Quick Settings", AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)

        fun createLockScreen(): GlobalNavigationActionModule =
            GlobalNavigationActionModule("lock_screen", "Lock Screen", AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)

        fun createSplitScreen(): GlobalNavigationActionModule =
            GlobalNavigationActionModule("splitscreen", "Split Screen", AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
    }
}
