package com.example.feature.language.dictionary

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Safe, streaming, off-main-thread importer for StarDict ZIP archives.
 *
 * Supports:
 * - Single or multiple StarDict dictionaries in one ZIP package.
 * - `.ifo`, `.idx` / `.idx.gz`, `.dict` / `.dict.dz`, and optional `.syn`.
 * - Strict ZIP path traversal rejection and extraction containment.
 * - Streaming `.dict.dz` decompression to `data.dict` without loading files into RAM.
 * - Disk-space checks, interrupted-import staging cleanup, and duplicate rejection.
 */
object StarDictImporter {

    const val STAGING_PREFIX = ".staging_"
    private const val STREAM_BUFFER_SIZE = 8 * 1024
    private const val MAX_IFO_BYTES = 512 * 1024 // 512 KB cap for .ifo header
    private const val MAX_ZIP_ENTRIES = 500
    private const val MAX_UNPACKED_BYTES = 1024L * 1024L * 1024L // 1 GB safety guard
    private const val MIN_FREE_SPACE_BUFFER_BYTES = 16L * 1024L * 1024L // 16 MB safety margin

    internal data class DiscoveredSet(
        val stemKey: String,
        var ifoFile: File? = null,
        var idxFile: File? = null,
        var idxIsGzipped: Boolean = false,
        var dictFile: File? = null,
        var dictIsDz: Boolean = false,
        var synFile: File? = null
    )

    data class StagedDictionaryResult(
        val dictionary: InstalledDictionary,
        val readyDir: File
    )

    /**
     * Validates that a ZIP entry name cannot escape [rootDir].
     * Throws [SecurityException] if any traversal segment or unsafe path is detected.
     */
    fun validateSafeZipEntryPath(entryName: String, rootDir: File): File {
        if (entryName.isBlank() || entryName.contains('\u0000')) {
            throw SecurityException("Unsafe empty or NUL ZIP entry name.")
        }
        val normalized = entryName.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.matches(Regex("^[A-Za-z]:/.*"))) {
            throw SecurityException("ZIP path traversal rejected (absolute path): $entryName")
        }
        val segments = normalized.split('/')
        for (seg in segments) {
            if (seg == ".." || seg.contains("..")) {
                if (seg == "..") {
                    throw SecurityException("ZIP path traversal rejected ('..'): $entryName")
                }
            }
        }
        val target = File(rootDir, normalized)
        val canonicalRoot = rootDir.canonicalFile
        val canonicalTarget = target.canonicalFile
        val rootPath = canonicalRoot.path.let { if (it.endsWith(File.separator)) it else it + File.separator }
        if (canonicalTarget.path != canonicalRoot.path && !canonicalTarget.path.startsWith(rootPath)) {
            throw SecurityException("ZIP path traversal rejected (escapes target directory): $entryName")
        }
        return canonicalTarget
    }

    /**
     * Computes a deterministic duplicate-detection fingerprint from parsed `.ifo` metadata.
     */
    fun computeFingerprint(ifo: StarDictIfo): String {
        val raw = "${ifo.bookName.trim().lowercase(Locale.ROOT)}|${ifo.wordCount}|${ifo.synWordCount}|${ifo.idxFileSize}|${ifo.idxOffsetBits}|${ifo.sameTypeSequence}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    /**
     * Imports all valid StarDict dictionaries from [zipStream] into [dictionariesRootDir],
     * staging everything under `.staging_<uuid>` until validated and indexed.
     */
    fun importFromZipStream(
        zipStream: InputStream,
        dictionariesRootDir: File,
        existingFingerprints: Set<String>,
        startingPriority: Int,
        minFreeSpaceBytes: Long = MIN_FREE_SPACE_BUFFER_BYTES,
        onProgress: ((DictionaryImportProgress) -> Unit)? = null
    ): Pair<List<StagedDictionaryResult>, List<String>> {
        if (!dictionariesRootDir.exists()) {
            dictionariesRootDir.mkdirs()
        }

        val stagingDir = File(dictionariesRootDir, "$STAGING_PREFIX${UUID.randomUUID()}")
        val extractedRawDir = File(stagingDir, "raw")
        val readyDirs = mutableListOf<File>()

        try {
            if (!extractedRawDir.mkdirs()) {
                throw IOException("Could not create temporary staging directory for dictionary import.")
            }

            onProgress?.invoke(DictionaryImportProgress("Validating archive", "Inspecting StarDict ZIP contents...", 0.08f))

            val extractedFiles = extractRelevantZipEntries(
                zipStream = zipStream,
                extractRoot = extractedRawDir,
                minFreeSpaceBytes = minFreeSpaceBytes
            )

            if (extractedFiles.isEmpty()) {
                throw IllegalArgumentException("ZIP archive does not contain any StarDict dictionary files (.ifo, .idx, .dict).")
            }

            val sets = groupIntoDictionarySets(extractedRawDir, extractedFiles)
            if (sets.isEmpty()) {
                throw IllegalArgumentException("No StarDict .ifo metadata file was found in the ZIP archive.")
            }

            // Validate completeness of every discovered set before installing anything
            val validSets = mutableListOf<Pair<DiscoveredSet, StarDictIfo>>()
            val skippedDuplicates = mutableListOf<String>()

            for (set in sets) {
                val ifoFile = set.ifoFile
                    ?: throw IllegalArgumentException("Incomplete StarDict package: missing .ifo file for '${set.stemKey}'.")
                if (set.idxFile == null) {
                    throw IllegalArgumentException("Incomplete StarDict package '${ifoFile.nameWithoutExtension}': missing .idx or .idx.gz file.")
                }
                if (set.dictFile == null) {
                    throw IllegalArgumentException("Incomplete StarDict package '${ifoFile.nameWithoutExtension}': missing .dict or .dict.dz file.")
                }

                val ifoText = ifoFile.readText(StandardCharsets.UTF_8)
                val ifo = StarDictFormatParser.parseIfo(ifoText)
                val fp = computeFingerprint(ifo)
                if (fp in existingFingerprints || validSets.any { computeFingerprint(it.second) == fp }) {
                    skippedDuplicates.add(ifo.bookName)
                    continue
                }
                validSets.add(set to ifo)
            }

            if (validSets.isEmpty() && skippedDuplicates.isNotEmpty()) {
                return emptyList<StagedDictionaryResult>() to skippedDuplicates
            }

            val results = mutableListOf<StagedDictionaryResult>()
            var nextPriority = startingPriority

            validSets.forEachIndexed { index, (set, ifo) ->
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Dictionary import was interrupted.")
                }
                val baseFraction = 0.20f + (0.75f * index.toFloat() / validSets.size.coerceAtLeast(1))
                val dictId = "dict_${UUID.randomUUID().toString().replace("-", "").take(12)}"
                val buildDir = File(stagingDir, "built_$dictId")
                if (!buildDir.mkdirs()) {
                    throw IOException("Could not create build directory for '${ifo.bookName}'.")
                }

                // 1. Prepare uncompressed data.dict via streaming (never loads whole dict into RAM)
                val dictOutFile = File(buildDir, StarDictDiskIndex.FILE_DATA_DICT)
                val sourceDict = set.dictFile!!
                if (set.dictIsDz) {
                    onProgress?.invoke(
                        DictionaryImportProgress(
                            stage = "Decompressing",
                            detail = "Streaming ${sourceDict.name} for ${ifo.bookName}...",
                            fraction = baseFraction
                        )
                    )
                    streamDecompressGzip(sourceDict, dictOutFile, minFreeSpaceBytes)
                } else {
                    onProgress?.invoke(
                        DictionaryImportProgress(
                            stage = "Preparing data",
                            detail = "Copying ${sourceDict.name} for ${ifo.bookName}...",
                            fraction = baseFraction
                        )
                    )
                    streamCopyFile(sourceDict, dictOutFile, minFreeSpaceBytes)
                }

                // 2. Build disk-backed sorted index from .idx/.idx.gz + optional .syn
                val idxSource = set.idxFile!!
                val synSource = set.synFile
                val summary = openPossiblyGzipped(idxSource, set.idxIsGzipped).use { idxIn ->
                    val synIn = synSource?.let { BufferedInputStream(FileInputStream(it), 16 * 1024) }
                    try {
                        StarDictDiskIndex.buildDiskIndex(
                            ifo = ifo,
                            idxStream = idxIn,
                            synStream = synIn,
                            dictFileLength = dictOutFile.length(),
                            targetDir = buildDir,
                            onProgress = { msg ->
                                onProgress?.invoke(
                                    DictionaryImportProgress(
                                        stage = "Indexing",
                                        detail = msg,
                                        fraction = (baseFraction + 0.35f / validSets.size.coerceAtLeast(1)).coerceAtMost(0.95f)
                                    )
                                )
                            }
                        )
                    } finally {
                        try { synIn?.close() } catch (_: Throwable) {}
                    }
                }

                // 3. Move completed dictionary into its final directory under dictionariesRootDir
                val finalDir = File(dictionariesRootDir, dictId)
                if (finalDir.exists()) {
                    finalDir.deleteRecursively()
                }
                if (!buildDir.renameTo(finalDir)) {
                    // Fallback copy if rename across mount/dir fails
                    copyDirectory(buildDir, finalDir)
                    buildDir.deleteRecursively()
                }
                readyDirs.add(finalDir)

                val totalBytes = calculateDirectorySize(finalDir)
                val installed = InstalledDictionary(
                    id = dictId,
                    name = ifo.bookName,
                    wordCount = summary.indexedWords,
                    synonymCount = summary.indexedSynonyms,
                    idxOffsetBits = ifo.idxOffsetBits,
                    sameTypeSequence = ifo.sameTypeSequence,
                    author = ifo.author,
                    description = ifo.description,
                    storageBytes = totalBytes,
                    enabled = true,
                    priority = nextPriority++,
                    sourceFingerprint = computeFingerprint(ifo),
                    installedAtMillis = System.currentTimeMillis()
                )
                results.add(StagedDictionaryResult(installed, finalDir))
            }

            onProgress?.invoke(DictionaryImportProgress("Complete", "Dictionary import complete.", 1.0f))
            return results to skippedDuplicates
        } catch (t: Throwable) {
            // Roll back any partially moved dictionaries from this batch on failure or interruption
            readyDirs.forEach { it.deleteRecursively() }
            throw t
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    /**
     * Cleans up any leftover `.staging_*` directories from interrupted or crashed imports.
     */
    fun cleanupOrphanedStagingDirs(dictionariesRootDir: File): Int {
        if (!dictionariesRootDir.isDirectory) return 0
        var removed = 0
        dictionariesRootDir.listFiles()?.forEach { child ->
            if (child.name.startsWith(STAGING_PREFIX)) {
                if (child.deleteRecursively()) removed++
            }
        }
        return removed
    }

    fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var total = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile) total += f.length()
        }
        return total
    }

    private fun extractRelevantZipEntries(
        zipStream: InputStream,
        extractRoot: File,
        minFreeSpaceBytes: Long
    ): List<File> {
        val extracted = mutableListOf<File>()
        var entryCount = 0
        var totalUnpackedBytes = 0L
        var sawAnyZipEntry = false

        ZipInputStream(BufferedInputStream(zipStream, 32 * 1024)).use { zis ->
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                sawAnyZipEntry = true
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Dictionary import was interrupted.")
                }
                entryCount++
                if (entryCount > MAX_ZIP_ENTRIES) {
                    throw IllegalArgumentException("ZIP archive contains too many entries (limit $MAX_ZIP_ENTRIES).")
                }

                // Always validate EVERY entry path against traversal, even skipped files/directories
                val targetFile = validateSafeZipEntryPath(entry.name, extractRoot)
                val lowerName = entry.name.lowercase(Locale.ROOT)

                val isMacJunk = lowerName.contains("__macosx/") || targetFile.name.startsWith("._")
                val isStarDictFile = !entry.isDirectory && !isMacJunk && (
                    lowerName.endsWith(".ifo") ||
                        lowerName.endsWith(".idx") ||
                        lowerName.endsWith(".idx.gz") ||
                        lowerName.endsWith(".dict") ||
                        lowerName.endsWith(".dict.dz") ||
                        lowerName.endsWith(".syn")
                    )

                if (isStarDictFile) {
                    checkSufficientSpace(extractRoot, minFreeSpaceBytes)
                    targetFile.parentFile?.mkdirs()
                    var entryBytes = 0L
                    BufferedOutputStream(FileOutputStream(targetFile), STREAM_BUFFER_SIZE).use { out ->
                        while (true) {
                            val read = zis.read(buffer)
                            if (read <= 0) break
                            entryBytes += read
                            totalUnpackedBytes += read
                            if (lowerName.endsWith(".ifo") && entryBytes > MAX_IFO_BYTES) {
                                throw IllegalArgumentException("StarDict .ifo file '${targetFile.name}' is unexpectedly large.")
                            }
                            if (totalUnpackedBytes > MAX_UNPACKED_BYTES) {
                                throw IllegalArgumentException("ZIP archive exceeds maximum supported unpacked size.")
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                    extracted.add(targetFile)
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        if (!sawAnyZipEntry) {
            throw IllegalArgumentException("Selected file is not a valid ZIP archive or is empty.")
        }
        return extracted
    }

    private fun groupIntoDictionarySets(rootDir: File, files: List<File>): List<DiscoveredSet> {
        val byStem = LinkedHashMap<String, DiscoveredSet>()
        val rootPath = rootDir.canonicalPath

        for (file in files) {
            val relPath = file.canonicalPath.removePrefix(rootPath).trimStart(File.separatorChar)
            val parent = File(relPath).parent ?: ""
            val name = file.name
            val lower = name.lowercase(Locale.ROOT)

            val (stem, ext) = when {
                lower.endsWith(".idx.gz") -> name.dropLast(7) to ".idx.gz"
                lower.endsWith(".dict.dz") -> name.dropLast(8) to ".dict.dz"
                lower.endsWith(".ifo") -> name.dropLast(4) to ".ifo"
                lower.endsWith(".idx") -> name.dropLast(4) to ".idx"
                lower.endsWith(".dict") -> name.dropLast(5) to ".dict"
                lower.endsWith(".syn") -> name.dropLast(4) to ".syn"
                else -> continue
            }

            val stemKey = if (parent.isEmpty()) stem.lowercase(Locale.ROOT) else "${parent.lowercase(Locale.ROOT)}/${stem.lowercase(Locale.ROOT)}"
            val set = byStem.getOrPut(stemKey) { DiscoveredSet(stemKey) }
            when (ext) {
                ".ifo" -> set.ifoFile = file
                ".idx" -> {
                    set.idxFile = file
                    set.idxIsGzipped = false
                }
                ".idx.gz" -> {
                    if (set.idxFile == null) {
                        set.idxFile = file
                        set.idxIsGzipped = true
                    }
                }
                ".dict" -> {
                    set.dictFile = file
                    set.dictIsDz = false
                }
                ".dict.dz" -> {
                    if (set.dictFile == null) {
                        set.dictFile = file
                        set.dictIsDz = true
                    }
                }
                ".syn" -> set.synFile = file
            }
        }

        return byStem.values.toList()
    }

    private fun openPossiblyGzipped(file: File, isGzipped: Boolean): InputStream {
        val base = BufferedInputStream(FileInputStream(file), 32 * 1024)
        return if (isGzipped) {
            try {
                GZIPInputStream(base, 32 * 1024)
            } catch (t: Throwable) {
                base.close()
                throw IllegalArgumentException("Failed to decompress ${file.name}: ${t.message}", t)
            }
        } else {
            base
        }
    }

    private fun streamDecompressGzip(source: File, dest: File, minFreeSpaceBytes: Long) {
        checkSufficientSpace(dest.parentFile ?: dest, minFreeSpaceBytes)
        var totalWritten = 0L
        try {
            GZIPInputStream(BufferedInputStream(FileInputStream(source), 32 * 1024), 32 * 1024).use { gis ->
                BufferedOutputStream(FileOutputStream(dest), 32 * 1024).use { out ->
                    val buffer = ByteArray(STREAM_BUFFER_SIZE)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedException("Dictionary decompression interrupted.")
                        }
                        val read = gis.read(buffer)
                        if (read <= 0) break
                        totalWritten += read
                        if (totalWritten > MAX_UNPACKED_BYTES) {
                            throw IllegalArgumentException("Decompressed .dict.dz exceeds maximum supported size.")
                        }
                        if (totalWritten % (4 * 1024 * 1024) < read) {
                            checkSufficientSpace(dest.parentFile ?: dest, minFreeSpaceBytes)
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: IOException) {
            throw IllegalArgumentException("Invalid or corrupted .dict.dz archive (${source.name}): ${e.message}", e)
        }
    }

    private fun streamCopyFile(source: File, dest: File, minFreeSpaceBytes: Long) {
        checkSufficientSpace(dest.parentFile ?: dest, minFreeSpaceBytes + source.length())
        BufferedInputStream(FileInputStream(source), 32 * 1024).use { input ->
            BufferedOutputStream(FileOutputStream(dest), 32 * 1024).use { out ->
                val buffer = ByteArray(STREAM_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
            }
        }
    }

    private fun copyDirectory(src: File, dst: File) {
        if (!dst.exists()) dst.mkdirs()
        src.listFiles()?.forEach { child ->
            val target = File(dst, child.name)
            if (child.isDirectory) {
                copyDirectory(child, target)
            } else {
                streamCopyFile(child, target, 0L)
            }
        }
    }

    private fun checkSufficientSpace(dir: File, requiredBytes: Long) {
        if (requiredBytes <= 0L) return
        val usable = try {
            dir.usableSpace
        } catch (_: Throwable) {
            return
        }
        // Only enforce when filesystem reports a positive usable space value
        if (usable > 0L && usable < requiredBytes) {
            throw IOException("Insufficient storage space to import dictionary.")
        }
    }
}
