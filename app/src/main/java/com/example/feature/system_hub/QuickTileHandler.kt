package com.example.feature.system_hub

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.provider.Settings
import android.widget.Toast
import android.os.Build

object QuickTileHandler {
    private var isTorchOn = false

    fun handleQuickTileAction(context: Context, action: String) {
        when (action) {
            "torch" -> toggleTorch(context)
            "wifi" -> openPanelOrSettings(context, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "android.settings.panel.action.WIFI" else Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> openPanelOrSettings(context, "android.settings.panel.action.BLUETOOTH", Settings.ACTION_BLUETOOTH_SETTINGS)
            "airplane" -> openSettings(context, Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "dnd" -> {
                try {
                    openSettings(context, Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)
                } catch(e: Exception) {
                    openSettings(context, Settings.ACTION_SOUND_SETTINGS)
                }
            }
            "location" -> openSettings(context, Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "nfc" -> openPanelOrSettings(context, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "android.settings.panel.action.NFC" else Settings.ACTION_NFC_SETTINGS, Settings.ACTION_NFC_SETTINGS)
            "data" -> openPanelOrSettings(context, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "android.settings.panel.action.INTERNET_CONNECTIVITY" else Settings.ACTION_DATA_ROAMING_SETTINGS, Settings.ACTION_DATA_ROAMING_SETTINGS)
        }
    }

    private fun toggleTorch(context: Context) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            isTorchOn = !isTorchOn
            cameraManager.setTorchMode(cameraId, isTorchOn)
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot access torch", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSettings(context: Context, action: String) {
        try {
            val intent = Intent(action)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Settings not available", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun openPanelOrSettings(context: Context, panelAction: String, fallbackAction: String) {
        try {
            val intent = Intent(panelAction)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            openSettings(context, fallbackAction)
        }
    }
}
