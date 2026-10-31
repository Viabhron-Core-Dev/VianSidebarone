package com.example.feature.floating

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.example.core.LogKeeper
import com.example.core.WindowBounds
import com.example.core.ipc.HeavyCommandType
import com.example.core.ipc.HeavyProcessHost
import com.example.core.ipc.IpcErrorCode
import com.example.core.ipc.IpcResult

/**
 * HeavyFloatingHostService: On-demand, disposable host Service running in the Heavy process (:heavy).
 *
 * Characteristics:
 * 1. Runs exclusively in android:process=":heavy".
 * 2. On-demand: Triggered only when a mini-app is launched, controlled, or restored.
 * 3. Disposable: Automatically terminates via stopSelf() when no active mini-apps remain
 *    (and no heavy pages, modules, or call recordings), returning Heavy process memory to 0 MB idle.
 * 4. Sleeping mini-apps do NOT keep Heavy alive (their runtimes are destroyed and state committed in Main).
 * 5. START_NOT_STICKY to avoid unsolicited background wakeups.
 */
class HeavyFloatingHostService : Service() {

    private lateinit var host: HeavyFloatingHost
    private lateinit var ipcHost: HeavyProcessHost

    override fun onCreate() {
        super.onCreate()
        host = HeavyFloatingHost.getInstance(this)
        ipcHost = HeavyProcessHost.getInstance(this)

        // Listen for HeavyModuleManager becoming idle
        com.example.feature.heavy.module.HeavyModuleManager.getInstance(this).setIdleListener {
            checkDisposableTeardown()
        }

        // Wire incoming Binder commands to HeavyFloatingHost
        ipcHost.registerCommandHandler(HeavyCommandType.START_OPERATION) { cmd ->
            val action = cmd.payload["action"]
            if (action in listOf("mount", "use", "unmount", "unmountAll") ||
                cmd.payload.containsKey("capabilityId") ||
                cmd.payload.containsKey("moduleId") ||
                (!cmd.payload.containsKey("instance_id") && !cmd.payload.containsKey("app_type"))
            ) {
                return@registerCommandHandler IpcResult.success()
            }
            val instanceId = cmd.targetId ?: cmd.payload["instance_id"] ?: ""
            val appType = cmd.payload["app_type"] ?: "generic"
            val bx = cmd.payload["bounds_x"]?.toIntOrNull() ?: 100
            val by = cmd.payload["bounds_y"]?.toIntOrNull() ?: 100
            val bw = cmd.payload["bounds_w"]?.toIntOrNull() ?: 600
            val bh = cmd.payload["bounds_h"]?.toIntOrNull() ?: 800
            val bounds = WindowBounds(bx, by, bw, bh)

            // Extract custom initial payload keys
            val payload = LinkedHashMap<String, String>()
            for ((k, v) in cmd.payload) {
                if (k !in listOf("instance_id", "app_type", "bounds_x", "bounds_y", "bounds_w", "bounds_h", "action")) {
                    payload[k] = v
                }
            }

            if (instanceId.isNotEmpty()) {
                val inst = host.startInstance(instanceId, appType, bounds, payload)
                if (inst != null) {
                    IpcResult.success("Started $instanceId")
                } else {
                    IpcResult.error(IpcErrorCode.COMMAND_FAILED, "Failed to start $instanceId")
                }
            } else {
                IpcResult.error(IpcErrorCode.MARSHAL_ERROR, "Missing instanceId")
            }
        }

        ipcHost.registerCommandHandler(HeavyCommandType.PAUSE_OPERATION) { cmd ->
            val action = cmd.payload["action"]
            if (action in listOf("mount", "use", "unmount", "unmountAll") ||
                cmd.payload.containsKey("capabilityId") ||
                cmd.payload.containsKey("moduleId") ||
                (!cmd.payload.containsKey("instance_id") && !cmd.payload.containsKey("app_type"))
            ) {
                return@registerCommandHandler IpcResult.success()
            }
            val instanceId = cmd.targetId ?: cmd.payload["instance_id"] ?: ""
            if (instanceId.isNotEmpty()) {
                val isSleep = cmd.payload["sleep"] == "true" || action == "sleep"
                val returnedData = if (isSleep) {
                    val payload = host.prepareSleep(instanceId, cmd.payload)
                    checkDisposableTeardown()
                    payload
                } else {
                    host.pauseInstance(instanceId)
                }
                IpcResult.success(if (isSleep) "Slept $instanceId" else "Paused $instanceId", returnedData)
            } else {
                IpcResult.error(IpcErrorCode.MARSHAL_ERROR, "Missing instanceId")
            }
        }

        ipcHost.registerCommandHandler(HeavyCommandType.STOP_OPERATION) { cmd ->
            val action = cmd.payload["action"]
            if (action in listOf("mount", "use", "unmount", "unmountAll") ||
                cmd.payload.containsKey("capabilityId") ||
                cmd.payload.containsKey("moduleId") ||
                (!cmd.payload.containsKey("instance_id") && !cmd.payload.containsKey("app_type"))
            ) {
                return@registerCommandHandler IpcResult.success()
            }
            val instanceId = cmd.targetId ?: cmd.payload["instance_id"] ?: ""
            if (instanceId.isNotEmpty()) {
                val payload = host.stopInstance(instanceId)
                checkDisposableTeardown()
                IpcResult.success("Stopped $instanceId", payload)
            } else {
                IpcResult.error(IpcErrorCode.MARSHAL_ERROR, "Missing instanceId")
            }
        }

        LogKeeper.log(this, "HeavyFloatingHostService", "Heavy process floating service created with Binder IPC")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            checkDisposableTeardown()
            return START_NOT_STICKY
        }

        val action = intent.action
        val instanceId = intent.getStringExtra(MiniAppIpcContracts.EXTRA_INSTANCE_ID)

        when (action) {
            MiniAppIpcContracts.ACTION_START_MINIAPP -> {
                val appType = intent.getStringExtra(MiniAppIpcContracts.EXTRA_APP_TYPE) ?: "generic"
                val bx = intent.getIntExtra(MiniAppIpcContracts.EXTRA_BOUNDS_X, 100)
                val by = intent.getIntExtra(MiniAppIpcContracts.EXTRA_BOUNDS_Y, 100)
                val bw = intent.getIntExtra(MiniAppIpcContracts.EXTRA_BOUNDS_W, 600)
                val bh = intent.getIntExtra(MiniAppIpcContracts.EXTRA_BOUNDS_H, 800)
                val bounds = WindowBounds(bx, by, bw, bh)

                if (!instanceId.isNullOrEmpty()) {
                    host.startInstance(instanceId, appType, bounds)
                }
            }

            MiniAppIpcContracts.ACTION_SLEEP_MINIAPP -> {
                if (!instanceId.isNullOrEmpty()) {
                    host.prepareSleep(instanceId)
                }
            }

            MiniAppIpcContracts.ACTION_PAUSE_MINIAPP -> {
                if (!instanceId.isNullOrEmpty()) {
                    host.pauseInstance(instanceId)
                }
            }

            MiniAppIpcContracts.ACTION_STOP_MINIAPP -> {
                if (!instanceId.isNullOrEmpty()) {
                    host.stopInstance(instanceId)
                }
            }

            MiniAppIpcContracts.ACTION_DESTROY_MINIAPP -> {
                if (!instanceId.isNullOrEmpty()) {
                    host.destroyInstance(instanceId)
                }
            }
        }

        checkDisposableTeardown()
        return START_NOT_STICKY
    }

    /**
     * Checks if any active workloads remain in Heavy.
     * Sleeping mini-apps return 0 in getActiveInstanceCount(), allowing teardown.
     */
    fun checkDisposableTeardown() {
        val activeFloating = host.getActiveInstanceCount()
        val activeModules = com.example.feature.heavy.module.HeavyModuleManager.getInstance(this).getActiveSessionCount()
        val isCallRecording = HeavyProcessHost.getInstance(this).getCallHostExtension().isRecordingActive()
        val activePages = com.example.feature.heavy.page.HeavyPageHostManager.getInstance(this).getActivePageCount()

        if (activeFloating == 0 && activeModules == 0 && !isCallRecording && activePages == 0) {
            LogKeeper.log(this, "HeavyFloatingHostService", "No active mini-apps ($activeFloating), modules ($activeModules), call recordings ($isCallRecording), or heavy pages ($activePages); stopping Heavy host")
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        com.example.feature.heavy.module.HeavyModuleManager.getInstance(this).setIdleListener(null)
        com.example.feature.heavy.page.HeavyPageHostManager.getInstance(this).unmountAll()
        LogKeeper.log(this, "HeavyFloatingHostService", "Heavy process floating service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder {
        return ipcHost.getBinder()
    }
}
