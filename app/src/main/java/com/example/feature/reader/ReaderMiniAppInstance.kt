package com.example.feature.reader

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import com.example.core.LogKeeper
import com.example.data.AppDatabase
import com.example.data.EpubBook
import com.example.feature.floating.MiniAppInstance
import com.example.feature.floating.MiniAppLifecycleState
import com.example.feature.floating.MiniAppRecoverySnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ReaderMiniAppInstance: Mini-app implementation in the Main process hosting the eReader inside
 * Sidebar's existing floating mini-app framework.
 *
 * Implements the complete sleep, warm fold/unfold, and cold-restart recovery contract:
 * - [prepareForSleep]: Marks warm session in [ReaderSessionTracker], flushes latest reading
 *   position and book identity into recovery snapshot.
 * - [releaseRuntime]: Releases view tree, TextToSpeech, auto-scroll handlers, and 21-chapter buffers.
 * - [restoreFromSnapshot]: On warm unfold, restores exact active screen, book, chapter, and scroll
 *   position; on clean launch / cold start ([KEY_COLD_START] == "true" or null snapshot), restores and
 *   opens the last-read book (or opens Library if none exists or binary is missing).
 */
class ReaderMiniAppInstance(
    override val instanceId: String,
    override val appType: String = APP_TYPE,
    var onRequestWindowFocusable: ((Boolean) -> Unit)? = null,
    var onActiveBookChanged: ((EpubBook?) -> Unit)? = null,
    var onChapterTitleChanged: ((String?) -> Unit)? = null,
    var onOpenTracker: ((Int) -> Unit)? = null,
    var onOpenReaderSettings: (() -> Unit)? = null,
    var onReadingToolbarVisibilityChanged: ((Boolean) -> Unit)? = null
) : MiniAppInstance {

    companion object {
        const val APP_TYPE = "reader"
        const val APP_TYPE_EBOOK = "ebook_reader"

        const val KEY_SCREEN_MODE = "screen_mode"
        const val KEY_BOOK_ID = "book_id"
        const val KEY_CHAPTER_INDEX = "chapter_index"
        const val KEY_SCROLL_Y = "scroll_y"
        const val KEY_FONT_SIZE = "font_size_sp"
        const val KEY_COLD_START = "cold_start"
    }

    override var lifecycleState: MiniAppLifecycleState = MiniAppLifecycleState.UNINITIALIZED
        private set

    private val instanceScope = CoroutineScope(SupervisorJob() + ReaderDispatchers.main)

    private var currentScreenMode: ReaderScreenMode = ReaderScreenMode.LIBRARY
    private var currentBookId: Int = -1
    private var currentChapterIndex: Int = 0
    private var currentScrollY: Int = 0
    private var currentFontSizeSp: Float = 16f
    private var pendingAutoOpenLastReadBook: Boolean = true
    private var coldStartSnapshotResume: ColdStartResumeHint? = null

    private data class ColdStartResumeHint(
        val bookId: Int,
        val chapterIndex: Int,
        val scrollY: Int,
        val timestampMs: Long
    )

    private var hostContext: Context? = null
    private var rootContainer: FrameLayout? = null
    private var libraryView: ReaderLibraryView? = null
    private var readingView: ReaderReadingView? = null
    private var folderBrowserView: ReaderFolderBrowserView? = null
    private var openBookJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedSnapshot: MiniAppRecoverySnapshot?) {
        lifecycleState = MiniAppLifecycleState.CREATED
        if (savedSnapshot != null) {
            restoreFromSnapshot(savedSnapshot)
        } else {
            pendingAutoOpenLastReadBook = true
        }
    }

    override fun onStart() {
        lifecycleState = MiniAppLifecycleState.STARTED
    }

    override fun onResume() {
        lifecycleState = MiniAppLifecycleState.RESUMED
        when (currentScreenMode) {
            ReaderScreenMode.LIBRARY -> {
                onReadingToolbarVisibilityChanged?.invoke(false)
                libraryView?.loadBooks()
            }
            ReaderScreenMode.FOLDER_BROWSER -> {
                onReadingToolbarVisibilityChanged?.invoke(false)
                folderBrowserView?.refreshCurrentFolder()
            }
            ReaderScreenMode.READING -> {
                readingView?.refreshPreferences()
                onReadingToolbarVisibilityChanged?.invoke(
                    readingView?.isBottomToolbarVisibleForTesting() == true
                )
            }
        }
    }

    override fun onPause() {
        lifecycleState = MiniAppLifecycleState.PAUSED
        readingView?.saveCurrentProgressImmediately()
    }

    override fun onStop() {
        lifecycleState = MiniAppLifecycleState.STOPPED
    }

    override fun onDestroy() {
        lifecycleState = MiniAppLifecycleState.DESTROYED
        releaseRuntime()
        instanceScope.cancel()
    }

    override fun prepareForSleep(): Map<String, String> {
        lifecycleState = MiniAppLifecycleState.SLEEPING
        ReaderSessionTracker.markWarmSession(instanceId)
        readingView?.saveCurrentProgressImmediately()

        val activeCh = readingView?.currentChapterIndex ?: currentChapterIndex
        val activeScroll = readingView?.currentScrollY ?: currentScrollY
        val activeFont = readingView?.currentFontSizeSp ?: currentFontSizeSp
        currentChapterIndex = activeCh
        currentScrollY = activeScroll
        currentFontSizeSp = activeFont

        val payload = LinkedHashMap<String, String>()
        payload[KEY_SCREEN_MODE] = currentScreenMode.name
        payload[KEY_BOOK_ID] = currentBookId.toString()
        payload[KEY_CHAPTER_INDEX] = activeCh.toString()
        payload[KEY_SCROLL_Y] = activeScroll.toString()
        payload[KEY_FONT_SIZE] = activeFont.toString()
        payload[KEY_COLD_START] = "false"
        payload["app_type"] = appType
        payload["timestamp"] = System.currentTimeMillis().toString()

        val ctx = rootContainer?.context
        if (ctx != null) {
            LogKeeper.log(ctx, "ReaderMiniAppInstance", "PREPARE_SLEEP id=$instanceId mode=$currentScreenMode bookId=$currentBookId ch=$currentChapterIndex")
        } else {
            android.util.Log.d("ReaderMiniAppInstance", "PREPARE_SLEEP id=$instanceId mode=$currentScreenMode bookId=$currentBookId ch=$currentChapterIndex")
        }
        return payload
    }

    override fun releaseRuntime() {
        openBookJob?.cancel()
        readingView?.saveCurrentProgressImmediately()
        com.example.feature.floating.EmbeddedImeBridge.clearFocusRecursive(rootContainer)
        onRequestWindowFocusable?.invoke(false)
        onReadingToolbarVisibilityChanged?.invoke(false)
        libraryView?.releaseResources()
        readingView?.releaseResources()
        folderBrowserView?.releaseResources()
        rootContainer?.removeAllViews()
        rootContainer = null
        hostContext = null
        libraryView = null
        readingView = null
        folderBrowserView = null
    }

    override fun restoreFromSnapshot(snapshot: MiniAppRecoverySnapshot) {
        val payload = snapshot.payload
        val isColdStart = payload[KEY_COLD_START] == "true"

        if (isColdStart) {
            // Cold app/process restart: schedule auto-open of the last-read book once views attach
            pendingAutoOpenLastReadBook = true
            val snapMode = payload[KEY_SCREEN_MODE]
            val snapBookId = payload[KEY_BOOK_ID]?.toIntOrNull() ?: -1
            coldStartSnapshotResume = if (snapMode == ReaderScreenMode.READING.name && snapBookId > 0) {
                ColdStartResumeHint(
                    bookId = snapBookId,
                    chapterIndex = (payload[KEY_CHAPTER_INDEX]?.toIntOrNull() ?: 0).coerceAtLeast(0),
                    scrollY = (payload[KEY_SCROLL_Y]?.toIntOrNull() ?: 0).coerceAtLeast(0),
                    timestampMs = payload["timestamp"]?.toLongOrNull() ?: snapshot.timestamp
                )
            } else {
                null
            }
            currentScreenMode = ReaderScreenMode.LIBRARY
            currentBookId = -1
            currentChapterIndex = 0
            currentScrollY = 0
            val ctx = hostContext
            currentFontSizeSp = if (ctx != null &&
                ctx.getSharedPreferences(ReaderPreferences.PREFS_NAME, Context.MODE_PRIVATE)
                    .contains(ReaderPreferences.KEY_DEFAULT_FONT_SIZE)
            ) {
                ReaderPreferences.getDefaultFontSizeSp(ctx)
            } else {
                payload[KEY_FONT_SIZE]?.toFloatOrNull()?.coerceIn(12f, 28f) ?: 16f
            }
            libraryView?.selectTab(ReaderLibraryTab.RECENT)
        } else {
            pendingAutoOpenLastReadBook = false
            coldStartSnapshotResume = null
            val modeStr = payload[KEY_SCREEN_MODE] ?: ReaderScreenMode.LIBRARY.name
            currentScreenMode = try {
                ReaderScreenMode.valueOf(modeStr)
            } catch (_: Throwable) {
                ReaderScreenMode.LIBRARY
            }
            currentBookId = payload[KEY_BOOK_ID]?.toIntOrNull() ?: -1
            currentChapterIndex = payload[KEY_CHAPTER_INDEX]?.toIntOrNull() ?: 0
            currentScrollY = payload[KEY_SCROLL_Y]?.toIntOrNull() ?: 0
            currentFontSizeSp = payload[KEY_FONT_SIZE]?.toFloatOrNull()?.coerceIn(12f, 28f) ?: 16f
        }

        ReaderSessionTracker.markWarmSession(instanceId)

        val ctx = rootContainer?.context
        if (ctx != null) {
            LogKeeper.log(ctx, "ReaderMiniAppInstance", "RESTORE_SNAPSHOT id=$instanceId cold=$isColdStart mode=$currentScreenMode bookId=$currentBookId ch=$currentChapterIndex")
        } else {
            android.util.Log.d("ReaderMiniAppInstance", "RESTORE_SNAPSHOT id=$instanceId cold=$isColdStart mode=$currentScreenMode bookId=$currentBookId ch=$currentChapterIndex")
        }

        if (rootContainer != null) {
            showCurrentScreen()
            if (isColdStart) {
                tryAutoOpenLastReadBook()
            }
        }
    }

    override fun onSaveState(): Map<String, String> {
        readingView?.saveCurrentProgressImmediately()
        val activeCh = readingView?.currentChapterIndex ?: currentChapterIndex
        val activeScroll = readingView?.currentScrollY ?: currentScrollY
        val activeFont = readingView?.currentFontSizeSp ?: currentFontSizeSp
        currentChapterIndex = activeCh
        currentScrollY = activeScroll
        currentFontSizeSp = activeFont
        return mapOf(
            KEY_SCREEN_MODE to currentScreenMode.name,
            KEY_BOOK_ID to currentBookId.toString(),
            KEY_CHAPTER_INDEX to activeCh.toString(),
            KEY_SCROLL_Y to activeScroll.toString(),
            KEY_FONT_SIZE to activeFont.toString(),
            KEY_COLD_START to if (pendingAutoOpenLastReadBook) "true" else "false",
            "app_type" to appType
        )
    }

    override fun createContentView(context: Context): View {
        hostContext = context
        val prefs = context.getSharedPreferences(ReaderPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        val hasSavedDefaultFont = prefs.contains(ReaderPreferences.KEY_DEFAULT_FONT_SIZE)
        val defaultFont = ReaderPreferences.getDefaultFontSizeSp(context)
        if (pendingAutoOpenLastReadBook && hasSavedDefaultFont) {
            currentFontSizeSp = defaultFont
        } else if (currentFontSizeSp == 16f && defaultFont != 16f) {
            currentFontSizeSp = defaultFont
        }

        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#0B0B10"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootContainer = container

        libraryView = ReaderLibraryView(
            context = context,
            instanceId = instanceId,
            onRequestWindowFocusable = { focusable ->
                onRequestWindowFocusable?.invoke(focusable)
            },
            onOpenFolderBrowser = {
                pendingAutoOpenLastReadBook = false
                openBookJob?.cancel()
                currentScreenMode = ReaderScreenMode.FOLDER_BROWSER
                showCurrentScreen()
            },
            onOpenTracker = { epubId ->
                onOpenTracker?.invoke(epubId)
            },
            onOpenReaderSettings = {
                onOpenReaderSettings?.invoke()
            },
            onBookSelected = { selectedBook ->
                pendingAutoOpenLastReadBook = false
                openBook(selectedBook)
            }
        )

        readingView = ReaderReadingView(
            context = context,
            instanceId = instanceId,
            onRequestWindowFocusable = { focusable ->
                onRequestWindowFocusable?.invoke(focusable)
            },
            onActiveBookChanged = { book ->
                onActiveBookChanged?.invoke(book)
            },
            onChapterTitleChanged = { chapterTitle ->
                onChapterTitleChanged?.invoke(chapterTitle)
            },
            onOpenReaderSettings = {
                onOpenReaderSettings?.invoke()
            },
            onToolbarVisibilityChanged = { visible ->
                val isReadingActive = currentScreenMode == ReaderScreenMode.READING
                onReadingToolbarVisibilityChanged?.invoke(isReadingActive && visible)
            },
            onBackToLibrary = {
                pendingAutoOpenLastReadBook = false
                coldStartSnapshotResume = null
                openBookJob?.cancel()
                currentBookId = -1
                currentChapterIndex = 0
                currentScrollY = 0
                currentScreenMode = ReaderScreenMode.LIBRARY
                onChapterTitleChanged?.invoke(null)
                onActiveBookChanged?.invoke(null)
                onReadingToolbarVisibilityChanged?.invoke(false)
                showCurrentScreen()
            }
        )

        folderBrowserView = ReaderFolderBrowserView(
            context = context,
            instanceId = instanceId,
            onBackToLibrary = {
                pendingAutoOpenLastReadBook = false
                currentScreenMode = ReaderScreenMode.LIBRARY
                showCurrentScreen()
            },
            onBookImported = { importedBook ->
                pendingAutoOpenLastReadBook = false
                openBook(importedBook)
            },
            onRequestWindowFocusable = { focusable ->
                onRequestWindowFocusable?.invoke(focusable)
            }
        )

        showCurrentScreen()
        if (pendingAutoOpenLastReadBook) {
            tryAutoOpenLastReadBook()
        }
        return container
    }

    private fun tryAutoOpenLastReadBook() {
        if (!pendingAutoOpenLastReadBook) return
        val ctx = hostContext ?: return
        if (rootContainer == null) return

        openBookJob?.cancel()
        openBookJob = instanceScope.launch {
            if (!pendingAutoOpenLastReadBook || lifecycleState == MiniAppLifecycleState.DESTROYED) return@launch
            val repo = com.example.data.LibraryRepository(ctx)
            val resolvedBook = withContext(Dispatchers.IO) {
                try {
                    val primary = repo.getLastActiveReaderBook()
                    if (primary != null) {
                        val hint = coldStartSnapshotResume
                        if (hint != null && hint.bookId == primary.id &&
                            hint.timestampMs >= primary.lastActivityTimestamp &&
                            (hint.chapterIndex != primary.lastReadChapter || hint.scrollY != primary.lastReadScrollY)
                        ) {
                            val safeCh = if (primary.totalChapters > 0) {
                                hint.chapterIndex.coerceIn(0, primary.totalChapters - 1)
                            } else {
                                hint.chapterIndex.coerceAtLeast(0)
                            }
                            val safeScroll = hint.scrollY.coerceAtLeast(0)
                            repo.saveResumePosition(primary.id, safeCh, safeScroll, updateOpenRecency = true)
                            primary.copy(
                                lastReadChapter = safeCh,
                                lastReadScrollY = safeScroll,
                                lastActivityTimestamp = hint.timestampMs
                            )
                        } else {
                            primary
                        }
                    } else {
                        // Backward-compatible recovery ONLY if last_active_reader_book_id was unset
                        // and snapshot identifies a valid non-deleted book in Room (never Recent[0])
                        val hint = coldStartSnapshotResume
                        if (hint != null && hint.bookId > 0) {
                            repo.getBook(hint.bookId)?.let { book ->
                                val safeCh = if (book.totalChapters > 0) {
                                    hint.chapterIndex.coerceIn(0, book.totalChapters - 1)
                                } else {
                                    hint.chapterIndex.coerceAtLeast(0)
                                }
                                val safeScroll = hint.scrollY.coerceAtLeast(0)
                                repo.setLastActiveReaderBookId(book.id)
                                if (hint.timestampMs >= book.lastActivityTimestamp) {
                                    repo.saveResumePosition(book.id, safeCh, safeScroll, updateOpenRecency = true)
                                    book.copy(lastReadChapter = safeCh, lastReadScrollY = safeScroll)
                                } else {
                                    book
                                }
                            }
                        } else {
                            null
                        }
                    }
                } catch (_: Throwable) {
                    null
                }
            }
            if (!pendingAutoOpenLastReadBook || lifecycleState == MiniAppLifecycleState.DESTROYED || rootContainer == null) {
                return@launch
            }
            pendingAutoOpenLastReadBook = false
            coldStartSnapshotResume = null
            if (resolvedBook == null) {
                return@launch
            }
            val prefs = ctx.getSharedPreferences(ReaderPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.contains(ReaderPreferences.KEY_DEFAULT_FONT_SIZE)) {
                currentFontSizeSp = ReaderPreferences.getDefaultFontSizeSp(ctx)
            }
            if (repo.hasBookBinary(resolvedBook)) {
                repo.markBookBinaryActive(resolvedBook.id)
                openBook(resolvedBook)
            } else {
                currentScreenMode = ReaderScreenMode.LIBRARY
                currentBookId = -1
                onChapterTitleChanged?.invoke(null)
                onActiveBookChanged?.invoke(null)
                onReadingToolbarVisibilityChanged?.invoke(false)
                libraryView?.setSelectedBookId(resolvedBook.id)
                libraryView?.loadBooks()
                libraryView?.promptReimportForBook(resolvedBook)
            }
        }
    }

    fun clearEmbeddedImeFocus() {
        com.example.feature.floating.EmbeddedImeBridge.clearFocusRecursive(rootContainer)
        onRequestWindowFocusable?.invoke(false)
    }

    /**
     * Returns the currently open [EpubBook.id] when in [ReaderScreenMode.READING], or -1 otherwise.
     */
    fun getCurrentBookId(): Int {
        return if (currentScreenMode == ReaderScreenMode.READING && currentBookId > 0) {
            currentBookId
        } else {
            -1
        }
    }

    /**
     * Opens a specific [EpubBook] by its exact [epubBookId] (e.g., from Book Tracker's "Open in Reader").
     */
    fun openBookById(epubBookId: Int) {
        if (epubBookId <= 0) return
        pendingAutoOpenLastReadBook = false
        ReaderSessionTracker.markWarmSession(instanceId)
        val ctx = hostContext
        val container = rootContainer
        if (container == null || ctx == null) {
            currentBookId = epubBookId
            currentScreenMode = ReaderScreenMode.READING
            return
        }
        openBookJob?.cancel()
        openBookJob = instanceScope.launch {
            val repo = com.example.data.LibraryRepository(ctx)
            val book = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(ctx).epubDao().getBookById(epubBookId)
            }
            if (book != null) {
                if (repo.hasBookBinary(book)) {
                    repo.markBookBinaryActive(book.id)
                    openBook(book)
                } else {
                    currentScreenMode = ReaderScreenMode.LIBRARY
                    currentBookId = -1
                    onActiveBookChanged?.invoke(null)
                    onReadingToolbarVisibilityChanged?.invoke(false)
                    showCurrentScreen()
                    libraryView?.setSelectedBookId(book.id)
                    libraryView?.promptReimportForBook(book)
                }
            }
        }
    }

    private fun openBook(book: EpubBook) {
        pendingAutoOpenLastReadBook = false
        val ctx = hostContext
        val repo = ctx?.let { com.example.data.LibraryRepository(it) }
        if (repo != null && !repo.hasBookBinary(book)) {
            currentScreenMode = ReaderScreenMode.LIBRARY
            currentBookId = -1
            onChapterTitleChanged?.invoke(null)
            onActiveBookChanged?.invoke(null)
            onReadingToolbarVisibilityChanged?.invoke(false)
            showCurrentScreen()
            libraryView?.setSelectedBookId(book.id)
            libraryView?.promptReimportForBook(book)
            return
        }
        repo?.setLastActiveReaderBookId(book.id)
        ReaderSessionTracker.markWarmSession(instanceId)
        currentBookId = book.id
        currentChapterIndex = book.lastReadChapter
        currentScrollY = book.lastReadScrollY
        currentScreenMode = ReaderScreenMode.READING
        onActiveBookChanged?.invoke(book)

        showCurrentScreen(alreadyLoadedBook = book)
    }

    private fun showCurrentScreen(alreadyLoadedBook: EpubBook? = null) {
        val container = rootContainer ?: return
        val ctx = hostContext ?: return
        com.example.feature.floating.EmbeddedImeBridge.clearFocusRecursive(container)
        onRequestWindowFocusable?.invoke(false)
        container.removeAllViews()

        when {
            currentScreenMode == ReaderScreenMode.READING && currentBookId > 0 -> {
                val rView = readingView ?: return
                container.addView(rView)
                onReadingToolbarVisibilityChanged?.invoke(rView.isBottomToolbarVisibleForTesting())

                if (alreadyLoadedBook != null && alreadyLoadedBook.id == currentBookId) {
                    onActiveBookChanged?.invoke(alreadyLoadedBook)
                    rView.loadBook(alreadyLoadedBook, currentChapterIndex, currentScrollY, currentFontSizeSp)
                } else {
                    openBookJob?.cancel()
                    openBookJob = instanceScope.launch {
                        val repo = com.example.data.LibraryRepository(ctx)
                        val book = withContext(Dispatchers.IO) {
                            AppDatabase.getDatabase(ctx).epubDao().getBookById(currentBookId)
                        }
                        if (book != null && repo.hasBookBinary(book)) {
                            repo.markBookBinaryActive(book.id)
                            repo.setLastActiveReaderBookId(book.id)
                            onActiveBookChanged?.invoke(book)
                            rView.loadBook(book, currentChapterIndex, currentScrollY, currentFontSizeSp)
                        } else {
                            currentScreenMode = ReaderScreenMode.LIBRARY
                            currentBookId = -1
                            onChapterTitleChanged?.invoke(null)
                            onActiveBookChanged?.invoke(null)
                            onReadingToolbarVisibilityChanged?.invoke(false)
                            showCurrentScreen()
                            if (book != null) {
                                libraryView?.setSelectedBookId(book.id)
                                libraryView?.promptReimportForBook(book)
                            }
                        }
                    }
                }
            }
            currentScreenMode == ReaderScreenMode.FOLDER_BROWSER -> {
                onChapterTitleChanged?.invoke(null)
                onActiveBookChanged?.invoke(null)
                onReadingToolbarVisibilityChanged?.invoke(false)
                val fView = folderBrowserView ?: return
                container.addView(fView)
                fView.loadRootFolder()
            }
            else -> {
                onChapterTitleChanged?.invoke(null)
                onActiveBookChanged?.invoke(null)
                onReadingToolbarVisibilityChanged?.invoke(false)
                val lView = libraryView ?: return
                container.addView(lView)
                lView.loadBooks()
            }
        }
    }

    // Testing helpers
    suspend fun awaitPendingJobsForTesting() {
        openBookJob?.join()
    }
    fun getLibraryViewForTesting(): ReaderLibraryView? = libraryView
    fun getReadingViewForTesting(): ReaderReadingView? = readingView
    fun getFolderBrowserViewForTesting(): ReaderFolderBrowserView? = folderBrowserView
    fun getCurrentBookIdForTesting(): Int = currentBookId
    fun getCurrentScreenModeForTesting(): ReaderScreenMode = currentScreenMode
}
