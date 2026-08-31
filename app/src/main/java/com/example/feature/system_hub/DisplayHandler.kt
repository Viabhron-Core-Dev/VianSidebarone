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
        if (!Settings.System.canWrite(context) && action != "torch_toggle" && action != "blue_light_filter") {
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
                "orientation_toggle" -> toggleOrientationLock(context)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Failed to apply setting", Toast.LENGTH_SHORT).show()
        }
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
        val resolver = context.contentResolver
        try {
            val current = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION)
            val next = if (current == 1) 0 else 1
            Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, next)
            val stateStr = if (next == 1) "Auto Rotate" else "Portrait Locked"
            Toast.makeText(context, "Rotation: $stateStr", Toast.LENGTH_SHORT).show()
        } catch (e: Settings.SettingNotFoundException) {
            e.printStackTrace()
        }
    }
}
