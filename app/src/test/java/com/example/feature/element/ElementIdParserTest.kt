package com.example.feature.element

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.example.core.FakeSharedPreferences
import com.example.feature.sidebar.SidebarItem
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ElementIdParserTest {

    private class MockTestContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example"
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var context: MockTestContext

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        context = MockTestContext(fakePrefs)
    }

    @Test
    fun testParseQuickTile() {
        val item = ElementIdParser.parse(context, "quicktile:torch")
        assertNotNull(item)
        assertTrue(item is SidebarItem.QuickTile)
        val qTile = item as SidebarItem.QuickTile
        assertEquals("torch", qTile.action)
        assertEquals("Torch", qTile.label)
    }

    @Test
    fun testParseSystemAction() {
        val item = ElementIdParser.parse(context, "system:screenshot")
        assertNotNull(item)
        assertTrue(item is SidebarItem.SystemAction)
        val sysAction = item as SidebarItem.SystemAction
        assertEquals("screenshot", sysAction.action)
        assertEquals("Screenshot", sysAction.label)
    }

    @Test
    fun testParseDisplayAction() {
        val item = ElementIdParser.parse(context, "display:privacy_curtain")
        assertNotNull(item)
        assertTrue(item is SidebarItem.DisplayAction)
        val dispAction = item as SidebarItem.DisplayAction
        assertEquals("privacy_curtain", dispAction.action)
    }

    @Test
    fun testParseVolumeAction() {
        val item = ElementIdParser.parse(context, "volume:media_vol_up")
        assertNotNull(item)
        assertTrue(item is SidebarItem.VolumeAction)
        val volAction = item as SidebarItem.VolumeAction
        assertEquals("media", volAction.stream)
        assertEquals("vol_up", volAction.action)
    }

    @Test
    fun testParseMediaAction() {
        val item = ElementIdParser.parse(context, "media:play_pause")
        assertNotNull(item)
        assertTrue(item is SidebarItem.MediaAction)
        val mediaAction = item as SidebarItem.MediaAction
        assertEquals("play_pause", mediaAction.action)
    }

    @Test
    fun testParseWidget() {
        val item = ElementIdParser.parse(context, "widget:101:{\"label\":\"My Clock\"}")
        assertNotNull(item)
        assertTrue(item is SidebarItem.Widget)
        val widget = item as SidebarItem.Widget
        assertEquals(101, widget.widgetId)
        assertEquals("My Clock", widget.label)
    }

    @Test
    fun testParseSpacer() {
        val item = ElementIdParser.parse(context, "spacer:uuid-1:{\"heightDp\":32}")
        assertNotNull(item)
        assertTrue(item is SidebarItem.Spacer)
        val spacer = item as SidebarItem.Spacer
        assertEquals(32, spacer.heightDp)
        assertEquals("spacer:uuid-1", spacer.id)
    }

    @Test
    fun testParseFolder() {
        val json = "{\"name\":\"Office Tools\",\"colorHex\":\"#2196F3\",\"items\":[\"system:screenshot\",\"quicktile:torch\"],\"folderStyle\":1}"
        val item = ElementIdParser.parse(context, "folder:folder-xyz:$json")
        assertNotNull(item)
        assertTrue(item is SidebarItem.Folder)
        val folder = item as SidebarItem.Folder
        assertEquals("folder-xyz", folder.uuid)
        assertEquals("Office Tools", folder.name)
        assertEquals("#2196F3", folder.colorHex)
        assertEquals(listOf("system:screenshot", "quicktile:torch"), folder.items)
    }

    @Test
    fun testParseLink() {
        val json = "{\"url\":\"https://github.com\",\"label\":\"GitHub\",\"browserPackage\":\"com.android.chrome\"}"
        val item = ElementIdParser.parse(context, "link:link-456:$json")
        assertNotNull(item)
        assertTrue(item is SidebarItem.Link)
        val link = item as SidebarItem.Link
        assertEquals("link-456", link.uuid)
        assertEquals("https://github.com", link.url)
        assertEquals("GitHub", link.label)
        assertEquals("com.android.chrome", link.browserPackage)
    }

    @Test
    fun testParseFloatingTrigger() {
        val item = ElementIdParser.parse(context, "floating_trigger:quicktile:torch")
        assertNotNull(item)
        assertTrue(item is SidebarItem.FloatingTrigger)
        val trigger = item as SidebarItem.FloatingTrigger
        assertEquals("quicktile:torch", trigger.targetId)
        assertTrue(trigger.label.contains("Torch"))
    }

    @Test
    fun testParsePageWindow() {
        val item = ElementIdParser.parse(context, "page_window:calculator")
        assertNotNull(item)
        assertTrue(item is SidebarItem.PageWindow)
        val window = item as SidebarItem.PageWindow
        assertEquals("calculator", window.pageType)
        assertEquals("Window: Calculator", window.label)
    }

    @Test
    fun testParseInvalidOrEmptyReturnsNull() {
        assertNull(ElementIdParser.parse(context, ""))
        assertNull(ElementIdParser.parse(context, "unknown_prefix:something"))
    }
}
