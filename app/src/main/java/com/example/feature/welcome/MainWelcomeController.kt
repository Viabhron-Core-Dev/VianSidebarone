package com.example.feature.welcome

import android.content.Context
import com.example.core.LogKeeper
import com.example.core.ipc.HeavyProcessConnectionManager
import com.example.core.ipc.IpcErrorCode
import com.example.core.ipc.IpcResult
import com.example.core.ipc.WelcomeIpcContract

/**
 * Lightweight Main-process coordinator to request the Welcome / Permission setup UI in :heavy.
 *
 * Characteristics:
 * 1. Zero UI or Compose dependencies in Main: keeps Main lightweight and resident.
 * 2. Asynchronously wakes/requests the Heavy process via the existing HeavyProcessConnectionManager IPC.
 * 3. Process death resilient: If Heavy is dead or unavailable, does NOT crash or stall Main.
 */
class MainWelcomeController internal constructor(
    private val context: Context?,
    private val connectionManager: HeavyProcessConnectionManager? = null
) {

    fun requestWelcome(reason: String = WelcomeIpcContract.REASON_SETUP): IpcResult {
        val cm = connectionManager ?: context?.let {
            HeavyProcessConnectionManager.getInstance(it)
        }

        if (cm == null) {
            safeLog(TAG, "HeavyProcessConnectionManager unavailable; cannot request Welcome")
            return IpcResult.error(
                IpcErrorCode.HEAVY_UNAVAILABLE,
                "HeavyProcessConnectionManager is unavailable"
            )
        }

        val command = WelcomeIpcContract.createShowWelcomeCommand(reason)
        return try {
            val result = cm.sendCommand(command, autoConnect = true)
            safeLog(TAG, "Requested Welcome (reason=$reason): success=${result.success}")
            result
        } catch (e: Throwable) {
            safeLogCrash(TAG, e)
            IpcResult.error(
                IpcErrorCode.UNKNOWN_ERROR,
                "Exception dispatching SHOW_WELCOME: ${e.message}"
            )
        }
    }

    private fun safeLog(tag: String, msg: String) {
        context?.let { LogKeeper.log(it, tag, msg) }
    }

    private fun safeLogCrash(tag: String, t: Throwable) {
        context?.let { LogKeeper.logCrash(it, tag, t) }
    }

    companion object {
        private const val TAG = "MainWelcomeController"

        @Volatile
        private var instance: MainWelcomeController? = null

        fun getInstance(context: Context): MainWelcomeController {
            return instance ?: synchronized(this) {
                instance ?: MainWelcomeController(context.applicationContext).also { instance = it }
            }
        }

        internal fun resetInstanceForTesting() {
            instance = null
        }
    }
}
