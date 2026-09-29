package com.example.feature.system_hub.accessibility

import com.example.core.LogKeeper
import com.example.feature.system_hub.VianSideAccessibilityService
import java.util.concurrent.ConcurrentHashMap

/**
 * AccessibilityActionRegistry: Central modular action coordinator and lifecycle manager
 * for VianSideAccessibilityService.
 *
 * Guarantees:
 * 1. Accessibility actions remain independent modules.
 * 2. Modules are loaded on-demand when an action is requested.
 * 3. Modules are unloaded when finished or stopped.
 * 4. Distinguishes: ServiceUnavailable vs Unavailable vs Failed vs Success.
 * 5. Diagnostic logging with exact fields:
 *    - requested action ID
 *    - selected module/handler
 *    - module load
 *    - execution result
 *    - module unload
 *    - reason for failure when unsuccessful
 */
class AccessibilityActionRegistry {

    private val factories = ConcurrentHashMap<String, AccessibilityModuleFactory>()
    private val activeModules = ConcurrentHashMap<String, AccessibilityActionModule>()

    /**
     * Optional custom diagnostic logger for test verification or instrumentation.
     */
    var diagnosticLogger: ((tag: String, message: String) -> Unit)? = null

    private fun logDiagnostic(tag: String, message: String) {
        diagnosticLogger?.invoke(tag, message)
        LogKeeper.writeLog(tag, message)
    }

    init {
        registerBuiltInModules()
    }

    /**
     * Registers a module factory for on-demand lazy instantiation.
     */
    fun register(actionId: String, factory: AccessibilityModuleFactory) {
        factories[actionId] = factory
    }

    /**
     * Checks if an action ID is registered in the registry.
     */
    fun isRegistered(actionId: String): Boolean {
        return factories.containsKey(actionId)
    }

    /**
     * Registers all standard modular accessibility actions.
     */
    private fun registerBuiltInModules() {
        // Global Navigation Actions
        register("back") { GlobalNavigationActionModule.createBack() }
        register("home") { GlobalNavigationActionModule.createHome() }
        register("recents") { GlobalNavigationActionModule.createRecents() }
        register("notifications") { GlobalNavigationActionModule.createNotifications() }
        register("quick_settings") { GlobalNavigationActionModule.createQuickSettings() }
        register("lock_screen") { GlobalNavigationActionModule.createLockScreen() }
        register("splitscreen") { GlobalNavigationActionModule.createSplitScreen() }

        // Standard Screenshot
        register("screenshot") { ScreenshotActionModule() }

        // Utilities & Scrolling & Cursor
        register("auto_scroll") { AutoScrollActionModule() }
        register("cursor") { CursorActionModule() }
        register("long_screenshot") { LongScreenshotActionModule() }

        // Scanner / ML capabilities
        register("qr_scan") { ScreenQrScannerModule() }
        register("barcode_scanner") { BarcodeScannerModule() }
        register("redact_screenshot") { RedactScreenshotModule() }
    }

    /**
     * Dispatches an action through its registered module.
     * Manages on-demand loading, execution, diagnostic logging, and unloading.
     */
    @Synchronized
    fun dispatch(
        service: VianSideAccessibilityService?,
        actionId: String,
        params: Map<String, String> = emptyMap()
    ): AccessibilityActionResult {
        logDiagnostic(TAG, "requested action ID: $actionId")

        // 1. Accessibility Service availability check
        if (service == null) {
            logDiagnostic(TAG, "selected module/handler: None (service unavailable)")
            logDiagnostic(TAG, "execution result: ServiceUnavailable")
            logDiagnostic(TAG, "reason for failure: VianSideAccessibilityService is not connected")
            return AccessibilityActionResult.ServiceUnavailable
        }

        // 2. Action registration check
        val factory = factories[actionId]
        if (factory == null) {
            logDiagnostic(TAG, "selected module/handler: None (unregistered)")
            logDiagnostic(TAG, "execution result: Unavailable")
            logDiagnostic(TAG, "reason for failure: Action '$actionId' is not registered")
            return AccessibilityActionResult.Unavailable("Action '$actionId' is not supported")
        }

        // 3. Resolve module (reuse active module if stateful and running, or instantiate new)
        val existingActive = activeModules[actionId]
        val module = existingActive ?: factory.create()
        val moduleName = module::class.java.simpleName
        logDiagnostic(TAG, "selected module/handler: $moduleName")

        // 4. Module availability check
        if (!module.isAvailable(service)) {
            logDiagnostic(TAG, "execution result: Unavailable")
            val reason = "Module $moduleName is not available on this device"
            logDiagnostic(TAG, "reason for failure: $reason")
            return AccessibilityActionResult.Unavailable(reason)
        }

        // 5. Module Load
        val isNewlyLoaded = (existingActive == null)
        if (isNewlyLoaded) {
            logDiagnostic(TAG, "module load: $moduleName")
            try {
                module.onLoad(service)
            } catch (e: Throwable) {
                val err = "Error loading module $moduleName: ${e.message}"
                logDiagnostic(TAG, "execution result: Failed")
                logDiagnostic(TAG, "reason for failure: $err")
                return AccessibilityActionResult.Failed(err)
            }
        }

        // 6. Execute Action
        val result = try {
            module.execute(service, params)
        } catch (e: Throwable) {
            AccessibilityActionResult.Failed("Exception executing $moduleName: ${e.message}")
        }
        logDiagnostic(TAG, "execution result: $result")

        // 7. Module Lifecycle & Unload
        if (module.isOneShot || result !is AccessibilityActionResult.Success || !module.isActive) {
            try {
                module.onUnload()
            } catch (_: Exception) {}
            logDiagnostic(TAG, "module unload: $moduleName")
            activeModules.remove(actionId)

            if (result is AccessibilityActionResult.Failed) {
                logDiagnostic(TAG, "reason for failure: ${result.reason}")
            } else if (result is AccessibilityActionResult.Unavailable) {
                logDiagnostic(TAG, "reason for failure: ${result.reason}")
            }
        } else {
            // Module remains active (e.g. ongoing auto-scroll or virtual cursor overlay)
            activeModules[actionId] = module
        }

        return result
    }

    /**
     * Unloads and cleans up all active modules.
     */
    @Synchronized
    fun unloadAll() {
        for ((_, module) in activeModules) {
            val moduleName = module::class.java.simpleName
            try {
                module.onUnload()
            } catch (_: Exception) {}
            logDiagnostic(TAG, "module unload: $moduleName")
        }
        activeModules.clear()
    }

    /**
     * Returns a snapshot of currently active modules for inspection/testing.
     */
    fun getActiveModules(): Map<String, AccessibilityActionModule> = activeModules.toMap()

    companion object {
        private const val TAG = "AccessibilityRegistry"

        @Volatile
        private var instance: AccessibilityActionRegistry? = null

        fun getInstance(): AccessibilityActionRegistry {
            return instance ?: synchronized(this) {
                instance ?: AccessibilityActionRegistry().also { instance = it }
            }
        }
    }
}
