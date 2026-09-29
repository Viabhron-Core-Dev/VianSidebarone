package com.example.feature.system_hub.accessibility

import android.content.Context
import com.example.core.LogKeeper

/**
 * ScannerCapabilityContract: Clean boundary for optional heavy OCR, barcode, and screen scanning features.
 *
 * Requirements:
 * 1. Treat OCR, text recognition, QR scanning, barcode scanning as OPTIONAL heavy capabilities.
 * 2. Do NOT bundle large OCR models/resources into the always-loaded Main runtime.
 * 3. Do NOT initialize OCR/ML resources during normal Sidebar startup.
 * 4. Do NOT keep OCR resources resident after the scanner operation finishes.
 * 5. Designed so scanner capabilities can be downloaded on-demand after installation.
 * 6. Report specific unavailable status if capability is missing without claiming Accessibility permission is missing.
 */
interface ScannerCapabilityContract {
    fun isInstalled(context: Context): Boolean
    fun launchQrScanner(context: Context): AccessibilityActionResult
    fun launchBarcodeScanner(context: Context): AccessibilityActionResult
    fun launchRedactScreenshot(context: Context): AccessibilityActionResult
}

/**
 * Default implementation of the Scanner capability boundary.
 * Checks for presence of scanner components/activities on-demand.
 * If not installed, cleanly returns Unavailable with an actionable description.
 */
object ScannerCapabilityBridge : ScannerCapabilityContract {

    private const val TAG = "ScannerCapabilityBridge"

    override fun isInstalled(context: Context): Boolean {
        // Can be queried from dynamic feature installation, package manager, or downloadable module status
        return false
    }

    override fun launchQrScanner(context: Context): AccessibilityActionResult {
        LogKeeper.log(context, TAG, "Checking QR Scanner capability availability")
        if (!isInstalled(context)) {
            return AccessibilityActionResult.Unavailable("QR Scanner module is not installed (download required)")
        }
        return AccessibilityActionResult.Success
    }

    override fun launchBarcodeScanner(context: Context): AccessibilityActionResult {
        LogKeeper.log(context, TAG, "Checking Barcode Scanner capability availability")
        if (!isInstalled(context)) {
            return AccessibilityActionResult.Unavailable("Barcode Scanner module is not installed (download required)")
        }
        return AccessibilityActionResult.Success
    }

    override fun launchRedactScreenshot(context: Context): AccessibilityActionResult {
        LogKeeper.log(context, TAG, "Checking Redact Screenshot capability availability")
        if (!isInstalled(context)) {
            return AccessibilityActionResult.Unavailable("Redact Screenshot module is not installed (download required)")
        }
        return AccessibilityActionResult.Success
    }
}
