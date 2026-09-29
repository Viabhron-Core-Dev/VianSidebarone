package com.example.feature.system_hub.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import com.example.feature.element.ElementActionDispatcher
import com.example.feature.system_hub.VianSideAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModularAccessibilityActionTest {

    private class TestContext : ContextWrapper(null) {
        var startedIntent: Intent? = null
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example"
        override fun startActivity(intent: Intent?) {
            startedIntent = intent
        }
    }

    /**
     * Subclass of VianSideAccessibilityService for testing without Android system binder.
     */
    private class TestAccessibilityService : VianSideAccessibilityService() {
        var lastGlobalAction: Int = -1
        var globalActionReturnValue: Boolean = true

        override fun performSystemGlobalAction(action: Int): Boolean {
            lastGlobalAction = action
            return globalActionReturnValue
        }
    }

    private lateinit var mockContext: TestContext
    private lateinit var registry: AccessibilityActionRegistry
    private lateinit var testService: TestAccessibilityService

    @Before
    fun setUp() {
        mockContext = TestContext()
        registry = AccessibilityActionRegistry.getInstance()
        registry.unloadAll()

        // Register versions configured for modern Android in JVM unit test runner
        registry.register("screenshot") { ScreenshotActionModule(sdkIntProvider = { 34 }) }
        registry.register("splitscreen") { GlobalNavigationActionModule.createSplitScreen().apply { sdkIntProvider = { 34 } } }

        testService = TestAccessibilityService()
    }

    @Test
    fun testServiceUnavailableWhenServiceNull() {
        VianSideAccessibilityService.instance = null

        val result = registry.dispatch(null, "back")
        assertTrue("Expected ServiceUnavailable when service is null", result is AccessibilityActionResult.ServiceUnavailable)

        // ElementActionDispatcher should detect null service and redirect to Accessibility Settings
        val handled = ElementActionDispatcher.handleSystemAction(mockContext, "back")
        assertFalse(handled)
        assertNotNull("Expected redirect intent to be started when service is null", mockContext.startedIntent)
    }

    @Test
    fun testGlobalNavigationActionsWhenServiceConnected() {
        VianSideAccessibilityService.instance = testService

        val navActions = listOf(
            "back" to AccessibilityService.GLOBAL_ACTION_BACK,
            "home" to AccessibilityService.GLOBAL_ACTION_HOME,
            "recents" to AccessibilityService.GLOBAL_ACTION_RECENTS,
            "notifications" to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
            "quick_settings" to AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
            "lock_screen" to AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN,
            "splitscreen" to AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
        )

        for ((actionId, expectedCode) in navActions) {
            mockContext.startedIntent = null
            testService.lastGlobalAction = -1

            val handled = ElementActionDispatcher.handleSystemAction(mockContext, actionId)
            assertTrue("Expected action $actionId to be handled successfully", handled)
            assertEquals("Expected global action code $expectedCode for $actionId", expectedCode, testService.lastGlobalAction)

            // NO redirect to Accessibility Settings!
            assertNull("Action $actionId should NOT redirect to Accessibility Settings", mockContext.startedIntent)

            // One-shot module should be unloaded immediately
            assertEquals("Module for $actionId should unload immediately", 0, registry.getActiveModules().size)
        }
    }

    @Test
    fun testScreenshotActionWhenServiceConnected() {
        VianSideAccessibilityService.instance = testService
        mockContext.startedIntent = null
        testService.lastGlobalAction = -1

        val result = registry.dispatch(testService, "screenshot")
        assertTrue("Screenshot should execute successfully", result is AccessibilityActionResult.Success)
        assertEquals(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, testService.lastGlobalAction)

        // Verified one-shot unload
        assertEquals(0, registry.getActiveModules().size)

        // Handled via ElementActionDispatcher
        val handled = ElementActionDispatcher.handleSystemAction(mockContext, "screenshot")
        assertTrue(handled)
        assertNull(mockContext.startedIntent)
    }

    @Test
    fun testAutoScrollActionModuleToggleLifecycle() {
        VianSideAccessibilityService.instance = testService

        // Custom test module to isolate from WindowManager in JVM test
        var isStarted = false
        var loadCount = 0
        var unloadCount = 0

        registry.register("auto_scroll_test") {
            object : AccessibilityActionModule {
                override val actionId: String = "auto_scroll_test"
                override val displayName: String = "Auto Scroll Test"
                override val isOneShot: Boolean = false
                override val isActive: Boolean get() = isStarted

                override fun onLoad(service: VianSideAccessibilityService) {
                    loadCount++
                }

                override fun execute(service: VianSideAccessibilityService, params: Map<String, String>): AccessibilityActionResult {
                    isStarted = !isStarted
                    return AccessibilityActionResult.Success
                }

                override fun onUnload() {
                    unloadCount++
                    isStarted = false
                }
            }
        }

        // 1st dispatch: toggles ON -> loads and stays active
        val result1 = registry.dispatch(testService, "auto_scroll_test")
        assertTrue(result1 is AccessibilityActionResult.Success)
        assertTrue(isStarted)
        assertEquals(1, loadCount)
        assertEquals(0, unloadCount)
        assertTrue(registry.getActiveModules().containsKey("auto_scroll_test"))

        // 2nd dispatch: toggles OFF -> unloads
        val result2 = registry.dispatch(testService, "auto_scroll_test")
        assertTrue(result2 is AccessibilityActionResult.Success)
        assertFalse(isStarted)
        assertEquals(1, loadCount)
        assertEquals(1, unloadCount)
        assertFalse(registry.getActiveModules().containsKey("auto_scroll_test"))
    }

    @Test
    fun testCursorActionModuleToggleLifecycle() {
        VianSideAccessibilityService.instance = testService

        var isStarted = false
        var loadCount = 0
        var unloadCount = 0

        registry.register("cursor_test") {
            object : AccessibilityActionModule {
                override val actionId: String = "cursor_test"
                override val displayName: String = "Cursor Test"
                override val isOneShot: Boolean = false
                override val isActive: Boolean get() = isStarted

                override fun onLoad(service: VianSideAccessibilityService) {
                    loadCount++
                }

                override fun execute(service: VianSideAccessibilityService, params: Map<String, String>): AccessibilityActionResult {
                    isStarted = !isStarted
                    return AccessibilityActionResult.Success
                }

                override fun onUnload() {
                    unloadCount++
                    isStarted = false
                }
            }
        }

        // Toggle ON
        val result1 = registry.dispatch(testService, "cursor_test")
        assertTrue(result1 is AccessibilityActionResult.Success)
        assertTrue(isStarted)
        assertEquals(1, loadCount)
        assertEquals(0, unloadCount)
        assertTrue(registry.getActiveModules().containsKey("cursor_test"))

        // Toggle OFF
        val result2 = registry.dispatch(testService, "cursor_test")
        assertTrue(result2 is AccessibilityActionResult.Success)
        assertFalse(isStarted)
        assertEquals(1, loadCount)
        assertEquals(1, unloadCount)
        assertFalse(registry.getActiveModules().containsKey("cursor_test"))
    }

    @Test
    fun testScannerCapabilitiesExecutionWithoutSettingsRedirect() {
        VianSideAccessibilityService.instance = testService

        // Provide mock screenshot for screen scanner and redact screenshot
        AccessibilityScreenshotHelper.testScreenshotProvider = { Any() }

        val scannerActions = listOf("qr_scan", "barcode_scanner", "redact_screenshot")

        for (action in scannerActions) {
            mockContext.startedIntent = null

            val result = registry.dispatch(testService, action)
            assertTrue("Expected Success for $action", result is AccessibilityActionResult.Success)

            // Via ElementActionDispatcher: MUST NOT redirect to Accessibility Settings!
            val handled = ElementActionDispatcher.handleSystemAction(mockContext, action)
            assertTrue("Expected action $action to be handled", handled)
            assertNull("Scanner action $action must NOT redirect to Accessibility Settings", mockContext.startedIntent)
        }

        AccessibilityScreenshotHelper.testScreenshotProvider = null
    }

    @Test
    fun testUnregisteredActionReportsUnavailableWithoutSettingsRedirect() {
        VianSideAccessibilityService.instance = testService
        mockContext.startedIntent = null

        val result = registry.dispatch(testService, "non_existent_action_xyz")
        assertTrue(result is AccessibilityActionResult.Unavailable)

        val handled = ElementActionDispatcher.handleSystemAction(mockContext, "non_existent_action_xyz")
        assertFalse(handled)
        assertNull("Unregistered action must NOT redirect to Accessibility Settings", mockContext.startedIntent)
    }

    @Test
    fun testActionFailureDoesNotRedirectToAccessibilitySettings() {
        VianSideAccessibilityService.instance = testService
        mockContext.startedIntent = null
        testService.globalActionReturnValue = false // Simulate system failure

        val handled = ElementActionDispatcher.handleSystemAction(mockContext, "back")
        assertFalse(handled)

        // When action fails on active service, do NOT redirect to settings!
        assertNull("Action failure must NOT redirect to Accessibility Settings", mockContext.startedIntent)
    }

    @Test
    fun testDiagnosticLoggingStructure() {
        VianSideAccessibilityService.instance = testService

        val logs = mutableListOf<String>()
        registry.diagnosticLogger = { _, msg ->
            logs.add(msg)
        }

        registry.dispatch(testService, "home")

        registry.diagnosticLogger = null

        // Verify required diagnostic logging entries
        assertTrue("Expected requested action ID log", logs.any { it.contains("requested action ID: home") })
        assertTrue("Expected selected module/handler log", logs.any { it.contains("selected module/handler: GlobalNavigationActionModule") })
        assertTrue("Expected module load log", logs.any { it.contains("module load: GlobalNavigationActionModule") })
        assertTrue("Expected execution result log", logs.any { it.contains("execution result: Success") })
        assertTrue("Expected module unload log", logs.any { it.contains("module unload: GlobalNavigationActionModule") })
    }
}
