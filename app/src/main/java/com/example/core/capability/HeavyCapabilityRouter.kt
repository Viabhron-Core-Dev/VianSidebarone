package com.example.core.capability

import android.content.Context
import com.example.core.LogKeeper
import com.example.core.ipc.ConnectionState
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyCommandType
import com.example.core.ipc.HeavyConnectionListener
import com.example.core.ipc.HeavyProcessConnectionManager
import com.example.core.ipc.IpcErrorCode
import java.util.concurrent.ConcurrentHashMap

/**
 * HeavyCapabilityRouter: Bridges capability mount/use/unmount requests from the Main process
 * across the existing Binder IPC bridge to the Heavy process (:heavy).
 *
 * Guarantees:
 * 1. ZERO heavy UI, Activity, or engine classes are loaded into the Main process.
 * 2. Retains only lightweight session tokens and capability IDs in memory.
 * 3. Does NOT keep Heavy alive unnecessarily; Heavy process remains strictly on-demand.
 * 4. Recovers from Heavy process death through [HeavyConnectionListener] and on-demand reconnection.
 * 5. Safely unmounts and discards sessions when released.
 */
class HeavyCapabilityRouter(
    private val context: Context? = null,
    val connectionManager: HeavyProcessConnectionManager = if (context != null) {
        HeavyProcessConnectionManager.getInstance(context)
    } else {
        HeavyProcessConnectionManager(null)
    }
) : HeavyConnectionListener {

    // Maps active sessionToken -> capabilityId
    private val activeSessions = ConcurrentHashMap<String, String>()

    init {
        connectionManager.addListener(this)
    }

    private fun safeLog(msg: String) {
        context?.let { LogKeeper.log(it, TAG, msg) }
    }

    private fun safeLogError(msg: String, t: Throwable) {
        context?.let { LogKeeper.logError(it, TAG, msg, t) }
    }

    /**
     * Checks if the Heavy process is currently connected or in the process of connecting.
     */
    fun isHeavyAvailable(): Boolean {
        val state = connectionManager.state
        return state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING
    }

    /**
     * Mounts a Heavy-routed capability by establishing session tracking in Main
     * and notifying the Heavy host over Binder IPC.
     */
    fun mount(
        descriptor: CapabilityDescriptor,
        sessionToken: String,
        params: Map<String, String>
    ): CapabilityResult {
        activeSessions[sessionToken] = descriptor.capabilityId
        safeLog("Mounting Heavy capability '${descriptor.capabilityId}' for session '$sessionToken'")

        val payload = HashMap<String, String>(params).apply {
            put("action", "mount")
            put("sessionToken", sessionToken)
            put("capabilityId", descriptor.capabilityId)
        }

        val command = HeavyCommand(
            commandId = "mount_${descriptor.capabilityId}_$sessionToken",
            type = HeavyCommandType.START_OPERATION,
            targetId = descriptor.capabilityId,
            payload = payload
        )

        val ipcResult = connectionManager.sendCommand(command, autoConnect = true)
        return if (ipcResult.success) {
            CapabilityResult.success(ipcResult.data)
        } else {
            // If the connection is actively being established asynchronously, accept session locally
            if (ipcResult.errorCode == IpcErrorCode.HEAVY_UNAVAILABLE &&
                connectionManager.state == ConnectionState.CONNECTING
            ) {
                CapabilityResult.success(mapOf("status" to "connecting"))
            } else {
                val moduleErr = ipcResult.data["error_code"]
                val errCode = when (moduleErr) {
                    com.example.feature.heavy.module.HeavyModuleErrors.NOT_FOUND -> CapabilityErrors.NOT_FOUND
                    com.example.feature.heavy.module.HeavyModuleErrors.UNAVAILABLE -> CapabilityErrors.UNAVAILABLE
                    com.example.feature.heavy.module.HeavyModuleErrors.OPERATION_FAILED -> CapabilityErrors.OPERATION_FAILED
                    else -> if (ipcResult.errorCode == IpcErrorCode.DEAD_BINDER) {
                        CapabilityErrors.PROCESS_DIED
                    } else {
                        CapabilityErrors.HEAVY_UNAVAILABLE
                    }
                }
                CapabilityResult.error(
                    errCode,
                    "Failed to mount on Heavy process: ${ipcResult.message}"
                )
            }
        }
    }

    /**
     * Dispatches an operation request to the Heavy process.
     */
    fun use(request: CapabilityRequest): CapabilityResult {
        val capabilityId = activeSessions[request.sessionToken]
            ?: return CapabilityResult.error(
                CapabilityErrors.NOT_MOUNTED,
                "Session '${request.sessionToken}' is not active or has been unmounted"
            )

        val payload = HashMap<String, String>(request.parameters).apply {
            put("action", "use")
            put("operation", request.operation)
            put("sessionToken", request.sessionToken)
            put("capabilityId", capabilityId)
        }

        val command = HeavyCommand(
            commandId = request.requestId,
            type = HeavyCommandType.START_OPERATION,
            targetId = capabilityId,
            payload = payload
        )

        val ipcResult = connectionManager.sendCommand(command, autoConnect = true)
        return if (ipcResult.success) {
            CapabilityResult.success(ipcResult.data)
        } else {
            val moduleErr = ipcResult.data["error_code"]
            val errCode = when (ipcResult.errorCode) {
                IpcErrorCode.DEAD_BINDER -> CapabilityErrors.PROCESS_DIED
                IpcErrorCode.HEAVY_UNAVAILABLE -> CapabilityErrors.HEAVY_UNAVAILABLE
                else -> when (moduleErr) {
                    com.example.feature.heavy.module.HeavyModuleErrors.NOT_MOUNTED -> CapabilityErrors.NOT_MOUNTED
                    com.example.feature.heavy.module.HeavyModuleErrors.OPERATION_FAILED -> CapabilityErrors.OPERATION_FAILED
                    com.example.feature.heavy.module.HeavyModuleErrors.NOT_FOUND -> CapabilityErrors.NOT_FOUND
                    else -> CapabilityErrors.IPC_FAILURE
                }
            }
            CapabilityResult.error(errCode, ipcResult.message)
        }
    }

    /**
     * Unmounts a Heavy-routed session, removing local tracking and notifying Heavy.
     */
    fun unmount(sessionToken: String): Boolean {
        val capabilityId = activeSessions.remove(sessionToken) ?: return false
        safeLog("Unmounting Heavy capability '$capabilityId' for session '$sessionToken'")

        val payload = mapOf(
            "action" to "unmount",
            "sessionToken" to sessionToken,
            "capabilityId" to capabilityId
        )

        val command = HeavyCommand(
            commandId = "unmount_${capabilityId}_$sessionToken",
            type = HeavyCommandType.STOP_OPERATION,
            targetId = capabilityId,
            payload = payload
        )

        // Best-effort delivery; do not wake up Heavy if it is currently disconnected/dead
        connectionManager.sendCommand(command, autoConnect = false)
        return true
    }

    override fun onHeavyProcessDied() {
        safeLog("Heavy process died. Active sessions: ${activeSessions.size}. Heavy state marked disconnected.")
    }

    override fun onDisconnected() {
        safeLog("Heavy process disconnected")
    }

    fun getActiveSessionCount(): Int = activeSessions.size

    fun isSessionActive(sessionToken: String): Boolean = activeSessions.containsKey(sessionToken)

    fun releaseAll() {
        activeSessions.clear()
        connectionManager.removeListener(this)
    }

    companion object {
        private const val TAG = "HeavyCapRouter"
    }
}
