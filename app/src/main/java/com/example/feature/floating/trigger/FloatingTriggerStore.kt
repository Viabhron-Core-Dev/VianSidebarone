package com.example.feature.floating.trigger

import android.content.Context
import android.content.SharedPreferences
import com.example.core.LogKeeper
import com.example.feature.element.ElementIdParser
import com.example.feature.sidebar.SidebarItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable record representing a standalone Floating Trigger button overlay.
 * Independent from mini-app snapshots (DurableMiniAppStore) and from legacy container navigation.
 */
data class FloatingTriggerRecord(
    val triggerId: String,
    val targetElementId: String,
    val label: String,
    val x: Int,
    val y: Int,
    val active: Boolean = true
)

/**
 * Compatibility helper for persisted `floating_trigger:<targetId>` elements.
 *
 * Historically `floating_trigger:<containerId>` opened a Sidebar container via
 * `SidebarManager.openContainerById(containerId)` (e.g. `left_0_swipe_right` or `notes`).
 * Standalone Floating Trigger buttons associate with a Sidebar element ID
 * (e.g. `app:com.foo`, `system:torch`, `quicktile:wifi`, `link:...`, `pwa:...`).
 *
 * Rule:
 * - If [targetId] parses as a real Sidebar element via [ElementIdParser.parse] (and is not
 *   a recursive `floating_trigger:`), it is a standalone Floating Trigger button.
 * - Otherwise, treat it as a legacy container-navigation target so existing saved container
 *   triggers continue working without silent breakage.
 */
object FloatingTriggerCompat {
    fun isLegacyContainerTarget(context: Context, targetId: String): Boolean {
        val trimmed = targetId.trim()
        if (trimmed.isEmpty()) return false
        val parsed = ElementIdParser.parse(context, trimmed)
        return parsed == null
    }

    fun isStandaloneElementTarget(context: Context, targetId: String): Boolean {
        val trimmed = targetId.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("floating_trigger:")) return false
        return ElementIdParser.parse(context, trimmed) != null
    }

    /**
     * Deterministic trigger ID derived from the associated element ID so toggling a grid entry
     * maps consistently to the same standalone floating trigger button and persisted position.
     */
    fun triggerIdForElement(targetElementId: String): String {
        val normalized = targetElementId.trim()
        val hash = normalized.hashCode().toUInt().toString(16)
        return "ft_$hash"
    }
}

/**
 * Clamps a floating trigger button coordinate into usable screen bounds so it never becomes
 * permanently unreachable off-screen or behind system bars.
 */
object FloatingTriggerBoundsHelper {
    fun clampPosition(
        x: Int,
        y: Int,
        buttonSizePx: Int,
        screenWidth: Int,
        screenHeight: Int,
        safeTopInsetPx: Int = 0,
        safeBottomInsetPx: Int = 0,
        marginPx: Int = 0
    ): Pair<Int, Int> {
        val safeW = screenWidth.coerceAtLeast(buttonSizePx)
        val safeH = screenHeight.coerceAtLeast(buttonSizePx)
        val minX = marginPx.coerceAtMost(maxOf(0, safeW - buttonSizePx))
        val maxX = maxOf(minX, safeW - buttonSizePx - marginPx)
        val minY = maxOf(0, safeTopInsetPx + marginPx).coerceAtMost(maxOf(0, safeH - buttonSizePx))
        val maxY = maxOf(minY, safeH - buttonSizePx - maxOf(0, safeBottomInsetPx) - marginPx)
        return Pair(x.coerceIn(minX, maxX), y.coerceIn(minY, maxY))
    }

    fun defaultPosition(
        existingCount: Int,
        buttonSizePx: Int,
        density: Float,
        screenWidth: Int,
        screenHeight: Int,
        safeTopInsetPx: Int = 0,
        safeBottomInsetPx: Int = 0
    ): Pair<Int, Int> {
        val marginPx = (16 * density).toInt()
        val stepPx = (buttonSizePx + (12 * density).toInt())
        val rawX = screenWidth - buttonSizePx - marginPx
        val rawY = (screenHeight * 0.35f).toInt() + (existingCount % 5) * stepPx
        return clampPosition(
            x = rawX,
            y = rawY,
            buttonSizePx = buttonSizePx,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            safeTopInsetPx = safeTopInsetPx,
            safeBottomInsetPx = safeBottomInsetPx,
            marginPx = (4 * density).toInt()
        )
    }
}

/**
 * SharedPreferences-backed persistence store for standalone Floating Trigger buttons.
 * Uses synchronous `.commit()` so positions and active states survive app/process restarts.
 */
class FloatingTriggerStore private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun getAllTriggers(): List<FloatingTriggerRecord> = synchronized(lock) {
        val raw = prefs.getString(KEY_TRIGGERS_JSON, null) ?: return emptyList()
        parseRecords(raw)
    }

    fun getActiveTriggers(): List<FloatingTriggerRecord> =
        getAllTriggers().filter { it.active }

    fun getTrigger(triggerId: String): FloatingTriggerRecord? =
        getAllTriggers().firstOrNull { it.triggerId == triggerId }

    fun getTriggerByElementId(targetElementId: String): FloatingTriggerRecord? =
        getAllTriggers().firstOrNull { it.targetElementId == targetElementId }

    fun saveTrigger(record: FloatingTriggerRecord): Boolean = synchronized(lock) {
        val current = getAllTriggers().toMutableList()
        val idx = current.indexOfFirst { it.triggerId == record.triggerId }
        if (idx >= 0) {
            current[idx] = record
        } else {
            current.add(record)
        }
        persistRecordsLocked(current)
    }

    fun updatePosition(triggerId: String, x: Int, y: Int): Boolean = synchronized(lock) {
        val current = getAllTriggers().toMutableList()
        val idx = current.indexOfFirst { it.triggerId == triggerId }
        if (idx < 0) return false
        current[idx] = current[idx].copy(x = x, y = y)
        persistRecordsLocked(current)
    }

    fun setActive(triggerId: String, active: Boolean): Boolean = synchronized(lock) {
        val current = getAllTriggers().toMutableList()
        val idx = current.indexOfFirst { it.triggerId == triggerId }
        if (idx < 0) return false
        current[idx] = current[idx].copy(active = active)
        persistRecordsLocked(current)
    }

    fun deleteTrigger(triggerId: String): Boolean = synchronized(lock) {
        val current = getAllTriggers().filterNot { it.triggerId == triggerId }
        persistRecordsLocked(current)
    }

    fun clearAll(): Boolean = synchronized(lock) {
        prefs.edit().remove(KEY_TRIGGERS_JSON).commit()
    }

    /**
     * Discovers existing Sidebar grid elements persisted across hybrid grids and sidebar pages
     * so users can associate a Floating Trigger directly with an existing Sidebar grid element.
     */
    fun discoverExistingSidebarGridElements(): List<SidebarItem> {
        val sidebarPrefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        val seenIds = LinkedHashSet<String>()
        val results = mutableListOf<SidebarItem>()

        fun addCandidateId(rawId: String?) {
            val id = rawId?.trim() ?: return
            if (id.isEmpty() || id.startsWith("floating_trigger:") || !seenIds.add(id)) return
            val parsed = ElementIdParser.parse(context, id) ?: return
            if (parsed is SidebarItem.FloatingTrigger || parsed is SidebarItem.Folder) return
            results.add(parsed)
        }

        for ((key, value) in sidebarPrefs.all) {
            val str = value as? String ?: continue
            val isHybridGridKey = key.contains("hybrid_grid_") &&
                !key.contains("_cols_") &&
                !key.contains("_modified_") &&
                !key.contains("_height_")
            val isAppsKey = key.contains("sidebar_apps") || key.contains("page_apps_")
            if (!isHybridGridKey && !isAppsKey) continue
            try {
                val arr = JSONArray(str)
                for (i in 0 until arr.length()) {
                    val elem = arr.opt(i)
                    when (elem) {
                        is JSONObject -> {
                            addCandidateId(elem.optString("elementId"))
                            addCandidateId(elem.optString("id"))
                            addCandidateId(elem.optString("itemId"))
                        }
                        is String -> addCandidateId(elem)
                    }
                }
            } catch (_: Throwable) {
                // Ignore non-JSON preference entries safely
            }
        }
        return results
    }

    private fun parseRecords(raw: String): List<FloatingTriggerRecord> {
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<FloatingTriggerRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val triggerId = obj.optString("triggerId")
                val targetElementId = obj.optString("targetElementId")
                if (triggerId.isBlank() || targetElementId.isBlank()) continue
                list.add(
                    FloatingTriggerRecord(
                        triggerId = triggerId,
                        targetElementId = targetElementId,
                        label = obj.optString("label", "Trigger"),
                        x = obj.optInt("x", 0),
                        y = obj.optInt("y", 0),
                        active = obj.optBoolean("active", true)
                    )
                )
            }
            list
        } catch (e: Throwable) {
            LogKeeper.logError(context, TAG, "Failed to parse FloatingTriggerStore records", e)
            emptyList()
        }
    }

    private fun persistRecordsLocked(records: List<FloatingTriggerRecord>): Boolean {
        return try {
            val arr = JSONArray()
            for (rec in records) {
                val obj = JSONObject().apply {
                    put("triggerId", rec.triggerId)
                    put("targetElementId", rec.targetElementId)
                    put("label", rec.label)
                    put("x", rec.x)
                    put("y", rec.y)
                    put("active", rec.active)
                }
                arr.put(obj)
            }
            prefs.edit().putString(KEY_TRIGGERS_JSON, arr.toString()).commit()
        } catch (e: Throwable) {
            LogKeeper.logError(context, TAG, "Failed to persist FloatingTriggerStore records", e)
            false
        }
    }

    companion object {
        private const val TAG = "FloatingTriggerStore"
        const val PREFS_NAME = "FloatingTriggerPrefs"
        const val KEY_TRIGGERS_JSON = "floating_triggers_json"

        @Volatile
        private var instance: FloatingTriggerStore? = null

        fun getInstance(context: Context): FloatingTriggerStore {
            return instance ?: synchronized(this) {
                instance ?: FloatingTriggerStore(context.applicationContext).also { instance = it }
            }
        }

        internal fun resetForTesting() {
            synchronized(this) {
                instance = null
            }
        }
    }
}
