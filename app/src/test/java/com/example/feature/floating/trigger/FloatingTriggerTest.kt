package com.example.feature.floating.trigger

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.MotionEvent
import android.view.WindowManager
import com.example.core.FakeSharedPreferences
import com.example.core.FloatingWindowManager
import com.example.feature.element.ElementIdParser
import com.example.feature.floating.DurableMiniAppStore
import com.example.feature.floating.FloatingMiniAppBridge
import com.example.feature.sidebar.SidebarItem
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

class FloatingTriggerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class MockTestContext(
        private val prefsByName: MutableMap<String, FakeSharedPreferences>,
        private val baseFilesDir: File
    ) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example"
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            val key = name ?: "default"
            return prefsByName.getOrPut(key) { FakeSharedPreferences() }
        }
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
                override fun getIdentifier(name: String?, defType: String?, defPackage: String?): Int = 0
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

    private lateinit var prefsMap: MutableMap<String, FakeSharedPreferences>
    private lateinit var context: MockTestContext

    @Before
    fun setUp() {
        prefsMap = LinkedHashMap()
        context = MockTestContext(prefsMap, tempFolder.newFolder("trigger_test"))
        FloatingTriggerManager.resetForTesting()
        FloatingTriggerStore.resetForTesting()
        DurableMiniAppStore.resetForTesting()
        FloatingMiniAppBridge.resetForTesting()
        val wm = FloatingWindowManager.getInstance(context)
        wm.getAllWindows().map { it.windowId }.forEach { wm.unregisterWindow(it) }
    }

    @After
    fun tearDown() {
        FloatingTriggerManager.resetForTesting()
        FloatingTriggerStore.resetForTesting()
        FloatingMiniAppBridge.resetForTesting()
        val wm = FloatingWindowManager.getInstance(context)
        wm.getAllWindows().map { it.windowId }.forEach { wm.unregisterWindow(it) }
    }

    @Test
    fun testAssociatedActionPersistsAndDispatchesViaCentralDispatcher() {
        val manager = FloatingTriggerManager.getInstance(context)
        manager.canDrawOverlaysCheck = { true }

        val dispatched = mutableListOf<SidebarItem>()
        manager.actionExecutor = { _, item ->
            dispatched.add(item)
            true
        }

        val record = manager.ensureActiveTrigger("quicktile:torch", "Flashlight")
        assertNotNull("Trigger record must be created", record)
        assertEquals("quicktile:torch", record!!.targetElementId)
        assertTrue(record.active)

        val window = manager.getActiveWindow(record.triggerId)
        assertNotNull("Standalone floating trigger window must be attached", window)

        val center = FloatingTriggerButtonWindow.computeButtonSizePx(context) / 2f
        window!!.handleTouch(MotionEvent.ACTION_DOWN, center, center, 200f, 300f)
        window.handleTouch(MotionEvent.ACTION_UP, center, center, 200f, 300f)

        assertEquals("Short tap must execute associated action exactly once", 1, dispatched.size)
        assertTrue("Dispatched item must match associated QuickTile", dispatched[0] is SidebarItem.QuickTile)
        assertEquals("quicktile:torch", dispatched[0].id)
    }

    @Test
    fun testLongPressAndDragRepositionsPersistsAndDoesNotFireTapAction() {
        var fakeTimeMs = 1000L
        var tapCount = 0
        var committedPos: Pair<Int, Int>? = null

        val button = FloatingTriggerButtonWindow(
            triggerId = "ft_drag_test",
            targetElementId = "quicktile:wifi",
            label = "Wi-Fi",
            context = context,
            initialX = 100,
            initialY = 300,
            onTriggerTapped = { _, _ -> tapCount++ },
            onPositionCommitted = { _, x, y -> committedPos = Pair(x, y) },
            longPressTimeoutMs = 350L,
            clockMs = { fakeTimeMs }
        )

        val wm = FloatingWindowManager.getInstance(context)
        wm.registerWindow(button)
        wm.showWindow(button.windowId)

        val center = FloatingTriggerButtonWindow.computeButtonSizePx(context) / 2f
        button.handleTouch(MotionEvent.ACTION_DOWN, center, center, 100f, 300f)
        fakeTimeMs = 1450L
        button.handleTouch(MotionEvent.ACTION_MOVE, center, center, 340f, 480f)
        assertTrue("Drag mode must activate after long-press timeout", button.isDragModeActiveForTesting)

        button.handleTouch(MotionEvent.ACTION_UP, center, center, 340f, 480f)

        assertEquals("Drag release MUST NOT execute tap action", 0, tapCount)
        assertNotNull("Position must be committed after drag", committedPos)
        assertEquals(340, committedPos!!.first)
        assertEquals(480, committedPos!!.second)
        assertEquals(340, button.bounds.x)
        assertEquals(480, button.bounds.y)

        wm.unregisterWindow(button.windowId)
    }

    @Test
    fun testFastSwipeBeforeLongPressCancelsTapWithoutExecutingAction() {
        var fakeTimeMs = 1000L
        var tapCount = 0

        val button = FloatingTriggerButtonWindow(
            triggerId = "ft_cancel_test",
            targetElementId = "quicktile:wifi",
            label = "Wi-Fi",
            context = context,
            initialX = 100,
            initialY = 300,
            onTriggerTapped = { _, _ -> tapCount++ },
            onPositionCommitted = { _, _, _ -> },
            longPressTimeoutMs = 400L,
            clockMs = { fakeTimeMs }
        )

        val center = FloatingTriggerButtonWindow.computeButtonSizePx(context) / 2f
        button.handleTouch(MotionEvent.ACTION_DOWN, center, center, 100f, 300f)
        fakeTimeMs = 1080L
        button.handleTouch(MotionEvent.ACTION_MOVE, center, center, 250f, 450f)
        button.handleTouch(MotionEvent.ACTION_UP, center, center, 250f, 450f)

        assertEquals("Swipe gesture must not fire tap action", 0, tapCount)
        assertFalse("Drag mode must not activate on fast swipe", button.isDragModeActiveForTesting)
    }

    @Test
    fun testPositionPersistsAcrossRestartAndClampsOutOfBoundsCoordinates() {
        val store = FloatingTriggerStore.getInstance(context)
        val triggerId = FloatingTriggerCompat.triggerIdForElement("quicktile:bluetooth")
        store.saveTrigger(
            FloatingTriggerRecord(
                triggerId = triggerId,
                targetElementId = "quicktile:bluetooth",
                label = "Bluetooth",
                x = 5000,
                y = -900,
                active = true
            )
        )

        // Simulate restart by resetting singletons while keeping SharedPreferences intact
        FloatingTriggerManager.resetForTesting()
        FloatingTriggerStore.resetForTesting()

        val restoredManager = FloatingTriggerManager.getInstance(context)
        restoredManager.canDrawOverlaysCheck = { true }
        val restoredCount = restoredManager.restoreActiveTriggers()
        assertEquals(1, restoredCount)

        val window = restoredManager.getActiveWindow(triggerId)
        assertNotNull("Persisted trigger must be restored after restart", window)
        val size = FloatingTriggerButtonWindow.computeButtonSizePx(context)
        assertTrue("Restored X must be clamped inside screen width", window!!.bounds.x in 0..(1080 - size))
        assertTrue("Restored Y must be clamped inside usable screen height", window.bounds.y in 0..(1920 - size))

        val persisted = FloatingTriggerStore.getInstance(context).getTrigger(triggerId)
        assertNotNull(persisted)
        assertEquals(window.bounds.x, persisted!!.x)
        assertEquals(window.bounds.y, persisted.y)
    }

    @Test
    fun testBackwardCompatibilityPreservesLegacyContainerTargets() {
        assertTrue(FloatingTriggerCompat.isLegacyContainerTarget(context, "left_0_swipe_right"))
        assertTrue(FloatingTriggerCompat.isLegacyContainerTarget(context, "notes"))
        assertFalse(FloatingTriggerCompat.isStandaloneElementTarget(context, "left_0_swipe_right"))
        assertFalse(FloatingTriggerCompat.isStandaloneElementTarget(context, "notes"))

        assertFalse(FloatingTriggerCompat.isLegacyContainerTarget(context, "quicktile:torch"))
        assertTrue(FloatingTriggerCompat.isStandaloneElementTarget(context, "quicktile:torch"))
        assertTrue(FloatingTriggerCompat.isStandaloneElementTarget(context, "system:dictionary_floating"))
        assertFalse(FloatingTriggerCompat.isStandaloneElementTarget(context, "floating_trigger:quicktile:torch"))

        val legacyItem = ElementIdParser.parse(context, "floating_trigger:left_0_swipe_right")
        assertTrue(legacyItem is SidebarItem.FloatingTrigger)
        assertEquals("left_0_swipe_right", (legacyItem as SidebarItem.FloatingTrigger).targetId)

        val standaloneItem = ElementIdParser.parse(context, "floating_trigger:quicktile:torch")
        assertTrue(standaloneItem is SidebarItem.FloatingTrigger)
        assertEquals("quicktile:torch", (standaloneItem as SidebarItem.FloatingTrigger).targetId)
    }

    @Test
    fun testFloatingTriggerDoesNotInterfereWithMiniAppWindows() {
        val bridge = FloatingMiniAppBridge.getInstance(context)
        val dictWindow = bridge.openMiniApp("miniapp_dictionary_coexist", "dictionary")

        val triggerManager = FloatingTriggerManager.getInstance(context)
        triggerManager.canDrawOverlaysCheck = { true }
        val rec = triggerManager.ensureActiveTrigger("quicktile:torch", "Flashlight")
        assertNotNull(rec)

        val wm = FloatingWindowManager.getInstance(context)
        assertNotNull(wm.getWindow(dictWindow.windowId))
        assertNotNull(wm.getWindow("${FloatingTriggerButtonWindow.WINDOW_ID_PREFIX}${rec!!.triggerId}"))

        triggerManager.deactivateTrigger(rec.triggerId)
        assertNull(wm.getWindow("${FloatingTriggerButtonWindow.WINDOW_ID_PREFIX}${rec.triggerId}"))
        assertNotNull(wm.getWindow(dictWindow.windowId))

        bridge.closeMiniApp(dictWindow.instanceId, deleteDurable = true)
    }
}
