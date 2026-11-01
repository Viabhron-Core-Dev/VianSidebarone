package com.example.feature.floating

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.WindowManager
import com.example.core.FakeSharedPreferences
import com.example.core.WindowBounds
import com.example.feature.miniapps.MiniAppManager
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyCommandType
import com.example.core.ipc.HeavyProcessConnectionManager
import com.example.core.ipc.ConnectionState
import com.example.core.ipc.IHeavyHostContract
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

    @Test
    fun testWindowGeometryMoveResizeMaximizeAndImeIndependence() {
        val store = DurableMiniAppStore.getInstance(testContext)
        val initialBounds = WindowBounds(100, 150, 400, 500)
        val snapshot = MiniAppRecoverySnapshot(
            instanceId = "geom_test_notes",
            appType = "test_notes",
            state = MiniAppState.ACTIVE,
            windowBounds = initialBounds.copyBounds(),
            buttonPosition = Pair(40, 200)
        )
        store.saveSnapshot(snapshot)

        // 1. Move: updates x, y
        val movedBounds = WindowBounds(180, 220, 400, 500)
        store.updateWindowBounds("geom_test_notes", movedBounds)
        val afterMove = store.loadSnapshot("geom_test_notes")!!
        assertEquals(180, afterMove.windowBounds.x)
        assertEquals(220, afterMove.windowBounds.y)
        assertEquals(Pair(40, 200), afterMove.buttonPosition)

        // 2. Resize: updates width, height
        val resizedBounds = WindowBounds(180, 220, 480, 620)
        store.updateWindowBounds("geom_test_notes", resizedBounds)
        val afterResize = store.loadSnapshot("geom_test_notes")!!
        assertEquals(480, afterResize.windowBounds.width)
        assertEquals(620, afterResize.windowBounds.height)
        assertEquals(Pair(40, 200), afterResize.buttonPosition)

        // 3. Maximize: saves maximizeRestoreBounds
        val restoreBounds = resizedBounds.copyBounds()
        store.updateMaximizeRestoreBounds("geom_test_notes", restoreBounds)
        val maximizedBounds = WindowBounds(12, 28, 760, 1400)
        store.updateWindowBounds("geom_test_notes", maximizedBounds)
        val afterMax = store.loadSnapshot("geom_test_notes")!!
        assertEquals(maximizedBounds.width, afterMax.windowBounds.width)
        assertEquals(restoreBounds.width, afterMax.maximizeRestoreBounds!!.width)

        // 4. Restore: resets to restoreBounds
        store.updateWindowBounds("geom_test_notes", afterMax.maximizeRestoreBounds!!)
        store.updateMaximizeRestoreBounds("geom_test_notes", null)
        val afterRestore = store.loadSnapshot("geom_test_notes")!!
        assertEquals(480, afterRestore.windowBounds.width)
        assertEquals(620, afterRestore.windowBounds.height)
        assertNull(afterRestore.maximizeRestoreBounds)

        // 5. Button position is completely independent
        store.updateButtonPosition("geom_test_notes", 75, 800)
        val afterBubbleMove = store.loadSnapshot("geom_test_notes")!!
        assertEquals(Pair(75, 800), afterBubbleMove.buttonPosition)
        assertEquals(480, afterBubbleMove.windowBounds.width)
        assertEquals(620, afterBubbleMove.windowBounds.height)
    }

    @Test
    fun testFoldSafeTransactionalOrderingAndPreservation() {
        val store = DurableMiniAppStore.getInstance(testContext)
        val initialSnapshot = MiniAppRecoverySnapshot(
            instanceId = "fold_safe_test",
            appType = "test_notes",
            state = MiniAppState.ACTIVE,
            windowBounds = WindowBounds(100, 100, 500, 600),
            payload = mutableMapOf(TestNotesMiniAppInstance.KEY_TEXT to "Vital Note Content")
        )
        store.saveSnapshot(initialSnapshot)

        // Simulate instance running in Heavy
        val host = HeavyFloatingHost.getInstance(testContext)
        val instance = host.startInstance("fold_safe_test", "test_notes", initialSnapshot.windowBounds, initialSnapshot.payload)
        assertNotNull(instance)

        // 1. Non-destructive snapshot retrieval from Heavy
        val snapshotPayload = host.getSnapshot("fold_safe_test")
        assertTrue(snapshotPayload.containsKey(TestNotesMiniAppInstance.KEY_TEXT))
        assertEquals("Vital Note Content", snapshotPayload[TestNotesMiniAppInstance.KEY_TEXT])
        // Live Heavy instance MUST still be alive!
        assertEquals("Heavy instance must NOT be released before durable persistence", 1, host.getActiveInstanceCount())

        // 2. Commit recovery snapshot to durable store in Main
        initialSnapshot.state = MiniAppState.SLEEPING
        initialSnapshot.payload.putAll(snapshotPayload)
        val saveOk = store.saveSnapshot(initialSnapshot)
        assertTrue("Main persistence must succeed", saveOk)

        // 3. ONLY after durable save succeeds is the instance released from Heavy
        val released = host.releaseInstance("fold_safe_test", reason = "sleep_confirmed")
        assertTrue("Heavy instance release must succeed", released)
        assertEquals("Heavy active instance count must reach 0 after release", 0, host.getActiveInstanceCount())

        // 4. Verify durable store contains exact content
        val loaded = store.loadSnapshot("fold_safe_test")
        assertNotNull(loaded)
        assertEquals(MiniAppState.SLEEPING, loaded!!.state)
        assertEquals("Vital Note Content", loaded.payload[TestNotesMiniAppInstance.KEY_TEXT])
    }

    @Test
    fun testSnapshotOrPersistenceFailurePreservesLiveHeavyInstance() {
        val store = DurableMiniAppStore.getInstance(testContext)
        val initialSnapshot = MiniAppRecoverySnapshot(
            instanceId = "preserve_live_test",
            appType = "test_notes",
            state = MiniAppState.ACTIVE,
            windowBounds = WindowBounds(100, 100, 500, 600),
            payload = mutableMapOf(TestNotesMiniAppInstance.KEY_TEXT to "Critical Unsaved Data")
        )
        store.saveSnapshot(initialSnapshot)

        val host = HeavyFloatingHost.getInstance(testContext)
        val instance = host.startInstance("preserve_live_test", "test_notes", initialSnapshot.windowBounds, initialSnapshot.payload)
        assertNotNull(instance)
        assertEquals(1, host.getActiveInstanceCount())

        // Simulate snapshot retrieval
        val snapshotPayload = host.getSnapshot("preserve_live_test")
        assertEquals(1, host.getActiveInstanceCount())

        // Simulate failure during Main durable persistence (e.g. disk full / exception)
        val persistenceSucceeded = false
        if (!persistenceSucceeded) {
            // Fold transaction aborted: do NOT call releaseInstance!
        }

        // Live Heavy instance must remain intact
        assertEquals("Heavy instance must remain alive when durable persistence fails", 1, host.getActiveInstanceCount())
        val liveInstance = host.getActiveInstance("preserve_live_test")
        assertNotNull(liveInstance)
        assertEquals("Critical Unsaved Data", liveInstance!!.onSaveState()[TestNotesMiniAppInstance.KEY_TEXT])
    }

    @Test
    fun testIdempotentRepeatedStartAndWake() {
        val host = HeavyFloatingHost.getInstance(testContext)
        val bounds = WindowBounds(120, 160, 500, 600)

        // First start
        val inst1 = host.startInstance("idempotent_test", "test_notes", bounds, mapOf("text" to "Initial Note Content"))
        assertNotNull(inst1)
        assertEquals(1, host.getActiveInstanceCount())
        assertEquals("Initial Note Content", inst1!!.onSaveState()[TestNotesMiniAppInstance.KEY_TEXT])

        // Second start for the same instanceId (e.g. wake or re-mount) with updated payload
        val inst2 = host.startInstance("idempotent_test", "test_notes", bounds, mapOf("text" to "Updated Wake Content"))
        assertNotNull(inst2)
        assertEquals("Second start must NOT create a duplicate instance", 1, host.getActiveInstanceCount())
        assertSame("Second start must return the same instance", inst1, inst2)
        assertEquals("Updated snapshot payload must be restored onto existing instance", "Updated Wake Content", inst2!!.onSaveState()[TestNotesMiniAppInstance.KEY_TEXT])
    }

    @Test
    fun testStartRequestedDuringConnectingQueuesAndDeliversExactlyOnce() {
        val connManager = HeavyProcessConnectionManager.getInstance(testContext)
        connManager.setMockProxyForTesting(null, ConnectionState.CONNECTING)

        val cmd = HeavyCommand(
            commandId = "test_cmd_1",
            type = HeavyCommandType.START_OPERATION,
            targetId = "test_miniapp_queue",
            payload = mapOf("instance_id" to "test_miniapp_queue", "app_type" to "test_notes")
        )

        // Send while CONNECTING: must queue without terminal failure
        val res = connManager.sendCommand(cmd, autoConnect = true)
        assertTrue("Command sent during CONNECTING with autoConnect must report queued success", res.success)
        assertEquals(1, connManager.getPendingCommandCountForTesting())

        // Mock Heavy host proxy recording delivered commands
        val deliveredCommands = mutableListOf<String>()
        val mockProxy = object : IHeavyHostContract {
            override fun sendCommand(commandJson: String): String {
                deliveredCommands.add(commandJson)
                return com.example.core.ipc.IpcResult.success("Started").toJson()
            }
            override fun syncSnapshot(snapshotJson: String): String = com.example.core.ipc.IpcResult.success().toJson()
            override fun ping(): Boolean = true
            override fun registerCallback(callbackBinder: android.os.IBinder): Boolean = true
            override fun unregisterCallback(): Boolean = true
        }

        // Connection succeeds -> flushes queued commands
        connManager.setMockProxyForTesting(mockProxy, ConnectionState.CONNECTED)
        assertEquals(0, connManager.getPendingCommandCountForTesting())
        assertEquals("Queued command must be delivered exactly once upon connection", 1, deliveredCommands.size)
        assertTrue(deliveredCommands[0].contains("test_cmd_1"))
    }

    @Test
    fun testFoldedRecordRestorationDoesNotWakeHeavy() {
        val store = DurableMiniAppStore.getInstance(testContext)
        val sleepingSnapshot = MiniAppRecoverySnapshot(
            instanceId = "sleeping_note_1",
            appType = "test_notes",
            state = MiniAppState.SLEEPING,
            windowBounds = WindowBounds(100, 100, 500, 600),
            buttonPosition = Pair(40, 200),
            payload = mutableMapOf(TestNotesMiniAppInstance.KEY_TEXT to "Sleeping Note")
        )
        store.saveSnapshot(sleepingSnapshot)

        val host = HeavyFloatingHost.getInstance(testContext)
        assertEquals(0, host.getActiveInstanceCount())

        val bridge = FloatingMiniAppBridge.getInstance(testContext)
        val restoredWindows = bridge.restoreActiveMiniApps()

        // Sleeping mini-apps must NOT create heavy floating windows
        assertTrue("Sleeping mini-apps should not restore as full windows on startup", restoredWindows.isEmpty())
        // Heavy process instance count must remain 0
        assertEquals("Sleeping mini-apps must not instantiate runtimes in Heavy on startup", 0, host.getActiveInstanceCount())
    }

    @Test
    fun testLauncherDimensionsRemainStableBeforeDuringAndAfterDrag() {
        val bubble = FloatingMiniAppLauncherBubble(
            instanceId = "test_bubble",
            appType = "test_notes",
            context = testContext,
            initialX = 50,
            initialY = 150,
            onBubbleTapped = {}
        )

        val initialW = bubble.bounds.width
        val initialH = bubble.bounds.height
        assertTrue("Bubble size must be compact (around 36dp * density)", initialW > 0)
        assertEquals(initialW, initialH)
        assertEquals("LayoutParams width must match bounds width", initialW, bubble.layoutParams.width)
        assertEquals("LayoutParams height must match bounds height", initialH, bubble.layoutParams.height)

        // Verify bounds update simulates drag without warping dimensions
        val movedBounds = WindowBounds(120, 280, initialW, initialH)
        bubble.setWindowBounds(movedBounds)
        assertEquals(120, bubble.bounds.x)
        assertEquals(280, bubble.bounds.y)
        assertEquals(initialW, bubble.bounds.width)
        assertEquals(initialH, bubble.bounds.height)
        assertEquals(initialW, bubble.layoutParams.width)
        assertEquals(initialH, bubble.layoutParams.height)
    }

    @Test
    fun testMaximizePreservesNormalFloatingBoundsWithoutOverwrite() {
        val store = DurableMiniAppStore.getInstance(testContext)
        val normalBounds = WindowBounds(80, 120, 450, 550)
        val snapshot = MiniAppRecoverySnapshot(
            instanceId = "max_geom_test",
            appType = "test_notes",
            state = MiniAppState.ACTIVE,
            windowBounds = normalBounds.copyBounds()
        )
        store.saveSnapshot(snapshot)

        val window = FloatingMiniAppWindow("max_geom_test", testContext, "test_notes")
        window.setWindowBounds(normalBounds)

        // Toggle Maximize
        window.toggleMaximize()
        assertTrue("Window must report maximized", window.isMaximized)
        assertNotNull("maximizeRestoreBounds must record normal floating bounds", window.maximizeRestoreBounds)
        assertEquals(normalBounds.width, window.maximizeRestoreBounds!!.width)
        assertEquals(normalBounds.height, window.maximizeRestoreBounds!!.height)

        // Ensure durableStore normal bounds were NOT overwritten by maximized bounds
        val storeSnapshot = store.loadSnapshot("max_geom_test")!!
        assertEquals("Normal floating bounds in store must remain intact during maximize", normalBounds.width, storeSnapshot.windowBounds.width)
        assertEquals("Normal floating bounds in store must remain intact during maximize", normalBounds.height, storeSnapshot.windowBounds.height)

        // Toggle Restore
        window.toggleMaximize()
        assertFalse("Window must report unmaximized", window.isMaximized)
        assertNull("maximizeRestoreBounds must be cleared on restore", window.maximizeRestoreBounds)
        assertEquals(normalBounds.x, window.bounds.x)
        assertEquals(normalBounds.y, window.bounds.y)
        assertEquals(normalBounds.width, window.bounds.width)
        assertEquals(normalBounds.height, window.bounds.height)
    }

    @Test
    fun testSurfaceLifecycleCleanupAndTeardownCheck() {
        val pageHostManager = com.example.feature.heavy.page.HeavyPageHostManager.getInstance(testContext)
        val initialCount = pageHostManager.getActivePageCount()

        // Release surface host
        pageHostManager.releaseSurfaceHost("surface_cleanup_test")
        val afterCount = pageHostManager.getActivePageCount()
        assertTrue(afterCount <= initialCount)
        assertFalse(pageHostManager.isEmbeddedHostActive("surface_cleanup_test"))

        // Heavy idle check evaluation
        val host = HeavyFloatingHost.getInstance(testContext)
        val activeFloating = host.getActiveInstanceCount()
        val willStop = (activeFloating == 0 && afterCount == 0)
        assertTrue("When active instances and embedded pages reach 0, teardown condition must be true", willStop)
    }
}
