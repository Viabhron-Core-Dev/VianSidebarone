package com.example.feature.element

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.example.feature.sidebar.ALL_DISPLAY_ACTIONS
import com.example.feature.sidebar.ALL_MEDIA_ACTIONS
import com.example.feature.sidebar.ALL_QUICK_TILES
import com.example.feature.sidebar.ALL_SCREEN_CAPTURE_ACTIONS
import com.example.feature.sidebar.ALL_SETTINGS_SHORTCUTS
import com.example.feature.sidebar.ALL_SYSTEM_ACTIONS
import com.example.feature.sidebar.ALL_UTILITIES_ACTIONS
import com.example.feature.sidebar.ALL_VOLUME_ACTIONS
import com.example.feature.sidebar.SidebarEditNavigator
import com.example.feature.sidebar.SidebarItem
import com.example.utils.SidebarPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ElementSystemTest {

    private class TestContext : ContextWrapper(null) {
        var startedIntent: Intent? = null
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example"
        override fun startActivity(intent: Intent?) {
            startedIntent = intent
        }
    }

    private lateinit var mockContext: TestContext

    @Before
    fun setUp() {
        mockContext = TestContext()
    }

    @Test
    fun testSidebarItemHierarchyAndIds() {
        val appItem = SidebarItem.App(packageName = "com.test.app", label = "Test App")
        assertEquals("app:com.test.app", appItem.id)
        assertEquals("Test App", appItem.label)

        val linkItem = SidebarItem.Link(uuid = "link-1", url = "https://example.com", label = "Example Link")
        assertEquals("link:link-1", linkItem.id)
        assertEquals("Example Link", linkItem.label)

        val folderItem = SidebarItem.Folder(
            uuid = "folder-1",
            name = "Work Tools",
            colorHex = "#FF0000",
            items = listOf("app:com.test.app", "link:link-1")
        )
        assertEquals("folder:folder-1", folderItem.id)
        assertEquals("Work Tools", folderItem.label)
        assertEquals(2, folderItem.items.size)

        val widgetItem = SidebarItem.Widget(widgetId = 42, label = "Clock Widget")
        assertEquals("widget:42", widgetItem.id)

        val popupWidgetItem = SidebarItem.PopupWidget(widgetId = 43, label = "Calendar Popup")
        assertEquals("popup_widget:43", popupWidgetItem.id)

        val quickTile = SidebarItem.QuickTile("torch", "Torch", 1)
        assertEquals("quicktile:torch", quickTile.id)

        val sysAction = SidebarItem.SystemAction("screenshot", "Screenshot", 2)
        assertEquals("system:screenshot", sysAction.id)

        val volAction = SidebarItem.VolumeAction("media", "vol_up", "Media Vol+", 3)
        assertEquals("volume:media_vol_up", volAction.id)

        val dispAction = SidebarItem.DisplayAction("torch_toggle", "Flashlight", 4)
        assertEquals("display:torch_toggle", dispAction.id)

        val trigger = SidebarItem.FloatingTrigger("notes", "Notes Trigger")
        assertEquals("floating_trigger:notes", trigger.id)

        val pageWindow = SidebarItem.PageWindow("reader", "Reader Window", 5)
        assertEquals("page_window:reader", pageWindow.id)

        val spacer = SidebarItem.Spacer("sp-1", 16)
        assertEquals("spacer:sp-1", spacer.id)
    }

    @Test
    fun testActionListsCompleteness() {
        assertTrue(ALL_QUICK_TILES.any { it.action == "torch" })
        assertTrue(ALL_QUICK_TILES.any { it.action == "wifi" })
        assertTrue(ALL_SYSTEM_ACTIONS.any { it.action == "back" })
        assertTrue(ALL_SYSTEM_ACTIONS.any { it.action == "home" })
        assertTrue(ALL_SCREEN_CAPTURE_ACTIONS.any { it.action == "screenshot" })
        assertTrue(ALL_SCREEN_CAPTURE_ACTIONS.any { it.action == "audio_record" })
        assertTrue(ALL_VOLUME_ACTIONS.any { it.stream == "media" && it.action == "vol_up" })
        assertTrue(ALL_MEDIA_ACTIONS.any { it.action == "play_pause" })
        assertTrue(ALL_SETTINGS_SHORTCUTS.any { it.action == "wifi" })
        assertTrue(ALL_DISPLAY_ACTIONS.any { it.action == "torch_toggle" })
        assertTrue(ALL_UTILITIES_ACTIONS.any { it.id == "system:force_stop_running_apps" })
    }

    @Test
    fun testElementActionRegistryModularResolution() {
        val registry = ElementActionRegistry.getInstance(mockContext)

        assertTrue(registry.isRegistered("app:com.android.chrome"))
        assertTrue(registry.isRegistered("system:screenshot"))
        assertTrue(registry.isRegistered("volume:media_vol_up"))
        assertTrue(registry.isRegistered("display:torch_toggle"))
        assertTrue(registry.isRegistered("quicktile:torch"))
        assertTrue(registry.isRegistered("link:uuid-123"))
        assertTrue(registry.isRegistered("folder:uuid-456"))
        assertTrue(registry.isRegistered("widget:789"))

        val appDescriptor = registry.getDescriptor("app:com.android.chrome")
        assertNotNull(appDescriptor)
        assertEquals("app:com.android.chrome", appDescriptor?.actionKey)

        val systemDescriptor = registry.getDescriptor("system:screenshot")
        assertNotNull(systemDescriptor)
        assertEquals("system:screenshot", systemDescriptor?.actionKey)

        val resolved = registry.resolve("display:torch_toggle")
        assertNotNull(resolved)
    }

    @Test
    fun testSidebarEditNavigatorRoutesCorrectly() {
        var closed = false

        // 1. Apps page config
        val appsConfig = SidebarPage(id = "page-1", type = "apps", title = "Apps")
        val appsIntent = SidebarEditNavigator.createEditIntent(mockContext, appsConfig)
        assertNotNull(appsIntent)

        // 2. Hybrid grid page config
        val hybridConfig = SidebarPage(id = "page-2", type = "hybrid_grid", title = "Hybrid")
        val hybridIntent = SidebarEditNavigator.createEditIntent(mockContext, hybridConfig)
        assertNotNull(hybridIntent)

        // 3. App tracker page config
        val trackerConfig = SidebarPage(id = "page-3", type = "app_tracker", title = "Tracker")
        val trackerIntent = SidebarEditNavigator.createEditIntent(mockContext, trackerConfig)
        assertNotNull(trackerIntent)

        // 4. Notifications page config
        val notifConfig = SidebarPage(id = "page-4", type = "notifications", title = "Notifications")
        val notifIntent = SidebarEditNavigator.createEditIntent(mockContext, notifConfig)
        assertNotNull(notifIntent)

        // 5. Test navigateToEditScreen triggers onClose and launches intent
        SidebarEditNavigator.navigateToEditScreen(mockContext, appsConfig, "sidebar", "handle_1") {
            closed = true
        }
        assertTrue(closed)
        assertNotNull(mockContext.startedIntent)
    }
}
