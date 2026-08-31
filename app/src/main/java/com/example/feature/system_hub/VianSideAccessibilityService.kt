package com.example.feature.system_hub

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VianSideAccessibilityService : AccessibilityService() {
    private var autoScrollManager: AutoScrollManager? = null
    private var cursorManager: CursorManager? = null
    private var longScreenshotManager: LongScreenshotManager? = null
    private var appKillerManager: AppKillerManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        appKillerManager = AppKillerManager(this)
        autoScrollManager = AutoScrollManager(this)
        cursorManager = CursorManager(this)
        longScreenshotManager = LongScreenshotManager(this)
        com.example.core.LogKeeper.writeLog("VianSideAccessibility", "Service connected")
        android.util.Log.d("VianSideAccessibility", "Service connected")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            "ACTION_START_CURSOR" -> {
                if (cursorManager == null) cursorManager = CursorManager(this)
                cursorManager?.start()
            }
            "ACTION_STOP_CURSOR" -> {
                cursorManager?.stop()
            }
            "ACTION_START_AUTOSCROLL" -> {
                if (autoScrollManager == null) autoScrollManager = AutoScrollManager(this)
                autoScrollManager?.start()
            }
            "ACTION_STOP_AUTOSCROLL" -> {
                autoScrollManager?.stop()
            }
            "ACTION_START_LONG_SCREENSHOT" -> {
                if (longScreenshotManager == null) longScreenshotManager = LongScreenshotManager(this)
                longScreenshotManager?.start()
            }
            "ACTION_STOP_LONG_SCREENSHOT" -> {
                longScreenshotManager?.stop()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        appKillerManager?.handleAccessibilityEvent(event, rootInActiveWindow)
    }

    override fun onInterrupt() {
        // Not used
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        com.example.core.LogKeeper.writeLog("VianSideAccessibility", "Service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try {
            cursorManager?.stop()
            autoScrollManager?.stop()
            longScreenshotManager?.stop()
        } catch (e: Exception) {}
        cursorManager = null
        autoScrollManager = null
        longScreenshotManager = null
        appKillerManager = null
        com.example.core.LogKeeper.writeLog("VianSideAccessibility", "Service destroyed")
    }

    fun performAction(action: String): Boolean {
        com.example.core.LogKeeper.writeLog("VianSideAccessibility", "Performing action: $action")
        
        if (action == "cursor") {
            if (cursorManager == null) cursorManager = CursorManager(this)
            if (cursorManager?.isRunning == true) {
                cursorManager?.stop()
            } else {
                com.example.service.SidebarService.instance?.closeSidebar()
                cursorManager?.start()
            }
            return true
        }
        if (action == "auto_scroll") {
            if (autoScrollManager == null) autoScrollManager = AutoScrollManager(this)
            if (autoScrollManager?.isRunning == true) autoScrollManager?.stop() else autoScrollManager?.start()
            return true
        }
        if (action == "audio_record") {
            com.example.service.SidebarService.instance?.closeSidebar()
            com.example.feature.system_hub.AudioRecordFloatingPanel.toggle(this)
            return true
        }
        if (action == "long_screenshot") {
            com.example.service.SidebarService.instance?.closeSidebar()
            if (longScreenshotManager == null) longScreenshotManager = LongScreenshotManager(this)
            longScreenshotManager?.start()
            return true
        }

        if (action == "screenshot") {
            com.example.service.SidebarService.instance?.closeSidebar()
            handleScreenshotWithDelay()
            return true
        }
        if (action == "barcode_scanner") {
            val intent = Intent(this, com.example.feature.system_hub.BarcodeScannerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return true
        }
        if (action == "camera_measure") {
            val intent = Intent(this, com.example.feature.system_hub.CameraMeasureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return true
        }
        if (action == "arrangement_checker" || action == "ghost_camera") {
            val intent = Intent(this, com.example.feature.system_hub.GhostCameraActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return true
        }
        if (action == "log_keeper") {
            val intent = Intent(this, com.example.feature.settings.LogKeeperActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return true
        }
        if (action == "qr_scan") {
            handleQRScan()
            return true
        }
        if (action == "redact_screenshot") {
            handleRedactScreenshot()
            return true
        }

        return when (action) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "lock_screen" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            "splitscreen" -> performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            else -> false
        }
    }

    private fun handleScreenshotWithDelay() {
        val prefs = getSharedPreferences("ScreenCapPrefs", Context.MODE_PRIVATE)
        val delaySec = prefs.getInt("screenshot_delay", 0)
        if (delaySec > 0) {
            Toast.makeText(this, "Screenshot in $delaySec seconds", Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({
                takeCustomScreenshot(prefs)
            }, delaySec * 1000L)
        } else {
            takeCustomScreenshot(prefs)
        }
    }

    private fun takeCustomScreenshot(prefs: android.content.SharedPreferences) {
        val saveLocation = prefs.getString("save_location", "Default (Pictures/Screenshots)") ?: "Default (Pictures/Screenshots)"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && saveLocation != "Default (Pictures/Screenshots)") {
            try {
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        try {
                            val hwBuffer = screenshotResult.hardwareBuffer
                            val colorSpace = screenshotResult.colorSpace
                            val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                            if (bitmap != null) {
                                saveBitmapToCustomLocation(bitmap, saveLocation)
                            } else {
                                performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                            }
                            hwBuffer.close()
                        } catch (e: Exception) {
                            e.printStackTrace()
                            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                    }
                })
            } catch (e: Exception) {
                e.printStackTrace()
                performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            }
        } else {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        }
    }

    private fun saveBitmapToCustomLocation(bitmap: Bitmap, locationUriStr: String) {
        try {
            val uri = Uri.parse(locationUriStr)
            val dir = DocumentFile.fromTreeUri(this, uri)
            if (dir != null && dir.isDirectory) {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "Screenshot_$timestamp.png"
                val file = dir.createFile("image/png", fileName)
                if (file != null) {
                    val out: OutputStream? = contentResolver.openOutputStream(file.uri)
                    if (out != null) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.flush()
                        out.close()
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(this, "Screenshot saved to custom location", Toast.LENGTH_SHORT).show()
                        }
                        return
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, "Failed to save to custom location, using default", Toast.LENGTH_SHORT).show()
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        }
    }


    private fun handleQRScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Toast.makeText(this, "Preparing Screen Capture...", Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            try {
                                val hwBuffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                if (bitmap != null) {
                                    val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    launchCropActivity(softwareBitmap)
                                } else {
                                    Handler(Looper.getMainLooper()).post {
                                        Toast.makeText(this@VianSideAccessibilityService, "Failed to get screenshot", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                hwBuffer.close()
                            } catch (e: Exception) {
                                e.printStackTrace()
                                Handler(Looper.getMainLooper()).post {
                                    Toast.makeText(this@VianSideAccessibilityService, "Error reading screenshot", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(this@VianSideAccessibilityService, "Failed to take screenshot", Toast.LENGTH_SHORT).show()
                            }
                        }
                    })
                } catch (e: Exception) {
                    e.printStackTrace()
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this@VianSideAccessibilityService, "Screenshot capability unavailable", Toast.LENGTH_SHORT).show()
                    }
                }
            }, 400) // Delay to let sidebar close
        } else {
            Toast.makeText(this, "Screen QR Scanner requires Android 11+", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchCropActivity(bitmap: Bitmap) {
        Thread {
            try {
                val cacheFile = java.io.File(cacheDir, "temp_qr_screenshot.jpg")
                java.io.FileOutputStream(cacheFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                }
                val intent = Intent(this@VianSideAccessibilityService, QRCropActivity::class.java).apply {
                    putExtra("IMAGE_PATH", cacheFile.absolutePath)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@VianSideAccessibilityService, "Failed to prepare screenshot", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun handleRedactScreenshot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Toast.makeText(this, "Preparing Screen Capture...", Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            try {
                                val hwBuffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                if (bitmap != null) {
                                    val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    launchRedactActivity(softwareBitmap)
                                } else {
                                    Handler(Looper.getMainLooper()).post {
                                        Toast.makeText(this@VianSideAccessibilityService, "Failed to get screenshot", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                hwBuffer.close()
                            } catch (e: Exception) {
                                e.printStackTrace()
                                Handler(Looper.getMainLooper()).post {
                                    Toast.makeText(this@VianSideAccessibilityService, "Error reading screenshot", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(this@VianSideAccessibilityService, "Failed to take screenshot", Toast.LENGTH_SHORT).show()
                            }
                        }
                    })
                } catch (e: Exception) {
                    e.printStackTrace()
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this@VianSideAccessibilityService, "Screenshot capability unavailable", Toast.LENGTH_SHORT).show()
                    }
                }
            }, 350)
        } else {
            Toast.makeText(this, "Redact Screenshot requires Android 11+", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchRedactActivity(bitmap: Bitmap) {
        Thread {
            try {
                val cacheFile = java.io.File(cacheDir, "temp_redact_screenshot.png")
                java.io.FileOutputStream(cacheFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                val intent = Intent(this@VianSideAccessibilityService, RedactScreenshotActivity::class.java).apply {
                    putExtra("IMAGE_PATH", cacheFile.absolutePath)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@VianSideAccessibilityService, "Failed to prepare screenshot", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun scanBitmapForQRCode(bitmap: Bitmap) {
        Thread {
            try {
                val width = bitmap.width
                val height = bitmap.height
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                
                val source = com.google.zxing.RGBLuminanceSource(width, height, pixels)
                val binaryBitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
                
                val reader = com.google.zxing.MultiFormatReader()
                val result = reader.decode(binaryBitmap)
                
                val text = result.text
                if (text.isNullOrEmpty()) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this, "No QR Code found", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this, "QR Code found!", Toast.LENGTH_SHORT).show()
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        val chooser = Intent.createChooser(intent, "QR Code Result")
                        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(chooser)
                    }
                }
            } catch (e: com.google.zxing.NotFoundException) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, "No QR Code found on screen", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, "Error scanning QR Code", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    companion object {
        var instance: VianSideAccessibilityService? = null
            private set
        var isForceStopping: Boolean
            get() = instance?.appKillerManager?.isForceStopping ?: false
            set(value) {
                instance?.appKillerManager?.isForceStopping = value
            }
    }
}
