package com.example.feature.system_hub.accessibility

import android.content.Intent
import com.example.core.LogKeeper
import com.example.feature.sidebar.SidebarManager
import com.example.feature.system_hub.RedactScreenshotActivity
import com.example.feature.system_hub.SecureCameraScannerActivity
import com.example.feature.system_hub.SecureScreenScannerActivity
import com.example.feature.system_hub.VianSideAccessibilityService
import java.io.File

/**
 * Modular action handler for Screen QR / Secure Screen Scanner ("qr_scan").
 * Flow:
 * Captures screen immediately via Accessibility -> creates temporary image file ->
 * launches SecureScreenScannerActivity in :heavy -> lets user crop/inspect region ->
 * runs available scanner against crop.
 */
class ScreenQrScannerModule(
    private val screenshotHelper: AccessibilityScreenshotHelper = AccessibilityScreenshotHelper
) : AccessibilityActionModule {

    override val actionId: String = "qr_scan"
    override val displayName: String = "Secure Screen Scanner"
    override val isOneShot: Boolean = true
    override val isActive: Boolean = false

    override fun isAvailable(service: VianSideAccessibilityService?): Boolean {
        return service != null
    }

    override fun execute(
        service: VianSideAccessibilityService,
        params: Map<String, String>
    ): AccessibilityActionResult {
        LogKeeper.writeLog(TAG, "Executing Secure Screen Scanner (qr_scan)")
        try {
            SidebarManager.getInstance(service).closeSidebar()
        } catch (_: Exception) {}

        val outputFile = File(service.cacheDir, "temp_screen_scan.png")
        var executionResult: AccessibilityActionResult = AccessibilityActionResult.Success

        screenshotHelper.captureScreenshotToFile(
            service = service,
            outputFile = outputFile,
            onSuccess = { file ->
                LogKeeper.writeLog(TAG, "Screenshot captured for scanner: ${file.absolutePath}")
                val intent = Intent(service, SecureScreenScannerActivity::class.java).apply {
                    putExtra(SecureScreenScannerActivity.EXTRA_IMAGE_PATH, file.absolutePath)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(intent)
            },
            onFailure = { error ->
                LogKeeper.writeLog(TAG, "Failed capturing screenshot for scanner: $error")
                executionResult = AccessibilityActionResult.Failed("Screen capture failed: $error")
            }
        )

        return executionResult
    }

    companion object {
        private const val TAG = "ScreenQrScannerModule"
    }
}

/**
 * Modular action handler for Secure Camera Scanner ("barcode_scanner").
 * Flow:
 * Opens camera on-demand in :heavy -> captures photo -> lets user crop/inspect ->
 * runs available scanner against crop.
 */
class BarcodeScannerModule : AccessibilityActionModule {

    override val actionId: String = "barcode_scanner"
    override val displayName: String = "Secure Camera Scanner"
    override val isOneShot: Boolean = true
    override val isActive: Boolean = false

    override fun isAvailable(service: VianSideAccessibilityService?): Boolean {
        return service != null
    }

    override fun execute(
        service: VianSideAccessibilityService,
        params: Map<String, String>
    ): AccessibilityActionResult {
        LogKeeper.writeLog(TAG, "Launching Secure Camera Scanner (barcode_scanner)")
        try {
            SidebarManager.getInstance(service).closeSidebar()
        } catch (_: Exception) {}

        return try {
            val intent = Intent(service, SecureCameraScannerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(intent)
            AccessibilityActionResult.Success
        } catch (e: Exception) {
            LogKeeper.writeLog(TAG, "Failed launching camera scanner: ${e.message}")
            AccessibilityActionResult.Failed("Failed launching camera scanner: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "BarcodeScannerModule"
    }
}

/**
 * Modular action handler for Redact Screenshot ("redact_screenshot").
 * Not an OCR/scanner capability; captures screenshot, creates temporary image file,
 * and opens RedactScreenshotActivity in :heavy for drawing blackout/blur redaction boxes.
 */
class RedactScreenshotModule(
    private val screenshotHelper: AccessibilityScreenshotHelper = AccessibilityScreenshotHelper
) : AccessibilityActionModule {

    override val actionId: String = "redact_screenshot"
    override val displayName: String = "Redact Screenshot"
    override val isOneShot: Boolean = true
    override val isActive: Boolean = false

    override fun isAvailable(service: VianSideAccessibilityService?): Boolean {
        return service != null
    }

    override fun execute(
        service: VianSideAccessibilityService,
        params: Map<String, String>
    ): AccessibilityActionResult {
        LogKeeper.writeLog(TAG, "Executing Redact Screenshot")
        try {
            SidebarManager.getInstance(service).closeSidebar()
        } catch (_: Exception) {}

        val outputFile = File(service.cacheDir, "temp_redact_screenshot.png")
        var executionResult: AccessibilityActionResult = AccessibilityActionResult.Success

        screenshotHelper.captureScreenshotToFile(
            service = service,
            outputFile = outputFile,
            onSuccess = { file ->
                LogKeeper.writeLog(TAG, "Screenshot captured for redaction: ${file.absolutePath}")
                val intent = Intent(service, RedactScreenshotActivity::class.java).apply {
                    putExtra(RedactScreenshotActivity.EXTRA_IMAGE_PATH, file.absolutePath)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(intent)
            },
            onFailure = { error ->
                LogKeeper.writeLog(TAG, "Failed capturing screenshot for redaction: $error")
                executionResult = AccessibilityActionResult.Failed("Screen capture failed: $error")
            }
        )

        return executionResult
    }

    companion object {
        private const val TAG = "RedactScreenshotModule"
    }
}
