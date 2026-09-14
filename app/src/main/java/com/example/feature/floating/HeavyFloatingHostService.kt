package com.example.feature.floating

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.example.core.LogKeeper
import com.example.core.WindowBounds

/**
 * HeavyFloatingHostService: On-demand, disposable host Service running in the Heavy process (:heavy).
 *
 * Characteristics:
 * 1. Runs exclusively in android:process=":heavy".
 * 2. On-demand: Triggered only when a mini-app is launched, controlled, or restored.
 * 3. Disposable: Automatically terminates via stopSelf() when no active mini-apps remain,
 *    returning the Heavy process memory to 0 MB idle.
 * 4. START_NOT_STICKY to avoid unsolicited background wakeups.
 */
class HeavyFloatingHostService : Service() {

    private lateinit var host: HeavyFloatingHost
    private lateinit var ipcHost: com.example.core.ipc.HeavyProcessHost

    override fun onCreate() {
        super.onCreate()
        host = HeavyFloatingHost.getInstance(this)
        ipcHost = com.example.core.ipc.HeavyProcessHost.getInstance(this)

        // Wire incoming Binder commands to HeavyFloatingHost
        ipcHost.registerCommandHandler(com.example.core.ipc.HeavyCommandType.START_OPERATION) { cmd ->
            val instanceId = cmd.targetId ?: cmd.payload["instance_id"] ?: ""
            val appType = cmd.payload["app_type"] ?: "generic"
            val bx = cmd.payload["bounds_x"]?.toIntOrNull() ?: 100
            val by = cmd.payload["bounds_y"]?.toIntOrNull() ?: 100
            val bw = cmd.payload["bounds_w"]?.toIntOrNull() ?: 600
            val bh = cmd.payload["bounds_h"]?.toIntOrNull() ?: 800
            val bounds = WindowBounds(bx, by, bw, bh)
            if (instanceId.isNotEmpty()) {
                host.startInstance(instanceId, appType, bounds)
                com.example.core.ipc.IpcResult.success("Started $instanceId")
            } else {
                com.example.core.ipc.IpcResult.error(com.example.core.ipc.IpcErrorCode.MARSHAL_ERROR, "Missing instanceId")
            }
        }

        ipcHost.registerCommandHandler(com.example.core.ipc.HeavyCommandType.STOP_OPERATION) { cmd ->
            val instanceId = cmd.targetId ?: cmd.payload["instance_id"] ?: ""
            if (instanceId.isNotEmpty()) {
                host.stopInstance(instanceId)
                checkDisposableTeardown()
                com.example.core.ipc.IpcResult.success("Stopped $instanceId")
            } else {
                com.example.core.ipc.IpcResult.error(com.example.core.ipc.IpcErrorCode.MARSHAL_ERROR, "Missing instanceId")
            }
        }

        ipcHost.registerCommandHandler(com.example.core.ipc.HeavyCommandType.PAUSE_OPERATION) { cmd ->
            val instanceId = cmd.targetId ?: cmd.payload["instance_id"] ?: ""
            if (instanceId.isNotEmpty()) {
                host.pauseInstance(instanceId)
                com.example.core.ipc.IpcResult.success("Paused $instanceId")
            } else {
                com.example.core.ipc.IpcResult.error(com.example.core.ipc.IpcErrorCode.MARSHAL_ERROR, "Missing instanceId")
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
                val deleteDurable = intent.getBooleanExtra(MiniAppIpcContracts.EXTRA_DELETE_DURABLE, false)
                if (!instanceId.isNullOrEmpty()) {
                    host.destroyInstance(instanceId, deleteDurable)
                }
            }

            MiniAppIpcContracts.ACTION_UPDATE_BOUNDS -> {
                val bx = intent.getIntExtra(MiniAppIpcContracts.EXTRA_BOUNDS_X, 100)
                val by = intent.getIntExtra(MiniAppIpcContracts.EXTRA_BOUNDS_Y, 100)
                val bw = intent.getIntExtra(MiniAppIpcContracts.EXTRA_BOUNDS_W, 600)
                val bh = intent.getIntExtra(MiniAppIpcContracts.EXTRA_BOUNDS_H, 800)
                if (!instanceId.isNullOrEmpty()) {
                    host.updateBounds(instanceId, WindowBounds(bx, by, bw, bh))
                }
            }

            MiniAppIpcContracts.ACTION_UPDATE_FOLD -> {
                val isFolded = intent.getBooleanExtra(MiniAppIpcContracts.EXTRA_IS_FOLDED, false)
                if (!instanceId.isNullOrEmpty()) {
                    host.updateFold(instanceId, isFolded)
                }
            }

            MiniAppIpcContracts.ACTION_RESTORE_ALL -> {
                host.restoreSavedInstances()
            }
        }

        checkDisposableTeardown()
        return START_NOT_STICKY
    }

    private fun checkDisposableTeardown() {
        if (host.getActiveInstanceCount() == 0) {
            LogKeeper.log(this, "HeavyFloatingHostService", "No active mini-apps in Heavy process; calling stopSelf()")
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LogKeeper.log(this, "HeavyFloatingHostService", "Heavy process floating service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder {
        return ipcHost.getBinder()
    }
}
