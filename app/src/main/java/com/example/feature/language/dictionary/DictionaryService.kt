package com.example.feature.language.dictionary

import android.content.Context
import android.net.Uri
import com.example.core.LogKeeper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Single shared offline Dictionary service used by:
 * - [com.example.feature.language.DictionaryMiniAppInstance] (Main process)
 * - [com.example.feature.selection.MeaningSelectionActivity] (Main process)
 * - [com.example.feature.settings.DictionarySettingsScreen] (`:heavy` process)
 *
 * Cross-process consistency:
 * Catalog metadata is stored atomically in `filesDir/dictionaries/catalog.json` rather than
 * single-process cached `SharedPreferences`, so changes made in `:heavy` Settings (import,
 * enable/disable, priority reorder, delete) are immediately visible in Main without restart.
 */
class DictionaryService private constructor(
    private val appContext: Context,
    private val dictionariesRootDir: File
) {

    private val lock = Any()
    private val catalogFile: File = File(dictionariesRootDir, FILE_CATALOG_JSON)

    init {
        if (!dictionariesRootDir.exists()) {
            dictionariesRootDir.mkdirs()
        }
        StarDictImporter.cleanupOrphanedStagingDirs(dictionariesRootDir)
    }

    /**
     * Returns all installed dictionaries ordered by configured priority (ascending).
     * Always reads the latest committed catalog from disk for cross-process consistency.
     */
    fun getInstalledDictionaries(): List<InstalledDictionary> = synchronized(lock) {
        readCatalogLocked()
            .filter { File(dictionariesRootDir, it.id).isDirectory }
            .sortedWith(compareBy<InstalledDictionary>({ it.priority }, { it.installedAtMillis }))
    }

    fun getEnabledDictionaries(): List<InstalledDictionary> {
        return getInstalledDictionaries().filter { it.enabled }
    }

    fun hasInstalledDictionaries(): Boolean = getInstalledDictionaries().isNotEmpty()

    fun hasEnabledDictionaries(): Boolean = getEnabledDictionaries().isNotEmpty()

    fun getTotalStorageBytes(): Long {
        return getInstalledDictionaries().sumOf { it.storageBytes }
    }

    /**
     * Imports a StarDict ZIP package from an Android SAF [Uri]. Must be called off the main thread.
     */
    fun importFromUri(
        uri: Uri,
        onProgress: ((DictionaryImportProgress) -> Unit)? = null
    ): DictionaryImportResult {
        val stream = try {
            appContext.contentResolver.openInputStream(uri)
        } catch (e: Throwable) {
            LogKeeper.logError(appContext, TAG, "Failed to open dictionary ZIP URI", e)
            return DictionaryImportResult.Failure("Could not open the selected file: ${e.message ?: "permission denied"}")
        } ?: return DictionaryImportResult.Failure("Could not open the selected ZIP file.")

        return stream.use { importFromZipStream(it, onProgress) }
    }

    /**
     * Imports a StarDict ZIP package from a local [File]. Must be called off the main thread.
     */
    fun importFromFile(
        zipFile: File,
        onProgress: ((DictionaryImportProgress) -> Unit)? = null
    ): DictionaryImportResult {
        if (!zipFile.isFile) {
            return DictionaryImportResult.Failure("Selected file does not exist.")
        }
        return FileInputStream(zipFile).use { importFromZipStream(it, onProgress) }
    }

    /**
     * Validates, streams, indexes, and persists all StarDict dictionaries in [zipStream].
     * Never shows success until indexing and catalog persistence succeed.
     */
    fun importFromZipStream(
        zipStream: InputStream,
        onProgress: ((DictionaryImportProgress) -> Unit)? = null
    ): DictionaryImportResult {
        return try {
            val existing = getInstalledDictionaries()
            val existingFingerprints = existing.map { it.sourceFingerprint }.filter { it.isNotEmpty() }.toSet()
            val nextPriority = (existing.maxOfOrNull { it.priority } ?: -1) + 1

            val (staged, skippedDuplicates) = StarDictImporter.importFromZipStream(
                zipStream = zipStream,
                dictionariesRootDir = dictionariesRootDir,
                existingFingerprints = existingFingerprints,
                startingPriority = nextPriority,
                onProgress = onProgress
            )

            if (staged.isEmpty() && skippedDuplicates.isNotEmpty()) {
                val names = skippedDuplicates.joinToString(", ")
                return DictionaryImportResult.Failure("Dictionary already installed (skipped duplicate): $names")
            }

            val newlyInstalled = staged.map { it.dictionary }
            synchronized(lock) {
                val latest = readCatalogLocked().toMutableList()
                latest.addAll(newlyInstalled)
                val normalized = reindexPriorities(latest)
                if (!writeCatalogLocked(normalized)) {
                    // Roll back installed directories if catalog write failed
                    staged.forEach { it.readyDir.deleteRecursively() }
                    return DictionaryImportResult.Failure("Failed to persist dictionary catalog to storage.")
                }
            }

            LogKeeper.log(
                appContext,
                TAG,
                "IMPORT_SUCCESS count=${newlyInstalled.size} skippedDuplicates=${skippedDuplicates.size}"
            )
            DictionaryImportResult.Success(
                installed = newlyInstalled,
                skippedDuplicates = skippedDuplicates
            )
        } catch (e: SecurityException) {
            LogKeeper.logError(appContext, TAG, "ZIP security validation failed", e)
            DictionaryImportResult.Failure(e.message ?: "Rejected unsafe ZIP archive.")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            LogKeeper.log(appContext, TAG, "IMPORT_INTERRUPTED")
            DictionaryImportResult.Failure("Dictionary import was interrupted.")
        } catch (e: Throwable) {
            LogKeeper.logError(appContext, TAG, "Dictionary import failed", e)
            DictionaryImportResult.Failure(e.message ?: "Failed to import dictionary archive.")
        }
    }

    /**
     * Looks up [rawQuery] across all enabled installed dictionaries in priority order.
     * Supports exact, case-insensitive, and `.syn` synonym lookups using disk-backed indexes.
     * Must be called off the main thread.
     */
    fun lookup(rawQuery: String): DictionaryLookupOutcome {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) return DictionaryLookupOutcome.EmptyQuery

        val installed = getInstalledDictionaries()
        if (installed.isEmpty()) {
            return DictionaryLookupOutcome.NoDictionariesInstalled
        }
        val enabled = installed.filter { it.enabled }
        if (enabled.isEmpty()) {
            return DictionaryLookupOutcome.AllDictionariesDisabled
        }

        val allMatches = mutableListOf<DictionaryDefinitionEntry>()
        var lastError: String? = null

        for (dict in enabled) {
            val dictDir = File(dictionariesRootDir, dict.id)
            if (!dictDir.isDirectory) continue
            try {
                val hits = StarDictDiskIndex.lookup(dictDir, dict, trimmed)
                if (hits.isNotEmpty()) {
                    allMatches.addAll(hits)
                }
            } catch (e: Throwable) {
                lastError = "Error reading ${dict.name}: ${e.message ?: "corrupt index"}"
                LogKeeper.logError(appContext, TAG, "Lookup failed for dict=${dict.id}", e)
            }
        }

        return when {
            allMatches.isNotEmpty() -> DictionaryLookupOutcome.Found(
                query = trimmed,
                entries = allMatches
            )
            lastError != null -> DictionaryLookupOutcome.Error(
                query = trimmed,
                message = lastError
            )
            else -> DictionaryLookupOutcome.NotFound(
                query = trimmed,
                searchedDictionaries = enabled.size
            )
        }
    }

    fun setDictionaryEnabled(dictionaryId: String, enabled: Boolean): Boolean = synchronized(lock) {
        val list = readCatalogLocked().toMutableList()
        val idx = list.indexOfFirst { it.id == dictionaryId }
        if (idx < 0) return false
        list[idx] = list[idx].copy(enabled = enabled)
        writeCatalogLocked(list)
    }

    /**
     * Moves a dictionary up (higher priority, smaller index) or down (lower priority).
     */
    fun moveDictionaryPriority(dictionaryId: String, moveUp: Boolean): Boolean = synchronized(lock) {
        val sorted = readCatalogLocked()
            .sortedWith(compareBy<InstalledDictionary>({ it.priority }, { it.installedAtMillis }))
            .toMutableList()
        val idx = sorted.indexOfFirst { it.id == dictionaryId }
        if (idx < 0) return false
        val targetIdx = if (moveUp) idx - 1 else idx + 1
        if (targetIdx !in sorted.indices) return false
        val item = sorted.removeAt(idx)
        sorted.add(targetIdx, item)
        writeCatalogLocked(reindexPriorities(sorted))
    }

    fun setDictionaryOrder(orderedIds: List<String>): Boolean = synchronized(lock) {
        val byId = readCatalogLocked().associateBy { it.id }
        val ordered = mutableListOf<InstalledDictionary>()
        for (id in orderedIds) {
            byId[id]?.let { ordered.add(it) }
        }
        for ((id, dict) in byId) {
            if (id !in orderedIds) ordered.add(dict)
        }
        writeCatalogLocked(reindexPriorities(ordered))
    }

    /**
     * Removes a dictionary from the catalog and deletes all associated files cleanly.
     */
    fun deleteDictionary(dictionaryId: String): Boolean = synchronized(lock) {
        val list = readCatalogLocked()
        val remaining = list.filterNot { it.id == dictionaryId }
        val removed = remaining.size != list.size
        val dictDir = File(dictionariesRootDir, dictionaryId)
        val dirDeleted = if (dictDir.exists()) dictDir.deleteRecursively() else true
        val saved = writeCatalogLocked(reindexPriorities(remaining))
        if (removed) {
            LogKeeper.log(appContext, TAG, "DICTIONARY_DELETED id=$dictionaryId filesDeleted=$dirDeleted")
        }
        removed && saved && dirDeleted
    }

    fun cleanupOrphanedStagingDirs(): Int {
        return StarDictImporter.cleanupOrphanedStagingDirs(dictionariesRootDir)
    }

    private fun reindexPriorities(list: List<InstalledDictionary>): List<InstalledDictionary> {
        return list.mapIndexed { idx, item -> item.copy(priority = idx) }
    }

    private fun readCatalogLocked(): List<InstalledDictionary> {
        if (!catalogFile.isFile) return emptyList()
        return try {
            val raw = catalogFile.readText(StandardCharsets.UTF_8)
            if (raw.isBlank()) return emptyList()
            val arr = JSONArray(raw)
            val result = ArrayList<InstalledDictionary>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                val name = obj.optString("name").trim()
                if (id.isEmpty() || name.isEmpty()) continue
                result.add(
                    InstalledDictionary(
                        id = id,
                        name = name,
                        wordCount = obj.optLong("wordCount", 0L),
                        synonymCount = obj.optLong("synonymCount", 0L),
                        idxOffsetBits = obj.optInt("idxOffsetBits", 32),
                        sameTypeSequence = obj.optString("sameTypeSequence", ""),
                        author = obj.optString("author", ""),
                        description = obj.optString("description", ""),
                        storageBytes = obj.optLong("storageBytes", 0L),
                        enabled = obj.optBoolean("enabled", true),
                        priority = obj.optInt("priority", i),
                        sourceFingerprint = obj.optString("sourceFingerprint", ""),
                        installedAtMillis = obj.optLong("installedAtMillis", 0L)
                    )
                )
            }
            result
        } catch (e: Throwable) {
            LogKeeper.logError(appContext, TAG, "Failed to read dictionary catalog", e)
            emptyList()
        }
    }

    private fun writeCatalogLocked(list: List<InstalledDictionary>): Boolean {
        return try {
            if (!dictionariesRootDir.exists()) dictionariesRootDir.mkdirs()
            val arr = JSONArray()
            for (item in list) {
                arr.put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("name", item.name)
                        put("wordCount", item.wordCount)
                        put("synonymCount", item.synonymCount)
                        put("idxOffsetBits", item.idxOffsetBits)
                        put("sameTypeSequence", item.sameTypeSequence)
                        put("author", item.author)
                        put("description", item.description)
                        put("storageBytes", item.storageBytes)
                        put("enabled", item.enabled)
                        put("priority", item.priority)
                        put("sourceFingerprint", item.sourceFingerprint)
                        put("installedAtMillis", item.installedAtMillis)
                    }
                )
            }
            val tmpFile = File(dictionariesRootDir, "$FILE_CATALOG_JSON.tmp")
            FileOutputStream(tmpFile).use { fos ->
                fos.write(arr.toString().toByteArray(StandardCharsets.UTF_8))
                fos.flush()
                try { fos.fd.sync() } catch (_: Throwable) {}
            }
            if (!tmpFile.renameTo(catalogFile)) {
                catalogFile.writeText(arr.toString(), StandardCharsets.UTF_8)
                tmpFile.delete()
            }
            true
        } catch (e: Throwable) {
            LogKeeper.logError(appContext, TAG, "Failed to write dictionary catalog", e)
            false
        }
    }

    companion object {
        private const val TAG = "DictionaryService"
        private const val DIR_DICTIONARIES = "stardict_dictionaries"
        private const val FILE_CATALOG_JSON = "catalog.json"

        @Volatile
        private var instance: DictionaryService? = null

        fun getInstance(context: Context): DictionaryService {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val app = context.applicationContext ?: context
                    val root = File(app.filesDir ?: File(System.getProperty("java.io.tmpdir"), "vianside_dict"), DIR_DICTIONARIES)
                    DictionaryService(app, root).also { instance = it }
                }
            }
        }

        fun formatStorageBytes(bytes: Long): String {
            return when {
                bytes < 1024L -> "$bytes B"
                bytes < 1024L * 1024L -> String.format("%.1f KB", bytes / 1024.0)
                bytes < 1024L * 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
                else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            }
        }

        internal fun createForTesting(context: Context, rootDir: File): DictionaryService {
            return synchronized(this) {
                DictionaryService(context.applicationContext ?: context, rootDir).also {
                    instance = it
                }
            }
        }

        internal fun resetForTesting() {
            synchronized(this) {
                instance = null
            }
        }
    }
}
