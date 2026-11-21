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
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.Executors

/**
 * Focused regression suite for Phase 14.27 Note refinement:
 * - Complete user-facing rename from "Test Notes" to "Note" with zero-migration key preservation
 * - Note persistence lifecycle (flush before sleep/close, debounce cancellation, stale write guard,
 *   permanent-delete resurrection prevention, undo/redo preservation across fold/reopen)
 * - Reader untouched boundary guarantee for system light/dark theming
 */
class NoteRefinementAndLifecycleTest {

    private class MockTestContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example"
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs

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
        context = MockTestContext(prefs)
        DurableMiniAppStore.resetForTesting()
        FloatingMiniAppBridge.resetForTesting()
        val wm = FloatingWindowManager.getInstance(context)
        wm.getAllWindows().map { it.windowId }.forEach { wm.unregisterWindow(it) }
        store = DurableMiniAppStore.getInstance(context)
        bridge = FloatingMiniAppBridge.getInstance(context)
    }

    @After
    fun tearDown() {
        NotePersistenceCoordinator.TEST_DISPATCH_IMMEDIATELY = false
        FloatingMiniAppBridge.resetForTesting()
        val wm = FloatingWindowManager.getInstance(context)
        wm.getAllWindows().map { it.windowId }.forEach { wm.unregisterWindow(it) }
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
}
