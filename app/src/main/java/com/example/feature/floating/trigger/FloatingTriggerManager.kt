package com.example.feature.floating.trigger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.provider.Settings
import com.example.core.FloatingWindowManager
import com.example.core.LogKeeper
import com.example.feature.element.ElementActionDispatcher
import com.example.feature.element.ElementIdParser
import com.example.feature.sidebar.SidebarItem
import com.example.feature.sidebar.SidebarManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates standalone Floating Trigger buttons:
 * - Resolves backward compatibility between legacy container-navigation `floating_trigger:<containerId>`
 *   items and standalone element-backed Floating Trigger buttons.
 * - Dispatches tapped Floating Trigger buttons exclusively through the central
 *   [ElementActionDispatcher] (never creating a second action implementation).
 * - Persists active triggers and coordinates via [FloatingTriggerStore] and restores them on restart.
 */
class FloatingTriggerManager private constructor(private val context: Context) {

    private val store = FloatingTriggerStore.getInstance(context)
    private val windowManager = FloatingWindowManager.getInstance(context)
    private val activeWindows = ConcurrentHashMap<String, FloatingTriggerButtonWindow>()

    /**
     * Injectable action dispatcher hook (defaults to [ElementActionDispatcher.execute]).
     * Exposed internally for deterministic JVM unit testing.
     */
    internal var actionExecutor: (Context, SidebarItem) -> Boolean = { ctx, item ->
        ElementActionDispatcher.execute(ctx, item)
    }

    /**
     * Injectable overlay-permission check (defaults to [Settings.canDrawOverlays]).
     */
    internal var canDrawOverlaysCheck: (Context) -> Boolean = { ctx ->
        try {
            Settings.canDrawOverlays(ctx)
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * Unified handler for [SidebarItem.FloatingTrigger] items tapped in the Sidebar or dispatched
     * through [ElementActionDispatcher].
     *
     * Preserves legacy behavior for persisted container-navigation IDs while activating/toggling
     * standalone Floating Trigger buttons for element-backed IDs.
     */
    fun handleSidebarTriggerItem(item: SidebarItem.FloatingTrigger): Boolean {
        val targetId = item.targetId.trim()
        if (targetId.isEmpty()) return false

        if (FloatingTriggerCompat.isLegacyContainerTarget(context, targetId)) {
            LogKeeper.log(context, TAG, "LEGACY_CONTAINER_TRIGGER target=$targetId")
            SidebarManager.getInstance(context).openContainerById(targetId)
            return true
        }

        return toggleTriggerForElement(targetId, item.label.removePrefix("Trigger:").trim())
    }

    /**
     * Toggles a standalone Floating Trigger button for [targetElementId].
     * If already visible/active, hides and deactivates it; otherwise creates/activates and shows it.
     */
    fun toggleTriggerForElement(targetElementId: String, customLabel: String? = null): Boolean {
        if (!FloatingTriggerCompat.isStandaloneElementTarget(context, targetElementId)) {
            LogKeeper.log(context, TAG, "REJECT_INVALID_TRIGGER_TARGET target=$targetElementId")
            return false
        }
        val triggerId = FloatingTriggerCompat.triggerIdForElement(targetElementId)
        val existingWindow = activeWindows[triggerId]
        val existingRecord = store.getTrigger(triggerId)

        return if (existingWindow != null || existingRecord?.active == true) {
            deactivateTrigger(triggerId)
            true
        } else {
            ensureActiveTrigger(targetElementId, customLabel) != null
        }
    }

    /**
     * Ensures a standalone Floating Trigger button for [targetElementId] is persisted as active
     * and attached on screen (if overlay permission is available).
     */
    fun ensureActiveTrigger(targetElementId: String, customLabel: String? = null): FloatingTriggerRecord? {
        if (!FloatingTriggerCompat.isStandaloneElementTarget(context, targetElementId)) {
            return null
        }
        val parsed = ElementIdParser.parse(context, targetElementId) ?: return null
        val triggerId = FloatingTriggerCompat.triggerIdForElement(targetElementId)
        val resolvedLabel = customLabel?.takeIf { it.isNotBlank() } ?: parsed.label
        val existing = store.getTrigger(triggerId)

        val buttonSizePx = FloatingTriggerButtonWindow.computeButtonSizePx(context)
        val density = context.resources.displayMetrics.density
        val screenW = windowManager.screenWidth.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels.coerceAtLeast(1080)
        val screenH = windowManager.screenHeight.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels.coerceAtLeast(1920)
        val safeTop = getStatusBarHeightPx()
        val safeBottom = (24 * density).toInt()

        val (clampedX, clampedY) = if (existing != null) {
            FloatingTriggerBoundsHelper.clampPosition(
                x = existing.x,
                y = existing.y,
                buttonSizePx = buttonSizePx,
                screenWidth = screenW,
                screenHeight = screenH,
                safeTopInsetPx = safeTop,
                safeBottomInsetPx = safeBottom,
                marginPx = (4 * density).toInt()
            )
        } else {
            FloatingTriggerBoundsHelper.defaultPosition(
                existingCount = store.getActiveTriggers().size,
                buttonSizePx = buttonSizePx,
                density = density,
                screenWidth = screenW,
                screenHeight = screenH,
                safeTopInsetPx = safeTop,
                safeBottomInsetPx = safeBottom
            )
        }

        val record = FloatingTriggerRecord(
            triggerId = triggerId,
            targetElementId = targetElementId,
            label = resolvedLabel,
            x = clampedX,
            y = clampedY,
            active = true
        )
        store.saveTrigger(record)
        attachWindowForRecord(record)
        LogKeeper.log(context, TAG, "TRIGGER_ACTIVATED id=$triggerId target=$targetElementId pos=($clampedX,$clampedY)")
        return record
    }

    /**
     * Deactivates and removes a standalone Floating Trigger button while retaining its saved
     * coordinates for future re-activation.
     */
    fun deactivateTrigger(triggerId: String) {
        store.setActive(triggerId, false)
        detachWindow(triggerId)
        LogKeeper.log(context, TAG, "TRIGGER_DEACTIVATED id=$triggerId")
    }

    /**
     * Permanently deletes a standalone Floating Trigger record and removes its overlay window.
     */
    fun removeTrigger(triggerId: String) {
        store.deleteTrigger(triggerId)
        detachWindow(triggerId)
        LogKeeper.log(context, TAG, "TRIGGER_REMOVED id=$triggerId")
    }

    /**
     * Restores all persisted active standalone Floating Trigger buttons (e.g. on service startup
     * or process restart), clamping coordinates to current display bounds so buttons stay reachable.
     */
    fun restoreActiveTriggers(): Int {
        if (!canDrawOverlaysCheck(context)) {
            LogKeeper.log(context, TAG, "TRIGGER_RESTORE_SKIPPED reason=no_overlay_permission")
            return 0
        }
        val activeRecords = store.getActiveTriggers()
        var restored = 0
        val buttonSizePx = FloatingTriggerButtonWindow.computeButtonSizePx(context)
        val density = context.resources.displayMetrics.density
        val screenW = windowManager.screenWidth.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels.coerceAtLeast(1080)
        val screenH = windowManager.screenHeight.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels.coerceAtLeast(1920)
        val safeTop = getStatusBarHeightPx()
        val safeBottom = (24 * density).toInt()

        for (record in activeRecords) {
            if (!FloatingTriggerCompat.isStandaloneElementTarget(context, record.targetElementId)) {
                continue
            }
            val (clampedX, clampedY) = FloatingTriggerBoundsHelper.clampPosition(
                x = record.x,
                y = record.y,
                buttonSizePx = buttonSizePx,
                screenWidth = screenW,
                screenHeight = screenH,
                safeTopInsetPx = safeTop,
                safeBottomInsetPx = safeBottom,
                marginPx = (4 * density).toInt()
            )
            val safeRecord = if (clampedX != record.x || clampedY != record.y) {
                record.copy(x = clampedX, y = clampedY).also { store.saveTrigger(it) }
            } else {
                record
            }
            if (attachWindowForRecord(safeRecord) != null) {
                restored++
            }
        }
        LogKeeper.log(context, TAG, "TRIGGERS_RESTORED count=$restored totalActive=${activeRecords.size}")
        return restored
    }

    /**
     * Executes the associated Sidebar element action through [ElementActionDispatcher].
     * Never dispatches a second independent action implementation.
     */
    fun executeAssociatedElement(targetElementId: String): Boolean {
        if (targetElementId.startsWith("floating_trigger:")) {
            LogKeeper.log(context, TAG, "TRIGGER_RECURSION_BLOCKED target=$targetElementId")
            return false
        }
        val parsed = ElementIdParser.parse(context, targetElementId)
        if (parsed == null) {
            LogKeeper.log(context, TAG, "TRIGGER_TARGET_UNRESOLVED target=$targetElementId")
            return false
        }
        return actionExecutor(context, parsed)
    }

    fun getActiveWindow(triggerId: String): FloatingTriggerButtonWindow? = activeWindows[triggerId]

    fun getActiveWindowCount(): Int = activeWindows.size

    fun detachAll() {
        val ids = activeWindows.keys.toList()
        for (id in ids) {
            detachWindow(id)
        }
    }

    private fun attachWindowForRecord(record: FloatingTriggerRecord): FloatingTriggerButtonWindow? {
        if (!canDrawOverlaysCheck(context)) {
            return null
        }
        activeWindows[record.triggerId]?.let { existing ->
            existing.bounds.x = record.x
            existing.bounds.y = record.y
            windowManager.updateBounds(existing.windowId, existing.bounds)
            windowManager.showWindow(existing.windowId)
            return existing
        }

        val iconBitmap = resolveIconBitmap(record.targetElementId)
        val buttonWindow = FloatingTriggerButtonWindow(
            triggerId = record.triggerId,
            targetElementId = record.targetElementId,
            label = record.label,
            context = context,
            initialX = record.x,
            initialY = record.y,
            iconBitmap = iconBitmap,
            onTriggerTapped = { _, targetId ->
                executeAssociatedElement(targetId)
            },
            onPositionCommitted = { triggerId, x, y ->
                store.updatePosition(triggerId, x, y)
            }
        )
        activeWindows[record.triggerId] = buttonWindow
        windowManager.registerWindow(buttonWindow)
        windowManager.showWindow(buttonWindow.windowId)
        return buttonWindow
    }

    private fun detachWindow(triggerId: String) {
        val window = activeWindows.remove(triggerId)
        if (window != null) {
            windowManager.unregisterWindow(window.windowId)
        } else {
            windowManager.unregisterWindow("${FloatingTriggerButtonWindow.WINDOW_ID_PREFIX}$triggerId")
        }
    }

    private fun resolveIconBitmap(targetElementId: String): Bitmap? {
        val parsed = ElementIdParser.parse(context, targetElementId) ?: return null
        return try {
            when (parsed) {
                is SidebarItem.App -> {
                    val drawable = context.packageManager.getApplicationIcon(parsed.packageName)
                    drawableToBitmap(drawable)
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
            return drawable.bitmap
        }
        val size = (32 * context.resources.displayMetrics.density).toInt().coerceAtLeast(32)
        return try {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    private fun getStatusBarHeightPx(): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else (24 * context.resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val TAG = "FloatingTriggerManager"

        @Volatile
        private var instance: FloatingTriggerManager? = null

        fun getInstance(context: Context): FloatingTriggerManager {
            return instance ?: synchronized(this) {
                instance ?: FloatingTriggerManager(context.applicationContext).also { instance = it }
            }
        }

        internal fun resetForTesting() {
            synchronized(this) {
                instance?.detachAll()
                instance = null
            }
        }
    }
}
