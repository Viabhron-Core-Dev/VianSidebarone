package com.example.feature.floating.trigger

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.MotionEvent
import android.view.WindowManager
import com.example.core.FakeSharedPreferences
import com.example.core.FloatingWindowManager
import com.example.feature.element.ElementIdParser
import com.example.feature.floating.DefaultMiniAppFactory
import com.example.feature.floating.DurableMiniAppStore
import com.example.feature.floating.FloatingMiniAppBridge
import com.example.feature.floating.HeavyFloatingHost
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
        HeavyFloatingHost.resetForTesting()
        val wm = FloatingWindowManager.getInstance(context)
        wm.getAllWindows().map { it.windowId }.forEach { wm.unregisterWindow(it) }
    }

    @After
    fun tearDown() {
        FloatingTriggerManager.resetForTesting()
        FloatingTriggerStore.resetForTesting()
        FloatingMiniAppBridge.resetForTesting()
        HeavyFloatingHost.resetForTesting()
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

    @Test
    fun testAllDispatcherExecutableTypesAreEligible() {
        val validTargets = listOf(
            "app:com.example.notes",
            "link:u1:{\"url\":\"https://example.com\",\"label\":\"Docs\"}",
            "pwa:offline_wiki",
            "quicktile:torch",
            "volume:media_vol_up",
            "media:play_pause",
            "display:torch_toggle",
            "system:dictionary_floating",
            "settings_shortcut:wifi",
            "intent:My%20Shortcut:intent%3A%23Intent%3Bend",
            "page_window:calculator"
        )

        for (target in validTargets) {
            val validation = FloatingTriggerCompat.validateTarget(context, target)
            assertTrue(
                "Expected '$target' to be ValidStandalone, got $validation",
                validation is FloatingTriggerTargetValidation.ValidStandalone
            )
            assertTrue("Expected isStandaloneElementTarget=true for $target", FloatingTriggerCompat.isStandaloneElementTarget(context, target))
            assertFalse("Expected isLegacyContainerTarget=false for $target", FloatingTriggerCompat.isLegacyContainerTarget(context, target))
            assertNull(FloatingTriggerCompat.unsupportedReason(context, target))
        }
    }

    @Test
    fun testNonExecutableAndMalformedTargetsAreRejectedWithClearReason() {
        val unsupportedTargets = listOf(
            "",
            "   ",
            "folder:f1:{\"name\":\"Reading\",\"items\":[\"quicktile:torch\"]}",
            "widget:42",
            "popup_widget:99",
            "spacer:sp1:{\"heightDp\":40}",
            "floating_trigger:quicktile:torch",
            "page_window:widget",
            "quicktile:nonexistent_tile",
            "system:does_not_exist",
            "app:",
            "intent:",
            "pwa:",
            "link:",
            "link:bare_no_url:{}"
        )

        val manager = FloatingTriggerManager.getInstance(context)
        manager.canDrawOverlaysCheck = { true }
        var executedCount = 0
        manager.actionExecutor = { _, _ ->
            executedCount++
            true
        }
        val notifiedMessages = mutableListOf<String>()
        manager.userMessageNotifier = { _, msg -> notifiedMessages.add(msg) }

        for (target in unsupportedTargets) {
            val validation = FloatingTriggerCompat.validateTarget(context, target)
            assertTrue(
                "Expected '$target' to be Unsupported (never ValidStandalone or LegacyContainer), got $validation",
                validation is FloatingTriggerTargetValidation.Unsupported
            )
            assertFalse("isStandaloneElementTarget must be false for $target", FloatingTriggerCompat.isStandaloneElementTarget(context, target))
            assertFalse("isLegacyContainerTarget must be false for $target", FloatingTriggerCompat.isLegacyContainerTarget(context, target))
            assertNotNull("unsupportedReason must explain rejection for $target", FloatingTriggerCompat.unsupportedReason(context, target))

            assertNull("ensureActiveTrigger must reject '$target'", manager.ensureActiveTrigger(target, "Bad Target"))
            assertFalse("toggleTriggerForElement must reject '$target'", manager.toggleTriggerForElement(target, "Bad Target"))
            assertFalse("executeAssociatedElement must refuse '$target'", manager.executeAssociatedElement(target))
            assertFalse(
                "handleSidebarTriggerItem must refuse unsupported '$target'",
                manager.handleSidebarTriggerItem(SidebarItem.FloatingTrigger(target, "Trigger: Bad"))
            )
        }

        assertEquals("Unsupported targets must never execute via actionExecutor", 0, executedCount)
        assertTrue("User-facing rejection feedback must be emitted", notifiedMessages.isNotEmpty())
        assertEquals("Unsupported targets must never create trigger records", 0, FloatingTriggerStore.getInstance(context).getAllTriggers().size)
        assertEquals("Unsupported targets must never attach floating windows", 0, manager.getActiveWindowCount())
    }

    @Test
    fun testDiscoverExistingSidebarGridElementsFiltersUnsupportedTargets() {
        val sidebarPrefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        val hybridArray = org.json.JSONArray().apply {
            put(org.json.JSONObject().put("elementId", "quicktile:torch"))
            put(org.json.JSONObject().put("elementId", "system:dictionary_floating"))
            put(org.json.JSONObject().put("elementId", "app:com.example.reader"))
            put(org.json.JSONObject().put("elementId", "link:lnk1:{\"url\":\"https://example.org\",\"label\":\"Example\"}"))
            put(org.json.JSONObject().put("elementId", "pwa:demo_pwa"))
            put(org.json.JSONObject().put("elementId", "folder:fld1:{\"name\":\"Apps\",\"items\":[]}"))
            put(org.json.JSONObject().put("elementId", "widget:12"))
            put(org.json.JSONObject().put("elementId", "popup_widget:15"))
            put(org.json.JSONObject().put("elementId", "spacer:sp1:{\"heightDp\":50}"))
            put(org.json.JSONObject().put("elementId", "floating_trigger:quicktile:wifi"))
            put(org.json.JSONObject().put("elementId", "quicktile:nonexistent"))
        }
        sidebarPrefs.edit()
            .putString("hybrid_grid_page1", hybridArray.toString())
            .commit()

        val discovered = FloatingTriggerStore.getInstance(context).discoverExistingSidebarGridElements()
        val discoveredIds = discovered.map { it.id }

        assertTrue(discoveredIds.contains("quicktile:torch"))
        assertTrue(discoveredIds.contains("system:dictionary_floating"))
        assertTrue(discoveredIds.contains("app:com.example.reader"))
        assertTrue(discoveredIds.any { it.startsWith("link:lnk1") })
        assertTrue(discoveredIds.contains("pwa:demo_pwa"))

        assertFalse("Folder must be filtered out", discovered.any { it is SidebarItem.Folder })
        assertFalse("Widget must be filtered out", discovered.any { it is SidebarItem.Widget })
        assertFalse("PopupWidget must be filtered out", discovered.any { it is SidebarItem.PopupWidget })
        assertFalse("Spacer must be filtered out", discovered.any { it is SidebarItem.Spacer })
        assertFalse("FloatingTrigger must be filtered out", discovered.any { it is SidebarItem.FloatingTrigger })
        assertEquals(5, discovered.size)
    }

    @Test
    fun testRestoreActiveTriggersSkipsUnsupportedPersistedRecordsWithoutCrashing() {
        val store = FloatingTriggerStore.getInstance(context)
        store.saveTrigger(FloatingTriggerRecord("ft_valid", "quicktile:torch", "Torch", 120, 220, active = true))
        store.saveTrigger(FloatingTriggerRecord("ft_folder", "folder:f1:{\"name\":\"Box\",\"items\":[]}", "Box", 120, 220, active = true))
        store.saveTrigger(FloatingTriggerRecord("ft_widget", "widget:7", "Clock Widget", 120, 220, active = true))
        store.saveTrigger(FloatingTriggerRecord("ft_popup_widget", "popup_widget:9", "Popup Clock", 120, 220, active = true))
        store.saveTrigger(FloatingTriggerRecord("ft_spacer", "spacer:s1:{\"heightDp\":30}", "Spacer", 120, 220, active = true))
        store.saveTrigger(FloatingTriggerRecord("ft_recursive", "floating_trigger:quicktile:wifi", "Nested", 120, 220, active = true))

        FloatingTriggerManager.resetForTesting()
        FloatingTriggerStore.resetForTesting()

        val manager = FloatingTriggerManager.getInstance(context)
        manager.canDrawOverlaysCheck = { true }
        val restored = manager.restoreActiveTriggers()

        assertEquals("Only the single valid persisted trigger must restore", 1, restored)
        assertNotNull(manager.getActiveWindow("ft_valid"))
        assertNull(manager.getActiveWindow("ft_folder"))
        assertNull(manager.getActiveWindow("ft_widget"))
        assertNull(manager.getActiveWindow("ft_popup_widget"))
        assertNull(manager.getActiveWindow("ft_spacer"))
        assertNull(manager.getActiveWindow("ft_recursive"))

        val reloadedStore = FloatingTriggerStore.getInstance(context)
        assertTrue(reloadedStore.getTrigger("ft_valid")!!.active)
        assertFalse(reloadedStore.getTrigger("ft_folder")!!.active)
        assertFalse(reloadedStore.getTrigger("ft_widget")!!.active)
        assertFalse(reloadedStore.getTrigger("ft_popup_widget")!!.active)
        assertFalse(reloadedStore.getTrigger("ft_spacer")!!.active)
        assertFalse(reloadedStore.getTrigger("ft_recursive")!!.active)
    }

    @Test
    fun testAddElementActivityFullPickerFallbackAppliesSameEligibilityRules() {
        val candidates = listOf(
            File("src/main/java/com/example/feature/settings/AddElementActivity.kt"),
            File("app/src/main/java/com/example/feature/settings/AddElementActivity.kt"),
            File("../app/src/main/java/com/example/feature/settings/AddElementActivity.kt")
        )
        val source = candidates.first { it.isFile }.readText()

        assertTrue("Full picker must hide Widget, Popup Widget, and Folder during trigger selection", source.contains("if (!isFloatingTrigger)"))
        assertTrue("launchFullFloatingTriggerPicker must force IS_HYBRID_GRID=false", source.contains("putExtra(\"IS_HYBRID_GRID\", false)"))
        assertTrue("Full picker must validate via FloatingTriggerCompat.validateTarget", source.contains("FloatingTriggerCompat"))
        assertTrue("Full picker must show user-facing explanation on unsupported selection", source.contains("showUnsupportedFloatingTriggerExplanation"))
    }

    @Test
    fun testEverySupportedRegisteredPageWindowTypeIsAccepted() {
        val heavyHost = HeavyFloatingHost.getInstance(context)
        val allSupportedTypes = LinkedHashSet<String>().apply {
            addAll(FloatingTriggerCompat.MAIN_ONLY_MINI_APP_TYPES)
            addAll(FloatingTriggerCompat.EMBEDDED_HEAVY_PAGE_WINDOW_TYPES)
            addAll(heavyHost.getRegisteredAppTypes())
        }

        assertTrue("Supported PageWindow set must not be empty", allSupportedTypes.isNotEmpty())
        assertFalse("widget must never be in supported PageWindow types", allSupportedTypes.contains("widget"))
        assertFalse("single_widget must never be in supported PageWindow types", allSupportedTypes.contains("single_widget"))

        for (type in allSupportedTypes) {
            val bareId = "page_window:$type"
            val bareValidation = FloatingTriggerCompat.validateTarget(context, bareId)
            assertTrue(
                "Expected supported PageWindow '$bareId' to be ValidStandalone, got $bareValidation",
                bareValidation is FloatingTriggerTargetValidation.ValidStandalone
            )

            val instancedId = "page_window:$type:inst_42"
            val instancedValidation = FloatingTriggerCompat.validateTarget(context, instancedId)
            assertTrue(
                "Expected instanced PageWindow '$instancedId' to be ValidStandalone, got $instancedValidation",
                instancedValidation is FloatingTriggerTargetValidation.ValidStandalone
            )
        }
    }

    @Test
    fun testDynamicallyRegisteredMiniAppPageWindowIsAcceptedWhileRegisteredAndRejectedWhenUnregistered() {
        val dynamicType = "dynamic_custom_plugin"
        val bareId = "page_window:$dynamicType"
        val instancedId = "page_window:$dynamicType:session_7"

        // Before registration: must be rejected
        assertTrue(
            "Unregistered dynamic type must be rejected",
            FloatingTriggerCompat.validateTarget(context, bareId) is FloatingTriggerTargetValidation.Unsupported
        )
        assertTrue(
            "Unregistered instanced dynamic type must be rejected",
            FloatingTriggerCompat.validateTarget(context, instancedId) is FloatingTriggerTargetValidation.Unsupported
        )

        // Register dynamic factory on HeavyFloatingHost
        val heavyHost = HeavyFloatingHost.getInstance(context)
        heavyHost.registerFactory(dynamicType, DefaultMiniAppFactory())

        assertTrue(
            "Dynamically registered PageWindow type must be accepted",
            FloatingTriggerCompat.validateTarget(context, bareId) is FloatingTriggerTargetValidation.ValidStandalone
        )
        assertTrue(
            "Dynamically registered PageWindow instance must be accepted",
            FloatingTriggerCompat.validateTarget(context, instancedId) is FloatingTriggerTargetValidation.ValidStandalone
        )

        // Unregister dynamic factory -> rejected again
        heavyHost.unregisterFactory(dynamicType)
        assertTrue(
            "Unregistered dynamic PageWindow type must be rejected again",
            FloatingTriggerCompat.validateTarget(context, bareId) is FloatingTriggerTargetValidation.Unsupported
        )
    }

    @Test
    fun testUnknownAndMalformedPageWindowIdsAreRejectedAndNeverExecuted() {
        val invalidPageWindowIds = listOf(
            "page_window:not_a_real_app",
            "page_window:not_a_real_app:inst_1",
            "page_window:widget",
            "page_window:widget:12",
            "page_window:single_widget",
            "page_window:",
            "page_window:   ",
            "page_window::inst_1",
            "page_window:calculator:",
            "page_window:note:   "
        )

        val manager = FloatingTriggerManager.getInstance(context)
        manager.canDrawOverlaysCheck = { true }
        var executedCount = 0
        manager.actionExecutor = { _, _ ->
            executedCount++
            true
        }
        val messages = mutableListOf<String>()
        manager.userMessageNotifier = { _, msg -> messages.add(msg) }

        for (badId in invalidPageWindowIds) {
            val validation = FloatingTriggerCompat.validateTarget(context, badId)
            assertTrue(
                "Expected '$badId' to be Unsupported, got $validation",
                validation is FloatingTriggerTargetValidation.Unsupported
            )
            assertFalse("isStandaloneElementTarget must be false for '$badId'", FloatingTriggerCompat.isStandaloneElementTarget(context, badId))
            assertFalse("isLegacyContainerTarget must be false for '$badId'", FloatingTriggerCompat.isLegacyContainerTarget(context, badId))
            assertNull("ensureActiveTrigger must reject '$badId'", manager.ensureActiveTrigger(badId, "Invalid Window"))
            assertFalse("executeAssociatedElement must refuse '$badId'", manager.executeAssociatedElement(badId))
        }

        assertEquals("Unsupported PageWindow targets must never execute", 0, executedCount)
        assertEquals("Unsupported PageWindow targets must not create trigger records", 0, FloatingTriggerStore.getInstance(context).getAllTriggers().size)
        assertTrue("User-facing feedback must be emitted for rejected PageWindow targets", messages.isNotEmpty())
    }

    @Test
    fun testRestoreActiveTriggersDeactivatesUnsupportedPersistedPageWindowAndKeepsValidPageWindow() {
        val store = FloatingTriggerStore.getInstance(context)
        store.saveTrigger(FloatingTriggerRecord("ft_pw_valid", "page_window:calculator", "Calculator", 100, 200, active = true))
        store.saveTrigger(FloatingTriggerRecord("ft_pw_note", "page_window:test_notes:note_1", "Note", 120, 240, active = true))
        store.saveTrigger(FloatingTriggerRecord("ft_pw_unknown", "page_window:not_a_real_app", "Bogus", 140, 260, active = true))
        store.saveTrigger(FloatingTriggerRecord("ft_pw_widget", "page_window:widget", "Widget", 160, 280, active = true))
        store.saveTrigger(FloatingTriggerRecord("ft_pw_malformed", "page_window:calculator:", "Malformed", 180, 300, active = true))

        FloatingTriggerManager.resetForTesting()
        FloatingTriggerStore.resetForTesting()

        val manager = FloatingTriggerManager.getInstance(context)
        manager.canDrawOverlaysCheck = { true }
        val restored = manager.restoreActiveTriggers()

        assertEquals("Exactly the two valid PageWindow triggers must restore", 2, restored)
        assertNotNull(manager.getActiveWindow("ft_pw_valid"))
        assertNotNull(manager.getActiveWindow("ft_pw_note"))
        assertNull(manager.getActiveWindow("ft_pw_unknown"))
        assertNull(manager.getActiveWindow("ft_pw_widget"))
        assertNull(manager.getActiveWindow("ft_pw_malformed"))

        val reloadedStore = FloatingTriggerStore.getInstance(context)
        assertTrue(reloadedStore.getTrigger("ft_pw_valid")!!.active)
        assertTrue(reloadedStore.getTrigger("ft_pw_note")!!.active)
        assertFalse(reloadedStore.getTrigger("ft_pw_unknown")!!.active)
        assertFalse(reloadedStore.getTrigger("ft_pw_widget")!!.active)
        assertFalse(reloadedStore.getTrigger("ft_pw_malformed")!!.active)
    }
}

