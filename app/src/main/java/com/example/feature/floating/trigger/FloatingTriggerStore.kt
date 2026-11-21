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
 * Explicit validation result for a candidate Floating Trigger target ID.
 * Derived directly from the branches that [com.example.feature.element.ElementActionDispatcher.execute]
 * can genuinely execute standalone (without a Sidebar anchor view or widget host).
 */
sealed class FloatingTriggerTargetValidation {
    data class ValidStandalone(val item: SidebarItem) : FloatingTriggerTargetValidation()
    data class LegacyContainer(val containerId: String) : FloatingTriggerTargetValidation()
    data class Unsupported(val reason: String) : FloatingTriggerTargetValidation()
}

/**
 * Compatibility & target-eligibility helper for persisted `floating_trigger:<targetId>` elements.
 *
 * Historically `floating_trigger:<containerId>` opened a Sidebar container via
 * `SidebarManager.openContainerById(containerId)` (e.g. `left_0_swipe_right` or `notes`).
 * Standalone Floating Trigger buttons associate ONLY with Sidebar elements that can actually
 * execute through the central [com.example.feature.element.ElementActionDispatcher] without a
 * Sidebar anchor view or AppWidget host (e.g. `app:`, `link:`, `pwa:`, `quicktile:`, `volume:`,
 * `media:`, `display:`, `system:`, `settings_shortcut:`, `intent:`, executable `page_window:`).
 *
 * Non-executable / container / host-dependent items (`folder:`, `widget:`, `popup_widget:`,
 * `spacer:`, recursive `floating_trigger:`, or malformed element IDs with a known prefix) are
 * strictly rejected and never misclassified as legacy containers.
 */
object FloatingTriggerCompat {
    private val KNOWN_ELEMENT_PREFIXES = listOf(
        "app:",
        "intent:",
        "floating_trigger:",
        "quicktile:",
        "system:",
        "page_window:",
        "volume:",
        "media:",
        "display:",
        "settings_shortcut:",
        "widget:",
        "popup_widget:",
        "folder:",
        "link:",
        "spacer:",
        "pwa:",
        "quick_call:"
    )

    /**
     * Mini-app types hosted natively in the Main process via [com.example.feature.floating.FloatingMiniAppBridge].
     */
    internal val MAIN_ONLY_MINI_APP_TYPES = setOf(
        "note",
        "test_notes",
        "reader",
        "ebook_reader",
        "dictionary"
    )

    /**
     * Built-in mini-app factory types pre-registered in [com.example.feature.floating.HeavyFloatingHost].
     * Used only as fallback when [validateItem] is called without a [Context]; when a [Context] is
     * available, [com.example.feature.floating.HeavyFloatingHost.isFactoryRegistered] is queried live
     * so dynamically registered factories are supported and unregistered ones are rejected.
     */
    internal val DEFAULT_REGISTERED_HEAVY_MINI_APP_TYPES = setOf(
        "generic",
        "work_notes",
        "floating_pwa",
        "dictionary",
        "test_notes",
        "note"
    )

    /**
     * Non-widget embedded page types explicitly handled by
     * [com.example.feature.heavy.page.HeavyPageHostManager.createEmbeddedPage].
     * Excludes `"widget"` and `"single_widget"` which require a specific widget host/ID.
     */
    internal val EMBEDDED_HEAVY_PAGE_WINDOW_TYPES = setOf(
        "calculator",
        "compass",
        "scheduler",
        "notifications",
        "notification",
        "app_tracker",
        "resources_tracker",
        "media_player",
        "hybrid_grid",
        "default_hybrid",
        "tools",
        "apps",
        "apps_grid",
        "widgets_grid",
        "widgets"
    )

    fun hasKnownElementPrefix(targetId: String): Boolean {
        val trimmed = targetId.trim()
        return KNOWN_ELEMENT_PREFIXES.any { trimmed.startsWith(it) }
    }

    /**
     * Returns true when [baseType] corresponds to a genuinely launchable `PageWindow` target:
     * 1. Main-only mini-apps (`FloatingMiniAppBridge`), OR
     * 2. Explicitly registered factories in `HeavyFloatingHost` (built-in and dynamic), OR
     * 3. Non-widget embedded page views explicitly constructed by `HeavyPageHostManager`.
     */
    fun isSupportedPageWindowType(context: Context?, baseType: String): Boolean {
        val clean = baseType.trim()
        if (clean.isEmpty() || clean == "widget" || clean == "single_widget") {
            return false
        }
        if (clean in MAIN_ONLY_MINI_APP_TYPES) {
            return true
        }
        if (clean in EMBEDDED_HEAVY_PAGE_WINDOW_TYPES) {
            return true
        }
        return if (context != null) {
            com.example.feature.floating.HeavyFloatingHost
                .getInstance(context)
                .isFactoryRegistered(clean)
        } else {
            clean in DEFAULT_REGISTERED_HEAVY_MINI_APP_TYPES
        }
    }

    private fun validatePageWindow(context: Context?, item: SidebarItem.PageWindow): FloatingTriggerTargetValidation {
        val rawPageType = item.pageType
        if (rawPageType.isBlank()) {
            return FloatingTriggerTargetValidation.Unsupported(
                "Page window type is missing or malformed."
            )
        }
        val trimmed = rawPageType.trim()
        val hasColon = trimmed.contains(":")
        val baseType = if (hasColon) trimmed.substringBefore(":").trim() else trimmed
        if (baseType.isEmpty()) {
            return FloatingTriggerTargetValidation.Unsupported(
                "Page window type is missing or malformed."
            )
        }
        if (hasColon) {
            val instancePart = trimmed.substringAfter(":").trim()
            if (instancePart.isEmpty()) {
                return FloatingTriggerTargetValidation.Unsupported(
                    "Page window instance identifier is malformed."
                )
            }
        }
        if (baseType == "widget" || baseType == "single_widget") {
            return FloatingTriggerTargetValidation.Unsupported(
                "Widget windows cannot run as standalone Floating Triggers."
            )
        }
        return if (isSupportedPageWindowType(context, baseType)) {
            FloatingTriggerTargetValidation.ValidStandalone(item)
        } else {
            FloatingTriggerTargetValidation.Unsupported(
                "Page window type '$baseType' is not registered and cannot run as a standalone Floating Trigger."
            )
        }
    }

    /**
     * Authoritative item-level check matching [com.example.feature.element.ElementActionDispatcher.execute]
     * when called standalone (`onShowFolder = null`, `onShowWidget = null`).
     */
    fun validateItem(item: SidebarItem): FloatingTriggerTargetValidation {
        return validateItem(null, item)
    }

    fun validateItem(context: Context?, item: SidebarItem): FloatingTriggerTargetValidation {
        return when (item) {
            is SidebarItem.App -> {
                if (item.packageName.isNotBlank()) {
                    FloatingTriggerTargetValidation.ValidStandalone(item)
                } else {
                    FloatingTriggerTargetValidation.Unsupported("App package name is missing.")
                }
            }
            is SidebarItem.Link -> {
                if (item.uuid.isNotBlank() && item.url.isNotBlank() && item.url != "https://") {
                    FloatingTriggerTargetValidation.ValidStandalone(item)
                } else {
                    FloatingTriggerTargetValidation.Unsupported("Link URL is missing or invalid.")
                }
            }
            is SidebarItem.Pwa -> {
                if (item.pwaId.isNotBlank()) {
                    FloatingTriggerTargetValidation.ValidStandalone(item)
                } else {
                    FloatingTriggerTargetValidation.Unsupported("Offline PWA identifier is missing.")
                }
            }
            is SidebarItem.QuickCall -> {
                if (item.uuid.isBlank()) {
                    FloatingTriggerTargetValidation.Unsupported("Quick Call identifier is missing.")
                } else if (context != null) {
                    val record = com.example.feature.quickcall.QuickCallStore
                        .getInstance(context)
                        .getRecord(item.uuid)
                    if (record != null && com.example.feature.quickcall.QuickCallNumberValidator.isValidPhoneNumber(record.phoneNumber)) {
                        FloatingTriggerTargetValidation.ValidStandalone(item)
                    } else {
                        FloatingTriggerTargetValidation.Unsupported("Quick Call contact or phone number is missing or invalid.")
                    }
                } else {
                    FloatingTriggerTargetValidation.ValidStandalone(item)
                }
            }
            is SidebarItem.QuickTile,
            is SidebarItem.VolumeAction,
            is SidebarItem.MediaAction,
            is SidebarItem.DisplayAction,
            is SidebarItem.SettingsShortcut -> {
                FloatingTriggerTargetValidation.ValidStandalone(item)
            }
            is SidebarItem.SystemAction -> {
                if (item.action == "translation_floating") {
                    FloatingTriggerTargetValidation.Unsupported(
                        "Translation has been deferred to Vian World and cannot run as a Floating Trigger."
                    )
                } else {
                    FloatingTriggerTargetValidation.ValidStandalone(item)
                }
            }
            is SidebarItem.IntentAction -> {
                if (item.uri.isNotBlank()) {
                    FloatingTriggerTargetValidation.ValidStandalone(item)
                } else {
                    FloatingTriggerTargetValidation.Unsupported("Intent URI is missing.")
                }
            }
            is SidebarItem.PageWindow -> validatePageWindow(context, item)
            is SidebarItem.Folder -> FloatingTriggerTargetValidation.Unsupported(
                "Folders require a Sidebar anchor popup and cannot run as standalone Floating Triggers."
            )
            is SidebarItem.PopupWidget -> FloatingTriggerTargetValidation.Unsupported(
                "Popup Widgets require a Sidebar anchor popup and cannot run as standalone Floating Triggers."
            )
            is SidebarItem.Widget -> FloatingTriggerTargetValidation.Unsupported(
                "Embedded Widgets require a grid host and cannot run as standalone Floating Triggers."
            )
            is SidebarItem.Spacer -> FloatingTriggerTargetValidation.Unsupported(
                "Spacers are layout elements and have no executable action."
            )
            is SidebarItem.FloatingTrigger -> FloatingTriggerTargetValidation.Unsupported(
                "A Floating Trigger cannot target another Floating Trigger."
            )
        }
    }

    fun isExecutableElement(item: SidebarItem): Boolean {
        return validateItem(item) is FloatingTriggerTargetValidation.ValidStandalone
    }

    fun isExecutableElement(context: Context, item: SidebarItem): Boolean {
        return validateItem(context, item) is FloatingTriggerTargetValidation.ValidStandalone
    }

    fun validateTarget(context: Context, targetId: String): FloatingTriggerTargetValidation {
        val trimmed = targetId.trim()
        if (trimmed.isEmpty()) {
            return FloatingTriggerTargetValidation.Unsupported("No target Sidebar element was specified.")
        }
        if (trimmed.startsWith("floating_trigger:")) {
            return FloatingTriggerTargetValidation.Unsupported(
                "A Floating Trigger cannot target another Floating Trigger."
            )
        }
        if (trimmed.startsWith("popup_widget:")) {
            return FloatingTriggerTargetValidation.Unsupported(
                "Popup Widgets require a Sidebar anchor popup and cannot run as standalone Floating Triggers."
            )
        }
        if (trimmed.startsWith("widget:")) {
            return FloatingTriggerTargetValidation.Unsupported(
                "Embedded Widgets require a grid host and cannot run as standalone Floating Triggers."
            )
        }
        if (trimmed.startsWith("folder:")) {
            return FloatingTriggerTargetValidation.Unsupported(
                "Folders require a Sidebar anchor popup and cannot run as standalone Floating Triggers."
            )
        }
        if (trimmed.startsWith("spacer:")) {
            return FloatingTriggerTargetValidation.Unsupported(
                "Spacers are layout elements and have no executable action."
            )
        }

        val parsed = ElementIdParser.parse(context, trimmed)
        if (parsed != null) {
            return validateItem(context, parsed)
        }

        if (hasKnownElementPrefix(trimmed)) {
            return FloatingTriggerTargetValidation.Unsupported(
                "Selected element is unrecognized or incomplete and cannot be triggered."
            )
        }

        return FloatingTriggerTargetValidation.LegacyContainer(trimmed)
    }

    fun isLegacyContainerTarget(context: Context, targetId: String): Boolean {
        return validateTarget(context, targetId) is FloatingTriggerTargetValidation.LegacyContainer
    }

    fun isStandaloneElementTarget(context: Context, targetId: String): Boolean {
        return validateTarget(context, targetId) is FloatingTriggerTargetValidation.ValidStandalone
    }

    fun resolveExecutableItem(context: Context, targetId: String): SidebarItem? {
        return (validateTarget(context, targetId) as? FloatingTriggerTargetValidation.ValidStandalone)?.item
    }

    fun unsupportedReason(context: Context, targetId: String): String? {
        return (validateTarget(context, targetId) as? FloatingTriggerTargetValidation.Unsupported)?.reason
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
            if (id.isEmpty() || !seenIds.add(id)) return
            val executableItem = FloatingTriggerCompat.resolveExecutableItem(context, id) ?: return
            results.add(executableItem)
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
