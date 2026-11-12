package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.core.LogKeeper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

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
 * LibraryRepository: Single authoritative coordinator for eReader library operations,
 * book persistence in Room ("litereader_db"), and chapter access.
 */
class LibraryRepository(private val context: Context) {
    private val dao = AppDatabase.getDatabase(context).epubDao()

    companion object {
        private const val TAG = "LibraryRepository"
        val persistentIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    suspend fun getBooks(): List<EpubBook> = withContext(Dispatchers.IO) {
        dao.getAllBooks()
    }

    suspend fun getBook(id: Int): EpubBook? = withContext(Dispatchers.IO) {
        dao.getBookById(id)
    }

    suspend fun updateBook(book: EpubBook) = withContext(Dispatchers.IO) {
        dao.updateBook(book)
    }

    suspend fun deleteBook(book: EpubBook) = withContext(Dispatchers.IO) {
        dao.deleteBook(book)
        val bookDir = File(context.filesDir, "book_${book.id}")
        bookDir.deleteRecursively()
        val origFile = File(book.filePath)
        if (origFile.exists()) origFile.delete()
        LogKeeper.log(context, TAG, "BOOK_DELETED id=${book.id} title='${book.title}'")
    }

    /**
     * Imports a book from SAF / Storage Access Framework Uri.
     */
    suspend fun importBook(uri: Uri): EpubBook? = withContext(Dispatchers.IO) {
        val rawName = getFileName(uri) ?: "Unknown Book"
        val mimeType = context.contentResolver.getType(uri)
        val isTxt = rawName.lowercase().endsWith(".txt") || mimeType?.startsWith("text/") == true
        val baseTitle = rawName.removeSuffix(".epub").removeSuffix(".txt")

        try {
            val stream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            stream.use { inputStream ->
                importFromInputStream(inputStream, isTxt, baseTitle)
            }
        } catch (e: Exception) {
            LogKeeper.log(context, TAG, "Failed to import from Uri: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Imports a book from a local File.
     */
    suspend fun importBookFile(file: File, displayTitle: String? = null): EpubBook? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        val rawName = file.name
        val isTxt = rawName.lowercase().endsWith(".txt")
        val baseTitle = displayTitle ?: rawName.removeSuffix(".epub").removeSuffix(".txt")

        try {
            file.inputStream().use { inputStream ->
                importFromInputStream(inputStream, isTxt, baseTitle)
            }
        } catch (e: Exception) {
            LogKeeper.log(context, TAG, "Failed to import from File: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private suspend fun importFromInputStream(inputStream: InputStream, isTxt: Boolean, defaultTitle: String): EpubBook? {
        val ext = if (isTxt) "txt" else "epub"
        val destFile = File(context.filesDir, "imported_${System.currentTimeMillis()}.$ext")

        FileOutputStream(destFile).use { output ->
            inputStream.copyTo(output)
        }

        // Check if an existing book already has this exact title and file size to avoid duplicates
        val allBooks = dao.getAllBooks()
        val existingDuplicate = allBooks.find {
            it.title.equals(defaultTitle, ignoreCase = true) &&
                    File(it.filePath).exists() &&
                    File(it.filePath).length() == destFile.length()
        }
        if (existingDuplicate != null) {
            destFile.delete() // Don't keep duplicate on disk
            LogKeeper.log(context, TAG, "IMPORT_DUPLICATE_DETECTED reusing existing id=${existingDuplicate.id}")
            return existingDuplicate
        }

        val book = EpubBook(
            title = defaultTitle,
            filePath = destFile.absolutePath,
            addedTimestamp = System.currentTimeMillis(),
            lastOpenedTimestamp = System.currentTimeMillis()
        )
        val id = dao.insertBook(book).toInt()
        val finalBook = book.copy(id = id)

        // Parse book chapters
        var finalTitle = defaultTitle
        val totalChapters = if (isTxt) {
            EpubParser.parseTxtToText(context, id, destFile)
        } else {
            val parseResult = EpubParser.parseEpub(context, id, destFile)
            if (!parseResult.title.isNullOrBlank()) {
                finalTitle = parseResult.title
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
            totalChapters = totalChapters,
            isParsed = true
        )
        dao.updateBook(readyBook)
        LogKeeper.log(context, TAG, "BOOK_IMPORTED id=$id title='$finalTitle' chapters=$totalChapters")
        return readyBook
    }

    /**
     * Resolves the text content of a chapter.
     */
    fun getChapterText(bookId: Int, chapterIndex: Int): String {
        return EpubParser.getChapterText(context, bookId, chapterIndex)
    }

    /**
     * Retrieves the chapter metadata list for Table of Contents.
     */
    fun getChapters(bookId: Int): List<ChapterMeta> {
        return EpubParser.getChapterList(context, bookId)
    }

    /**
     * Updates reading progress for an open book safely without cancellation loss.
     */
    suspend fun saveReadingProgress(bookId: Int, chapterIndex: Int, scrollY: Int) = withContext(NonCancellable + Dispatchers.IO) {
        val existing = dao.getBookById(bookId) ?: return@withContext
        val updated = existing.copy(
            lastReadChapter = chapterIndex,
            lastReadScrollY = scrollY,
            lastOpenedTimestamp = System.currentTimeMillis()
        )
        dao.updateBook(updated)
    }

    fun saveReadingProgressAsync(bookId: Int, chapterIndex: Int, scrollY: Int) {
        persistentIoScope.launch {
            try {
                saveReadingProgress(bookId, chapterIndex, scrollY)
            } catch (t: Throwable) {
                LogKeeper.log(context, TAG, "Failed to persist reading progress id=$bookId: ${t.message}")
            }
        }
    }

    /**
     * Searches across all chapters in a book, returning snippet matches.
     */
    suspend fun searchInBook(bookId: Int, query: String): List<BookSearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val results = mutableListOf<BookSearchResult>()
        val chapters = getChapters(bookId)
        val cleanQuery = query.lowercase().trim()

        for (ch in chapters) {
            val text = getChapterText(bookId, ch.index)
            if (text.isBlank()) continue
            val lowerText = text.lowercase()

            var startIndex = 0
            var matchCountInChapter = 0
            while (startIndex < lowerText.length) {
                val matchIdx = lowerText.indexOf(cleanQuery, startIndex)
                if (matchIdx == -1 || matchCountInChapter >= 5) break // Max 5 matches per chapter to keep fast

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
