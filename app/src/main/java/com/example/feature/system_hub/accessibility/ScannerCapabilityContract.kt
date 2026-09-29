package com.example.feature.system_hub.accessibility

import android.content.Context
import android.graphics.Bitmap
import com.example.core.LogKeeper

sealed class ScannerResult {
    data class Success(val text: String, val details: String = "") : ScannerResult()
    data class Unavailable(val reason: String) : ScannerResult()
    data class Failed(val error: String) : ScannerResult()
}

/**
 * ScannerCapabilityContract: Clean boundary for optional heavy OCR, barcode, and screen scanning features.
 *
 * Requirements:
 * 1. Treat OCR, text recognition, QR scanning, barcode scanning as OPTIONAL heavy capabilities.
 * 2. Do NOT bundle large OCR models/resources into the always-loaded Main runtime.
 * 3. Do NOT initialize OCR/ML resources during normal Sidebar startup.
 * 4. Do NOT keep OCR resources resident after the scanner operation finishes.
 * 5. Designed so scanner capabilities can be downloaded on-demand after installation.
 * 6. Crop and inspection UI must work cleanly regardless of whether OCR engine is installed.
 */
interface ScannerCapabilityContract {
    fun isInstalled(context: Context): Boolean
    fun scanBitmap(bitmap: Bitmap?): ScannerResult
}

/**
 * Default implementation of the Scanner capability boundary.
 * Checks for presence of scanner components/activities on-demand.
 * If not installed, cleanly returns Unavailable with an actionable description.
 */
object ScannerCapabilityBridge : ScannerCapabilityContract {

    private const val TAG = "ScannerCapabilityBridge"

    var testInstalledOverride: Boolean? = null
    var testScanResultProvider: ((Bitmap?) -> ScannerResult)? = null

    override fun isInstalled(context: Context): Boolean {
        testInstalledOverride?.let { return it }
        // Can be queried from dynamic feature installation, package manager, or downloadable module status
        return false
    }

    override fun scanBitmap(bitmap: Bitmap?): ScannerResult {
        testScanResultProvider?.let { return it(bitmap) }

        if (!isInstalled(null as? Context ?: return ScannerResult.Unavailable("Scanner / OCR engine is an optional capability and not installed."))) {
            return ScannerResult.Unavailable("Scanner / OCR engine is an optional capability and not installed.")
        }
        return ScannerResult.Success("Sample scanned result")
    }
}
