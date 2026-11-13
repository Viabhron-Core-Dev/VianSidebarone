package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.core.LogKeeper
import com.example.feature.reader.ReaderEventManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.max

/**
 * Result representation for in-book search matches.
 */
data class BookSearchResult(
    val chapterIndex: Int,
    val chapterTitle: String,
    val snippet: String,
    val matchOffset: Int
)

/**
 * Represents a genuine high-water reading progress advancement that can be undone by the user.
 */
data class HighWaterProgressUpdate(
    val trackerBookId: Int,
    val epubBookId: Int,
    val previousReadChapters: Int,
    val newReadChapters: Int
)

/**
 * LibraryRepository: Single authoritative coordinator for eReader library operations,
 * book persistence in Room ("litereader_db"), Tracker high-water vs Reader resume separation,
 * and rolling chapter access.
 */
class LibraryRepository(private val context: Context) {
    private val db by lazy { AppDatabase.getDatabase(context) }
    private val dao by lazy { db.epubDao() }
    private val trackerDao by lazy { db.trackerDao() }

    companion object {
        private const val TAG = "LibraryRepository"
        val persistentIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun computeFileDigestPrefix(file: File, maxBytes: Int = 65536): String {
            if (!file.exists() || !file.isFile) return ""
            return try {
                val md = MessageDigest.getInstance("SHA-256")
                md.update(file.length().toString().toByteArray(Charsets.UTF_8))
                val buffer = ByteArray(8192)
                var remaining = maxBytes
                file.inputStream().use { input ->
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size, remaining)
                        val read = input.read(buffer, 0, toRead)
                        if (read <= 0) break
                        md.update(buffer, 0, read)
                        remaining -= read
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) }.take(24)
            } catch (_: Throwable) {
                "${file.length()}"
            }
        }
    }

    /**
     * Returns all imported books ordered by addedTimestamp DESC (for All Books view).
     */
    suspend fun getAllBooks(): List<EpubBook> = withContext(Dispatchers.IO) {
        dao.getAllBooksByAddedDesc()
    }

    /**
     * Backward-compatible alias returning all books.
     */
    suspend fun getBooks(): List<EpubBook> = withContext(Dispatchers.IO) {
        dao.getAllBooks()
    }

    /**
     * Returns at most the last 10 distinct opened Reader books (`lastOpenedTimestamp > 0`),
     * ordered strictly by actual Reader open/read recency (`lastOpenedTimestamp DESC, id DESC`).
     * Never ordered by Tracker metadata edit time.
     */
    suspend fun getRecentBooks(): List<EpubBook> = withContext(Dispatchers.IO) {
        dao.getRecentBooks()
    }

    suspend fun getBook(id: Int): EpubBook? = withContext(Dispatchers.IO) {
        dao.getBookById(id)
    }

    suspend fun updateBook(book: EpubBook) = withContext(Dispatchers.IO) {
        dao.updateBook(book)
        ReaderEventManager.notifyLibraryChanged(context)
    }

    /**
     * Deletes an EpubBook and its chapter cache while unlinking (never deleting) any linked TrackerBook.
     */
    suspend fun deleteBook(book: EpubBook) = withContext(Dispatchers.IO) {
        trackerDao.unlinkEpubBook(book.id)
        dao.deleteBook(book)
        EpubParser.releaseInMemoryWindow(book.id)
        val bookDir = File(context.filesDir, "book_${book.id}")
        bookDir.deleteRecursively()
        val origFile = File(book.filePath)
        if (origFile.exists()) origFile.delete()
        LogKeeper.log(context, TAG, "BOOK_DELETED id=${book.id} title='${book.title}' (Tracker unlinked)")
        ReaderEventManager.notifyLibraryChanged(context)
    }

    /**
     * Retrieves or creates the linked TrackerBook for an EpubBook strictly by `linkedEpubBookId`.
     * Never links or merges by title alone.
     */
    suspend fun getOrCreateLinkedTrackerBook(epubBookId: Int): TrackerBook? = withContext(Dispatchers.IO) {
        val existingLinked = trackerDao.getByLinkedEpubId(epubBookId)
        if (existingLinked != null) return@withContext existingLinked

        val epub = dao.getBookById(epubBookId) ?: return@withContext null
        val now = System.currentTimeMillis()
        val created = TrackerBook(
            title = epub.title,
            author = epub.author,
            totalChapters = epub.totalChapters,
            readChapters = 0,
            isFinished = false,
            isWebNovel = false,
            genres = "",
            rating = 0f,
            comment = "",
            addedTimestamp = epub.addedTimestamp.takeIf { it > 0L } ?: now,
            lastUpdatedTimestamp = now,
            linkedEpubBookId = epub.id,
            lastReadingActivityTimestamp = epub.lastActivityTimestamp,
            lastMetadataEditTimestamp = now
        )
        val newId = trackerDao.insertBook(created).toInt()
        created.copy(id = newId)
    }

    suspend fun getLinkedTrackerBook(epubBookId: Int): TrackerBook? = withContext(Dispatchers.IO) {
        trackerDao.getByLinkedEpubId(epubBookId)
    }

    /**
     * Updates Tracker metadata from the Tracker editor without altering Reader `lastOpenedTimestamp`
     * or Reader resume position (`lastReadChapter`, `lastReadScrollY`).
     */
    suspend fun saveTrackerMetadataEdit(trackerBook: TrackerBook): TrackerBook = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val updated = trackerBook.copy(
            lastMetadataEditTimestamp = now,
            lastUpdatedTimestamp = now
        )
        val finalTracker = if (updated.id == 0) {
            val id = trackerDao.insertBook(updated).toInt()
            updated.copy(id = id)
        } else {
            trackerDao.updateBook(updated)
            updated
        }

        // Sync display title/author to linked EpubBook WITHOUT changing lastOpenedTimestamp or resume chapter/scroll
        val linkedId = finalTracker.linkedEpubBookId
        if (linkedId != null) {
            val epub = dao.getBookById(linkedId)
            if (epub != null && (epub.title != finalTracker.title || epub.author != finalTracker.author)) {
                dao.updateBook(
                    epub.copy(
                        title = finalTracker.title,
                        author = finalTracker.author
                    )
                )
            }
        }
        ReaderEventManager.notifyLibraryChanged(context)
        finalTracker
    }

    /**
     * Imports a book from SAF / Storage Access Framework Uri.
     */
    suspend fun importBook(uri: Uri, markOpenedOnImport: Boolean = false): EpubBook? = withContext(Dispatchers.IO) {
        val rawName = getFileName(uri) ?: "Unknown Book"
        val mimeType = context.contentResolver.getType(uri)
        val isTxt = rawName.lowercase().endsWith(".txt") || mimeType?.startsWith("text/") == true
        val baseTitle = rawName.removeSuffix(".epub").removeSuffix(".EPUB").removeSuffix(".txt").removeSuffix(".TXT")

        try {
            val stream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            stream.use { inputStream ->
                importFromInputStream(inputStream, isTxt, baseTitle, markOpenedOnImport)
            }
        } catch (e: Exception) {
            LogKeeper.log(context, TAG, "Failed to import from Uri: ${e.message}")
            null
        }
    }

    /**
     * Imports a book from a local File.
     */
    suspend fun importBookFile(
        file: File,
        displayTitle: String? = null,
        markOpenedOnImport: Boolean = false
    ): EpubBook? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        val rawName = file.name
        val isTxt = rawName.lowercase().endsWith(".txt")
        val baseTitle = displayTitle ?: rawName.removeSuffix(".epub").removeSuffix(".EPUB").removeSuffix(".txt").removeSuffix(".TXT")

        try {
            file.inputStream().use { inputStream ->
                importFromInputStream(inputStream, isTxt, baseTitle, markOpenedOnImport)
            }
        } catch (e: Exception) {
            LogKeeper.log(context, TAG, "Failed to import from File: ${e.message}")
            null
        }
    }

    private suspend fun importFromInputStream(
        inputStream: InputStream,
        isTxt: Boolean,
        defaultTitle: String,
        markOpenedOnImport: Boolean
    ): EpubBook? {
        val ext = if (isTxt) "txt" else "epub"
        val now = System.currentTimeMillis()
        val destFile = File(context.filesDir, "imported_${now}_${System.nanoTime() % 100000}.$ext")

        FileOutputStream(destFile).use { output ->
            inputStream.copyTo(output)
        }

        val incomingDigest = computeFileDigestPrefix(destFile)
        // Only deduplicate if an existing book has the exact same binary file size AND digest prefix
        val allBooks = dao.getAllBooks()
        val existingExactBinary = allBooks.find { existing ->
            val f = File(existing.filePath)
            f.exists() && f.length() == destFile.length() && computeFileDigestPrefix(f) == incomingDigest
        }
        if (existingExactBinary != null) {
            destFile.delete()
            LogKeeper.log(context, TAG, "IMPORT_EXACT_BINARY_DUPLICATE reusing id=${existingExactBinary.id}")
            return existingExactBinary
        }

        val initialBook = EpubBook(
            title = defaultTitle,
            filePath = destFile.absolutePath,
            addedTimestamp = now,
            lastOpenedTimestamp = if (markOpenedOnImport) now else 0L
        )
        val id = dao.insertBook(initialBook).toInt()
        val finalBook = initialBook.copy(id = id)

        var finalTitle = defaultTitle
        var finalAuthor = ""
        val totalChapters = if (isTxt) {
            EpubParser.parseTxtToText(context, id, destFile)
        } else {
            val parseResult = EpubParser.parseEpub(context, id, destFile)
            if (!parseResult.title.isNullOrBlank()) {
                finalTitle = parseResult.title
            }
            if (!parseResult.author.isNullOrBlank()) {
                finalAuthor = parseResult.author
            }
            parseResult.chapterCount
        }

        if (totalChapters <= 0) {
            LogKeeper.log(context, TAG, "BOOK_IMPORT_FAILED_CLEANUP id=$id chapters=$totalChapters title='$finalTitle'")
            try { dao.deleteBook(finalBook) } catch (_: Throwable) {}
            try { destFile.delete() } catch (_: Throwable) {}
            try { File(context.filesDir, "book_$id").deleteRecursively() } catch (_: Throwable) {}
            return null
        }

        val readyBook = finalBook.copy(
            title = finalTitle,
            author = finalAuthor,
            totalChapters = totalChapters,
            isParsed = true
        )
        dao.updateBook(readyBook)

        // Create linked TrackerBook by explicit linkedEpubBookId (never matching by title)
        getOrCreateLinkedTrackerBook(readyBook.id)

        LogKeeper.log(context, TAG, "BOOK_IMPORTED id=$id title='$finalTitle' author='$finalAuthor' chapters=$totalChapters")
        ReaderEventManager.notifyLibraryChanged(context)
        return readyBook
    }

    /**
     * Resolves the text content of a chapter and maintains the 21-chapter rolling window around [chapterIndex].
     */
    fun getChapterText(bookId: Int, chapterIndex: Int): String {
        return EpubParser.getChapterText(context, bookId, chapterIndex)
    }

    /**
     * Explicitly updates the 21-chapter rolling cache window around [centerChapterIndex].
     */
    fun updateRollingWindow(bookId: Int, centerChapterIndex: Int): IntRange {
        return EpubParser.updateRollingWindow(context, bookId, centerChapterIndex)
    }

    /**
     * Retrieves the chapter metadata list for Table of Contents (`toc.json`).
     */
    fun getChapters(bookId: Int): List<ChapterMeta> {
        return EpubParser.getChapterList(context, bookId)
    }

    /**
     * Saves the Reader Resume Position (`lastReadChapter` 0-based and `lastReadScrollY`) on `EpubBook`.
     *
     * CRITICAL: This updates ONLY the Reader resume position and (when [updateOpenRecency] is true)
     * `lastOpenedTimestamp`. It NEVER advances `TrackerBook.readChapters`!
     * Jumping via TOC or search results calls this method so resume position updates without inflating
     * high-water Tracker progress.
     */
    suspend fun saveResumePosition(
        bookId: Int,
        chapterIndex: Int,
        scrollY: Int,
        updateOpenRecency: Boolean = true
    ) = withContext(NonCancellable + Dispatchers.IO) {
        val existing = dao.getBookById(bookId) ?: return@withContext
        val now = System.currentTimeMillis()
        val safeChapter = if (existing.totalChapters > 0) {
            chapterIndex.coerceIn(0, existing.totalChapters - 1)
        } else {
            chapterIndex.coerceAtLeast(0)
        }
        val updated = existing.copy(
            lastReadChapter = safeChapter,
            lastReadScrollY = scrollY.coerceAtLeast(0),
            lastOpenedTimestamp = if (updateOpenRecency) now else existing.lastOpenedTimestamp,
            lastActivityTimestamp = now
        )
        dao.updateBook(updated)
    }

    /**
     * Backward-compatible method delegating to [saveResumePosition].
     */
    suspend fun saveReadingProgress(bookId: Int, chapterIndex: Int, scrollY: Int) {
        saveResumePosition(bookId, chapterIndex, scrollY, updateOpenRecency = true)
    }

    fun saveReadingProgressAsync(bookId: Int, chapterIndex: Int, scrollY: Int, updateOpenRecency: Boolean = true) {
        persistentIoScope.launch {
            try {
                saveResumePosition(bookId, chapterIndex, scrollY, updateOpenRecency)
            } catch (t: Throwable) {
                LogKeeper.log(context, TAG, "Failed to persist resume position id=$bookId: ${t.message}")
            }
        }
    }

    /**
     * Records genuine reading activity in [currentChapterZeroBased].
     *
     * Rules:
     * 1. Converts 0-based `currentChapterZeroBased` to 1-based chapter count `reached = currentChapterZeroBased + 1`.
     * 2. Advances `TrackerBook.readChapters` ONLY if `reached > tracker.readChapters`.
     * 3. Never decreases `TrackerBook.readChapters` when revisiting an earlier chapter.
     * 4. Returns a [HighWaterProgressUpdate] when `readChapters` increased so the Reader UI can offer a quick Undo.
     */
    suspend fun recordGenuineReadingProgress(
        bookId: Int,
        currentChapterZeroBased: Int
    ): HighWaterProgressUpdate? = withContext(NonCancellable + Dispatchers.IO) {
        val epub = dao.getBookById(bookId) ?: return@withContext null
        val oneBasedReached = (currentChapterZeroBased + 1).coerceAtLeast(1)
        val clampedReached = if (epub.totalChapters > 0) {
            oneBasedReached.coerceAtMost(epub.totalChapters)
        } else {
            oneBasedReached
        }

        val tracker = getOrCreateLinkedTrackerBook(bookId) ?: return@withContext null
        val now = System.currentTimeMillis()
        dao.updateBook(
            epub.copy(
                lastOpenedTimestamp = now,
                lastActivityTimestamp = now
            )
        )

        if (clampedReached > tracker.readChapters) {
            val previous = tracker.readChapters
            val updatedTracker = tracker.copy(
                readChapters = clampedReached,
                totalChapters = max(tracker.totalChapters, epub.totalChapters),
                lastReadingActivityTimestamp = now,
                lastUpdatedTimestamp = now
            )
            trackerDao.updateBook(updatedTracker)
            ReaderEventManager.notifyLibraryChanged(context)
            return@withContext HighWaterProgressUpdate(
                trackerBookId = tracker.id,
                epubBookId = bookId,
                previousReadChapters = previous,
                newReadChapters = clampedReached
            )
        }
        return@withContext null
    }

    /**
     * Reverts a recent high-water progress update back to `update.previousReadChapters`
     * without changing the Reader's resume chapter or scroll position.
     */
    suspend fun undoHighWaterProgress(update: HighWaterProgressUpdate): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        val tracker = trackerDao.getBookById(update.trackerBookId)
            ?: trackerDao.getByLinkedEpubId(update.epubBookId)
            ?: return@withContext false
        val reverted = tracker.copy(
            readChapters = update.previousReadChapters.coerceAtLeast(0),
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        trackerDao.updateBook(reverted)
        ReaderEventManager.notifyLibraryChanged(context)
        true
    }

    /**
     * Searches across all chapters in a book using transient on-demand chapter streaming
     * so chapters outside the active 21-chapter rolling window are released immediately after scanning.
     */
    suspend fun searchInBook(bookId: Int, query: String): List<BookSearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val results = mutableListOf<BookSearchResult>()
        val chapters = getChapters(bookId)
        val cleanQuery = query.lowercase().trim()

        for (ch in chapters) {
            val text = EpubParser.readChapterTextTransient(context, bookId, ch.index)
            if (text.isBlank()) continue
            val lowerText = text.lowercase()

            var startIndex = 0
            var matchCountInChapter = 0
            while (startIndex < lowerText.length) {
                val matchIdx = lowerText.indexOf(cleanQuery, startIndex)
                if (matchIdx == -1 || matchCountInChapter >= 5) break

                val snippetStart = (matchIdx - 30).coerceAtLeast(0)
                val snippetEnd = (matchIdx + cleanQuery.length + 30).coerceAtMost(text.length)
                val snippet = "..." + text.substring(snippetStart, snippetEnd).replace('\n', ' ') + "..."

                results.add(
                    BookSearchResult(
                        chapterIndex = ch.index,
                        chapterTitle = ch.title,
                        snippet = snippet,
                        matchOffset = matchIdx
                    )
                )
                matchCountInChapter++
                startIndex = matchIdx + cleanQuery.length
            }
        }
        results
    }

    private fun getFileName(uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.lastPathSegment
        }
        var name: String? = null
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1) name = it.getString(idx)
            }
        }
        return name
    }
}
