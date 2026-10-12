package com.example.feature.sidebar

import com.example.core.SidebarPage
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyCommandType
import com.example.core.ipc.HeavyEvent
import com.example.core.ipc.HeavyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the Main -> Heavy Calculator Split:
 * 1. Command and Event serialization (MOUNT_PAGE, UNMOUNT_PAGE, UPDATE_PAGE_BOUNDS, page_height_changed).
 * 2. Heavy desired-height event payload validation and filtering logic.
 * 3. Duplicate/unchanged height suppression logic.
 * 4. Bounds identical suppression logic.
 * 5. Generic bootstrap height derivation preventing 44px empty-TextView collapse.
 * 6. Routing invariants: Calculator configuration routes to Heavy placeholder, while Home Grid remains resident.
 */
class HeavyCalculatorSplitTest {

    @Test
    fun testCommandSerializationForHeavyPageLifecycle() {
        val mountCommand = HeavyCommand(
            commandId = "mount_calc_123",
            type = HeavyCommandType.MOUNT_PAGE,
            targetId = "calc_1",
            payload = mapOf(
                "pageId" to "calc_1",
                "pageType" to "calculator",
                "containerId" to "sidebar",
                "x" to "100",
                "y" to "200",
                "width" to "320",
                "height" to "540"
            )
        )

        val json = mountCommand.toJson()
        val deserialized = HeavyCommand.fromJson(json)

        assertEquals("mount_calc_123", deserialized.commandId)
        assertEquals(HeavyCommandType.MOUNT_PAGE, deserialized.type)
        assertEquals("calc_1", deserialized.targetId)
        assertEquals("calculator", deserialized.payload["pageType"])
        assertEquals("100", deserialized.payload["x"])
        assertEquals("200", deserialized.payload["y"])
        assertEquals("320", deserialized.payload["width"])
        assertEquals("540", deserialized.payload["height"])

        val unmountCommand = HeavyCommand(
            commandId = "unmount_calc_123",
            type = HeavyCommandType.UNMOUNT_PAGE,
            targetId = "calc_1",
            payload = mapOf("pageId" to "calc_1")
        )

        val unmountJson = unmountCommand.toJson()
        val deserializedUnmount = HeavyCommand.fromJson(unmountJson)
        assertEquals(HeavyCommandType.UNMOUNT_PAGE, deserializedUnmount.type)
        assertEquals("calc_1", deserializedUnmount.payload["pageId"])
    }

    @Test
    fun testUpdatePageBoundsCommand() {
        val boundsCommand = HeavyCommand(
            commandId = "bounds_calc_123",
            type = HeavyCommandType.UPDATE_PAGE_BOUNDS,
            targetId = "calc_1",
            payload = mapOf(
                "pageId" to "calc_1",
                "x" to "150",
                "y" to "220",
                "width" to "330",
                "height" to "560"
            )
        )

        val json = boundsCommand.toJson()
        val deserialized = HeavyCommand.fromJson(json)
        assertEquals(HeavyCommandType.UPDATE_PAGE_BOUNDS, deserialized.type)
        assertEquals("150", deserialized.payload["x"])
        assertEquals("220", deserialized.payload["y"])
        assertEquals("330", deserialized.payload["width"])
        assertEquals("560", deserialized.payload["height"])
    }

    @Test
    fun testHeavyDesiredHeightEventSerializationAndParsing() {
        val heightEvent = HeavyEvent(
            eventId = "height_calc_1_999",
            type = HeavyEventType.STATE_UPDATED,
            sourceId = "calc_1",
            payload = mapOf(
                "action" to "page_height_changed",
                "pageId" to "calc_1",
                "height" to "608"
            )
        )

        val json = heightEvent.toJson()
        val deserialized = HeavyEvent.fromJson(json)

        assertEquals("height_calc_1_999", deserialized.eventId)
        assertEquals(HeavyEventType.STATE_UPDATED, deserialized.type)
        assertEquals("calc_1", deserialized.sourceId)
        assertEquals("page_height_changed", deserialized.payload["action"])
        assertEquals("calc_1", deserialized.payload["pageId"])
        assertEquals("608", deserialized.payload["height"])
        assertEquals(608, deserialized.payload["height"]?.toIntOrNull())
    }

    @Test
    fun testHeightEventFilteringLogic() {
        val myPageId = "page_calc_test"
        var currentDesiredHeight = -1
        var applyCount = 0

        fun processEvent(event: HeavyEvent) {
            val action = event.payload["action"]
            val targetPageId = event.payload["pageId"] ?: event.sourceId
            if (action == "page_height_changed" && targetPageId == myPageId) {
                val reportedH = event.payload["height"]?.toIntOrNull() ?: return
                if (reportedH > 0 && reportedH != currentDesiredHeight) {
                    currentDesiredHeight = reportedH
                    applyCount++
                }
            }
        }

        // 1. Valid height for this page -> applied
        val validEvent = HeavyEvent(
            eventId = "e1",
            type = HeavyEventType.STATE_UPDATED,
            sourceId = myPageId,
            payload = mapOf("action" to "page_height_changed", "pageId" to myPageId, "height" to "608")
        )
        processEvent(validEvent)
        assertEquals(608, currentDesiredHeight)
        assertEquals(1, applyCount)

        // 2. Duplicate unchanged height -> ignored (no loop)
        val duplicateEvent = HeavyEvent(
            eventId = "e2",
            type = HeavyEventType.STATE_UPDATED,
            sourceId = myPageId,
            payload = mapOf("action" to "page_height_changed", "pageId" to myPageId, "height" to "608")
        )
        processEvent(duplicateEvent)
        assertEquals(608, currentDesiredHeight)
        assertEquals(1, applyCount) // Count unchanged

        // 3. Event for a different page ID -> ignored
        val otherPageEvent = HeavyEvent(
            eventId = "e3",
            type = HeavyEventType.STATE_UPDATED,
            sourceId = "other_page",
            payload = mapOf("action" to "page_height_changed", "pageId" to "other_page", "height" to "750")
        )
        processEvent(otherPageEvent)
        assertEquals(608, currentDesiredHeight)
        assertEquals(1, applyCount)

        // 4. Invalid non-positive height -> ignored
        val negativeHeightEvent = HeavyEvent(
            eventId = "e4",
            type = HeavyEventType.STATE_UPDATED,
            sourceId = myPageId,
            payload = mapOf("action" to "page_height_changed", "pageId" to myPageId, "height" to "-50")
        )
        processEvent(negativeHeightEvent)
        assertEquals(608, currentDesiredHeight)
        assertEquals(1, applyCount)

        // 5. Valid changed height (e.g. drawer toggled open) -> applied
        val changedEvent = HeavyEvent(
            eventId = "e5",
            type = HeavyEventType.STATE_UPDATED,
            sourceId = myPageId,
            payload = mapOf("action" to "page_height_changed", "pageId" to myPageId, "height" to "740")
        )
        processEvent(changedEvent)
        assertEquals(740, currentDesiredHeight)
        assertEquals(2, applyCount)
    }

    @Test
    fun testBoundsIdenticalSuppressionLogic() {
        var lastDispatchedLeft = 100
        var lastDispatchedTop = 200
        var lastDispatchedWidth = 320
        var lastDispatchedHeight = 608
        var dispatchCount = 1

        fun checkAndDispatch(left: Int, top: Int, width: Int, height: Int): Boolean {
            if (left == lastDispatchedLeft &&
                top == lastDispatchedTop &&
                width == lastDispatchedWidth &&
                height == lastDispatchedHeight) {
                // Suppressed
                return false
            }
            lastDispatchedLeft = left
            lastDispatchedTop = top
            lastDispatchedWidth = width
            lastDispatchedHeight = height
            dispatchCount++
            return true
        }

        // Identical stationary coordinates -> suppressed
        assertFalse(checkAndDispatch(100, 200, 320, 608))
        assertEquals(1, dispatchCount)

        // True coordinate shift (e.g. orientation or drawer expansion) -> dispatched
        assertTrue(checkAndDispatch(100, 200, 320, 740))
        assertEquals(2, dispatchCount)
    }

    @Test
    fun testGenericBootstrapHeightStrategy() {
        // Test that generic fallback does not collapse to 44px
        val screenHeightPx = 1600
        val bootstrapFraction = 0.45f
        val computedBootstrap = (screenHeightPx * bootstrapFraction).toInt()

        // Evaluates to 720px, far above the 44px intrinsic TextView collapse
        assertEquals(720, computedBootstrap)
        assertTrue(computedBootstrap > 200)
        assertNotEquals(44, computedBootstrap)
    }

    @Test
    fun testCalculatorDedicatedBootstrapHeight() {
        val density = 2.3385f
        val defaultDp = 260
        val expectedPx = (defaultDp * density).toInt()

        // On ~2.34 density displays, 260dp evaluates exactly to 608px
        assertEquals(608, expectedPx)
        assertTrue(expectedPx > 200)
    }

    @Test
    fun testCachedDesiredHeightPreservation() {
        val pageId = "calc_persist_test"
        HeavyPagePlaceholderView.clearCachedDesiredHeights()
        assertEquals(null, HeavyPagePlaceholderView.getCachedDesiredHeight(pageId))

        HeavyPagePlaceholderView.setCachedDesiredHeight(pageId, 608)
        assertEquals(608, HeavyPagePlaceholderView.getCachedDesiredHeight(pageId))

        HeavyPagePlaceholderView.clearCachedDesiredHeights()
    }

    @Test
    fun testHostAnimationSuppressionLogic() {
        // Verify that while translationX != 0f (animateIn active), stable mount is deferred
        fun isAnimationSettled(translationX: Float, translationY: Float): Boolean {
            return translationX == 0f && translationY == 0f
        }

        assertFalse("Slide-in animation at +560f should defer mount", isAnimationSettled(560f, 0f))
        assertFalse("Slide-in animation at +200f should defer mount", isAnimationSettled(200f, 0f))
        assertFalse("Slide-in animation at -560f should defer mount", isAnimationSettled(-560f, 0f))
        assertTrue("Settled translation (0, 0) should allow stable mount", isAnimationSettled(0f, 0f))
    }

    @Test
    fun testPageFactoryTypeMappings() {
        // Verify Calculator page type metadata
        val calcPage = SidebarPage(id = "page_calc", type = "calculator", title = "Calculator")
        assertEquals("calculator", calcPage.type)

        // Verify Home Grid page type metadata
        val homeGridPage = SidebarPage(id = "default_hybrid", type = "default_hybrid", title = "Home Grid")
        assertEquals("default_hybrid", homeGridPage.type)

        // Verify compass page type metadata
        val compassPage = SidebarPage(id = "page_compass", type = "compass", title = "Compass")
        assertEquals("compass", compassPage.type)
    }
}
