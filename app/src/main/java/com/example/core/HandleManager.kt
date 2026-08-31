package com.example.core

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import com.example.util.HandleEdge
import com.example.util.HandleShape

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
)

/**
 * HandleManager: Lightweight coordinator for floating trigger handle configurations.
 * Avoids heavy overhead and synchronizes directly with SharedPreferences.
 */
class HandleManager(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        const val PREFS_NAME = "FloatingReaderPrefs"
        const val KEY_HANDLES_COUNT = "handles_count"
        const val KEY_DEFAULT_HANDLE_ENABLED = "handle_enabled_1"

        const val ACTION_OPEN_SIDEBAR = "open_sidebar"
        const val ACTION_MOVE_HANDLE = "move_handle"
        const val ACTION_NONE = "none"

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
}
