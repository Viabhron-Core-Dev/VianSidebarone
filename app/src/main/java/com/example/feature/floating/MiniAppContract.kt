package com.example.feature.floating

import android.content.Context
import android.view.View
import com.example.core.WindowBounds

/**
 * MiniAppLifecycleState: Formal lifecycle states for floating mini-app instances in the Heavy process.
 */
enum class MiniAppLifecycleState {
    UNINITIALIZED,
    CREATED,
    STARTED,
    RESUMED,
    PAUSED,
    STOPPED,
    DESTROYED
}

/**
 * MiniAppRecord: Durable model representing the persisted metadata and state of a mini-app instance.
 * Survives Heavy process death, Heavy process restart, and normal app restarts.
 */
data class MiniAppRecord(
    val instanceId: String,
    val appType: String,
    var title: String = "",
    var bounds: WindowBounds = WindowBounds(100, 100, 600, 800),
    var isFolded: Boolean = false,
    var isPinned: Boolean = false,
    var lastActiveTimestamp: Long = System.currentTimeMillis(),
    val data: MutableMap<String, String> = mutableMapOf()
) {
    fun copyRecord(): MiniAppRecord {
        return MiniAppRecord(
            instanceId = instanceId,
            appType = appType,
            title = title,
            bounds = bounds.copyBounds(),
            isFolded = isFolded,
            isPinned = isPinned,
            lastActiveTimestamp = lastActiveTimestamp,
            data = LinkedHashMap(data)
        )
    }
}

/**
 * MiniAppInstance: Contract for all floating mini-app implementations hosted in the Heavy process.
 * Separates mini-app execution logic from Main-process Window Manager hierarchy.
 */
interface MiniAppInstance {
    val instanceId: String
    val appType: String
    val lifecycleState: MiniAppLifecycleState

    fun onCreate(savedRecord: MiniAppRecord?) {}
    fun onStart() {}
    fun onResume() {}
    fun onPause() {}
    fun onStop() {}
    fun onDestroy() {}
    fun onSaveState(): Map<String, String> = emptyMap()
    fun onTrimMemory(level: Int) {}
    fun createContentView(context: Context): View? = null
}

/**
 * MiniAppFactory: Extensibility contract allowing future mini-apps to register into HeavyFloatingHost
 * without Main process having compile-time dependencies on individual mini-app implementations.
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
    const val ACTION_STOP_MINIAPP = "com.example.action.MINIAPP_STOP"
    const val ACTION_DESTROY_MINIAPP = "com.example.action.MINIAPP_DESTROY"
    const val ACTION_UPDATE_BOUNDS = "com.example.action.MINIAPP_UPDATE_BOUNDS"
    const val ACTION_UPDATE_FOLD = "com.example.action.MINIAPP_UPDATE_FOLD"
    const val ACTION_RESTORE_ALL = "com.example.action.MINIAPP_RESTORE_ALL"
    const val ACTION_RECONCILE_STATE = "com.example.action.MINIAPP_RECONCILE_STATE"

    const val EXTRA_INSTANCE_ID = "instance_id"
    const val EXTRA_APP_TYPE = "app_type"
    const val EXTRA_BOUNDS_X = "bounds_x"
    const val EXTRA_BOUNDS_Y = "bounds_y"
    const val EXTRA_BOUNDS_W = "bounds_w"
    const val EXTRA_BOUNDS_H = "bounds_h"
    const val EXTRA_IS_FOLDED = "is_folded"
    const val EXTRA_DELETE_DURABLE = "delete_durable"
}
