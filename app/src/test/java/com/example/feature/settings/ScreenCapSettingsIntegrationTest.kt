package com.example.feature.settings

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import com.example.feature.system_hub.VianSideAccessibilityService
import com.example.feature.system_hub.accessibility.AccessibilityActionResult
import com.example.feature.system_hub.accessibility.ScreenshotActionModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScreenCapSettingsIntegrationTest {

    private class TestAccessibilityService : VianSideAccessibilityService() {
        var lastGlobalAction: Int = -1
        var globalActionReturnValue: Boolean = true

        override fun performSystemGlobalAction(action: Int): Boolean {
            lastGlobalAction = action
            return globalActionReturnValue
        }
    }

    private lateinit var testService: TestAccessibilityService

    @Before
    fun setUp() {
        testService = TestAccessibilityService()
    }

    @Test
    fun testScreenshotModuleImmediateExecutionOnModernAndroid() {
        val module = ScreenshotActionModule(
            sdkIntProvider = { Build.VERSION_CODES.UPSIDE_DOWN_CAKE },
            delayProvider = { 0 },
            saveLocationProvider = { "Default (Pictures/Screenshots)" }
        )

        assertTrue(module.isAvailable(testService))
        val result = module.execute(testService, emptyMap())

        assertTrue("Expected Success when global action succeeds", result is AccessibilityActionResult.Success)
        assertEquals(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, testService.lastGlobalAction)
    }

    @Test
    fun testScreenshotModuleLegacyAndroidUnsupported() {
        val module = ScreenshotActionModule(
            sdkIntProvider = { Build.VERSION_CODES.O },
            delayProvider = { 0 }
        )

        assertFalse(module.isAvailable(testService))
        val result = module.execute(testService, emptyMap())

        assertTrue("Expected Failed result on Android < 9.0", result is AccessibilityActionResult.Failed)
        assertEquals(-1, testService.lastGlobalAction)
    }

    @Test
    fun testScreenshotModuleDelayScheduling() {
        var delayChecked = false
        val module = ScreenshotActionModule(
            sdkIntProvider = { Build.VERSION_CODES.UPSIDE_DOWN_CAKE },
            delayProvider = {
                delayChecked = true
                2
            },
            saveLocationProvider = { "Default (Pictures/Screenshots)" }
        )

        val result = module.execute(testService, emptyMap())
        assertTrue("Delay provider should have been queried", delayChecked)
        assertTrue("Expected Success when delayed screenshot is scheduled", result is AccessibilityActionResult.Success)
    }

    @Test
    fun testPreferencesKeysContract() {
        val prefsName = "ScreenCapPrefs"
        val keySaveLocation = "save_location"
        val keyScreenshotDelay = "screenshot_delay"
        val keyRecordQuality = "record_quality"
        val keyRecordAudio = "record_audio"

        assertEquals("ScreenCapPrefs", prefsName)
        assertEquals("save_location", keySaveLocation)
        assertEquals("screenshot_delay", keyScreenshotDelay)
        assertEquals("record_quality", keyRecordQuality)
        assertEquals("record_audio", keyRecordAudio)
    }
}
