package com.example.feature.floating

import android.content.Context
import com.example.core.WindowBounds
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * DurableMiniAppStore: Authoritative, durable file-backed persistence for floating mini-app state.
 *
 * Architecture Mandates:
 * 1. Single Authoritative Owner: Resides and operates EXCLUSIVELY in the Main process (com.example).
 *    Heavy process (:heavy) never writes directly to this store; it communicates via Binder IPC.
 * 2. Independent Geometry: Persists [buttonPosition] (launcher bubble) and [windowBounds] (floating window)
 *    strictly separately so neither can overwrite or corrupt the other.
 * 3. Atomic File Writes: Uses temp-file write + rename to guarantee crash-resilient persistence.
 * 4. Lightweight & Reflection-Free: Uses standard Android org.json with zero heavyweight ORM or DB overhead.
 */
class DurableMiniAppStore private constructor(context: Context) {

    private val storageDir: File = File(context.applicationContext.filesDir, DIR_NAME).apply {
        if (!exists()) {
            mkdirs()
        }
    }

    private val lock = Any()

    /**
     * Atomically saves or updates the durable recovery snapshot for a mini-app instance.
     */
    fun saveSnapshot(snapshot: MiniAppRecoverySnapshot): Boolean {
        synchronized(lock) {
            if (!storageDir.exists()) {
                storageDir.mkdirs()
            }
            val file = getFileForInstance(snapshot.instanceId)
            val tempFile = File(storageDir, "${snapshot.instanceId}.tmp")

            return try {
                val json = JSONObject().apply {
                    put("instanceId", snapshot.instanceId)
                    put("appType", snapshot.appType)
                    put("title", snapshot.title)
                    put("state", snapshot.state.name)

                    // Independent window bounds
                    put("bounds_x", snapshot.windowBounds.x)
                    put("bounds_y", snapshot.windowBounds.y)
                    put("bounds_w", snapshot.windowBounds.width)
                    put("bounds_h", snapshot.windowBounds.height)

                    // Independent maximize restore bounds
                    val maxRestore = snapshot.maximizeRestoreBounds
                    if (maxRestore != null) {
                        val maxObj = JSONObject().apply {
                            put("x", maxRestore.x)
                            put("y", maxRestore.y)
                            put("w", maxRestore.width)
                            put("h", maxRestore.height)
                        }
                        put("maximizeRestoreBounds", maxObj)
                    }

                    // Independent persistent launcher button position
                    val btnObj = JSONObject().apply {
                        put("x", snapshot.buttonPosition.first)
                        put("y", snapshot.buttonPosition.second)
                    }
                    put("buttonPosition", btnObj)

                    put("version", snapshot.version)
                    put("timestamp", snapshot.timestamp)
                    put("isPinned", snapshot.isPinned)

                    // Payload key-value map
                    val payloadObj = JSONObject()
                    for ((k, v) in snapshot.payload) {
                        payloadObj.put(k, v)
                    }
                    put("payload", payloadObj)
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
     * Loads the durable recovery snapshot for a given instanceId.
     */
    fun loadSnapshot(instanceId: String): MiniAppRecoverySnapshot? {
        synchronized(lock) {
            val file = getFileForInstance(instanceId)
            if (!file.exists() || !file.isFile) return null

            return try {
                val jsonStr = file.readText(StandardCharsets.UTF_8)
                val json = JSONObject(jsonStr)

                val id = json.getString("instanceId")
                val appType = json.getString("appType")
                val title = json.optString("title", "")

                val stateStr = json.optString("state", MiniAppState.ACTIVE.name)
                val state = try {
                    MiniAppState.valueOf(stateStr)
                } catch (e: Exception) {
                    if (json.optBoolean("isFolded", false)) MiniAppState.SLEEPING else MiniAppState.ACTIVE
                }

                val bx = json.optInt("bounds_x", 100)
                val by = json.optInt("bounds_y", 100)
                val bw = json.optInt("bounds_w", 600)
                val bh = json.optInt("bounds_h", 800)
                val windowBounds = WindowBounds(bx, by, bw, bh)

                var maxRestoreBounds: WindowBounds? = null
                val maxObj = json.optJSONObject("maximizeRestoreBounds")
                if (maxObj != null) {
                    maxRestoreBounds = WindowBounds(
                        maxObj.optInt("x", 100),
                        maxObj.optInt("y", 100),
                        maxObj.optInt("w", 600),
                        maxObj.optInt("h", 800)
                    )
                }

                var buttonPos = Pair(40, 200)
                val btnObj = json.optJSONObject("buttonPosition")
                if (btnObj != null) {
                    buttonPos = Pair(btnObj.optInt("x", 40), btnObj.optInt("y", 200))
                }

                val version = json.optInt("version", MiniAppRecoverySnapshot.CURRENT_VERSION)
                val timestamp = json.optLong("timestamp", json.optLong("lastActiveTimestamp", System.currentTimeMillis()))
                val isPinned = json.optBoolean("isPinned", false)

                val payloadMap = mutableMapOf<String, String>()
                val payloadObj = json.optJSONObject("payload") ?: json.optJSONObject("data")
                if (payloadObj != null) {
                    val keys = payloadObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        payloadMap[key] = payloadObj.optString(key, "")
                    }
                }

                MiniAppRecoverySnapshot(
                    instanceId = id,
                    appType = appType,
                    title = title,
                    state = state,
                    windowBounds = windowBounds,
                    maximizeRestoreBounds = maxRestoreBounds,
                    buttonPosition = buttonPos,
                    payload = payloadMap,
                    version = version,
                    timestamp = timestamp
                ).also {
                    it.isPinned = isPinned
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Loads all persisted recovery snapshots ordered by timestamp descending.
     */
    fun getAllSnapshots(): List<MiniAppRecoverySnapshot> {
        synchronized(lock) {
            val files = storageDir.listFiles { f -> f.isFile && f.extension == "json" } ?: return emptyList()
            val list = mutableListOf<MiniAppRecoverySnapshot>()

            for (file in files) {
                val instanceId = file.nameWithoutExtension
                val snapshot = loadSnapshot(instanceId)
                if (snapshot != null) {
                    list.add(snapshot)
                }
            }
            list.sortByDescending { it.timestamp }
            return list
        }
    }

    /**
     * Updates ONLY the geometric window bounds for a persisted instance.
     * Guaranteed never to modify or overwrite [MiniAppRecoverySnapshot.buttonPosition].
     */
    fun updateWindowBounds(instanceId: String, bounds: WindowBounds): Boolean {
        synchronized(lock) {
            val snapshot = loadSnapshot(instanceId) ?: return false
            snapshot.windowBounds = bounds.copyBounds()
            snapshot.timestamp = System.currentTimeMillis()
            return saveSnapshot(snapshot)
        }
    }

    /**
     * Updates ONLY the launcher button coordinates for a persisted instance.
     * Guaranteed never to modify or overwrite [MiniAppRecoverySnapshot.windowBounds].
     */
    fun updateButtonPosition(instanceId: String, x: Int, y: Int): Boolean {
        synchronized(lock) {
            val snapshot = loadSnapshot(instanceId) ?: return false
            snapshot.buttonPosition = Pair(x, y)
            snapshot.timestamp = System.currentTimeMillis()
            return saveSnapshot(snapshot)
        }
    }

    /**
     * Updates the saved maximize-restore bounds for an instance.
     */
    fun updateMaximizeRestoreBounds(instanceId: String, bounds: WindowBounds?): Boolean {
        synchronized(lock) {
            val snapshot = loadSnapshot(instanceId) ?: return false
            snapshot.maximizeRestoreBounds = bounds?.copyBounds()
            snapshot.timestamp = System.currentTimeMillis()
            return saveSnapshot(snapshot)
        }
    }

    /**
     * Updates the lifecycle state for a persisted instance.
     */
    fun updateState(instanceId: String, state: MiniAppState): Boolean {
        synchronized(lock) {
            val snapshot = loadSnapshot(instanceId) ?: return false
            snapshot.state = state
            snapshot.timestamp = System.currentTimeMillis()
            return saveSnapshot(snapshot)
        }
    }

    /**
     * Updates or merges payload key-value state for an instance.
     */
    fun updatePayload(instanceId: String, newPayload: Map<String, String>): Boolean {
        synchronized(lock) {
            val snapshot = loadSnapshot(instanceId) ?: return false
            snapshot.payload.putAll(newPayload)
            snapshot.timestamp = System.currentTimeMillis()
            return saveSnapshot(snapshot)
        }
    }

    /**
     * Deletes the durable storage for an instance when permanently closed.
     */
    fun deleteSnapshot(instanceId: String): Boolean {
        synchronized(lock) {
            val file = getFileForInstance(instanceId)
            return if (file.exists()) file.delete() else true
        }
    }

    /**
     * Clears all stored mini-app snapshots.
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

    // Legacy method aliases for backwards compatibility
    fun saveInstance(record: MiniAppRecord): Boolean = saveSnapshot(record)
    fun loadInstance(instanceId: String): MiniAppRecord? = loadSnapshot(instanceId)
    fun getAllInstances(): List<MiniAppRecord> = getAllSnapshots()
    fun updateBounds(instanceId: String, bounds: WindowBounds): Boolean = updateWindowBounds(instanceId, bounds)
    fun updateFoldState(instanceId: String, isFolded: Boolean): Boolean =
        updateState(instanceId, if (isFolded) MiniAppState.SLEEPING else MiniAppState.ACTIVE)
    fun updateData(instanceId: String, newData: Map<String, String>): Boolean = updatePayload(instanceId, newData)
    fun deleteInstance(instanceId: String): Boolean = deleteSnapshot(instanceId)

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

        fun resetForTesting() {
            synchronized(this) {
                instance = null
            }
        }
    }
}
