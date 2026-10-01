package com.example.feature.system_hub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import android.hardware.camera2.CameraManager
import com.example.core.LogKeeper

object DisplayHandler {
    private var torchEnabled = false
    private var torchCallbackRegistered = false

    fun handleDisplayAction(context: Context, action: String) {
        if (!Settings.System.canWrite(context) && action != "torch_toggle" && action != "blue_light_filter" && action != "keep_screen_on") {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Toast.makeText(context, "Please grant Write Settings permission first", Toast.LENGTH_LONG).show()
            return
        }

        try {
            com.example.core.LogKeeper.writeLog("DisplayHandler", "Handling action: $action")
            when (action) {
                "blue_light_filter" -> {
                    BlueLightFilterManager.toggle(context)
                    context.sendBroadcast(Intent("com.example.UPDATE_SIDEBAR_ICONS").apply {
                        putExtra("item_id", "display:blue_light_filter")
                        setPackage(context.packageName)
                    })
                }
                "torch_toggle" -> toggleTorch(context)
                "timeout_cycle" -> cycleScreenTimeout(context)
                "orientation_toggle", "screen_orientation" -> toggleScreenOrientation(context)
                "keep_screen_on" -> toggleKeepScreenOn(context)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Failed to apply setting", Toast.LENGTH_SHORT).show()
        }
    }

    private var isKeepScreenOn = false
    private var keepScreenOnOverlay: android.view.View? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun toggleKeepScreenOn(context: Context) {
        isKeepScreenOn = !isKeepScreenOn
        if (isKeepScreenOn) {
            try {
                if (Settings.canDrawOverlays(context)) {
                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                    val view = android.view.View(context)
                    val params = android.view.WindowManager.LayoutParams(
                        1, 1,
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        } else {
                            @Suppress("DEPRECATION")
                            android.view.WindowManager.LayoutParams.TYPE_PHONE
                        },
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                        android.graphics.PixelFormat.TRANSLUCENT
                    )
                    params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    wm.addView(view, params)
                    keepScreenOnOverlay = view
                }
            } catch (e: Exception) {
                LogKeeper.writeLog("DisplayHandler", "Overlay keep screen on failed: ${e.message}")
            }

            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                @Suppress("DEPRECATION")
                val wl = pm?.newWakeLock(
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ON_AFTER_RELEASE,
                    "VianSide:KeepScreenOn"
                )
                wl?.acquire()
                wakeLock = wl
            } catch (e: Exception) {
                LogKeeper.writeLog("DisplayHandler", "WakeLock keep screen on failed: ${e.message}")
            }

            Toast.makeText(context, "Keep Screen On: Enabled", Toast.LENGTH_SHORT).show()
        } else {
            try {
                keepScreenOnOverlay?.let {
                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                    wm.removeView(it)
                }
            } catch (e: Exception) {
                LogKeeper.writeLog("DisplayHandler", "Remove overlay failed: ${e.message}")
            }
            keepScreenOnOverlay = null

            try {
                wakeLock?.let {
                    if (it.isHeld) it.release()
                }
            } catch (e: Exception) {
                LogKeeper.writeLog("DisplayHandler", "Release wakeLock failed: ${e.message}")
            }
            wakeLock = null

            Toast.makeText(context, "Keep Screen On: Disabled", Toast.LENGTH_SHORT).show()
        }

        context.sendBroadcast(Intent("com.example.UPDATE_SIDEBAR_ICONS").apply {
            putExtra("item_id", "display:keep_screen_on")
            setPackage(context.packageName)
        })
    }

    private fun toggleScreenOrientation(context: Context) {
        val resolver = context.contentResolver
        try {
            val currentAccel = try {
                Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION)
            } catch (e: Exception) { 1 }

            val currentUserRot = try {
                Settings.System.getInt(resolver, Settings.System.USER_ROTATION)
            } catch (e: Exception) { android.view.Surface.ROTATION_0 }

            if (currentAccel == 1) {
                // Auto Rotate -> Portrait
                Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0)
                Settings.System.putInt(resolver, Settings.System.USER_ROTATION, android.view.Surface.ROTATION_0)
                Toast.makeText(context, "Orientation: Portrait", Toast.LENGTH_SHORT).show()
            } else if (currentUserRot == android.view.Surface.ROTATION_0 || currentUserRot == android.view.Surface.ROTATION_180) {
                // Portrait -> Landscape
                Settings.System.putInt(resolver, Settings.System.USER_ROTATION, android.view.Surface.ROTATION_90)
                Toast.makeText(context, "Orientation: Landscape", Toast.LENGTH_SHORT).show()
            } else {
                // Landscape -> Auto Rotate
                Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1)
                Toast.makeText(context, "Orientation: Auto Rotate", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Failed to change orientation", Toast.LENGTH_SHORT).show()
        }

        context.sendBroadcast(Intent("com.example.UPDATE_SIDEBAR_ICONS").apply {
            putExtra("item_id", "display:screen_orientation")
            setPackage(context.packageName)
        })
    }

    private fun toggleTorch(context: Context) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            if (!torchCallbackRegistered) {
                cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        torchEnabled = enabled
                    }
                }, null)
                torchCallbackRegistered = true
            }
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
            
            val newState = !torchEnabled
            cameraManager.setTorchMode(cameraId, newState)
            torchEnabled = newState
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Torch not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cycleScreenTimeout(context: Context) {
        val resolver = context.contentResolver
        val timeouts = intArrayOf(15000, 30000, 60000, 120000, 300000, 600000) // 15s, 30s, 1m, 2m, 5m, 10m
        try {
            val current = Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT)
            var nextIndex = timeouts.indexOfFirst { it > current }
            if (nextIndex == -1) nextIndex = 0
            val nextTimeout = timeouts[nextIndex]
            Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, nextTimeout)
            Toast.makeText(context, "Screen timeout: ${nextTimeout / 1000}s", Toast.LENGTH_SHORT).show()
        } catch (e: Settings.SettingNotFoundException) {
            e.printStackTrace()
        }
    }

    private fun toggleOrientationLock(context: Context) {
        toggleScreenOrientation(context)
    }
}
