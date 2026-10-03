package com.example.feature.system_hub

import android.graphics.Color
import com.example.feature.sidebar.ALL_UTILITIES_ACTIONS
import com.example.feature.sidebar.SidebarAppsManager
import com.example.feature.sidebar.SidebarItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EInkPaperFilterIntegrationTest {

    @Test
    fun testPaperTintColorFormula() {
        // Calibrated Warm Sepia Paper Tint
        assertEquals("#54DCD4C0", EInkPaperFilterManager.PAPER_TINT_COLOR)

        val hex = EInkPaperFilterManager.PAPER_TINT_COLOR.removePrefix("#")
        val alpha = hex.substring(0, 2).toInt(16)
        val red = hex.substring(2, 4).toInt(16)
        val green = hex.substring(4, 6).toInt(16)
        val blue = hex.substring(6, 8).toInt(16)

        // Alpha should be ~33% (84 / 255)
        assertEquals(0x54, alpha)
        // Warm paper tones have Red > Green > Blue
        assertEquals(0xDC, red)
        assertEquals(0xD4, green)
        assertEquals(0xC0, blue)
        assertTrue("Red channel must exceed Green channel for warm pulp tone", red > green)
        assertTrue("Green channel must exceed Blue channel to eliminate cold blue spike", green > blue)
    }

    @Test
    fun testAllUtilitiesActionsContainsEInkMode() {
        val eInkItem = ALL_UTILITIES_ACTIONS.find { it is SidebarItem.DisplayAction && it.action == "e_ink_mode" } as? SidebarItem.DisplayAction
        assertNotNull("E-Ink Paper Mode action must be present in ALL_UTILITIES_ACTIONS", eInkItem)
        assertEquals("e_ink_mode", eInkItem!!.action)
        assertEquals("E-Ink Paper Mode", eInkItem.label)
        assertEquals("display:e_ink_mode", eInkItem.id)
    }

    @Test
    fun testDisplayEInkModeResolution() {
        val actionId = "display:e_ink_mode".substringAfter("display:")
        val displayAction = ALL_UTILITIES_ACTIONS.filterIsInstance<SidebarItem.DisplayAction>().find { it.action == actionId }
        assertNotNull("Should resolve e_ink_mode from ALL_UTILITIES_ACTIONS", displayAction)
        assertEquals("e_ink_mode", displayAction!!.action)
        assertEquals("E-Ink Paper Mode", displayAction.label)
        assertEquals("display:e_ink_mode", displayAction.id)
    }

    @Test
    fun testInitialManagerDisabledState() {
        assertFalse("E-Ink paper filter should be disabled by default", EInkPaperFilterManager.isEnabled)
    }
}
