package com.example.feature.element

import android.content.Context
import android.content.Intent
import com.example.core.LogKeeper
import com.example.feature.sidebar.GridWidgetItem
import com.example.loadHybridLocalItems
import com.example.saveHybridItems

/**
 * ElementPlacementHelper: Centralized placement engine supporting continuous
 * fast-adding of elements (Apps, Actions, Links) without dismissing the picker.
 */
object ElementPlacementHelper {

    private const val TAG = "ElementPlacementHelper"

    fun addElementToHybridGrid(
        context: Context,
        pageId: String,
        elementId: String,
        cols: Int = 1,
        rows: Int = 1,
        containerId: String = "sidebar"
    ): Boolean {
        return try {
            val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
            val parsedItems = loadHybridLocalItems(prefs, pageId, containerId).toMutableList()
            val colsKey = if (containerId != "sidebar" || prefs.contains("handle_${containerId}_hybrid_grid_cols_$pageId")) "handle_${containerId}_hybrid_grid_cols_$pageId" else "hybrid_grid_cols_$pageId"
            val totalCols = prefs.getInt(colsKey, prefs.getInt("hybrid_grid_cols_$pageId", 4))
            val effectiveCols = minOf(cols, totalCols)

            var targetX = 0
            var targetY = 0
            var found = false
            var searchY = 0
            while (!found && searchY < 100) {
                for (searchX in 0..totalCols - effectiveCols) {
                    var overlap = false
                    for (item in parsedItems) {
                        if (searchX < item.x + item.cols && searchX + effectiveCols > item.x &&
                            searchY < item.y + item.rows && searchY + rows > item.y) {
                            overlap = true
                            break
                        }
                    }
                    if (!overlap) {
                        targetX = searchX
                        targetY = searchY
                        found = true
                        break
                    }
                }
                if (!found) searchY++
            }

            parsedItems.add(
                GridWidgetItem(
                    id = elementId,
                    cols = effectiveCols,
                    rows = rows,
                    x = targetX,
                    y = targetY
                )
            )
            saveHybridItems(prefs, pageId, parsedItems, context, containerId)
            LogKeeper.writeLog(TAG, "Fast-add: added $elementId to $pageId in container $containerId at ($targetX, $targetY)")

            val intent = Intent("ELEMENT_ADDED_TO_HYBRID").apply {
                putExtra("PAGE_ID", pageId)
                putExtra("CONTAINER_ID", containerId)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
            true
        } catch (e: Exception) {
            LogKeeper.writeLog(TAG, "Fast-add error for $elementId in $containerId: ${e.message}")
            false
        }
    }

    /**
     * Finds any orphaned elements stored under the legacy "sidebar" scope (hybrid_grid_$pageId)
     * that are missing in the container-isolated scope (handle_${containerId}_hybrid_grid_$pageId).
     */
    fun findOrphanedLegacyElements(
        prefs: android.content.SharedPreferences,
        pageId: String,
        containerId: String
    ): List<GridWidgetItem> {
        if (containerId == "sidebar") return emptyList()
        val legacyKey = "hybrid_grid_$pageId"
        if (!prefs.contains(legacyKey)) return emptyList()
        val legacyJson = prefs.getString(legacyKey, null) ?: return emptyList()
        val legacyItems = com.example.parseHybridItems(legacyJson)
        val containerItems = loadHybridLocalItems(prefs, pageId, containerId)
        val containerElementIds = containerItems.map { it.id }.toSet()
        return legacyItems.filter { it.id !in containerElementIds }
    }

    /**
     * Recovers orphaned elements from legacy scope into container-isolated scope without creating duplicates.
     * Preserves the original legacy data in SharedPreferences.
     */
    fun recoverOrphanedLegacyElements(
        prefs: android.content.SharedPreferences,
        pageId: String,
        containerId: String,
        context: Context? = null
    ): List<GridWidgetItem> {
        val orphans = findOrphanedLegacyElements(prefs, pageId, containerId)
        if (orphans.isEmpty()) return emptyList()

        val currentItems = loadHybridLocalItems(prefs, pageId, containerId).toMutableList()
        val colsKey = if (containerId != "sidebar" || prefs.contains("handle_${containerId}_hybrid_grid_cols_$pageId")) "handle_${containerId}_hybrid_grid_cols_$pageId" else "hybrid_grid_cols_$pageId"
        val totalCols = prefs.getInt(colsKey, prefs.getInt("hybrid_grid_cols_$pageId", 4))

        val recoveredList = mutableListOf<GridWidgetItem>()
        for (orphan in orphans) {
            val effectiveCols = minOf(orphan.cols, totalCols)
            var targetX = 0
            var targetY = 0
            var found = false
            var searchY = 0
            while (!found && searchY < 100) {
                for (searchX in 0..totalCols - effectiveCols) {
                    var overlap = false
                    for (item in currentItems) {
                        if (searchX < item.x + item.cols && searchX + effectiveCols > item.x &&
                            searchY < item.y + item.rows && searchY + orphan.rows > item.y) {
                            overlap = true
                            break
                        }
                    }
                    if (!overlap) {
                        targetX = searchX
                        targetY = searchY
                        found = true
                        break
                    }
                }
                if (!found) searchY++
            }
            val placed = orphan.copy(cols = effectiveCols, x = targetX, y = targetY)
            currentItems.add(placed)
            recoveredList.add(placed)
            LogKeeper.writeLog(TAG, "Recovered orphaned element ${orphan.id} into container $containerId page $pageId at ($targetX, $targetY)")
        }
        saveHybridItems(prefs, pageId, currentItems, context, containerId)
        return recoveredList
    }
}
