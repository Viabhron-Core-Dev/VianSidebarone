package com.example.core

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import com.example.util.HandleEdge
import com.example.util.HandleShape

object HandleGestures {
    const val TAP = "tap"
    const val DOUBLE_TAP = "double_tap"
    const val LONG_PRESS = "long_press"
    const val SWIPE_LEFT = "swipe_left"
    const val SWIPE_RIGHT = "swipe_right"
    const val SWIPE_UP = "swipe_up"
    const val SWIPE_DOWN = "swipe_down"

    val ALL = listOf(
        TAP,
        DOUBLE_TAP,
        LONG_PRESS,
        SWIPE_LEFT,
        SWIPE_RIGHT,
        SWIPE_UP,
        SWIPE_DOWN
    )
}

sealed class GestureTarget {
    data class Container(val containerId: String) : GestureTarget()
    data class Action(val actionKey: String) : GestureTarget()
    object None : GestureTarget()
}

/**
 * SidebarContainer: Runtime representation of an independent container belonging to exactly
 * one Handle + Gesture combination. Holds identity, enabled/available state, and provides
 * isolated access to its persisted page stack.
 */
data class SidebarContainer(
    val containerId: String,
    val handleId: String,
    val gesture: String,
    val enabled: Boolean = true
) {
    /**
     * Strict isolation page stack key: "handle_${containerId}_pages"
     */
    val pagesKey: String
        get() = HandleManager.getContainerPagesKey(containerId)

    fun getPageStack(context: Context): List<SidebarPage> =
        PageManager.getInstance(context).getPageStack(this)

    fun getPageIds(context: Context): List<String> =
        PageManager.getInstance(context).getPageIds(this)

    fun getFirstPage(context: Context): SidebarPage? =
        PageManager.getInstance(context).getFirstPage(this)
}

data class HandleConfig(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val edge: HandleEdge = HandleEdge.RIGHT,
    val positionPercent: Float = 0.5f, // 0.0 to 1.0 along the screen edge
    val widthDp: Int = 12,
    val heightDp: Int = 120,
    val color: Int = Color.parseColor("#242962ff"),
    val shape: HandleShape = HandleShape.SLANTED_BLOCK,
    val alphaPercent: Int = 14,
    // Gesture Action Keys
    val onTapAction: String = "none",
    val onDoubleTapAction: String = "none",
    val onLongPressAction: String = "move_handle",
    val onSwipeLeftAction: String = "open_sidebar",
    val onSwipeRightAction: String = "none",
    val onSwipeUpAction: String = "none",
    val onSwipeDownAction: String = "none"
) {
    fun getActionForGesture(gesture: String): String = when (gesture) {
        HandleGestures.TAP -> onTapAction
        HandleGestures.DOUBLE_TAP -> onDoubleTapAction
        HandleGestures.LONG_PRESS -> onLongPressAction
        HandleGestures.SWIPE_LEFT -> onSwipeLeftAction
        HandleGestures.SWIPE_RIGHT -> onSwipeRightAction
        HandleGestures.SWIPE_UP -> onSwipeUpAction
        HandleGestures.SWIPE_DOWN -> onSwipeDownAction
        else -> HandleManager.ACTION_NONE
    }

    fun withGestureAction(gesture: String, action: String): HandleConfig = when (gesture) {
        HandleGestures.TAP -> copy(onTapAction = action)
        HandleGestures.DOUBLE_TAP -> copy(onDoubleTapAction = action)
        HandleGestures.LONG_PRESS -> copy(onLongPressAction = action)
        HandleGestures.SWIPE_LEFT -> copy(onSwipeLeftAction = action)
        HandleGestures.SWIPE_RIGHT -> copy(onSwipeRightAction = action)
        HandleGestures.SWIPE_UP -> copy(onSwipeUpAction = action)
        HandleGestures.SWIPE_DOWN -> copy(onSwipeDownAction = action)
        else -> this
    }
}

/**
 * HandleGestureInfo: Summary representation of a configured gesture and its container relationship.
 */
data class HandleGestureInfo(
    val gesture: String,
    val action: String,
    val isEnabled: Boolean,
    val isContainerTarget: Boolean,
    val containerId: String,
    val pageCount: Int,
    val selectedPageId: String?
)

/**
 * HandleManager: Lightweight coordinator for floating trigger handle configurations.
 * Avoids heavy overhead and synchronizes directly with SharedPreferences.
 */
class HandleManager(private val context: Context) {

    val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val TAG = "HandleManager"
        const val PREFS_NAME = "FloatingReaderPrefs"
        const val KEY_HANDLES_COUNT = "handles_count"
        const val KEY_HANDLE_IDS = "handle_ids"
        const val KEY_DEFAULT_HANDLE_ENABLED = "handle_enabled_1"

        const val ACTION_OPEN_SIDEBAR = "open_sidebar"
        const val ACTION_MOVE_HANDLE = "move_handle"
        const val ACTION_NONE = "none"

        const val DEFAULT_PAGE_HYBRID = "default_hybrid"

        /**
         * Resolves independent container identity for a handle and gesture.
         * Contract requirement: "${handleId}_${gesture}"
         */
        fun getContainerId(handleId: String, gesture: String): String = "${handleId}_${gesture}"

        /**
         * Resolves SharedPreferences key for the gesture action on a handle index.
         */
        fun getGesturePrefKey(index: Int, gesture: String): String = when (gesture) {
            HandleGestures.TAP -> "handle_tap_$index"
            HandleGestures.DOUBLE_TAP -> "handle_double_tap_$index"
            HandleGestures.LONG_PRESS -> "handle_long_press_$index"
            HandleGestures.SWIPE_LEFT -> "handle_swipe_left_$index"
            HandleGestures.SWIPE_RIGHT -> "handle_swipe_right_$index"
            HandleGestures.SWIPE_UP -> "handle_swipe_up_$index"
            HandleGestures.SWIPE_DOWN -> "handle_swipe_down_$index"
            else -> "handle_${gesture}_$index"
        }

        /**
         * SharedPreferences key for the page stack of a specific container.
         * Contract requirement: "handle_${containerId}_pages"
         * Strictly isolates gesture stacks and avoids generic handle_${cleanHandleId}_pages bleed.
         */
        fun getContainerPagesKey(containerId: String): String = "handle_${containerId}_pages"

        /**
         * SharedPreferences key for the placed elements on a specific page of an independent container.
         * Contract requirement: "handle_${containerId}_page_${pageId}_elements"
         * Strictly enforces: handleId + gesture -> containerId -> pageId -> elements
         */
        fun getContainerPageElementsKey(containerId: String, pageId: String): String =
            "handle_${containerId}_page_${pageId}_elements"

        /**
         * SharedPreferences key for the currently selected page of an independent container.
         * Contract requirement: "handle_${containerId}_selected_page"
         * Strictly isolates selected page state per container and avoids generic handle-level bleed.
         */
        fun getContainerSelectedPageKey(containerId: String): String =
            "handle_${containerId}_selected_page"

        /**
         * SharedPreferences key for the configured Opening Face of an independent container.
         * Contract requirement: "handle_${containerId}_opening_face"
         * Distinct from the runtime selected/swiped page during a live session.
         */
        fun getContainerOpeningFaceKey(containerId: String): String =
            "handle_${containerId}_opening_face"

        fun getCleanHandleId(handleOrContainerId: String): String =
            PageManager.getCleanHandleId(handleOrContainerId)

        /**
         * Resolves whether a gesture action targets an independent Sidebar Container or an Element/action.
         */
        fun resolveGestureTarget(handleId: String, gesture: String, actionKey: String): GestureTarget {
            return when (actionKey) {
                ACTION_OPEN_SIDEBAR -> GestureTarget.Container(getContainerId(handleId, gesture))
                ACTION_NONE -> GestureTarget.None
                else -> GestureTarget.Action(actionKey)
            }
        }

        @Volatile
        private var instance: HandleManager? = null

        fun getInstance(context: Context): HandleManager {
            return instance ?: synchronized(this) {
                instance ?: HandleManager(context.applicationContext).also { instance = it }
            }
        }

        @androidx.annotation.VisibleForTesting
        fun resetForTesting() {
            instance = null
        }
    }

    /**
     * Retrieves the list of tracked handle IDs, falling back to legacy 1..handles_count.
     */
    fun getHandleIds(): List<String> {
        val raw = prefs.getString(KEY_HANDLE_IDS, null)
        if (!raw.isNullOrBlank()) {
            val list = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (list.isNotEmpty()) return list
        }
        val count = prefs.getInt(KEY_HANDLES_COUNT, 1)
        val ids = (1..maxOf(1, count)).map { "handle_$it" }
        prefs.edit().putString(KEY_HANDLE_IDS, ids.joinToString(",")).apply()
        return ids
    }

    private fun saveHandleIds(ids: List<String>) {
        prefs.edit()
            .putString(KEY_HANDLE_IDS, ids.joinToString(","))
            .putInt(KEY_HANDLES_COUNT, ids.size)
            .apply()
    }

    /**
     * Returns all configured handles (both enabled and disabled) for Settings management.
     */
    fun getAllHandles(): List<HandleConfig> {
        val ids = getHandleIds()
        val handles = mutableListOf<HandleConfig>()
        for (id in ids) {
            val handle = getHandle(id)
            if (handle != null) {
                handles.add(handle)
            }
        }
        if (handles.isEmpty()) {
            handles.add(createDefaultHandleConfig("handle_1"))
        }
        return handles
    }

    /**
     * Returns all currently enabled handles for Main runtime attachment.
     */
    fun getActiveHandles(): List<HandleConfig> {
        val all = getAllHandles()
        val active = all.filter { it.enabled }
        return active
    }

    private fun createDefaultHandleConfig(id: String): HandleConfig {
        return HandleConfig(
            id = id,
            name = "Primary Handle",
            enabled = true,
            edge = HandleEdge.RIGHT,
            positionPercent = 0.5f,
            widthDp = 12,
            heightDp = 120,
            color = Color.parseColor("#242962ff"),
            shape = HandleShape.SLANTED_BLOCK,
            alphaPercent = 14,
            onTapAction = ACTION_NONE,
            onDoubleTapAction = ACTION_NONE,
            onLongPressAction = ACTION_NONE,
            onSwipeLeftAction = ACTION_OPEN_SIDEBAR,
            onSwipeRightAction = ACTION_NONE,
            onSwipeUpAction = ACTION_NONE,
            onSwipeDownAction = ACTION_NONE
        )
    }

    fun updateHandlePosition(handleId: String, newPercent: Float) {
        val index = handleId.substringAfter("handle_").toIntOrNull() ?: 1
        prefs.edit().putFloat("handle_pos_$index", newPercent.coerceIn(0.05f, 0.95f)).apply()
    }

    /**
     * Resolves a HandleConfig by its unique handleId.
     */
    fun getHandle(handleId: String): HandleConfig? {
        val index = handleId.substringAfter("handle_").toIntOrNull() ?: return null
        val exists = prefs.contains("handle_name_$index") || prefs.contains("handle_enabled_$index") || handleId == "handle_1"
        if (!exists) {
            return null
        }

        val enabled = prefs.getBoolean("handle_enabled_$index", index == 1)
        val name = prefs.getString("handle_name_$index", "Handle $index") ?: "Handle $index"
        val edgeStr = prefs.getString("handle_edge_$index", "RIGHT") ?: "RIGHT"
        val edge = HandleEdge.fromString(edgeStr)
        val posPercent = prefs.getFloat("handle_pos_$index", 0.5f)
        val widthDp = try { prefs.getInt("handle_width_$index", 12) } catch (_: Exception) { 12 }
        val heightDp = try { prefs.getInt("handle_height_$index", 120) } catch (_: Exception) { 120 }
        val color = try {
            val raw = prefs.all["handle_color_$index"]
            when (raw) {
                is Int -> raw
                is String -> Color.parseColor(raw)
                else -> Color.parseColor("#242962ff")
            }
        } catch (e: Exception) {
            Color.parseColor("#242962ff")
        }
        val shapeStr = prefs.getString("handle_shape_$index", "SLANTED_BLOCK") ?: "SLANTED_BLOCK"
        val shape = HandleShape.fromString(shapeStr)
        val alpha = try { prefs.getInt("handle_alpha_$index", 14) } catch (_: Exception) { 14 }

        val isFirstHandle = (handleId == "handle_1")
        val defaultSwipeLeft = if (isFirstHandle) ACTION_OPEN_SIDEBAR else ACTION_NONE
        val tap = prefs.getString("handle_tap_$index", ACTION_NONE) ?: ACTION_NONE
        val doubleTap = prefs.getString("handle_double_tap_$index", ACTION_NONE) ?: ACTION_NONE
        val rawLongPress = prefs.getString("handle_long_press_$index", ACTION_NONE) ?: ACTION_NONE
        val longPress = if (rawLongPress == ACTION_MOVE_HANDLE) ACTION_NONE else rawLongPress
        var swipeLeft = prefs.getString("handle_swipe_left_$index", defaultSwipeLeft) ?: defaultSwipeLeft
        val swipeRight = prefs.getString("handle_swipe_right_$index", ACTION_NONE) ?: ACTION_NONE
        val swipeUp = prefs.getString("handle_swipe_up_$index", ACTION_NONE) ?: ACTION_NONE
        val swipeDown = prefs.getString("handle_swipe_down_$index", ACTION_NONE) ?: ACTION_NONE

        if (isFirstHandle) {
            val allActions = listOf(tap, doubleTap, longPress, swipeLeft, swipeRight, swipeUp, swipeDown)
            if (allActions.none { it == ACTION_OPEN_SIDEBAR }) {
                swipeLeft = ACTION_OPEN_SIDEBAR
                prefs.edit().putString("handle_swipe_left_1", ACTION_OPEN_SIDEBAR).apply()
            }
        }

        return HandleConfig(
            id = handleId,
            name = name,
            enabled = enabled,
            edge = edge,
            positionPercent = posPercent,
            widthDp = widthDp,
            heightDp = heightDp,
            color = color,
            shape = shape,
            alphaPercent = alpha,
            onTapAction = tap,
            onDoubleTapAction = doubleTap,
            onLongPressAction = longPress,
            onSwipeLeftAction = swipeLeft,
            onSwipeRightAction = swipeRight,
            onSwipeUpAction = swipeUp,
            onSwipeDownAction = swipeDown
        )
    }

    /**
     * Creates and persists a new Handle with distinct ID and safe defaults.
     */
    fun createHandle(name: String? = null, edge: HandleEdge = HandleEdge.RIGHT): HandleConfig {
        val currentIds = getHandleIds().toMutableList()
        var nextIndex = 1
        while (currentIds.contains("handle_$nextIndex")) {
            nextIndex++
        }
        val newId = "handle_$nextIndex"
        val handleName = name ?: "Handle $nextIndex"
        val config = HandleConfig(
            id = newId,
            name = handleName,
            enabled = true,
            edge = edge,
            positionPercent = 0.5f,
            widthDp = 12,
            heightDp = 120,
            color = Color.parseColor("#242962ff"),
            shape = HandleShape.SLANTED_BLOCK,
            alphaPercent = 14,
            onTapAction = ACTION_NONE,
            onDoubleTapAction = ACTION_NONE,
            onLongPressAction = ACTION_NONE,
            onSwipeLeftAction = ACTION_NONE,
            onSwipeRightAction = ACTION_NONE,
            onSwipeUpAction = ACTION_NONE,
            onSwipeDownAction = ACTION_NONE
        )
        currentIds.add(newId)
        saveHandleIds(currentIds)
        saveHandle(config)
        return config
    }

    /**
     * Persists all fields of a HandleConfig to SharedPreferences.
     */
    fun saveHandle(config: HandleConfig) {
        val index = config.id.substringAfter("handle_").toIntOrNull() ?: return
        val currentIds = getHandleIds().toMutableList()
        if (!currentIds.contains(config.id)) {
            currentIds.add(config.id)
            saveHandleIds(currentIds)
        }

        prefs.edit()
            .putString("handle_name_$index", config.name)
            .putBoolean("handle_enabled_$index", config.enabled)
            .putString("handle_edge_$index", config.edge.name)
            .putFloat("handle_pos_$index", config.positionPercent)
            .putInt("handle_width_$index", config.widthDp)
            .putInt("handle_height_$index", config.heightDp)
            .putInt("handle_color_$index", config.color)
            .putString("handle_shape_$index", config.shape.name)
            .putInt("handle_alpha_$index", config.alphaPercent)
            .putString("handle_tap_$index", config.onTapAction)
            .putString("handle_double_tap_$index", config.onDoubleTapAction)
            .putString("handle_long_press_$index", config.onLongPressAction)
            .putString("handle_swipe_left_$index", config.onSwipeLeftAction)
            .putString("handle_swipe_right_$index", config.onSwipeRightAction)
            .putString("handle_swipe_up_$index", config.onSwipeUpAction)
            .putString("handle_swipe_down_$index", config.onSwipeDownAction)
            .apply()

        // Ensure container pages key is seeded for any configured open_sidebar gesture
        val primaryContainerId = if (config.id == "handle_1") getFirstHandlePrimaryContainerId() else ""
        for (gesture in HandleGestures.ALL) {
            if (config.getActionForGesture(gesture) == ACTION_OPEN_SIDEBAR) {
                val containerId = getContainerId(config.id, gesture)
                val pagesKey = getContainerPagesKey(containerId)
                if (!prefs.contains(pagesKey)) {
                    val isFirstSidebar = containerId == "handle_1_swipe_left" || (config.id == "handle_1" && containerId == primaryContainerId)
                    val defaultPage = if (isFirstSidebar) DEFAULT_PAGE_HYBRID else "default_hybrid_$containerId"
                    prefs.edit().putString(pagesKey, defaultPage).apply()
                }
            }
        }

        notifyMainReload(context, config)
    }

    /**
     * Toggles enabled state for a handle.
     */
    fun setHandleEnabled(handleId: String, enabled: Boolean) {
        val index = handleId.substringAfter("handle_").toIntOrNull() ?: return
        prefs.edit().putBoolean("handle_enabled_$index", enabled).apply()
        notifyMainReload(context, getHandle(handleId))
    }

    /**
     * Safely deletes a handle and cleans up only its associated container data.
     * Guarantees other handles and containers remain unaffected.
     * Enforces First-Handle Invariant: handle_1 must never be deletable.
     */
    fun deleteHandle(handleId: String): Boolean {
        if (handleId == "handle_1") {
            LogKeeper.log(context, TAG, "Cannot delete handle_1: First-handle invariant enforced.")
            return false
        }

        val currentIds = getHandleIds().toMutableList()
        if (!currentIds.contains(handleId)) return false

        val index = handleId.substringAfter("handle_").toIntOrNull()

        // 1. Clean up container data (pages, selected page, elements) for all gestures on this handle
        for (gesture in HandleGestures.ALL) {
            val containerId = getContainerId(handleId, gesture)
            cleanContainerData(containerId)
        }

        // 2. Remove handle-specific preference keys
        val editor = prefs.edit()
        if (index != null) {
            editor.remove("handle_name_$index")
            editor.remove("handle_enabled_$index")
            editor.remove("handle_edge_$index")
            editor.remove("handle_pos_$index")
            editor.remove("handle_width_$index")
            editor.remove("handle_height_$index")
            editor.remove("handle_color_$index")
            editor.remove("handle_shape_$index")
            editor.remove("handle_alpha_$index")
            for (gesture in HandleGestures.ALL) {
                editor.remove(getGesturePrefKey(index, gesture))
            }
        }

        // 3. Update tracked IDs
        currentIds.remove(handleId)
        if (currentIds.isEmpty()) {
            val fallback = "handle_1"
            currentIds.add(fallback)
            editor.putBoolean("handle_enabled_1", true)
            editor.putString("handle_name_1", "Primary Handle")
        }

        editor.putString(KEY_HANDLE_IDS, currentIds.joinToString(","))
        editor.putInt(KEY_HANDLES_COUNT, currentIds.size)
        editor.apply()

        notifyMainReload(context)
        return true
    }

    /**
     * Purges only the container-specific keys for a given container identity.
     */
    fun cleanContainerData(containerId: String) {
        val editor = prefs.edit()
        editor.remove(getContainerPagesKey(containerId))
        editor.remove(getContainerSelectedPageKey(containerId))
        editor.remove(getContainerOpeningFaceKey(containerId))

        val prefix = "handle_${containerId}_"
        val allKeys = prefs.all.keys
        for (key in allKeys) {
            if (key.startsWith(prefix)) {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    /**
     * Migrates all container-specific data from [oldContainerId] to [newContainerId].
     * Preserves:
     * - Home Grid / complete page deck
     * - selected page
     * - element placements for every page
     * - page-specific layout/configurations (width, height, wrap_content, columns, color)
     * - Whitelist and feature configs
     * Retires the old container only after migration is complete.
     */
    fun migrateContainerData(oldContainerId: String, newContainerId: String) {
        if (oldContainerId == newContainerId) return

        val editor = prefs.edit()

        // 1. Migrate Page deck
        val oldPagesKey = getContainerPagesKey(oldContainerId)
        val pagesJson = prefs.getString(oldPagesKey, null)
        if (pagesJson != null) {
            editor.putString(getContainerPagesKey(newContainerId), pagesJson)
        }

        // 2. Migrate Selected page & Opening face
        val oldSelectedKey = getContainerSelectedPageKey(oldContainerId)
        val selectedPage = prefs.getString(oldSelectedKey, null)
        if (selectedPage != null) {
            editor.putString(getContainerSelectedPageKey(newContainerId), selectedPage)
        }
        val oldFaceKey = getContainerOpeningFaceKey(oldContainerId)
        val openingFace = prefs.getString(oldFaceKey, null)
        if (openingFace != null) {
            editor.putString(getContainerOpeningFaceKey(newContainerId), openingFace)
        }

        // 3. Migrate all container-prefixed keys ("handle_${oldContainerId}_*")
        val oldPrefix = "handle_${oldContainerId}_"
        val newPrefix = "handle_${newContainerId}_"
        val allKeys = prefs.all
        for ((key, value) in allKeys) {
            if (key.startsWith(oldPrefix)) {
                val newKey = key.replaceFirst(oldPrefix, newPrefix)
                when (value) {
                    is String -> editor.putString(newKey, value)
                    is Int -> editor.putInt(newKey, value)
                    is Boolean -> editor.putBoolean(newKey, value)
                    is Float -> editor.putFloat(newKey, value)
                    is Long -> editor.putLong(newKey, value)
                    is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(newKey, value as Set<String>)
                }
            } else if (key.startsWith("sidebar_apps_${oldContainerId}_")) {
                val newKey = key.replaceFirst("sidebar_apps_${oldContainerId}_", "sidebar_apps_${newContainerId}_")
                if (value is String) editor.putString(newKey, value)
            }
        }

        // 4. Migrate custom hybrid grid for this container if keyed with oldContainerId
        val oldGridKey = "hybrid_grid_default_hybrid_$oldContainerId"
        if (prefs.contains(oldGridKey)) {
            val gridVal = prefs.getString(oldGridKey, null)
            if (gridVal != null) {
                editor.putString("hybrid_grid_default_hybrid_$newContainerId", gridVal)
            }
            val cols = prefs.getInt("hybrid_grid_cols_default_hybrid_$oldContainerId", -1)
            if (cols != -1) {
                editor.putInt("hybrid_grid_cols_default_hybrid_$newContainerId", cols)
            }
        }

        editor.commit()

        // Clean up old container data only after migration commit succeeds
        cleanContainerData(oldContainerId)
        LogKeeper.log(context, TAG, "Migrated container data from '$oldContainerId' to '$newContainerId'")
    }

    /**
     * Resolves the primary container ID for handle_1 currently opening the sidebar.
     */
    fun getFirstHandlePrimaryContainerId(): String {
        val handle1 = getHandle("handle_1")
        if (handle1 != null) {
            for (gesture in listOf(HandleGestures.SWIPE_LEFT, HandleGestures.SWIPE_RIGHT, HandleGestures.TAP, HandleGestures.DOUBLE_TAP, HandleGestures.LONG_PRESS, HandleGestures.SWIPE_UP, HandleGestures.SWIPE_DOWN)) {
                if (handle1.getActionForGesture(gesture) == ACTION_OPEN_SIDEBAR) {
                    return getContainerId("handle_1", gesture)
                }
            }
        }
        return "handle_1_swipe_left"
    }

    /**
     * Configures the action for a specific gesture on a handle.
     * When handle_1 changes its sidebar opening gesture, seamlessly migrates existing container data.
     */
    fun configureGesture(handleId: String, gesture: String, action: String) {
        val index = handleId.substringAfter("handle_").toIntOrNull() ?: return

        if (handleId == "handle_1") {
            val handle1 = getHandle("handle_1")
            val currentGestureAction = handle1?.getActionForGesture(gesture) ?: ACTION_NONE

            if (action == ACTION_NONE && currentGestureAction == ACTION_OPEN_SIDEBAR) {
                val otherSidebarGestures = HandleGestures.ALL.filter { it != gesture && handle1?.getActionForGesture(it) == ACTION_OPEN_SIDEBAR }
                if (otherSidebarGestures.isEmpty()) {
                    LogKeeper.log(context, TAG, "Cannot disable the only sidebar gesture on handle_1: First-handle invariant enforced.")
                    return
                }
            }

            if (action == ACTION_OPEN_SIDEBAR) {
                val sidebarGestures = HandleGestures.ALL.filter { it != gesture && handle1?.getActionForGesture(it) == ACTION_OPEN_SIDEBAR }
                if (sidebarGestures.size == 1) {
                    val oldGesture = sidebarGestures.first()
                    val oldContainerId = getContainerId("handle_1", oldGesture)
                    val oldPagesKey = getContainerPagesKey(oldContainerId)
                    val oldPages = prefs.getString(oldPagesKey, null)
                    val hasCustomPages = oldPages != null && oldPages != DEFAULT_PAGE_HYBRID &&
                        !oldPages.startsWith("[{\"id\":\"default_hybrid")
                    val hasCustomData = hasCustomPages ||
                        prefs.contains("handle_${oldContainerId}_sidebar_width") ||
                        prefs.contains(getContainerSelectedPageKey(oldContainerId))
                    if (hasCustomData) {
                        val newContainerId = getContainerId("handle_1", gesture)
                        migrateContainerData(oldContainerId, newContainerId)
                        prefs.edit().putString(getGesturePrefKey(1, oldGesture), ACTION_NONE).apply()
                    }
                }
            }
        }

        val key = getGesturePrefKey(index, gesture)
        prefs.edit().putString(key, action).apply()

        if (action == ACTION_OPEN_SIDEBAR) {
            val containerId = getContainerId(handleId, gesture)
            val pagesKey = getContainerPagesKey(containerId)
            if (!prefs.contains(pagesKey)) {
                val isFirstSidebar = containerId == "handle_1_swipe_left" || (handleId == "handle_1" && containerId == getFirstHandlePrimaryContainerId())
                val defaultPage = if (isFirstSidebar) DEFAULT_PAGE_HYBRID else "default_hybrid_$containerId"
                prefs.edit().putString(pagesKey, defaultPage).apply()
            }
        }
        notifyMainReload(context, getHandle(handleId))
    }

    /**
     * Safely resets/removes a gesture action and optionally cleans its container data.
     * Enforces First-Handle Invariant: handle_1's required sidebar gesture must not be removed.
     */
    fun removeGesture(handleId: String, gesture: String, cleanContainerData: Boolean = false) {
        val index = handleId.substringAfter("handle_").toIntOrNull() ?: return
        if (handleId == "handle_1") {
            val handle1 = getHandle("handle_1")
            if (handle1?.getActionForGesture(gesture) == ACTION_OPEN_SIDEBAR) {
                val otherSidebarGestures = HandleGestures.ALL.filter { it != gesture && handle1?.getActionForGesture(it) == ACTION_OPEN_SIDEBAR }
                if (otherSidebarGestures.isEmpty()) {
                    LogKeeper.log(context, TAG, "Cannot remove the only sidebar gesture on handle_1: First-handle invariant enforced.")
                    return
                }
            }
        }

        prefs.edit().putString(getGesturePrefKey(index, gesture), ACTION_NONE).apply()
        if (cleanContainerData) {
            val containerId = getContainerId(handleId, gesture)
            cleanContainerData(containerId)
        }
        notifyMainReload(context, getHandle(handleId))
    }

    /**
     * Returns structured gesture information for all gestures of a handle.
     */
    fun getGesturesForHandle(handleId: String): List<HandleGestureInfo> {
        val handle = getHandle(handleId) ?: return emptyList()
        val list = mutableListOf<HandleGestureInfo>()
        for (gesture in HandleGestures.ALL) {
            val action = handle.getActionForGesture(gesture)
            val isEnabled = action != ACTION_NONE
            val isContainerTarget = action == ACTION_OPEN_SIDEBAR
            val containerId = getContainerId(handleId, gesture)
            val pages = if (isContainerTarget) getPagesForContainer(containerId) else emptyList()
            val selectedPage = if (isContainerTarget) getSelectedPageForContainer(containerId) else null
            list.add(
                HandleGestureInfo(
                    gesture = gesture,
                    action = action,
                    isEnabled = isEnabled,
                    isContainerTarget = isContainerTarget,
                    containerId = containerId,
                    pageCount = pages.size,
                    selectedPageId = selectedPage
                )
            )
        }
        return list
    }

    /**
     * Sends reload request to Main process to synchronize runtime triggers.
     */
    fun notifyMainReload(context: Context, config: HandleConfig? = null) {
        val configsToSync = if (config != null) listOf(config) else getAllHandles()
        try {
            val intent = Intent(context, HandleService::class.java).apply {
                action = OverlaySyncManager.ACTION_SYNC_PREF
                putExtra(OverlaySyncManager.EXTRA_TYPE, "SYNC_HANDLE")
                for (c in configsToSync) {
                    val index = c.id.substringAfter("handle_").toIntOrNull() ?: continue
                    putExtra("handle_name_$index", c.name)
                    putExtra("handle_enabled_$index", c.enabled)
                    putExtra("handle_edge_$index", c.edge.name)
                    putExtra("handle_pos_$index", c.positionPercent)
                    putExtra("handle_width_$index", c.widthDp)
                    putExtra("handle_height_$index", c.heightDp)
                    putExtra("handle_color_$index", c.color)
                    putExtra("handle_shape_$index", c.shape.name)
                    putExtra("handle_alpha_$index", c.alphaPercent)
                    putExtra("handle_tap_$index", c.onTapAction)
                    putExtra("handle_double_tap_$index", c.onDoubleTapAction)
                    putExtra("handle_long_press_$index", c.onLongPressAction)
                    putExtra("handle_swipe_left_$index", c.onSwipeLeftAction)
                    putExtra("handle_swipe_right_$index", c.onSwipeRightAction)
                    putExtra("handle_swipe_up_$index", c.onSwipeUpAction)
                    putExtra("handle_swipe_down_$index", c.onSwipeDownAction)
                }
                val ids = getHandleIds()
                putExtra(KEY_HANDLE_IDS, ids.joinToString(","))
                putExtra(KEY_HANDLES_COUNT, ids.size)
            }
            context.startService(intent)
        } catch (ignored: Exception) {}

        try {
            val bIntent = Intent(OverlaySyncManager.ACTION_SYNC_PREF).apply {
                setPackage(context.packageName)
                putExtra(OverlaySyncManager.EXTRA_TYPE, "SYNC_HANDLE")
                for (c in configsToSync) {
                    val index = c.id.substringAfter("handle_").toIntOrNull() ?: continue
                    putExtra("handle_name_$index", c.name)
                    putExtra("handle_enabled_$index", c.enabled)
                    putExtra("handle_edge_$index", c.edge.name)
                    putExtra("handle_pos_$index", c.positionPercent)
                    putExtra("handle_width_$index", c.widthDp)
                    putExtra("handle_height_$index", c.heightDp)
                    putExtra("handle_color_$index", c.color)
                    putExtra("handle_shape_$index", c.shape.name)
                    putExtra("handle_alpha_$index", c.alphaPercent)
                    putExtra("handle_tap_$index", c.onTapAction)
                    putExtra("handle_double_tap_$index", c.onDoubleTapAction)
                    putExtra("handle_long_press_$index", c.onLongPressAction)
                    putExtra("handle_swipe_left_$index", c.onSwipeLeftAction)
                    putExtra("handle_swipe_right_$index", c.onSwipeRightAction)
                    putExtra("handle_swipe_up_$index", c.onSwipeUpAction)
                    putExtra("handle_swipe_down_$index", c.onSwipeDownAction)
                }
                val ids = getHandleIds()
                putExtra(KEY_HANDLE_IDS, ids.joinToString(","))
                putExtra(KEY_HANDLES_COUNT, ids.size)
            }
            context.sendBroadcast(bIntent)
        } catch (ignored: Exception) {}

        try {
            val legacyIntent = Intent(HandleService.ACTION_RELOAD_HANDLES).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(legacyIntent)
        } catch (ignored: Exception) {}
    }

    /**
     * Resolves the independent SidebarContainer belonging to a specific Handle + Gesture.
     * Checks if the handle exists and whether the gesture action is configured to target a container
     * (i.e. ACTION_OPEN_SIDEBAR).
     */
    fun resolveContainer(handleId: String, gesture: String): SidebarContainer? {
        val handle = getHandle(handleId) ?: return null
        val action = handle.getActionForGesture(gesture)
        if (action != ACTION_OPEN_SIDEBAR) {
            return null
        }
        val containerId = getContainerId(handleId, gesture)
        return SidebarContainer(
            containerId = containerId,
            handleId = handleId,
            gesture = gesture,
            enabled = handle.enabled
        )
    }

    /**
     * Retrieves an independent SidebarContainer by its containerId: "${handleId}_${gesture}".
     */
    fun getContainer(containerId: String): SidebarContainer? {
        for (gesture in HandleGestures.ALL) {
            if (containerId.endsWith("_$gesture")) {
                val handleId = containerId.removeSuffix("_$gesture")
                return resolveContainer(handleId, gesture)
            }
        }
        return null
    }

    /**
     * Retrieves all active SidebarContainers across all gestures for a specific handle.
     */
    fun getContainersForHandle(handleId: String): List<SidebarContainer> {
        val handle = getHandle(handleId) ?: return emptyList()
        val containers = mutableListOf<SidebarContainer>()
        for (gesture in HandleGestures.ALL) {
            if (handle.getActionForGesture(gesture) == ACTION_OPEN_SIDEBAR) {
                containers.add(
                    SidebarContainer(
                        containerId = getContainerId(handleId, gesture),
                        handleId = handleId,
                        gesture = gesture,
                        enabled = handle.enabled
                    )
                )
            }
        }
        return containers
    }

    /**
     * Retrieves the isolated page stack for an independent container.
     * Contract: Strictly uses "handle_${containerId}_pages".
     *
     * If uninitialized, performs a conservative legacy migration check ONLY for the primary
     * gesture of the handle (swipe_left), safely adopting legacy generic data if present
     * and immediately clearing the legacy key to prevent bleeding into other gestures.
     */
    fun getPagesForContainer(
        containerId: String,
        defaultPages: List<String> = listOf(DEFAULT_PAGE_HYBRID)
    ): List<String> {
        val isolatedKey = getContainerPagesKey(containerId)
        val raw = prefs.getString(isolatedKey, null)
        if (raw != null) {
            return parsePages(raw)
        }

        // Conservative migration check if no isolated data exists yet
        val migratedPages = checkAndMigrateLegacyPages(containerId)
        if (migratedPages != null) {
            return migratedPages
        }

        return defaultPages
    }

    /**
     * Persists the page stack strictly under "handle_${containerId}_pages".
     */
    fun savePagesForContainer(containerId: String, pages: List<String>) {
        val isolatedKey = getContainerPagesKey(containerId)
        val serialized = pages.filter { it.isNotBlank() }.joinToString(",")
        prefs.edit().putString(isolatedKey, serialized).apply()
    }

    /**
     * Convenience overload for SidebarContainer instance.
     */
    fun getPagesForContainer(
        container: SidebarContainer,
        defaultPages: List<String> = listOf(DEFAULT_PAGE_HYBRID)
    ): List<String> = getPagesForContainer(container.containerId, defaultPages)

    /**
     * Convenience overload for SidebarContainer instance.
     */
    fun savePagesForContainer(container: SidebarContainer, pages: List<String>) {
        savePagesForContainer(container.containerId, pages)
    }

    /**
     * Retrieves the persisted selected page ID for an independent container.
     */
    fun getSelectedPageForContainer(containerId: String): String? {
        val isolatedKey = getContainerSelectedPageKey(containerId)
        return prefs.getString(isolatedKey, null)
    }

    /**
     * Convenience overload for SidebarContainer instance.
     */
    fun getSelectedPageForContainer(container: SidebarContainer): String? =
        getSelectedPageForContainer(container.containerId)

    /**
     * Persists the selected page ID for an independent container.
     */
    fun saveSelectedPageForContainer(containerId: String, pageId: String?) {
        val isolatedKey = getContainerSelectedPageKey(containerId)
        if (pageId != null) {
            prefs.edit().putString(isolatedKey, pageId).apply()
            OverlaySyncManager.syncString(context, isolatedKey, pageId)
        } else {
            prefs.edit().remove(isolatedKey).apply()
        }
    }

    /**
     * Convenience overload for SidebarContainer instance.
     */
    fun saveSelectedPageForContainer(container: SidebarContainer, pageId: String?) {
        saveSelectedPageForContainer(container.containerId, pageId)
    }

    /**
     * Parses a raw stored string (JSON array, comma-separated or bracket-enclosed) into a list of page IDs.
     */
    fun parsePages(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val parsedPages = PageManager.parseRawPagesString(raw)
        if (parsedPages.isNotEmpty()) {
            return parsedPages.map { it.pageId }
        }
        val trimmed = raw.trim()
        val clean = if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
        return clean.split(",")
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() }
    }

    /**
     * Conservative legacy migration:
     * Checks if older generic keys exist (e.g. "handle_${cleanHandleId}_pages" or "handle_${handleId}_pages").
     * ONLY migrates for the default primary gesture (swipe_left), and deletes the legacy key
     * immediately so subsequent gesture containers never inherit or bleed into the same stack.
     */
    private fun checkAndMigrateLegacyPages(containerId: String): List<String>? {
        if (!containerId.endsWith("_${HandleGestures.SWIPE_LEFT}")) {
            return null
        }
        val handleId = containerId.removeSuffix("_${HandleGestures.SWIPE_LEFT}")

        val cleanHandleId = handleId.removePrefix("handle_")
        val possibleLegacyKeys = listOf(
            "handle_${cleanHandleId}_pages",
            "handle_${handleId}_pages"
        )

        for (legacyKey in possibleLegacyKeys) {
            if (prefs.contains(legacyKey)) {
                val legacyRaw = prefs.getString(legacyKey, null)
                if (!legacyRaw.isNullOrBlank()) {
                    val parsed = parsePages(legacyRaw)
                    if (parsed.isNotEmpty()) {
                        val isolatedKey = getContainerPagesKey(containerId)
                        prefs.edit()
                            .putString(isolatedKey, parsed.joinToString(","))
                            .remove(legacyKey)
                            .apply()
                        LogKeeper.log(
                            context,
                            "HandleManager",
                            "Migrated legacy page stack from '$legacyKey' to isolated container key '$isolatedKey'"
                        )
                        return parsed
                    }
                }
            }
        }
        return null
    }
}
