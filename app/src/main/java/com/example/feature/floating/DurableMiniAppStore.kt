package com.example.feature.floating

import android.content.Context
import com.example.core.WindowBounds
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * DurableMiniAppStore: Lightweight, durable file-backed persistence for floating mini-app state.
 *
 * Characteristics:
 * 1. Survives Heavy-process death, Heavy-process restart, and full application restarts.
 * 2. Does NOT rely on in-memory state as the source of truth.
 * 3. Uses atomic temp-file renames to prevent partial write corruptions.
 * 4. Zero external database dependencies (uses standard Android SDK org.json).
 */
class DurableMiniAppStore private constructor(context: Context) {

    private val storageDir: File = File(context.applicationContext.filesDir, DIR_NAME).apply {
        if (!exists()) {
            mkdirs()
        }
    }

    private val lock = Any()

    /**
     * Atomically saves or updates the durable record for a mini-app instance.
     */
    fun saveInstance(record: MiniAppRecord): Boolean {
        synchronized(lock) {
            val file = getFileForInstance(record.instanceId)
            val tempFile = File(storageDir, "${record.instanceId}.tmp")

            return try {
                val json = JSONObject().apply {
                    put("instanceId", record.instanceId)
                    put("appType", record.appType)
                    put("title", record.title)
                    put("bounds_x", record.bounds.x)
                    put("bounds_y", record.bounds.y)
                    put("bounds_w", record.bounds.width)
                    put("bounds_h", record.bounds.height)
                    put("isFolded", record.isFolded)
                    put("isPinned", record.isPinned)
                    put("lastActiveTimestamp", record.lastActiveTimestamp)

                    val dataObj = JSONObject()
                    for ((k, v) in record.data) {
                        dataObj.put(k, v)
                    }
                    put("data", dataObj)
                }

                OutputStreamWriter(FileOutputStream(tempFile), StandardCharsets.UTF_8).use { writer ->
                    writer.write(json.toString(2))
                    writer.flush()
                }

                if (tempFile.exists() && tempFile.renameTo(file)) {
                    true
                } else {
                    tempFile.copyTo(file, overwrite = true)
                    tempFile.delete()
                    true
                }
            } catch (e: Exception) {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                false
            }
        }
    }

    /**
     * Loads the durable record for a given instanceId.
     */
    fun loadInstance(instanceId: String): MiniAppRecord? {
        synchronized(lock) {
            val file = getFileForInstance(instanceId)
            if (!file.exists() || !file.isFile) return null

            return try {
                val jsonStr = file.readText(StandardCharsets.UTF_8)
                val json = JSONObject(jsonStr)

                val id = json.getString("instanceId")
                val appType = json.getString("appType")
                val title = json.optString("title", "")
                val bx = json.optInt("bounds_x", 100)
                val by = json.optInt("bounds_y", 100)
                val bw = json.optInt("bounds_w", 600)
                val bh = json.optInt("bounds_h", 800)
                val isFolded = json.optBoolean("isFolded", false)
                val isPinned = json.optBoolean("isPinned", false)
                val timestamp = json.optLong("lastActiveTimestamp", System.currentTimeMillis())

                val dataMap = mutableMapOf<String, String>()
                val dataObj = json.optJSONObject("data")
                if (dataObj != null) {
                    val keys = dataObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        dataMap[key] = dataObj.optString(key, "")
                    }
                }

                MiniAppRecord(
                    instanceId = id,
                    appType = appType,
                    title = title,
                    bounds = WindowBounds(bx, by, bw, bh),
                    isFolded = isFolded,
                    isPinned = isPinned,
                    lastActiveTimestamp = timestamp,
                    data = dataMap
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Loads all persisted mini-app records ordered by lastActiveTimestamp descending.
     */
    fun getAllInstances(): List<MiniAppRecord> {
        synchronized(lock) {
            val files = storageDir.listFiles { f -> f.isFile && f.extension == "json" } ?: return emptyList()
            val list = mutableListOf<MiniAppRecord>()

            for (file in files) {
                val instanceId = file.nameWithoutExtension
                val record = loadInstance(instanceId)
                if (record != null) {
                    list.add(record)
                }
            }
            list.sortByDescending { it.lastActiveTimestamp }
            return list
        }
    }

    /**
     * Updates only the geometric bounds for a persisted instance.
     */
    fun updateBounds(instanceId: String, bounds: WindowBounds): Boolean {
        synchronized(lock) {
            val record = loadInstance(instanceId) ?: return false
            record.bounds = bounds.copyBounds()
            record.lastActiveTimestamp = System.currentTimeMillis()
            return saveInstance(record)
        }
    }

    /**
     * Updates the fold state for a persisted instance.
     */
    fun updateFoldState(instanceId: String, isFolded: Boolean): Boolean {
        synchronized(lock) {
            val record = loadInstance(instanceId) ?: return false
            record.isFolded = isFolded
            record.lastActiveTimestamp = System.currentTimeMillis()
            return saveInstance(record)
        }
    }

    /**
     * Updates or merges payload key-value state for an instance.
     */
    fun updateData(instanceId: String, newData: Map<String, String>): Boolean {
        synchronized(lock) {
            val record = loadInstance(instanceId) ?: return false
            record.data.putAll(newData)
            record.lastActiveTimestamp = System.currentTimeMillis()
            return saveInstance(record)
        }
    }

    /**
     * Deletes the durable storage for an instance when permanently closed.
     */
    fun deleteInstance(instanceId: String): Boolean {
        synchronized(lock) {
            val file = getFileForInstance(instanceId)
            return if (file.exists()) file.delete() else true
        }
    }

    /**
     * Clears all stored mini-app records.
     */
    fun clearAll(): Boolean {
        synchronized(lock) {
            val files = storageDir.listFiles() ?: return true
            var allDeleted = true
            for (f in files) {
                if (!f.delete()) allDeleted = false
            }
            return allDeleted
        }
    }

    private fun getFileForInstance(instanceId: String): File {
        val safeName = instanceId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(storageDir, "$safeName.json")
    }

    companion object {
        private const val DIR_NAME = "floating_miniapps"

        @Volatile
        private var instance: DurableMiniAppStore? = null

        fun getInstance(context: Context): DurableMiniAppStore {
            return instance ?: synchronized(this) {
                instance ?: DurableMiniAppStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
