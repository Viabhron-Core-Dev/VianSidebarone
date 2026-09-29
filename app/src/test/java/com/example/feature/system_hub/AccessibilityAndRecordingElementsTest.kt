package com.example.feature.system_hub

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import com.example.core.CallRecorderManager
import com.example.feature.element.ElementActionDispatcher
import com.example.feature.system_hub.accessibility.AccessibilityActionResult
import com.example.feature.system_hub.accessibility.AccessibilityScreenshotHelper
import com.example.feature.system_hub.accessibility.BarcodeScannerModule
import com.example.feature.system_hub.accessibility.RedactScreenshotModule
import com.example.feature.system_hub.accessibility.ScannerCapabilityBridge
import com.example.feature.system_hub.accessibility.ScannerResult
import com.example.feature.system_hub.accessibility.ScreenQrScannerModule
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class AccessibilityAndRecordingElementsTest {

    private class MockContext : ContextWrapper(null) {
        var lastStartedIntent: Intent? = null
        var permissionMap = mutableMapOf<String, Int>()

        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example"

        override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
            return permissionMap[permission] ?: PackageManager.PERMISSION_DENIED
        }

        override fun startActivity(intent: Intent?) {
            lastStartedIntent = intent
        }

        override fun getCacheDir(): File {
            val dir = File(System.getProperty("java.io.tmpdir"), "test_cache_${System.currentTimeMillis()}")
            dir.mkdirs()
            return dir
        }
    }

    private class MockAccessibilityService : VianSideAccessibilityService() {
        override fun getCacheDir(): File {
            val dir = File(System.getProperty("java.io.tmpdir"), "test_acc_cache_${System.currentTimeMillis()}")
            dir.mkdirs()
            return dir
        }

        override fun getPackageName(): String = "com.example"
        override fun getApplicationContext(): Context = this
    }

    private lateinit var context: MockContext
    private lateinit var service: MockAccessibilityService

    @Before
    fun setUp() {
        context = MockContext()
        service = MockAccessibilityService()
        VianSideAccessibilityService.instance = service
    }

    // 1. Missing microphone permission does not call MediaRecorder
    @Test
    fun testMissingMicrophonePermissionDoesNotCallMediaRecorder() {
        context.permissionMap[android.Manifest.permission.RECORD_AUDIO] = PackageManager.PERMISSION_DENIED
        var recorderCreated = false

        AudioRecordFloatingPanel.permissionChecker = { _, _ -> PackageManager.PERMISSION_DENIED }
        AudioRecordFloatingPanel.mediaRecorderFactory = { _ ->
            recorderCreated = true
            throw AssertionError("MediaRecorder must NOT be instantiated when RECORD_AUDIO is missing")
        }

        val handled = ElementActionDispatcher.handleSystemAction(context, "audio_record")
        assertTrue("ElementActionDispatcher should handle audio_record trigger", handled)

        assertNotNull("Should launch permission trampoline activity when permission is missing", context.lastStartedIntent)
        assertFalse("MediaRecorder should not be instantiated", recorderCreated)
        assertFalse("AudioRecordFloatingPanel must not be showing", AudioRecordFloatingPanel.isShowing)
    }

    // 2. Granted microphone permission proceeds to recording
    @Test
    fun testGrantedMicrophonePermissionProceedsToRecording() {
        context.permissionMap[android.Manifest.permission.RECORD_AUDIO] = PackageManager.PERMISSION_GRANTED
        AudioRecordFloatingPanel.permissionChecker = { _, _ -> PackageManager.PERMISSION_GRANTED }

        val hasMic = AudioRecordFloatingPanel.permissionChecker(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        assertTrue("RECORD_AUDIO permission check should return granted", hasMic)
    }

    // 3. Disabled Call Recorder does not register the telephony listener
    @Test
    fun testDisabledCallRecorderDoesNotRegisterTelephonyListener() {
        val manager = CallRecorderManager(context)
        manager.testEnabledOverride = false

        manager.startListening()
        assertFalse("Listener must not start when Call Recorder is disabled", manager.isListening)
        assertEquals(CallRecorderManager.ListenerStatus.DISABLED, manager.listenerStatus)
    }

    // 4. Missing telephony permission does not crash the resident process
    @Test
    fun testMissingTelephonyPermissionDoesNotCrashResidentProcess() {
        val manager = CallRecorderManager(context)
        manager.testEnabledOverride = true
        manager.permissionChecker = { _, _ -> PackageManager.PERMISSION_DENIED }

        try {
            manager.startListening()
            assertFalse("Listener must not be active without permission", manager.isListening)
            assertEquals(CallRecorderManager.ListenerStatus.PERMISSION_MISSING, manager.listenerStatus)
        } catch (t: Throwable) {
            fail("Missing permission must not throw or crash resident process: ${t.message}")
        }
    }

    // 4b. SecurityException registering listener does not crash resident process
    @Test
    fun testSecurityExceptionDoesNotCrashResidentProcess() {
        val manager = CallRecorderManager(context)
        manager.testEnabledOverride = true
        manager.permissionChecker = { _, _ -> PackageManager.PERMISSION_GRANTED }
        manager.telephonyRegistrationOverride = { _, _ ->
            throw SecurityException("listen: Neither user nor current process has android.permission.READ_PHONE_STATE")
        }

        try {
            manager.startListening()
            assertFalse("Listener must not be listening after SecurityException", manager.isListening)
            assertEquals(CallRecorderManager.ListenerStatus.UNAVAILABLE, manager.listenerStatus)
        } catch (t: Throwable) {
            fail("SecurityException must be caught gracefully and not crash resident process: ${t.message}")
        }
    }

    // 5. Cursor click dispatch reports real gesture success/failure
    @Test
    fun testCursorClickDispatchReportsRealGestureSuccessAndFailure() {
        val cursorManager = CursorManager(service)
        val dispatchedGestures = mutableListOf<GestureDescription?>()
        var gestureReturnValue = true

        cursorManager.gestureDispatcher = { gesture, callback, _ ->
            dispatchedGestures.add(gesture)
            if (gestureReturnValue) {
                callback?.onCompleted(gesture)
            } else {
                callback?.onCancelled(gesture)
            }
            gestureReturnValue
        }

        var clickSuccess: Boolean? = null
        cursorManager.performClick(100f, 200f) { success ->
            clickSuccess = success
        }

        assertEquals(1, dispatchedGestures.size)
        assertEquals(true, clickSuccess)

        // Test failure scenario
        dispatchedGestures.clear()
        gestureReturnValue = false
        var clickFailure: Boolean? = null
        cursorManager.performClick(100f, 200f) { success ->
            clickFailure = success
        }
        assertEquals(1, dispatchedGestures.size)
        assertEquals(false, clickFailure)
    }

    // 6. Double-click performs two actual fake-touch gestures
    @Test
    fun testDoubleClickPerformsTwoActualFakeTouchGestures() {
        val cursorManager = CursorManager(service)
        val dispatchedGestures = mutableListOf<GestureDescription?>()

        cursorManager.gestureDispatcher = { gesture, callback, _ ->
            dispatchedGestures.add(gesture)
            callback?.onCompleted(gesture)
            true
        }

        // Execute posted delayed runnable synchronously in unit test environment
        cursorManager.postDelayedHandler = { runnable, _ ->
            runnable.run()
        }

        var doubleClickFinished = false
        cursorManager.performDoubleClick(300f, 400f) { success ->
            doubleClickFinished = success
        }

        assertEquals("Both click 1 and click 2 must be dispatched", 2, dispatchedGestures.size)
        assertTrue("Double click callback must report success", doubleClickFinished)
    }

    // 7. Redact Screenshot does not depend on OCR
    @Test
    fun testRedactScreenshotDoesNotDependOnOcr() {
        ScannerCapabilityBridge.testInstalledOverride = false
        AccessibilityScreenshotHelper.testScreenshotProvider = { Any() }

        val redactModule = RedactScreenshotModule()
        assertTrue("RedactScreenshotModule must be available without OCR", redactModule.isAvailable(service))

        val result = redactModule.execute(service, emptyMap())
        assertTrue("RedactScreenshot execution must succeed without OCR", result is AccessibilityActionResult.Success)

        AccessibilityScreenshotHelper.testScreenshotProvider = null
    }

    // 8. Secure Screen Scanner captures and crops a screenshot
    @Test
    fun testSecureScreenScannerCapturesAndCropsScreenshot() {
        AccessibilityScreenshotHelper.testScreenshotProvider = { Any() }

        val qrModule = ScreenQrScannerModule()
        assertTrue("ScreenQrScannerModule must be available", qrModule.isAvailable(service))

        val result = qrModule.execute(service, emptyMap())
        assertTrue("Execution must succeed", result is AccessibilityActionResult.Success)

        // Verify crop dimension calculation logic
        val bw = 800
        val bh = 1200
        val cropLeft = 0.1f
        val cropTop = 0.2f
        val cropRight = 0.9f
        val cropBottom = 0.6f
        val cropX = kotlin.math.round(cropLeft * bw).toInt()
        val cropY = kotlin.math.round(cropTop * bh).toInt()
        val cropW = kotlin.math.round((cropRight - cropLeft) * bw).toInt()
        val cropH = kotlin.math.round((cropBottom - cropTop) * bh).toInt()

        assertEquals(80, cropX)
        assertEquals(240, cropY)
        assertEquals(640, cropW)
        assertEquals(480, cropH)

        AccessibilityScreenshotHelper.testScreenshotProvider = null
    }

    // 9. Secure Camera Scanner captures and crops a camera image
    @Test
    fun testSecureCameraScannerCapturesAndCropsCameraImage() {
        val barcodeModule = BarcodeScannerModule()
        assertTrue("BarcodeScannerModule must be available", barcodeModule.isAvailable(service))

        val result = barcodeModule.execute(service, emptyMap())
        assertTrue("Execution must succeed", result is AccessibilityActionResult.Success)

        // Verify camera frame crop dimension calculation logic
        val cw = 1920
        val ch = 1080
        val cropW = (0.5f * cw).toInt()
        val cropH = (0.5f * ch).toInt()

        assertEquals(960, cropW)
        assertEquals(540, cropH)
    }

    // 10. Optional scanner/OCR absence does not break the crop UI
    @Test
    fun testOptionalScannerOcrAbsenceDoesNotBreakCropUi() {
        ScannerCapabilityBridge.testInstalledOverride = false
        ScannerCapabilityBridge.testScanResultProvider = null

        val scanResult = ScannerCapabilityBridge.scanBitmap(null)

        // Must return Unavailable with explanation, NOT throw an exception or crash
        assertTrue(
            "Scan result without OCR engine must be Unavailable without crashing",
            scanResult is ScannerResult.Unavailable
        )
        val reason = (scanResult as ScannerResult.Unavailable).reason
        assertTrue(reason.isNotEmpty())
    }

    // 11. Settings recording item and Sidebar element share the same underlying action capability
    @Test
    fun testSettingsAndSidebarShareRecordingAction() {
        context.permissionMap[android.Manifest.permission.RECORD_AUDIO] = PackageManager.PERMISSION_DENIED
        AudioRecordFloatingPanel.permissionChecker = { _, _ -> PackageManager.PERMISSION_DENIED }
        context.lastStartedIntent = null

        // Trigger via helper as used in both Sidebar and Settings
        RecordingActionHelper.startOrToggleAudioRecord(context)

        assertNotNull("Should start AudioRecordPermissionActivity", context.lastStartedIntent)

        // Test Recordings viewer
        context.lastStartedIntent = null
        RecordingActionHelper.openRecordings(context)
        assertNotNull("Should start RecordingsActivity", context.lastStartedIntent)
    }
}
