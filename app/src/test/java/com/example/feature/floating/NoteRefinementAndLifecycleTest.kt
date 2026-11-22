package com.example.feature.floating

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Handler
import android.view.WindowManager
import com.example.core.FakeSharedPreferences
import com.example.core.FloatingWindowManager
import com.example.core.WindowBounds
import com.example.feature.element.ElementIdParser
import com.example.feature.sidebar.ALL_FLOATING_WINDOWS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Focused regression suite for Note refinement & real fold/wake persistence lifecycle:
 * - Complete user-facing rename from "Test Notes" to "Note" with zero-migration key preservation
 * - Real production fold/wake transaction (FloatingMiniAppBridge.sleepMiniApp -> wakeMiniApp)
 *   covering latest text, formatting, accent color, undo/redo history, launcher bubble,
 *   stale debounce protection, permanent deletion guard, and fold-abort on save failure
 */
class NoteRefinementAndLifecycleTest {

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
                widthPixels = 1080
                heightPixels = 1920
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
            return if (name == Context.WINDOW_SERVICE) mockWindowManager else null
        }
    }

    private lateinit var context: MockTestContext
    private lateinit var store: DurableMiniAppStore
    private lateinit var bridge: FloatingMiniAppBridge

    @Before
    fun setUp() {
        NotePersistenceCoordinator.TEST_DISPATCH_IMMEDIATELY = true
        val prefs = FakeSharedPreferences()
        val filesDir = tempFolder.newFolder("note_lifecycle_test")
        context = MockTestContext(prefs, filesDir)
        DurableMiniAppStore.resetForTesting()
        FloatingMiniAppBridge.resetForTesting()
        FloatingWindowManager.resetForTesting()
        store = DurableMiniAppStore.getInstance(context)
        bridge = FloatingMiniAppBridge.getInstance(context)
    }

    @After
    fun tearDown() {
        NotePersistenceCoordinator.TEST_DISPATCH_IMMEDIATELY = false
        FloatingMiniAppBridge.resetForTesting()
        FloatingWindowManager.resetForTesting()
        DurableMiniAppStore.resetForTesting()
    }

    private fun resolveProjectPath(relativeFromRoot: String): File {
        val fromApp = relativeFromRoot.removePrefix("app/")
        val candidates = listOf(File(relativeFromRoot), File(fromApp), File("../$relativeFromRoot"))
        return candidates.first { it.exists() }
    }

    @Test
    fun testAllUserFacingTestNotesStringsRenamedToNoteWhilePreservingInternalKeys() {
        // 1. ALL_FLOATING_WINDOWS preserves "test_notes" key and displays "Note"
        val noteAction = ALL_FLOATING_WINDOWS.first { it.action == "test_notes" }
        assertEquals("Note", noteAction.label)

        // 2. ElementIdParser preserves page_window:test_notes and system:test_notes keys with "Note" display label
        val parsedSys = ElementIdParser.parse(context, "system:test_notes") as com.example.feature.sidebar.SidebarItem.SystemAction
        assertEquals("test_notes", parsedSys.action)
        assertEquals("Note", parsedSys.label)

        val parsedPage = ElementIdParser.parse(context, "page_window:test_notes") as com.example.feature.sidebar.SidebarItem.PageWindow
        assertEquals("test_notes", parsedPage.pageType)
        assertEquals("Window: Note", parsedPage.label)

        // 3. No active production Kotlin file still shows user-facing "Test Notes"
        val mainSrcDir = resolveProjectPath("app/src/main/java")
        val remaining = mainSrcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("\"Test Notes\"") }
            .toList()
        assertTrue("Expected zero remaining \"Test Notes\" string literals in main Kotlin sources, found: $remaining", remaining.isEmpty())

        // 4. Existing persisted "test_notes" snapshot with "note_text" key loads cleanly as "Note"
        store.saveSnapshot(
            MiniAppRecoverySnapshot(
                instanceId = "legacy_note_1",
                appType = "test_notes",
                state = MiniAppState.ACTIVE,
                windowBounds = WindowBounds(100, 120, 600, 700),
                payload = mutableMapOf(
                    TestNotesMiniAppInstance.KEY_TEXT to "Existing saved user note",
                    NotePersistenceCoordinator.KEY_ACCENT_COLOR to "#CBA6F7"
                )
            )
        )
        val win = bridge.openMiniApp("legacy_note_1", "test_notes")
        assertEquals("Note", win.getWindowTitleForTesting())
        assertEquals("Existing saved user note", win.getAuthoritativeNoteText())
        assertEquals("#CBA6F7", win.currentAccentColorHex)
    }

    @Test
    fun testNotePermanentCloseCancelsPendingDebounceAndNeverResurrectsDeletedSnapshot() {
        NotePersistenceCoordinator.TEST_DISPATCH_IMMEDIATELY = false
        val directExecutor = Executors.newSingleThreadExecutor()
        try {
            val coordinator = NotePersistenceCoordinator(
                instanceId = "note_delete_guard",
                context = context,
                durableStore = store,
                mainHandler = Handler(),
                backgroundExecutor = directExecutor
            )
            coordinator.updateStateSnapshot(
                text = "Draft before delete",
                fontSize = 15f,
                fontStyle = "regular",
                wordWrap = true,
                lineSpacing = 1.2f,
                bounds = WindowBounds(100, 100, 500, 600),
                accentColor = "#89B4FA"
            )
            coordinator.scheduleDebouncedSave(400L)
            val staleRunnable = coordinator.getPendingDebounceRunnableForTesting()
            assertNotNull("Expected debounced save runnable to be scheduled", staleRunnable)
            assertTrue(coordinator.hasPendingDebounceRunnableForTesting())

            // Simulate permanent close/delete
            coordinator.markDeletedPermanently()
            store.deleteSnapshot("note_delete_guard")

            assertTrue(coordinator.isMarkedDeletedForTesting())
            assertFalse(coordinator.hasPendingDebounceRunnableForTesting())
            assertFalse(coordinator.hasPendingEdits())
            assertNull("Pending debounce runnable must be cleared on permanent delete", coordinator.getPendingDebounceRunnableForTesting())

            // Even if a stale runnable was already in-flight and executes now, it MUST NOT revive the deleted note
            staleRunnable!!.run()
            directExecutor.submit {}.get()
            assertNull("Deleted note snapshot must NEVER be resurrected by stale debounce callback", store.loadSnapshot("note_delete_guard"))

            // Subsequent debounced saves or flushes must also be ignored
            coordinator.scheduleDebouncedSave(0L)
            assertFalse(coordinator.flushPendingSave())
            assertNull(store.loadSnapshot("note_delete_guard"))
        } finally {
            directExecutor.shutdownNow()
        }
    }

    @Test
    fun testNoteFlushBeforeSleepPreservesLatestTextAndUndoRedoAndIgnoresStaleDebounce() {
        val win = bridge.openMiniApp("note_lifecycle_1", "test_notes")
        win.notePersistence.pushUndoState("v1")
        win.notePersistence.pushUndoState("v2")
        win.notePersistence.updateStateSnapshot(
            text = "v3 latest unsaved",
            fontSize = 17f,
            fontStyle = "bold",
            wordWrap = false,
            lineSpacing = 1.5f,
            bounds = WindowBounds(80, 90, 560, 720),
            accentColor = "#A6E3A1"
        )
        // Perform one undo so both undoStack and redoStack have entries
        val afterUndo = win.notePersistence.performUndo("v3 latest unsaved")
        assertEquals("v2", afterUndo)

        // Update current text on window to match and sleep
        win.notePersistence.flushPendingSave(targetState = MiniAppState.SLEEPING)

        val snap = store.loadSnapshot("note_lifecycle_1")
        assertNotNull(snap)
        assertEquals(MiniAppState.SLEEPING, snap!!.state)
        assertEquals("v2", snap.payload[TestNotesMiniAppInstance.KEY_TEXT])
        assertEquals("#A6E3A1", snap.payload[NotePersistenceCoordinator.KEY_ACCENT_COLOR])
        assertTrue(snap.payload[NotePersistenceCoordinator.KEY_UNDO_HISTORY]!!.contains("v1"))
        assertTrue(snap.payload[NotePersistenceCoordinator.KEY_REDO_HISTORY]!!.contains("v3 latest unsaved"))

        // Reopen into a fresh window (simulating wake / process recreation)
        FloatingWindowManager.getInstance(context).unregisterWindow("note_lifecycle_1")
        val restored = bridge.openMiniApp("note_lifecycle_1", "test_notes")
        assertEquals("v2", restored.getAuthoritativeNoteText())
        assertEquals(listOf("v1"), restored.getUndoStackForTesting().toList())
        assertEquals(listOf("v3 latest unsaved"), restored.getRedoStackForTesting().toList())

        // Permanent close through bridge deletes snapshot cleanly without onDestroy re-saving it
        var closedOk = false
        bridge.closeMiniApp("note_lifecycle_1", deleteDurable = true) { closedOk = it }
        assertTrue(closedOk)
        assertNull("Permanent close must not leave resurrected durable snapshot", store.loadSnapshot("note_lifecycle_1"))
    }

    @Test
    fun testRealNoteFoldAndWakeTransactionPreservesTextFormattingAccentUndoRedoAndIgnoresStaleDebounce() {
        // Exercise the real asynchronous debounce + flushPendingSaveAsync path (no immediate-dispatch shortcut)
        NotePersistenceCoordinator.TEST_DISPATCH_IMMEDIATELY = false
        val wm = FloatingWindowManager.getInstance(context)
        val noteId = "note_real_fold_wake_1"

        // 1. Open Note through normal production bridge path
        val win = bridge.openMiniApp(noteId, "test_notes")
        assertNotNull("Expected Note editor view to be created on open", win.getFallbackEditTextForTesting())

        // 2. Type text, capture an early stale debounce runnable, then apply final edits, formatting, accent & undo/redo
        win.setNoteTextForTesting("Early draft")
        val staleEarlyDebounce = win.notePersistence.getPendingDebounceRunnableForTesting()
        assertNotNull("Expected real debounced save runnable to be queued", staleEarlyDebounce)

        win.setNoteTextForTesting("Second revision")
        win.setNoteTextForTesting("Third revision before undo")
        win.applyNoteFormatting(
            fontSizeSp = 19f,
            fontStyle = "monospace",
            wordWrap = false,
            lineSpacingMult = 1.5f
        )
        win.applyNoteAccentColor("#F9E2AF", persist = true)

        // Undo once so both undoStack ("", "Early draft") and redoStack ("Third revision before undo") are populated
        assertTrue("Undo before fold must succeed", win.performUndoForTesting())
        assertEquals("Second revision", win.getAuthoritativeNoteText())
        val stalePreFoldDebounce = win.notePersistence.getPendingDebounceRunnableForTesting()
        assertNotNull("Expected pending debounce runnable right before fold", stalePreFoldDebounce)

        // 3. Immediately fold via REAL FloatingMiniAppBridge.sleepMiniApp transaction (deterministic latch, no Thread.sleep)
        val foldLatch = CountDownLatch(1)
        val foldResult = AtomicReference<Boolean?>()
        bridge.sleepMiniApp(noteId) { ok ->
            foldResult.set(ok)
            foldLatch.countDown()
        }
        assertTrue("Real sleepMiniApp transaction timed out", foldLatch.await(3, TimeUnit.SECONDS))
        assertEquals(true, foldResult.get())

        // 4. Verify runtime release, window unregistration, launcher bubble creation, and durable SLEEPING snapshot
        assertFalse("Pending debounce must be cleared by fold transaction", win.notePersistence.hasPendingDebounceRunnableForTesting())
        assertNull("Runtime Note editor view must be released on fold teardown", win.getFallbackEditTextForTesting())
        assertNull("Folded Note window must be unregistered from FloatingWindowManager", wm.getWindow(noteId))
        val bubble = wm.getWindow("bubble_$noteId") as? FloatingMiniAppLauncherBubble
        assertNotNull("Launcher bubble must appear after successful fold", bubble)
        assertTrue("Launcher bubble must be visible", bubble!!.isVisible)

        val sleepingSnap = store.loadSnapshot(noteId)
        assertNotNull("Durable snapshot must exist after fold", sleepingSnap)
        assertEquals(MiniAppState.SLEEPING, sleepingSnap!!.state)
        assertEquals("Second revision", sleepingSnap.payload[TestNotesMiniAppInstance.KEY_TEXT])
        assertEquals("19.0", sleepingSnap.payload["font_size"])
        assertEquals("monospace", sleepingSnap.payload["font_style"])
        assertEquals("false", sleepingSnap.payload["word_wrap"])
        assertEquals("1.5", sleepingSnap.payload["line_spacing"])
        assertEquals("#F9E2AF", sleepingSnap.payload[NotePersistenceCoordinator.KEY_ACCENT_COLOR])
        assertTrue(sleepingSnap.payload[NotePersistenceCoordinator.KEY_UNDO_HISTORY]!!.contains("Early draft"))
        assertTrue(sleepingSnap.payload[NotePersistenceCoordinator.KEY_REDO_HISTORY]!!.contains("Third revision before undo"))

        // 5. Stale pending debounce callbacks fired while sleeping MUST NOT overwrite sleeping snapshot
        staleEarlyDebounce!!.run()
        stalePreFoldDebounce!!.run()
        val snapAfterStaleWhileSleeping = store.loadSnapshot(noteId)!!
        assertEquals(MiniAppState.SLEEPING, snapAfterStaleWhileSleeping.state)
        assertEquals("Second revision", snapAfterStaleWhileSleeping.payload[TestNotesMiniAppInstance.KEY_TEXT])

        // 6. Wake Note through normal production bridge path (dismissing launcher bubble and reconstructing window)
        bridge.wakeMiniApp(noteId)
        assertNull("Launcher bubble must be dismissed on wake", wm.getWindow("bubble_$noteId"))
        val wokenWin = wm.getWindow(noteId) as? FloatingMiniAppWindow
        assertNotNull("Woken Note window must be registered", wokenWin)
        assertTrue("Woken Note must be a fresh reconstructed instance", wokenWin !== win)
        assertEquals("Second revision", wokenWin!!.getAuthoritativeNoteText())
        assertEquals(19f, wokenWin.currentFontSizeSp, 0.001f)
        assertEquals("monospace", wokenWin.currentFontStyle)
        assertFalse(wokenWin.currentWordWrap)
        assertEquals(1.5f, wokenWin.currentLineSpacingMult, 0.001f)
        assertEquals("#F9E2AF", wokenWin.currentAccentColorHex)
        assertTrue(wokenWin.getUndoStackForTesting().contains("Early draft"))
        assertEquals(listOf("Third revision before undo"), wokenWin.getRedoStackForTesting().toList())

        // Verify live Redo and Undo still function on the reconstructed window
        assertTrue("Redo after wake must restore Third revision", wokenWin.performRedoForTesting())
        assertEquals("Third revision before undo", wokenWin.getAuthoritativeNoteText())
        assertTrue("Undo after wake must return to Second revision", wokenWin.performUndoForTesting())
        assertEquals("Second revision", wokenWin.getAuthoritativeNoteText())

        // 7. Type brand-new final characters immediately before a second real fold to verify zero-loss final keystrokes
        wokenWin.setNoteTextForTesting("Final keystroke right before fold!")
        val foldLatch2 = CountDownLatch(1)
        val foldResult2 = AtomicReference<Boolean?>()
        bridge.sleepMiniApp(noteId) { ok ->
            foldResult2.set(ok)
            foldLatch2.countDown()
        }
        assertTrue("Second sleepMiniApp transaction timed out", foldLatch2.await(3, TimeUnit.SECONDS))
        assertEquals(true, foldResult2.get())

        // Fire old stale runnables from first window AFTER newer edits were committed
        staleEarlyDebounce.run()
        stalePreFoldDebounce.run()

        bridge.wakeMiniApp(noteId)
        val wokenAgain = wm.getWindow(noteId) as? FloatingMiniAppWindow
        assertNotNull(wokenAgain)
        assertEquals("Final keystroke right before fold!", wokenAgain!!.getAuthoritativeNoteText())
        assertEquals(19f, wokenAgain.currentFontSizeSp, 0.001f)
        assertEquals("monospace", wokenAgain.currentFontStyle)
        assertFalse(wokenAgain.currentWordWrap)
        assertEquals(1.5f, wokenAgain.currentLineSpacingMult, 0.001f)
        assertEquals("#F9E2AF", wokenAgain.currentAccentColorHex)

        // 8. Queue an unsaved edit and permanently delete through real bridge path; stale debounce must not resurrect snapshot
        wokenAgain.setNoteTextForTesting("Draft right before permanent delete")
        val staleBeforeDelete = wokenAgain.notePersistence.getPendingDebounceRunnableForTesting()
        assertNotNull(staleBeforeDelete)

        val closeLatch = CountDownLatch(1)
        val closeResult = AtomicReference<Boolean?>()
        bridge.closeMiniApp(noteId, deleteDurable = true) { ok ->
            closeResult.set(ok)
            closeLatch.countDown()
        }
        assertTrue("Permanent close timed out", closeLatch.await(3, TimeUnit.SECONDS))
        assertEquals(true, closeResult.get())
        assertNull(store.loadSnapshot(noteId))

        staleBeforeDelete!!.run()
        staleEarlyDebounce.run()
        assertNull("Deleted Note snapshot must never be resurrected by stale callbacks", store.loadSnapshot(noteId))
    }

    @Test
    fun testRealNoteFoldAbortsCleanlyWhenDurableSaveFails() {
        NotePersistenceCoordinator.TEST_DISPATCH_IMMEDIATELY = false

        // Inject a controllable real DurableMiniAppStore subclass into the production singleton pipeline
        var failSaves = false
        val controlledStore = object : DurableMiniAppStore(context) {
            override fun saveSnapshot(snapshot: MiniAppRecoverySnapshot): Boolean {
                if (failSaves) return false
                return super.saveSnapshot(snapshot)
            }
        }
        FloatingMiniAppBridge.resetForTesting()
        FloatingWindowManager.resetForTesting()
        DurableMiniAppStore.setInstanceForTesting(controlledStore)

        val localBridge = FloatingMiniAppBridge.getInstance(context)
        val wm = FloatingWindowManager.getInstance(context)
        val noteId = "note_fold_failure_abort"

        val win = localBridge.openMiniApp(noteId, "test_notes")
        win.setNoteTextForTesting("Critical unsaved note content")
        win.applyNoteFormatting(fontSizeSp = 18f, fontStyle = "bold", wordWrap = false, lineSpacingMult = 1.4f)
        win.applyNoteAccentColor("#A6E3A1", persist = true)

        // Force durable persistence failure during the real fold transaction
        failSaves = true
        val failedFoldLatch = CountDownLatch(1)
        val failedFoldOk = AtomicReference<Boolean?>()
        localBridge.sleepMiniApp(noteId) { ok ->
            failedFoldOk.set(ok)
            failedFoldLatch.countDown()
        }
        assertTrue("Failed sleepMiniApp transaction timed out", failedFoldLatch.await(3, TimeUnit.SECONDS))
        assertEquals("Fold must report failure when durable save fails", false, failedFoldOk.get())

        // Window MUST NOT be destroyed, unregistered, or replaced with a launcher bubble on save failure
        val stillOpen = wm.getWindow(noteId) as? FloatingMiniAppWindow
        assertTrue("Note window must remain open and registered after fold persistence failure", stillOpen === win)
        assertTrue("Note window must remain attached", win.isAttached)
        assertEquals(com.example.core.WindowState.VISIBLE, win.state)
        assertNotNull("Runtime editor view must NOT be destroyed on fold failure", win.getFallbackEditTextForTesting())
        assertEquals("Critical unsaved note content", win.getAuthoritativeNoteText())
        assertNull("Launcher bubble must NOT be shown when fold failed", wm.getWindow("bubble_$noteId"))
        assertEquals(
            "Durable snapshot must NOT transition to SLEEPING when save failed",
            MiniAppState.ACTIVE,
            controlledStore.loadSnapshot(noteId)?.state
        )

        // Once persistence recovers, retrying real fold succeeds and preserves the unsaved note cleanly
        failSaves = false
        val recoveredFoldLatch = CountDownLatch(1)
        val recoveredFoldOk = AtomicReference<Boolean?>()
        localBridge.sleepMiniApp(noteId) { ok ->
            recoveredFoldOk.set(ok)
            recoveredFoldLatch.countDown()
        }
        assertTrue("Recovered sleepMiniApp transaction timed out", recoveredFoldLatch.await(3, TimeUnit.SECONDS))
        assertEquals(true, recoveredFoldOk.get())
        assertNull("Window should unregister once fold succeeds", wm.getWindow(noteId))
        assertNotNull("Launcher bubble should appear once fold succeeds", wm.getWindow("bubble_$noteId"))
        val savedAfterRecovery = controlledStore.loadSnapshot(noteId)
        assertNotNull(savedAfterRecovery)
        assertEquals(MiniAppState.SLEEPING, savedAfterRecovery!!.state)
        assertEquals("Critical unsaved note content", savedAfterRecovery.payload[TestNotesMiniAppInstance.KEY_TEXT])
        assertEquals("18.0", savedAfterRecovery.payload["font_size"])
        assertEquals("bold", savedAfterRecovery.payload["font_style"])
        assertEquals("false", savedAfterRecovery.payload["word_wrap"])
        assertEquals("1.4", savedAfterRecovery.payload["line_spacing"])
        assertEquals("#A6E3A1", savedAfterRecovery.payload[NotePersistenceCoordinator.KEY_ACCENT_COLOR])
    }
}
