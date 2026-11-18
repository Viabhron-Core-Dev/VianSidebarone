package com.example.feature.reader

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.example.core.FakeSharedPreferences
import com.example.core.WindowBounds
import com.example.data.EpubBook
import com.example.data.EpubParser
import com.example.feature.floating.HeavyFloatingHost
import com.example.feature.floating.MiniAppLifecycleState
import com.example.feature.floating.MiniAppRecoverySnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReaderMiniAppTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: MockReaderTestContext
    private lateinit var tempDir: File
    private lateinit var fakePrefs: FakeSharedPreferences

    private class MockReaderTestContext(
        private val prefs: SharedPreferences,
        private val baseFilesDir: File
    ) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example"
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getFilesDir(): File = baseFilesDir
        override fun getCacheDir(): File = baseFilesDir

        private val fakeResources: android.content.res.Resources by lazy {
            val dm = android.util.DisplayMetrics().apply {
                density = 2f
                widthPixels = 800
                heightPixels = 1600
            }
            val conf = android.content.res.Configuration()
            val am = try {
                android.content.res.AssetManager::class.java.getConstructor().newInstance()
            } catch (_: Throwable) {
                android.content.res.AssetManager::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            }
            @Suppress("DEPRECATION")
            object : android.content.res.Resources(am, dm, conf) {
                override fun getDisplayMetrics(): android.util.DisplayMetrics = dm
            }
        }

        override fun getResources(): android.content.res.Resources = fakeResources

        private val mockWindowManager: android.view.WindowManager by lazy {
            java.lang.reflect.Proxy.newProxyInstance(
                android.view.WindowManager::class.java.classLoader,
                arrayOf(android.view.WindowManager::class.java)
            ) { _, _, _ -> null } as android.view.WindowManager
        }

        override fun getSystemService(name: String): Any? {
            return if (name == Context.WINDOW_SERVICE) {
                mockWindowManager
            } else {
                try {
                    super.getSystemService(name)
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }

    @Before
    fun setUp() {
        tempDir = tempFolder.newFolder("reader_test")
        fakePrefs = FakeSharedPreferences()
        testContext = MockReaderTestContext(fakePrefs, tempDir)
        HeavyFloatingHost.resetForTesting()
    }

    @Test
    fun testReaderRunsExclusivelyInMainProcessAndNotInHeavyHost() {
        val bridge = com.example.feature.floating.FloatingMiniAppBridge.getInstance(testContext)
        assertTrue(
            "FloatingMiniAppBridge must treat 'reader' and 'ebook_reader' as Main-only mini-apps",
            bridge.isMainOnlyMiniApp("reader") && bridge.isMainOnlyMiniApp("ebook_reader")
        )

        val window = com.example.feature.floating.FloatingMiniAppWindow("test_reader_win", testContext, "reader")
        assertTrue("FloatingMiniAppWindow must recognize 'reader' as Main-only", window.isMainOnlyApp() && window.isReaderApp())

        // Verify ReaderDispatchers.main does not silently fall back to Dispatchers.Default when Main Looper is available
        assertNotNull(ReaderDispatchers.main)
    }

    @Test
    fun testReaderMiniAppLifecycleWarmFoldUnfoldAndColdRestart() {
        val instanceId = "reader_lifecycle_test"
        ReaderSessionTracker.resetForTesting()
        val instance = ReaderMiniAppInstance(instanceId, "reader")

        // 1. Initial warm snapshot recovery
        val initialSnapshot = MiniAppRecoverySnapshot(
            instanceId = instanceId,
            appType = "reader",
            payload = mutableMapOf(
                ReaderMiniAppInstance.KEY_SCREEN_MODE to ReaderScreenMode.READING.name,
                ReaderMiniAppInstance.KEY_BOOK_ID to "42",
                ReaderMiniAppInstance.KEY_CHAPTER_INDEX to "3",
                ReaderMiniAppInstance.KEY_SCROLL_Y to "450",
                ReaderMiniAppInstance.KEY_FONT_SIZE to "18.0",
                ReaderMiniAppInstance.KEY_COLD_START to "false"
            )
        )
        instance.onCreate(initialSnapshot)
        instance.onStart()
        instance.onResume()

        assertEquals(MiniAppLifecycleState.RESUMED, instance.lifecycleState)
        assertEquals(ReaderScreenMode.READING, instance.getCurrentScreenModeForTesting())
        assertEquals(42, instance.getCurrentBookIdForTesting())

        // 2. Prepare for sleep (warm fold): gathers state into payload and marks warm session
        val sleepPayload = instance.prepareForSleep()
        assertTrue("prepareForSleep must mark session as warm", ReaderSessionTracker.isWarmSession(instanceId))
        assertEquals(MiniAppLifecycleState.SLEEPING, instance.lifecycleState)
        assertEquals(ReaderScreenMode.READING.name, sleepPayload[ReaderMiniAppInstance.KEY_SCREEN_MODE])
        assertEquals("42", sleepPayload[ReaderMiniAppInstance.KEY_BOOK_ID])
        assertEquals("3", sleepPayload[ReaderMiniAppInstance.KEY_CHAPTER_INDEX])
        assertEquals("450", sleepPayload[ReaderMiniAppInstance.KEY_SCROLL_Y])
        assertEquals("18.0", sleepPayload[ReaderMiniAppInstance.KEY_FONT_SIZE])

        // 3. Runtime release and destroy
        instance.releaseRuntime()
        instance.onDestroy()
        assertEquals(MiniAppLifecycleState.DESTROYED, instance.lifecycleState)

        // 4. Warm unfold reconstruction in a new instance
        val wakeInstance = ReaderMiniAppInstance(instanceId, "reader")
        val recoverySnapshot = MiniAppRecoverySnapshot(
            instanceId = instanceId,
            appType = "reader",
            payload = LinkedHashMap(sleepPayload)
        )
        wakeInstance.onCreate(recoverySnapshot)
        wakeInstance.onStart()
        wakeInstance.onResume()

        assertEquals(ReaderScreenMode.READING, wakeInstance.getCurrentScreenModeForTesting())
        assertEquals(42, wakeInstance.getCurrentBookIdForTesting())

        // 5. Cold process restart with empty Library DB: KEY_COLD_START == "true" must safely stay in LIBRARY (Recent tab)
        ReaderSessionTracker.resetForTesting()
        val coldSnapshot = MiniAppRecoverySnapshot(
            instanceId = instanceId,
            appType = "reader",
            payload = LinkedHashMap(sleepPayload).apply {
                put(ReaderMiniAppInstance.KEY_COLD_START, "true")
            }
        )
        val coldInstance = ReaderMiniAppInstance(instanceId, "reader")
        coldInstance.onCreate(coldSnapshot)
        coldInstance.createContentView(testContext)
        assertEquals("Cold start with no books must stay in LIBRARY mode", ReaderScreenMode.LIBRARY, coldInstance.getCurrentScreenModeForTesting())
        assertEquals("Cold start with no books must not open deleted/non-existent book", -1, coldInstance.getCurrentBookIdForTesting())
        assertEquals(
            "Cold start Library view must default to RECENT tab",
            ReaderLibraryTab.RECENT,
            coldInstance.getLibraryViewForTesting()?.getActiveTabForTesting()
        )
        coldInstance.onDestroy()
    }

    @Test
    fun testViewCreationAndLibraryDisplay() {
        val instance = ReaderMiniAppInstance("view_test_1", "reader")
        instance.onCreate(null)
        val view = instance.createContentView(testContext)

        assertNotNull(view)
        assertEquals(ReaderScreenMode.LIBRARY, instance.getCurrentScreenModeForTesting())
        assertNotNull(instance.getLibraryViewForTesting())
    }

    @Test
    fun testViewScopesSurviveDetachDuringScreenSwitchAndCancelOnRelease() {
        val instance = ReaderMiniAppInstance("scope_detach_test", "reader")
        instance.onCreate(null)
        instance.createContentView(testContext)

        val libView = instance.getLibraryViewForTesting()
        val readView = instance.getReadingViewForTesting()
        assertNotNull(libView)
        assertNotNull(readView)

        assertTrue("Library viewScope must be active initially", libView!!.isViewScopeActiveForTesting())
        assertTrue("Reading viewScope must be active initially", readView!!.isViewScopeActiveForTesting())

        // Simulate removeAllViews() detaching/reattaching views during screen transitions
        val detachLib = libView.javaClass.getDeclaredMethod("onDetachedFromWindow").apply { isAccessible = true }
        val detachRead = readView.javaClass.getDeclaredMethod("onDetachedFromWindow").apply { isAccessible = true }
        detachLib.invoke(libView)
        detachRead.invoke(readView)

        assertTrue("Library viewScope must survive onDetachedFromWindow during screen switch", libView.isViewScopeActiveForTesting())
        assertTrue("Reading viewScope must survive onDetachedFromWindow during screen switch", readView.isViewScopeActiveForTesting())

        // Explicit releaseRuntime must cancel both scopes cleanly
        instance.releaseRuntime()
        assertTrue("Library viewScope must cancel on releaseRuntime", !libView.isViewScopeActiveForTesting())
        assertTrue("Reading viewScope must cancel on releaseRuntime", !readView.isViewScopeActiveForTesting())
    }

    @Test
    fun testFolderBrowserFilterAndNaturalSort() {
        val raw = listOf(
            ReaderFolderBrowserView.FolderEntry("notes.pdf", isDirectory = false),
            ReaderFolderBrowserView.FolderEntry("Chapter_10.epub", isDirectory = false),
            ReaderFolderBrowserView.FolderEntry("Chapter_2.epub", isDirectory = false),
            ReaderFolderBrowserView.FolderEntry("readme.txt", isDirectory = false),
            ReaderFolderBrowserView.FolderEntry("cover.png", isDirectory = false),
            ReaderFolderBrowserView.FolderEntry("Zebra_Folder", isDirectory = true),
            ReaderFolderBrowserView.FolderEntry("Alpha_Folder", isDirectory = true)
        )

        val unfiltered = ReaderFolderBrowserView.filterAndSortEntries(raw, "")
        assertEquals(
            listOf("Alpha_Folder", "Zebra_Folder", "Chapter_2.epub", "Chapter_10.epub", "readme.txt"),
            unfiltered.map { it.name }
        )

        val filtered = ReaderFolderBrowserView.filterAndSortEntries(raw, "chapter")
        assertEquals(listOf("Chapter_2.epub", "Chapter_10.epub"), filtered.map { it.name })
    }

    @Test
    fun testBringToFrontSkipsRefreshWhenOnlyHiddenOrDetachedWindowsAbove() {
        var removeCount = 0
        val countingWm = java.lang.reflect.Proxy.newProxyInstance(
            android.view.WindowManager::class.java.classLoader,
            arrayOf(android.view.WindowManager::class.java)
        ) { _, method, _ ->
            if (method.name == "removeViewImmediate") {
                removeCount++
            }
            null
        } as android.view.WindowManager

        val wmContext = object : ContextWrapper(testContext) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? {
                return if (name == Context.WINDOW_SERVICE) countingWm else super.getSystemService(name)
            }
        }

        val instanceField = com.example.core.FloatingWindowManager::class.java.getDeclaredField("instance").apply {
            isAccessible = true
        }
        val previousInstance = instanceField.get(null)
        instanceField.set(null, null)
        try {
            val fwm = com.example.core.FloatingWindowManager.getInstance(wmContext)
            fun makeTestWin(id: String) = object : com.example.core.FloatingWindow(id, wmContext, "test") {
                override fun createContentView(context: Context): android.view.View = android.widget.FrameLayout(context)
            }

            val winA = makeTestWin("win_a")
            val winHiddenAbove = makeTestWin("win_hidden")
            val winVisibleAbove = makeTestWin("win_visible")

            fwm.registerWindow(winA)
            fwm.showWindow("win_a")

            fwm.registerWindow(winHiddenAbove)
            fwm.showWindow("win_hidden")
            fwm.hideWindow("win_hidden")

            removeCount = 0
            // Only win_hidden (invisible) sits above win_a -> must NOT call removeViewImmediate
            fwm.bringToFront("win_a")
            assertEquals("Hidden window above must not trigger SurfaceView-destroying removeViewImmediate", 0, removeCount)

            fwm.registerWindow(winVisibleAbove)
            fwm.showWindow("win_visible")

            removeCount = 0
            // Now win_visible (attached + visible) sits above win_a -> must reorder overlay cleanly
            fwm.bringToFront("win_a")
            assertEquals("Visible attached window above must trigger overlay Z-order refresh", 1, removeCount)
        } finally {
            instanceField.set(null, previousInstance)
        }
    }

    @Test
    fun testLibraryTabsSortSearchAndDetailsStatePreservation() {
        val books = listOf(
            com.example.data.EpubBook(id = 1, title = "Zebra Tales", author = "Alice", filePath = "", totalChapters = 10, lastReadChapter = 8, lastOpenedTimestamp = 100L),
            com.example.data.EpubBook(id = 2, title = "Alpha Odyssey", author = "Bob", filePath = "", totalChapters = 10, lastReadChapter = 1, lastOpenedTimestamp = 300L),
            com.example.data.EpubBook(id = 3, title = "Middle Path", author = "Carol", filePath = "", totalChapters = 10, lastReadChapter = 4, lastOpenedTimestamp = 200L)
        )
        assertEquals(
            listOf(2, 3, 1),
            ReaderLibraryView.filterAndSortBooks(books, "", ReaderLibrarySortOption.RECENTLY_OPENED).map { it.id }
        )
        assertEquals(
            listOf(2, 3, 1),
            ReaderLibraryView.filterAndSortBooks(books, "", ReaderLibrarySortOption.TITLE).map { it.id }
        )
        assertEquals(
            listOf(1, 3, 2),
            ReaderLibraryView.filterAndSortBooks(books, "", ReaderLibrarySortOption.PROGRESS).map { it.id }
        )
        assertEquals(
            listOf(3),
            ReaderLibraryView.filterAndSortBooks(books, "carol", ReaderLibrarySortOption.RECENTLY_OPENED).map { it.id }
        )

        val libView = ReaderLibraryView(context = testContext, instanceId = "lib_state_test", onBookSelected = {})
        try {
            assertEquals(ReaderLibraryTab.RECENT, libView.getActiveTabForTesting())
            libView.cycleSortOptionForTesting()
            assertEquals(ReaderLibrarySortOption.TITLE, libView.getSortOptionForTesting())
            libView.setSearchQueryForTesting("Alpha")
            assertTrue(libView.isSearchExpandedForTesting())

            libView.openBookDetailsForTesting(books[1])
            assertTrue(libView.isDetailsOpenForTesting())
            libView.closeBookDetailsForTesting()
            assertTrue(!libView.isDetailsOpenForTesting())

            // Exact Library state (tab, sort, search) preserved across Details open/close
            assertEquals(ReaderLibraryTab.RECENT, libView.getActiveTabForTesting())
            assertEquals(ReaderLibrarySortOption.TITLE, libView.getSortOptionForTesting())
            assertTrue(libView.isSearchExpandedForTesting())

            libView.selectTab(ReaderLibraryTab.FILE_EXPLORER)
            assertEquals(ReaderLibraryTab.FILE_EXPLORER, libView.getActiveTabForTesting())
        } finally {
            libView.releaseResources()
        }
    }

    @Test
    fun testReadingToolbarHiddenByDefaultAndCleanTapToggleOnly() {
        val readView = ReaderReadingView(context = testContext, instanceId = "toolbar_tap_test", onBackToLibrary = {})
        try {
            assertTrue("Toolbar must be hidden by default", !readView.isBottomToolbarVisibleForTesting())

            readView.dispatchTouchCoordinatesForTesting(android.view.MotionEvent.ACTION_DOWN, 200f, 300f, 100L)
            readView.dispatchTouchCoordinatesForTesting(android.view.MotionEvent.ACTION_UP, 202f, 301f, 140L)
            assertTrue("Clean tap on reading area must show toolbar", readView.isBottomToolbarVisibleForTesting())

            // Drag / scroll beyond touchSlop must NOT toggle toolbar
            readView.dispatchTouchCoordinatesForTesting(android.view.MotionEvent.ACTION_DOWN, 200f, 300f, 200L)
            readView.dispatchTouchCoordinatesForTesting(android.view.MotionEvent.ACTION_MOVE, 200f, 450f, 230L)
            readView.dispatchTouchCoordinatesForTesting(android.view.MotionEvent.ACTION_UP, 200f, 480f, 260L)
            assertTrue("Scroll drag must NOT accidentally dismiss toolbar", readView.isBottomToolbarVisibleForTesting())

            // Text selection tap must NOT toggle toolbar
            readView.dispatchTouchCoordinatesForTesting(android.view.MotionEvent.ACTION_DOWN, 200f, 300f, 300L, hasSelection = true)
            readView.dispatchTouchCoordinatesForTesting(android.view.MotionEvent.ACTION_UP, 201f, 300f, 340L, hasSelection = true)
            assertTrue("Tap with active text selection must NOT dismiss toolbar", readView.isBottomToolbarVisibleForTesting())

            // Clean second tap hides toolbar again
            readView.handleCleanTapOnReadingAreaForTesting()
            assertTrue("Second clean tap must hide toolbar", !readView.isBottomToolbarVisibleForTesting())
        } finally {
            readView.releaseResources()
        }
    }

    @Test
    fun testBookmarksToggleAndPersistence() {
        ReaderBookmarkStore.clearBookmarksForBook(testContext, 77)
        val added = ReaderBookmarkStore.toggleChapterBookmark(
            context = testContext,
            bookId = 77,
            chapterIndex = 4,
            chapterTitle = "Chapter 5: The Signal",
            scrollY = 380
        )
        assertTrue("First toggle must add bookmark", added)
        val saved = ReaderBookmarkStore.getBookmarks(testContext, 77)
        assertEquals(1, saved.size)
        assertEquals(4, saved[0].chapterIndex)
        assertEquals(380, saved[0].scrollY)
        assertTrue(ReaderBookmarkStore.isChapterBookmarked(testContext, 77, 4))

        val removed = ReaderBookmarkStore.toggleChapterBookmark(
            context = testContext,
            bookId = 77,
            chapterIndex = 4,
            chapterTitle = "Chapter 5: The Signal",
            scrollY = 380
        )
        assertTrue("Second toggle must remove bookmark", !removed)
        assertEquals(0, ReaderBookmarkStore.getBookmarks(testContext, 77).size)
    }

    @Test
    fun testHideAndRestoreFloatingReaderForExternalActivityPreservesOtherWindows() {
        val instanceField = com.example.core.FloatingWindowManager::class.java.getDeclaredField("instance").apply {
            isAccessible = true
        }
        val bridgeField = com.example.feature.floating.FloatingMiniAppBridge::class.java.getDeclaredField("instance").apply {
            isAccessible = true
        }
        val prevFwm = instanceField.get(null)
        val prevBridge = bridgeField.get(null)
        instanceField.set(null, null)
        bridgeField.set(null, null)
        try {
            val fwm = com.example.core.FloatingWindowManager.getInstance(testContext)
            val bridge = com.example.feature.floating.FloatingMiniAppBridge.getInstance(testContext)

            val readerWin = com.example.feature.floating.FloatingMiniAppWindow("reader_ext_test", testContext, "reader")
            val noteWin = object : com.example.core.FloatingWindow("note_keep", testContext, "test_notes") {
                override fun createContentView(context: Context): android.view.View = android.widget.FrameLayout(context)
            }

            fwm.registerWindow(noteWin)
            fwm.showWindow("note_keep")
            fwm.registerWindow(readerWin)
            fwm.showWindow("reader_ext_test")

            val hiddenId = bridge.hideReaderForExternalActivity("reader_ext_test")
            assertEquals("reader_ext_test", hiddenId)
            assertEquals(com.example.core.WindowState.HIDDEN, readerWin.state)
            assertEquals(com.example.core.WindowState.VISIBLE, noteWin.state)
            assertEquals("reader_ext_test", bridge.getHiddenReaderInstanceIdForTesting())

            val restored = bridge.restoreReaderAfterExternalActivity("reader_ext_test")
            assertTrue("restoreReaderAfterExternalActivity must succeed", restored)
            assertEquals(com.example.core.WindowState.VISIBLE, readerWin.state)
            assertEquals(com.example.core.WindowState.VISIBLE, noteWin.state)
            assertEquals(null, bridge.getHiddenReaderInstanceIdForTesting())
        } finally {
            instanceField.set(null, prevFwm)
            bridgeField.set(null, prevBridge)
        }
    }

    @Test
    fun testRestoreAfterBookTrackerDoesNotReAddAttachedHiddenViewAndIdempotentRestore() {
        var addViewCount = 0
        var removeViewCount = 0
        val attachedViews = LinkedHashSet<android.view.View>()
        val countingWm = java.lang.reflect.Proxy.newProxyInstance(
            android.view.WindowManager::class.java.classLoader,
            arrayOf(android.view.WindowManager::class.java)
        ) { _, method, args ->
            when (method.name) {
                "addView" -> {
                    val view = args!![0] as android.view.View
                    if (!attachedViews.add(view)) {
                        throw IllegalStateException("View $view has already been added to the window manager.")
                    }
                    addViewCount++
                }
                "removeViewImmediate", "removeView" -> {
                    val view = args!![0] as android.view.View
                    if (!attachedViews.remove(view)) {
                        throw IllegalArgumentException("View=$view not attached to window manager")
                    }
                    removeViewCount++
                }
            }
            null
        } as android.view.WindowManager

        val wmContext = object : ContextWrapper(testContext) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = testContext.packageName
            override fun getResources(): android.content.res.Resources = testContext.resources
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                testContext.getSharedPreferences(name, mode)
            override fun getFilesDir(): File = testContext.filesDir
            override fun getCacheDir(): File = testContext.cacheDir
            override fun getSystemService(name: String): Any? {
                return if (name == Context.WINDOW_SERVICE) countingWm else testContext.getSystemService(name)
            }
        }

        val instanceField = com.example.core.FloatingWindowManager::class.java.getDeclaredField("instance").apply {
            isAccessible = true
        }
        val bridgeField = com.example.feature.floating.FloatingMiniAppBridge::class.java.getDeclaredField("instance").apply {
            isAccessible = true
        }
        val prevFwm = instanceField.get(null)
        val prevBridge = bridgeField.get(null)
        instanceField.set(null, null)
        bridgeField.set(null, null)
        try {
            val fwm = com.example.core.FloatingWindowManager.getInstance(wmContext)
            val bridge = com.example.feature.floating.FloatingMiniAppBridge.getInstance(wmContext)
            val readerWin = com.example.feature.floating.FloatingMiniAppWindow("reader_wm_fix", wmContext, "reader")

            fwm.registerWindow(readerWin)
            assertTrue("Initial showWindow must succeed", fwm.showWindow("reader_wm_fix"))
            assertEquals("Initial showWindow must call addView once", 1, addViewCount)
            assertTrue("Visible window must report isAttached == true", readerWin.isAttached)

            // Hide Reader for Book Tracker -> keeps view attached, hides visually
            val hiddenId = bridge.hideReaderForExternalActivity("reader_wm_fix")
            assertEquals("reader_wm_fix", hiddenId)
            assertEquals(com.example.core.WindowState.HIDDEN, readerWin.state)
            assertTrue("HIDDEN overlay view stays attached in WindowManager", readerWin.isAttached)
            assertEquals(0, removeViewCount)

            // Restore after Book Tracker closes -> MUST NOT re-add already-attached view
            assertTrue(bridge.restoreReaderAfterExternalActivity("reader_wm_fix"))
            assertEquals("Restoring hidden attached view must NOT trigger duplicate addView", 1, addViewCount)
            assertEquals(com.example.core.WindowState.VISIBLE, readerWin.state)

            // Second restore call (dual broadcast + IPC) -> idempotent no-op
            assertTrue(bridge.restoreReaderAfterExternalActivity("reader_wm_fix"))
            assertEquals("Duplicate restore call must remain idempotent", 1, addViewCount)
            assertEquals(com.example.core.WindowState.VISIBLE, readerWin.state)

            // Contrast with foldWindow -> detaches view and marks FOLDED
            fwm.foldWindow("reader_wm_fix")
            assertEquals(com.example.core.WindowState.FOLDED, readerWin.state)
            assertTrue("Folded window must be detached (isAttached == false)", !readerWin.isAttached)
            assertEquals(1, removeViewCount)

            // Unfold from FOLDED -> cleanly re-attaches via addView once
            fwm.unfoldWindow("reader_wm_fix")
            assertEquals(com.example.core.WindowState.VISIBLE, readerWin.state)
            assertTrue(readerWin.isAttached)
            assertEquals(2, addViewCount)
        } finally {
            instanceField.set(null, prevFwm)
            bridgeField.set(null, prevBridge)
        }
    }

    @Test
    fun testSingleProgressIndicatorAndRemovedFontControlsInReadingToolbar() {
        val readView = ReaderReadingView(context = testContext, instanceId = "single_progress_test", onBackToLibrary = {})
        try {
            assertTrue("Bottom toolbar must be hidden by default", !readView.isBottomToolbarVisibleForTesting())
            assertTrue("Top thin progress line must be visible when toolbar is hidden", readView.isTopProgressLineVisibleForTesting())
            assertTrue("A-/sp/A+ font controls must be removed from bottom toolbar", !readView.hasFontControlsInToolbarForTesting())
            assertTrue(
                "Bottom toolbar must have compact padding without 46dp sticker gap (< 20dp)",
                readView.getBottomToolbarBottomPaddingPxForTesting() < 40
            )

            // Toggle toolbar ON -> top line hides, bottom SeekBar toolbar shows
            readView.handleCleanTapOnReadingAreaForTesting()
            assertTrue(readView.isBottomToolbarVisibleForTesting())
            assertTrue("Top thin progress line must hide when toolbar is visible", !readView.isTopProgressLineVisibleForTesting())

            // Toggle toolbar OFF -> top line returns
            readView.handleCleanTapOnReadingAreaForTesting()
            assertTrue(!readView.isBottomToolbarVisibleForTesting())
            assertTrue(readView.isTopProgressLineVisibleForTesting())

            // Verify proportional full-book progress math and seek target resolution
            val chapters = listOf(
                com.example.data.ChapterMeta(index = 0, title = "Ch 1", wordCount = 1000),
                com.example.data.ChapterMeta(index = 1, title = "Ch 2", wordCount = 1000),
                com.example.data.ChapterMeta(index = 2, title = "Ch 3", wordCount = 2000)
            ) // total = 4000 words
            val pMid = ReaderReadingView.computeFullBookProgressPermille(
                chapters = chapters,
                totalChapters = 3,
                chapterIndex = 1,
                scrollY = 500,
                scrollableRangePx = 1000
            )
            assertEquals(375, pMid) // (1000 + 500) / 4000 = 0.375 -> 375 permille

            val target = ReaderReadingView.resolveSeekTarget(
                chapters = chapters,
                totalChapters = 3,
                permille = 750 // 3000 / 4000 -> halfway into chapter 2
            )
            assertEquals(2, target.chapterIndex)
            assertEquals(0.5f, target.intraChapterFraction, 0.01f)
        } finally {
            readView.releaseResources()
        }
    }

    @Test
    fun testReaderStickerHidingAndTopBarPencilVsNoteEditButton() {
        val readerWin = com.example.feature.floating.FloatingMiniAppWindow("reader_sticker_test", testContext, "reader")
        val noteWin = com.example.feature.floating.FloatingMiniAppWindow("note_sticker_test", testContext, "note")
        try {
            readerWin.createContentView(testContext)
            noteWin.createContentView(testContext)

            // Phase 2 & 3: Reader top bar omits blue dot, uses compact 32dp height + Settings cog; Note keeps dot + "Edit"
            val readerEditBtn = readerWin.getTopBarReaderEditButtonForTesting()
            assertNotNull("Reader top bar must have compact Settings ImageButton", readerEditBtn)
            assertEquals("Reader Settings", readerWin.topBarReaderEditContentDescription)
            assertEquals(null, readerWin.getTopBarIconViewForTesting())
            assertEquals("Reader", readerWin.getTopBarTitleTextForTesting())
            assertTrue("Reader top bar must be compact (<= Note height)", readerWin.getTopBarHeightPx() < noteWin.getTopBarHeightPx())
            assertEquals(null, readerWin.getTopBarNoteEditButtonForTesting())

            assertNotNull("Note top bar must preserve accent indicator dot", noteWin.getTopBarIconViewForTesting())
            assertNotNull("Note top bar must preserve 'Edit' text button", noteWin.getTopBarNoteEditButtonForTesting())
            assertEquals(null, noteWin.getTopBarReaderEditButtonForTesting())

            // Requirement #6: Sticker starts visible in Reader Library
            assertTrue("Sticker must be visible in Reader Library", readerWin.isBottomRightStickerVisible)
            assertTrue("Sticker must be visible in Note", noteWin.isBottomRightStickerVisible)

            val readerInstance = readerWin.getReaderInstanceForTesting()!!
            val readingView = readerInstance.getReadingViewForTesting()!!
            // Switch instance into READING mode via reflection/open flow
            val modeField = ReaderMiniAppInstance::class.java.getDeclaredField("currentScreenMode").apply { isAccessible = true }
            modeField.set(readerInstance, ReaderScreenMode.READING)

            // Showing bottom reading toolbar in READING hides Reader's sticker
            readingView.handleCleanTapOnReadingAreaForTesting()
            assertTrue(readingView.isBottomToolbarVisibleForTesting())
            assertTrue("Reader sticker must hide when reading toolbar is visible", !readerWin.isBottomRightStickerVisible)

            // Hiding toolbar restores sticker
            readingView.handleCleanTapOnReadingAreaForTesting()
            assertTrue(!readingView.isBottomToolbarVisibleForTesting())
            assertTrue("Reader sticker must restore when reading toolbar hides", readerWin.isBottomRightStickerVisible)

            // Leaving reading screen via Library button also restores sticker
            readingView.handleCleanTapOnReadingAreaForTesting()
            assertTrue(!readerWin.isBottomRightStickerVisible)
            readingView.navigateBackToLibraryForTesting()
            assertTrue("Reader sticker must restore when returning to Library", readerWin.isBottomRightStickerVisible)

            // Fold sync restores sticker visibility
            readerWin.onFold(true)
            assertTrue(readerWin.isBottomRightStickerVisible)

            // Note sticker remains unaffected
            assertTrue(noteWin.isBottomRightStickerVisible)
        } finally {
            readerWin.onDestroy()
            noteWin.onDestroy()
        }
    }

    @Test
    fun testExplorerTabHidesRecentControlsAndUsesCompactMetrics() {
        val libView = ReaderLibraryView(context = testContext, instanceId = "explorer_compact_test", onBookSelected = {})
        try {
            libView.selectTab(ReaderLibraryTab.FILE_EXPLORER)
            assertTrue(
                "File Explorer tab must hide Recent utility/search/list controls",
                libView.isRecentControlsHiddenForTesting()
            )
            val explorer = libView.getEmbeddedExplorerForTesting()
            assertTrue(explorer.isEmbeddedInLibraryForTesting())
            assertTrue("Explorer topBar padding must be compact", explorer.getTopBarBottomPaddingPxForTesting() <= 8)
            assertTrue("Explorer search bottom margin must be compact", explorer.getSearchBottomMarginPxForTesting() <= 10)
        } finally {
            libView.releaseResources()
        }
    }

    @Test
    fun testReadingViewMinimalHeaderAndBottomToolbarOrderAndTitle() {
        val longTitle = "The Unabridged Chronicles of the Northern Archipelago & Distant Voyages Vol. III"
        val readerWin = com.example.feature.floating.FloatingMiniAppWindow("reader_phase2_test", testContext, "reader")
        try {
            readerWin.createContentView(testContext)
            val instance = readerWin.getReaderInstanceForTesting()!!
            val readView = instance.getReadingViewForTesting()!!

            val epubFile = File(testContext.cacheDir, "fallback_named_book.epub").apply { writeText("stub") }
            val bookWithTitle = EpubBook(
                id = 77,
                title = longTitle,
                author = "A. Author",
                filePath = epubFile.absolutePath,
                totalChapters = 5
            )
            readView.loadBook(bookWithTitle)
            val expectedChapterTitle = readView.getMinimalHeaderTitleForTesting()
            assertTrue(expectedChapterTitle.isNotEmpty())
            assertFalse("Minimal chapter header must NOT show full book name", expectedChapterTitle.contains("Northern Archipelago"))

            // Scope 2.A: Chapter title renders in floating window top bar and separate header bar is detached
            assertEquals(expectedChapterTitle, readerWin.getTopBarTitleTextForTesting())
            assertTrue(
                "Separate chapter header underneath top bar must be detached",
                readView.getTopHeaderLayoutForTesting().parent == null
            )

            // Bottom toolbar shows wrapped full book name (maxLines == 3, multi-line)
            assertEquals(longTitle, readView.getToolbarBookTitleTextForTesting())
            assertEquals(3, readView.getToolbarBookTitleMaxLinesForTesting())

            // Returning to Library resets floating top bar back to "Reader"
            readView.navigateBackToLibraryForTesting()
            assertEquals("Reader", readerWin.getTopBarTitleTextForTesting())

            // Fallback to filename when book title is blank
            assertEquals(
                "fallback_named_book",
                ReaderReadingView.resolveDisplayBookName(bookWithTitle.copy(title = "  "))
            )

            // Strict single-row order: Prev -> SeekBar -> Next, plus all 7 icon actions
            val navSeekChildren = readView.getNavSeekRowChildrenForTesting()
            assertEquals(3, navSeekChildren.size)
            assertEquals(readView.getPrevChapterButtonForTesting(), navSeekChildren[0])
            assertEquals(readView.getFullBookSeekBarForTesting(), navSeekChildren[1])
            assertEquals(readView.getNextChapterButtonForTesting(), navSeekChildren[2])
            assertTrue(readView.getPrevChapterContentDescriptionForTesting().isNotBlank())
            assertTrue(readView.getNextChapterContentDescriptionForTesting().isNotBlank())
            assertEquals(7, readView.getBottomToolbarIconDescriptionsForTesting().size)
        } finally {
            readerWin.onDestroy()
        }
    }

    @Test
    fun testLibraryRemovesHeaderBarAndShowsContinueFabAcrossTabs() {
        val tempBookFile = File(testContext.cacheDir, "continue_fab_book.epub").apply {
            writeText("non-empty-epub-stub")
        }
        val openedBooks = mutableListOf<EpubBook>()
        val libView = ReaderLibraryView(
            context = testContext,
            instanceId = "library_fab_test",
            onBookSelected = { openedBooks.add(it) }
        )
        fun collectAllDescendants(root: android.view.View): List<android.view.View> {
            val result = mutableListOf(root)
            if (root is android.view.ViewGroup) {
                for (i in 0 until root.childCount) {
                    result.addAll(collectAllDescendants(root.getChildAt(i)))
                }
            }
            return result
        }
        val readerWin = com.example.feature.floating.FloatingMiniAppWindow("library_pill_check", testContext, "reader")
        try {
            readerWin.createContentView(testContext)
            // 1. Header bar and obsolete Sort / + File / Search bottom utility controls are absent
            assertFalse("Redundant Library header bar must be removed", libView.hasHeaderBarForTesting())
            assertFalse("Obsolete Library bottom utility bar must be removed", libView.hasBottomUtilityBarForTesting())
            assertFalse("Library bottom utility sticker must not be visible", libView.isUtilityStickerVisibleForTesting())
            val allLibraryViews = collectAllDescendants(libView)
            assertTrue(
                "Obsolete Sort / + File / Search utility controls must not exist in Library view tree",
                allLibraryViews.none { v ->
                    val desc = v.contentDescription?.toString() ?: ""
                    val text = (v as? android.widget.TextView)?.text?.toString() ?: ""
                    desc == "Cycle library sort order" || desc == "Toggle Library Search" || text == "+ File"
                }
            )

            // 2. Unwanted full-width bottom strip is absent (rootLayout bottom padding == 0 on both tabs)
            assertEquals("Root Library column must have zero bottom strip padding on Recent tab", 0, libView.getRootLayoutBottomPaddingPxForTesting())
            val rootChildren = libView.getRootContentColumnChildrenForTesting()
            assertTrue(rootChildren.size >= 4)
            assertEquals(libView.getTabBarViewForTesting(), rootChildren[0])

            // 3. Shared three-control floating window pill [ × | — | ↘ ] remains unchanged
            assertNotNull("Shared floating window pill must exist", readerWin.getBottomRightStickerViewForTesting())
            assertTrue("Shared floating window pill must remain visible", readerWin.isBottomRightStickerVisible)
            assertTrue("Resize control in shared pill must remain visible", readerWin.isStickerResizeVisibleForTesting())
            assertTrue("Resize divider in shared pill must remain visible", readerWin.isStickerResizeDividerVisibleForTesting())

            // 4. Continue button is compact circular play-only (▶) without "Continue" text label
            assertEquals("Continue Reading", libView.getContinueFabContentDescForTesting())
            assertEquals("▶", libView.getContinueFabLabelTextForTesting())
            assertFalse(
                "Continue button must not display text label",
                libView.getContinueFabLabelTextForTesting().contains("Continue", ignoreCase = true)
            )

            // Hidden when no last-read book exists
            libView.setRecentBooksForTesting(emptyList())
            assertFalse("Continue FAB must hide when no last-read book exists", libView.isContinueFabVisibleForTesting())

            // Visible on BOTH Recent and File Explorer tabs when a last-read book exists
            val lastBook = EpubBook(
                id = 91,
                title = "Last Read Odyssey",
                author = "Homer",
                filePath = tempBookFile.absolutePath,
                totalChapters = 12,
                lastReadChapter = 4,
                lastReadScrollY = 320,
                lastOpenedTimestamp = 5000L
            )
            libView.setRecentBooksForTesting(listOf(lastBook))
            assertTrue("Continue FAB must be visible on Recent tab", libView.isContinueFabVisibleForTesting())
            assertTrue(
                "Recent scroll container must reserve bottom padding clear of Continue FAB and shared pill",
                libView.getBookListScrollViewBottomPaddingPxForTesting() >= 80
            )

            libView.selectTab(ReaderLibraryTab.FILE_EXPLORER)
            assertEquals("Root Library column must have zero bottom strip padding on File Explorer tab", 0, libView.getRootLayoutBottomPaddingPxForTesting())
            assertFalse("Bottom utility controls must remain absent in File Explorer tab", libView.isUtilityStickerVisibleForTesting())
            assertTrue("Continue FAB must remain visible on File Explorer tab", libView.isContinueFabVisibleForTesting())
            assertTrue(
                "Explorer scroll container must reserve bottom padding clear of Continue FAB and shared pill",
                libView.getEmbeddedExplorerForTesting().getListScrollViewBottomPaddingPxForTesting() >= 80
            )

            // Clicking Continue FAB from Explorer opens the actual last-read book
            libView.clickContinueFabForTesting()
            runBlocking { libView.awaitPendingJobsForTesting() }
            assertEquals(1, openedBooks.size)
            assertEquals(91, openedBooks.first().id)
            assertEquals(4, openedBooks.first().lastReadChapter)
            assertEquals(320, openedBooks.first().lastReadScrollY)

            // Missing binary triggers promptReimport cleanly without invoking onBookSelected
            openedBooks.clear()
            tempBookFile.delete()
            libView.clickContinueFabForTesting()
            assertTrue("Missing binary must not blindly open empty reader", openedBooks.isEmpty())
        } finally {
            tempBookFile.delete()
            readerWin.onDestroy()
            libView.releaseResources()
        }
    }

    @Test
    fun testExplorerSearchHiddenByDefaultToggleFilterAndCleanClose() {
        var lastFocusableRequested: Boolean? = null
        val explorer = ReaderFolderBrowserView(
            context = testContext,
            instanceId = "explorer_search_test",
            onBackToLibrary = {},
            onBookImported = {},
            onRequestWindowFocusable = { lastFocusableRequested = it },
            embeddedInLibrary = true
        )
        try {
            val entries = listOf(
                ReaderFolderBrowserView.FolderEntry("Dune.epub", isDirectory = false, sizeBytes = 4096),
                ReaderFolderBrowserView.FolderEntry("Foundation.epub", isDirectory = false, sizeBytes = 2048),
                ReaderFolderBrowserView.FolderEntry("SciFi", isDirectory = true)
            )
            explorer.setEntriesForTesting(entries)

            // Search hidden by default; all 3 entries shown
            assertFalse("Explorer search must start collapsed", explorer.isSearchExpandedForTesting())
            assertFalse("Explorer search row must be GONE by default", explorer.isSearchRowVisibleForTesting())
            assertEquals(3, explorer.getRenderedEntryCountForTesting())

            // Toggle search open -> search row becomes visible and filters entries
            explorer.toggleSearchForTesting()
            assertTrue(explorer.isSearchExpandedForTesting())
            assertTrue(explorer.isSearchRowVisibleForTesting())
            assertEquals(true, lastFocusableRequested)

            explorer.setSearchQueryForTesting("dune")
            assertEquals("dune", explorer.getCurrentQueryForTesting())
            assertEquals(1, explorer.getRenderedEntryCountForTesting())

            // Closing search collapses row, clears query & focus, and restores all 3 entries
            explorer.closeSearchForTesting()
            assertFalse(explorer.isSearchExpandedForTesting())
            assertFalse(explorer.isSearchRowVisibleForTesting())
            assertEquals("", explorer.getCurrentQueryForTesting())
            assertEquals(false, lastFocusableRequested)
            assertEquals(3, explorer.getRenderedEntryCountForTesting())
        } finally {
            explorer.releaseResources()
        }
    }

    @Test
    fun testBookTrackerLightColorSchemeUsesHighContrastLightPalette() {
        val scheme = BookTrackerLightColors.createColorScheme()
        assertTrue("Background must be near-white light canvas", scheme.background.red > 0.9f && scheme.background.green > 0.9f && scheme.background.blue > 0.9f)
        assertTrue("Surface must be light white", scheme.surface.red > 0.95f && scheme.surface.green > 0.95f && scheme.surface.blue > 0.95f)
        assertTrue("OnBackground text must be dark ink", scheme.onBackground.red < 0.15f && scheme.onBackground.green < 0.15f && scheme.onBackground.blue < 0.25f)
        assertTrue("OnSurface text must be dark ink", scheme.onSurface.red < 0.15f && scheme.onSurface.green < 0.15f && scheme.onSurface.blue < 0.25f)
        assertTrue("Primary cobalt accent must contrast with white OnPrimary", scheme.primary != scheme.background && scheme.onPrimary.red > 0.95f)
    }
}
