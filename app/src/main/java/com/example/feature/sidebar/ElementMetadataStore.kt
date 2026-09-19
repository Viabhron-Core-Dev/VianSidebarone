package com.example.feature.sidebar

import android.content.Context
import android.content.pm.PackageManager
import com.example.core.IconCacheManager
import com.example.core.LogKeeper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ElementMetadata(
    val id: String,          // Unique element ID (e.g. "app:com.android.chrome" or "link:uuid")
    val type: String,        // "app" or "link"
    val target: String,      // Package name for apps, or URL for links
    val label: String,       // Display label
    val iconPath: String     // Absolute path to compact WebP icon on disk
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("type", type)
            put("target", target)
            put("label", label)
            put("iconPath", iconPath)
        }
    }

    companion object {
        fun fromJson(jsonStr: String): ElementMetadata? {
            return try {
                val obj = JSONObject(jsonStr)
                ElementMetadata(
                    id = obj.getString("id"),
                    type = obj.getString("type"),
                    target = obj.getString("target"),
                    label = obj.getString("label"),
                    iconPath = obj.optString("iconPath", "")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

object ElementMetadataStore {
    private const val TAG = "ElementMetadataStore"
    private const val PREFS_NAME = "FloatingReaderPrefs"
    private const val PREF_KEY_PREFIX = "elem_meta_"
    private const val MIGRATION_KEY = "element_metadata_migrated_v1"

    fun get(context: Context, id: String): ElementMetadata? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // 1. Exact ID lookup
        val exactJson = prefs.getString(PREF_KEY_PREFIX + id, null)
        if (exactJson != null) {
            val meta = ElementMetadata.fromJson(exactJson)
            if (meta != null) return meta
        }

        // 2. Canonical app prefix lookup: app:package
        if (id.startsWith("app:")) {
            val pkg = id.substringAfter("app:").substringBefore(":")
            val appJson = prefs.getString(PREF_KEY_PREFIX + "app:$pkg", null)
            if (appJson != null) {
                val meta = ElementMetadata.fromJson(appJson)
                if (meta != null) return meta
            }
        }

        // 3. Canonical link prefix lookup: link:uuid
        if (id.startsWith("link:")) {
            val parts = id.split(":", limit = 3)
            val uuid = if (parts.size >= 2) parts[1] else ""
            if (uuid.isNotEmpty()) {
                val linkJson = prefs.getString(PREF_KEY_PREFIX + "link:$uuid", null)
                if (linkJson != null) {
                    val meta = ElementMetadata.fromJson(linkJson)
                    if (meta != null) return meta
                }
            }
            // Parse inline JSON if legacy link payload
            if (parts.size >= 3) {
                try {
                    val obj = JSONObject(parts[2])
                    val url = obj.optString("url", "https://")
                    val label = obj.optString("label", "Link")
                    val iconPath = obj.optString("iconPath", "")
                    val meta = ElementMetadata(
                        id = if (uuid.isNotEmpty()) "link:$uuid" else id,
                        type = "link",
                        target = url,
                        label = label,
                        iconPath = iconPath
                    )
                    save(context, meta)
                    return meta
                } catch (e: Exception) {
                    LogKeeper.writeLog(TAG, "Error parsing inline link data: ${e.message}")
                }
            }
        }

        return null
    }

    fun save(context: Context, metadata: ElementMetadata) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = metadata.toJson().toString()
        val editor = prefs.edit()
        editor.putString(PREF_KEY_PREFIX + metadata.id, jsonStr)

        // Also save canonical lookup key for apps and links
        if (metadata.type == "app") {
            editor.putString(PREF_KEY_PREFIX + "app:${metadata.target}", jsonStr)
        } else if (metadata.type == "link") {
            val uuid = metadata.id.substringAfter("link:").substringBefore(":")
            if (uuid.isNotEmpty()) {
                editor.putString(PREF_KEY_PREFIX + "link:$uuid", jsonStr)
            }
        }
        editor.apply()
    }

    fun saveAppElement(context: Context, packageName: String, label: String, elementId: String = "app:$packageName"): ElementMetadata {
        val iconPath = IconCacheManager.captureAndSavePackageIcon(context, packageName) ?: ""
        val metadata = ElementMetadata(
            id = elementId,
            type = "app",
            target = packageName,
            label = label,
            iconPath = iconPath
        )
        save(context, metadata)
        return metadata
    }

    fun saveLinkElement(context: Context, uuid: String, url: String, label: String, elementId: String = "link:$uuid"): ElementMetadata {
        val iconPath = IconCacheManager.captureAndSaveLinkIcon(context, uuid) ?: ""
        val metadata = ElementMetadata(
            id = elementId,
            type = "link",
            target = url,
            label = label,
            iconPath = iconPath
        )
        save(context, metadata)
        return metadata
    }

    fun remove(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.remove(PREF_KEY_PREFIX + id)
        if (id.startsWith("app:")) {
            val pkg = id.substringAfter("app:").substringBefore(":")
            editor.remove(PREF_KEY_PREFIX + "app:$pkg")
        } else if (id.startsWith("link:")) {
            val uuid = id.substringAfter("link:").substringBefore(":")
            if (uuid.isNotEmpty()) {
                editor.remove(PREF_KEY_PREFIX + "link:$uuid")
            }
        }
        editor.apply()
    }

    fun isPackageLaunchable(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getLaunchIntentForPackage(packageName) != null
        } catch (e: Exception) {
            false
        }
    }

    fun migrateLegacyElements(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(MIGRATION_KEY, false)) {
            return
        }

        try {
            val allPrefs = prefs.all
            val allElementIds = mutableSetOf<String>()

            fun extractIdsFromJsonArray(jsonStr: String?) {
                if (jsonStr.isNullOrEmpty()) return
                try {
                    val arr = JSONArray(jsonStr)
                    for (i in 0 until arr.length()) {
                        val item = arr.optString(i)
                        if (!item.isNullOrEmpty()) {
                            allElementIds.add(item)
                        }
                    }
                } catch (e: Exception) {}
            }

            fun extractIdsFromHybridJson(jsonStr: String?) {
                if (jsonStr.isNullOrEmpty()) return
                try {
                    val arr = JSONArray(jsonStr)
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i)
                        if (obj != null) {
                            val id = obj.optString("id")
                            if (!id.isNullOrEmpty()) allElementIds.add(id)
                        }
                    }
                } catch (e: Exception) {}
            }

            for ((key, value) in allPrefs) {
                if (value is String) {
                    if (key.startsWith("sidebar_apps") || key.endsWith("_apps")) {
                        extractIdsFromJsonArray(value)
                    } else if (key.startsWith("hybrid_grid_") && !key.startsWith("hybrid_grid_cols_") && !key.startsWith("hybrid_grid_modified_")) {
                        extractIdsFromHybridJson(value)
                    }
                }
            }

            // Also inspect nested folder items
            val folderItems = mutableSetOf<String>()
            for (id in allElementIds) {
                if (id.startsWith("folder:")) {
                    try {
                        val parts = id.split(":", limit = 3)
                        if (parts.size >= 3) {
                            val obj = JSONObject(parts[2])
                            val itemsArr = obj.optJSONArray("items")
                            if (itemsArr != null) {
                                for (j in 0 until itemsArr.length()) {
                                    folderItems.add(itemsArr.getString(j))
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
            allElementIds.addAll(folderItems)

            val pm = context.packageManager
            for (rawId in allElementIds) {
                val id = if (!rawId.contains(":")) "app:$rawId" else rawId
                if (get(context, id) != null) continue

                if (id.startsWith("app:")) {
                    val pkg = id.substringAfter("app:").substringBefore(":")
                    var label = pkg
                    try {
                        val info = pm.getApplicationInfo(pkg, 0)
                        label = pm.getApplicationLabel(info).toString()
                    } catch (e: Exception) {}

                    val iconPath = IconCacheManager.captureAndSavePackageIcon(context, pkg) ?: ""
                    val meta = ElementMetadata(
                        id = id,
                        type = "app",
                        target = pkg,
                        label = label,
                        iconPath = iconPath
                    )
                    save(context, meta)
                } else if (id.startsWith("link:")) {
                    try {
                        val parts = id.split(":", limit = 3)
                        val uuid = if (parts.size >= 2) parts[1] else ""
                        val linkDataStr = if (parts.size >= 3) parts[2] else "{}"
                        val obj = JSONObject(linkDataStr)
                        val url = obj.optString("url", "https://")
                        val label = obj.optString("label", "Link")
                        val iconPath = IconCacheManager.captureAndSaveLinkIcon(context, uuid) ?: ""
                        val meta = ElementMetadata(
                            id = if (uuid.isNotEmpty()) "link:$uuid" else id,
                            type = "link",
                            target = url,
                            label = label,
                            iconPath = iconPath
                        )
                        save(context, meta)
                    } catch (e: Exception) {}
                }
            }

            prefs.edit().putBoolean(MIGRATION_KEY, true).apply()
            LogKeeper.writeLog(TAG, "Legacy element metadata migration completed for ${allElementIds.size} elements.")
        } catch (e: Exception) {
            LogKeeper.writeLog(TAG, "Legacy element metadata migration error: ${e.message}")
        }
    }
}
