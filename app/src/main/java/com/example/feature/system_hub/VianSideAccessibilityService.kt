package com.example.feature.system_hub

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.example.core.LogKeeper

class VianSideAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        LogKeeper.writeLog("VianSideAccessibility", "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        LogKeeper.writeLog("VianSideAccessibility", "Service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        LogKeeper.writeLog("VianSideAccessibility", "Service destroyed")
    }

    fun performAction(action: String): Boolean {
        LogKeeper.writeLog("VianSideAccessibility", "Performing action: $action")
        return when (action) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "lock_screen" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            "splitscreen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            } else false
            "screenshot" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            } else false
            else -> false
        }
    }

    companion object {
        var instance: VianSideAccessibilityService? = null
            private set
        var isForceStopping: Boolean = false
    }
}
