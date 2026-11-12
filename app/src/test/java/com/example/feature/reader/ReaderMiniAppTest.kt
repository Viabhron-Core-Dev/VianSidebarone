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
}
