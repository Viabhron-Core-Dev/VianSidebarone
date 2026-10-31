package com.example.feature.floating

import android.content.Context
import android.view.View
import com.example.core.WindowBounds

/**
 * MiniAppState: Coarse, durable lifecycle state for managed floating mini-apps.
 * Maintained authoritatively by the Main process (com.example).
 */
enum class MiniAppState {
    ACTIVE,     // Actively mounted or displayed floating window
    SLEEPING,   // Suspended in Main with lightweight launcher bubble; 0 MB footprint in :heavy
    CLOSED      // Fully dismissed and dismantled
}

/**
 * MiniAppLifecycleState: Fine-grained runtime lifecycle states in the Heavy process (:heavy).
 */
enum class MiniAppLifecycleState {
    UNINITIALIZED,
    CREATED,
    STARTED,
    RESUMED,
    PAUSED,
    STOPPED,
    SLEEPING,
    DESTROYED
}

/**
 * MiniAppRecoverySnapshot: Compact, serializable, versioned snapshot capturing
 * the complete recovery state of a floating mini-app.
 *
 * Owned and persisted exclusively by the Main process in DurableMiniAppStore.
 * Note: [buttonPosition] and [windowBounds] are strictly independent and must never overwrite each other.
 */
data class MiniAppRecoverySnapshot(
    val instanceId: String,
    val appType: String,
    var title: String = "",
    var state: MiniAppState = MiniAppState.ACTIVE,
    var windowBounds: WindowBounds = WindowBounds(100, 100, 600, 800),
    var maximizeRestoreBounds: WindowBounds? = null,
    var buttonPosition: Pair<Int, Int> = Pair(40, 200),
    val payload: MutableMap<String, String> = mutableMapOf(),
    val version: Int = CURRENT_VERSION,
    var timestamp: Long = System.currentTimeMillis()
) {
    // Backwards-compatible accessors for legacy callers
    var bounds: WindowBounds
        get() = windowBounds
        set(value) {
            windowBounds = value
        }

    var isFolded: Boolean
        get() = state == MiniAppState.SLEEPING
        set(value) {
            state = if (value) MiniAppState.SLEEPING else MiniAppState.ACTIVE
        }

    val data: MutableMap<String, String>
        get() = payload

    var lastActiveTimestamp: Long
        get() = timestamp
        set(value) {
            timestamp = value
        }

    var isPinned: Boolean = false

    fun copySnapshot(): MiniAppRecoverySnapshot {
        return MiniAppRecoverySnapshot(
            instanceId = instanceId,
            appType = appType,
            title = title,
            state = state,
            windowBounds = windowBounds.copyBounds(),
            maximizeRestoreBounds = maximizeRestoreBounds?.copyBounds(),
            buttonPosition = buttonPosition.copy(),
            payload = LinkedHashMap(payload),
            version = version,
            timestamp = timestamp
        ).also {
            it.isPinned = this.isPinned
        }
    }

    fun copyRecord(): MiniAppRecoverySnapshot = copySnapshot()

    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * Type alias for seamless backwards compatibility with earlier prototypes.
 */
typealias MiniAppRecord = MiniAppRecoverySnapshot

/**
 * MiniAppInstance: Foundational lifecycle contract for all mini-apps executed in :heavy.
 *
 * Implements a complete sleep/restore contract:
 * - [prepareForSleep]: Suspends background tasks and returns compact payload data.
 * - [releaseRuntime]: Dismantles memory-heavy UI, engines, and listeners.
 * - [restoreFromSnapshot]: Reconstructs live state when waking from sleep.
 */
interface MiniAppInstance {
    val instanceId: String
    val appType: String
    val lifecycleState: MiniAppLifecycleState

    fun onCreate(savedSnapshot: MiniAppRecoverySnapshot?) {}
    fun onStart() {}
    fun onResume() {}
    fun onPause() {}
    fun onStop() {}
    fun onDestroy() {}

    /**
     * Prepares for sleep: suspends asynchronous operations and returns a compact payload.
     */
    fun prepareForSleep(): Map<String, String> = emptyMap()

    /**
     * Releases heavy UI and runtime resources while retaining state.
     */
    fun releaseRuntime() {}

    /**
     * Restores state from a recovery snapshot when resuming/waking from sleep.
     */
    fun restoreFromSnapshot(snapshot: MiniAppRecoverySnapshot) {}

    fun onSaveState(): Map<String, String> = emptyMap()
    fun onTrimMemory(level: Int) {}
    fun createContentView(context: Context): View? = null
}

/**
 * MiniAppFactory: Extensibility contract allowing mini-app implementations to register into
 * HeavyFloatingHost without Main having compile-time dependencies on individual implementations.
 */
interface MiniAppFactory {
    fun createAppInstance(instanceId: String, appType: String): MiniAppInstance?
}

/**
 * MiniAppIpcContracts: Minimal, narrow cross-process IPC contract for coordinating floating
 * mini-app lifecycle and state between Main (com.example) and Heavy (:heavy).
 */
object MiniAppIpcContracts {
    const val ACTION_START_MINIAPP = "com.example.action.MINIAPP_START"
    const val ACTION_PAUSE_MINIAPP = "com.example.action.MINIAPP_PAUSE"
    const val ACTION_SLEEP_MINIAPP = "com.example.action.MINIAPP_SLEEP"
    const val ACTION_STOP_MINIAPP = "com.example.action.MINIAPP_STOP"
    const val ACTION_DESTROY_MINIAPP = "com.example.action.MINIAPP_DESTROY"
    const val ACTION_UPDATE_BOUNDS = "com.example.action.MINIAPP_UPDATE_BOUNDS"
    const val ACTION_UPDATE_FOLD = "com.example.action.MINIAPP_UPDATE_FOLD"
    const val ACTION_RESTORE_ALL = "com.example.action.MINIAPP_RESTORE_ALL"

    const val EXTRA_INSTANCE_ID = "instance_id"
    const val EXTRA_APP_TYPE = "app_type"
    const val EXTRA_BOUNDS_X = "bounds_x"
    const val EXTRA_BOUNDS_Y = "bounds_y"
    const val EXTRA_BOUNDS_W = "bounds_w"
    const val EXTRA_BOUNDS_H = "bounds_h"
    const val EXTRA_IS_FOLDED = "is_folded"
    const val EXTRA_DELETE_DURABLE = "delete_durable"
    const val EXTRA_PAYLOAD_JSON = "payload_json"
}
