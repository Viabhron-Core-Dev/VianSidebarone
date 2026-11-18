package com.example.feature.reader

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.example.core.OverlaySyncManager
import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * Screen modes within the floating Reader mini-app.
 */
enum class ReaderScreenMode {
    LIBRARY,
    READING,
    FOLDER_BROWSER
}

/**
 * Sub-tabs within the Reader LIBRARY home screen.
 * Default is [RECENT] (last 10 distinct opened Reader books).
 * Older history lives in Book Tracker; [FILE_EXPLORER] provides folder browsing & SAF import.
 */
enum class ReaderLibraryTab {
    RECENT,
    FILE_EXPLORER
}

/**
 * Compact sort modes for the Library Recent list.
 */
enum class ReaderLibrarySortOption(val label: String) {
    RECENTLY_OPENED("Opened"),
    TITLE("Title"),
    PROGRESS("Progress")
}

data class ReaderBookmark(
    val id: String,
    val bookId: Int,
    val chapterIndex: Int,
    val chapterTitle: String,
    val scrollY: Int,
    val createdAtMs: Long
)

/**
 * Lightweight per-book bookmark persistence stored in `FloatingReaderPrefs` (keeps Room v11 intact).
 */
object ReaderBookmarkStore {
    private const val PREFS_NAME = "FloatingReaderPrefs"
    private const val KEY_PREFIX = "reader_bookmarks_"

    fun getBookmarks(context: Context, bookId: Int): List<ReaderBookmark> {
        if (bookId <= 0) return emptyList()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString("$KEY_PREFIX$bookId", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            val list = ArrayList<ReaderBookmark>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(
                    ReaderBookmark(
                        id = obj.optString("id", "${bookId}_${i}"),
                        bookId = bookId,
                        chapterIndex = obj.optInt("chapterIndex", 0),
                        chapterTitle = obj.optString("chapterTitle", ""),
                        scrollY = obj.optInt("scrollY", 0),
                        createdAtMs = obj.optLong("createdAtMs", 0L)
                    )
                )
            }
            list.sortedWith(compareBy<ReaderBookmark> { it.chapterIndex }.thenBy { it.scrollY })
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun isChapterBookmarked(context: Context, bookId: Int, chapterIndex: Int): Boolean {
        return getBookmarks(context, bookId).any { it.chapterIndex == chapterIndex }
    }

    fun addOrUpdateBookmark(
        context: Context,
        bookId: Int,
        chapterIndex: Int,
        chapterTitle: String,
        scrollY: Int
    ): ReaderBookmark {
        val current = getBookmarks(context, bookId).toMutableList()
        val cleanTitle = chapterTitle.ifBlank { "Chapter ${chapterIndex + 1}" }
        val existingIdx = current.indexOfFirst { it.chapterIndex == chapterIndex }
        val entry = ReaderBookmark(
            id = if (existingIdx >= 0) current[existingIdx].id else "${bookId}_${chapterIndex}_${System.currentTimeMillis()}",
            bookId = bookId,
            chapterIndex = chapterIndex,
            chapterTitle = cleanTitle,
            scrollY = scrollY.coerceAtLeast(0),
            createdAtMs = System.currentTimeMillis()
        )
        if (existingIdx >= 0) {
            current[existingIdx] = entry
        } else {
            current.add(entry)
        }
        saveBookmarks(context, bookId, current)
        return entry
    }

    fun toggleChapterBookmark(
        context: Context,
        bookId: Int,
        chapterIndex: Int,
        chapterTitle: String,
        scrollY: Int
    ): Boolean {
        if (bookId <= 0) return false
        val current = getBookmarks(context, bookId).toMutableList()
        val existing = current.indexOfFirst { it.chapterIndex == chapterIndex }
        return if (existing >= 0) {
            current.removeAt(existing)
            saveBookmarks(context, bookId, current)
            false
        } else {
            addOrUpdateBookmark(context, bookId, chapterIndex, chapterTitle, scrollY)
            true
        }
    }

    fun removeBookmark(context: Context, bookId: Int, bookmarkId: String) {
        if (bookId <= 0) return
        val updated = getBookmarks(context, bookId).filterNot { it.id == bookmarkId }
        saveBookmarks(context, bookId, updated)
    }

    fun clearBookmarksForBook(context: Context, bookId: Int) {
        if (bookId <= 0) return
        OverlaySyncManager.syncRemove(context, "$KEY_PREFIX$bookId", PREFS_NAME)
    }

    private fun saveBookmarks(context: Context, bookId: Int, list: List<ReaderBookmark>) {
        val arr = org.json.JSONArray()
        for (b in list) {
            arr.put(
                org.json.JSONObject().apply {
                    put("id", b.id)
                    put("bookId", b.bookId)
                    put("chapterIndex", b.chapterIndex)
                    put("chapterTitle", b.chapterTitle)
                    put("scrollY", b.scrollY)
                    put("createdAtMs", b.createdAtMs)
                }
            )
        }
        OverlaySyncManager.syncString(context, "$KEY_PREFIX$bookId", arr.toString(), PREFS_NAME)
    }
}

/**
 * Authoritative Main UI-thread coroutine dispatcher for Reader UI components.
 *
 * Guarantees:
 * 1. In Android runtime (and Robolectric), always executes on `Looper.getMainLooper()` — never on `Dispatchers.Default`.
 * 2. Executes immediately inline when already on the Main UI thread, avoiding unnecessary queue delay.
 * 3. In headless JVM unit tests where `Looper.getMainLooper()` is null or unmocked `Handler.post` returns false,
 *    falls back to deterministic inline execution on the calling test thread.
 */
object ReaderDispatchers {
    val main: CoroutineDispatcher = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean {
            val mainLooper = try { Looper.getMainLooper() } catch (_: Throwable) { null }
            val mainThread = try { mainLooper?.thread } catch (_: Throwable) { null }
            if (mainLooper == null || mainThread == null) {
                return false
            }
            return Thread.currentThread() !== mainThread
        }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val mainLooper = try { Looper.getMainLooper() } catch (_: Throwable) { null }
            val mainThread = try { mainLooper?.thread } catch (_: Throwable) { null }
            if (mainLooper != null && mainThread != null) {
                if (Thread.currentThread() === mainThread) {
                    block.run()
                } else {
                    val posted = try {
                        Handler(mainLooper).post(block)
                    } catch (_: Throwable) {
                        false
                    }
                    if (!posted) {
                        // Headless JVM test environment where Handler.post is a stub returning false
                        block.run()
                    }
                }
            } else {
                block.run()
            }
        }
    }

    fun isOnMainUiThread(): Boolean {
        val mainLooper = try { Looper.getMainLooper() } catch (_: Throwable) { null }
        val mainThread = try { mainLooper?.thread } catch (_: Throwable) { null } ?: return true
        return Thread.currentThread() === mainThread
    }
}

/**
 * Tracks warm in-process Reader sessions vs cold process starts.
 *
 * - Temporary fold (`sleepMiniApp`) marks the instance warm so unfolding (`wakeMiniApp`) in the same
 *   process preserves any active screen (Library, Folder Browser, or current Reading position).
 * - On cold process restart (`warmSessions` is empty), Reader restores and opens the last-read active book
 *   when one exists (or opens Library if none exists / falls back to Library with re-import prompt if missing binary).
 */
object ReaderSessionTracker {
    private val warmSessions = ConcurrentHashMap.newKeySet<String>()

    fun markWarmSession(instanceId: String) {
        warmSessions.add(instanceId)
    }

    fun isWarmSession(instanceId: String): Boolean {
        return warmSessions.contains(instanceId)
    }

    fun clearWarmSession(instanceId: String) {
        warmSessions.remove(instanceId)
    }

    fun resetForColdStart() {
        warmSessions.clear()
    }

    fun resetForTesting() {
        warmSessions.clear()
    }
}

/**
 * Reader appearance and behavior preferences shared across `:heavy` (ReaderSettingsScreen)
 * and Main (Floating Reader).
 */
object ReaderPreferences {
    const val PREFS_NAME = "FloatingReaderPrefs"
    const val KEY_DEFAULT_FONT_SIZE = "reader_default_font_size_sp"
    const val KEY_LINE_SPACING = "reader_line_spacing_mult"
    const val KEY_THEME_PRESET = "reader_theme_preset"
    const val KEY_AUTO_SCROLL_SPEED = "reader_auto_scroll_speed"
    const val KEY_TTS_SPEECH_RATE = "reader_tts_speech_rate"
    const val KEY_KEEP_SCREEN_ON = "reader_keep_screen_on"

    const val THEME_DARK = "dark"
    const val THEME_SEPIA = "sepia"
    const val THEME_AMOLED = "amoled"
    const val THEME_LIGHT = "light"

    data class ThemePalette(
        val backgroundColor: Int,
        val surfaceColor: Int,
        val textColor: Int,
        val secondaryTextColor: Int,
        val accentColor: Int
    )

    fun getPalette(themePreset: String): ThemePalette {
        return when (themePreset.lowercase()) {
            THEME_SEPIA -> ThemePalette(
                backgroundColor = android.graphics.Color.parseColor("#F4ECD8"),
                surfaceColor = android.graphics.Color.parseColor("#E8DEC5"),
                textColor = android.graphics.Color.parseColor("#3B3228"),
                secondaryTextColor = android.graphics.Color.parseColor("#6E5E4F"),
                accentColor = android.graphics.Color.parseColor("#8C5A2B")
            )
            THEME_AMOLED -> ThemePalette(
                backgroundColor = android.graphics.Color.parseColor("#000000"),
                surfaceColor = android.graphics.Color.parseColor("#0F0F14"),
                textColor = android.graphics.Color.parseColor("#FFFFFF"),
                secondaryTextColor = android.graphics.Color.parseColor("#8A93A6"),
                accentColor = android.graphics.Color.parseColor("#89B4FA")
            )
            THEME_LIGHT -> ThemePalette(
                backgroundColor = android.graphics.Color.parseColor("#FAFAFC"),
                surfaceColor = android.graphics.Color.parseColor("#ECEEF4"),
                textColor = android.graphics.Color.parseColor("#1E1E2E"),
                secondaryTextColor = android.graphics.Color.parseColor("#585B70"),
                accentColor = android.graphics.Color.parseColor("#1E66F5")
            )
            else -> ThemePalette(
                backgroundColor = android.graphics.Color.parseColor("#08080B"),
                surfaceColor = android.graphics.Color.parseColor("#121218"),
                textColor = android.graphics.Color.parseColor("#F5F5F7"),
                secondaryTextColor = android.graphics.Color.parseColor("#9BA3B8"),
                accentColor = android.graphics.Color.parseColor("#89B4FA")
            )
        }
    }

    fun getDefaultFontSizeSp(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_DEFAULT_FONT_SIZE, 16f).coerceIn(12f, 28f)
    }

    fun setDefaultFontSizeSp(context: Context, sizeSp: Float) {
        val clamped = sizeSp.coerceIn(12f, 28f)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_DEFAULT_FONT_SIZE, clamped)
            .commit()
        OverlaySyncManager.syncFloat(context, KEY_DEFAULT_FONT_SIZE, clamped, PREFS_NAME)
        ReaderEventManager.notifySettingsChanged()
    }

    fun getLineSpacingMultiplier(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_LINE_SPACING, 1.35f).coerceIn(1.0f, 2.2f)
    }

    fun setLineSpacingMultiplier(context: Context, mult: Float) {
        val clamped = mult.coerceIn(1.0f, 2.2f)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_LINE_SPACING, clamped)
            .commit()
        OverlaySyncManager.syncFloat(context, KEY_LINE_SPACING, clamped, PREFS_NAME)
        ReaderEventManager.notifySettingsChanged()
    }

    fun getThemePreset(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_THEME_PRESET, THEME_DARK) ?: THEME_DARK
    }

    fun setThemePreset(context: Context, preset: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_PRESET, preset)
            .commit()
        OverlaySyncManager.syncString(context, KEY_THEME_PRESET, preset, PREFS_NAME)
        ReaderEventManager.notifySettingsChanged()
    }

    fun getAutoScrollSpeed(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_AUTO_SCROLL_SPEED, 1.0f).coerceIn(0.5f, 3.0f)
    }

    fun setAutoScrollSpeed(context: Context, speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_AUTO_SCROLL_SPEED, clamped)
            .commit()
        OverlaySyncManager.syncFloat(context, KEY_AUTO_SCROLL_SPEED, clamped, PREFS_NAME)
        ReaderEventManager.notifySettingsChanged()
    }

    fun getTtsSpeechRate(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_TTS_SPEECH_RATE, 1.0f).coerceIn(0.5f, 2.0f)
    }

    fun setTtsSpeechRate(context: Context, rate: Float) {
        val clamped = rate.coerceIn(0.5f, 2.0f)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_TTS_SPEECH_RATE, clamped)
            .commit()
        OverlaySyncManager.syncFloat(context, KEY_TTS_SPEECH_RATE, clamped, PREFS_NAME)
        ReaderEventManager.notifySettingsChanged()
    }
}

/**
 * Persists and retrieves the user's selected SAF library folder tree URI across Main and :heavy.
 */
object ReaderFolderPrefs {
    private const val PREFS_NAME = "FloatingReaderPrefs"
    private const val KEY_TREE_URI = "reader_folder_tree_uri"

    fun getFolderUri(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_TREE_URI, null)?.takeIf { it.isNotBlank() }
    }

    fun saveFolderUri(context: Context, uriStr: String?) {
        val clean = uriStr?.takeIf { it.isNotBlank() }
        if (clean == null) {
            OverlaySyncManager.syncRemove(context, KEY_TREE_URI, PREFS_NAME)
        } else {
            OverlaySyncManager.syncString(context, KEY_TREE_URI, clean, PREFS_NAME)
        }
    }
}

/**
 * Event broadcast interface for library, settings, and SAF folder updates across Main and :heavy.
 */
object ReaderEventManager {
    const val ACTION_READER_DB_CHANGED = "com.example.action.READER_DB_CHANGED"
    const val ACTION_OPEN_BOOK_IN_READER = "com.example.action.OPEN_BOOK_IN_READER"
    const val ACTION_RESTORE_READER_AFTER_ACTIVITY = "com.example.action.RESTORE_READER_AFTER_ACTIVITY"
    const val EXTRA_EPUB_BOOK_ID = "extra_epub_book_id"
    const val EXTRA_READER_INSTANCE_ID = "extra_reader_instance_id"

    private val listeners = mutableListOf<() -> Unit>()
    private val folderListeners = mutableListOf<(String?) -> Unit>()
    private val settingsListeners = mutableListOf<() -> Unit>()

    fun registerListener(listener: () -> Unit) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }

    fun unregisterListener(listener: () -> Unit) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun notifyLibraryChanged(context: Context? = null) {
        val copy = synchronized(listeners) { ArrayList(listeners) }
        for (listener in copy) {
            try {
                listener.invoke()
            } catch (_: Throwable) {}
        }
        if (context != null) {
            try {
                val intent = Intent(ACTION_READER_DB_CHANGED).apply {
                    setPackage(context.packageName)
                }
                context.sendBroadcast(intent)
            } catch (_: Throwable) {}
        }
    }

    fun requestOpenBookInReader(context: Context, epubBookId: Int) {
        if (epubBookId <= 0) return
        try {
            val intent = Intent(ACTION_OPEN_BOOK_IN_READER).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_EPUB_BOOK_ID, epubBookId)
            }
            context.sendBroadcast(intent)
        } catch (_: Throwable) {}
    }

    fun notifyExternalActivityClosed(context: Context, readerInstanceId: String? = null) {
        try {
            val intent = Intent(ACTION_RESTORE_READER_AFTER_ACTIVITY).apply {
                setPackage(context.packageName)
                if (!readerInstanceId.isNullOrBlank()) {
                    putExtra(EXTRA_READER_INSTANCE_ID, readerInstanceId)
                }
            }
            context.sendBroadcast(intent)
        } catch (_: Throwable) {}
    }

    fun registerFolderListener(listener: (String?) -> Unit) {
        synchronized(folderListeners) {
            if (!folderListeners.contains(listener)) {
                folderListeners.add(listener)
            }
        }
    }

    fun unregisterFolderListener(listener: (String?) -> Unit) {
        synchronized(folderListeners) {
            folderListeners.remove(listener)
        }
    }

    fun notifyFolderGranted(folderUri: String?) {
        val copy = synchronized(folderListeners) { ArrayList(folderListeners) }
        for (listener in copy) {
            try {
                listener.invoke(folderUri)
            } catch (_: Throwable) {}
        }
    }

    fun registerSettingsListener(listener: () -> Unit) {
        synchronized(settingsListeners) {
            if (!settingsListeners.contains(listener)) {
                settingsListeners.add(listener)
            }
        }
    }

    fun unregisterSettingsListener(listener: () -> Unit) {
        synchronized(settingsListeners) {
            settingsListeners.remove(listener)
        }
    }

    fun notifySettingsChanged() {
        val copy = synchronized(settingsListeners) { ArrayList(settingsListeners) }
        for (listener in copy) {
            try {
                listener.invoke()
            } catch (_: Throwable) {}
        }
    }
}

/**
 * Policy governing genuine high-water reading progress (`TrackerBook.readChapters`) vs navigation.
 *
 * Guarantees:
 * - Direct TOC/search jumps, restoring a chapter, reopening a book, and clicking Next/Prev immediately
 *   without meaningful reading activity NEVER advance high-water Tracker progress.
 * - Normal scrollable chapters require actual scroll, auto-scroll, or TTS progress.
 * - Short chapters (fitting on screen / unscrollable or <= [SHORT_CHAPTER_MAX_WORDS]) require a
 *   sensible reading dwell duration before advancing via Next so they are neither skipped falsely
 *   nor impossible to count.
 */
object ReaderProgressPolicy {
    const val SHORT_CHAPTER_MAX_WORDS = 220
    const val SHORT_CHAPTER_MAX_CHARS = 1400
    const val MIN_SHORT_CHAPTER_DWELL_MS = 2500L
    const val MAX_SHORT_CHAPTER_DWELL_MS = 10000L

    fun countWords(text: String): Int {
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

    fun requiredShortChapterDwellMs(wordCount: Int): Long {
        val safeWords = wordCount.coerceAtLeast(1)
        return (safeWords * 100L).coerceIn(MIN_SHORT_CHAPTER_DWELL_MS, MAX_SHORT_CHAPTER_DWELL_MS)
    }

    fun isShortChapter(
        wordCount: Int,
        charCount: Int,
        scrollableRangePx: Int,
        scrollThresholdPx: Int
    ): Boolean {
        if (charCount <= 0 && wordCount <= 0) return false
        if (scrollableRangePx > scrollThresholdPx) {
            return false
        }
        if (scrollableRangePx in 1..scrollThresholdPx) {
            return true
        }
        return wordCount <= SHORT_CHAPTER_MAX_WORDS && charCount <= SHORT_CHAPTER_MAX_CHARS
    }

    fun isScrollActivityMeaningful(
        scrollY: Int,
        initialScrollY: Int,
        scrollableRangePx: Int,
        scrollThresholdPx: Int,
        userTouched: Boolean,
        isProgrammatic: Boolean
    ): Boolean {
        if (isProgrammatic || !userTouched) return false
        val delta = kotlin.math.abs(scrollY - initialScrollY)
        if (delta >= scrollThresholdPx) return true
        if (scrollableRangePx in 1 until scrollThresholdPx) {
            val minFractionDelta = (scrollableRangePx * 0.50f).toInt().coerceAtLeast(1)
            val bottomZoneY = (scrollableRangePx * 0.70f).toInt().coerceAtLeast(1)
            if (delta >= minFractionDelta && scrollY >= bottomZoneY) {
                return true
            }
        }
        return false
    }

    fun shouldAdvanceProgressOnNextClick(
        hasGenuineReadingInChapter: Boolean,
        chapterEnteredAtMs: Long,
        nowMs: Long,
        wordCount: Int,
        charCount: Int,
        scrollableRangePx: Int,
        scrollThresholdPx: Int
    ): Boolean {
        if (hasGenuineReadingInChapter) {
            return false
        }
        if (!isShortChapter(wordCount, charCount, scrollableRangePx, scrollThresholdPx)) {
            return false
        }
        val elapsedMs = (nowMs - chapterEnteredAtMs).coerceAtLeast(0L)
        return elapsedMs >= requiredShortChapterDwellMs(wordCount)
    }
}

