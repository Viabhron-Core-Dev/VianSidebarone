package com.example.core

import android.graphics.Color
import com.example.util.HandleEdge
import com.example.util.HandleShape
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * HandleBehaviorFixesTest:
 * Verifies:
 * 1. Existing first/default handle keeps exactly one default gesture: Swipe Left -> Open Sidebar.
 * 2. Newly created handle starts with zero gestures.
 * 3. Does not copy gestures, targets, pages, elements, or Sidebar content to new handles.
 * 4. Runtime handle movement only happens through Handle Adjustment/Edit screen (long press does not move).
 * 5. Handle Adjustment position persists across settings exit and process restart.
 */
class HandleBehaviorFixesTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var fakeContext: FakeContext

    @Before
    fun setup() {
        fakePrefs = FakeSharedPreferences()
        fakeContext = FakeContext(fakePrefs)
    }

    @Test
    fun testFirstHandleHasExactlyOneDefaultGestureSwipeLeft() {
        val manager = HandleManager(fakeContext)

        val handles = manager.getAllHandles()
        assertEquals(1, handles.size)

        val firstHandle = handles.first()
        assertEquals("handle_1", firstHandle.id)

        // Verify gestures on first handle
        assertEquals(HandleManager.ACTION_OPEN_SIDEBAR, firstHandle.getActionForGesture(HandleGestures.SWIPE_LEFT))
        assertEquals(HandleManager.ACTION_NONE, firstHandle.getActionForGesture(HandleGestures.TAP))
        assertEquals(HandleManager.ACTION_NONE, firstHandle.getActionForGesture(HandleGestures.DOUBLE_TAP))
        assertEquals(HandleManager.ACTION_NONE, firstHandle.getActionForGesture(HandleGestures.LONG_PRESS))
        assertEquals(HandleManager.ACTION_NONE, firstHandle.getActionForGesture(HandleGestures.SWIPE_RIGHT))
        assertEquals(HandleManager.ACTION_NONE, firstHandle.getActionForGesture(HandleGestures.SWIPE_UP))
        assertEquals(HandleManager.ACTION_NONE, firstHandle.getActionForGesture(HandleGestures.SWIPE_DOWN))

        val configuredGestures = HandleGestures.ALL.filter {
            firstHandle.getActionForGesture(it) != HandleManager.ACTION_NONE
        }
        assertEquals(1, configuredGestures.size)
        assertEquals(listOf(HandleGestures.SWIPE_LEFT), configuredGestures)
    }

    @Test
    fun testNewlyCreatedHandleStartsWithZeroGesturesAndNoSidebarContent() {
        val manager = HandleManager(fakeContext)

        // Create a new handle
        val newHandle = manager.createHandle(name = "Left Test Handle", edge = HandleEdge.LEFT)
        assertEquals("handle_2", newHandle.id)
        assertEquals(HandleEdge.LEFT, newHandle.edge)

        // Verify new handle has ZERO gestures
        for (gesture in HandleGestures.ALL) {
            assertEquals(
                "Gesture $gesture must be ACTION_NONE on newly created handle",
                HandleManager.ACTION_NONE,
                newHandle.getActionForGesture(gesture)
            )
        }

        val configuredGestures = HandleGestures.ALL.filter {
            newHandle.getActionForGesture(it) != HandleManager.ACTION_NONE
        }
        assertEquals(0, configuredGestures.size)

        // Verify no container pages were seeded for the new handle
        for (gesture in HandleGestures.ALL) {
            val containerId = HandleManager.getContainerId(newHandle.id, gesture)
            val pagesKey = HandleManager.getContainerPagesKey(containerId)
            assertFalse(
                "Container pages key $pagesKey must not exist for newly created handle",
                fakePrefs.contains(pagesKey)
            )
        }

        // Verify that only when a gesture is explicitly configured, sidebar content is created
        manager.configureGesture(newHandle.id, HandleGestures.SWIPE_RIGHT, HandleManager.ACTION_OPEN_SIDEBAR)
        val containerId = HandleManager.getContainerId(newHandle.id, HandleGestures.SWIPE_RIGHT)
        val pagesKey = HandleManager.getContainerPagesKey(containerId)
        assertTrue(
            "Container pages key must be created only after explicitly configuring gesture",
            fakePrefs.contains(pagesKey)
        )
    }

    @Test
    fun testLegacyMoveHandlePreferenceResolvesToNone() {
        // Simulate a pre-existing preference with "move_handle" for long press
        fakePrefs.edit()
            .putString("handle_long_press_1", HandleManager.ACTION_MOVE_HANDLE)
            .putString("handle_ids", "handle_1")
            .apply()

        val manager = HandleManager(fakeContext)
        val handle1 = manager.getHandle("handle_1")

        assertNotNull(handle1)
        assertEquals(HandleManager.ACTION_NONE, handle1?.getActionForGesture(HandleGestures.LONG_PRESS))
    }

    @Test
    fun testPositionAdjustmentPersistenceAcrossProcessRestart() {
        val manager1 = HandleManager(fakeContext)
        val handle = manager1.getAllHandles().first()
        assertEquals(0.5f, handle.positionPercent, 0.001f)

        // User adjusts position in Handle Adjustment/Edit screen to 72%
        val adjusted = handle.copy(
            positionPercent = 0.72f,
            widthDp = 20,
            heightDp = 180,
            edge = HandleEdge.RIGHT
        )
        manager1.saveHandle(adjusted)

        // Simulate leaving settings and recreating HandleManager (process restart)
        val newContext = FakeContext(fakePrefs)
        val manager2 = HandleManager(newContext)

        val reloadedHandle = manager2.getHandle("handle_1")
        assertNotNull(reloadedHandle)
        assertEquals(0.72f, reloadedHandle?.positionPercent ?: 0f, 0.001f)
        assertEquals(20, reloadedHandle?.widthDp)
        assertEquals(180, reloadedHandle?.heightDp)
        assertEquals(HandleEdge.RIGHT, reloadedHandle?.edge)
    }
}
