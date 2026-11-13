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
 * - [restoreFromSnapshot]: On warm unfold, restores active book, chapter, scroll position, and
 *   settings; on cold process restart ([KEY_COLD_START] == "true"), opens Library (Recent tab).
 */
class ReaderMiniAppInstance(
    override val instanceId: String,
    override val appType: String = APP_TYPE,
    var onRequestWindowFocusable: ((Boolean) -> Unit)? = null,
    var onActiveBookChanged: ((EpubBook?) -> Unit)? = null
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

    private var rootContainer: FrameLayout? = null
    private var libraryView: ReaderLibraryView? = null
    private var readingView: ReaderReadingView? = null
    private var folderBrowserView: ReaderFolderBrowserView? = null

    override fun onCreate(savedSnapshot: MiniAppRecoverySnapshot?) {
        lifecycleState = MiniAppLifecycleState.CREATED
        savedSnapshot?.let { restoreFromSnapshot(it) }
    }

    override fun onStart() {
        lifecycleState = MiniAppLifecycleState.STARTED
    }

    override fun onResume() {
        lifecycleState = MiniAppLifecycleState.RESUMED
        when (currentScreenMode) {
            ReaderScreenMode.LIBRARY -> libraryView?.loadBooks()
            ReaderScreenMode.FOLDER_BROWSER -> folderBrowserView?.refreshCurrentFolder()
            ReaderScreenMode.READING -> {
                readingView?.refreshPreferences()
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
        readingView?.saveCurrentProgressImmediately()
        com.example.feature.floating.EmbeddedImeBridge.clearFocusRecursive(rootContainer)
        onRequestWindowFocusable?.invoke(false)
        libraryView?.releaseResources()
        readingView?.releaseResources()
        folderBrowserView?.releaseResources()
        rootContainer?.removeAllViews()
        rootContainer = null
        libraryView = null
        readingView = null
        folderBrowserView = null
    }

    override fun restoreFromSnapshot(snapshot: MiniAppRecoverySnapshot) {
        val payload = snapshot.payload
        val isColdStart = payload[KEY_COLD_START] == "true"

        if (isColdStart) {
            // Cold app/process restart: open Library (Recent tab) by default rather than auto-opening a book
            currentScreenMode = ReaderScreenMode.LIBRARY
            currentBookId = -1
            currentChapterIndex = 0
            currentScrollY = 0
            currentFontSizeSp = payload[KEY_FONT_SIZE]?.toFloatOrNull() ?: 16f
            libraryView?.selectTab(ReaderLibraryTab.RECENT)
        } else {
            val modeStr = payload[KEY_SCREEN_MODE] ?: ReaderScreenMode.LIBRARY.name
            currentScreenMode = try {
                ReaderScreenMode.valueOf(modeStr)
            } catch (_: Throwable) {
                ReaderScreenMode.LIBRARY
            }
            currentBookId = payload[KEY_BOOK_ID]?.toIntOrNull() ?: -1
            currentChapterIndex = payload[KEY_CHAPTER_INDEX]?.toIntOrNull() ?: 0
            currentScrollY = payload[KEY_SCROLL_Y]?.toIntOrNull() ?: 0
            currentFontSizeSp = payload[KEY_FONT_SIZE]?.toFloatOrNull() ?: 16f
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
            KEY_COLD_START to "false",
            "app_type" to appType
        )
    }

    override fun createContentView(context: Context): View {
        val defaultFont = ReaderPreferences.getDefaultFontSizeSp(context)
        if (currentFontSizeSp == 16f && defaultFont != 16f) {
            currentFontSizeSp = defaultFont
        }

        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#181825"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootContainer = container

        libraryView = ReaderLibraryView(
            context = context,
            instanceId = instanceId,
            onOpenFolderBrowser = {
                currentScreenMode = ReaderScreenMode.FOLDER_BROWSER
                showCurrentScreen()
            },
            onBookSelected = { selectedBook ->
                openBook(selectedBook)
            },
            onRequestWindowFocusable = { focusable ->
                onRequestWindowFocusable?.invoke(focusable)
            }
        )

        readingView = ReaderReadingView(
            context = context,
            instanceId = instanceId,
            onRequestWindowFocusable = { focusable ->
                onRequestWindowFocusable?.invoke(focusable)
            },
            onBackToLibrary = {
                currentScreenMode = ReaderScreenMode.LIBRARY
                onActiveBookChanged?.invoke(null)
                showCurrentScreen()
            }
        )

        folderBrowserView = ReaderFolderBrowserView(
            context = context,
            instanceId = instanceId,
            onBackToLibrary = {
                currentScreenMode = ReaderScreenMode.LIBRARY
                showCurrentScreen()
            },
            onBookImported = { importedBook ->
                openBook(importedBook)
            },
            onRequestWindowFocusable = { focusable ->
                onRequestWindowFocusable?.invoke(focusable)
            }
        )

        showCurrentScreen()
        return container
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
        ReaderSessionTracker.markWarmSession(instanceId)
        val container = rootContainer
        if (container == null) {
            currentBookId = epubBookId
            currentScreenMode = ReaderScreenMode.READING
            return
        }
        instanceScope.launch {
            val book = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(container.context).epubDao().getBookById(epubBookId)
            }
            if (book != null) {
                openBook(book)
            }
        }
    }

    private fun openBook(book: EpubBook) {
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
        com.example.feature.floating.EmbeddedImeBridge.clearFocusRecursive(container)
        onRequestWindowFocusable?.invoke(false)
        container.removeAllViews()

        when {
            currentScreenMode == ReaderScreenMode.READING && currentBookId > 0 -> {
                val rView = readingView ?: return
                container.addView(rView)

                if (alreadyLoadedBook != null && alreadyLoadedBook.id == currentBookId) {
                    onActiveBookChanged?.invoke(alreadyLoadedBook)
                    rView.loadBook(alreadyLoadedBook, currentChapterIndex, currentScrollY, currentFontSizeSp)
                } else {
                    instanceScope.launch {
                        val book = withContext(Dispatchers.IO) {
                            AppDatabase.getDatabase(container.context).epubDao().getBookById(currentBookId)
                        }
                        if (book != null) {
                            onActiveBookChanged?.invoke(book)
                            rView.loadBook(book, currentChapterIndex, currentScrollY, currentFontSizeSp)
                        } else {
                            currentScreenMode = ReaderScreenMode.LIBRARY
                            onActiveBookChanged?.invoke(null)
                            showCurrentScreen()
                        }
                    }
                }
            }
            currentScreenMode == ReaderScreenMode.FOLDER_BROWSER -> {
                onActiveBookChanged?.invoke(null)
                val fView = folderBrowserView ?: return
                container.addView(fView)
                fView.loadRootFolder()
            }
            else -> {
                onActiveBookChanged?.invoke(null)
                val lView = libraryView ?: return
                container.addView(lView)
                lView.loadBooks()
            }
        }
    }

    // Testing helpers
    fun getLibraryViewForTesting(): ReaderLibraryView? = libraryView
    fun getReadingViewForTesting(): ReaderReadingView? = readingView
    fun getFolderBrowserViewForTesting(): ReaderFolderBrowserView? = folderBrowserView
    fun getCurrentBookIdForTesting(): Int = currentBookId
    fun getCurrentScreenModeForTesting(): ReaderScreenMode = currentScreenMode
}
