package com.example.feature.floating

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.example.core.LogKeeper
import com.example.core.WindowBounds
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyCommandHandler
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
    private var startHandler: HeavyCommandHandler? = null
    private var pauseHandler: HeavyCommandHandler? = null
    private var stopHandler: HeavyCommandHandler? = null

    override fun onCreate() {
        super.onCreate()
        host = HeavyFloatingHost.getInstance(this)
        ipcHost = HeavyProcessHost.getInstance(this)

        // Listen for HeavyModuleManager becoming idle
        com.example.feature.heavy.module.HeavyModuleManager.getInstance(this).setIdleListener {
            checkDisposableTeardown()
        }

        // Wire incoming Binder commands to HeavyFloatingHost
        val sHandler = HeavyCommandHandler { cmd ->
            val action = cmd.payload["action"]
            if (action in listOf("mount", "use", "unmount", "unmountAll") ||
                cmd.payload.containsKey("capabilityId") ||
                cmd.payload.containsKey("moduleId") ||
                (!cmd.payload.containsKey("instance_id") && !cmd.payload.containsKey("app_type"))
            ) {
                return@HeavyCommandHandler IpcResult.success()
            }
            val instanceId = cmd.targetId ?: cmd.payload["instance_id"] ?: ""
            if (action == "sync_live_text" && instanceId.isNotEmpty()) {
                val liveText = cmd.payload[TestNotesMiniAppInstance.KEY_TEXT] ?: cmd.payload["text"] ?: ""
                val selStart = cmd.payload["sel_start"]?.toIntOrNull() ?: -1
                val selEnd = cmd.payload["sel_end"]?.toIntOrNull() ?: -1
                host.updateLiveInstanceText(instanceId, liveText, selStart, selEnd)
                return@HeavyCommandHandler IpcResult.success("Synced live text for $instanceId")
            }
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
                LogKeeper.log(this@HeavyFloatingHostService, "HeavyFloatingHostService", "CMD_START_OPERATION id=$instanceId type=$appType bounds=$bounds")
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
        startHandler = sHandler
        ipcHost.registerCommandHandler(HeavyCommandType.START_OPERATION, sHandler)

        val pHandler = HeavyCommandHandler { cmd ->
            val action = cmd.payload["action"]
            if (action in listOf("mount", "use", "unmount", "unmountAll") ||
                cmd.payload.containsKey("capabilityId") ||
                cmd.payload.containsKey("moduleId") ||
                (!cmd.payload.containsKey("instance_id") && !cmd.payload.containsKey("app_type"))
            ) {
                return@HeavyCommandHandler IpcResult.success()
            }
            val instanceId = cmd.targetId ?: cmd.payload["instance_id"] ?: ""
            if (instanceId.isNotEmpty()) {
                if (action == "sync_live_text") {
                    val liveText = cmd.payload[TestNotesMiniAppInstance.KEY_TEXT] ?: cmd.payload["text"] ?: ""
                    val selStart = cmd.payload["sel_start"]?.toIntOrNull() ?: -1
                    val selEnd = cmd.payload["sel_end"]?.toIntOrNull() ?: -1
                    val sess = cmd.payload["sessionId"]
                    host.updateLiveInstanceText(instanceId, liveText, selStart, selEnd, sess)
                    return@HeavyCommandHandler IpcResult.success("Synced live text for $instanceId")
                }
                if (action == "reader_library_changed") {
                    com.example.feature.reader.ReaderEventManager.notifyLibraryChanged()
                    return@HeavyCommandHandler IpcResult.success("Notified reader library changed")
                }
                if (action == "reader_folder_granted") {
                    val folderUri = cmd.payload["folder_uri"]
                    if (!folderUri.isNullOrBlank()) {
                        com.example.feature.reader.ReaderFolderPrefs.saveFolderUri(this@HeavyFloatingHostService, folderUri)
                    }
                    com.example.feature.reader.ReaderEventManager.notifyFolderGranted(folderUri)
                    return@HeavyCommandHandler IpcResult.success("Notified reader folder granted")
                }
                if (action == EmbeddedImeBridge.CMD_EMBEDDED_IME_BLUR) {
                    host.dispatchEmbeddedImeBlur(instanceId)
                    return@HeavyCommandHandler IpcResult.success("Blurred embedded IME for $instanceId")
                }
                if (action in listOf("note_format", "note_undo", "note_redo", "note_clear")) {
                    host.dispatchNoteCommand(instanceId, action ?: "", cmd.payload)
                    return@HeavyCommandHandler IpcResult.success("Handled $action for $instanceId")
                }
                if (action in listOf(
                        com.example.feature.pwa.floating.FloatingPwaMiniAppInstance.CMD_PWA_SELECT,
                        com.example.feature.pwa.floating.FloatingPwaMiniAppInstance.CMD_PWA_LOAD_URL,
                        com.example.feature.pwa.floating.FloatingPwaMiniAppInstance.CMD_PWA_BACK,
                        com.example.feature.pwa.floating.FloatingPwaMiniAppInstance.CMD_PWA_FORWARD,
                        com.example.feature.pwa.floating.FloatingPwaMiniAppInstance.CMD_PWA_RELOAD,
                        com.example.feature.pwa.floating.FloatingPwaMiniAppInstance.CMD_PWA_STOP,
                        com.example.feature.pwa.floating.FloatingPwaMiniAppInstance.CMD_PWA_FILE_RESULT,
                        com.example.feature.pwa.floating.FloatingPwaMiniAppInstance.CMD_PWA_PERMISSION_RESULT
                    )
                ) {
                    val handled = host.dispatchFloatingPwaCommand(instanceId, action ?: "", cmd.payload)
                    return@HeavyCommandHandler if (handled) {
                        IpcResult.success("Handled $action for $instanceId")
                    } else {
                        IpcResult.error(IpcErrorCode.COMMAND_FAILED, "Floating PWA instance not active: $instanceId")
                    }
                }
                val isSleep = cmd.payload["sleep"] == "true" || action == "sleep"
                val isSnapshotOnly = action == "get_snapshot" || (isSleep && cmd.payload["release"] != "true")
                val returnedData = if (isSnapshotOnly) {
                    // Non-destructive snapshot gathering: keep instance alive in Heavy until Main confirms durable save
                    host.getSnapshot(instanceId, cmd.payload)
                } else if (isSleep) {
                    val payload = host.prepareSleep(instanceId, cmd.payload)
                    com.example.feature.heavy.page.HeavyPageHostManager.getInstance(this@HeavyFloatingHostService).releaseSurfaceHost(instanceId)
                    checkDisposableTeardown()
                    payload
                } else {
                    host.pauseInstance(instanceId)
                }
                IpcResult.success(if (isSnapshotOnly) "Snapshot $instanceId" else if (isSleep) "Slept $instanceId" else "Paused $instanceId", returnedData)
            } else {
                IpcResult.error(IpcErrorCode.MARSHAL_ERROR, "Missing instanceId")
            }
        }
        pauseHandler = pHandler
        ipcHost.registerCommandHandler(HeavyCommandType.PAUSE_OPERATION, pHandler)

        val stHandler = HeavyCommandHandler { cmd ->
            val action = cmd.payload["action"]
            if (action in listOf("mount", "use", "unmount", "unmountAll") ||
                cmd.payload.containsKey("capabilityId") ||
                cmd.payload.containsKey("moduleId") ||
                (!cmd.payload.containsKey("instance_id") && !cmd.payload.containsKey("app_type"))
            ) {
                return@HeavyCommandHandler IpcResult.success()
            }
            val instanceId = cmd.targetId ?: cmd.payload["instance_id"] ?: ""
            if (instanceId.isNotEmpty()) {
                if (action == "release_sleeping_instance") {
                    host.releaseInstance(instanceId, reason = "sleep_confirmed")
                    com.example.feature.heavy.page.HeavyPageHostManager.getInstance(this@HeavyFloatingHostService).releaseSurfaceHost(instanceId)
                    checkDisposableTeardown()
                    IpcResult.success("Released $instanceId")
                } else {
                    val payload = host.stopInstance(instanceId)
                    com.example.feature.heavy.page.HeavyPageHostManager.getInstance(this@HeavyFloatingHostService).releaseSurfaceHost(instanceId)
                    checkDisposableTeardown()
                    IpcResult.success("Stopped $instanceId", payload)
                }
            } else {
                IpcResult.error(IpcErrorCode.MARSHAL_ERROR, "Missing instanceId")
            }
        }
        stopHandler = stHandler
        ipcHost.registerCommandHandler(HeavyCommandType.STOP_OPERATION, stHandler)

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
                    LogKeeper.log(this@HeavyFloatingHostService, "HeavyFloatingHostService", "INTENT_START_MINIAPP id=$instanceId type=$appType bounds=$bounds")
                    host.startInstance(instanceId, appType, bounds)
                }
            }

            MiniAppIpcContracts.ACTION_SLEEP_MINIAPP -> {
                if (!instanceId.isNullOrEmpty()) {
                    host.prepareSleep(instanceId)
                    checkDisposableTeardown()
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
                    checkDisposableTeardown()
                }
            }

            MiniAppIpcContracts.ACTION_DESTROY_MINIAPP -> {
                if (!instanceId.isNullOrEmpty()) {
                    host.destroyInstance(instanceId)
                    checkDisposableTeardown()
                }
            }
        }

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
        val willStop = (activeFloating == 0 && activeModules == 0 && !isCallRecording && activePages == 0)

        LogKeeper.log(
            this,
            "HeavyFloatingHostService",
            "HEAVY_IDLE_CHECK activeMiniApps=$activeFloating activeHeavyPages=$activePages moduleSessions=$activeModules callRecording=$isCallRecording willStop=$willStop reason=${if (willStop) "all_workloads_idle" else "workloads_active"}"
        )

        if (willStop) {
            LogKeeper.log(this, "HeavyFloatingHostService", "HEAVY_STOP_REQUEST reason=all_workloads_idle stopping Heavy host")
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LogKeeper.log(this, "HeavyFloatingHostService", "HEAVY_SERVICE_DESTROY timestamp=${System.currentTimeMillis()}")
        startHandler?.let { ipcHost.unregisterCommandHandler(HeavyCommandType.START_OPERATION, it) }
        pauseHandler?.let { ipcHost.unregisterCommandHandler(HeavyCommandType.PAUSE_OPERATION, it) }
        stopHandler?.let { ipcHost.unregisterCommandHandler(HeavyCommandType.STOP_OPERATION, it) }
        startHandler = null
        pauseHandler = null
        stopHandler = null
        com.example.feature.heavy.module.HeavyModuleManager.getInstance(this).setIdleListener(null)
        com.example.feature.heavy.page.HeavyPageHostManager.getInstance(this).unmountAll()
    }

    override fun onBind(intent: Intent?): IBinder {
        return ipcHost.getBinder()
    }
}
