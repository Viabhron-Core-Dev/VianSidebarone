package com.example.feature.floating

import android.content.Context
import android.content.Intent
import com.example.core.FloatingWindow
import com.example.core.FloatingWindowManager
import com.example.core.LogKeeper
import com.example.core.WindowBounds
import com.example.core.WindowState

/**
 * FloatingMiniAppWindow: A specialized FloatingWindow representation in the Main process
 * for floating mini-apps whose business logic, content, and heavy execution run in :heavy.
 *
 * All runtime hierarchy, Z-order, overlay layout parameters, magnetic snapping, and focus
 * remain strictly authoritative within Main's FloatingWindowManager.
 */
class FloatingMiniAppWindow(
    val instanceId: String,
    context: Context,
    val appType: String
) : FloatingWindow(instanceId, context, TYPE_MINIAPP) {

    override fun onShow() {
        super.onShow()
        val connectionManager = com.example.core.ipc.HeavyProcessConnectionManager.getInstance(context)
        val cmd = com.example.core.ipc.HeavyCommand(
            commandId = java.util.UUID.randomUUID().toString(),
            type = com.example.core.ipc.HeavyCommandType.START_OPERATION,
            targetId = instanceId,
            payload = mapOf(
                "instance_id" to instanceId,
                "app_type" to appType,
                "bounds_x" to bounds.x.toString(),
                "bounds_y" to bounds.y.toString(),
                "bounds_w" to bounds.width.toString(),
                "bounds_h" to bounds.height.toString()
            )
        )
        val res = connectionManager.sendCommand(cmd, autoConnect = true)
        if (!res.success) {
            // Fallback to startService intent if Binder connection pending
            FloatingMiniAppBridge.dispatchToHeavy(
                context,
                Intent(MiniAppIpcContracts.ACTION_START_MINIAPP).apply {
                    putExtra(MiniAppIpcContracts.EXTRA_INSTANCE_ID, instanceId)
                    putExtra(MiniAppIpcContracts.EXTRA_APP_TYPE, appType)
                    putExtra(MiniAppIpcContracts.EXTRA_BOUNDS_X, bounds.x)
                    putExtra(MiniAppIpcContracts.EXTRA_BOUNDS_Y, bounds.y)
                    putExtra(MiniAppIpcContracts.EXTRA_BOUNDS_W, bounds.width)
                    putExtra(MiniAppIpcContracts.EXTRA_BOUNDS_H, bounds.height)
                }
            )
        }
    }

    override fun onHide() {
        super.onHide()
        val connectionManager = com.example.core.ipc.HeavyProcessConnectionManager.getInstance(context)
        val cmd = com.example.core.ipc.HeavyCommand(
            commandId = java.util.UUID.randomUUID().toString(),
            type = com.example.core.ipc.HeavyCommandType.PAUSE_OPERATION,
            targetId = instanceId,
            payload = mapOf("instance_id" to instanceId)
        )
        val res = connectionManager.sendCommand(cmd, autoConnect = true)
        if (!res.success) {
            FloatingMiniAppBridge.dispatchToHeavy(
                context,
                Intent(MiniAppIpcContracts.ACTION_PAUSE_MINIAPP).apply {
                    putExtra(MiniAppIpcContracts.EXTRA_INSTANCE_ID, instanceId)
                }
            )
        }
    }

    override fun onFold(folded: Boolean) {
        super.onFold(folded)
        FloatingMiniAppBridge.dispatchToHeavy(
            context,
            Intent(MiniAppIpcContracts.ACTION_UPDATE_FOLD).apply {
                putExtra(MiniAppIpcContracts.EXTRA_INSTANCE_ID, instanceId)
                putExtra(MiniAppIpcContracts.EXTRA_IS_FOLDED, folded)
            }
        )
    }

    override fun setWindowBounds(newBounds: WindowBounds) {
        super.setWindowBounds(newBounds)
        FloatingMiniAppBridge.dispatchToHeavy(
            context,
            Intent(MiniAppIpcContracts.ACTION_UPDATE_BOUNDS).apply {
                putExtra(MiniAppIpcContracts.EXTRA_INSTANCE_ID, instanceId)
                putExtra(MiniAppIpcContracts.EXTRA_BOUNDS_X, newBounds.x)
                putExtra(MiniAppIpcContracts.EXTRA_BOUNDS_Y, newBounds.y)
                putExtra(MiniAppIpcContracts.EXTRA_BOUNDS_W, newBounds.width)
                putExtra(MiniAppIpcContracts.EXTRA_BOUNDS_H, newBounds.height)
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        val connectionManager = com.example.core.ipc.HeavyProcessConnectionManager.getInstance(context)
        val cmd = com.example.core.ipc.HeavyCommand(
            commandId = java.util.UUID.randomUUID().toString(),
            type = com.example.core.ipc.HeavyCommandType.STOP_OPERATION,
            targetId = instanceId,
            payload = mapOf("instance_id" to instanceId)
        )
        val res = connectionManager.sendCommand(cmd, autoConnect = false)
        if (!res.success) {
            FloatingMiniAppBridge.dispatchToHeavy(
                context,
                Intent(MiniAppIpcContracts.ACTION_STOP_MINIAPP).apply {
                    putExtra(MiniAppIpcContracts.EXTRA_INSTANCE_ID, instanceId)
                }
            )
        }
    }

    companion object {
        const val TYPE_MINIAPP = "miniapp"
    }
}

/**
 * FloatingMiniAppBridge: The narrow boundary coordinator operating in the Main process (com.example).
 *
 * Responsibilities:
 * 1. Delegates all window hierarchy, Z-order, and overlay management to FloatingWindowManager.
 * 2. Bridges lifecycle events to HeavyFloatingHostService in :heavy via minimal IPC intents.
 * 3. Bridges durable restoration on cold start / app start without making Main depend on individual mini-apps.
 */
class FloatingMiniAppBridge private constructor(private val context: Context) {

    private val windowManager = FloatingWindowManager.getInstance(context)
    private val durableStore = DurableMiniAppStore.getInstance(context)

    /**
     * Opens or brings to front a mini-app floating window.
     */
    fun openMiniApp(
        instanceId: String,
        appType: String,
        initialBounds: WindowBounds? = null
    ): FloatingMiniAppWindow {
        val existing = windowManager.getWindow(instanceId)
        if (existing is FloatingMiniAppWindow) {
            if (existing.state != WindowState.VISIBLE) {
                windowManager.showWindow(instanceId)
            }
            windowManager.bringToFront(instanceId)
            return existing
        }

        // Determine bounds from initialBounds, saved durable record, or defaults
        val savedRecord = durableStore.loadInstance(instanceId)
        val bounds = initialBounds?.copyBounds()
            ?: savedRecord?.bounds?.copyBounds()
            ?: WindowBounds(120, 150, 650, 850)

        val window = FloatingMiniAppWindow(instanceId, context, appType)
        window.setWindowBounds(bounds)

        windowManager.registerWindow(window)
        windowManager.showWindow(instanceId)
        windowManager.bringToFront(instanceId)

        LogKeeper.log(context, "FloatingMiniAppBridge", "Registered & opened floating mini-app window $instanceId ($appType)")
        return window
    }

    /**
     * Closes and unregisters a mini-app floating window from Main's Window Manager.
     */
    fun closeMiniApp(instanceId: String, deleteDurable: Boolean = false) {
        windowManager.unregisterWindow(instanceId)

        dispatchToHeavy(
            context,
            Intent(MiniAppIpcContracts.ACTION_DESTROY_MINIAPP).apply {
                putExtra(MiniAppIpcContracts.EXTRA_INSTANCE_ID, instanceId)
                putExtra(MiniAppIpcContracts.EXTRA_DELETE_DURABLE, deleteDurable)
            }
        )
        LogKeeper.log(context, "FloatingMiniAppBridge", "Closed mini-app window $instanceId (deleteDurable=$deleteDurable)")
    }

    /**
     * Restoration hook: Restores floating mini-app window representations into Main's FloatingWindowManager
     * from durable storage upon app launch or service start.
     */
    fun restoreActiveMiniApps(): List<FloatingMiniAppWindow> {
        val records = durableStore.getAllInstances()
        val restored = mutableListOf<FloatingMiniAppWindow>()

        for (record in records) {
            // Only restore if not already active in Main's window manager
            if (windowManager.getWindow(record.instanceId) == null) {
                val window = FloatingMiniAppWindow(record.instanceId, context, record.appType)
                window.setWindowBounds(record.bounds)
                windowManager.registerWindow(window)
                if (record.isFolded) {
                    windowManager.foldWindow(record.instanceId)
                } else {
                    windowManager.showWindow(record.instanceId)
                }
                restored.add(window)
            }
        }

        if (restored.isNotEmpty()) {
            LogKeeper.log(context, "FloatingMiniAppBridge", "Restored ${restored.size} mini-app windows into Main WindowManager")
            // Wake Heavy host to restore instances
            dispatchToHeavy(
                context,
                Intent(MiniAppIpcContracts.ACTION_RESTORE_ALL)
            )
        }
        return restored
    }

    companion object {
        @Volatile
        private var instance: FloatingMiniAppBridge? = null

        fun getInstance(context: Context): FloatingMiniAppBridge {
            return instance ?: synchronized(this) {
                instance ?: FloatingMiniAppBridge(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Dispatches an explicit IPC intent to HeavyFloatingHostService running in :heavy.
         */
        internal fun dispatchToHeavy(context: Context, intent: Intent) {
            try {
                intent.setClass(context, HeavyFloatingHostService::class.java)
                context.startService(intent)
            } catch (e: Throwable) {
                LogKeeper.logCrash(context, "FloatingMiniAppBridge", e)
            }
        }
    }
}
