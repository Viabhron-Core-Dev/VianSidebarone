package com.example.data

import android.content.Context
import com.example.core.LogKeeper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Metadata representation for an individual book chapter.
 */
data class ChapterMeta(
    val index: Int,
    val title: String,
    val wordCount: Int = 0
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
 * Robust, authoritative EPUB and TXT parser.
 *
 * Implements:
 * 1. Authoritative OPF manifest and spine parsing using XmlPullParser.
 * 2. Proper relative path resolution against OPF base directory.
 * 3. URL-decoded and relative segment ('..', '.') path normalization.
 * 4. Exact normalized ZIP entry lookup (avoiding ambiguous suffix-only matching).
 * 5. Exclusion of navigation documents (properties="nav", toc.ncx, linear="no").
 * 6. Explicit deterministic fallback with natural numeric ordering ("chapter_1" < "chapter_2" < "chapter_10").
 * 7. Clean HTML entity unescaping, title extraction, and paragraph preservation.
 * 8. Segmented chapter generation and cached table of contents (toc.json).
 */
object EpubParser {

    private const val TAG = "EpubParser"

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
     * Parses an EPUB file into text chapters stored in private storage (book_${bookId}/).
     * Returns total chapter count, title, and author.
     */
    suspend fun parseEpub(context: Context, bookId: Int, epubFile: File): ParsedBookInfo = withContext(Dispatchers.IO) {
        try {
            var chapterCount = 0
            val bookDir = File(context.filesDir, "book_$bookId")
            if (!bookDir.exists()) bookDir.mkdirs()

            var extractedTitle: String? = null
            var extractedAuthor: String? = null
            val chaptersMeta = mutableListOf<ChapterMeta>()

            ZipFile(epubFile).use { zip ->
                // 1. Locate OPF package file from container.xml
                var opfPath = ""
                val containerEntry = zip.getEntry("META-INF/container.xml") ?: zip.getEntry("meta-inf/container.xml")
                if (containerEntry != null) {
                    val containerXml = zip.getInputStream(containerEntry).bufferedReader().readText()
                    opfPath = extractOpfPathFromContainer(containerXml)
                }

                if (opfPath.isEmpty()) {
                    // Fallback search for any .opf file in the archive
                    val opfEntry = zip.entries().asSequence().find { it.name.endsWith(".opf", ignoreCase = true) }
                    if (opfEntry != null) {
                        opfPath = opfEntry.name
                        LogKeeper.log(context, TAG, "OPF not in container.xml; discovered entry: $opfPath")
                    }
                }

                val manifestMap = mutableMapOf<String, ManifestItem>()
                val spineList = mutableListOf<SpineItem>()

                if (opfPath.isNotEmpty()) {
                    val normalizedOpfPath = normalizeZipPath("", opfPath)
                    val opfEntry = findZipEntry(zip, normalizedOpfPath) ?: zip.getEntry(opfPath)
                    if (opfEntry != null) {
                        val opfContent = zip.getInputStream(opfEntry).bufferedReader().readText()
                        parseOpfXml(opfContent, manifestMap, spineList).let { (title, author) ->
                            extractedTitle = title
                            extractedAuthor = author
                        }
                    }
                }

                val opfDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""

                // 2. Resolve reading chapters strictly following OPF spine
                val readingEntries = mutableListOf<Pair<ZipEntry, String>>() // (entry, href)
                val seenEntryPaths = mutableSetOf<String>()

                for (spineItem in spineList) {
                    // Non-linear items (e.g. popups, cover plates marked non-linear) are excluded from the main reading flow
                    if (!spineItem.linear) continue

                    val item = manifestMap[spineItem.idref] ?: continue
                    // Exclude navigation documents
                    if (item.properties.contains("nav", ignoreCase = true)) continue

                    val hrefLower = item.href.lowercase()
                    val mediaType = item.mediaType.lowercase()
                    val isHtml = mediaType.contains("xhtml") || mediaType.contains("html") || mediaType.contains("xml") ||
                            hrefLower.endsWith(".html") || hrefLower.endsWith(".xhtml") || hrefLower.endsWith(".htm")
                    if (!isHtml) continue
                    if (hrefLower.contains("toc.ncx")) continue

                    val normalizedPath = normalizeZipPath(opfDir, item.href)
                    val entry = findZipEntry(zip, normalizedPath)
                    if (entry != null && seenEntryPaths.add(entry.name.lowercase())) {
                        readingEntries.add(entry to item.href)
                    }
                }

                // 3. Fallback when spine is missing or invalid: deterministic natural sort
                val finalEntries: List<Pair<ZipEntry, String>> = if (readingEntries.isNotEmpty()) {
                    readingEntries
                } else {
                    LogKeeper.log(context, TAG, "Spine missing or yielded 0 reading entries for bookId=$bookId. Using natural-sort fallback.")
                    val fallback = zip.entries().asSequence()
                        .filter { entry ->
                            val name = entry.name.lowercase()
                            (name.endsWith(".html") || name.endsWith(".xhtml") || name.endsWith(".htm")) &&
                                    !name.contains("meta-inf/") &&
                                    !name.contains("toc.ncx") &&
                                    !name.contains("nav.xhtml")
                        }
                        .sortedWith { e1, e2 -> naturalCompare(e1.name, e2.name) }
                        .map { it to it.name }
                        .toList()
                    fallback
                }

                // 4. Extract text and TOC metadata for each chapter
                for ((index, pair) in finalEntries.withIndex()) {
                    val entry = pair.first
                    val rawHtml = zip.getInputStream(entry).bufferedReader().readText()

                    val title = extractChapterTitle(rawHtml) ?: "Chapter ${index + 1}"
                    val cleanText = htmlToCleanText(rawHtml)

                    if (cleanText.isNotBlank()) {
                        val chapterFile = File(bookDir, "chapter_$chapterCount.txt")
                        chapterFile.writeText(cleanText)
                        val wordCount = cleanText.split(Regex("\\s+")).count { it.isNotBlank() }
                        chaptersMeta.add(ChapterMeta(chapterCount, title, wordCount))
                        chapterCount++
                    }
                }
            }

            // Save table of contents metadata
            saveTocJson(bookDir, chaptersMeta)

            return@withContext ParsedBookInfo(chapterCount, extractedTitle, extractedAuthor)
        } catch (e: Exception) {
            LogKeeper.log(context, TAG, "EpubParser error for bookId=$bookId: ${e.message}")
            e.printStackTrace()
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
     * Parses a plain TXT file into segmented chapters with natural section detection.
     */
    suspend fun parseTxtToText(context: Context, bookId: Int, txtFile: File): Int = withContext(Dispatchers.IO) {
        try {
            val bookDir = File(context.filesDir, "book_$bookId")
            if (!bookDir.exists()) bookDir.mkdirs()

            var chapterCount = 0
            val chaptersMeta = mutableListOf<ChapterMeta>()
            val chapterPattern = Regex("(?im)^\\s*(Chapter|CHAPTER|Section|SECTION|Part|PART)\\s+([0-9IVXLCDM]+|[A-Za-z]+)(\\s*[:\\-\\u2014].*)?$")

            val fullText = txtFile.readText()
            val lines = fullText.lines()
            val currentChunk = StringBuilder()
            var currentTitle = "Chapter 1"

            fun flushChapter() {
                val text = currentChunk.toString().trim()
                if (text.isNotBlank()) {
                    val chapterFile = File(bookDir, "chapter_$chapterCount.txt")
                    chapterFile.writeText(text)
                    val wordCount = text.split(Regex("\\s+")).count { it.isNotBlank() }
                    chaptersMeta.add(ChapterMeta(chapterCount, currentTitle, wordCount))
                    chapterCount++
                    currentChunk.clear()
                }
            }

            var detectedHeadingCount = 0
            for (line in lines) {
                if (chapterPattern.matches(line.trim())) {
                    detectedHeadingCount++
                }
            }

            if (detectedHeadingCount >= 2) {
                // Split on detected chapter headings
                for (line in lines) {
                    val trimmed = line.trim()
                    if (chapterPattern.matches(trimmed)) {
                        flushChapter()
                        currentTitle = trimmed
                    }
                    currentChunk.append(line).append("\n")
                }
                flushChapter()
            } else {
                // Split by length (~15,000 characters) at natural paragraph breaks
                for (line in lines) {
                    currentChunk.append(line).append("\n")
                    if (currentChunk.length >= 15000 && line.isBlank()) {
                        currentTitle = "Section ${chapterCount + 1}"
                        flushChapter()
                    }
                }
                if (currentChunk.isNotEmpty() || chapterCount == 0) {
                    currentTitle = if (chapterCount == 0) "Complete Text" else "Section ${chapterCount + 1}"
                    flushChapter()
                }
            }

            saveTocJson(bookDir, chaptersMeta)
            return@withContext chapterCount
        } catch (e: Exception) {
            LogKeeper.log(context, "EpubParserTxt", "TXT parse error: ${e.message}")
            e.printStackTrace()
            return@withContext -1
        }
    }

    /**
     * Resolves a chapter's text from disk.
     */
    fun getChapterText(context: Context, bookId: Int, chapterIndex: Int): String {
        val chapterFile = File(File(context.filesDir, "book_$bookId"), "chapter_$chapterIndex.txt")
        return if (chapterFile.exists()) {
            chapterFile.readText()
        } else {
            ""
        }
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
                            wordCount = obj.optInt("wordCount", 0)
                        )
                    )
                }
                return list
            } catch (e: Exception) {
                LogKeeper.log(context, TAG, "Failed to parse toc.json: ${e.message}")
            }
        }

        // Fallback scan of chapter_*.txt files
        val files = bookDir.listFiles { _, name -> name.startsWith("chapter_") && name.endsWith(".txt") } ?: return emptyList()
        val sortedFiles = files.sortedWith { f1, f2 -> naturalCompare(f1.name, f2.name) }
        return sortedFiles.mapIndexed { idx, file ->
            ChapterMeta(index = idx, title = "Chapter ${idx + 1}")
        }
    }

    private fun saveTocJson(bookDir: File, chapters: List<ChapterMeta>) {
        try {
            val array = JSONArray()
            for (ch in chapters) {
                val obj = JSONObject()
                obj.put("index", ch.index)
                obj.put("title", ch.title)
                obj.put("wordCount", ch.wordCount)
                array.put(obj)
            }
            File(bookDir, "toc.json").writeText(array.toString(2))
        } catch (_: Throwable) {}
    }

    /**
     * Normalizes a relative ZIP entry path, resolving '..' and '.' segments, URL decoding,
     * and combining with the base directory.
     */
    fun normalizeZipPath(baseDir: String, href: String): String {
        val cleanHref = href.substringBefore('#').substringBefore('?')
        val decodedHref = try {
            URLDecoder.decode(cleanHref, "UTF-8")
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

        // Exact case-insensitive match against all entries
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
                if (eventType == XmlPullParser.START_TAG && parser.name.equals("rootfile", ignoreCase = true)) {
                    val fullPath = parser.getAttributeValue(null, "full-path")
                    if (!fullPath.isNullOrEmpty()) {
                        return fullPath.trim()
                    }
                }
                eventType = parser.next()
            }
            // Fallback regex if container.xml is malformed
            Regex("full-path=\"([^\"]+)\"").find(containerXml)?.groupValues?.get(1)?.trim() ?: ""
        } catch (e: Exception) {
            Regex("full-path=\"([^\"]+)\"").find(containerXml)?.groupValues?.get(1)?.trim() ?: ""
        }
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
                    val tagName = parser.name.lowercase()
                    when {
                        tagName.endsWith("title") -> {
                            if (bookTitle == null) {
                                bookTitle = parser.nextText()?.trim()
                            }
                        }
                        tagName.endsWith("creator") -> {
                            if (bookAuthor == null) {
                                bookAuthor = parser.nextText()?.trim()
                            }
                        }
                        tagName == "item" -> {
                            val id = parser.getAttributeValue(null, "id") ?: ""
                            val href = parser.getAttributeValue(null, "href") ?: ""
                            val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                            val properties = parser.getAttributeValue(null, "properties") ?: ""
                            if (id.isNotEmpty() && href.isNotEmpty()) {
                                manifestMap[id] = ManifestItem(id, href, mediaType, properties)
                            }
                        }
                        tagName == "itemref" -> {
                            val idref = parser.getAttributeValue(null, "idref") ?: ""
                            val linearAttr = parser.getAttributeValue(null, "linear")
                            val linear = linearAttr == null || linearAttr.lowercase() != "no"
                            if (idref.isNotEmpty()) {
                                spineList.add(SpineItem(idref, linear))
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            // Regex fallback if XML pull parsing fails on severely malformed OPF
            val itemRegex = Regex("<item\\s+([^>]+)>")
            itemRegex.findAll(opfContent).forEach { match ->
                val attrs = match.groupValues[1]
                val idMatch = Regex("id=\"([^\"]+)\"").find(attrs)
                val hrefMatch = Regex("href=\"([^\"]+)\"").find(attrs)
                val mediaMatch = Regex("media-type=\"([^\"]+)\"").find(attrs)
                val propMatch = Regex("properties=\"([^\"]+)\"").find(attrs)
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

            val spineMatch = Regex("<spine.*?</spine>", RegexOption.DOT_MATCHES_ALL).find(opfContent)
            if (spineMatch != null) {
                val itemrefRegex = Regex("<itemref[^>]+idref=\"([^\"]+)\"(?:[^>]*linear=\"([^\"]+)\")?")
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
        val titleMatch = Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE).find(html)
        if (titleMatch != null) {
            val title = unescapeHtml(titleMatch.groupValues[1].trim())
            if (title.isNotBlank() && !title.equals("untitled", ignoreCase = true)) {
                return title
            }
        }

        val headingMatch = Regex("<h[1-3][^>]*>([^<]+)</h[1-3]>", RegexOption.IGNORE_CASE).find(html)
        if (headingMatch != null) {
            val heading = unescapeHtml(headingMatch.groupValues[1].trim())
            if (heading.isNotBlank()) {
                return heading
            }
        }
        return null
    }

    private fun htmlToCleanText(rawHtml: String): String {
        // Strip head, script, and style tags completely
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
            .replace(Regex("(?m)^[ \\t]+"), "") // trim leading indentation
            .replace(Regex("\\n{3,}"), "\n\n") // max two newlines
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
