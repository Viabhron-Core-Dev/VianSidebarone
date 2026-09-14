package com.example.feature.element

import android.content.Context
import com.example.core.HandleManager
import com.example.core.LogKeeper
import com.example.core.PageManager
import org.json.JSONArray
import java.util.UUID

/**
 * AddElementRequest: Strongly-typed request to place an Element on a specific container page.
 * Enforces explicit scoping: containerId and pageId are required.
 */
data class AddElementRequest(
    val containerId: String,
    val pageId: String,
    val actionKey: String,
    val position: Int = -1, // -1 denotes appending at the end of the page list
    val customTitle: String? = null,
    val customIconUri: String? = null,
    val config: Map<String, String> = emptyMap()
)

/**
 * AddElementResult: Outcome of attempting to add an Element to a page.
 */
sealed class AddElementResult {
    data class Success(val placement: ElementPlacement) : AddElementResult()
    data class Failure(val reason: String) : AddElementResult()
}

/**
 * ElementPlacementManager: Lightweight coordinator for persisting, querying, and updating
 * Element placements on container pages.
 *
 * Guarantees:
 * 1. Scope isolation: handleId + gesture -> containerId -> pageId -> elements.
 * 2. Validates action keys through [ElementActionRegistry] without instantiating the actual Element.
 * 3. Uses lightweight SharedPreferences JSON persistence matching HandleManager and PageManager.
 * 4. Zero eager Element instantiations.
 */
class ElementPlacementManager private constructor(private val context: Context) {

    private val handleManager: HandleManager = HandleManager.getInstance(context)
    private val pageManager: PageManager = PageManager.getInstance(context)
    private val actionRegistry: ElementActionRegistry = ElementActionRegistry.getInstance(context)

    /**
     * Retrieves all element placements on a container page, sorted by position.
     */
    fun getElementsForPage(containerId: String, pageId: String): List<ElementPlacement> {
        val key = HandleManager.getContainerPageElementsKey(containerId, pageId)
        val raw = handleManager.prefs.getString(key, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(raw)
            val elements = mutableListOf<ElementPlacement>()
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                elements.add(ElementPlacement.fromJson(item))
            }
            elements.sortedBy { it.position }
        } catch (e: Exception) {
            LogKeeper.logError(context, TAG, "Failed to parse elements for key '$key'", e)
            emptyList()
        }
    }

    /**
     * Validates and adds an Element to the specified container and page.
     * Checks registration in ElementActionRegistry without instantiating the Element class.
     */
    fun addElement(request: AddElementRequest): AddElementResult {
        val containerId = request.containerId.trim()
        val pageId = request.pageId.trim()
        val actionKey = request.actionKey.trim()

        if (containerId.isEmpty()) {
            return AddElementResult.Failure("Invalid request: containerId cannot be empty")
        }
        if (pageId.isEmpty()) {
            return AddElementResult.Failure("Invalid request: pageId cannot be empty")
        }
        if (actionKey.isEmpty()) {
            return AddElementResult.Failure("Invalid request: actionKey cannot be empty")
        }

        // Validate container exists and is enabled
        val container = handleManager.getContainer(containerId)
        if (container == null) {
            return AddElementResult.Failure("Container '$containerId' does not exist")
        }

        // Validate page exists in container
        val page = pageManager.getPage(containerId, pageId)
        if (page == null) {
            return AddElementResult.Failure("Page '$pageId' not found in container '$containerId'")
        }

        // Validate actionKey through ElementActionRegistry (without instantiating the element)
        if (!actionRegistry.isRegistered(actionKey)) {
            return AddElementResult.Failure("Action key '$actionKey' is not registered in ElementActionRegistry")
        }

        val existing = getElementsForPage(containerId, pageId).toMutableList()

        val targetPosition = if (request.position in 0..existing.size) {
            request.position
        } else {
            existing.size
        }

        val placementId = "${pageId}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val newPlacement = ElementPlacement(
            placementId = placementId,
            containerId = containerId,
            pageId = pageId,
            actionKey = actionKey,
            position = targetPosition,
            customTitle = request.customTitle,
            customIconUri = request.customIconUri,
            config = request.config
        )

        existing.add(targetPosition, newPlacement)

        // Normalize positions to 0, 1, 2, ...
        val normalized = existing.mapIndexed { idx, item ->
            if (item.placementId == placementId) {
                newPlacement.copy(position = idx)
            } else {
                item.copy(position = idx)
            }
        }

        saveElements(containerId, pageId, normalized)
        LogKeeper.log(context, TAG, "Added element '$actionKey' (id=$placementId) at position $targetPosition on $containerId/$pageId")

        return AddElementResult.Success(newPlacement.copy(position = targetPosition))
    }

    /**
     * Removes an Element by placementId from the container page.
     */
    fun removeElement(containerId: String, pageId: String, placementId: String): Boolean {
        val existing = getElementsForPage(containerId, pageId).toMutableList()
        val removed = existing.removeAll { it.placementId == placementId }
        if (removed) {
            val normalized = existing.mapIndexed { idx, item -> item.copy(position = idx) }
            saveElements(containerId, pageId, normalized)
            LogKeeper.log(context, TAG, "Removed element '$placementId' from $containerId/$pageId")
            return true
        }
        return false
    }

    /**
     * Reorders an element from fromPosition to toPosition on the container page.
     */
    fun reorderElements(
        containerId: String,
        pageId: String,
        fromPosition: Int,
        toPosition: Int
    ): List<ElementPlacement> {
        val existing = getElementsForPage(containerId, pageId).toMutableList()
        if (fromPosition in existing.indices && toPosition in existing.indices && fromPosition != toPosition) {
            val item = existing.removeAt(fromPosition)
            existing.add(toPosition, item)
            val normalized = existing.mapIndexed { idx, elem -> elem.copy(position = idx) }
            saveElements(containerId, pageId, normalized)
            LogKeeper.log(context, TAG, "Reordered elements on $containerId/$pageId ($fromPosition -> $toPosition)")
            return normalized
        }
        return existing
    }

    /**
     * Persists the elements list to SharedPreferences under the isolated key.
     */
    fun saveElements(containerId: String, pageId: String, elements: List<ElementPlacement>) {
        val key = HandleManager.getContainerPageElementsKey(containerId, pageId)
        val jsonArray = JSONArray()
        elements.forEach { jsonArray.put(it.toJson()) }
        handleManager.prefs.edit().putString(key, jsonArray.toString()).apply()
        com.example.feature.sidebar.SidebarManager.getInstance(context).onElementsChanged(containerId, pageId)
    }

    /**
     * Clears all elements from a page.
     */
    fun clearElementsForPage(containerId: String, pageId: String) {
        val key = HandleManager.getContainerPageElementsKey(containerId, pageId)
        handleManager.prefs.edit().remove(key).apply()
        com.example.feature.sidebar.SidebarManager.getInstance(context).onElementsChanged(containerId, pageId)
        LogKeeper.log(context, TAG, "Cleared elements for $containerId/$pageId")
    }

    companion object {
        private const val TAG = "ElementPlacementManager"

        @Volatile
        private var instance: ElementPlacementManager? = null

        fun getInstance(context: Context): ElementPlacementManager {
            return instance ?: synchronized(this) {
                instance ?: ElementPlacementManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
