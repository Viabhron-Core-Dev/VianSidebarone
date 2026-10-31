package com.example.feature.floating

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.WindowManager
import com.example.core.FakeSharedPreferences
import com.example.core.WindowBounds
import com.example.feature.miniapps.MiniAppManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy

class FloatingMiniAppFrameworkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class MockTestContext(
        private val prefs: SharedPreferences,
        private val baseFilesDir: File
    ) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example"
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getFilesDir(): File = baseFilesDir

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

        private val mockWindowManager: WindowManager by lazy {
            Proxy.newProxyInstance(
                WindowManager::class.java.classLoader,
                arrayOf(WindowManager::class.java)
            ) { _, _, _ -> null } as WindowManager
        }

        override fun getSystemService(name: String): Any {
            return if (name == Context.WINDOW_SERVICE) {
                mockWindowManager
            } else {
                super.getSystemService(name) ?: Any()
            }
        }
    }

    private lateinit var testContext: MockTestContext
    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        DurableMiniAppStore.resetForTesting()
        HeavyFloatingHost.resetForTesting()
        FloatingMiniAppBridge.resetForTesting()

        fakePrefs = FakeSharedPreferences()
        testContext = MockTestContext(fakePrefs, tempFolder.root)
    }

    @Test
    fun testSnapshotSerializationAndDeserialization() {
        val store = DurableMiniAppStore.getInstance(testContext)

        val originalSnapshot = MiniAppRecoverySnapshot(
            instanceId = "test_miniapp_1",
            appType = "work_notes",
            title = "Project Work Notes",
            state = MiniAppState.SLEEPING,
            windowBounds = WindowBounds(150, 200, 700, 900),
            maximizeRestoreBounds = WindowBounds(100, 100, 600, 800),
            buttonPosition = Pair(60, 350),
            payload = mutableMapOf(
                "note_content" to "Remember to review floating IPC",
                "counter" to "5"
            ),
            version = 1,
            timestamp = 1700000000000L
        )

        val saved = store.saveSnapshot(originalSnapshot)
        assertTrue("Snapshot save should succeed", saved)

        val loaded = store.loadSnapshot("test_miniapp_1")
        assertNotNull("Loaded snapshot should not be null", loaded)
        assertEquals(originalSnapshot.instanceId, loaded!!.instanceId)
        assertEquals(originalSnapshot.appType, loaded.appType)
        assertEquals(originalSnapshot.title, loaded.title)
        assertEquals(MiniAppState.SLEEPING, loaded.state)
        assertEquals(150, loaded.windowBounds.x)
        assertEquals(200, loaded.windowBounds.y)
        assertEquals(700, loaded.windowBounds.width)
        assertEquals(900, loaded.windowBounds.height)

        assertNotNull("maximizeRestoreBounds should be preserved", loaded.maximizeRestoreBounds)
        assertEquals(100, loaded.maximizeRestoreBounds!!.x)
        assertEquals(100, loaded.maximizeRestoreBounds!!.y)
        assertEquals(600, loaded.maximizeRestoreBounds!!.width)
        assertEquals(800, loaded.maximizeRestoreBounds!!.height)

        assertEquals(60, loaded.buttonPosition.first)
        assertEquals(350, loaded.buttonPosition.second)

        assertEquals("Remember to review floating IPC", loaded.payload["note_content"])
        assertEquals("5", loaded.payload["counter"])
    }

    @Test
    fun testIndependentCoordinatesNeverOverwriteEachOther() {
        val store = DurableMiniAppStore.getInstance(testContext)

        val snapshot = MiniAppRecoverySnapshot(
            instanceId = "test_coords_app",
            appType = "reader",
            windowBounds = WindowBounds(120, 180, 500, 600),
            buttonPosition = Pair(50, 220)
        )
        store.saveSnapshot(snapshot)

        // 1. Update ONLY windowBounds
        val newBounds = WindowBounds(300, 400, 650, 750)
        store.updateWindowBounds("test_coords_app", newBounds)

        val afterBoundsUpdate = store.loadSnapshot("test_coords_app")
        assertNotNull(afterBoundsUpdate)
        assertEquals("windowBounds must reflect update", 300, afterBoundsUpdate!!.windowBounds.x)
        assertEquals("windowBounds width must reflect update", 650, afterBoundsUpdate.windowBounds.width)
        assertEquals("buttonPosition must NOT be modified by bounds update", 50, afterBoundsUpdate.buttonPosition.first)
        assertEquals("buttonPosition must NOT be modified by bounds update", 220, afterBoundsUpdate.buttonPosition.second)

        // 2. Update ONLY buttonPosition
        store.updateButtonPosition("test_coords_app", 90, 500)

        val afterButtonUpdate = store.loadSnapshot("test_coords_app")
        assertNotNull(afterButtonUpdate)
        assertEquals("buttonPosition X must reflect update", 90, afterButtonUpdate!!.buttonPosition.first)
        assertEquals("buttonPosition Y must reflect update", 500, afterButtonUpdate.buttonPosition.second)
        assertEquals("windowBounds X must remain unchanged", 300, afterButtonUpdate.windowBounds.x)
        assertEquals("windowBounds width must remain unchanged", 650, afterButtonUpdate.windowBounds.width)
    }

    @Test
    fun testMiniAppSleepContractAndStateRestoration() {
        val factory = DefaultMiniAppFactory()
        val instance = factory.createAppInstance("notes_101", "work_notes")
        assertNotNull("Factory should produce instance", instance)

        instance.onCreate(null)
        instance.onStart()
        instance.onResume()
        assertEquals(MiniAppLifecycleState.RESUMED, instance.lifecycleState)

        // Simulate user making edits
        val sleepPayload = instance.prepareForSleep()
        instance.releaseRuntime()
        instance.onDestroy()

        assertEquals(MiniAppLifecycleState.DESTROYED, instance.lifecycleState)
        assertNotNull("Payload must be produced on sleep", sleepPayload)
        assertEquals("work_notes", sleepPayload["app_type"])

        // Reconstruct live state in new instance from recovery snapshot
        val snapshot = MiniAppRecoverySnapshot(
            instanceId = "notes_101",
            appType = "work_notes",
            state = MiniAppState.ACTIVE,
            payload = LinkedHashMap(sleepPayload)
        )

        val restoredInstance = factory.createAppInstance("notes_101", "work_notes")
        assertNotNull(restoredInstance)
        restoredInstance.onCreate(snapshot)
        restoredInstance.restoreFromSnapshot(snapshot)
        restoredInstance.onStart()
        restoredInstance.onResume()

        assertEquals(MiniAppLifecycleState.RESUMED, restoredInstance.lifecycleState)
        val savedData = restoredInstance.onSaveState()
        assertEquals(sleepPayload["counter"], savedData["counter"])
    }

    @Test
    fun testHeavyFloatingHostActiveInstanceTrackingAndTeardown() {
        val host = HeavyFloatingHost.getInstance(testContext)

        val inst = host.startInstance("reader_1", "reader")
        assertNotNull("Should start reader instance", inst)
        assertEquals("Active instance count must be at least 1", 1, host.getActiveInstanceCount())

        // Sleep instance -> runtime must be dismantled and active count must drop to 0
        val sleepPayload = host.prepareSleep("reader_1")
        assertNotNull(sleepPayload)
        assertEquals("Active instance count must be 0 when mini-app is sleeping", 0, host.getActiveInstanceCount())
    }

    @Test
    fun testMiniAppManagerToggleDispatchesCleanly() {
        // Test that MiniAppManager.toggleApp dispatches and registers snapshot in Main
        MiniAppManager.toggleApp(testContext, "work_notes")
        val snapshot = DurableMiniAppStore.getInstance(testContext).loadSnapshot("miniapp_work_notes")
        assertNotNull("Toggle should have registered durable snapshot in Main", snapshot)
        assertEquals("work_notes", snapshot!!.appType)
        assertEquals(MiniAppState.ACTIVE, snapshot.state)
    }

    @Test
    fun testTestNotesMiniAppCreationAndTextPersistence() {
        val factory = TestNotesMiniAppFactory()
        val instance = factory.createAppInstance("test_notes_cuj", TestNotesMiniAppInstance.APP_TYPE)
        assertNotNull("Factory must produce TestNotesMiniAppInstance", instance)
        assertTrue(instance is TestNotesMiniAppInstance)
        val testNotes = instance as TestNotesMiniAppInstance

        testNotes.onCreate(null)
        testNotes.onStart()
        testNotes.onResume()
        assertEquals(TestNotesMiniAppInstance.DEFAULT_INSTRUCTIONAL_TEXT, testNotes.noteText)

        // Simulate user typing text
        val editedUserText = "Validation on physical device: Move, Resize, Sleep, Wake."
        val snapshotWithEdit = MiniAppRecoverySnapshot(
            instanceId = "test_notes_cuj",
            appType = TestNotesMiniAppInstance.APP_TYPE,
            payload = mutableMapOf(TestNotesMiniAppInstance.KEY_TEXT to editedUserText)
        )
        testNotes.restoreFromSnapshot(snapshotWithEdit)
        assertEquals(editedUserText, testNotes.noteText)

        // Prepare for sleep
        val sleepPayload = testNotes.prepareForSleep()
        assertEquals(editedUserText, sleepPayload[TestNotesMiniAppInstance.KEY_TEXT])
        assertEquals(MiniAppLifecycleState.SLEEPING, testNotes.lifecycleState)

        // Wake in new instance from recovery snapshot
        val newInstance = factory.createAppInstance("test_notes_cuj", TestNotesMiniAppInstance.APP_TYPE) as TestNotesMiniAppInstance
        val wakeSnapshot = MiniAppRecoverySnapshot(
            instanceId = "test_notes_cuj",
            appType = TestNotesMiniAppInstance.APP_TYPE,
            state = MiniAppState.ACTIVE,
            payload = LinkedHashMap(sleepPayload)
        )
        newInstance.onCreate(wakeSnapshot)
        newInstance.restoreFromSnapshot(wakeSnapshot)
        newInstance.onStart()
        newInstance.onResume()

        assertEquals("Saved text must survive sleep and be restored on wake", editedUserText, newInstance.noteText)
    }

    @Test
    fun testHeavyFloatingHostTestNotesRegistrationAndLifecycle() {
        val host = HeavyFloatingHost.getInstance(testContext)

        val instance = host.startInstance("notes_hw_1", TestNotesMiniAppInstance.APP_TYPE)
        assertNotNull("HeavyFloatingHost must have test_notes factory registered", instance)
        assertEquals(1, host.getActiveInstanceCount())

        val editedText = "Physical validation step 5"
        val payload = host.prepareSleep("notes_hw_1", mapOf(TestNotesMiniAppInstance.KEY_TEXT to editedText))
        assertNotNull(payload)
        assertEquals(editedText, payload[TestNotesMiniAppInstance.KEY_TEXT])

        // Verify active instance count is 0 so Heavy is disposable
        assertEquals(0, host.getActiveInstanceCount())
    }

    @Test
    fun testMiniAppManagerToggleTestNotesWiresToMainStore() {
        MiniAppManager.toggleApp(testContext, "test_notes")
        val snapshot = DurableMiniAppStore.getInstance(testContext).loadSnapshot("miniapp_test_notes")
        assertNotNull("Toggle test_notes must register snapshot in Main", snapshot)
        assertEquals("test_notes", snapshot!!.appType)
        assertEquals(MiniAppState.ACTIVE, snapshot.state)
    }
}
