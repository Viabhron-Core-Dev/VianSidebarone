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

    private val syncReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == com.example.core.OverlaySyncManager.ACTION_SYNC_PREF && context != null) {
                val key = intent.getStringExtra(com.example.core.OverlaySyncManager.EXTRA_KEY) ?: ""
                val value = intent.getStringExtra(com.example.core.OverlaySyncManager.EXTRA_VALUE) ?: ""
                val type = intent.getStringExtra(com.example.core.OverlaySyncManager.EXTRA_TYPE) ?: ""
                val prefName = intent.getStringExtra(com.example.core.OverlaySyncManager.EXTRA_PREF_NAME) ?: "FloatingReaderPrefs"
                val targetPrefs = context.getSharedPreferences(prefName, android.content.Context.MODE_PRIVATE)
                when (type) {
                    "STRING" -> targetPrefs.edit().putString(key, value).commit()
                    "INT" -> targetPrefs.edit().putInt(key, value.toIntOrNull() ?: 0).commit()
                    "BOOLEAN" -> targetPrefs.edit().putBoolean(key, value.toBoolean()).commit()
                    "FLOAT" -> targetPrefs.edit().putFloat(key, value.toFloatOrNull() ?: 0f).commit()
                    "REMOVE" -> targetPrefs.edit().remove(key).commit()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        LogKeeper.writeLog(TAG, "Service connected")
        try {
            val filter = android.content.IntentFilter(com.example.core.OverlaySyncManager.ACTION_SYNC_PREF)
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                syncReceiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Throwable) {}
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
        try {
            unregisterReceiver(syncReceiver)
        } catch (_: Throwable) {}
        instance = null
        LogKeeper.writeLog(TAG, "Service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        registry.unloadAll()
        try {
            unregisterReceiver(syncReceiver)
        } catch (_: Throwable) {}
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
