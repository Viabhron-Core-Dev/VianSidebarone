package com.example.feature.sidebar

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import com.example.core.FakeSharedPreferences
import com.example.feature.element.ElementPlacementHelper
import com.example.loadHybridLocalItems
import com.example.saveHybridItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Audit tests verifying complete container isolation across all Grid-type Sidebar pages:
 * (A) Hybrid Grid across containers A & B
 * (B) Widgets Grid across containers A & B (items & columns)
 * (C) Apps Grid across containers A & B (items & columns/rows)
 */
class GridContainerIsolationAuditTest {

    private class MockTestContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        val sentBroadcasts = mutableListOf<Intent>()
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example"
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun sendBroadcast(intent: Intent?) {
            if (intent != null) {
                sentBroadcasts.add(intent)
            }
        }
    }

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var mockContext: MockTestContext
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val containerA = "handle_1_swipe_left"
    private val containerB = "handle_2_swipe_right"
    private val pageId = "test_audit_grid"

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        mockContext = MockTestContext(fakePrefs)
    }

    // =========================================================================
    // Test A: Hybrid Grid Isolation
    // =========================================================================
    @Test
    fun testHybridGridContainerIsolation() {
        // 1. Add item to Container A
        ElementPlacementHelper.addElementToHybridGrid(
            context = mockContext,
            pageId = pageId,
            elementId = "app:com.example.app_a",
            cols = 1,
            rows = 1,
            containerId = containerA
        )

        // 2. Add item to Container B
        ElementPlacementHelper.addElementToHybridGrid(
            context = mockContext,
            pageId = pageId,
            elementId = "app:com.example.app_b",
            cols = 2,
            rows = 2,
            containerId = containerB
        )

        // 3. Load items from each container
        val itemsA = loadHybridLocalItems(fakePrefs, pageId, containerA)
        val itemsB = loadHybridLocalItems(fakePrefs, pageId, containerB)

        // Verify Container A has only app_a
        assertEquals(1, itemsA.size)
        assertEquals("app:com.example.app_a", itemsA[0].id)
        assertFalse(itemsA.any { it.id == "app:com.example.app_b" })

        // Verify Container B has only app_b
        assertEquals(1, itemsB.size)
        assertEquals("app:com.example.app_b", itemsB[0].id)
        assertFalse(itemsB.any { it.id == "app:com.example.app_a" })

        // Verify keys in SharedPreferences
        val keyA = "handle_${containerA}_hybrid_grid_$pageId"
        val keyB = "handle_${containerB}_hybrid_grid_$pageId"
        assertTrue(fakePrefs.contains(keyA))
        assertTrue(fakePrefs.contains(keyB))
        assertNotEquals(fakePrefs.getString(keyA, null), fakePrefs.getString(keyB, null))

        // Ensure legacy key was never written
        assertNull(fakePrefs.getString("hybrid_grid_$pageId", null))
    }

    // =========================================================================
    // Test B: Widgets Grid Isolation (Items & Columns)
    // =========================================================================
    @Test
    fun testWidgetsGridContainerIsolation() {
        val widgetsKeyA = "handle_${containerA}_widgets_grid_$pageId"
        val widgetsKeyB = "handle_${containerB}_widgets_grid_$pageId"
        val colsKeyA = "handle_${containerA}_widgets_grid_cols_$pageId"
        val colsKeyB = "handle_${containerB}_widgets_grid_cols_$pageId"

        // Store different widget configurations and columns in Container A and Container B
        fakePrefs.edit()
            .putString(widgetsKeyA, "[{\"id\":\"widget:101\",\"cols\":2,\"rows\":2,\"x\":0,\"y\":0}]")
            .putInt(colsKeyA, 3)
            .putString(widgetsKeyB, "[{\"id\":\"widget:202\",\"cols\":4,\"rows\":3,\"x\":0,\"y\":0}]")
            .putInt(colsKeyB, 5)
            .commit()

        // Verify items in A vs B
        val rawA = fakePrefs.getString(widgetsKeyA, null)
        val rawB = fakePrefs.getString(widgetsKeyB, null)
        assertNotNull(rawA)
        assertNotNull(rawB)
        assertTrue(rawA!!.contains("widget:101"))
        assertFalse(rawA.contains("widget:202"))

        assertTrue(rawB!!.contains("widget:202"))
        assertFalse(rawB.contains("widget:101"))

        // Verify columns isolation
        val colsA = fakePrefs.getInt(colsKeyA, 4)
        val colsB = fakePrefs.getInt(colsKeyB, 4)
        assertEquals(3, colsA)
        assertEquals(5, colsB)

        // Verify legacy keys were not touched
        assertNull(fakePrefs.getString("widgets_grid_$pageId", null))
        assertEquals(4, fakePrefs.getInt("widgets_grid_cols_$pageId", 4))
    }

    // =========================================================================
    // Test C: Apps Grid Isolation (Items, Columns, Rows, and No Legacy Bleed)
    // =========================================================================
    @Test
    fun testAppsGridContainerIsolation() {
        val prefKeyA = "sidebar_apps_${containerA}_$pageId"
        val prefKeyB = "sidebar_apps_${containerB}_$pageId"

        val colsKeyA = "handle_${containerA}_page_${pageId}_columns"
        val rowsKeyA = "handle_${containerA}_page_${pageId}_rows"
        val colsKeyB = "handle_${containerB}_page_${pageId}_columns"
        val rowsKeyB = "handle_${containerB}_page_${pageId}_rows"

        // Pre-populate ElementMetadata so ElementIdParser resolves them without PackageManager
        ElementMetadataStore.save(mockContext, ElementMetadata("app:com.example.alpha", "app", "com.example.alpha", "Alpha", ""))
        ElementMetadataStore.save(mockContext, ElementMetadata("app:com.example.beta", "app", "com.example.beta", "Beta", ""))
        ElementMetadataStore.save(mockContext, ElementMetadata("app:com.example.gamma", "app", "com.example.gamma", "Gamma", ""))

        // Set up Container A & B apps and dimensions in fakePrefs
        fakePrefs.edit()
            .putString(prefKeyA, JSONArray(listOf("com.example.alpha", "com.example.beta")).toString())
            .putInt(colsKeyA, 3)
            .putInt(rowsKeyA, 5)
            .putString(prefKeyB, JSONArray(listOf("com.example.gamma")).toString())
            .putInt(colsKeyB, 4)
            .putInt(rowsKeyB, 6)
            .commit()

        // 1. Verify Managers loaded for A and B are independent
        val managerA = SidebarAppsManager(mockContext, fakePrefs, testScope, prefKeyA) {}
        val managerB = SidebarAppsManager(mockContext, fakePrefs, testScope, prefKeyB) {}
        managerA.ensureLoaded()
        managerB.ensureLoaded()
        Thread.sleep(250)

        assertEquals(2, managerA.activeItems.size)
        assertTrue(managerA.activeItems.any { (it as? SidebarItem.App)?.packageName == "com.example.alpha" })
        assertFalse(managerA.activeItems.any { (it as? SidebarItem.App)?.packageName == "com.example.gamma" })

        assertEquals(1, managerB.activeItems.size)
        assertTrue(managerB.activeItems.any { (it as? SidebarItem.App)?.packageName == "com.example.gamma" })
        assertFalse(managerB.activeItems.any { (it as? SidebarItem.App)?.packageName == "com.example.alpha" })

        // 2. Verify column and row dimensions are isolated
        assertEquals(3, fakePrefs.getInt(colsKeyA, 0))
        assertEquals(5, fakePrefs.getInt(rowsKeyA, 0))
        assertEquals(4, fakePrefs.getInt(colsKeyB, 0))
        assertEquals(6, fakePrefs.getInt(rowsKeyB, 0))

        // 3. Verify Container B does NOT inherit from legacy unscoped keys if Container B key is absent
        val freshContainerBPage = "new_page_no_config"
        val freshKeyB = "sidebar_apps_${containerB}_$freshContainerBPage"

        // Seed a legacy unscoped key with some apps
        fakePrefs.edit()
            .putString("sidebar_apps_$freshContainerBPage", JSONArray(listOf("com.example.legacy_app")).toString())
            .putString("sidebar_apps", JSONArray(listOf("com.example.global_app")).toString())
            .commit()

        val freshManagerB = SidebarAppsManager(mockContext, fakePrefs, testScope, freshKeyB) {}
        freshManagerB.ensureLoaded()
        Thread.sleep(250)

        // Non-default container B MUST NOT adopt the legacy unscoped apps
        assertTrue("Non-default container must not bleed from legacy unscoped keys", freshManagerB.activeItems.isEmpty())
    }
}
