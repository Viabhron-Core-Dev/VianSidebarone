package com.example.feature.element

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import com.example.core.FakeSharedPreferences
import com.example.feature.sidebar.GridWidgetItem
import com.example.loadHybridLocalItems
import com.example.parseHybridItems
import com.example.saveHybridItems
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HybridGridStorageScopeTest {

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
    private val pageId = "default_hybrid"
    private val containerId = "handle_1_swipe_left"

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        mockContext = MockTestContext(fakePrefs)
    }

    @Test
    fun testAddElementUsesContainerIsolatedKey() {
        // Fast-add an app element to Home Grid under container handle_1_swipe_left
        val success = ElementPlacementHelper.addElementToHybridGrid(
            context = mockContext,
            pageId = pageId,
            elementId = "app:com.android.settings",
            cols = 1,
            rows = 1,
            containerId = containerId
        )

        assertTrue(success)

        val isolatedKey = "handle_${containerId}_hybrid_grid_$pageId"
        val legacyKey = "hybrid_grid_$pageId"

        // The isolated key MUST exist and contain the element
        assertTrue("Isolated key should exist", fakePrefs.contains(isolatedKey))
        val isolatedItems = loadHybridLocalItems(fakePrefs, pageId, containerId)
        assertTrue(isolatedItems.any { it.id == "app:com.android.settings" })

        // The legacy key should NOT have had the element written to it
        val legacyStored = fakePrefs.getString(legacyKey, null)
        assertNull("Legacy key should not be written to when containerId is provided", legacyStored)

        // Verify broadcast was dispatched
        assertTrue("Broadcast should have been dispatched", mockContext.sentBroadcasts.isNotEmpty())
    }

    @Test
    fun testContainerIsolationBetweenHandles() {
        // Add element to container 1
        ElementPlacementHelper.addElementToHybridGrid(
            context = mockContext,
            pageId = pageId,
            elementId = "app:com.example.browser",
            cols = 1,
            rows = 1,
            containerId = "handle_1_swipe_left"
        )

        // Add different element to container 2
        ElementPlacementHelper.addElementToHybridGrid(
            context = mockContext,
            pageId = pageId,
            elementId = "app:com.example.calculator",
            cols = 1,
            rows = 1,
            containerId = "handle_2_swipe_right"
        )

        val itemsC1 = loadHybridLocalItems(fakePrefs, pageId, "handle_1_swipe_left")
        val itemsC2 = loadHybridLocalItems(fakePrefs, pageId, "handle_2_swipe_right")

        assertTrue(itemsC1.any { it.id == "app:com.example.browser" })
        assertFalse(itemsC1.any { it.id == "app:com.example.calculator" })

        assertTrue(itemsC2.any { it.id == "app:com.example.calculator" })
        assertFalse(itemsC2.any { it.id == "app:com.example.browser" })
    }

    @Test
    fun testOrphanedLegacyElementsDetectionAndRecovery() {
        val isolatedKey = "handle_${containerId}_hybrid_grid_$pageId"
        val legacyKey = "hybrid_grid_$pageId"

        // Seed isolated container with default items
        val isolatedList = listOf(
            GridWidgetItem(id = "system:ebook_reader", cols = 1, rows = 1, x = 0, y = 0),
            GridWidgetItem(id = "system:log_keeper", cols = 1, rows = 1, x = 1, y = 0)
        )
        saveHybridItems(fakePrefs, pageId, isolatedList, null, containerId)

        // Simulate legacy scope having an orphaned element added during a previous failed attempt
        val legacyList = listOf(
            GridWidgetItem(id = "system:ebook_reader", cols = 1, rows = 1, x = 0, y = 0),
            GridWidgetItem(id = "system:log_keeper", cols = 1, rows = 1, x = 1, y = 0),
            GridWidgetItem(id = "app:com.android.camera", cols = 1, rows = 1, x = 2, y = 0)
        )
        fakePrefs.edit().putString(legacyKey, org.json.JSONArray().apply {
            legacyList.forEach {
                put(org.json.JSONObject().apply {
                    put("id", it.id)
                    put("cols", it.cols)
                    put("rows", it.rows)
                    put("x", it.x)
                    put("y", it.y)
                })
            }
        }.toString()).commit()

        // 1. Detect orphans
        val orphans = ElementPlacementHelper.findOrphanedLegacyElements(fakePrefs, pageId, containerId)
        assertEquals(1, orphans.size)
        assertEquals("app:com.android.camera", orphans[0].id)

        // 2. Recover orphans
        val recovered = ElementPlacementHelper.recoverOrphanedLegacyElements(fakePrefs, pageId, containerId, mockContext)
        assertEquals(1, recovered.size)
        assertEquals("app:com.android.camera", recovered[0].id)

        // 3. Verify isolated container now includes the recovered element
        val reloaded = loadHybridLocalItems(fakePrefs, pageId, containerId)
        assertEquals(3, reloaded.size)
        assertTrue(reloaded.any { it.id == "app:com.android.camera" })

        // 4. Verify no duplicates on subsequent recovery call
        val secondRecovery = ElementPlacementHelper.recoverOrphanedLegacyElements(fakePrefs, pageId, containerId, mockContext)
        assertTrue(secondRecovery.isEmpty())
        val finalReloaded = loadHybridLocalItems(fakePrefs, pageId, containerId)
        assertEquals(3, finalReloaded.size)

        // 5. Verify legacy key data is still preserved
        assertTrue(fakePrefs.contains(legacyKey))
    }

    @Test
    fun testContinuousAddNonOverlappingPlacement() {
        // Add 3 consecutive items
        val elem1 = "app:app.one"
        val elem2 = "app:app.two"
        val elem3 = "app:app.three"

        ElementPlacementHelper.addElementToHybridGrid(mockContext, pageId, elem1, 1, 1, containerId)
        ElementPlacementHelper.addElementToHybridGrid(mockContext, pageId, elem2, 1, 1, containerId)
        ElementPlacementHelper.addElementToHybridGrid(mockContext, pageId, elem3, 1, 1, containerId)

        val items = loadHybridLocalItems(fakePrefs, pageId, containerId)
        val addedItems = items.filter { it.id in listOf(elem1, elem2, elem3) }
        assertEquals(3, addedItems.size)

        // Verify none of them overlap
        for (i in addedItems.indices) {
            for (j in i + 1 until addedItems.size) {
                val a = addedItems[i]
                val b = addedItems[j]
                val overlap = a.x < b.x + b.cols && a.x + a.cols > b.x &&
                        a.y < b.y + b.rows && a.y + a.rows > b.y
                assertFalse("Items $a and $b should not overlap", overlap)
            }
        }
    }
}
