package com.example.core

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import com.example.util.HandleEdge
import com.example.util.HandleShape
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * HandleGestureContainerTest: Verifies multi-handle coexistence, multi-gesture isolation,
 * independent container identity resolution, persistence across process recreation,
 * and safe deletion.
 *
 * Strict Hierarchy Verified:
 * Handle -> Gesture -> Independent Container -> Page -> Element
 */
class HandleGestureContainerTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var fakeContext: FakeContext

    @Before
    fun setup() {
        fakePrefs = FakeSharedPreferences()
        fakeContext = FakeContext(fakePrefs)
    }

    // 1. Verify multiple handles can coexist
    @Test
    fun testMultipleHandlesCanCoexist() {
        val manager = HandleManager(fakeContext)

        val handle1 = manager.getAllHandles().first()
        assertEquals("handle_1", handle1.id)

        val handle2 = manager.createHandle(name = "Left Utility Handle", edge = HandleEdge.LEFT)
        assertEquals("handle_2", handle2.id)
        assertEquals("Left Utility Handle", handle2.name)
        assertEquals(HandleEdge.LEFT, handle2.edge)

        val handle3 = manager.createHandle(name = "Right Assist Handle", edge = HandleEdge.RIGHT)
        assertEquals("handle_3", handle3.id)

        val allHandles = manager.getAllHandles()
        assertEquals(3, allHandles.size)
        assertEquals(listOf("handle_1", "handle_2", "handle_3"), allHandles.map { it.id })

        // Verify each can be fetched individually
        val fetched2 = manager.getHandle("handle_2")
        assertNotNull(fetched2)
        assertEquals("Left Utility Handle", fetched2?.name)
        assertEquals(HandleEdge.LEFT, fetched2?.edge)
    }

    // 2. Verify multiple gestures can coexist under one handle
    @Test
    fun testMultipleGesturesCanCoexistUnderOneHandle() {
        val manager = HandleManager(fakeContext)

        manager.configureGesture("handle_1", HandleGestures.SWIPE_LEFT, HandleManager.ACTION_OPEN_SIDEBAR)
        manager.configureGesture("handle_1", HandleGestures.TAP, HandleManager.ACTION_MOVE_HANDLE)
        manager.configureGesture("handle_1", HandleGestures.DOUBLE_TAP, "torch")
        manager.configureGesture("handle_1", HandleGestures.LONG_PRESS, HandleManager.ACTION_OPEN_SIDEBAR)
        manager.configureGesture("handle_1", HandleGestures.SWIPE_UP, "screenshot")

        val handle = manager.getHandle("handle_1")
        assertNotNull(handle)
        assertEquals(HandleManager.ACTION_OPEN_SIDEBAR, handle?.getActionForGesture(HandleGestures.SWIPE_LEFT))
        assertEquals(HandleManager.ACTION_MOVE_HANDLE, handle?.getActionForGesture(HandleGestures.TAP))
        assertEquals("torch", handle?.getActionForGesture(HandleGestures.DOUBLE_TAP))
        assertEquals(HandleManager.ACTION_OPEN_SIDEBAR, handle?.getActionForGesture(HandleGestures.LONG_PRESS))
        assertEquals("screenshot", handle?.getActionForGesture(HandleGestures.SWIPE_UP))
        assertEquals(HandleManager.ACTION_NONE, handle?.getActionForGesture(HandleGestures.SWIPE_DOWN))

        val gesturesInfo = manager.getGesturesForHandle("handle_1")
        assertEquals(7, gesturesInfo.size)

        val swipeLeftInfo = gesturesInfo.first { it.gesture == HandleGestures.SWIPE_LEFT }
        assertTrue(swipeLeftInfo.isEnabled)
        assertTrue(swipeLeftInfo.isContainerTarget)
        assertEquals("handle_1_swipe_left", swipeLeftInfo.containerId)

        val tapInfo = gesturesInfo.first { it.gesture == HandleGestures.TAP }
        assertTrue(tapInfo.isEnabled)
        assertFalse(tapInfo.isContainerTarget)

        val doubleTapInfo = gesturesInfo.first { it.gesture == HandleGestures.DOUBLE_TAP }
        assertEquals("torch", doubleTapInfo.action)
        assertFalse(doubleTapInfo.isContainerTarget)
    }

    // 3. Verify same gesture name under different handles produces different container identities
    @Test
    fun testSameGestureUnderDifferentHandlesProducesDifferentContainerIdentities() {
        val manager = HandleManager(fakeContext)

        val handle1 = manager.getHandle("handle_1")!!
        val handle2 = manager.createHandle("Handle 2", edge = HandleEdge.LEFT)

        manager.configureGesture("handle_1", HandleGestures.SWIPE_LEFT, HandleManager.ACTION_OPEN_SIDEBAR)
        manager.configureGesture("handle_2", HandleGestures.SWIPE_LEFT, HandleManager.ACTION_OPEN_SIDEBAR)

        val container1 = manager.resolveContainer("handle_1", HandleGestures.SWIPE_LEFT)
        val container2 = manager.resolveContainer("handle_2", HandleGestures.SWIPE_LEFT)

        assertNotNull(container1)
        assertNotNull(container2)
        assertEquals("handle_1_swipe_left", container1?.containerId)
        assertEquals("handle_2_swipe_left", container2?.containerId)
        assertNotEquals(container1?.containerId, container2?.containerId)
        assertNotEquals(container1?.pagesKey, container2?.pagesKey)

        // Strict isolation of persisted page stack
        manager.savePagesForContainer(container1!!.containerId, listOf("page_h1_1", "page_h1_2"))
        manager.savePagesForContainer(container2!!.containerId, listOf("page_h2_alpha"))

        val pages1 = manager.getPagesForContainer(container1.containerId)
        val pages2 = manager.getPagesForContainer(container2.containerId)

        assertEquals(listOf("page_h1_1", "page_h1_2"), pages1)
        assertEquals(listOf("page_h2_alpha"), pages2)
    }

    // 4. Verify different gestures under same handle produce different container identities
    @Test
    fun testDifferentGesturesUnderSameHandleProduceDifferentContainerIdentities() {
        val manager = HandleManager(fakeContext)

        manager.configureGesture("handle_1", HandleGestures.SWIPE_LEFT, HandleManager.ACTION_OPEN_SIDEBAR)
        manager.configureGesture("handle_1", HandleGestures.LONG_PRESS, HandleManager.ACTION_OPEN_SIDEBAR)
        manager.configureGesture("handle_1", HandleGestures.TAP, HandleManager.ACTION_OPEN_SIDEBAR)

        val containerSwipe = manager.resolveContainer("handle_1", HandleGestures.SWIPE_LEFT)
        val containerLongPress = manager.resolveContainer("handle_1", HandleGestures.LONG_PRESS)
        val containerTap = manager.resolveContainer("handle_1", HandleGestures.TAP)

        assertNotNull(containerSwipe)
        assertNotNull(containerLongPress)
        assertNotNull(containerTap)

        assertEquals("handle_1_swipe_left", containerSwipe?.containerId)
        assertEquals("handle_1_long_press", containerLongPress?.containerId)
        assertEquals("handle_1_tap", containerTap?.containerId)

        // Verify resolution by container ID
        val resolvedSwipe = manager.getContainer("handle_1_swipe_left")
        val resolvedLongPress = manager.getContainer("handle_1_long_press")
        val resolvedTap = manager.getContainer("handle_1_tap")

        assertEquals(containerSwipe, resolvedSwipe)
        assertEquals(containerLongPress, resolvedLongPress)
        assertEquals(containerTap, resolvedTap)

        // Verify page stack isolation between gestures of the same handle
        manager.savePagesForContainer(containerSwipe!!.containerId, listOf("swipe_page"))
        manager.savePagesForContainer(containerLongPress!!.containerId, listOf("long_press_page"))
        manager.savePagesForContainer(containerTap!!.containerId, listOf("tap_page"))

        assertEquals(listOf("swipe_page"), manager.getPagesForContainer(containerSwipe.containerId))
        assertEquals(listOf("long_press_page"), manager.getPagesForContainer(containerLongPress.containerId))
        assertEquals(listOf("tap_page"), manager.getPagesForContainer(containerTap.containerId))
    }

    // 5. Verify persistence survives simulated process recreation
    @Test
    fun testPersistenceSurvivesProcessRecreation() {
        // Process 1: Setup and configure
        val managerProcess1 = HandleManager(fakeContext)
        val newHandle = managerProcess1.createHandle(name = "Custom Floating Bar", edge = HandleEdge.LEFT)
        managerProcess1.saveHandle(
            newHandle.copy(
                widthDp = 20,
                heightDp = 180,
                color = Color.parseColor("#ff3b30"),
                alphaPercent = 90,
                onTapAction = HandleManager.ACTION_OPEN_SIDEBAR,
                onDoubleTapAction = "torch"
            )
        )
        val containerId = HandleManager.getContainerId(newHandle.id, HandleGestures.TAP)
        managerProcess1.savePagesForContainer(containerId, listOf("page_a", "page_b", "page_c"))
        managerProcess1.saveSelectedPageForContainer(containerId, "page_b")

        // Process 2: Discard managerProcess1 and simulate fresh process launch reading same prefs
        val managerProcess2 = HandleManager(fakeContext)
        val restoredHandles = managerProcess2.getAllHandles()
        assertEquals(2, restoredHandles.size)

        val restoredHandle = managerProcess2.getHandle(newHandle.id)
        assertNotNull(restoredHandle)
        assertEquals("Custom Floating Bar", restoredHandle?.name)
        assertEquals(HandleEdge.LEFT, restoredHandle?.edge)
        assertEquals(20, restoredHandle?.widthDp)
        assertEquals(180, restoredHandle?.heightDp)
        assertEquals(Color.parseColor("#ff3b30"), restoredHandle?.color)
        assertEquals(90, restoredHandle?.alphaPercent)
        assertEquals(HandleManager.ACTION_OPEN_SIDEBAR, restoredHandle?.onTapAction)
        assertEquals("torch", restoredHandle?.onDoubleTapAction)

        val restoredPages = managerProcess2.getPagesForContainer(containerId)
        assertEquals(listOf("page_a", "page_b", "page_c"), restoredPages)

        val restoredSelectedPage = managerProcess2.getSelectedPageForContainer(containerId)
        assertEquals("page_b", restoredSelectedPage)
    }

    // 6. Verify deleting one handle does not affect another handle or gesture
    @Test
    fun testDeletingOneHandleDoesNotAffectAnotherHandleOrGesture() {
        val manager = HandleManager(fakeContext)

        val handle2 = manager.createHandle(name = "Handle To Keep", edge = HandleEdge.RIGHT)
        val handle3 = manager.createHandle(name = "Handle To Delete", edge = HandleEdge.LEFT)

        val container2Id = HandleManager.getContainerId(handle2.id, HandleGestures.SWIPE_LEFT)
        val container3Id = HandleManager.getContainerId(handle3.id, HandleGestures.SWIPE_RIGHT)

        manager.savePagesForContainer(container2Id, listOf("keep_page_1", "keep_page_2"))
        manager.savePagesForContainer(container3Id, listOf("delete_page_1"))

        // Add dummy element to container 3 to test element cleanup
        fakePrefs.edit().putString("handle_${container3Id}_page_delete_page_1_elements", "elem1,elem2").apply()

        // Delete handle 3
        val deleted = manager.deleteHandle(handle3.id)
        assertTrue(deleted)

        // Verify handle 3 is gone
        assertNull(manager.getHandle(handle3.id))
        assertFalse(manager.getHandleIds().contains(handle3.id))
        assertFalse(fakePrefs.contains(HandleManager.getContainerPagesKey(container3Id)))
        assertFalse(fakePrefs.contains("handle_${container3Id}_page_delete_page_1_elements"))

        // Verify handle 1 and handle 2 remain completely intact
        assertNotNull(manager.getHandle("handle_1"))
        assertNotNull(manager.getHandle(handle2.id))
        assertEquals(listOf("keep_page_1", "keep_page_2"), manager.getPagesForContainer(container2Id))
    }

    // 7. Verify deleting a gesture does not affect other gestures
    @Test
    fun testDeletingGestureDoesNotAffectOtherGestures() {
        val manager = HandleManager(fakeContext)

        manager.configureGesture("handle_1", HandleGestures.SWIPE_LEFT, HandleManager.ACTION_OPEN_SIDEBAR)
        manager.configureGesture("handle_1", HandleGestures.LONG_PRESS, HandleManager.ACTION_OPEN_SIDEBAR)

        val swipeContainer = HandleManager.getContainerId("handle_1", HandleGestures.SWIPE_LEFT)
        val longPressContainer = HandleManager.getContainerId("handle_1", HandleGestures.LONG_PRESS)

        manager.savePagesForContainer(swipeContainer, listOf("swipe_stack"))
        manager.savePagesForContainer(longPressContainer, listOf("long_press_stack"))

        // Remove swipe_left with cleanContainerData = true
        manager.removeGesture("handle_1", HandleGestures.SWIPE_LEFT, cleanContainerData = true)

        val handle = manager.getHandle("handle_1")
        assertEquals(HandleManager.ACTION_NONE, handle?.getActionForGesture(HandleGestures.SWIPE_LEFT))
        assertEquals(HandleManager.ACTION_OPEN_SIDEBAR, handle?.getActionForGesture(HandleGestures.LONG_PRESS))

        // Swipe container data was cleaned
        assertFalse(fakePrefs.contains(HandleManager.getContainerPagesKey(swipeContainer)))

        // Long press container data remains untouched
        assertEquals(listOf("long_press_stack"), manager.getPagesForContainer(longPressContainer))
    }

    // 8. Verify conservative legacy migration isolation
    @Test
    fun testConservativeLegacyMigrationIsolation() {
        val manager = HandleManager(fakeContext)

        // Seed a legacy key from pre-isolated version
        fakePrefs.edit().putString("handle_1_pages", "legacy_p1,legacy_p2").apply()

        // SWIPE_LEFT is the default primary gesture -> should safely adopt legacy data and purge legacy key
        val swipePages = manager.getPagesForContainer("handle_1_swipe_left")
        assertEquals(listOf("legacy_p1", "legacy_p2"), swipePages)
        assertFalse("Legacy key must be deleted after migration", fakePrefs.contains("handle_1_pages"))

        // Another gesture container (e.g. swipe_right) should NOT see legacy data, must get default
        val rightPages = manager.getPagesForContainer("handle_1_swipe_right")
        assertEquals(listOf(HandleManager.DEFAULT_PAGE_HYBRID), rightPages)
    }

    // 9. First-handle invariant: handle_1 cannot be deleted
    @Test
    fun testHandle1CannotBeDeleted() {
        val manager = HandleManager(fakeContext)
        val initialHandles = manager.getAllHandles()
        assertTrue(initialHandles.any { it.id == "handle_1" })

        val deleted = manager.deleteHandle("handle_1")
        assertFalse("handle_1 must not be deletable", deleted)

        assertNotNull("handle_1 must still exist", manager.getHandle("handle_1"))
        assertTrue("handle_ids must retain handle_1", manager.getHandleIds().contains("handle_1"))
    }

    // 10. First-handle invariant: handle_1 cannot remove its only sidebar gesture
    @Test
    fun testHandle1CannotRemoveOnlySidebarGesture() {
        val manager = HandleManager(fakeContext)
        manager.configureGesture("handle_1", HandleGestures.SWIPE_LEFT, HandleManager.ACTION_OPEN_SIDEBAR)

        // Attempt to remove the only sidebar gesture on handle_1
        manager.removeGesture("handle_1", HandleGestures.SWIPE_LEFT, cleanContainerData = false)

        val handle = manager.getHandle("handle_1")
        assertEquals(
            "handle_1 must keep at least one gesture opening sidebar",
            HandleManager.ACTION_OPEN_SIDEBAR,
            handle?.getActionForGesture(HandleGestures.SWIPE_LEFT)
        )
    }

    // 11. First-handle invariant: gesture migration preserves existing state
    @Test
    fun testHandle1GestureMigrationPreservesExistingState() {
        val manager = HandleManager(fakeContext)
        val oldContainer = "handle_1_swipe_left"
        val newContainer = "handle_1_swipe_right"

        // Set up initial container state under swipe_left
        manager.configureGesture("handle_1", HandleGestures.SWIPE_LEFT, HandleManager.ACTION_OPEN_SIDEBAR)
        manager.savePagesForContainer(oldContainer, listOf("home_page", "calc_page", "apps_page"))
        manager.saveSelectedPageForContainer(oldContainer, "calc_page")
        fakePrefs.edit().putString("handle_${oldContainer}_page_calc_page_elements", "elem_1,elem_2").apply()
        fakePrefs.edit().putInt("handle_${oldContainer}_sidebar_width", 280).apply()
        fakePrefs.edit().putString("sidebar_apps_${oldContainer}_apps_page", "[app1,app2]").apply()

        // Change handle_1's sidebar gesture from SWIPE_LEFT to SWIPE_RIGHT
        manager.configureGesture("handle_1", HandleGestures.SWIPE_RIGHT, HandleManager.ACTION_OPEN_SIDEBAR)

        // Verify old gesture is retired
        val handle = manager.getHandle("handle_1")
        assertEquals(HandleManager.ACTION_NONE, handle?.getActionForGesture(HandleGestures.SWIPE_LEFT))
        assertEquals(HandleManager.ACTION_OPEN_SIDEBAR, handle?.getActionForGesture(HandleGestures.SWIPE_RIGHT))

        // Verify complete state is migrated to newContainer
        val migratedPages = manager.getPagesForContainer(newContainer)
        assertEquals(listOf("home_page", "calc_page", "apps_page"), migratedPages)
        assertEquals("calc_page", manager.getSelectedPageForContainer(newContainer))
        assertEquals("elem_1,elem_2", fakePrefs.getString("handle_${newContainer}_page_calc_page_elements", null))
        assertEquals(280, fakePrefs.getInt("handle_${newContainer}_sidebar_width", -1))
        assertEquals("[app1,app2]", fakePrefs.getString("sidebar_apps_${newContainer}_apps_page", null))

        // Verify old container was cleaned
        assertFalse(fakePrefs.contains(HandleManager.getContainerPagesKey(oldContainer)))
        assertFalse(fakePrefs.contains("handle_${oldContainer}_sidebar_width"))
    }

    // 12. Verify container-isolated persistence between handle 1 and handle 2
    @Test
    fun testContainerIsolatedPersistence() {
        val manager = HandleManager(fakeContext)
        val pageManager = PageManager(fakeContext)

        val handle2 = manager.createHandle(name = "Handle 2", edge = HandleEdge.LEFT)
        manager.configureGesture(handle2.id, HandleGestures.SWIPE_LEFT, HandleManager.ACTION_OPEN_SIDEBAR)

        val container1 = "handle_1_swipe_left"
        val container2 = "handle_2_swipe_left"

        // Handle 1 primary container uses default_hybrid
        val pages1 = pageManager.getPageStack(container1)
        assertEquals("default_hybrid", pages1.first().pageId)

        // Handle 2 container uses default_hybrid_handle_2_swipe_left to avoid collision
        val pages2 = pageManager.getPageStack(container2)
        assertEquals("default_hybrid_$container2", pages2.first().pageId)

        // Verify saving apps in container 1 does not bleed to container 2
        fakePrefs.edit().putString("sidebar_apps_${container1}_apps", "[app_a,app_b]").apply()
        assertNull(fakePrefs.getString("sidebar_apps_${container2}_apps", null))

        // Verify saving whitelist in container 2 does not overwrite global or container 1
        com.example.utils.AppTrackerHelper.saveForceStopWhitelist(fakeContext, container2, setOf("com.pkg.c"))
        val whitelist2 = com.example.utils.AppTrackerHelper.getForceStopWhitelist(fakeContext, container2)
        assertEquals(setOf("com.pkg.c"), whitelist2)
        assertFalse(fakePrefs.getStringSet("force_stop_whitelist_current", emptySet())?.contains("com.pkg.c") == true)
    }

    // 10. Verify Direct Element gesture configuration path
    @Test
    fun testDirectElementGestureConfiguration() {
        val manager = HandleManager(fakeContext)

        // Configure a gesture directly with an element ID (e.g. app or system action)
        manager.configureGesture("handle_1", HandleGestures.SWIPE_RIGHT, "app:com.android.settings")

        val handle = manager.getHandle("handle_1")
        assertNotNull(handle)
        assertEquals("app:com.android.settings", handle?.getActionForGesture(HandleGestures.SWIPE_RIGHT))

        val gestureInfo = manager.getGesturesForHandle("handle_1").first { it.gesture == HandleGestures.SWIPE_RIGHT }
        assertEquals("app:com.android.settings", gestureInfo.action)
        assertFalse(gestureInfo.isContainerTarget)

        // Confirm gesture target resolves to Action, not Container
        val target = HandleManager.resolveGestureTarget("handle_1", HandleGestures.SWIPE_RIGHT, "app:com.android.settings")
        assertTrue(target is GestureTarget.Action)
        assertEquals("app:com.android.settings", (target as GestureTarget.Action).actionKey)
    }

    // 11. Verify Per-Container Sidebar Page Deck CRUD & Opening Face Persistence
    @Test
    fun testPerContainerPageDeckAndOpeningFace() {
        val handleManager = HandleManager(fakeContext)
        val pageManager = PageManager(fakeContext)

        // Set up two distinct gestures configured as ACTION_OPEN_SIDEBAR
        handleManager.configureGesture("handle_1", HandleGestures.SWIPE_LEFT, HandleManager.ACTION_OPEN_SIDEBAR)
        handleManager.configureGesture("handle_1", HandleGestures.SWIPE_DOWN, HandleManager.ACTION_OPEN_SIDEBAR)

        val container1 = HandleManager.getContainerId("handle_1", HandleGestures.SWIPE_LEFT)
        val container2 = HandleManager.getContainerId("handle_1", HandleGestures.SWIPE_DOWN)

        // Container 1 starts with default_hybrid (Home Grid)
        val stack1 = pageManager.getPageStack(container1)
        assertEquals(1, stack1.size)
        assertEquals("default_hybrid", stack1[0].pageId)

        // Add Apps and Calculator to Container 1
        pageManager.addPage(container1, "apps_page_1")
        pageManager.addPage(container1, "calculator_page_1")
        val updatedStack1 = pageManager.getPageStack(container1)
        assertEquals(3, updatedStack1.size)
        assertEquals("default_hybrid", updatedStack1[0].pageId)
        assertEquals("apps_page_1", updatedStack1[1].pageId)
        assertEquals("calculator_page_1", updatedStack1[2].pageId)

        // Set opening face of Container 1 to Apps
        pageManager.saveSelectedPageId(container1, "apps_page_1")
        assertEquals("apps_page_1", pageManager.getSelectedPageId(container1))

        // Reorder Container 1: move Calculator to front
        pageManager.reorderPages(container1, 2, 0)
        val reordered1 = pageManager.getPageStack(container1)
        assertEquals("calculator_page_1", reordered1[0].pageId)
        assertEquals("default_hybrid", reordered1[1].pageId)
        assertEquals("apps_page_1", reordered1[2].pageId)

        // Confirm Container 2 remains completely isolated with its own default page
        val stack2 = pageManager.getPageStack(container2)
        assertEquals(1, stack2.size)
        assertEquals("default_hybrid_$container2", stack2[0].pageId)
        assertNull(pageManager.getSelectedPageId(container2))

        // Add Media Player to Container 2
        pageManager.addPage(container2, "media_player_2")
        val updatedStack2 = pageManager.getPageStack(container2)
        assertEquals(2, updatedStack2.size)
        assertEquals("media_player_2", updatedStack2[1].pageId)

        // Delete page from Container 1
        pageManager.removePage(container1, "default_hybrid")
        val postDelete1 = pageManager.getPageStack(container1)
        assertEquals(2, postDelete1.size)
        assertEquals(listOf("calculator_page_1", "apps_page_1"), postDelete1.map { it.pageId })
        // Selected face remains apps_page_1
        assertEquals("apps_page_1", pageManager.getSelectedPageId(container1))
    }

    // 16. Verify Two-Choice gesture flow (Sidebar Page vs Direct Element) and Container Page Management
    @Test
    fun testGestureTwoChoiceFlowAndPageManagement() {
        val handleManager = HandleManager(fakeContext)
        val pageManager = PageManager(fakeContext)

        // Case A: Gesture configured as Sidebar Page
        handleManager.configureGesture("handle_1", HandleGestures.SWIPE_RIGHT, HandleManager.ACTION_OPEN_SIDEBAR)
        val containerId = HandleManager.getContainerId("handle_1", HandleGestures.SWIPE_RIGHT)
        val targetSidebar = HandleManager.resolveGestureTarget("handle_1", HandleGestures.SWIPE_RIGHT, HandleManager.ACTION_OPEN_SIDEBAR)
        assertTrue(targetSidebar is GestureTarget.Container)
        assertEquals(containerId, (targetSidebar as GestureTarget.Container).containerId)

        // Case B: Gesture configured as Direct Element
        handleManager.configureGesture("handle_1", HandleGestures.DOUBLE_TAP, "app:com.android.chrome")
        val targetDirect = HandleManager.resolveGestureTarget("handle_1", HandleGestures.DOUBLE_TAP, "app:com.android.chrome")
        assertTrue(targetDirect is GestureTarget.Action)
        assertEquals("app:com.android.chrome", (targetDirect as GestureTarget.Action).actionKey)

        // In Container for SWIPE_RIGHT: manage pages
        val initialStack = pageManager.getPageStack(containerId)
        assertEquals(1, initialStack.size)

        // Add a Single Widget page with custom title
        val widgetPageId = "widget_news_feed"
        pageManager.addPage(containerId, widgetPageId, position = -1, pageType = "widget", title = "Single Widget")
        val stackAfterAdd = pageManager.getPageStack(containerId)
        assertEquals(2, stackAfterAdd.size)
        val addedPage = stackAfterAdd.first { it.pageId == widgetPageId }
        assertEquals("widget", addedPage.type)
        assertEquals("Single Widget", addedPage.title)

        // Rename the page
        pageManager.renamePage(containerId, widgetPageId, "Morning News Feed")
        val stackAfterRename = pageManager.getPageStack(containerId)
        val renamedPage = stackAfterRename.first { it.pageId == widgetPageId }
        assertEquals("Morning News Feed", renamedPage.title)

        // Choose as opening face
        pageManager.saveSelectedPageId(containerId, widgetPageId)
        assertEquals(widgetPageId, pageManager.getSelectedPageId(containerId))

        // Reorder: move to top
        pageManager.reorderPages(containerId, 1, 0)
        val reorderedStack = pageManager.getPageStack(containerId)
        assertEquals(widgetPageId, reorderedStack[0].pageId)

        // Remove the default hybrid page
        pageManager.removePage(containerId, initialStack[0].pageId)
        val finalStack = pageManager.getPageStack(containerId)
        assertEquals(1, finalStack.size)
        assertEquals(widgetPageId, finalStack[0].pageId)
        assertEquals(widgetPageId, pageManager.getSelectedPageId(containerId))
    }
}

/**
 * In-memory FakeSharedPreferences for fast JVM unit testing.
 */
class FakeSharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any>()

    override fun getAll(): Map<String, *> = HashMap(data)

    override fun getString(key: String, defValue: String?): String? =
        data[key] as? String ?: defValue

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        (data[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: defValues

    override fun getInt(key: String, defValue: Int): Int =
        (data[key] as? Number)?.toInt() ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        (data[key] as? Number)?.toLong() ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        (data[key] as? Number)?.toFloat() ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        data[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor(this)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    class Editor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
        private val temp = mutableMapOf<String, Any?>()
        private val toRemove = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            if (value == null) toRemove.add(key) else temp[key] = value
            return this
        }

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
            if (values == null) toRemove.add(key) else temp[key] = values
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            temp[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            temp[key] = value
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            temp[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            temp[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            toRemove.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearAll) prefs.data.clear()
            for (key in toRemove) prefs.data.remove(key)
            prefs.data.putAll(temp.filterValues { it != null } as Map<String, Any>)
        }
    }
}

/**
 * Lightweight Context double providing in-memory SharedPreferences and package name.
 */
class FakeContext(private val prefs: FakeSharedPreferences) : android.content.ContextWrapper(null) {
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    override fun getApplicationContext(): Context = this
    override fun getPackageName(): String = "com.example"
}
