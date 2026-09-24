package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.core.HandleService
import com.example.core.LogKeeper

/**
 * BootReceiver: Automatically restarts HandleService on device boot or package replace,
 * or upon receiving a system-scheduled recovery trigger following an unexpected Main process termination.
 * Operates strictly in the Main process and does NOT start the Heavy process or Welcome UI.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RECOVER_MAIN = "com.example.action.RECOVER_MAIN"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        LogKeeper.logLifecycle(context, "BootReceiver", "ON_RECEIVE", "Action received: $action")
        if (action == Intent.ACTION_BOOT_COMPLETED || 
            action == Intent.ACTION_MY_PACKAGE_REPLACED || 
            action == ACTION_RECOVER_MAIN) {
            try {
                when (action) {
                    Intent.ACTION_BOOT_COMPLETED -> {
                        LogKeeper.logLifecycle(context, "BootReceiver", "BOOT_COMPLETED", "Device boot completed")
                    }
                    Intent.ACTION_MY_PACKAGE_REPLACED -> {
                        LogKeeper.logLifecycle(context, "BootReceiver", "PACKAGE_REPLACED", "Package replaced/updated")
                    }
                    ACTION_RECOVER_MAIN -> {
                        LogKeeper.logLifecycle(context, "BootReceiver", "RECOVER_MAIN", "One-shot crash recovery alarm triggered")
                    }
                }
                LogKeeper.log(context, "BootReceiver", "Dispatching HandleService.startIfConfigured()")
                HandleService.startIfConfigured(context)
            } catch (e: Exception) {
                LogKeeper.logError(context, "BootReceiver", "Failed to start service on $action", e)
            }
        }
    }
}
