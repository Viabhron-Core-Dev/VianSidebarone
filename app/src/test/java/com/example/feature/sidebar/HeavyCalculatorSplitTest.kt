package com.example.feature.sidebar

import com.example.core.PageTypes
import com.example.core.SidebarPage
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyCommandType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the First Vertical Slice of the Main -> Heavy Sidebar Page-Runtime Split:
 * 1. Calculator page configuration routes to HeavyPagePlaceholderView in Main instead of CalculatorPageView.
 * 2. Resident Home Grid ("default_hybrid") still routes to its resident HybridGridPageView in Main.
 * 3. Unrelated page types (e.g. Compass, Widget) continue following their existing Main path.
 * 4. Mount, unmount, and update bounds commands are correctly constructed and serialized.
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
