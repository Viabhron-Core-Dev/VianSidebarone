package com.example.core

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * UnifiedPageSystemTest: Verifies that the existing Page system operates with:
 * 1. Single authoritative SidebarPage model in com.example.core.
 * 2. Dual-format compatibility: reads legacy JSON arrays of objects and comma-separated IDs.
 * 3. Persistence of ordered page decks under "handle_${containerId}_pages".
 * 4. Selected page persistence strictly isolated under "handle_${containerId}_selected_page".
 * 5. Container isolation across different handles and gestures.
 * 6. Backward-compatibility of utils.PageManager adapter.
 */
class UnifiedPageSystemTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var fakeContext: FakeContext

    @Before
    fun setup() {
        PageManager.resetForTesting()
        HandleManager.resetForTesting()
        fakePrefs = FakeSharedPreferences()
        fakeContext = FakeContext(fakePrefs)
    }

    @Test
    fun testSidebarPageJsonSerialization() {
        val page = SidebarPage(
            pageId = "test_custom_page",
            pageType = PageTypes.CALCULATOR,
            title = "My Calc",
            order = 2,
            gridColumns = 4,
            wrapContentHeight = false,
            transparency = 0.85f
        )

        val json = page.toJson()
        assertEquals("test_custom_page", json.getString("id"))
        assertEquals("test_custom_page", json.getString("pageId"))
        assertEquals(PageTypes.CALCULATOR, json.getString("type"))
        assertEquals(PageTypes.CALCULATOR, json.getString("pageType"))
        assertEquals("My Calc", json.getString("title"))
        assertEquals(2, json.getInt("order"))
        assertEquals(4, json.getInt("gridColumns"))
        assertFalse(json.getBoolean("wrapContentHeight"))

        val restored = SidebarPage.fromJson(json, 2)
        assertEquals(page.pageId, restored.pageId)
        assertEquals(page.pageType, restored.pageType)
        assertEquals(page.title, restored.title)
        assertEquals(page.order, restored.order)
        assertEquals(page.gridColumns, restored.gridColumns)
        assertEquals(page.wrapContentHeight, restored.wrapContentHeight)
        assertEquals(page.transparency, restored.transparency, 0.01f)
    }

    @Test
    fun testDualFormatMigrationFormatA_JsonArray() {
        val containerId = "handle_1_swipe_left"
        val jsonArrayStr = """
            [
              {"id": "default_hybrid", "type": "hybrid_grid", "title": "Home Grid", "order": 0},
              {"id": "apps_page_1", "type": "apps", "title": "My Work Apps", "order": 1},
              {"id": "calc_page", "type": "calculator", "title": "Calculator", "order": 2}
            ]
        """.trimIndent()
        fakePrefs.edit().putString(HandleManager.getContainerPagesKey(containerId), jsonArrayStr).apply()

        val pageManager = PageManager.getInstance(fakeContext)
        val stack = pageManager.getPageStack(containerId)

        assertEquals(3, stack.size)
        assertEquals("default_hybrid", stack[0].pageId)
        assertEquals(PageTypes.HYBRID_GRID, stack[0].pageType)
        assertEquals("Home Grid", stack[0].title)

        assertEquals("apps_page_1", stack[1].pageId)
        assertEquals(PageTypes.APPS, stack[1].pageType)
        assertEquals("My Work Apps", stack[1].title)

        assertEquals("calc_page", stack[2].pageId)
        assertEquals(PageTypes.CALCULATOR, stack[2].pageType)
        assertEquals("Calculator", stack[2].title)
    }

    @Test
    fun testDualFormatMigrationFormatB_CommaSeparated() {
        val containerId = "handle_2_tap"
        val commaSeparated = "default_hybrid_handle_2_tap, apps, widgets_grid, compass"
        fakePrefs.edit().putString(HandleManager.getContainerPagesKey(containerId), commaSeparated).apply()

        val pageManager = PageManager.getInstance(fakeContext)
        val stack = pageManager.getPageStack(containerId)

        assertEquals(4, stack.size)
        assertEquals("default_hybrid_handle_2_tap", stack[0].pageId)
        assertEquals(PageTypes.HYBRID_GRID, stack[0].pageType)
        assertEquals("Home Grid", stack[0].title)

        assertEquals("apps", stack[1].pageId)
        assertEquals(PageTypes.APPS, stack[1].pageType)

        assertEquals("widgets_grid", stack[2].pageId)
        assertEquals(PageTypes.WIDGETS_GRID, stack[2].pageType)

        assertEquals("compass", stack[3].pageId)
        assertEquals(PageTypes.COMPASS, stack[3].pageType)
    }

    @Test
    fun testSelectedPagePersistenceAndContainerIsolation() {
        val pageManager = PageManager.getInstance(fakeContext)

        val containerA = "handle_1_swipe_left"
        val containerB = "handle_1_tap"

        pageManager.saveSelectedPageId(containerA, "page_a_selected")
        pageManager.saveSelectedPageId(containerB, "page_b_selected")

        assertEquals("page_a_selected", pageManager.getSelectedPageId(containerA))
        assertEquals("page_b_selected", pageManager.getSelectedPageId(containerB))

        // Verify SharedPreferences keys are distinct and properly formatted
        assertEquals("page_a_selected", fakePrefs.getString("handle_${containerA}_selected_page", null))
        assertEquals("page_b_selected", fakePrefs.getString("handle_${containerB}_selected_page", null))
    }

    @Test
    fun testPageStackCrudOperations() {
        val pageManager = PageManager.getInstance(fakeContext)
        val containerId = "handle_1_swipe_left"

        // Initial default stack
        val initial = pageManager.getPageStack(containerId)
        assertEquals(1, initial.size)
        assertEquals("default_hybrid", initial[0].pageId)

        // Add pages
        pageManager.addPage(containerId, "calc_page")
        pageManager.addPage(containerId, "compass_page")
        var stack = pageManager.getPageStack(containerId)
        assertEquals(3, stack.size)
        assertEquals(listOf("default_hybrid", "calc_page", "compass_page"), stack.map { it.pageId })

        // Reorder pages
        pageManager.reorderPages(containerId, fromIndex = 2, toIndex = 0)
        stack = pageManager.getPageStack(containerId)
        assertEquals(listOf("compass_page", "default_hybrid", "calc_page"), stack.map { it.pageId })

        // Remove page
        pageManager.removePage(containerId, "default_hybrid")
        stack = pageManager.getPageStack(containerId)
        assertEquals(listOf("compass_page", "calc_page"), stack.map { it.pageId })
    }

    @Test
    fun testUtilsPageManagerBackwardCompatibilityBridge() {
        val containerId = "handle_4_swipe_left"
        val pages = listOf(
            SidebarPage(pageId = "p1", pageType = "apps", title = "Apps", order = 0),
            SidebarPage(pageId = "p2", pageType = "calculator", title = "Calculator", order = 1)
        )

        // Save via utils.PageManager
        com.example.utils.PageManager.savePages(fakePrefs, containerId, pages, fakeContext)

        // Read via core.PageManager
        val coreStack = PageManager.getInstance(fakeContext).getPageStack(containerId)
        assertEquals(2, coreStack.size)
        assertEquals("p1", coreStack[0].pageId)
        assertEquals("p2", coreStack[1].pageId)

        // Read via utils.PageManager
        val utilsStack = com.example.utils.PageManager.getPages(fakePrefs, containerId)
        assertEquals(2, utilsStack.size)
        assertEquals("p1", utilsStack[0].pageId)
        assertEquals("p2", utilsStack[1].pageId)
    }

    @Test
    fun testFallbackKeyAutoMigrationWritesToIsolatedKey() {
        val cleanHandleId = "handle_5"
        val containerId = "handle_5_swipe_left"
        val fallbackJson = """[{"id":"p_calc","type":"calculator","title":"Calc","order":0}]"""
        fakePrefs.edit().putString("handle_${cleanHandleId}_pages", fallbackJson).apply()

        assertNull(fakePrefs.getString(HandleManager.getContainerPagesKey(containerId), null))

        val pageManager = PageManager.getInstance(fakeContext)
        val stack = pageManager.getPageStack(containerId)

        assertEquals(1, stack.size)
        assertEquals("p_calc", stack[0].pageId)

        // Verify it was auto-migrated and written to the isolated container key in JSON array format
        val migrated = fakePrefs.getString(HandleManager.getContainerPagesKey(containerId), null)
        assertNotNull(migrated)
        assertTrue(migrated!!.startsWith("["))
        assertTrue(migrated.contains("p_calc"))
    }

    @Test
    fun testLegacySelectedIndexAutoMigrationWritesToSelectedPageKey() {
        val containerId = "handle_6_swipe_left"
        val jsonArrayStr = """
            [
              {"id": "p0", "type": "hybrid_grid", "title": "Home Grid", "order": 0},
              {"id": "p1", "type": "apps", "title": "Apps", "order": 1},
              {"id": "p2", "type": "calculator", "title": "Calc", "order": 2}
            ]
        """.trimIndent()
        fakePrefs.edit().putString(HandleManager.getContainerPagesKey(containerId), jsonArrayStr).apply()
        fakePrefs.edit().putInt("handle_${containerId}_default_page_index", 2).apply()

        assertNull(fakePrefs.getString(HandleManager.getContainerSelectedPageKey(containerId), null))

        val pageManager = PageManager.getInstance(fakeContext)
        val selectedId = pageManager.getSelectedPageId(containerId)

        assertEquals("p2", selectedId)
        assertEquals("p2", fakePrefs.getString(HandleManager.getContainerSelectedPageKey(containerId), null))
    }

    @Test
    fun testIsPageTypePresentInPrefs() {
        fakePrefs.edit().putString("handle_test_pages", """[{"id":"my_tracker","type":"app_tracker","title":"Tracker"}]""").apply()
        assertTrue(PageManager.isPageTypePresentInPrefs(fakePrefs, "app_tracker"))
        assertTrue(com.example.utils.PageManager.isPageTypePresent(fakePrefs, "app_tracker"))
        assertFalse(PageManager.isPageTypePresentInPrefs(fakePrefs, "non_existent_page"))
    }
}
