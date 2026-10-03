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
        rows: Int = 1
    ): Boolean {
        return try {
            val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
            val parsedItems = loadHybridLocalItems(prefs, pageId).toMutableList()
            val totalCols = prefs.getInt("hybrid_grid_cols_$pageId", 4)
            val effectiveCols = minOf(cols, totalCols)

            var targetX = 0
            var targetY = 0
            var found = false
            var searchY = 0
            while (!found && searchY < 100) {
                for (searchX in 0..totalCols - effectiveCols + 1) {
                    if (searchX + effectiveCols > totalCols) continue
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
            saveHybridItems(prefs, pageId, parsedItems, context)
            LogKeeper.writeLog(TAG, "Fast-add: added $elementId to $pageId at ($targetX, $targetY)")

            val intent = Intent("ELEMENT_ADDED_TO_HYBRID").apply {
                putExtra("PAGE_ID", pageId)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
            true
        } catch (e: Exception) {
            LogKeeper.writeLog(TAG, "Fast-add error for $elementId: ${e.message}")
            false
        }
    }
}
