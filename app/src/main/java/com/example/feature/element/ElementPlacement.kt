package com.example.feature.element

import org.json.JSONObject

/**
 * ElementPlacement: Lightweight, persistent representation of an Element placed on a container page.
 *
 * Preserves the independent container architecture:
 * handleId + gesture -> containerId -> pageId -> elements
 *
 * Contains zero View or Composable references, keeping memory usage minimal.
 */
data class ElementPlacement(
    val placementId: String,
    val containerId: String,
    val pageId: String,
    val actionKey: String,
    val position: Int,
    val customTitle: String? = null,
    val customIconUri: String? = null,
    val config: Map<String, String> = emptyMap()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("placementId", placementId)
        put("containerId", containerId)
        put("pageId", pageId)
        put("actionKey", actionKey)
        put("position", position)
        if (customTitle != null) put("customTitle", customTitle)
        if (customIconUri != null) put("customIconUri", customIconUri)
        val configObj = JSONObject()
        config.forEach { (k, v) -> configObj.put(k, v) }
        put("config", configObj)
    }

    companion object {
        fun fromJson(json: JSONObject): ElementPlacement {
            val placementId = json.getString("placementId")
            val containerId = json.getString("containerId")
            val pageId = json.getString("pageId")
            val actionKey = json.getString("actionKey")
            val position = json.optInt("position", 0)
            val customTitle = if (json.has("customTitle") && !json.isNull("customTitle")) json.getString("customTitle") else null
            val customIconUri = if (json.has("customIconUri") && !json.isNull("customIconUri")) json.getString("customIconUri") else null
            val configMap = mutableMapOf<String, String>()
            if (json.has("config")) {
                val configObj = json.getJSONObject("config")
                val keys = configObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    configMap[key] = configObj.getString(key)
                }
            }
            return ElementPlacement(
                placementId = placementId,
                containerId = containerId,
                pageId = pageId,
                actionKey = actionKey,
                position = position,
                customTitle = customTitle,
                customIconUri = customIconUri,
                config = configMap
            )
        }
    }
}
