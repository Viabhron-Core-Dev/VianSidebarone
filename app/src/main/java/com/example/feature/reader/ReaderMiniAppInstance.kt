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

/**
 * ReaderMiniAppInstance: Mini-app implementation in :heavy hosting the eReader inside
 * Sidebar's existing floating mini-app framework.
 *
 * Implements the complete sleep and recovery contract:
 * - [prepareForSleep]: Flushes latest reading position and book identity into recovery snapshot.
 * - [releaseRuntime]: Releases view tree, TextToSpeech, and auto-scroll handlers.
 * - [restoreFromSnapshot]: Restores active book, chapter, scroll position, and library view.
 */
class ReaderMiniAppInstance(
    override val instanceId: String,
    override val appType: String = APP_TYPE
) : MiniAppInstance {

    companion object {
        const val APP_TYPE = "reader"
        const val APP_TYPE_EBOOK = "ebook_reader"

        const val KEY_SCREEN_MODE = "screen_mode"
        const val KEY_BOOK_ID = "book_id"
        const val KEY_CHAPTER_INDEX = "chapter_index"
        const val KEY_SCROLL_Y = "scroll_y"
        const val KEY_FONT_SIZE = "font_size_sp"
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

    override fun onCreate(savedSnapshot: MiniAppRecoverySnapshot?) {
        lifecycleState = MiniAppLifecycleState.CREATED
        savedSnapshot?.let { restoreFromSnapshot(it) }
    }

    override fun onStart() {
        lifecycleState = MiniAppLifecycleState.STARTED
    }

    override fun onResume() {
        lifecycleState = MiniAppLifecycleState.RESUMED
        if (currentScreenMode == ReaderScreenMode.LIBRARY) {
            libraryView?.loadBooks()
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
        readingView?.saveCurrentProgressImmediately()

        val payload = LinkedHashMap<String, String>()
        payload[KEY_SCREEN_MODE] = currentScreenMode.name
        payload[KEY_BOOK_ID] = currentBookId.toString()
        payload[KEY_CHAPTER_INDEX] = (readingView?.currentChapterIndex ?: currentChapterIndex).toString()
        payload[KEY_SCROLL_Y] = (readingView?.currentScrollY ?: currentScrollY).toString()
        payload[KEY_FONT_SIZE] = (readingView?.currentFontSizeSp ?: currentFontSizeSp).toString()
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
        readingView?.releaseResources()
        rootContainer?.removeAllViews()
        rootContainer = null
        libraryView = null
        readingView = null
    }

    override fun restoreFromSnapshot(snapshot: MiniAppRecoverySnapshot) {
        val payload = snapshot.payload
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

        val ctx = rootContainer?.context
        if (ctx != null) {
            LogKeeper.log(ctx, "ReaderMiniAppInstance", "RESTORE_SNAPSHOT id=$instanceId mode=$currentScreenMode bookId=$currentBookId ch=$currentChapterIndex")
        } else {
            android.util.Log.d("ReaderMiniAppInstance", "RESTORE_SNAPSHOT id=$instanceId mode=$currentScreenMode bookId=$currentBookId ch=$currentChapterIndex")
        }

        if (rootContainer != null) {
            showCurrentScreen()
        }
    }

    override fun onSaveState(): Map<String, String> {
        readingView?.saveCurrentProgressImmediately()
        return mapOf(
            KEY_SCREEN_MODE to currentScreenMode.name,
            KEY_BOOK_ID to currentBookId.toString(),
            KEY_CHAPTER_INDEX to (readingView?.currentChapterIndex ?: currentChapterIndex).toString(),
            KEY_SCROLL_Y to (readingView?.currentScrollY ?: currentScrollY).toString(),
            KEY_FONT_SIZE to (readingView?.currentFontSizeSp ?: currentFontSizeSp).toString(),
            "app_type" to appType
        )
    }

    override fun createContentView(context: Context): View {
        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#181825"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootContainer = container

        libraryView = ReaderLibraryView(context) { selectedBook ->
            openBook(selectedBook)
        }

        readingView = ReaderReadingView(context) {
            currentScreenMode = ReaderScreenMode.LIBRARY
            showCurrentScreen()
        }

        showCurrentScreen()
        return container
    }

    private fun openBook(book: EpubBook) {
        currentBookId = book.id
        currentChapterIndex = book.lastReadChapter
        currentScrollY = book.lastReadScrollY
        currentScreenMode = ReaderScreenMode.READING

        showCurrentScreen()
        readingView?.loadBook(book, currentChapterIndex, currentScrollY, currentFontSizeSp)
    }

    private fun showCurrentScreen() {
        val container = rootContainer ?: return
        container.removeAllViews()

        if (currentScreenMode == ReaderScreenMode.READING && currentBookId > 0) {
            val rView = readingView ?: return
            container.addView(rView)

            // If reading view is not already loaded with this book, load it from DB
            instanceScope.launch {
                val db = AppDatabase.getDatabase(container.context)
                val book = db.epubDao().getBookById(currentBookId)
                if (book != null) {
                    rView.loadBook(book, currentChapterIndex, currentScrollY, currentFontSizeSp)
                } else {
                    currentScreenMode = ReaderScreenMode.LIBRARY
                    showCurrentScreen()
                }
            }
        } else {
            val lView = libraryView ?: return
            container.addView(lView)
            lView.loadBooks()
        }
    }

    // Testing helpers
    fun getLibraryViewForTesting(): ReaderLibraryView? = libraryView
    fun getReadingViewForTesting(): ReaderReadingView? = readingView
    fun getCurrentBookIdForTesting(): Int = currentBookId
    fun getCurrentScreenModeForTesting(): ReaderScreenMode = currentScreenMode
}
