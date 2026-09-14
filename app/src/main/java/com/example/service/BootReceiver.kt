package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.core.HandleService
import com.example.core.LogKeeper

/**
 * BootReceiver: Automatically restarts HandleService on device boot or package replace.
 * Operates strictly in the Main process and does NOT start the Heavy process or Welcome UI.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try {
                HandleService.startIfConfigured(context)
            } catch (e: Exception) {
                LogKeeper.logError(context, "BootReceiver", "Failed to start service on boot", e)
            }
        }
    }
}
