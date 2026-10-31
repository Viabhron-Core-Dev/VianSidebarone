package com.example.feature.floating

import android.content.Context
import android.content.Intent
import com.example.core.FloatingWindowManager
import com.example.core.LogKeeper
import com.example.core.WindowBounds
import com.example.core.WindowState
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyCommandType
import com.example.core.ipc.HeavyProcessConnectionManager
import java.util.UUID

/**
 * FloatingMiniAppBridge: The narrow boundary coordinator operating in the Main process (com.example).
 *
 * Responsibilities:
 * 1. Single Authoritative Owner: Manages mini-app durable recovery snapshots in DurableMiniAppStore.
 * 2. Window Hierarchy & Z-order: Delegates completely to Main's FloatingWindowManager.
 * 3. Lifecycle Transitions: ACTIVE <-> SLEEPING <-> CLOSED.
 * 4. Sleep Sequence: Asks Heavy for compact recovery payload, commits snapshot in Main,
 *    tears down Heavy runtime (reaching 0 MB Heavy idle), and displays a lightweight launcher bubble in Main.
 * 5. Independent Geometry: Keeps persistent launcher buttonPosition and windowBounds strictly separate.
 */
class FloatingMiniAppBridge private constructor(private val context: Context) {

    private val windowManager = FloatingWindowManager.getInstance(context)
    private val durableStore = DurableMiniAppStore.getInstance(context)
    private val activeBubbles = LinkedHashMap<String, FloatingMiniAppLauncherBubble>()

    /**
     * Opens or brings to front a mini-app floating window.
     */
    fun openMiniApp(
        instanceId: String,
        appType: String,
        initialBounds: WindowBounds? = null
    ): FloatingMiniAppWindow {
        // Dismiss launcher bubble if present
        dismissLauncherBubble(instanceId)

        val existing = windowManager.getWindow(instanceId)
        if (existing is FloatingMiniAppWindow) {
            if (existing.state != WindowState.VISIBLE) {
                windowManager.showWindow(instanceId)
            }
            windowManager.bringToFront(instanceId)
            durableStore.updateState(instanceId, MiniAppState.ACTIVE)
            return existing
        }

        // Determine bounds from initialBounds, saved snapshot, or defaults
        val savedSnapshot = durableStore.loadSnapshot(instanceId)
        val bounds = initialBounds?.copyBounds()
            ?: savedSnapshot?.windowBounds?.copyBounds()
            ?: WindowBounds(120, 150, 650, 850)

        // Ensure snapshot exists and is marked ACTIVE
        if (savedSnapshot == null) {
            val newSnapshot = MiniAppRecoverySnapshot(
                instanceId = instanceId,
                appType = appType,
                state = MiniAppState.ACTIVE,
                windowBounds = bounds.copyBounds()
            )
            durableStore.saveSnapshot(newSnapshot)
        } else {
            savedSnapshot.state = MiniAppState.ACTIVE
            savedSnapshot.windowBounds = bounds.copyBounds()
            durableStore.saveSnapshot(savedSnapshot)
        }

        val window = FloatingMiniAppWindow(instanceId, context, appType)
        window.setWindowBounds(bounds)

        windowManager.registerWindow(window)
        windowManager.showWindow(instanceId)
        windowManager.bringToFront(instanceId)

        LogKeeper.log(context, TAG, "Opened floating mini-app $instanceId ($appType)")
        return window
    }

    /**
     * Puts an active mini-app to sleep:
     * 1. Requests compact recovery payload from Heavy runtime.
     * 2. Commits snapshot with state=SLEEPING and payload to DurableMiniAppStore.
     * 3. Unregisters floating window from FloatingWindowManager.
     * 4. Shows lightweight launcher bubble at snapshot.buttonPosition in Main.
     * 5. Heavy dismantles runtime and terminates via checkDisposableTeardown().
     */
    fun sleepMiniApp(instanceId: String) {
        val window = windowManager.getWindow(instanceId) as? FloatingMiniAppWindow
        val editedPayload = window?.getCurrentPayload() ?: emptyMap()
        val appType = window?.appType
            ?: durableStore.loadSnapshot(instanceId)?.appType
            ?: "generic"

        // Step 1: Request sleep from Heavy via Binder
        val connectionManager = HeavyProcessConnectionManager.getInstance(context)
        val payloadToSend = LinkedHashMap<String, String>().apply {
            put("instance_id", instanceId)
            put("action", "sleep")
            put("sleep", "true")
            putAll(editedPayload)
        }
        val cmd = HeavyCommand(
            commandId = UUID.randomUUID().toString(),
            type = HeavyCommandType.PAUSE_OPERATION,
            targetId = instanceId,
            payload = payloadToSend
        )

        val res = connectionManager.sendCommand(cmd, autoConnect = false)
        val returnedPayload = if (res.success && res.data.isNotEmpty()) res.data else editedPayload

        // Fallback IPC intent to trigger teardown if Binder was not connected
        if (!res.success) {
            dispatchToHeavy(
                context,
                Intent(MiniAppIpcContracts.ACTION_SLEEP_MINIAPP).apply {
                    putExtra(MiniAppIpcContracts.EXTRA_INSTANCE_ID, instanceId)
                }
            )
        }

        // Step 2: Unregister and dismantle floating window in Main
        windowManager.unregisterWindow(instanceId)

        // Step 3: Commit recovery snapshot with state=SLEEPING
        val snapshot = durableStore.loadSnapshot(instanceId) ?: MiniAppRecoverySnapshot(
            instanceId = instanceId,
            appType = appType,
            state = MiniAppState.SLEEPING
        )
        snapshot.state = MiniAppState.SLEEPING
        if (returnedPayload.isNotEmpty()) {
            snapshot.payload.putAll(returnedPayload)
        }
        if (editedPayload.isNotEmpty()) {
            snapshot.payload.putAll(editedPayload)
        }
        durableStore.saveSnapshot(snapshot)

        // Step 4: Show launcher bubble in Main at independent buttonPosition
        showLauncherBubble(snapshot)

        LogKeeper.log(context, TAG, "Mini-app $instanceId transitioned to SLEEPING with bubble at ${snapshot.buttonPosition}")
    }

    /**
     * Wakes a sleeping mini-app:
     * 1. Removes launcher bubble.
     * 2. Opens floating window restored from snapshot.
     */
    fun wakeMiniApp(instanceId: String) {
        dismissLauncherBubble(instanceId)
        val snapshot = durableStore.loadSnapshot(instanceId)
        val appType = snapshot?.appType ?: "generic"
        val bounds = snapshot?.windowBounds?.copyBounds()

        openMiniApp(instanceId, appType, bounds)
        LogKeeper.log(context, TAG, "Woke mini-app $instanceId from sleep")
    }

    /**
     * Closes and dismantles a mini-app window and optionally deletes its durable record.
     */
    fun closeMiniApp(instanceId: String, deleteDurable: Boolean = false) {
        dismissLauncherBubble(instanceId)
        windowManager.unregisterWindow(instanceId)

        val connectionManager = HeavyProcessConnectionManager.getInstance(context)
        val cmd = HeavyCommand(
            commandId = UUID.randomUUID().toString(),
            type = HeavyCommandType.STOP_OPERATION,
            targetId = instanceId,
            payload = mapOf("instance_id" to instanceId)
        )
        val res = connectionManager.sendCommand(cmd, autoConnect = false)
        if (!res.success) {
            dispatchToHeavy(
                context,
                Intent(MiniAppIpcContracts.ACTION_DESTROY_MINIAPP).apply {
                    putExtra(MiniAppIpcContracts.EXTRA_INSTANCE_ID, instanceId)
                    putExtra(MiniAppIpcContracts.EXTRA_DELETE_DURABLE, deleteDurable)
                }
            )
        }

        if (deleteDurable) {
            durableStore.deleteSnapshot(instanceId)
        } else {
            durableStore.updateState(instanceId, MiniAppState.CLOSED)
        }
        LogKeeper.log(context, TAG, "Closed mini-app window $instanceId (deleteDurable=$deleteDurable)")
    }

    /**
     * Toggles a mini-app based on appType from Sidebar or element clicks.
     */
    fun toggleMiniApp(appType: String) {
        val existingSnapshot = durableStore.getAllSnapshots().firstOrNull { it.appType == appType }
        val instanceId = existingSnapshot?.instanceId ?: "miniapp_$appType"

        val activeWindow = windowManager.getWindow(instanceId) as? FloatingMiniAppWindow
        val isBubbleActive = activeBubbles.containsKey(instanceId)

        if (activeWindow != null && activeWindow.isVisible) {
            if (activeWindow.isFocused) {
                // Already focused & on top -> toggle to sleep
                sleepMiniApp(instanceId)
            } else {
                // Obscured -> bring to front
                windowManager.bringToFront(instanceId)
            }
        } else if (isBubbleActive || existingSnapshot?.state == MiniAppState.SLEEPING) {
            wakeMiniApp(instanceId)
        } else {
            openMiniApp(instanceId, appType)
        }
    }

    private fun showLauncherBubble(snapshot: MiniAppRecoverySnapshot) {
        dismissLauncherBubble(snapshot.instanceId)
        val bubble = FloatingMiniAppLauncherBubble(
            instanceId = snapshot.instanceId,
            appType = snapshot.appType,
            context = context,
            initialX = snapshot.buttonPosition.first,
            initialY = snapshot.buttonPosition.second,
            onBubbleTapped = { id -> wakeMiniApp(id) }
        )
        activeBubbles[snapshot.instanceId] = bubble
        windowManager.registerWindow(bubble)
        windowManager.showWindow(bubble.windowId)
        windowManager.bringToFront(bubble.windowId)
    }

    private fun dismissLauncherBubble(instanceId: String) {
        val bubble = activeBubbles.remove(instanceId)
        if (bubble != null) {
            windowManager.unregisterWindow(bubble.windowId)
        }
    }

    /**
     * Restores active and sleeping mini-apps upon app startup.
     * Sleeping mini-apps restore ONLY their launcher bubble in Main (0 MB in Heavy!).
     */
    fun restoreActiveMiniApps(): List<FloatingMiniAppWindow> {
        val snapshots = durableStore.getAllSnapshots()
        val restored = mutableListOf<FloatingMiniAppWindow>()

        for (snapshot in snapshots) {
            when (snapshot.state) {
                MiniAppState.ACTIVE -> {
                    if (windowManager.getWindow(snapshot.instanceId) == null) {
                        val window = FloatingMiniAppWindow(snapshot.instanceId, context, snapshot.appType)
                        window.setWindowBounds(snapshot.windowBounds)
                        windowManager.registerWindow(window)
                        windowManager.showWindow(snapshot.instanceId)
                        restored.add(window)
                    }
                }

                MiniAppState.SLEEPING -> {
                    // Restore only the lightweight launcher bubble in Main without waking Heavy!
                    showLauncherBubble(snapshot)
                }

                MiniAppState.CLOSED -> {
                    // Do not restore closed windows
                }
            }
        }

        if (restored.isNotEmpty()) {
            LogKeeper.log(context, TAG, "Restored ${restored.size} active mini-app windows into Main")
        }
        return restored
    }

    companion object {
        private const val TAG = "FloatingMiniAppBridge"

        @Volatile
        private var instance: FloatingMiniAppBridge? = null

        fun getInstance(context: Context): FloatingMiniAppBridge {
            return instance ?: synchronized(this) {
                instance ?: FloatingMiniAppBridge(context.applicationContext).also { instance = it }
            }
        }

        fun resetForTesting() {
            synchronized(this) {
                instance = null
            }
        }

        internal fun dispatchToHeavy(context: Context, intent: Intent) {
            try {
                intent.setClass(context, HeavyFloatingHostService::class.java)
                context.startService(intent)
            } catch (e: Throwable) {
                LogKeeper.logCrash(context, TAG, e)
            }
        }
    }
}
