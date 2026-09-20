package com.example.core.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.example.core.LogKeeper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lifecycle states of the connection from Main to Heavy process.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DEAD
}

/**
 * Listener interface for Main ↔ Heavy connection lifecycle and incoming callback notifications.
 */
interface HeavyConnectionListener {
    fun onConnected() {}
    fun onDisconnected() {}
    fun onHeavyProcessDied() {}
    fun onHeavyEvent(event: HeavyEvent) {}
    fun onMainActionRequested(request: MainCommandRequest): IpcResult = IpcResult.success()
}

/**
 * HeavyProcessConnectionManager: Resident connection and lifecycle manager in the Main process (com.example).
 *
 * Guarantees:
 * 1. Main remains the sole authoritative source of truth for runtime hierarchy and state.
 * 2. Connects on-demand to the Heavy process (:heavy) via Android Binder.
 * 3. Monitors Heavy process death via IBinder.DeathRecipient without crashing Main.
 * 4. Safe no-op and graceful failure when Heavy is unavailable.
 * 5. Clean disconnect and unbind when idle.
 */
class HeavyProcessConnectionManager internal constructor(
    private val context: Context? = null
) {

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<HeavyConnectionListener>()

    @Volatile
    var state: ConnectionState = ConnectionState.DISCONNECTED
        private set

    @Volatile
    private var remoteProxy: IHeavyHostContract? = null

    @Volatile
    private var remoteBinder: IBinder? = null

    @Volatile
    private var isServiceBound = false

    private val pendingCommands = java.util.concurrent.ConcurrentLinkedQueue<HeavyCommand>()

    private var latestSnapshot: MainStateSnapshot? = null

    private fun safeLog(tag: String, msg: String) {
        context?.let { LogKeeper.log(it, tag, msg) }
    }

    private fun safeLogCrash(tag: String, t: Throwable) {
        context?.let { LogKeeper.logCrash(it, tag, t) }
    }

    private val callbackHandler = object : IMainCallbackHandler {
        override fun onHeavyEvent(eventJson: String): String {
            val event = try {
                HeavyEvent.fromJson(eventJson)
            } catch (e: Exception) {
                return IpcResult.error(IpcErrorCode.MARSHAL_ERROR, "Failed to parse event: ${e.message}").toJson()
            }
            dispatchHeavyEvent(event)
            return IpcResult.success().toJson()
        }

        override fun onRequestMainAction(requestJson: String): String {
            val request = try {
                MainCommandRequest.fromJson(requestJson)
            } catch (e: Exception) {
                return IpcResult.error(IpcErrorCode.MARSHAL_ERROR, "Failed to parse action request: ${e.message}").toJson()
            }
            val result = dispatchMainAction(request)
            return result.toJson()
        }
    }

    private val callbackBinder by lazy { MainCallbackBinder(callbackHandler) }

    private val deathRecipient = IBinder.DeathRecipient {
        synchronized(lock) {
            safeLog("HeavyProcessConnectionManager", "Heavy process binder died (DeathRecipient triggered)")
            context?.let { ctx ->
                LogKeeper.logLifecycle(ctx, "HeavyProcess", "HEAVY_PROCESS_DIED", "DeathRecipient triggered: Binder connection to :heavy terminated")
            }
            state = ConnectionState.DEAD
            remoteProxy = null
            remoteBinder = null
            isServiceBound = false
        }
        for (listener in listeners) {
            try {
                listener.onHeavyProcessDied()
            } catch (e: Throwable) {
                safeLogCrash("HeavyProcessConnectionManager", e)
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            synchronized(lock) {
                if (service == null) {
                    state = ConnectionState.DISCONNECTED
                    return
                }
                try {
                    service.linkToDeath(deathRecipient, 0)
                    remoteBinder = service
                    val proxy = HeavyHostProxy(service)
                    proxy.registerCallback(callbackBinder)
                    remoteProxy = proxy
                    state = ConnectionState.CONNECTED
                    isServiceBound = true
                    safeLog("HeavyProcessConnectionManager", "Heavy process bound successfully (CONNECTED)")

                    // Re-sync cached snapshot if available
                    latestSnapshot?.let { snapshot ->
                        proxy.syncSnapshot(snapshot.toJson())
                    }

                    // Flush any pending commands queued during connection startup
                    flushPendingCommands(proxy)
                } catch (e: Throwable) {
                    safeLogCrash("HeavyProcessConnectionManager", e)
                    state = ConnectionState.DISCONNECTED
                    return
                }
            }

            for (listener in listeners) {
                try {
                    listener.onConnected()
                } catch (e: Throwable) {
                    safeLogCrash("HeavyProcessConnectionManager", e)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) {
                safeLog("HeavyProcessConnectionManager", "Heavy process unexpectedly disconnected")
                state = ConnectionState.DISCONNECTED
                remoteProxy = null
                remoteBinder = null
                isServiceBound = false
            }

            for (listener in listeners) {
                try {
                    listener.onDisconnected()
                } catch (e: Throwable) {
                    safeLogCrash("HeavyProcessConnectionManager", e)
                }
            }
        }
    }

    fun addListener(listener: HeavyConnectionListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: HeavyConnectionListener) {
        listeners.remove(listener)
    }

    /**
     * Binds asynchronously to HeavyFloatingHostService running in :heavy.
     */
    fun connect(autoCreate: Boolean = true): Boolean {
        val ctx = context ?: run {
            state = ConnectionState.DISCONNECTED
            return false
        }
        synchronized(lock) {
            if (state == ConnectionState.CONNECTED) return true
            if (state == ConnectionState.CONNECTING) return true

            state = ConnectionState.CONNECTING
            return try {
                val intent = Intent().setClassName(
                    ctx.packageName,
                    "com.example.feature.floating.HeavyFloatingHostService"
                )
                val flags = if (autoCreate) Context.BIND_AUTO_CREATE else 0
                val bound = ctx.bindService(intent, serviceConnection, flags)
                if (!bound) {
                    state = ConnectionState.DISCONNECTED
                    safeLog("HeavyProcessConnectionManager", "bindService returned false (Heavy unavailable)")
                }
                bound
            } catch (e: Throwable) {
                safeLogCrash("HeavyProcessConnectionManager", e)
                state = ConnectionState.DISCONNECTED
                false
            }
        }
    }

    /**
     * Unbinds from Heavy process cleanly.
     */
    fun disconnect() {
        synchronized(lock) {
            if (!isServiceBound && state == ConnectionState.DISCONNECTED) return

            try {
                remoteBinder?.unlinkToDeath(deathRecipient, 0)
            } catch (ignored: Exception) {}

            try {
                if (isServiceBound) {
                    context?.unbindService(serviceConnection)
                }
            } catch (e: Throwable) {
                safeLog("HeavyProcessConnectionManager", "Error unbinding service: ${e.message}")
            }

            remoteProxy = null
            remoteBinder = null
            isServiceBound = false
            pendingCommands.clear()
            state = ConnectionState.DISCONNECTED
            safeLog("HeavyProcessConnectionManager", "Disconnected from Heavy process")
        }

        for (listener in listeners) {
            try {
                listener.onDisconnected()
            } catch (e: Throwable) {
                safeLogCrash("HeavyProcessConnectionManager", e)
            }
        }
    }

    /**
     * Forces a clean disconnect and reconnect sequence.
     */
    fun reconnect(): Boolean {
        disconnect()
        return connect(autoCreate = true)
    }

    /**
     * Sends an immutable HeavyCommand across the Binder boundary.
     * Guarantees safe failure if Heavy is unavailable or dead.
     */
    fun sendCommand(command: HeavyCommand, autoConnect: Boolean = true): IpcResult {
        val proxy: IHeavyHostContract?
        synchronized(lock) {
            if (state != ConnectionState.CONNECTED) {
                if (autoConnect) {
                    if (pendingCommands.size < 20) {
                        pendingCommands.offer(command)
                    }
                    connect(autoCreate = true)
                }
                return IpcResult.error(
                    IpcErrorCode.HEAVY_UNAVAILABLE,
                    "Heavy process is not connected (state=$state)"
                )
            }
            proxy = remoteProxy
        }

        if (proxy == null) {
            return IpcResult.error(IpcErrorCode.HEAVY_UNAVAILABLE, "Remote proxy is null")
        }

        return try {
            val responseJson = proxy.sendCommand(command.toJson())
            val result = IpcResult.fromJson(responseJson)
            if (result.errorCode == IpcErrorCode.DEAD_BINDER) {
                handleDeadBinder()
            }
            result
        } catch (e: Throwable) {
            safeLogCrash("HeavyProcessConnectionManager", e)
            IpcResult.error(IpcErrorCode.UNKNOWN_ERROR, e.message ?: "sendCommand failed")
        }
    }

    /**
     * Syncs a minimal immutable snapshot of Main state to Heavy.
     */
    fun syncSnapshot(snapshot: MainStateSnapshot, autoConnect: Boolean = false): IpcResult {
        latestSnapshot = snapshot
        val proxy: IHeavyHostContract?
        synchronized(lock) {
            if (state != ConnectionState.CONNECTED) {
                if (autoConnect) {
                    connect(autoCreate = true)
                }
                return IpcResult.error(
                    IpcErrorCode.HEAVY_UNAVAILABLE,
                    "Heavy process is not connected; cached snapshot for later"
                )
            }
            proxy = remoteProxy
        }

        if (proxy == null) {
            return IpcResult.error(IpcErrorCode.HEAVY_UNAVAILABLE, "Remote proxy is null")
        }

        return try {
            val responseJson = proxy.syncSnapshot(snapshot.toJson())
            val result = IpcResult.fromJson(responseJson)
            if (result.errorCode == IpcErrorCode.DEAD_BINDER) {
                handleDeadBinder()
            }
            result
        } catch (e: Throwable) {
            safeLogCrash("HeavyProcessConnectionManager", e)
            IpcResult.error(IpcErrorCode.UNKNOWN_ERROR, e.message ?: "syncSnapshot failed")
        }
    }

    /**
     * Fast health check pinging Heavy process.
     */
    fun ping(): Boolean {
        val proxy = remoteProxy ?: return false
        return try {
            proxy.ping()
        } catch (e: Throwable) {
            false
        }
    }

    private fun handleDeadBinder() {
        synchronized(lock) {
            state = ConnectionState.DEAD
            remoteProxy = null
            remoteBinder = null
            isServiceBound = false
            pendingCommands.clear()
        }
        for (listener in listeners) {
            try {
                listener.onHeavyProcessDied()
            } catch (ignored: Exception) {}
        }
    }

    private fun flushPendingCommands(proxy: IHeavyHostContract) {
        while (pendingCommands.isNotEmpty()) {
            val pendingCmd = pendingCommands.poll() ?: break
            try {
                val resJson = proxy.sendCommand(pendingCmd.toJson())
                safeLog("HeavyProcessConnectionManager", "Delivered queued command ${pendingCmd.commandId}: $resJson")
            } catch (e: Throwable) {
                safeLogCrash("HeavyProcessConnectionManager", e)
            }
        }
    }

    private fun dispatchHeavyEvent(event: HeavyEvent) {
        for (listener in listeners) {
            try {
                listener.onHeavyEvent(event)
            } catch (e: Throwable) {
                safeLogCrash("HeavyProcessConnectionManager", e)
            }
        }
    }

    private fun dispatchMainAction(request: MainCommandRequest): IpcResult {
        var finalResult = IpcResult.success()
        for (listener in listeners) {
            try {
                val res = listener.onMainActionRequested(request)
                if (!res.success) finalResult = res
            } catch (e: Throwable) {
                safeLogCrash("HeavyProcessConnectionManager", e)
                return IpcResult.error(IpcErrorCode.UNKNOWN_ERROR, e.message ?: "Handler failed")
            }
        }
        return finalResult
    }

    // Testing / internal hooks
    internal fun setMockProxyForTesting(proxy: IHeavyHostContract?, testState: ConnectionState = ConnectionState.CONNECTED) {
        synchronized(lock) {
            remoteProxy = proxy
            state = testState
        }
        if (testState == ConnectionState.CONNECTED && proxy != null) {
            flushPendingCommands(proxy)
        }
    }

    internal fun getPendingCommandCountForTesting(): Int = pendingCommands.size

    internal fun triggerDeathRecipientForTesting() {
        deathRecipient.binderDied()
    }

    companion object {
        @Volatile
        private var instance: HeavyProcessConnectionManager? = null

        fun getInstance(context: Context): HeavyProcessConnectionManager {
            return instance ?: synchronized(this) {
                instance ?: HeavyProcessConnectionManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
