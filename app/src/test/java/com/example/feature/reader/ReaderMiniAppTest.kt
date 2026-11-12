package com.example.feature.reader

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.example.core.FakeSharedPreferences
import com.example.core.WindowBounds
import com.example.data.EpubParser
import com.example.feature.floating.HeavyFloatingHost
import com.example.feature.floating.MiniAppLifecycleState
import com.example.feature.floating.MiniAppRecoverySnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

        override fun getSystemService(name: String): Any? {
            return try {
                super.getSystemService(name)
            } catch (_: Throwable) {
                null
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
    fun testReaderMiniAppFactoryRegisteredInHeavyHost() {
        val host = HeavyFloatingHost.getInstance(testContext)
        val instance = host.startInstance(
            instanceId = "test_reader_instance_1",
            appType = "reader",
            initialBounds = WindowBounds(100, 100, 500, 700)
        )

        assertNotNull("HeavyFloatingHost must instantiate ReaderMiniAppInstance for 'reader'", instance)
        assertTrue("Instance must be ReaderMiniAppInstance", instance is ReaderMiniAppInstance)
        assertEquals(MiniAppLifecycleState.RESUMED, instance?.lifecycleState)

        host.releaseInstance("test_reader_instance_1", "test_teardown")
    }

    @Test
    fun testReaderMiniAppLifecycleAndSnapshotRecovery() {
        val instanceId = "reader_lifecycle_test"
        val instance = ReaderMiniAppInstance(instanceId, "reader")

        // 1. Initial snapshot recovery
        val initialSnapshot = MiniAppRecoverySnapshot(
            instanceId = instanceId,
            appType = "reader",
            payload = mutableMapOf(
                ReaderMiniAppInstance.KEY_SCREEN_MODE to ReaderScreenMode.READING.name,
                ReaderMiniAppInstance.KEY_BOOK_ID to "42",
                ReaderMiniAppInstance.KEY_CHAPTER_INDEX to "3",
                ReaderMiniAppInstance.KEY_SCROLL_Y to "450",
                ReaderMiniAppInstance.KEY_FONT_SIZE to "18.0"
            )
        )
        instance.onCreate(initialSnapshot)
        instance.onStart()
        instance.onResume()

        assertEquals(MiniAppLifecycleState.RESUMED, instance.lifecycleState)
        assertEquals(ReaderScreenMode.READING, instance.getCurrentScreenModeForTesting())
        assertEquals(42, instance.getCurrentBookIdForTesting())

        // 2. Prepare for sleep: gathers state into payload
        val sleepPayload = instance.prepareForSleep()
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

        // 4. Reconstruction in a new instance (simulating app wake after sleep)
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
}
