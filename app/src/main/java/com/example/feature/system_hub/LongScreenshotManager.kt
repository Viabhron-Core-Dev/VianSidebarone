package com.example.feature.system_hub

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import com.example.R
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class LongScreenshotManager(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var floatingView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var isRunning = false
    private var isPlaying = false
    private var speed = 5 // 1 to 10
    
    private val screenHeight: Int
    private val screenWidth: Int
    
    private val parts = mutableListOf<File>()
    private val cacheDir = File(service.cacheDir, "long_screenshot").apply { mkdirs() }
    
    private var btnPausePlay: ImageButton? = null

    init {
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        screenHeight = displayMetrics.heightPixels
        screenWidth = displayMetrics.widthPixels
    }
    
    private fun setFloatingUIVisibility(visible: Boolean) {
        com.example.service.FloatingTriggerService.instance?.setVisibility(visible)
        if (!visible) {
            com.example.service.SidebarService.instance?.closeSidebar()
        }
    }

    fun start() {
        if (isRunning) return
        setFloatingUIVisibility(false)
        isRunning = true
        isPlaying = true
        parts.clear()
        cacheDir.listFiles()?.forEach { it.delete() }
        showFloatingControls()
        handler.postDelayed({ captureNextPart() }, 500)
    }

    fun stop() {
        isRunning = false
        isPlaying = false
        removeFloatingControls()
        setFloatingUIVisibility(true)
        stitchAndSave()
    }
    
    private fun split() {
        isPlaying = false
        updatePlayIcon()
        stitchAndSave()
        parts.clear()
        cacheDir.listFiles()?.forEach { it.delete() }
        Toast.makeText(service, "Split saved. Ready for next.", Toast.LENGTH_SHORT).show()
    }

    private fun captureNextPart() {
        if (!isRunning || !isPlaying) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                service.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, service.mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: AccessibilityService.ScreenshotResult) {
                        executor.execute {
                            try {
                                val hwBuffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                if (bitmap != null) {
                                    // Crop top and bottom 15% to remove headers/footers (status bar, bottom nav, sticky headers)
                                    val cropTop = (bitmap.height * 0.15).toInt()
                                    val cropBottom = (bitmap.height * 0.15).toInt()
                                    val croppedHeight = bitmap.height - cropTop - cropBottom
                                    
                                    val copyBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    val croppedBitmap = if (croppedHeight > 0 && copyBitmap != null) {
                                        Bitmap.createBitmap(copyBitmap, 0, cropTop, copyBitmap.width, croppedHeight)
                                    } else {
                                        copyBitmap ?: bitmap
                                    }
                                    
                                    val file = File(cacheDir, "part_${parts.size}.png")
                                    FileOutputStream(file).use { out ->
                                        croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                                    }
                                    parts.add(file)
                                }
                                hwBuffer.close()
                                
                                if (isRunning && isPlaying) {
                                    handler.post { scrollAndContinue() }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                handler.post { stop() }
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        handler.post { stop() }
                    }
                })
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(service, "Failed to capture screenshot", Toast.LENGTH_SHORT).show()
                stop()
            }
        } else {
            Toast.makeText(service, "Long screenshot requires Android 11+", Toast.LENGTH_SHORT).show()
            stop()
        }
    }

    private fun scrollAndContinue() {
        val swipePath = Path()
        val startY = screenHeight * 0.5f
        val endY = screenHeight * 0.15f
        val x = screenWidth / 2f
        swipePath.moveTo(x, startY)
        swipePath.lineTo(x, endY)

        val duration = Math.max(500L, 5000L - (speed * 400L))
        val gestureBuilder = GestureDescription.Builder()
        val stroke = GestureDescription.StrokeDescription(swipePath, 0, duration)
        gestureBuilder.addStroke(stroke)
        
        service.dispatchGesture(gestureBuilder.build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                handler.postDelayed({
                    if (isRunning && isPlaying) {
                        captureNextPart()
                    }
                }, 800) // Wait a bit longer (800ms) for UI to settle (e.g. scrollbars fading, animations)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                isPlaying = false
                updatePlayIcon()
            }
        }, null)
    }

    private fun stitchAndSave() {
        if (parts.isEmpty()) return
        val currentParts = parts.toList()
        executor.execute {
            try {
                val bitmaps = currentParts.mapNotNull { BitmapFactory.decodeFile(it.absolutePath) }
                if (bitmaps.isEmpty()) return@execute

                val stitchedHeight = bitmaps.sumOf { it.height }
                // In a real robust implementation, we would do row-by-row pixel matching to find the overlap.
                // For simplicity and performance, we can just do a basic concatenation or a simple overlap check.
                // A basic overlap check: check the last 200 pixels of bitmap i and first 200 pixels of bitmap i+1.
                var finalBitmap = bitmaps[0]
                
                for (i in 1 until bitmaps.size) {
                    val nextBitmap = bitmaps[i]
                    val overlap = findOverlap(finalBitmap, nextBitmap)
                    
                    val newHeight = finalBitmap.height + nextBitmap.height - overlap
                    val tempBitmap = Bitmap.createBitmap(finalBitmap.width, newHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(tempBitmap)
                    canvas.drawBitmap(finalBitmap, 0f, 0f, null)
                    canvas.drawBitmap(nextBitmap, 0f, (finalBitmap.height - overlap).toFloat(), null)
                    
                    finalBitmap = tempBitmap
                }

                val resolver = service.contentResolver
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "LongScreenshot_${System.currentTimeMillis()}.png")
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Screenshots")
                }
                
                val imageUri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (imageUri != null) {
                    resolver.openOutputStream(imageUri).use { out ->
                        if (out != null) {
                            finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                    }
                    handler.post {
                        Toast.makeText(service, "Long Screenshot Saved!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    throw Exception("Failed to create MediaStore entry")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun findOverlap(topImg: Bitmap, bottomImg: Bitmap): Int {
        // Fast row matching algorithm.
        // We look for a row in the bottom of topImg that matches a row in the top of bottomImg.
        // Since images are already cropped, we can just match from the top of bottomImg.
        val searchStart = Math.max(0, topImg.height - bottomImg.height)
        val searchEnd = topImg.height - 1

        val sampleY = 0 // take the first row of bottomImg
        if (sampleY >= bottomImg.height) return 0
        
        val sampleRow = IntArray(bottomImg.width)
        bottomImg.getPixels(sampleRow, 0, bottomImg.width, 0, sampleY, bottomImg.width, 1)

        for (y in searchEnd downTo searchStart) {
            val compareRow = IntArray(topImg.width)
            topImg.getPixels(compareRow, 0, topImg.width, 0, y, topImg.width, 1)
            
            var diff = 0
            val minWidth = Math.min(topImg.width, bottomImg.width)
            for (x in 0 until minWidth step 10) { // step 10 for performance
                val c1 = sampleRow[x]
                val c2 = compareRow[x]
                diff += Math.abs((c1 shr 16 and 0xFF) - (c2 shr 16 and 0xFF))
                diff += Math.abs((c1 shr 8 and 0xFF) - (c2 shr 8 and 0xFF))
                diff += Math.abs((c1 and 0xFF) - (c2 and 0xFF))
            }
            if (diff < 5000) { // arbitrary threshold for matching row
                return topImg.height - y
            }
        }
        return 0 // no overlap found, just append
    }

    private fun updatePlayIcon() {
        if (isPlaying) {
            btnPausePlay?.setImageResource(android.R.drawable.ic_media_pause)
        } else {
            btnPausePlay?.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    private fun showFloatingControls() {
        if (floatingView != null) return
        val inflater = LayoutInflater.from(service)
        floatingView = inflater.inflate(R.layout.overlay_long_screenshot, null)
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        layoutParams.y = 150

        btnPausePlay = floatingView?.findViewById(R.id.btn_pause_play)
        val btnSlower = floatingView?.findViewById<ImageButton>(R.id.btn_slower)
        val btnFaster = floatingView?.findViewById<ImageButton>(R.id.btn_faster)
        val btnSplit = floatingView?.findViewById<ImageButton>(R.id.btn_split)
        val btnExit = floatingView?.findViewById<android.view.View>(R.id.btn_exit)

        updatePlayIcon()

        btnPausePlay?.setOnClickListener {
            isPlaying = !isPlaying
            updatePlayIcon()
            if (isPlaying) {
                captureNextPart()
            }
        }

        btnSlower?.setOnClickListener {
            if (speed > 1) speed--
            Toast.makeText(service, "Speed: $speed", Toast.LENGTH_SHORT).show()
        }
        btnFaster?.setOnClickListener {
            if (speed < 10) speed++
            Toast.makeText(service, "Speed: $speed", Toast.LENGTH_SHORT).show()
        }

        btnSplit?.setOnClickListener {
            split()
        }

        btnExit?.setOnClickListener {
            stop()
        }

        // Draggable
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        floatingView?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY - (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, layoutParams)
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(floatingView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun removeFloatingControls() {
        floatingView?.let {
            if (it.isAttachedToWindow) {
                windowManager.removeView(it)
            }
            floatingView = null
        }
    }
}
