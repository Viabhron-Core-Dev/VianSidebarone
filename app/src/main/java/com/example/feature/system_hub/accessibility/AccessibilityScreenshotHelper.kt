package com.example.feature.system_hub.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import java.io.File
import java.io.FileOutputStream

/**
 * AccessibilityScreenshotHelper: Utility for capturing screenshots using AccessibilityService
 * and saving to temporary app-private cache files.
 */
object AccessibilityScreenshotHelper {

    var testScreenshotProvider: (() -> Any?)? = null

    fun captureScreenshotToFile(
        service: AccessibilityService,
        outputFile: File,
        onSuccess: (File) -> Unit,
        onFailure: (String) -> Unit
    ) {
        testScreenshotProvider?.invoke()?.let { testObj ->
            try {
                FileOutputStream(outputFile).use { out ->
                    if (testObj is Bitmap) {
                        try {
                            testObj.compress(Bitmap.CompressFormat.PNG, 100, out)
                        } catch (_: Throwable) {
                            out.write("MOCK_PNG".toByteArray())
                        }
                    } else {
                        out.write("MOCK_PNG".toByteArray())
                    }
                }
                onSuccess(outputFile)
            } catch (e: Exception) {
                onFailure(e.message ?: "Failed to write mock screenshot")
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            try {
                                val hwBuffer = result.hardwareBuffer
                                val colorSpace = result.colorSpace
                                val bmp = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                                hwBuffer.close()

                                if (bmp != null) {
                                    FileOutputStream(outputFile).use { out ->
                                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                                    }
                                    onSuccess(outputFile)
                                } else {
                                    onFailure("Failed to wrap hardware buffer into Bitmap")
                                }
                            } catch (e: Exception) {
                                onFailure(e.message ?: "Failed to process screenshot result")
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            onFailure("Accessibility screenshot failed with error code: $errorCode")
                        }
                    }
                )
            } catch (e: Exception) {
                onFailure(e.message ?: "Exception invoking takeScreenshot")
            }
        } else {
            onFailure("Screen capture requires Android 11+")
        }
    }
}
