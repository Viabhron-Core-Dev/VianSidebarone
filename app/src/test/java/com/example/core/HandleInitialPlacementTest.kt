package com.example.core

import com.example.util.HandleEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Focused test suite for automatic initial placement of newly created handles:
 * 1. Creating Handle 2 on the same edge as Handle 1 gives it a different non-overlapping initial position.
 * 2. Creating Handle 3 does not alter Handle 1 or Handle 2 positions.
 * 3. New position remains within valid bounds (0.05f..0.95f).
 * 4. Existing saved/manual positions are preserved.
 * 5. Handles on opposite edges do not unnecessarily affect each other's placement.
 * 6. Reloading existing handles does not trigger auto-placement.
 */
class HandleInitialPlacementTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var fakeContext: FakeContext

    @Before
    fun setup() {
        fakePrefs = FakeSharedPreferences()
        fakeContext = FakeContext(fakePrefs)
    }

    // 1. Creating Handle 2 on the same edge as Handle 1 gives it a different non-overlapping initial position
    @Test
    fun testHandle2OnSameEdgeReceivesNonOverlappingPosition() {
        val manager = HandleManager(fakeContext)
        val handle1 = manager.getHandle("handle_1")
        assertNotNull(handle1)
        assertEquals(HandleEdge.RIGHT, handle1?.edge)
        assertEquals(0.5f, handle1!!.positionPercent, 0.001f)

        // Create Handle 2 on the same edge (RIGHT)
        val handle2 = manager.createHandle(name = "Handle 2", edge = HandleEdge.RIGHT)
        assertEquals("handle_2", handle2.id)
        assertEquals(HandleEdge.RIGHT, handle2.edge)

        // Must receive a different position from Handle 1
        assertNotEquals(handle1.positionPercent, handle2.positionPercent, 0.001f)

        // Verify no vertical overlap: Handle 1 is 160dp at 0.5f (320..480dp in 800dp screen)
        // Handle 2 is 120dp placed below Handle 1 with at least 16dp spacing
        assertTrue("Handle 2 must be placed below Handle 1", handle2.positionPercent > handle1.positionPercent)
        assertTrue("Handle 2 positionPercent must be at least 0.70f", handle2.positionPercent >= 0.70f)
    }

    // 2. Creating Handle 3 does not alter Handle 1 or Handle 2 positions
    @Test
    fun testCreatingHandle3DoesNotAlterExistingHandlePositions() {
        val manager = HandleManager(fakeContext)
        val handle1Before = manager.getHandle("handle_1")!!
        val pos1Before = handle1Before.positionPercent

        val handle2 = manager.createHandle(name = "Handle 2", edge = HandleEdge.RIGHT)
        val pos2Before = handle2.positionPercent

        // Create Handle 3
        val handle3 = manager.createHandle(name = "Handle 3", edge = HandleEdge.RIGHT)
        val pos3 = handle3.positionPercent

        // Verify existing handles did not move
        val handle1After = manager.getHandle("handle_1")!!
        val handle2After = manager.getHandle("handle_2")!!
        assertEquals(pos1Before, handle1After.positionPercent, 0.0001f)
        assertEquals(pos2Before, handle2After.positionPercent, 0.0001f)

        // Verify Handle 3 has a non-overlapping position
        assertNotEquals(pos1Before, pos3, 0.001f)
        assertNotEquals(pos2Before, pos3, 0.001f)
        assertTrue("Handle 3 must be placed below Handle 2", pos3 > pos2Before)
    }

    // 3. New position remains within valid bounds
    @Test
    fun testNewPositionsRemainWithinValidBounds() {
        val manager = HandleManager(fakeContext)

        // Create 4 handles sequentially on the same edge
        val h2 = manager.createHandle(name = "H2", edge = HandleEdge.RIGHT)
        val h3 = manager.createHandle(name = "H3", edge = HandleEdge.RIGHT)
        val h4 = manager.createHandle(name = "H4", edge = HandleEdge.RIGHT)
        val h5 = manager.createHandle(name = "H5", edge = HandleEdge.RIGHT)

        val handles = listOf(h2, h3, h4, h5)
        for (h in handles) {
            assertTrue(
                "Handle ${h.id} position ${h.positionPercent} must be >= 0.05f",
                h.positionPercent >= 0.05f
            )
            assertTrue(
                "Handle ${h.id} position ${h.positionPercent} must be <= 0.95f",
                h.positionPercent <= 0.95f
            )
        }

        // When space below is exhausted, subsequent handles wrap to available upper slots
        val h1 = manager.getHandle("handle_1")!!
        // At least one handle should be positioned above Handle 1 when lower screen area is filled
        assertTrue(
            "When lower area is filled, at least one handle should be placed above Handle 1",
            handles.any { it.positionPercent < h1.positionPercent }
        )
    }

    // 4. Existing saved/manual positions are preserved
    @Test
    fun testExistingSavedManualPositionsArePreserved() {
        val manager = HandleManager(fakeContext)

        // User manually moved Handle 1 to 0.20f (near top)
        val handle1 = manager.getHandle("handle_1")!!
        manager.saveHandle(handle1.copy(positionPercent = 0.20f))

        // Create Handle 2
        val handle2 = manager.createHandle(name = "Handle 2", edge = HandleEdge.RIGHT)

        // Handle 1 must retain its manual position
        val reloadedHandle1 = manager.getHandle("handle_1")!!
        assertEquals(0.20f, reloadedHandle1.positionPercent, 0.001f)

        // Handle 2 should be placed below Handle 1's custom position
        assertTrue(
            "Handle 2 must be positioned below Handle 1's custom 0.20 position",
            handle2.positionPercent > 0.20f
        )
    }

    // 5. Handles on opposite edges do not unnecessarily affect each other's placement
    @Test
    fun testOppositeEdgesDoNotAffectPlacement() {
        val manager = HandleManager(fakeContext)

        // Handle 1 is on RIGHT at 0.5f
        val handle1 = manager.getHandle("handle_1")!!
        assertEquals(HandleEdge.RIGHT, handle1.edge)

        // Create Handle 2 on LEFT edge (first handle on LEFT)
        val handle2 = manager.createHandle(name = "Left 1", edge = HandleEdge.LEFT)
        assertEquals(HandleEdge.LEFT, handle2.edge)
        assertEquals("First handle on LEFT edge should start at default center 0.5f", 0.5f, handle2.positionPercent, 0.001f)

        // Create Handle 3 on LEFT edge (second handle on LEFT)
        val handle3 = manager.createHandle(name = "Left 2", edge = HandleEdge.LEFT)
        assertEquals(HandleEdge.LEFT, handle3.edge)
        assertTrue("Handle 3 on LEFT edge should be placed below Handle 2", handle3.positionPercent > handle2.positionPercent)

        // Handle 1 on RIGHT edge remains untouched
        val handle1After = manager.getHandle("handle_1")!!
        assertEquals(0.5f, handle1After.positionPercent, 0.001f)
    }

    // 6. Reloading existing handles does not trigger auto-placement
    @Test
    fun testReloadingExistingHandlesDoesNotTriggerAutoPlacement() {
        val manager1 = HandleManager(fakeContext)
        val h2 = manager1.createHandle(name = "Handle 2", edge = HandleEdge.RIGHT)
        val originalH2Pos = h2.positionPercent

        // Simulate app restart / new HandleManager instance on same storage
        val manager2 = HandleManager(fakeContext)
        val loadedH1 = manager2.getHandle("handle_1")!!
        val loadedH2 = manager2.getHandle("handle_2")!!

        assertEquals("Handle 1 position must survive reload", 0.5f, loadedH1.positionPercent, 0.001f)
        assertEquals("Handle 2 position must survive reload", originalH2Pos, loadedH2.positionPercent, 0.001f)

        // getAllHandles() must return exactly the persisted positions without re-calculating
        val allHandles = manager2.getAllHandles()
        val allH2 = allHandles.first { it.id == "handle_2" }
        assertEquals(originalH2Pos, allH2.positionPercent, 0.001f)
    }
}
