package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.example.core.HandleManager
import com.example.core.HandleService
import com.example.core.LogKeeper

/**
 * BootReceiver: Automatically restarts HandleService on device boot or package replace.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try {
                val prefs = context.getSharedPreferences(HandleManager.PREFS_NAME, Context.MODE_PRIVATE)
                val autoStart = prefs.getBoolean("auto_start_on_boot", true)

                // Check overlay permission before starting service
                if (autoStart && Settings.canDrawOverlays(context)) {
                    val serviceIntent = Intent(context, HandleService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }
            } catch (e: Exception) {
                LogKeeper.logError(context, "BootReceiver", "Failed to start service on boot", e)
            }
        }
    }
}
