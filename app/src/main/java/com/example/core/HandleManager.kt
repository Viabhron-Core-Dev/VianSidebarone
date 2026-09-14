package com.example.core

import android.content.Context
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
    val edge: HandleEdge = HandleEdge.LEFT,
    val positionPercent: Float = 0.5f, // 0.0 to 1.0 along the screen edge
    val widthDp: Int = 18,
    val heightDp: Int = 120,
    val color: Int = Color.parseColor("#99444444"),
    val shape: HandleShape = HandleShape.ROUNDED_RECT,
    val alphaPercent: Int = 80,
    // Gesture Action Keys
    val onTapAction: String = "open_sidebar",
    val onDoubleTapAction: String = "none",
    val onLongPressAction: String = "move_handle",
    val onSwipeLeftAction: String = "open_sidebar",
    val onSwipeRightAction: String = "open_sidebar",
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
}

/**
 * HandleManager: Lightweight coordinator for floating trigger handle configurations.
 * Avoids heavy overhead and synchronizes directly with SharedPreferences.
 */
class HandleManager(private val context: Context) {

    val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        const val PREFS_NAME = "FloatingReaderPrefs"
        const val KEY_HANDLES_COUNT = "handles_count"
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
    }

    fun getActiveHandles(): List<HandleConfig> {
        val handles = mutableListOf<HandleConfig>()
        val count = prefs.getInt(KEY_HANDLES_COUNT, 1)

        for (i in 1..count) {
            val enabled = prefs.getBoolean("handle_enabled_$i", i == 1)
            if (!enabled) continue

            val id = "handle_$i"
            val name = prefs.getString("handle_name_$i", "Handle $i") ?: "Handle $i"
            val edgeStr = prefs.getString("handle_edge_$i", if (i == 1) "LEFT" else "RIGHT") ?: "LEFT"
            val edge = try { HandleEdge.valueOf(edgeStr) } catch (e: Exception) { HandleEdge.LEFT }
            val posPercent = prefs.getFloat("handle_pos_$i", 0.5f)
            val widthDp = prefs.getInt("handle_width_$i", 18)
            val heightDp = prefs.getInt("handle_height_$i", 120)
            val color = prefs.getInt("handle_color_$i", Color.parseColor("#99444444"))
            val shapeStr = prefs.getString("handle_shape_$i", "ROUNDED_RECT") ?: "ROUNDED_RECT"
            val shape = try { HandleShape.valueOf(shapeStr) } catch (e: Exception) { HandleShape.ROUNDED_RECT }
            val alpha = prefs.getInt("handle_alpha_$i", 80)

            val tap = prefs.getString("handle_tap_$i", ACTION_OPEN_SIDEBAR) ?: ACTION_OPEN_SIDEBAR
            val doubleTap = prefs.getString("handle_double_tap_$i", ACTION_NONE) ?: ACTION_NONE
            val longPress = prefs.getString("handle_long_press_$i", ACTION_MOVE_HANDLE) ?: ACTION_MOVE_HANDLE
            val swipeLeft = prefs.getString("handle_swipe_left_$i", ACTION_OPEN_SIDEBAR) ?: ACTION_OPEN_SIDEBAR
            val swipeRight = prefs.getString("handle_swipe_right_$i", ACTION_OPEN_SIDEBAR) ?: ACTION_OPEN_SIDEBAR
            val swipeUp = prefs.getString("handle_swipe_up_$i", ACTION_NONE) ?: ACTION_NONE
            val swipeDown = prefs.getString("handle_swipe_down_$i", ACTION_NONE) ?: ACTION_NONE

            handles.add(
                HandleConfig(
                    id = id,
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
            )
        }

        if (handles.isEmpty()) {
            // Guarantee at least 1 default active handle
            handles.add(
                HandleConfig(
                    id = "handle_1",
                    name = "Primary Handle",
                    enabled = true,
                    edge = HandleEdge.LEFT,
                    positionPercent = 0.5f,
                    widthDp = 18,
                    heightDp = 120,
                    color = Color.parseColor("#99444444"),
                    shape = HandleShape.ROUNDED_RECT,
                    alphaPercent = 80
                )
            )
        }
        return handles
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
        val count = prefs.getInt(KEY_HANDLES_COUNT, 1)
        if (index < 1 || index > count) {
            // Fallback for default primary handle
            if (handleId == "handle_1") {
                return HandleConfig(
                    id = "handle_1",
                    name = "Primary Handle",
                    enabled = true,
                    edge = HandleEdge.LEFT,
                    positionPercent = 0.5f,
                    widthDp = 18,
                    heightDp = 120,
                    color = Color.parseColor("#99444444"),
                    shape = HandleShape.ROUNDED_RECT,
                    alphaPercent = 80
                )
            }
            return null
        }

        val enabled = prefs.getBoolean("handle_enabled_$index", index == 1)
        val name = prefs.getString("handle_name_$index", "Handle $index") ?: "Handle $index"
        val edgeStr = prefs.getString("handle_edge_$index", if (index == 1) "LEFT" else "RIGHT") ?: "LEFT"
        val edge = try { HandleEdge.valueOf(edgeStr) } catch (e: Exception) { HandleEdge.LEFT }
        val posPercent = prefs.getFloat("handle_pos_$index", 0.5f)
        val widthDp = prefs.getInt("handle_width_$index", 18)
        val heightDp = prefs.getInt("handle_height_$index", 120)
        val color = prefs.getInt("handle_color_$index", Color.parseColor("#99444444"))
        val shapeStr = prefs.getString("handle_shape_$index", "ROUNDED_RECT") ?: "ROUNDED_RECT"
        val shape = try { HandleShape.valueOf(shapeStr) } catch (e: Exception) { HandleShape.ROUNDED_RECT }
        val alpha = prefs.getInt("handle_alpha_$index", 80)

        val tap = prefs.getString("handle_tap_$index", ACTION_OPEN_SIDEBAR) ?: ACTION_OPEN_SIDEBAR
        val doubleTap = prefs.getString("handle_double_tap_$index", ACTION_NONE) ?: ACTION_NONE
        val longPress = prefs.getString("handle_long_press_$index", ACTION_MOVE_HANDLE) ?: ACTION_MOVE_HANDLE
        val swipeLeft = prefs.getString("handle_swipe_left_$index", ACTION_OPEN_SIDEBAR) ?: ACTION_OPEN_SIDEBAR
        val swipeRight = prefs.getString("handle_swipe_right_$index", ACTION_OPEN_SIDEBAR) ?: ACTION_OPEN_SIDEBAR
        val swipeUp = prefs.getString("handle_swipe_up_$index", ACTION_NONE) ?: ACTION_NONE
        val swipeDown = prefs.getString("handle_swipe_down_$index", ACTION_NONE) ?: ACTION_NONE

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
        val lastUnderscore = containerId.lastIndexOf('_')
        if (lastUnderscore <= 0) return null
        val handleId = containerId.substring(0, lastUnderscore)
        val gesture = containerId.substring(lastUnderscore + 1)
        return resolveContainer(handleId, gesture)
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
     * Parses a raw stored string (comma-separated or bracket-enclosed) into a list of page IDs.
     */
    fun parsePages(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
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
        val lastUnderscore = containerId.lastIndexOf('_')
        if (lastUnderscore <= 0) return null
        val handleId = containerId.substring(0, lastUnderscore)
        val gesture = containerId.substring(lastUnderscore + 1)

        // Only migrate for the default primary gesture (swipe_left)
        if (gesture != HandleGestures.SWIPE_LEFT) {
            return null
        }

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
