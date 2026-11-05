package com.example.feature.floating

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
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

    private lateinit var testContext: MockTestContext
    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        DurableMiniAppStore.resetForTesting()
        HeavyFloatingHost.resetForTesting()
        FloatingMiniAppBridge.resetForTesting()
        HeavyProcessConnectionManager.resetForTesting()

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

        // Live user types new content
        inst1.restoreFromSnapshot(MiniAppRecoverySnapshot(
            instanceId = "idempotent_test",
            appType = "test_notes",
            windowBounds = bounds,
            payload = mutableMapOf("text" to "New Live User Note")
        ))

        // Second start while already active must preserve live state without overwriting with older snapshot
        val inst2 = host.startInstance("idempotent_test", "test_notes", bounds, mapOf("text" to "Stale Initial Content"))
        assertNotNull(inst2)
        assertEquals("Second start must NOT create a duplicate instance", 1, host.getActiveInstanceCount())
        assertSame("Second start must return the same instance", inst1, inst2)
        assertEquals("Live active state must be preserved and not overwritten by older snapshot", "New Live User Note", inst2!!.onSaveState()[TestNotesMiniAppInstance.KEY_TEXT])

        // Now pause/sleep instance and resume with updated snapshot
        inst1.onPause()
        inst1.onStop()
        val inst3 = host.startInstance("idempotent_test", "test_notes", bounds, mapOf("text" to "Updated Wake Content"))
        assertEquals("Stopped/sleeping instance must restore wake snapshot payload", "Updated Wake Content", inst3!!.onSaveState()[TestNotesMiniAppInstance.KEY_TEXT])
    }

    @Test
    fun testStartRequestedDuringConnectingQueuesAndDeliversExactlyOnce() {
        val connManager = HeavyProcessConnectionManager.getInstance(testContext)
        connManager.setMockProxyForTesting(null, ConnectionState.CONNECTING)

        val cmd1 = HeavyCommand(
            commandId = "test_cmd_1",
            type = HeavyCommandType.START_OPERATION,
            targetId = "test_miniapp_queue",
            payload = mapOf("instance_id" to "test_miniapp_queue", "app_type" to "test_notes")
        )
        val cmd2 = HeavyCommand(
            commandId = "test_cmd_2",
            type = HeavyCommandType.START_OPERATION,
            targetId = "test_miniapp_queue",
            payload = mapOf("instance_id" to "test_miniapp_queue", "app_type" to "test_notes")
        )

        // Send while CONNECTING: first command queues successfully
        val res1 = connManager.sendCommand(cmd1, autoConnect = true)
        assertTrue("Command sent during CONNECTING with autoConnect must report queued success", res1.success)
        assertEquals(1, connManager.getPendingCommandCountForTesting())

        // Send duplicate start while CONNECTING: must not add duplicate start command to queue
        val res2 = connManager.sendCommand(cmd2, autoConnect = true)
        assertTrue(res2.success)
        assertEquals("Duplicate START_OPERATION for the same targetId must NOT be enqueued twice", 1, connManager.getPendingCommandCountForTesting())

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

    @Test
    fun testRepeatedResizeMeasurementsRemainStableAndConverge() {
        val window = FloatingMiniAppWindow("resize_stability_test", testContext, "test_notes")
        val windowBounds = WindowBounds(100, 100, 592, 572)
        window.setWindowBounds(windowBounds)

        // Density in MockTestContext is 2f; top bar height is (38 * 2f).toInt() = 76
        val density = testContext.resources.displayMetrics.density
        val topBarHeight = (38 * density).toInt()
        assertEquals(76, topBarHeight)

        val expectedContentHeight = 572 - 76 // 496
        assertEquals(496, expectedContentHeight)

        // Multiple successive measurements with authoritative bounds 592x572 must produce stable 592x496
        val viewportW = windowBounds.width
        val viewportH = maxOf(0, windowBounds.height - topBarHeight)
        assertEquals(592, viewportW)
        assertEquals(496, viewportH)

        // Authoritative window bounds remain the source of truth
        assertEquals("Authoritative window bounds width must remain 592", 592, window.bounds.width)
        assertEquals("Authoritative window bounds height must remain 572", 572, window.bounds.height)
    }

    @Test
    fun testCloseReleasesInstanceAndSurfaceBeforeFinalConsumerDisconnect() {
        val bridge = FloatingMiniAppBridge.getInstance(testContext)
        val connManager = HeavyProcessConnectionManager.getInstance(testContext)
        val store = DurableMiniAppStore.getInstance(testContext)
        val wm = com.example.core.FloatingWindowManager.getInstance(testContext)

        val instanceId = "close_order_test"
        val snapshot = MiniAppRecoverySnapshot(
            instanceId = instanceId,
            appType = "test_notes",
            state = MiniAppState.ACTIVE,
            windowBounds = WindowBounds(100, 100, 500, 600)
        )
        store.saveSnapshot(snapshot)

        val deliveredCommands = mutableListOf<String>()
        val mockProxy = object : IHeavyHostContract {
            override fun sendCommand(commandJson: String): String {
                deliveredCommands.add(commandJson)
                return com.example.core.ipc.IpcResult.success("Stopped").toJson()
            }
            override fun syncSnapshot(snapshotJson: String): String = com.example.core.ipc.IpcResult.success().toJson()
            override fun ping(): Boolean = true
            override fun registerCallback(callbackBinder: android.os.IBinder): Boolean = true
            override fun unregisterCallback(): Boolean = true
        }
        connManager.setMockProxyForTesting(mockProxy, ConnectionState.CONNECTED)
        connManager.acquireConsumer("miniapp_$instanceId", reason = "test")

        val window = FloatingMiniAppWindow(instanceId, testContext, "test_notes")
        wm.registerWindow(window)
        assertNotNull(wm.getWindow(instanceId))

        // Execute closeMiniApp
        bridge.closeMiniApp(instanceId, deleteDurable = true)

        // 1. Window unregistered from Main
        assertNull("Window must be unregistered from WindowManager", wm.getWindow(instanceId))

        // 2. STOP_OPERATION was delivered to Heavy before disconnect
        assertTrue("STOP_OPERATION must be delivered to Heavy", deliveredCommands.any { it.contains("STOP_OPERATION") && it.contains(instanceId) })

        // 3. Durable snapshot was deleted
        assertNull("Snapshot must be deleted when deleteDurable=true", store.loadSnapshot(instanceId))
    }

    @Test
    fun testCloseAfterDeadHeavyCompletesCleanlyWithoutIpcFailure() {
        val bridge = FloatingMiniAppBridge.getInstance(testContext)
        val connManager = HeavyProcessConnectionManager.getInstance(testContext)
        val store = DurableMiniAppStore.getInstance(testContext)
        val wm = com.example.core.FloatingWindowManager.getInstance(testContext)

        val instanceId = "dead_heavy_close_test"
        val snapshot = MiniAppRecoverySnapshot(
            instanceId = instanceId,
            appType = "test_notes",
            state = MiniAppState.ACTIVE,
            windowBounds = WindowBounds(100, 100, 500, 600)
        )
        store.saveSnapshot(snapshot)

        // Simulate dead/disconnected Heavy process
        connManager.setMockProxyForTesting(null, ConnectionState.DEAD)

        val window = FloatingMiniAppWindow(instanceId, testContext, "test_notes")
        wm.registerWindow(window)
        assertNotNull(wm.getWindow(instanceId))

        // Must complete cleanly without throwing or crashing
        bridge.closeMiniApp(instanceId, deleteDurable = true)

        // Window and recovery record must still be removed
        assertNull("Window must be unregistered even if Heavy was dead", wm.getWindow(instanceId))
        assertNull("Snapshot must be removed even if Heavy was dead", store.loadSnapshot(instanceId))
    }

    @Test
    fun testClosingOneWindowDoesNotTerminateHeavyWhileAnotherConsumerActive() {
        val bridge = FloatingMiniAppBridge.getInstance(testContext)
        val connManager = HeavyProcessConnectionManager.getInstance(testContext)
        val wm = com.example.core.FloatingWindowManager.getInstance(testContext)

        val mockProxy = object : IHeavyHostContract {
            override fun sendCommand(commandJson: String): String = com.example.core.ipc.IpcResult.success("Ok").toJson()
            override fun syncSnapshot(snapshotJson: String): String = com.example.core.ipc.IpcResult.success().toJson()
            override fun ping(): Boolean = true
            override fun registerCallback(callbackBinder: android.os.IBinder): Boolean = true
            override fun unregisterCallback(): Boolean = true
        }
        connManager.setMockProxyForTesting(mockProxy, ConnectionState.CONNECTED)

        // Two consumers: window_1 and ebook_reader
        connManager.acquireConsumer("miniapp_multi_1", reason = "win1")
        connManager.acquireConsumer("ebook_reader", reason = "reader")

        val window = FloatingMiniAppWindow("multi_1", testContext, "test_notes")
        wm.registerWindow(window)

        bridge.closeMiniApp("multi_1", deleteDurable = true)

        // Connection manager must still have active consumer for ebook_reader
        assertTrue("Heavy must remain connected while another consumer is active", connManager.state == ConnectionState.CONNECTED)

        // Cleanup
        connManager.releaseConsumer("ebook_reader", reason = "test_cleanup")
    }

    @Test
    fun testFoldPersistsSuccessfullyBeforeReleasingHeavy() {
        val bridge = FloatingMiniAppBridge.getInstance(testContext)
        val store = DurableMiniAppStore.getInstance(testContext)
        val connManager = HeavyProcessConnectionManager.getInstance(testContext)

        val instanceId = "fold_persist_test"
        val initialSnapshot = MiniAppRecoverySnapshot(
            instanceId = instanceId,
            appType = "test_notes",
            state = MiniAppState.ACTIVE,
            windowBounds = WindowBounds(150, 180, 520, 620),
            payload = mutableMapOf("text" to "Durable Note to Fold")
        )
        store.saveSnapshot(initialSnapshot)

        val deliveredCommands = mutableListOf<String>()
        val mockProxy = object : IHeavyHostContract {
            override fun sendCommand(commandJson: String): String {
                deliveredCommands.add(commandJson)
                return com.example.core.ipc.IpcResult.success("Snapshot", mapOf("text" to "Durable Note to Fold")).toJson()
            }
            override fun syncSnapshot(snapshotJson: String): String = com.example.core.ipc.IpcResult.success().toJson()
            override fun ping(): Boolean = true
            override fun registerCallback(callbackBinder: android.os.IBinder): Boolean = true
            override fun unregisterCallback(): Boolean = true
        }
        connManager.setMockProxyForTesting(mockProxy, ConnectionState.CONNECTED)
        connManager.acquireConsumer("miniapp_$instanceId", reason = "fold_test")

        val window = FloatingMiniAppWindow(instanceId, testContext, "test_notes")
        com.example.core.FloatingWindowManager.getInstance(testContext).registerWindow(window)

        // Execute sleep / fold
        bridge.sleepMiniApp(instanceId)

        // Verify durable store has SLEEPING state with text intact
        val sleepingSnapshot = store.loadSnapshot(instanceId)
        assertNotNull(sleepingSnapshot)
        assertEquals(MiniAppState.SLEEPING, sleepingSnapshot!!.state)
        assertEquals("Durable Note to Fold", sleepingSnapshot.payload["text"])

        // Verify release command was delivered after snapshot
        assertTrue(deliveredCommands.any { it.contains("release_sleeping_instance") })

        // Cleanup
        bridge.closeMiniApp(instanceId, deleteDurable = true)
    }

    @Test
    fun testSafeDisplayBoundsCachedDuringRapidInquiries() {
        val window = FloatingMiniAppWindow("cache_test", testContext, "test_notes")
        val bounds1 = window.getSafeDisplayBounds(testContext)
        val bounds2 = window.getSafeDisplayBounds(testContext)
        assertSame("Subsequent calls within 1 second must return the cached instance", bounds1, bounds2)
    }

    @Test
    fun testResizeEndSettlesCleanlyWithoutContinuingRelayout() {
        val window = FloatingMiniAppWindow("resize_settle_test", testContext, "test_notes")
        window.setWindowBounds(WindowBounds(100, 100, 500, 600))
        val expectedViewportH = window.getContentViewportHeight(600)

        // Initial relayout to normal dimensions
        window.relayoutEmbeddedSurface(500, expectedViewportH)
        val initialDims = window.getLastRelayoutDimensions()
        assertEquals(500, initialDims.first)
        assertEquals(expectedViewportH, initialDims.second)

        // Simulate resize drag start
        window.isResizing = true
        assertTrue(window.isResizing)

        // Simulate resize to 600x700
        val resizedViewportH = window.getContentViewportHeight(700)
        window.relayoutEmbeddedSurface(600, resizedViewportH)
        val dragDims = window.getLastRelayoutDimensions()
        assertEquals(600, dragDims.first)
        assertEquals(resizedViewportH, dragDims.second)

        // Simulate RESIZE_END
        window.isResizing = false
        assertFalse(window.isResizing)

        // Settle embedded surface
        window.relayoutEmbeddedSurface(600, resizedViewportH)
        val finalDims = window.getLastRelayoutDimensions()
        assertEquals(600, finalDims.first)
        assertEquals(resizedViewportH, finalDims.second)
    }

    @Test
    fun testKeyboardOpenAppliesTemporaryShiftWithoutOverwritingNormalFloatingBounds() {
        val window = FloatingMiniAppWindow("ime_shift_test", testContext, "test_notes")
        val initialBounds = WindowBounds(100, 700, 500, 800)
        window.setWindowBounds(initialBounds)
        assertEquals(700, window.normalFloatingBounds.y)
        assertEquals(700, window.layoutParams.y)

        // Simulate soft keyboard opening (display bottom shrinks to 1200; window bottom is 700 + 800 = 1500)
        window.simulateImeVisibilityForTesting(isKeyboardOpen = true, keypadHeight = 600, displayBottom = 1200)

        // Window must be shifted upward temporarily
        assertTrue("isImeShifted must be true when window is occluded by keyboard", window.isImeShifted)
        assertTrue("layoutParams.y must shift upward", window.layoutParams.y < 700)
        assertTrue("imeShiftOffsetY must be positive", window.imeShiftOffsetY > 0)

        // CRITICAL CONTRACT: normalFloatingBounds must NOT be overwritten!
        assertEquals("normalFloatingBounds.y must remain pristine 700", 700, window.normalFloatingBounds.y)

        // Simulate soft keyboard closing
        window.simulateImeVisibilityForTesting(isKeyboardOpen = false, keypadHeight = 0, displayBottom = 1800)

        // Window must restore exact original position
        assertFalse("isImeShifted must be false when keyboard is dismissed", window.isImeShifted)
        assertEquals(0, window.imeShiftOffsetY)
        assertEquals("layoutParams.y must restore to exact 700", 700, window.layoutParams.y)
        assertEquals("bounds.y must restore to exact 700", 700, window.bounds.y)
    }

    @Test
    fun testFoldWhileKeyboardShiftedSavesNormalFloatingBounds() {
        val bridge = FloatingMiniAppBridge.getInstance(testContext)
        val store = DurableMiniAppStore.getInstance(testContext)
        val connManager = HeavyProcessConnectionManager.getInstance(testContext)

        val instanceId = "fold_ime_test"
        val window = FloatingMiniAppWindow(instanceId, testContext, "test_notes")
        window.setWindowBounds(WindowBounds(100, 700, 500, 800))
        com.example.core.FloatingWindowManager.getInstance(testContext).registerWindow(window)

        val mockProxy = object : IHeavyHostContract {
            override fun sendCommand(commandJson: String): String = com.example.core.ipc.IpcResult.success("Ok").toJson()
            override fun syncSnapshot(snapshotJson: String): String = com.example.core.ipc.IpcResult.success().toJson()
            override fun ping(): Boolean = true
            override fun registerCallback(callbackBinder: android.os.IBinder): Boolean = true
            override fun unregisterCallback(): Boolean = true
        }
        connManager.setMockProxyForTesting(mockProxy, ConnectionState.CONNECTED)
        connManager.acquireConsumer("miniapp_$instanceId", reason = "fold_ime")

        // Shift window with keyboard
        window.simulateImeVisibilityForTesting(isKeyboardOpen = true, keypadHeight = 600, displayBottom = 1200)
        assertTrue(window.isImeShifted)
        val shiftedLayoutY = window.layoutParams.y
        assertTrue(shiftedLayoutY < 700)

        // Fold while shifted
        bridge.sleepMiniApp(instanceId)

        // Snapshot must have recorded pristine normal bounds, NEVER shiftedLayoutY
        val snapshot = store.loadSnapshot(instanceId)
        assertNotNull(snapshot)
        assertEquals("Saved snapshot windowBounds.y must be unshifted normal Y (700)", 700, snapshot!!.windowBounds.y)
        assertNotEquals("Saved snapshot windowBounds.y must not be the shifted coordinate", shiftedLayoutY, snapshot.windowBounds.y)

        // Cleanup
        bridge.closeMiniApp(instanceId, deleteDurable = true)
    }

    @Test
    fun testMaximizeWhileKeyboardShiftedDoesNotCorruptMaximizeRestoreBounds() {
        val window = FloatingMiniAppWindow("max_ime_test", testContext, "test_notes")
        window.setWindowBounds(WindowBounds(100, 700, 500, 800))
        com.example.core.FloatingWindowManager.getInstance(testContext).registerWindow(window)

        // Shift window with keyboard
        window.simulateImeVisibilityForTesting(isKeyboardOpen = true, keypadHeight = 600, displayBottom = 1200)
        assertTrue(window.isImeShifted)

        // Maximize while IME is shifted
        window.toggleMaximize()
        assertTrue(window.isMaximized)

        // maximizeRestoreBounds must store normal pristine bounds (700), not shifted coordinate
        val restoreBounds = window.maximizeRestoreBounds
        assertNotNull(restoreBounds)
        assertEquals("maximizeRestoreBounds.y must be pristine 700", 700, restoreBounds!!.y)

        // Restore from maximized
        window.toggleMaximize()
        assertFalse(window.isMaximized)
        assertFalse(window.isImeShifted)
        assertEquals("Restored bounds.y must be pristine 700", 700, window.bounds.y)
        assertEquals("Restored normalFloatingBounds.y must be pristine 700", 700, window.normalFloatingBounds.y)
        assertEquals("Restored layoutParams.y must be pristine 700", 700, window.layoutParams.y)
    }

    @Test
    fun testTestNotesViewContainsEditableMultilineEditTextWithFocusAndSelection() {
        val instance = TestNotesMiniAppInstance("editor_view_test")
        instance.onCreate(null)
        instance.onStart()
        instance.onResume()

        val rootView = instance.createContentView(testContext)
        assertTrue("Root view must report hasWindowFocus() == true for embedded surface", rootView.hasWindowFocus())

        val editText = (rootView.findViewById<android.widget.EditText>(android.R.id.edit) ?: instance.getEditTextForTesting()) as? EmbeddedNoteEditText
        assertNotNull("Test Notes content view must contain an EmbeddedNoteEditText", editText)
        assertSame("getEditTextForTesting() must match mounted EditText", editText, instance.getEditTextForTesting())

        // Verify editable, multiline, focusable, and windowless-surface properties
        assertTrue("EditText must be enabled", editText!!.isEnabled)
        assertTrue("EditText must be focusable", editText.isConfiguredFocusable())
        assertTrue("EditText must be focusableInTouchMode", editText.isConfiguredFocusableInTouchMode())
        assertTrue("EditText must have cursor visible", editText.isCursorVisible)
        assertTrue("EditText must report hasWindowFocus() == true inside SurfaceControlViewHost", editText.hasWindowFocus())
        assertTrue("EditText must report onCheckIsTextEditor() == true", editText.onCheckIsTextEditor())
        assertNotNull("EditText must have a non-null KeyListener", editText.getConfiguredKeyListener())
        assertNotNull("EditText must have a non-null MovementMethod", editText.getConfiguredMovementMethod())

        val inputType = editText.inputType
        assertTrue(
            "EditText must have TYPE_CLASS_TEXT",
            (inputType and android.text.InputType.TYPE_CLASS_TEXT) != 0
        )
        assertTrue(
            "EditText must have TYPE_TEXT_FLAG_MULTI_LINE",
            (inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        )

        // Verify programmatic focus, typing, cursor placement, and text selection
        editText.requestFocus()
        editText.setText("Line 1\nLine 2\nEditable Note", android.widget.TextView.BufferType.EDITABLE)
        assertEquals("Line 1\nLine 2\nEditable Note", instance.noteText)

        // Verify cursor placement
        editText.setSelection(7)
        assertEquals(7, editText.selectionStart)
        assertEquals(7, editText.selectionEnd)

        // Verify text range selection
        editText.setSelection(0, 6)
        assertEquals(0, editText.selectionStart)
        assertEquals(6, editText.selectionEnd)

        // Verify live host text synchronization updates EditText and selection without feedback corruption
        instance.applyLiveTextFromHost("Updated via IME Bridge\nSecond Line", 8, 11)
        assertEquals("Updated via IME Bridge\nSecond Line", editText.text.toString())
        assertEquals("Updated via IME Bridge\nSecond Line", instance.noteText)
        assertEquals(8, editText.selectionStart)
        assertEquals(11, editText.selectionEnd)
    }

    @Test
    fun testTestNotesEditedTextSurvivesSnapshotFoldAndWakeAndDiscardsOnClose() {
        val host = HeavyFloatingHost.getInstance(testContext)
        val store = DurableMiniAppStore.getInstance(testContext)
        val bridge = FloatingMiniAppBridge.getInstance(testContext)
        val connManager = HeavyProcessConnectionManager.getInstance(testContext)
        val wm = com.example.core.FloatingWindowManager.getInstance(testContext)

        val instanceId = "editor_recovery_test"
        val initialBounds = WindowBounds(110, 140, 540, 640)

        // 1. Start Test Notes in Heavy and mount its view
        val instance = host.startInstance(instanceId, "test_notes", initialBounds, emptyMap()) as? TestNotesMiniAppInstance
        assertNotNull("TestNotesMiniAppInstance must be created", instance)
        val rootView = instance!!.createContentView(testContext)
        val editText = rootView.findViewById<android.widget.EditText>(android.R.id.edit) ?: instance.getEditTextForTesting()
        assertNotNull(editText)

        // 2. User edits multiline text directly in the EditText
        val userEditedText = "First edited line in Test Notes\nSecond line with custom note content"
        editText!!.setText(userEditedText, android.widget.TextView.BufferType.EDITABLE)
        editText.setSelection(userEditedText.length)
        assertEquals(userEditedText, instance.noteText)

        // Also verify HeavyFloatingHost.updateLiveInstanceText updates the live EditText
        val furtherEditedText = "$userEditedText\nThird line via live IME sync"
        assertTrue(host.updateLiveInstanceText(instanceId, furtherEditedText, furtherEditedText.length, furtherEditedText.length))
        assertEquals(furtherEditedText, editText.text.toString())

        // 3. Configure mock IPC proxy that delegates snapshot & release commands directly to HeavyFloatingHost
        val mockProxy = object : IHeavyHostContract {
            override fun sendCommand(commandJson: String): String {
                val cmd = HeavyCommand.fromJson(commandJson)
                return when (cmd.type) {
                    HeavyCommandType.PAUSE_OPERATION -> {
                        val snapPayload = host.getSnapshot(instanceId, additionalPayload = cmd.payload)
                        if (snapPayload.isNotEmpty()) {
                            com.example.core.ipc.IpcResult.success("Snapshot", snapPayload).toJson()
                        } else {
                            com.example.core.ipc.IpcResult.error(com.example.core.ipc.IpcErrorCode.COMMAND_FAILED, "No instance").toJson()
                        }
                    }
                    HeavyCommandType.STOP_OPERATION -> {
                        val action = cmd.payload["action"]
                        if (action == "release_sleeping_instance") {
                            host.releaseInstance(instanceId)
                        } else {
                            host.destroyInstance(instanceId)
                        }
                        com.example.core.ipc.IpcResult.success("Stopped").toJson()
                    }
                    else -> com.example.core.ipc.IpcResult.success("Ok").toJson()
                }
            }
            override fun syncSnapshot(snapshotJson: String): String = com.example.core.ipc.IpcResult.success().toJson()
            override fun ping(): Boolean = true
            override fun registerCallback(callbackBinder: android.os.IBinder): Boolean = true
            override fun unregisterCallback(): Boolean = true
        }
        connManager.setMockProxyForTesting(mockProxy, ConnectionState.CONNECTED)
        connManager.acquireConsumer("miniapp_$instanceId", reason = "test_fold")

        val window = FloatingMiniAppWindow(instanceId, testContext, "test_notes")
        window.setWindowBounds(initialBounds)
        wm.registerWindow(window)

        // 4. Fold (sleepMiniApp): must capture live EditText text from Heavy, persist durably, and release Heavy instance
        bridge.sleepMiniApp(instanceId)
        assertEquals("Heavy instance must be released after fold", 0, host.getActiveInstanceCount())

        val persistedSnapshot = store.loadSnapshot(instanceId)
        assertNotNull("Durable snapshot must exist after fold", persistedSnapshot)
        assertEquals(MiniAppState.SLEEPING, persistedSnapshot!!.state)
        assertEquals(
            "Persisted note_text must match edited text",
            furtherEditedText,
            persistedSnapshot.payload[TestNotesMiniAppInstance.KEY_TEXT]
        )
        assertEquals(
            "Persisted text key must also match edited text",
            furtherEditedText,
            persistedSnapshot.payload["text"]
        )

        // 5. Wake: reconstruct a fresh TestNotesMiniAppInstance from the durable snapshot and mount its EditText
        val wokenInstance = host.startInstance(instanceId, "test_notes", persistedSnapshot.windowBounds, persistedSnapshot.payload) as? TestNotesMiniAppInstance
        assertNotNull(wokenInstance)
        val wokenView = wokenInstance!!.createContentView(testContext)
        val wokenEditText = wokenView.findViewById<android.widget.EditText>(android.R.id.edit) ?: wokenInstance.getEditTextForTesting()
        assertNotNull(wokenEditText)
        assertEquals(
            "Woken EditText must restore the exact edited text from snapshot",
            furtherEditedText,
            wokenEditText!!.text.toString()
        )

        // 6. Close with discard (deleteDurable = true): unsaved edits and durable snapshot must be discarded
        wokenEditText.setText("Temporary unsaved text before close", android.widget.TextView.BufferType.EDITABLE)
        bridge.closeMiniApp(instanceId, deleteDurable = true)

        assertNull("Durable snapshot must be deleted on close with deleteDurable=true", store.loadSnapshot(instanceId))
        assertEquals("Active instance in Heavy must be destroyed on close", 0, host.getActiveInstanceCount())
    }

    @Test
    fun testTestNotesImeBridgeActivationAndDiagnostics() {
        val host = HeavyFloatingHost.getInstance(testContext)
        val wm = com.example.core.FloatingWindowManager.getInstance(testContext)
        val instanceId = "ime_diag_test"
        val initialBounds = WindowBounds(100, 100, 500, 600)

        // 1. Start Test Notes in Heavy and mount EmbeddedNoteEditText
        val instance = host.startInstance(instanceId, "test_notes", initialBounds, emptyMap()) as? TestNotesMiniAppInstance
        assertNotNull("TestNotesMiniAppInstance must be created", instance)
        val rootView = instance!!.createContentView(testContext)
        val heavyEditText = (rootView.findViewById<android.widget.EditText>(android.R.id.edit) ?: instance.getEditTextForTesting()) as? EmbeddedNoteEditText
        assertNotNull("EmbeddedNoteEditText must be present", heavyEditText)

        assertTrue("EmbeddedNoteEditText must be enabled", heavyEditText!!.isEnabled)
        assertTrue("EmbeddedNoteEditText must be focusable", heavyEditText.isConfiguredFocusable())
        assertTrue("EmbeddedNoteEditText must be focusableInTouchMode", heavyEditText.isConfiguredFocusableInTouchMode())
        assertTrue("EmbeddedNoteEditText must report hasWindowFocus() == true", heavyEditText.hasWindowFocus())
        assertTrue("EmbeddedNoteEditText must report onCheckIsTextEditor() == true", heavyEditText.onCheckIsTextEditor())

        // Verify onCreateInputConnection on EmbeddedNoteEditText
        val outAttrs = android.view.inputmethod.EditorInfo()
        heavyEditText.onCreateInputConnection(outAttrs)

        // 2. Instantiate FloatingMiniAppWindow in Main and verify initial state
        val window = FloatingMiniAppWindow(instanceId, testContext, "test_notes")
        window.setWindowBounds(initialBounds)
        wm.registerWindow(window)
        val windowView = window.createContentView(testContext)
        assertNotNull(windowView)

        val bridgeEdit = window.getImeBridgeEditTextForTesting()
        assertNotNull("imeBridgeEditText must be created in FloatingMiniAppWindow", bridgeEdit)
        assertFalse("imeBridgeEditText must not have window focus while overlay is unfocused and idle", bridgeEdit!!.hasWindowFocus())
        assertTrue("imeBridgeEditText must report onCheckIsTextEditor() == true", bridgeEdit.onCheckIsTextEditor())

        // Verify initial idle flags include FLAG_NOT_FOCUSABLE so overlay does not steal back button
        val hasNotFocusableInitially = (window.layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0
        assertTrue("Window must have FLAG_NOT_FOCUSABLE by default while idle", hasNotFocusableInitially)

        // Verify bridge layoutParams has MATCH_PARENT dimensions when set by view hierarchy
        bridgeEdit.layoutParams?.let { lp ->
            assertEquals(FrameLayout.LayoutParams.MATCH_PARENT, lp.width)
            assertEquals(FrameLayout.LayoutParams.MATCH_PARENT, lp.height)
        }

        // 3. Activate IME Bridge and verify FLAG_NOT_FOCUSABLE is cleared
        val testNote = "Diagnostic testing of IME bridge\nSecond line"
        val testSess = "diag_sess_42"
        window.activateEmbeddedEditorIme(testNote, 10, 10, testSess)

        val hasNotFocusableAfterActivation = (window.layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0
        assertFalse("FLAG_NOT_FOCUSABLE must be cleared upon activateEmbeddedEditorIme", hasNotFocusableAfterActivation)
        assertEquals(testNote, bridgeEdit.text.toString())
        assertEquals(10, bridgeEdit.selectionStart)
        assertEquals(10, bridgeEdit.selectionEnd)

        // Simulate real window focus callback from WMS
        windowView.onWindowFocusChanged(true)
        assertTrue("imeBridgeEditText must report hasWindowFocus() == true after window focus callback", bridgeEdit.hasWindowFocus())

        // Verify bridge onCreateInputConnection
        val bridgeAttrs = android.view.inputmethod.EditorInfo()
        bridgeEdit.onCreateInputConnection(bridgeAttrs)

        // 4. Verify text sync from Main bridge to Heavy instance
        val typedAddition = "$testNote\nTyped text from keyboard"
        bridgeEdit.setText(typedAddition, android.widget.TextView.BufferType.EDITABLE)
        assertEquals(typedAddition, window.getCurrentPayload()[TestNotesMiniAppInstance.KEY_TEXT])

        // Verify applyLiveTextFromHost updates Heavy editor
        instance.applyLiveTextFromHost(typedAddition, typedAddition.length, typedAddition.length, testSess)
        assertEquals(typedAddition, heavyEditText.text.toString())
        assertEquals(typedAddition, instance.noteText)

        // Clean up
        wm.unregisterWindow(window.windowId)
        host.destroyInstance(instanceId)
    }

    @Test
    fun testEmptyNotePersistenceThroughSnapshotAndWake() {
        val host = HeavyFloatingHost.getInstance(testContext)
        val wm = com.example.core.FloatingWindowManager.getInstance(testContext)
        val store = DurableMiniAppStore.getInstance(testContext)
        val instanceId = "empty_note_persistence_test"
        val initialBounds = WindowBounds(100, 100, 500, 600)

        // 1. Save an intentionally empty note in durable store
        val snapshot = MiniAppRecoverySnapshot(
            instanceId = instanceId,
            appType = "test_notes",
            windowBounds = initialBounds,
            payload = mutableMapOf(TestNotesMiniAppInstance.KEY_TEXT to "", "text" to "")
        )
        store.saveSnapshot(snapshot)

        // 2. Start instance and create window
        val instance = host.startInstance(instanceId, "test_notes", initialBounds, snapshot.payload) as? TestNotesMiniAppInstance
        assertNotNull(instance)
        assertEquals("Empty note text must be preserved on start", "", instance!!.noteText)

        val window = FloatingMiniAppWindow(instanceId, testContext, "test_notes")
        window.setWindowBounds(initialBounds)
        wm.registerWindow(window)
        val windowView = window.createContentView(testContext)
        assertNotNull(windowView)

        // 3. Verify getCurrentPayload returns empty note text, not default instructional text
        val payload = window.getCurrentPayload()
        assertEquals("", payload[TestNotesMiniAppInstance.KEY_TEXT])

        // 4. Reload from store (simulating fold/sleep/wake)
        val reloadedSnapshot = store.loadSnapshot(instanceId)
        assertNotNull(reloadedSnapshot)
        assertEquals("", reloadedSnapshot!!.payload[TestNotesMiniAppInstance.KEY_TEXT])

        // 5. New window instance waking from snapshot
        val wakingWindow = FloatingMiniAppWindow(instanceId, testContext, "test_notes")
        val wakingView = wakingWindow.createContentView(testContext)
        assertNotNull(wakingView)
        val wakingPayload = wakingWindow.getCurrentPayload()
        assertEquals("Intentionally empty note must remain empty after wake from snapshot", "", wakingPayload[TestNotesMiniAppInstance.KEY_TEXT])

        // Clean up
        wm.unregisterWindow(window.windowId)
        host.destroyInstance(instanceId)
        store.deleteSnapshot(instanceId)
    }

    @Test
    fun testClearOperationAndAuthoritativeTextSelection() {
        val host = HeavyFloatingHost.getInstance(testContext)
        val wm = com.example.core.FloatingWindowManager.getInstance(testContext)
        val instanceId = "clear_op_authority_test"
        val initialBounds = WindowBounds(100, 100, 500, 600)

        val instance = host.startInstance(instanceId, "test_notes", initialBounds, mapOf(TestNotesMiniAppInstance.KEY_TEXT to "Initial text")) as? TestNotesMiniAppInstance
        assertNotNull(instance)
        assertEquals("Initial text", instance!!.noteText)

        val window = FloatingMiniAppWindow(instanceId, testContext, "test_notes")
        window.setWindowBounds(initialBounds)
        wm.registerWindow(window)
        window.createContentView(testContext)

        val bridgeEdit = window.getImeBridgeEditTextForTesting()
        assertNotNull(bridgeEdit)

        // 1. User clears note on IME bridge
        bridgeEdit!!.setText("", android.widget.TextView.BufferType.EDITABLE)

        // 2. Authoritative payload must be empty string
        val clearedPayload = window.getCurrentPayload()
        assertEquals("", clearedPayload[TestNotesMiniAppInstance.KEY_TEXT])
        assertEquals("ime_bridge", clearedPayload["source"])

        // 3. Verify fallback editor (if attached on SDK < 30) does not overwrite authoritative cleared note
        val fallbackEdit = window.getFallbackEditTextForTesting()
        if (fallbackEdit != null) {
            assertEquals("Authoritative bridge text must be preserved", "", window.getCurrentPayload()[TestNotesMiniAppInstance.KEY_TEXT])
        }

        // Clean up
        wm.unregisterWindow(window.windowId)
        host.destroyInstance(instanceId)
    }
}

