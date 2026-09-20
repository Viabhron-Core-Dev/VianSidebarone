package com.example.core.ipc

import android.content.Context
import android.os.IBinder
import com.example.core.LogKeeper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Command listener for heavy components running in the Heavy process.
 */
fun interface HeavyCommandHandler {
    fun handleCommand(command: HeavyCommand): IpcResult
}

/**
 * HeavyProcessHost: Runtime host controller operating strictly within the Heavy process (:heavy).
 *
 * Responsibilities:
 * 1. Hosts the HeavyHostBinder and serves as the single entry point for incoming Main commands.
 * 2. Stores a read-only cached MainStateSnapshot for context (does NOT duplicate the Main hierarchy).
 * 3. Bridges callbacks and action requests back to Main via IMainCallbackContract.
 * 4. Dispatches commands to registered Heavy components (e.g., HeavyFloatingHost).
 */
class HeavyProcessHost internal constructor(private val context: Context? = null) : IHeavyHostHandler {

    private val binder by lazy { HeavyHostBinder(this) }
    private val commandHandlers = ConcurrentHashMap<HeavyCommandType, CopyOnWriteArrayList<HeavyCommandHandler>>()

    @Volatile
    var callbackProxy: IMainCallbackContract? = null
        private set

    @Volatile
    var cachedSnapshot: MainStateSnapshot? = null
        private set

    @Volatile
    private var callHostExtension: com.example.feature.call.HeavyCallHostExtension =
        com.example.feature.call.DefaultHeavyCallHostExtension()

    @Volatile
    private var welcomeHostExtension: com.example.feature.welcome.HeavyWelcomeHostExtension =
        com.example.feature.welcome.DefaultHeavyWelcomeHostExtension(context)

    @Volatile
    private var moduleManager: com.example.feature.heavy.module.HeavyModuleManager? = null

    private fun safeLog(tag: String, msg: String) {
        context?.let { LogKeeper.log(it, tag, msg) }
    }

    private fun safeLogCrash(tag: String, t: Throwable) {
        context?.let { LogKeeper.logCrash(it, tag, t) }
    }

    init {
        safeLog("HeavyProcessHost", "HeavyProcessHost initialized in :heavy")
        registerCommandHandler(HeavyCommandType.CALL_STATE_CHANGED) { cmd ->
            safeLog("HeavyProcessHost", "Routing CALL_STATE_CHANGED to CallHostExtension")
            callHostExtension.onCallStateChanged(cmd)
        }
        registerCommandHandler(HeavyCommandType.REQUEST_CALL_RECORDER) { cmd ->
            safeLog("HeavyProcessHost", "Routing REQUEST_CALL_RECORDER to CallHostExtension")
            callHostExtension.onRequestCallRecorder(cmd)
        }
        registerCommandHandler(HeavyCommandType.SHOW_WELCOME) { cmd ->
            safeLog("HeavyProcessHost", "Routing SHOW_WELCOME to WelcomeHostExtension")
            welcomeHostExtension.onShowWelcome(cmd)
        }
    }

    fun getModuleManager(): com.example.feature.heavy.module.HeavyModuleManager {
        return moduleManager ?: synchronized(this) {
            moduleManager ?: com.example.feature.heavy.module.HeavyModuleManager.getInstance(context).also {
                moduleManager = it
            }
        }
    }

    fun setModuleManager(manager: com.example.feature.heavy.module.HeavyModuleManager) {
        this.moduleManager = manager
    }

    fun setCallHostExtension(extension: com.example.feature.call.HeavyCallHostExtension) {
        this.callHostExtension = extension
    }

    fun getCallHostExtension(): com.example.feature.call.HeavyCallHostExtension = callHostExtension

    fun setWelcomeHostExtension(extension: com.example.feature.welcome.HeavyWelcomeHostExtension) {
        this.welcomeHostExtension = extension
    }

    fun getWelcomeHostExtension(): com.example.feature.welcome.HeavyWelcomeHostExtension = welcomeHostExtension

    fun handleCommand(command: HeavyCommand): IpcResult {
        val resJson = onSendCommand(command.toJson())
        return IpcResult.fromJson(resJson)
    }

    fun getBinder(): IBinder = binder

    /**
     * Registers a command handler for a specific command type in the Heavy process.
     */
    fun registerCommandHandler(type: HeavyCommandType, handler: HeavyCommandHandler) {
        commandHandlers.computeIfAbsent(type) { CopyOnWriteArrayList() }.add(handler)
    }

    /**
     * Unregisters a command handler.
     */
    fun unregisterCommandHandler(type: HeavyCommandType, handler: HeavyCommandHandler) {
        commandHandlers[type]?.remove(handler)
    }

    /**
     * Reports a state or lifecycle event back to the Main process.
     */
    fun reportEvent(event: HeavyEvent): IpcResult {
        val proxy = callbackProxy ?: return IpcResult.error(
            IpcErrorCode.HEAVY_UNAVAILABLE,
            "Main callback proxy is not connected"
        )
        return try {
            val resJson = proxy.onHeavyEvent(event.toJson())
            IpcResult.fromJson(resJson)
        } catch (e: Throwable) {
            safeLogCrash("HeavyProcessHost", e)
            IpcResult.error(IpcErrorCode.UNKNOWN_ERROR, e.message ?: "reportEvent failed")
        }
    }

    /**
     * Requests the Main process to execute a scoped action (e.g. close window, show toast).
     */
    fun requestMainAction(request: MainCommandRequest): IpcResult {
        val proxy = callbackProxy ?: return IpcResult.error(
            IpcErrorCode.HEAVY_UNAVAILABLE,
            "Main callback proxy is not connected"
        )
        return try {
            val resJson = proxy.requestMainAction(request.toJson())
            IpcResult.fromJson(resJson)
        } catch (e: Throwable) {
            safeLogCrash("HeavyProcessHost", e)
            IpcResult.error(IpcErrorCode.UNKNOWN_ERROR, e.message ?: "requestMainAction failed")
        }
    }

    // --- IHeavyHostHandler Implementation ---

    private fun isModuleCommand(cmd: HeavyCommand): Boolean {
        val action = cmd.payload["action"]
        return action in listOf("mount", "use", "unmount", "unmountAll") ||
                cmd.payload.containsKey("capabilityId") ||
                cmd.payload.containsKey("moduleId")
    }

    override fun onSendCommand(commandJson: String): String {
        return try {
            val command = HeavyCommand.fromJson(commandJson)
            safeLog("HeavyProcessHost", "Received command ${command.type} (id=${command.commandId})")

            if (isModuleCommand(command)) {
                safeLog("HeavyProcessHost", "Routing module command '${command.commandId}' to HeavyModuleManager")
                val moduleRes = getModuleManager().handleIpcCommand(command)
                return moduleRes.toJson()
            }

            val handlers = commandHandlers[command.type]
            if (handlers.isNullOrEmpty()) {
                // Default acknowledgment
                IpcResult.success("Acknowledged command ${command.type}").toJson()
            } else {
                var finalResult = IpcResult.success()
                for (handler in handlers) {
                    val res = handler.handleCommand(command)
                    finalResult = res
                    if (!res.success) {
                        break
                    }
                }
                finalResult.toJson()
            }
        } catch (e: Throwable) {
            safeLogCrash("HeavyProcessHost", e)
            IpcResult.error(IpcErrorCode.UNKNOWN_ERROR, "Error handling command: ${e.message}").toJson()
        }
    }

    override fun onSyncSnapshot(snapshotJson: String): String {
        return try {
            val snapshot = MainStateSnapshot.fromJson(snapshotJson)
            cachedSnapshot = snapshot
            safeLog("HeavyProcessHost", "Cached Main snapshot (id=${snapshot.snapshotId})")
            IpcResult.success("Snapshot cached").toJson()
        } catch (e: Throwable) {
            safeLogCrash("HeavyProcessHost", e)
            IpcResult.error(IpcErrorCode.MARSHAL_ERROR, "Error caching snapshot: ${e.message}").toJson()
        }
    }

    override fun onPing(): Boolean = true

    override fun onCallbackRegistered(callback: IMainCallbackContract) {
        callbackProxy = callback
        safeLog("HeavyProcessHost", "Main callback proxy registered successfully")
    }

    override fun onCallbackUnregistered() {
        callbackProxy = null
        safeLog("HeavyProcessHost", "Main callback proxy unregistered")
    }

    companion object {
        @Volatile
        private var instance: HeavyProcessHost? = null

        fun getInstance(context: Context): HeavyProcessHost {
            return instance ?: synchronized(this) {
                instance ?: HeavyProcessHost(context.applicationContext).also { instance = it }
            }
        }
    }
}
