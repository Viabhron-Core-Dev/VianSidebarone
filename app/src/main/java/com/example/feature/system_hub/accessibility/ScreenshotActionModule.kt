package com.example.feature.system_hub.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import com.example.feature.system_hub.VianSideAccessibilityService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Modular action handler for standard system screenshot.
 * Supports configurable capture delay and custom SAF save locations via ScreenCapPrefs.
 */
class ScreenshotActionModule(
    var sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT },
    var delayProvider: (Context) -> Int = { context ->
        try {
            context.getSharedPreferences("ScreenCapPrefs", Context.MODE_PRIVATE).getInt("screenshot_delay", 0)
        } catch (_: Throwable) { 0 }
    },
    var saveLocationProvider: (Context) -> String = { context ->
        try {
            context.getSharedPreferences("ScreenCapPrefs", Context.MODE_PRIVATE).getString("save_location", "Default (Pictures/Screenshots)") ?: "Default (Pictures/Screenshots)"
        } catch (_: Throwable) { "Default (Pictures/Screenshots)" }
    }
) : AccessibilityActionModule {

    override val actionId: String = "screenshot"
    override val displayName: String = "Screenshot"
    override val isOneShot: Boolean = true
    override val isActive: Boolean = false

    override fun isAvailable(service: VianSideAccessibilityService?): Boolean {
        return sdkIntProvider() >= Build.VERSION_CODES.P
    }

    override fun execute(
        service: VianSideAccessibilityService,
        params: Map<String, String>
    ): AccessibilityActionResult {
        if (sdkIntProvider() >= Build.VERSION_CODES.P) {
            val delaySec = delayProvider(service)
            if (delaySec > 0) {
                try {
                    Toast.makeText(service, "Screenshot in $delaySec seconds", Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {}
                Handler(Looper.getMainLooper()).postDelayed({
                    performCapture(service)
                }, delaySec * 1000L)
                return AccessibilityActionResult.Success
            } else {
                return performCapture(service)
            }
        }
        return AccessibilityActionResult.Failed("Screenshot requires Android 9.0+")
    }

    private fun performCapture(service: VianSideAccessibilityService): AccessibilityActionResult {
        val saveLoc = saveLocationProvider(service)
        if (sdkIntProvider() >= Build.VERSION_CODES.R && saveLoc != "Default (Pictures/Screenshots)") {
            try {
                val uri = Uri.parse(saveLoc)
                val dir = DocumentFile.fromTreeUri(service, uri)
                if (dir != null && dir.isDirectory) {
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val tempFile = File(service.cacheDir, "Screenshot_$timestamp.png")
                    AccessibilityScreenshotHelper.captureScreenshotToFile(
                        service = service,
                        outputFile = tempFile,
                        onSuccess = { file ->
                            try {
                                val targetDoc = dir.createFile("image/png", "Screenshot_$timestamp.png")
                                if (targetDoc != null) {
                                    service.contentResolver.openOutputStream(targetDoc.uri)?.use { out ->
                                        file.inputStream().use { input -> input.copyTo(out) }
                                    }
                                    file.delete()
                                    Handler(Looper.getMainLooper()).post {
                                        Toast.makeText(service, "Screenshot saved to custom location", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } catch (_: Throwable) {
                                service.performSystemGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                            }
                        },
                        onFailure = {
                            service.performSystemGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                        }
                    )
                    return AccessibilityActionResult.Success
                }
            } catch (_: Throwable) {
                // Fall back to standard global action
            }
        }

        val success = service.performSystemGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
        return if (success) {
            AccessibilityActionResult.Success
        } else {
            AccessibilityActionResult.Failed("Screenshot global action failed")
        }
    }
}
