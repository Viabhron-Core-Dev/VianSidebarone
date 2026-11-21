package com.example.feature.language.dictionary

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.PriorityQueue

/**
 * Disk-backed StarDict index builder and O(log N) lookup engine.
 *
 * Performance & Memory guarantees:
 * - Builds sorted disk files (`sorted_keys.bin` + `key_offsets.bin`) using bounded-memory external
 *   merge-sort runs so huge dictionaries never load the entire index or `.dict` file into RAM.
 * - Lookups perform binary search directly on `key_offsets.bin` and `sorted_keys.bin` via
 *   [RandomAccessFile], then seek and read ONLY the matched byte span from `data.dict`.
 * - Supports 32-bit and 64-bit `.idx` offsets, optional `.syn` synonym tables, exact matching,
 *   and case-insensitive matching.
 */
object StarDictDiskIndex {

    const val FILE_DATA_DICT = "data.dict"
    const val FILE_SORTED_KEYS = "sorted_keys.bin"
    const val FILE_KEY_OFFSETS = "key_offsets.bin"
    const val FILE_RAW_INDEX_REFS = "idx_refs.tmp"

    private const val CHUNK_LIMIT = 20_000
    private const val MAX_DEFINITION_BYTES = 2 * 1024 * 1024 // 2 MB safety cap per single entry read
    private const val MAX_HEADWORD_BYTES = 1024

    data class IndexBuildSummary(
        val indexedWords: Long,
        val indexedSynonyms: Long
    )

    internal data class IndexEntry(
        val normalizedKey: String,
        val displayWord: String,
        val isSynonym: Boolean,
        val canonicalWord: String,
        val dataOffset: Long,
        val dataSize: Int
    ) : Comparable<IndexEntry> {
        override fun compareTo(other: IndexEntry): Int {
            val c1 = normalizedKey.compareTo(other.normalizedKey)
            if (c1 != 0) return c1
            // Exact headwords before synonyms for the same normalized key
            if (isSynonym != other.isSynonym) return if (!isSynonym) -1 else 1
            val c2 = displayWord.compareTo(other.displayWord)
            if (c2 != 0) return c2
            return dataOffset.compareTo(other.dataOffset)
        }
    }

    /**
     * Streams `.idx` (already unwrapped from `.idx.gz` if needed) and optional `.syn`, validates
     * offsets against [dictFileLength], and writes disk-backed `sorted_keys.bin` & `key_offsets.bin`
     * into [targetDir].
     */
    fun buildDiskIndex(
        ifo: StarDictIfo,
        idxStream: InputStream,
        synStream: InputStream?,
        dictFileLength: Long,
        targetDir: File,
        onProgress: ((String) -> Unit)? = null
    ): IndexBuildSummary {
        if (!targetDir.exists()) targetDir.mkdirs()

        val runFiles = mutableListOf<File>()
        val chunk = ArrayList<IndexEntry>(minOf(CHUNK_LIMIT, ifo.wordCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(16)))
        val idxRefsFile = if (synStream != null) File(targetDir, FILE_RAW_INDEX_REFS) else null
        var idxRefsOut: DataOutputStream? = null

        var wordCountRead = 0L
        var synCountRead = 0L

        try {
            if (idxRefsFile != null) {
                idxRefsOut = DataOutputStream(BufferedOutputStream(FileOutputStream(idxRefsFile), 16 * 1024))
            }

            onProgress?.invoke("Indexing ${ifo.bookName}...")

            // 1. Stream .idx entries
            val bufferedIdx = BufferedInputStream(idxStream, 32 * 1024)
            val wordBuf = ByteArrayOutputStream(64)
            while (true) {
                val word = readNullTerminatedUtf8(bufferedIdx, wordBuf) ?: break
                val offset = if (ifo.idxOffsetBits == 64) {
                    readUnsignedLong64(bufferedIdx)
                } else {
                    readUnsignedInt32(bufferedIdx)
                }
                val sizeLong = readUnsignedInt32(bufferedIdx)
                if (offset < 0L || sizeLong < 0L || sizeLong > Int.MAX_VALUE.toLong() || offset + sizeLong > dictFileLength) {
                    throw IllegalArgumentException(
                        "Corrupted StarDict .idx entry at word #$wordCountRead (offset=$offset, size=$sizeLong, dictSize=$dictFileLength)."
                    )
                }
                val size = sizeLong.toInt()
                val trimmedWord = word.trim()
                val normalized = normalizeLookupKey(trimmedWord)

                // Record fixed-order index ref for optional .syn resolution
                if (idxRefsOut != null) {
                    val wordUtf8 = trimmedWord.toByteArray(StandardCharsets.UTF_8)
                    val safeLen = wordUtf8.size.coerceAtMost(MAX_HEADWORD_BYTES)
                    idxRefsOut.writeLong(offset)
                    idxRefsOut.writeInt(size)
                    idxRefsOut.writeShort(safeLen)
                    idxRefsOut.write(wordUtf8, 0, safeLen)
                }

                if (normalized.isNotEmpty()) {
                    chunk.add(
                        IndexEntry(
                            normalizedKey = normalized,
                            displayWord = trimmedWord,
                            isSynonym = false,
                            canonicalWord = trimmedWord,
                            dataOffset = offset,
                            dataSize = size
                        )
                    )
                    if (chunk.size >= CHUNK_LIMIT) {
                        runFiles.add(flushSortedRun(targetDir, runFiles.size, chunk))
                    }
                }
                wordCountRead++
            }

            idxRefsOut?.flush()
            idxRefsOut?.close()
            idxRefsOut = null

            if (wordCountRead == 0L) {
                throw IllegalArgumentException("StarDict .idx file contained 0 entries.")
            }

            // 2. Stream optional .syn file using disk-backed random access to idxRefsFile
            if (synStream != null && idxRefsFile != null && idxRefsFile.exists()) {
                onProgress?.invoke("Indexing synonyms for ${ifo.bookName}...")
                val refOffsetsFile = File(targetDir, "idx_ref_offsets.tmp")
                try {
                    buildRefOffsetTable(idxRefsFile, refOffsetsFile)
                    RandomAccessFile(idxRefsFile, "r").use { refRaf ->
                        RandomAccessFile(refOffsetsFile, "r").use { offRaf ->
                            val totalRefs = offRaf.length() / 8L
                            val bufferedSyn = BufferedInputStream(synStream, 16 * 1024)
                            val synBuf = ByteArrayOutputStream(64)
                            while (true) {
                                val synWord = readNullTerminatedUtf8(bufferedSyn, synBuf) ?: break
                                val origIndex = readUnsignedInt32(bufferedSyn)
                                if (origIndex < 0L || origIndex >= totalRefs) continue

                                offRaf.seek(origIndex * 8L)
                                val refPos = offRaf.readLong()
                                refRaf.seek(refPos)
                                val dataOffset = refRaf.readLong()
                                val dataSize = refRaf.readInt()
                                val canonLen = refRaf.readUnsignedShort()
                                val canonBytes = ByteArray(canonLen)
                                refRaf.readFully(canonBytes)
                                val canonicalWord = String(canonBytes, StandardCharsets.UTF_8)

                                val trimmedSyn = synWord.trim()
                                val normSyn = normalizeLookupKey(trimmedSyn)
                                if (normSyn.isNotEmpty()) {
                                    chunk.add(
                                        IndexEntry(
                                            normalizedKey = normSyn,
                                            displayWord = trimmedSyn,
                                            isSynonym = true,
                                            canonicalWord = canonicalWord,
                                            dataOffset = dataOffset,
                                            dataSize = dataSize
                                        )
                                    )
                                    synCountRead++
                                    if (chunk.size >= CHUNK_LIMIT) {
                                        runFiles.add(flushSortedRun(targetDir, runFiles.size, chunk))
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    refOffsetsFile.delete()
                }
            }

            if (chunk.isNotEmpty()) {
                runFiles.add(flushSortedRun(targetDir, runFiles.size, chunk))
            }

            onProgress?.invoke("Finalizing disk index for ${ifo.bookName}...")
            mergeRunsToFinalDiskIndex(runFiles, targetDir)

            return IndexBuildSummary(
                indexedWords = wordCountRead,
                indexedSynonyms = synCountRead
            )
        } finally {
            try { idxRefsOut?.close() } catch (_: Throwable) {}
            idxRefsFile?.delete()
            runFiles.forEach { it.delete() }
        }
    }

    /**
     * Performs O(log N) disk-backed lookup in a single installed dictionary directory.
     * Never loads the index or `.dict` file into RAM.
     */
    fun lookup(
        dictDir: File,
        dictionary: InstalledDictionary,
        rawQuery: String,
        maxEntries: Int = 12
    ): List<DictionaryDefinitionEntry> {
        val targetKey = normalizeLookupKey(rawQuery)
        if (targetKey.isEmpty()) return emptyList()

        val offsetsFile = File(dictDir, FILE_KEY_OFFSETS)
        val keysFile = File(dictDir, FILE_SORTED_KEYS)
        val dictFile = File(dictDir, FILE_DATA_DICT)
        if (!offsetsFile.isFile || !keysFile.isFile || !dictFile.isFile) return emptyList()

        val recordCount = offsetsFile.length() / 8L
        if (recordCount <= 0L) return emptyList()

        val matchedIndexEntries = mutableListOf<IndexEntry>()

        RandomAccessFile(offsetsFile, "r").use { offRaf ->
            RandomAccessFile(keysFile, "r").use { keysRaf ->
                val lowerBound = findLowerBound(offRaf, keysRaf, recordCount, targetKey)
                if (lowerBound < 0L || lowerBound >= recordCount) return emptyList()

                var idx = lowerBound
                while (idx < recordCount && matchedIndexEntries.size < maxEntries) {
                    val entry = readEntryAtIndex(offRaf, keysRaf, idx) ?: break
                    if (entry.normalizedKey != targetKey) break
                    matchedIndexEntries.add(entry)
                    idx++
                }
            }
        }

        if (matchedIndexEntries.isEmpty()) return emptyList()

        // Prefer exact-case matches before case-insensitive matches, and headwords before synonyms
        val trimmedQuery = rawQuery.trim()
        val ordered = matchedIndexEntries.sortedWith(
            compareBy<IndexEntry>(
                { it.isSynonym },
                { it.displayWord != trimmedQuery },
                { it.dataOffset }
            )
        )

        val seenSpans = HashSet<String>()
        val results = mutableListOf<DictionaryDefinitionEntry>()

        RandomAccessFile(dictFile, "r").use { dictRaf ->
            val fileLen = dictRaf.length()
            for (entry in ordered) {
                val dedupKey = "${entry.dataOffset}:${entry.dataSize}:${entry.canonicalWord}"
                if (!seenSpans.add(dedupKey)) continue
                if (entry.dataOffset < 0L || entry.dataSize < 0 || entry.dataSize > MAX_DEFINITION_BYTES) continue
                if (entry.dataOffset + entry.dataSize > fileLen) continue

                val rawBytes = ByteArray(entry.dataSize)
                dictRaf.seek(entry.dataOffset)
                dictRaf.readFully(rawBytes)

                val decoded = StarDictFormatParser.decodeDefinitionBytes(rawBytes, dictionary.sameTypeSequence)
                results.add(
                    DictionaryDefinitionEntry(
                        dictionaryId = dictionary.id,
                        dictionaryName = dictionary.name,
                        headword = entry.canonicalWord,
                        matchedSynonym = if (entry.isSynonym && !entry.displayWord.equals(entry.canonicalWord, ignoreCase = true)) {
                            entry.displayWord
                        } else {
                            null
                        },
                        definitionText = decoded.text,
                        phonetic = decoded.phonetic,
                        partOfSpeech = decoded.partOfSpeech,
                        hasUnsupportedBinaryOnly = decoded.hasUnsupportedBinaryOnly
                    )
                )
            }
        }

        return results
    }

    fun normalizeLookupKey(raw: String): String {
        return raw.trim().lowercase(Locale.ROOT)
    }

    private fun findLowerBound(
        offRaf: RandomAccessFile,
        keysRaf: RandomAccessFile,
        recordCount: Long,
        targetKey: String
    ): Long {
        var low = 0L
        var high = recordCount - 1L
        var found = -1L
        while (low <= high) {
            val mid = (low + high) ushr 1
            val key = readNormalizedKeyAtIndex(offRaf, keysRaf, mid) ?: return -1L
            val cmp = key.compareTo(targetKey)
            when {
                cmp < 0 -> low = mid + 1L
                cmp > 0 -> high = mid - 1L
                else -> {
                    found = mid
                    high = mid - 1L // keep searching left for first duplicate/synonym
                }
            }
        }
        return found
    }

    private fun readNormalizedKeyAtIndex(
        offRaf: RandomAccessFile,
        keysRaf: RandomAccessFile,
        index: Long
    ): String? {
        offRaf.seek(index * 8L)
        val recordPos = offRaf.readLong()
        keysRaf.seek(recordPos)
        val normLen = keysRaf.readUnsignedShort()
        val normBytes = ByteArray(normLen)
        keysRaf.readFully(normBytes)
        return String(normBytes, StandardCharsets.UTF_8)
    }

    private fun readEntryAtIndex(
        offRaf: RandomAccessFile,
        keysRaf: RandomAccessFile,
        index: Long
    ): IndexEntry? {
        offRaf.seek(index * 8L)
        val recordPos = offRaf.readLong()
        keysRaf.seek(recordPos)
        val normLen = keysRaf.readUnsignedShort()
        val normBytes = ByteArray(normLen)
        keysRaf.readFully(normBytes)
        val dispLen = keysRaf.readUnsignedShort()
        val dispBytes = ByteArray(dispLen)
        keysRaf.readFully(dispBytes)
        val isSyn = keysRaf.readByte().toInt() != 0
        val canonLen = keysRaf.readUnsignedShort()
        val canonBytes = ByteArray(canonLen)
        keysRaf.readFully(canonBytes)
        val dataOffset = keysRaf.readLong()
        val dataSize = keysRaf.readInt()
        return IndexEntry(
            normalizedKey = String(normBytes, StandardCharsets.UTF_8),
            displayWord = String(dispBytes, StandardCharsets.UTF_8),
            isSynonym = isSyn,
            canonicalWord = String(canonBytes, StandardCharsets.UTF_8),
            dataOffset = dataOffset,
            dataSize = dataSize
        )
    }

    private fun buildRefOffsetTable(refsFile: File, offsetsFile: File) {
        DataInputStream(BufferedInputStream(FileInputStream(refsFile), 32 * 1024)).use { dis ->
            DataOutputStream(BufferedOutputStream(FileOutputStream(offsetsFile), 16 * 1024)).use { dos ->
                var pos = 0L
                val fileLen = refsFile.length()
                while (pos < fileLen) {
                    dos.writeLong(pos)
                    dis.readLong() // offset
                    dis.readInt()  // size
                    val len = dis.readUnsignedShort()
                    var skipped = 0
                    while (skipped < len) {
                        val s = dis.skipBytes(len - skipped)
                        if (s <= 0) break
                        skipped += s
                    }
                    pos += 8L + 4L + 2L + len.toLong()
                }
            }
        }
    }

    private fun flushSortedRun(targetDir: File, runIndex: Int, chunk: MutableList<IndexEntry>): File {
        chunk.sort()
        val runFile = File(targetDir, "sort_run_$runIndex.tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(runFile), 32 * 1024)).use { dos ->
            for (entry in chunk) {
                writeEntry(dos, entry)
            }
        }
        chunk.clear()
        return runFile
    }

    private data class RunCursor(
        val stream: DataInputStream,
        var current: IndexEntry
    ) : Comparable<RunCursor> {
        override fun compareTo(other: RunCursor): Int = current.compareTo(other.current)
    }

    private fun mergeRunsToFinalDiskIndex(runFiles: List<File>, targetDir: File) {
        val keysFile = File(targetDir, FILE_SORTED_KEYS)
        val offsetsFile = File(targetDir, FILE_KEY_OFFSETS)

        val openStreams = mutableListOf<DataInputStream>()
        try {
            val pq = PriorityQueue<RunCursor>()
            for (rf in runFiles) {
                val dis = DataInputStream(BufferedInputStream(FileInputStream(rf), 16 * 1024))
                openStreams.add(dis)
                val first = readEntryFromStream(dis)
                if (first != null) {
                    pq.add(RunCursor(dis, first))
                }
            }

            DataOutputStream(BufferedOutputStream(FileOutputStream(keysFile), 32 * 1024)).use { keysOut ->
                DataOutputStream(BufferedOutputStream(FileOutputStream(offsetsFile), 32 * 1024)).use { offsetsOut ->
                    var currentFilePos = 0L
                    while (pq.isNotEmpty()) {
                        val cursor = pq.poll()!!
                        offsetsOut.writeLong(currentFilePos)
                        val bytesWritten = writeEntry(keysOut, cursor.current)
                        currentFilePos += bytesWritten
                        val next = readEntryFromStream(cursor.stream)
                        if (next != null) {
                            cursor.current = next
                            pq.add(cursor)
                        }
                    }
                }
            }
        } finally {
            openStreams.forEach { try { it.close() } catch (_: Throwable) {} }
        }
    }

    private fun writeEntry(dos: DataOutputStream, entry: IndexEntry): Long {
        val normBytes = truncateUtf8(entry.normalizedKey, MAX_HEADWORD_BYTES)
        val dispBytes = truncateUtf8(entry.displayWord, MAX_HEADWORD_BYTES)
        val canonBytes = truncateUtf8(entry.canonicalWord, MAX_HEADWORD_BYTES)

        dos.writeShort(normBytes.size)
        dos.write(normBytes)
        dos.writeShort(dispBytes.size)
        dos.write(dispBytes)
        dos.writeByte(if (entry.isSynonym) 1 else 0)
        dos.writeShort(canonBytes.size)
        dos.write(canonBytes)
        dos.writeLong(entry.dataOffset)
        dos.writeInt(entry.dataSize)

        return (2 + normBytes.size + 2 + dispBytes.size + 1 + 2 + canonBytes.size + 8 + 4).toLong()
    }

    private fun readEntryFromStream(dis: DataInputStream): IndexEntry? {
        val normLen = try {
            dis.readUnsignedShort()
        } catch (_: EOFException) {
            return null
        }
        val normBytes = ByteArray(normLen)
        dis.readFully(normBytes)
        val dispLen = dis.readUnsignedShort()
        val dispBytes = ByteArray(dispLen)
        dis.readFully(dispBytes)
        val isSyn = dis.readByte().toInt() != 0
        val canonLen = dis.readUnsignedShort()
        val canonBytes = ByteArray(canonLen)
        dis.readFully(canonBytes)
        val dataOffset = dis.readLong()
        val dataSize = dis.readInt()
        return IndexEntry(
            normalizedKey = String(normBytes, StandardCharsets.UTF_8),
            displayWord = String(dispBytes, StandardCharsets.UTF_8),
            isSynonym = isSyn,
            canonicalWord = String(canonBytes, StandardCharsets.UTF_8),
            dataOffset = dataOffset,
            dataSize = dataSize
        )
    }

    private fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        val full = text.toByteArray(StandardCharsets.UTF_8)
        if (full.size <= maxBytes) return full
        // Avoid splitting multi-byte UTF-8 continuation bytes (10xxxxxx)
        var end = maxBytes
        while (end > 0 && (full[end].toInt() and 0xC0) == 0x80) {
            end--
        }
        return full.copyOf(end)
    }

    private fun readNullTerminatedUtf8(stream: InputStream, scratch: ByteArrayOutputStream): String? {
        scratch.reset()
        var b = stream.read()
        if (b == -1) return null
        while (b != 0) {
            if (b == -1) {
                throw IllegalArgumentException("Unexpected EOF while reading null-terminated StarDict word.")
            }
            if (scratch.size() < MAX_HEADWORD_BYTES * 2) {
                scratch.write(b)
            }
            b = stream.read()
        }
        return String(scratch.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun readUnsignedInt32(stream: InputStream): Long {
        val b0 = stream.read()
        val b1 = stream.read()
        val b2 = stream.read()
        val b3 = stream.read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) {
            throw IllegalArgumentException("Unexpected EOF while reading 32-bit StarDict offset/size.")
        }
        return ((b0.toLong() and 0xFFL) shl 24) or
            ((b1.toLong() and 0xFFL) shl 16) or
            ((b2.toLong() and 0xFFL) shl 8) or
            (b3.toLong() and 0xFFL)
    }

    private fun readUnsignedLong64(stream: InputStream): Long {
        var value = 0L
        for (i in 0 until 8) {
            val b = stream.read()
            if (b < 0) {
                throw IllegalArgumentException("Unexpected EOF while reading 64-bit StarDict offset.")
            }
            value = (value shl 8) or (b.toLong() and 0xFFL)
        }
        return value
    }
}
