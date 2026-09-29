package com.example.feature.system_hub.accessibility

import com.example.feature.system_hub.VianSideAccessibilityService

/**
 * Modular action handler for Screen QR scanning.
 * Implements clean capability boundary: returns Unavailable with clear explanation
 * when the optional heavy ML scanner capability is not installed.
 */
class ScreenQrScannerModule(
    private val scannerBridge: ScannerCapabilityContract = ScannerCapabilityBridge
) : AccessibilityActionModule {

    override val actionId: String = "qr_scan"
    override val displayName: String = "Screen QR Scanner"
    override val isOneShot: Boolean = true
    override val isActive: Boolean = false

    override fun isAvailable(service: VianSideAccessibilityService?): Boolean {
        return service != null && scannerBridge.isInstalled(service)
    }

    override fun execute(
        service: VianSideAccessibilityService,
        params: Map<String, String>
    ): AccessibilityActionResult {
        return scannerBridge.launchQrScanner(service)
    }
}

/**
 * Modular action handler for Camera Barcode scanning.
 * Returns Unavailable when optional scanner capability is not installed.
 */
class BarcodeScannerModule(
    private val scannerBridge: ScannerCapabilityContract = ScannerCapabilityBridge
) : AccessibilityActionModule {

    override val actionId: String = "barcode_scanner"
    override val displayName: String = "Camera Barcode Scanner"
    override val isOneShot: Boolean = true
    override val isActive: Boolean = false

    override fun isAvailable(service: VianSideAccessibilityService?): Boolean {
        return service != null && scannerBridge.isInstalled(service)
    }

    override fun execute(
        service: VianSideAccessibilityService,
        params: Map<String, String>
    ): AccessibilityActionResult {
        return scannerBridge.launchBarcodeScanner(service)
    }
}

/**
 * Modular action handler for Redact Screenshot.
 * Returns Unavailable when optional screenshot redaction/OCR capability is not installed.
 */
class RedactScreenshotModule(
    private val scannerBridge: ScannerCapabilityContract = ScannerCapabilityBridge
) : AccessibilityActionModule {

    override val actionId: String = "redact_screenshot"
    override val displayName: String = "Redact Screenshot"
    override val isOneShot: Boolean = true
    override val isActive: Boolean = false

    override fun isAvailable(service: VianSideAccessibilityService?): Boolean {
        return service != null && scannerBridge.isInstalled(service)
    }

    override fun execute(
        service: VianSideAccessibilityService,
        params: Map<String, String>
    ): AccessibilityActionResult {
        return scannerBridge.launchRedactScreenshot(service)
    }
}
