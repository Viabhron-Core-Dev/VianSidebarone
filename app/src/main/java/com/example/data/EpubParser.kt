package com.example.data

import android.content.Context
import com.example.core.LogKeeper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.io.StringReader
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min

/**
 * Metadata representation for an individual book chapter.
 * [sourceRef] stores the internal ZIP entry path for EPUBs or "txt_bytes:<start>:<end>:<charset>" (or legacy "txt_chars:<start>:<end>") for TXT books.
 */
data class ChapterMeta(
    val index: Int,
    val title: String,
    val wordCount: Int = 0,
    val sourceRef: String = ""
)

/**
 * Cache manifest persisted per book in book_<id>/cache_manifest.json.
 */
data class BookCacheManifest(
    val bookId: Int,
    val format: String, // "epub" or "txt"
    val sourceFilePath: String,
    val sourceLastModified: Long,
    val sourceSize: Long,
    val totalChapters: Int,
    val charsetName: String = "UTF-8"
)

/**
 * Result of EPUB package parsing.
 */
data class ParsedBookInfo(
    val chapterCount: Int,
    val title: String? = null,
    val author: String? = null
)

/**
 * Authoritative EPUB and TXT parser with lazy metadata-first import and a 21-chapter rolling window cache.
 *
 * Implements:
 * 1. Lazy metadata-first import: parses container.xml, OPF manifest/spine, and builds complete ordered
 *    chapter metadata (`toc.json` + `cache_manifest.json`) without writing all chapter `.txt` files upfront.
 * 2. Rolling 21-chapter window (`current - 10` through `current + 10`) in memory and disk cache for books
 *    with > 21 chapters (and full retention for books with <= 21 chapters).
 * 3. Forward/backward rolling shifts and distant chapter jumps with automatic eviction outside the window.
 * 4. Transient on-demand chapter reading for full-book search without polluting the rolling cache.
 * 5. Hardened XML/OPF namespace handling, relative path resolution, URL decoding, literal '+' preservation,
 *    duplicate spine deduplication, natural numeric fallback, and atomic file writes.
 */
object EpubParser {

    private const val TAG = "EpubParser"
    const val ROLLING_WINDOW_RADIUS = 10
    const val MAX_ROLLING_WINDOW_CHAPTERS = 21

    // In-memory rolling chapter text cache per bookId: bookId -> (chapterIndex -> text)
    private val memoryWindowCache = ConcurrentHashMap<Int, ConcurrentHashMap<Int, String>>()
    // Active center chapter per bookId
    private val activeWindowCenter = ConcurrentHashMap<Int, Int>()
    private val bookLocks = ConcurrentHashMap<Int, Any>()

    // Instrumentation counters for unit tests to verify bounded byte-range TXT materialization
    @Volatile
    var lastTxtMaterializeBytesReadForTesting: Long = 0L
        private set
    @Volatile
    var txtMaterializeReadCallCountForTesting: Int = 0
        private set

    fun resetTxtTestCounters() {
        lastTxtMaterializeBytesReadForTesting = 0L
        txtMaterializeReadCallCountForTesting = 0
    }

    private fun lockFor(bookId: Int): Any = bookLocks.getOrPut(bookId) { Any() }

    data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String
    )

    data class SpineItem(
        val idref: String,
        val linear: Boolean
    )

    /**
     * Parses an EPUB file lazily into ordered chapter metadata stored in `book_${bookId}/toc.json`
     * and `cache_manifest.json`. Does NOT extract/write all chapter `.txt` files upfront.
     */
    suspend fun parseEpub(context: Context, bookId: Int, epubFile: File): ParsedBookInfo = withContext(Dispatchers.IO) {
        try {
            if (!epubFile.exists() || !epubFile.isFile) {
                return@withContext ParsedBookInfo(-1)
            }
            var chapterCount = 0
            val bookDir = File(context.filesDir, "book_$bookId")
            if (!bookDir.exists()) bookDir.mkdirs()

            var extractedTitle: String? = null
            var extractedAuthor: String? = null
            val chaptersMeta = mutableListOf<ChapterMeta>()

            ZipFile(epubFile).use { zip ->
                // 1. Locate OPF package file from container.xml
                var opfPath = ""
                val containerEntry = findZipEntry(zip, "META-INF/container.xml")
                if (containerEntry != null) {
                    val containerXml = zip.getInputStream(containerEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    opfPath = extractOpfPathFromContainer(containerXml)
                }

                if (opfPath.isEmpty()) {
                    val opfEntry = zip.entries().asSequence().find {
                        !it.isDirectory && it.name.endsWith(".opf", ignoreCase = true)
                    }
                    if (opfEntry != null) {
                        opfPath = opfEntry.name
                        LogKeeper.log(context, TAG, "OPF not in container.xml; discovered entry: $opfPath")
                    }
                }

                val manifestMap = LinkedHashMap<String, ManifestItem>()
                val spineList = mutableListOf<SpineItem>()

                if (opfPath.isNotEmpty()) {
                    val normalizedOpfPath = normalizeZipPath("", opfPath)
                    val opfEntry = findZipEntry(zip, normalizedOpfPath) ?: zip.getEntry(opfPath)
                    if (opfEntry != null) {
                        val opfContent = zip.getInputStream(opfEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                        parseOpfXml(opfContent, manifestMap, spineList).let { (title, author) ->
                            extractedTitle = title
                            extractedAuthor = author
                        }
                    }
                }

                val normalizedOpfFull = if (opfPath.isNotEmpty()) normalizeZipPath("", opfPath) else ""
                val opfDir = if (normalizedOpfFull.contains("/")) {
                    normalizedOpfFull.substringBeforeLast("/") + "/"
                } else if (opfPath.contains("/")) {
                    opfPath.substringBeforeLast("/") + "/"
                } else {
                    ""
                }

                // Optional NCX / nav title lookup map: normalizedEntryPath -> label
                val navTitlesByPath = extractNavTitles(zip, opfDir, manifestMap)

                // 2. Resolve reading chapters strictly following OPF spine
                val readingEntries = mutableListOf<Pair<ZipEntry, String>>()
                val seenEntryPaths = mutableSetOf<String>()
                val seenSpineIdrefs = mutableSetOf<String>()

                for (spineItem in spineList) {
                    if (!spineItem.linear) continue
                    // Deduplicate identical spine idrefs while preserving first occurrence order
                    if (!seenSpineIdrefs.add(spineItem.idref)) continue

                    val item = manifestMap[spineItem.idref] ?: continue
                    if (isNavigationManifestItem(item)) continue

                    val normalizedPath = normalizeZipPath(opfDir, item.href)
                    val entry = findZipEntry(zip, normalizedPath)
                    if (entry != null && !entry.isDirectory && seenEntryPaths.add(entry.name.lowercase())) {
                        readingEntries.add(entry to item.href)
                    }
                }

                // 3. Fallback when spine is missing or yielded 0 valid reading entries: deterministic natural sort
                val finalEntries: List<Pair<ZipEntry, String>> = if (readingEntries.isNotEmpty()) {
                    readingEntries
                } else {
                    LogKeeper.log(context, TAG, "Spine missing or yielded 0 reading entries for bookId=$bookId. Using natural-sort fallback.")
                    zip.entries().asSequence()
                        .filter { entry ->
                            if (entry.isDirectory) return@filter false
                            val name = entry.name.lowercase()
                            (name.endsWith(".html") || name.endsWith(".xhtml") || name.endsWith(".htm")) &&
                                !name.contains("meta-inf/") &&
                                !name.endsWith("toc.ncx") &&
                                !name.endsWith("/nav.xhtml") &&
                                name != "nav.xhtml"
                        }
                        .sortedWith { e1, e2 -> naturalCompare(e1.name, e2.name) }
                        .map { it to it.name }
                        .toList()
                }

                // 4. Build ordered TOC metadata lazily without writing chapter_*.txt files upfront
                for (pair in finalEntries) {
                    val entry = pair.first
                    val rawHtml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val cleanText = htmlToCleanText(rawHtml)
                    if (cleanText.isNotBlank()) {
                        val idx = chapterCount
                        val normalizedEntryKey = entry.name.trimStart('/').replace('\\', '/').lowercase()
                        val rawExtractedTitle = extractChapterTitle(rawHtml)
                            ?: navTitlesByPath[normalizedEntryKey]
                        val title = rawExtractedTitle?.takeIf { it.isNotBlank() } ?: "Chapter ${idx + 1}"
                        val wordCount = countWordsFast(cleanText)
                        chaptersMeta.add(
                            ChapterMeta(
                                index = idx,
                                title = title,
                                wordCount = wordCount,
                                sourceRef = entry.name
                            )
                        )
                        chapterCount++
                    }
                }
            }

            synchronized(lockFor(bookId)) {
                // Clean up any stale chapter_*.txt files from previous parses (e.g. shorter reparsed book)
                cleanupAllCachedChaptersOnDisk(bookDir)
                memoryWindowCache.remove(bookId)
                activeWindowCenter.remove(bookId)

                saveTocJsonAtomic(bookDir, chaptersMeta)
                saveCacheManifestAtomic(
                    bookDir,
                    BookCacheManifest(
                        bookId = bookId,
                        format = "epub",
                        sourceFilePath = epubFile.absolutePath,
                        sourceLastModified = epubFile.lastModified(),
                        sourceSize = epubFile.length(),
                        totalChapters = chapterCount
                    )
                )
            }

            return@withContext ParsedBookInfo(chapterCount, extractedTitle, extractedAuthor)
        } catch (e: Exception) {
            LogKeeper.log(context, TAG, "EpubParser error for bookId=$bookId: ${e.message}")
            return@withContext ParsedBookInfo(-1)
        }
    }

    /**
     * Backward-compatible helper returning chapter count.
     */
    suspend fun parseEpubToText(context: Context, bookId: Int, epubFile: File): Int {
        return parseEpub(context, bookId, epubFile).chapterCount
    }

    /**
     * Parses a plain TXT file in a single streaming pass into segmented chapter metadata
     * (`toc.json` + `cache_manifest.json`) using exact byte ranges (`txt_bytes:<start>:<end>:<charset>`).
     * Never loads the full TXT file into memory.
     */
    suspend fun parseTxtToText(
        context: Context,
        bookId: Int,
        txtFile: File
    ): Int = parseTxtToTextInternal(context, bookId, txtFile, bufferSize = 65536)

    internal suspend fun parseTxtToTextInternal(
        context: Context,
        bookId: Int,
        txtFile: File,
        bufferSize: Int = 65536
    ): Int = withContext(Dispatchers.IO) {
        try {
            if (!txtFile.exists() || !txtFile.isFile) return@withContext -1
            val bookDir = File(context.filesDir, "book_$bookId")
            if (!bookDir.exists()) bookDir.mkdirs()

            val chapterPattern = Regex("(?im)^\\s*(Chapter|CHAPTER|Section|SECTION|Part|PART)\\s+([0-9IVXLCDM]+|[A-Za-z]+)(\\s*[:\\-\\u2014].*)?$")
            val (charset, bomOffset) = detectTxtEncoding(txtFile)
            val charsetTag = charset.name()

            // Single-pass streaming trackers: both heading-based and size-based (~15,000 chars) segments
            var detectedHeadingCount = 0
            val headingSegments = mutableListOf<ChapterMeta>()
            var hStartByte = bomOffset
            var hEndByte = bomOffset
            var hTitle = "Chapter 1"
            var hWordCount = 0
            var hHasNonBlank = false

            val sectionSegments = mutableListOf<ChapterMeta>()
            var sStartByte = bomOffset
            var sEndByte = bomOffset
            var sCharCount = 0
            var sWordCount = 0
            var sHasNonBlank = false

            fun flushHeadingSegment() {
                if (hHasNonBlank && hEndByte > hStartByte) {
                    val idx = headingSegments.size
                    val cleanTitle = hTitle.takeIf { it.isNotBlank() } ?: "Chapter ${idx + 1}"
                    headingSegments.add(
                        ChapterMeta(
                            index = idx,
                            title = cleanTitle,
                            wordCount = hWordCount,
                            sourceRef = "txt_bytes:$hStartByte:$hEndByte:$charsetTag"
                        )
                    )
                }
            }

            fun flushSectionSegment(isFinal: Boolean) {
                if (sHasNonBlank && sEndByte > sStartByte) {
                    val idx = sectionSegments.size
                    val title = if (isFinal && idx == 0) "Complete Text" else "Section ${idx + 1}"
                    sectionSegments.add(
                        ChapterMeta(
                            index = idx,
                            title = title,
                            wordCount = sWordCount,
                            sourceRef = "txt_bytes:$sStartByte:$sEndByte:$charsetTag"
                        )
                    )
                }
            }

            streamTxtLinesWithByteOffsets(
                file = txtFile,
                charset = charset,
                bomOffset = bomOffset,
                bufferSize = bufferSize
            ) { line, lineStartByte, nextLineStartByte ->
                val trimmed = line.trim()
                val lineWords = countWordsFast(trimmed)

                // Track heading-based segmentation
                if (chapterPattern.matches(trimmed)) {
                    detectedHeadingCount++
                    if (lineStartByte > hStartByte) {
                        flushHeadingSegment()
                    }
                    hStartByte = lineStartByte
                    hEndByte = nextLineStartByte
                    hTitle = trimmed
                    hWordCount = lineWords
                    hHasNonBlank = lineWords > 0
                } else {
                    hEndByte = nextLineStartByte
                    if (lineWords > 0) {
                        hWordCount += lineWords
                        hHasNonBlank = true
                    }
                }

                // Track fallback ~15,000-character section segmentation
                sEndByte = nextLineStartByte
                sCharCount += line.length + 1
                if (lineWords > 0) {
                    sWordCount += lineWords
                    sHasNonBlank = true
                }
                if (sCharCount >= 15000 && line.isBlank() && sHasNonBlank) {
                    flushSectionSegment(isFinal = false)
                    sStartByte = nextLineStartByte
                    sCharCount = 0
                    sWordCount = 0
                    sHasNonBlank = false
                }
            }

            flushHeadingSegment()
            flushSectionSegment(isFinal = true)

            val chaptersMeta = if (detectedHeadingCount >= 2 && headingSegments.size >= 2) {
                headingSegments
            } else {
                sectionSegments
            }
            val chapterCount = chaptersMeta.size

            synchronized(lockFor(bookId)) {
                cleanupAllCachedChaptersOnDisk(bookDir)
                memoryWindowCache.remove(bookId)
                activeWindowCenter.remove(bookId)

                saveTocJsonAtomic(bookDir, chaptersMeta)
                saveCacheManifestAtomic(
                    bookDir,
                    BookCacheManifest(
                        bookId = bookId,
                        format = "txt",
                        sourceFilePath = txtFile.absolutePath,
                        sourceLastModified = txtFile.lastModified(),
                        sourceSize = txtFile.length(),
                        totalChapters = chapterCount,
                        charsetName = charsetTag
                    )
                )
            }

            return@withContext chapterCount
        } catch (e: Exception) {
            LogKeeper.log(context, "EpubParserTxt", "TXT parse error: ${e.message}")
            return@withContext -1
        }
    }

    internal fun detectTxtEncoding(file: File): Pair<Charset, Long> {
        val sample = ByteArray(65536)
        var bytesRead = 0
        try {
            FileInputStream(file).use { fis ->
                while (bytesRead < sample.size) {
                    val r = fis.read(sample, bytesRead, sample.size - bytesRead)
                    if (r <= 0) break
                    bytesRead += r
                }
            }
        } catch (_: Throwable) {
            return Charsets.UTF_8 to 0L
        }

        if (bytesRead >= 4) {
            val b0 = sample[0].toInt() and 0xFF
            val b1 = sample[1].toInt() and 0xFF
            val b2 = sample[2].toInt() and 0xFF
            val b3 = sample[3].toInt() and 0xFF
            if (b0 == 0x00 && b1 == 0x00 && b2 == 0xFE && b3 == 0xFF) {
                return runCatching { Charset.forName("UTF-32BE") }.getOrDefault(Charsets.UTF_8) to 4L
            }
            if (b0 == 0xFF && b1 == 0xFE && b2 == 0x00 && b3 == 0x00) {
                return runCatching { Charset.forName("UTF-32LE") }.getOrDefault(Charsets.UTF_8) to 4L
            }
        }
        if (bytesRead >= 3) {
            val b0 = sample[0].toInt() and 0xFF
            val b1 = sample[1].toInt() and 0xFF
            val b2 = sample[2].toInt() and 0xFF
            if (b0 == 0xEF && b1 == 0xBB && b2 == 0xBF) {
                return Charsets.UTF_8 to 3L
            }
        }
        if (bytesRead >= 2) {
            val b0 = sample[0].toInt() and 0xFF
            val b1 = sample[1].toInt() and 0xFF
            if (b0 == 0xFE && b1 == 0xFF) {
                return Charsets.UTF_16BE to 2L
            }
            if (b0 == 0xFF && b1 == 0xFE) {
                return Charsets.UTF_16LE to 2L
            }
        }

        if (bytesRead > 0) {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val byteBuf = ByteBuffer.wrap(sample, 0, bytesRead)
            val charBuf = CharBuffer.allocate(bytesRead)
            val endOfInput = file.length() <= bytesRead.toLong()
            val cr = decoder.decode(byteBuf, charBuf, endOfInput)
            if (cr.isError) {
                val fallback = if (Charset.isSupported("windows-1252")) {
                    Charset.forName("windows-1252")
                } else {
                    Charsets.ISO_8859_1
                }
                return fallback to 0L
            }
        }
        return Charsets.UTF_8 to 0L
    }

    internal fun streamTxtLinesWithByteOffsets(
        file: File,
        charset: Charset,
        bomOffset: Long,
        bufferSize: Int = 65536,
        onLine: (line: String, lineStartByte: Long, nextLineStartByte: Long) -> Unit
    ) {
        val upperName = charset.name().uppercase()
        val stride = when {
            upperName.contains("UTF-16") -> 2
            upperName.contains("UTF-32") -> 4
            else -> 1
        }
        val isBigEndian = upperName.contains("BE")
        val effectiveBufferSize = ((bufferSize.coerceAtLeast(stride * 8)) / stride) * stride
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val lineBuf = ByteArrayOutputStream(1024)
        var lineStartOffset = bomOffset
        var lastWasCr = false

        fun emitCurrentLine(nextStartOffset: Long) {
            val rawBytes = lineBuf.toByteArray()
            val text = if (rawBytes.isEmpty()) {
                ""
            } else {
                decoder.reset()
                decoder.decode(ByteBuffer.wrap(rawBytes, 0, rawBytes.size)).toString()
            }
            lineBuf.reset()
            onLine(text, lineStartOffset, nextStartOffset)
            lineStartOffset = nextStartOffset
        }

        FileInputStream(file).use { fis ->
            var toSkip = bomOffset
            val skipScratch = ByteArray(16)
            while (toSkip > 0L) {
                val r = fis.read(skipScratch, 0, minOf(toSkip, skipScratch.size.toLong()).toInt())
                if (r <= 0) break
                toSkip -= r.toLong()
            }

            var currentOffset = bomOffset
            val readBuf = ByteArray(effectiveBufferSize)
            var carryLen = 0
            val carry = ByteArray(stride)

            while (true) {
                val read = fis.read(readBuf, carryLen, effectiveBufferSize - carryLen)
                if (read == -1) break
                if (carryLen > 0) {
                    System.arraycopy(carry, 0, readBuf, 0, carryLen)
                }
                val totalValid = carryLen + read
                val alignedLimit = (totalValid / stride) * stride
                carryLen = totalValid - alignedLimit
                if (carryLen > 0) {
                    System.arraycopy(readBuf, alignedLimit, carry, 0, carryLen)
                }

                var i = 0
                while (i < alignedLimit) {
                    val isLf: Boolean
                    val isCr: Boolean
                    val isAsciiSpaceOrTab: Boolean
                    when (stride) {
                        1 -> {
                            val b = readBuf[i].toInt() and 0xFF
                            isLf = b == 0x0A
                            isCr = b == 0x0D
                            isAsciiSpaceOrTab = b == 0x20 || b == 0x09
                        }
                        2 -> {
                            val b0 = readBuf[i].toInt() and 0xFF
                            val b1 = readBuf[i + 1].toInt() and 0xFF
                            val codeUnit = if (isBigEndian) (b0 shl 8) or b1 else (b1 shl 8) or b0
                            isLf = codeUnit == 0x000A
                            isCr = codeUnit == 0x000D
                            isAsciiSpaceOrTab = codeUnit == 0x0020 || codeUnit == 0x0009
                        }
                        else -> {
                            val b0 = readBuf[i].toInt() and 0xFF
                            val b1 = readBuf[i + 1].toInt() and 0xFF
                            val b2 = readBuf[i + 2].toInt() and 0xFF
                            val b3 = readBuf[i + 3].toInt() and 0xFF
                            val codePoint = if (isBigEndian) {
                                (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
                            } else {
                                (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
                            }
                            isLf = codePoint == 0x000A
                            isCr = codePoint == 0x000D
                            isAsciiSpaceOrTab = codePoint == 0x0020 || codePoint == 0x0009
                        }
                    }

                    currentOffset += stride.toLong()
                    i += stride

                    if (isCr) {
                        emitCurrentLine(currentOffset)
                        lastWasCr = true
                    } else if (isLf) {
                        if (lastWasCr) {
                            lastWasCr = false
                            lineStartOffset = currentOffset
                        } else {
                            emitCurrentLine(currentOffset)
                        }
                    } else {
                        lastWasCr = false
                        lineBuf.write(readBuf, i - stride, stride)
                        if (lineBuf.size() >= 32768) {
                            val canHardBreakUtf8 = stride == 1 && lineBuf.size() >= 65536 &&
                                i < alignedLimit && ((readBuf[i].toInt() and 0xC0) != 0x80)
                            val canHardBreakMulti = stride > 1 && lineBuf.size() >= 65536
                            if (isAsciiSpaceOrTab || canHardBreakUtf8 || canHardBreakMulti) {
                                emitCurrentLine(currentOffset)
                            }
                        }
                    }
                }
            }

            if (carryLen > 0) {
                lineBuf.write(carry, 0, carryLen)
                currentOffset += carryLen.toLong()
            }
            if (lineBuf.size() > 0) {
                emitCurrentLine(currentOffset)
            }
        }
    }

    /**
     * Computes the inclusive `first..last` chapter index bounds for the 21-chapter rolling window
     * centered at [centerChapterIndex].
     * - For books with <= 21 chapters, the window is `0 until totalChapters`.
     * - For books with > 21 chapters, the window is `max(0, center - 10) .. min(total - 1, center + 10)`
     *   (never exceeding 21 chapters).
     */
    fun computeRollingWindowRange(centerChapterIndex: Int, totalChapters: Int): IntRange {
        if (totalChapters <= 0) return IntRange.EMPTY
        if (totalChapters <= MAX_ROLLING_WINDOW_CHAPTERS) {
            return 0 until totalChapters
        }
        val clampedCenter = centerChapterIndex.coerceIn(0, totalChapters - 1)
        val start = max(0, clampedCenter - ROLLING_WINDOW_RADIUS)
        val end = min(totalChapters - 1, clampedCenter + ROLLING_WINDOW_RADIUS)
        return start..end
    }

    /**
     * Updates the 21-chapter rolling cache window around [centerChapterIndex].
     * - Materializes chapters inside the active window `[center - 10, center + 10]`.
     * - Evicts any in-memory and disk `chapter_<idx>.txt` files outside the active window when `totalChapters > 21`.
     */
    fun updateRollingWindow(
        context: Context,
        bookId: Int,
        centerChapterIndex: Int,
        sourceFileOverride: File? = null
    ): IntRange {
        synchronized(lockFor(bookId)) {
            val bookDir = File(context.filesDir, "book_$bookId")
            val toc = getChapterList(context, bookId)
            val totalChapters = toc.size
            if (totalChapters <= 0) return IntRange.EMPTY

            val clampedCenter = centerChapterIndex.coerceIn(0, totalChapters - 1)
            val windowRange = computeRollingWindowRange(clampedCenter, totalChapters)
            activeWindowCenter[bookId] = clampedCenter

            val memMap = memoryWindowCache.getOrPut(bookId) { ConcurrentHashMap() }
            val manifest = loadCacheManifest(bookDir)
            val sourceFile = resolveSourceFile(context, bookId, manifest, sourceFileOverride)

            // 1. Evict chapters outside windowRange when totalChapters > 21
            if (totalChapters > MAX_ROLLING_WINDOW_CHAPTERS) {
                val keysToRemove = memMap.keys.filter { it !in windowRange }
                for (k in keysToRemove) {
                    memMap.remove(k)
                }
                val diskFiles = bookDir.listFiles { _, name -> name.startsWith("chapter_") && name.endsWith(".txt") }
                if (diskFiles != null) {
                    for (f in diskFiles) {
                        val idx = f.name.removePrefix("chapter_").removeSuffix(".txt").toIntOrNull()
                        if (idx != null && idx !in windowRange) {
                            f.delete()
                        }
                    }
                }
            }

            // 2. Ensure chapters in windowRange are materialized
            val missingIndices = windowRange.filter { idx ->
                !memMap.containsKey(idx) && !File(bookDir, "chapter_$idx.txt").exists()
            }

            if (missingIndices.isNotEmpty() && sourceFile != null && sourceFile.exists()) {
                materializeChaptersFromSource(
                    bookDir = bookDir,
                    format = manifest?.format ?: if (sourceFile.name.endsWith(".txt", ignoreCase = true)) "txt" else "epub",
                    sourceFile = sourceFile,
                    toc = toc,
                    indicesToLoad = missingIndices.toSet(),
                    memMap = memMap,
                    persistToDisk = true
                )
            }

            // Populate memory cache from disk for any window chapters already on disk
            for (idx in windowRange) {
                if (!memMap.containsKey(idx)) {
                    val f = File(bookDir, "chapter_$idx.txt")
                    if (f.exists()) {
                        try {
                            memMap[idx] = f.readText()
                        } catch (_: Throwable) {}
                    }
                }
            }

            return windowRange
        }
    }

    /**
     * Resolves a chapter's text, updating/maintaining the rolling 21-chapter window around [chapterIndex].
     */
    fun getChapterText(context: Context, bookId: Int, chapterIndex: Int): String {
        synchronized(lockFor(bookId)) {
            val bookDir = File(context.filesDir, "book_$bookId")
            val toc = getChapterList(context, bookId)
            if (chapterIndex < 0 || (toc.isNotEmpty() && chapterIndex >= toc.size)) {
                return ""
            }

            // Update rolling window centered at requested chapterIndex
            if (toc.isNotEmpty()) {
                updateRollingWindow(context, bookId, chapterIndex)
            }

            val memMap = memoryWindowCache[bookId]
            val fromMem = memMap?.get(chapterIndex)
            if (fromMem != null) {
                return fromMem
            }

            val chapterFile = File(bookDir, "chapter_$chapterIndex.txt")
            if (chapterFile.exists()) {
                val text = chapterFile.readText()
                memoryWindowCache.getOrPut(bookId) { ConcurrentHashMap() }[chapterIndex] = text
                return text
            }

            // On-demand fallback extraction if source file exists
            val manifest = loadCacheManifest(bookDir)
            val sourceFile = resolveSourceFile(context, bookId, manifest, null)
            if (sourceFile != null && sourceFile.exists() && chapterIndex < toc.size) {
                val tempMap = ConcurrentHashMap<Int, String>()
                materializeChaptersFromSource(
                    bookDir = bookDir,
                    format = manifest?.format ?: if (sourceFile.name.endsWith(".txt", ignoreCase = true)) "txt" else "epub",
                    sourceFile = sourceFile,
                    toc = toc,
                    indicesToLoad = setOf(chapterIndex),
                    memMap = tempMap,
                    persistToDisk = true
                )
                val extracted = tempMap[chapterIndex] ?: ""
                if (extracted.isNotEmpty()) {
                    memoryWindowCache.getOrPut(bookId) { ConcurrentHashMap() }[chapterIndex] = extracted
                }
                return extracted
            }

            return ""
        }
    }

    /**
     * Reads a chapter's text transiently (e.g. for full-book search) WITHOUT expanding the rolling
     * 21-chapter cache or leaving temporary chapter files outside the active window.
     */
    fun readChapterTextTransient(context: Context, bookId: Int, chapterIndex: Int): String {
        synchronized(lockFor(bookId)) {
            val memMap = memoryWindowCache[bookId]
            if (memMap != null && memMap.containsKey(chapterIndex)) {
                return memMap[chapterIndex] ?: ""
            }
            val bookDir = File(context.filesDir, "book_$bookId")
            val chapterFile = File(bookDir, "chapter_$chapterIndex.txt")
            if (chapterFile.exists()) {
                return try { chapterFile.readText() } catch (_: Throwable) { "" }
            }
            val toc = getChapterList(context, bookId)
            if (chapterIndex !in toc.indices) return ""
            val manifest = loadCacheManifest(bookDir)
            val sourceFile = resolveSourceFile(context, bookId, manifest, null) ?: return ""
            if (!sourceFile.exists()) return ""

            val transientMap = ConcurrentHashMap<Int, String>()
            materializeChaptersFromSource(
                bookDir = bookDir,
                format = manifest?.format ?: if (sourceFile.name.endsWith(".txt", ignoreCase = true)) "txt" else "epub",
                sourceFile = sourceFile,
                toc = toc,
                indicesToLoad = setOf(chapterIndex),
                memMap = transientMap,
                persistToDisk = false
            )
            return transientMap[chapterIndex] ?: ""
        }
    }

    /**
     * Releases in-memory chapter buffers for a specific book or all books (e.g. when Reader folds or closes).
     */
    fun releaseInMemoryWindow(bookId: Int? = null) {
        if (bookId != null) {
            memoryWindowCache.remove(bookId)
            activeWindowCenter.remove(bookId)
        } else {
            memoryWindowCache.clear()
            activeWindowCenter.clear()
        }
    }

    /**
     * Returns currently cached chapter indices in memory and disk for testing and verification.
     */
    fun getCachedChapterIndicesOnDisk(context: Context, bookId: Int): Set<Int> {
        val bookDir = File(context.filesDir, "book_$bookId")
        val files = bookDir.listFiles { _, name -> name.startsWith("chapter_") && name.endsWith(".txt") } ?: return emptySet()
        return files.mapNotNull { it.name.removePrefix("chapter_").removeSuffix(".txt").toIntOrNull() }.toSortedSet()
    }

    fun getCachedChapterIndicesInMemory(bookId: Int): Set<Int> {
        return memoryWindowCache[bookId]?.keys?.toSortedSet() ?: emptySet()
    }

    /**
     * Loads the table of contents list for a given book.
     */
    fun getChapterList(context: Context, bookId: Int): List<ChapterMeta> {
        val bookDir = File(context.filesDir, "book_$bookId")
        val tocFile = File(bookDir, "toc.json")
        if (tocFile.exists()) {
            try {
                val jsonStr = tocFile.readText()
                val array = JSONArray(jsonStr)
                val list = mutableListOf<ChapterMeta>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        ChapterMeta(
                            index = obj.optInt("index", i),
                            title = obj.optString("title", "Chapter ${i + 1}"),
                            wordCount = obj.optInt("wordCount", 0),
                            sourceRef = obj.optString("sourceRef", "")
                        )
                    )
                }
                return list
            } catch (e: Exception) {
                LogKeeper.log(context, TAG, "Failed to parse toc.json: ${e.message}")
            }
        }

        // Fallback scan of chapter_*.txt files if toc.json is missing
        val files = bookDir.listFiles { _, name -> name.startsWith("chapter_") && name.endsWith(".txt") } ?: return emptyList()
        val sortedFiles = files.sortedWith { f1, f2 -> naturalCompare(f1.name, f2.name) }
        return sortedFiles.mapIndexed { idx, _ ->
            ChapterMeta(index = idx, title = "Chapter ${idx + 1}")
        }
    }

    private fun materializeChaptersFromSource(
        bookDir: File,
        format: String,
        sourceFile: File,
        toc: List<ChapterMeta>,
        indicesToLoad: Set<Int>,
        memMap: ConcurrentHashMap<Int, String>,
        persistToDisk: Boolean
    ) {
        if (indicesToLoad.isEmpty()) return
        try {
            if (format.equals("txt", ignoreCase = true)) {
                val manifestCharset = loadCacheManifest(bookDir)?.charsetName
                val defaultCharset = runCatching {
                    Charset.forName(manifestCharset ?: "UTF-8")
                }.getOrDefault(Charsets.UTF_8)

                for (idx in indicesToLoad) {
                    val meta = toc.getOrNull(idx) ?: continue
                    val ref = meta.sourceRef
                    val text: String? = when {
                        ref.startsWith("txt_bytes:") -> {
                            val parts = ref.removePrefix("txt_bytes:").split(':', limit = 3)
                            val startByte = parts.getOrNull(0)?.toLongOrNull()
                            val endByte = parts.getOrNull(1)?.toLongOrNull()
                            val refCharset = parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.let {
                                runCatching { Charset.forName(it) }.getOrNull()
                            } ?: defaultCharset
                            if (startByte != null && endByte != null) {
                                readTxtByteRange(sourceFile, startByte, endByte, refCharset)
                            } else {
                                null
                            }
                        }
                        ref.startsWith("txt_chars:") -> {
                            val parts = ref.removePrefix("txt_chars:").split(':')
                            val startChar = parts.getOrNull(0)?.toIntOrNull()
                            val endChar = parts.getOrNull(1)?.toIntOrNull()
                            if (startChar != null && endChar != null) {
                                readTxtCharRangeStreaming(sourceFile, startChar, endChar, defaultCharset)
                            } else {
                                null
                            }
                        }
                        else -> null
                    }
                    if (text != null) {
                        memMap[idx] = text
                        if (persistToDisk) {
                            writeFileAtomic(File(bookDir, "chapter_$idx.txt"), text)
                        }
                    }
                }
            } else {
                ZipFile(sourceFile).use { zip ->
                    for (idx in indicesToLoad) {
                        val meta = toc.getOrNull(idx) ?: continue
                        val entryPath = meta.sourceRef
                        if (entryPath.isBlank()) continue
                        val entry = zip.getEntry(entryPath) ?: findZipEntry(zip, entryPath) ?: continue
                        val rawHtml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val cleanText = htmlToCleanText(rawHtml)
                        memMap[idx] = cleanText
                        if (persistToDisk) {
                            writeFileAtomic(File(bookDir, "chapter_$idx.txt"), cleanText)
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    internal fun readTxtByteRange(
        file: File,
        startByte: Long,
        endByte: Long,
        charset: Charset,
        chunkSize: Int = 16384
    ): String {
        if (!file.exists() || endByte <= startByte) return ""
        val totalToRead = (endByte - startByte).coerceAtLeast(0L)
        if (totalToRead == 0L) return ""

        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val inBuf = ByteBuffer.allocate(chunkSize)
        val outBuf = CharBuffer.allocate(chunkSize)
        val sb = StringBuilder(minOf(totalToRead, 262144L).toInt().coerceAtLeast(64))

        RandomAccessFile(file, "r").use { raf ->
            val channel = raf.channel
            channel.position(startByte)
            var remaining = totalToRead
            while (remaining > 0L) {
                val maxFill = minOf(inBuf.remaining().toLong(), remaining).toInt()
                if (maxFill <= 0) break
                val oldLimit = inBuf.limit()
                inBuf.limit(inBuf.position() + maxFill)
                val read = channel.read(inBuf)
                inBuf.limit(oldLimit)
                if (read <= 0) {
                    remaining = 0L
                    break
                }
                remaining -= read.toLong()
                lastTxtMaterializeBytesReadForTesting += read.toLong()

                inBuf.flip()
                val isEof = remaining <= 0L
                while (true) {
                    val cr = decoder.decode(inBuf, outBuf, isEof)
                    outBuf.flip()
                    if (outBuf.hasRemaining()) {
                        sb.append(outBuf)
                    }
                    outBuf.clear()
                    if (cr.isOverflow) continue
                    break
                }
                inBuf.compact()
            }
            if (inBuf.position() > 0) {
                inBuf.flip()
                while (true) {
                    val cr = decoder.decode(inBuf, outBuf, true)
                    outBuf.flip()
                    if (outBuf.hasRemaining()) {
                        sb.append(outBuf)
                    }
                    outBuf.clear()
                    if (cr.isOverflow) continue
                    break
                }
            }
            while (true) {
                val cr = decoder.flush(outBuf)
                outBuf.flip()
                if (outBuf.hasRemaining()) {
                    sb.append(outBuf)
                }
                outBuf.clear()
                if (!cr.isOverflow) break
            }
        }
        txtMaterializeReadCallCountForTesting++
        return normalizeLineEndings(sb.toString()).trim()
    }

    /**
     * Streaming fallback for legacy `txt_chars:<start>:<end>` entries from prior installs
     * so even unmigrated books never materialize the entire TXT file into memory.
     */
    private fun readTxtCharRangeStreaming(
        file: File,
        startChar: Int,
        endChar: Int,
        charset: Charset
    ): String {
        if (!file.exists() || endChar <= startChar) return ""
        val sb = StringBuilder((endChar - startChar).coerceIn(64, 262144))
        BufferedReader(InputStreamReader(FileInputStream(file), charset)).use { reader ->
            var pos = 0
            var firstLine = true
            while (pos < endChar) {
                val line = reader.readLine() ?: break
                val lineWithSepStart = if (firstLine) pos else pos
                if (!firstLine) {
                    // '\n' separator between lines
                    if (pos in startChar until endChar) {
                        sb.append('\n')
                    }
                    pos += 1
                }
                firstLine = false
                val lineEnd = pos + line.length
                if (lineEnd > startChar && pos < endChar) {
                    val sliceStart = (startChar - pos).coerceIn(0, line.length)
                    val sliceEnd = (endChar - pos).coerceIn(sliceStart, line.length)
                    sb.append(line, sliceStart, sliceEnd)
                }
                pos = lineEnd
            }
        }
        return sb.toString().trim()
    }

    private fun normalizeLineEndings(s: String): String {
        if (!s.contains('\r')) return s
        return s.replace("\r\n", "\n").replace('\r', '\n')
    }

    private fun resolveSourceFile(
        context: Context,
        bookId: Int,
        manifest: BookCacheManifest?,
        overrideFile: File?
    ): File? {
        if (overrideFile != null && overrideFile.exists()) return overrideFile
        if (manifest != null && manifest.sourceFilePath.isNotBlank()) {
            val f = File(manifest.sourceFilePath)
            if (f.exists()) return f
        }
        return try {
            val dbBook = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                AppDatabase.getDatabase(context).epubDao().getBookById(bookId)
            }
            dbBook?.filePath?.let { File(it) }?.takeIf { it.exists() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun cleanupAllCachedChaptersOnDisk(bookDir: File) {
        val files = bookDir.listFiles { _, name ->
            (name.startsWith("chapter_") && (name.endsWith(".txt") || name.endsWith(".tmp")))
        } ?: return
        for (f in files) {
            f.delete()
        }
    }

    private fun saveTocJsonAtomic(bookDir: File, chapters: List<ChapterMeta>) {
        try {
            val array = JSONArray()
            for (ch in chapters) {
                val obj = JSONObject()
                obj.put("index", ch.index)
                obj.put("title", ch.title)
                obj.put("wordCount", ch.wordCount)
                obj.put("sourceRef", ch.sourceRef)
                array.put(obj)
            }
            writeFileAtomic(File(bookDir, "toc.json"), array.toString(2))
        } catch (_: Throwable) {}
    }

    private fun saveCacheManifestAtomic(bookDir: File, manifest: BookCacheManifest) {
        try {
            val obj = JSONObject().apply {
                put("bookId", manifest.bookId)
                put("format", manifest.format)
                put("sourceFilePath", manifest.sourceFilePath)
                put("sourceLastModified", manifest.sourceLastModified)
                put("sourceSize", manifest.sourceSize)
                put("totalChapters", manifest.totalChapters)
                put("charsetName", manifest.charsetName)
            }
            writeFileAtomic(File(bookDir, "cache_manifest.json"), obj.toString(2))
        } catch (_: Throwable) {}
    }

    fun loadCacheManifest(bookDir: File): BookCacheManifest? {
        val file = File(bookDir, "cache_manifest.json")
        if (!file.exists()) return null
        return try {
            val obj = JSONObject(file.readText())
            BookCacheManifest(
                bookId = obj.optInt("bookId", 0),
                format = obj.optString("format", "epub"),
                sourceFilePath = obj.optString("sourceFilePath", ""),
                sourceLastModified = obj.optLong("sourceLastModified", 0L),
                sourceSize = obj.optLong("sourceSize", 0L),
                totalChapters = obj.optInt("totalChapters", 0),
                charsetName = obj.optString("charsetName", "UTF-8").ifBlank { "UTF-8" }
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeFileAtomic(target: File, content: String) {
        val parent = target.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun countWordsFast(text: String): Int {
        var count = 0
        var inWord = false
        for (i in 0 until text.length) {
            if (text[i].isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                count++
            }
        }
        return count
    }

    private fun isNavigationManifestItem(item: ManifestItem): Boolean {
        val propsTokens = item.properties.lowercase().split(Regex("\\s+"))
        if ("nav" in propsTokens) return true
        val hrefLower = item.href.lowercase()
        val mediaType = item.mediaType.lowercase()
        val isHtml = mediaType.contains("xhtml") || mediaType.contains("html") || mediaType.contains("xml") ||
            hrefLower.endsWith(".html") || hrefLower.endsWith(".xhtml") || hrefLower.endsWith(".htm")
        if (!isHtml) return true
        if (hrefLower.endsWith("toc.ncx") || mediaType.contains("dtbncx")) return true
        return false
    }

    private fun extractNavTitles(
        zip: ZipFile,
        opfDir: String,
        manifestMap: Map<String, ManifestItem>
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val ncxItem = manifestMap.values.firstOrNull {
                it.mediaType.contains("dtbncx", ignoreCase = true) || it.href.endsWith(".ncx", ignoreCase = true)
            }
            if (ncxItem != null) {
                val ncxPath = normalizeZipPath(opfDir, ncxItem.href)
                val ncxDir = if (ncxPath.contains("/")) ncxPath.substringBeforeLast("/") + "/" else ""
                val entry = findZipEntry(zip, ncxPath)
                if (entry != null) {
                    val xml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val navPointRegex = Regex("<navPoint[^>]*>(.*?)</navPoint>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    for (m in navPointRegex.findAll(xml)) {
                        val block = m.groupValues[1]
                        val text = Regex("<text[^>]*>([^<]+)</text>", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)?.trim()
                        val src = Regex("<content[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)?.trim()
                        if (!text.isNullOrBlank() && !src.isNullOrBlank()) {
                            val norm = normalizeZipPath(ncxDir, src).lowercase()
                            result.putIfAbsent(norm, unescapeHtml(text))
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return result
    }

    /**
     * Normalizes a relative ZIP entry path, resolving '..' and '.' segments, URL decoding,
     * preserving literal '+', and combining with the base directory.
     */
    fun normalizeZipPath(baseDir: String, href: String): String {
        val cleanHref = href.substringBefore('#').substringBefore('?')
        val decodedHref = try {
            URLDecoder.decode(cleanHref.replace("+", "%2B"), "UTF-8")
        } catch (_: Throwable) {
            cleanHref
        }

        val unifiedHref = decodedHref.replace('\\', '/')
        val unifiedBase = baseDir.replace('\\', '/')

        val fullPath = if (unifiedHref.startsWith("/")) {
            unifiedHref.trimStart('/')
        } else {
            val base = if (unifiedBase.isNotEmpty() && !unifiedBase.endsWith('/')) "$unifiedBase/" else unifiedBase
            "$base$unifiedHref"
        }

        val segments = fullPath.split('/')
        val resolved = mutableListOf<String>()
        for (seg in segments) {
            when (seg) {
                "", "." -> continue
                ".." -> {
                    if (resolved.isNotEmpty()) {
                        resolved.removeAt(resolved.size - 1)
                    }
                }
                else -> resolved.add(seg)
            }
        }
        return resolved.joinToString("/")
    }

    /**
     * Locates a ZIP entry strictly matching the normalized path, avoiding ambiguous suffix matching.
     */
    fun findZipEntry(zip: ZipFile, targetPath: String): ZipEntry? {
        val direct = zip.getEntry(targetPath)
        if (direct != null) return direct

        val trimmed = targetPath.trimStart('/')
        val directTrimmed = zip.getEntry(trimmed)
        if (directTrimmed != null) return directTrimmed

        val targetNorm = trimmed.replace('\\', '/').lowercase()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val entryNorm = entry.name.trimStart('/').replace('\\', '/').lowercase()
            if (entryNorm == targetNorm) {
                return entry
            }
        }
        return null
    }

    /**
     * Natural numeric comparator: ensures "chapter_1" < "chapter_2" < "chapter_10".
     */
    fun naturalCompare(a: String, b: String): Int {
        var ia = 0
        var ib = 0
        val la = a.length
        val lb = b.length
        while (ia < la && ib < lb) {
            val ca = a[ia]
            val cb = b[ib]
            if (ca.isDigit() && cb.isDigit()) {
                val startA = ia
                while (ia < la && a[ia].isDigit()) ia++
                val startB = ib
                while (ib < lb && b[ib].isDigit()) ib++

                val strA = a.substring(startA, ia)
                val strB = b.substring(startB, ib)
                val trimmedA = strA.trimStart('0')
                val trimmedB = strB.trimStart('0')

                if (trimmedA.length != trimmedB.length) {
                    return trimmedA.length.compareTo(trimmedB.length)
                }
                val numCmp = trimmedA.compareTo(trimmedB)
                if (numCmp != 0) return numCmp

                if (strA.length != strB.length) {
                    return strA.length.compareTo(strB.length)
                }
            } else {
                val diff = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (diff != 0) return diff
                ia++
                ib++
            }
        }
        return (la - ia).compareTo(lb - ib)
    }

    private fun extractOpfPathFromContainer(containerXml: String): String {
        return try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            val parser = factory.newPullParser()
            parser.setInput(StringReader(containerXml))
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val localName = parser.name.substringAfterLast(':').lowercase()
                    if (localName == "rootfile") {
                        val fullPath = getAttrIgnoreNamespace(parser, "full-path")
                        if (!fullPath.isNullOrEmpty()) {
                            return fullPath.trim()
                        }
                    }
                }
                eventType = parser.next()
            }
            Regex("full-path=[\"']([^\"']+)[\"']").find(containerXml)?.groupValues?.get(1)?.trim() ?: ""
        } catch (e: Exception) {
            Regex("full-path=[\"']([^\"']+)[\"']").find(containerXml)?.groupValues?.get(1)?.trim() ?: ""
        }
    }

    private fun getAttrIgnoreNamespace(parser: XmlPullParser, attrLocalName: String): String? {
        val direct = parser.getAttributeValue(null, attrLocalName)
        if (direct != null) return direct
        for (i in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(i)
            if (name.equals(attrLocalName, ignoreCase = true) ||
                name.substringAfterLast(':').equals(attrLocalName, ignoreCase = true)
            ) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    private fun parseOpfXml(
        opfContent: String,
        manifestMap: MutableMap<String, ManifestItem>,
        spineList: MutableList<SpineItem>
    ): Pair<String?, String?> {
        var bookTitle: String? = null
        var bookAuthor: String? = null

        try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            val parser = factory.newPullParser()
            parser.setInput(StringReader(opfContent))

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val localName = parser.name.substringAfterLast(':').lowercase()
                    when (localName) {
                        "title" -> {
                            if (bookTitle.isNullOrBlank()) {
                                bookTitle = try { parser.nextText()?.trim() } catch (_: Throwable) { null }
                            }
                        }
                        "creator", "author" -> {
                            if (bookAuthor.isNullOrBlank()) {
                                bookAuthor = try { parser.nextText()?.trim() } catch (_: Throwable) { null }
                            }
                        }
                        "item" -> {
                            val id = getAttrIgnoreNamespace(parser, "id") ?: ""
                            val href = getAttrIgnoreNamespace(parser, "href") ?: ""
                            val mediaType = getAttrIgnoreNamespace(parser, "media-type") ?: ""
                            val properties = getAttrIgnoreNamespace(parser, "properties") ?: ""
                            if (id.isNotEmpty() && href.isNotEmpty()) {
                                manifestMap[id] = ManifestItem(id, href, mediaType, properties)
                            }
                        }
                        "itemref" -> {
                            val idref = getAttrIgnoreNamespace(parser, "idref") ?: ""
                            val linearAttr = getAttrIgnoreNamespace(parser, "linear")
                            val linear = linearAttr == null || !linearAttr.equals("no", ignoreCase = true)
                            if (idref.isNotEmpty()) {
                                spineList.add(SpineItem(idref, linear))
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            val itemRegex = Regex("<(?:[a-zA-Z0-9_]+:)?item\\s+([^>]+)>", RegexOption.IGNORE_CASE)
            itemRegex.findAll(opfContent).forEach { match ->
                val attrs = match.groupValues[1]
                val idMatch = Regex("id=[\"']([^\"']+)[\"']").find(attrs)
                val hrefMatch = Regex("href=[\"']([^\"']+)[\"']").find(attrs)
                val mediaMatch = Regex("media-type=[\"']([^\"']+)[\"']").find(attrs)
                val propMatch = Regex("properties=[\"']([^\"']+)[\"']").find(attrs)
                if (idMatch != null && hrefMatch != null) {
                    val id = idMatch.groupValues[1]
                    val href = hrefMatch.groupValues[1]
                    manifestMap[id] = ManifestItem(
                        id = id,
                        href = href,
                        mediaType = mediaMatch?.groupValues?.get(1) ?: "",
                        properties = propMatch?.groupValues?.get(1) ?: ""
                    )
                }
            }

            val spineMatch = Regex("<(?:[a-zA-Z0-9_]+:)?spine.*?</(?:[a-zA-Z0-9_]+:)?spine>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(opfContent)
            if (spineMatch != null) {
                val itemrefRegex = Regex("<(?:[a-zA-Z0-9_]+:)?itemref[^>]+idref=[\"']([^\"']+)[\"'](?:[^>]*linear=[\"']([^\"']+)[\"'])?", RegexOption.IGNORE_CASE)
                itemrefRegex.findAll(spineMatch.value).forEach { match ->
                    val idref = match.groupValues[1]
                    val linear = match.groupValues.getOrNull(2)?.lowercase() != "no"
                    spineList.add(SpineItem(idref, linear))
                }
            }
        }

        return Pair(bookTitle, bookAuthor)
    }

    private fun extractChapterTitle(html: String): String? {
        val hMatch = Regex("<h[1-3][^>]*>(.*?)</h[1-3]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)
        val titleMatch = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)

        if (titleMatch != null) {
            val raw = titleMatch.groupValues[1].replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            val title = unescapeHtml(raw)
            if (title.isNotBlank() && !title.equals("untitled", ignoreCase = true) && !title.equals("unknown", ignoreCase = true)) {
                return title
            }
        }

        if (hMatch != null) {
            val raw = hMatch.groupValues[1].replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            val heading = unescapeHtml(raw)
            if (heading.isNotBlank()) {
                return heading
            }
        }
        return null
    }

    private fun htmlToCleanText(rawHtml: String): String {
        var text = rawHtml
            .replace(Regex("<head[^>]*>.*?</head>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")

        val bodyStart = text.indexOf("<body", ignoreCase = true)
        if (bodyStart != -1) {
            text = text.substring(bodyStart)
        }

        return text
            .replace(Regex("<p.*?>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("</p>|<br\\s*/?>|</div>|</h[1-6]>|</li>|</tr>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<[^>]+>"), "")
            .let { unescapeHtml(it) }
            .replace(Regex("(?m)^[ \\t]+"), "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun unescapeHtml(input: String): String {
        return input
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&#8217;", "’")
            .replace("&#8216;", "‘")
            .replace("&#8220;", "“")
            .replace("&#8221;", "”")
            .replace("&#8212;", "—")
            .replace("&#8211;", "–")
            .replace(Regex("&#(\\d+);")) { m ->
                val code = m.groupValues[1].toIntOrNull()
                if (code != null) code.toChar().toString() else m.value
            }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
                val code = m.groupValues[1].toIntOrNull(16)
                if (code != null) code.toChar().toString() else m.value
            }
    }
}
