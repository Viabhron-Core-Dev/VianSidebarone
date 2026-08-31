package com.example.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class HandleConfig(val id: String, var name: String, var enabled: Boolean)

object HandleManager {
    fun getHandles(prefs: SharedPreferences): List<HandleConfig> {
        val jsonStr = prefs.getString("handles_list", null)
        val list = mutableListOf<HandleConfig>()
        if (jsonStr == null) {
            val defaultHandle = HandleConfig(id = "sidebar", name = "Handle 1 | Right (Bottom)", enabled = true)
            list.add(defaultHandle)
            prefs.edit().putString("handle_sidebar_swipe_left", "open_page:default_hybrid").commit()
            prefs.edit().remove("handle_sidebar_tap").commit()
            prefs.edit().putString("handle_sidebar_color", "#242962ff").commit() // 14% opacity deep blue/purple
            saveHandles(prefs, list)
            return list
        }
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id")
                list.add(HandleConfig(
                    id = id,
                    name = obj.optString("name", "Handle"),
                    enabled = obj.optBoolean("enabled", true)
                ))
            }
        } catch (e: Exception) {}
        return list
    }

    fun saveHandles(prefs: SharedPreferences, handles: List<HandleConfig>, context: Context? = null) {
        val arr = JSONArray()
        for (h in handles) {
            val obj = JSONObject()
            obj.put("id", h.id)
            obj.put("name", h.name)
            obj.put("enabled", h.enabled)
            arr.put(obj)
        }
        val json = arr.toString()
        prefs.edit().putString("handles_list", json).commit()
        if (context != null) {
            OverlaySyncManager.syncString(context, "handles_list", json)
        }
    }
}

