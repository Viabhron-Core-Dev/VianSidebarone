package com.example.feature.system_hub

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.example.feature.element.ElementActionRegistry
import com.example.feature.sidebar.ALL_UTILITIES_ACTIONS
import com.example.feature.sidebar.SidebarItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PrivacyCurtainManagerTest {

    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example"
    }

    private lateinit var mockContext: TestContext

    @Before
    fun setUp() {
        mockContext = TestContext()
        PrivacyCurtainManager.disable(mockContext)
    }

    @Test
    fun testInitialState() {
        assertFalse(PrivacyCurtainManager.isEnabled)
        assertEquals(0.90f, PrivacyCurtainManager.opacity, 0.01f)
    }

    @Test
    fun testOpacityClamping() {
        PrivacyCurtainManager.setOpacityLevel(0.10f)
        assertEquals(0.30f, PrivacyCurtainManager.opacity, 0.01f)

        PrivacyCurtainManager.setOpacityLevel(1.50f)
        assertEquals(1.00f, PrivacyCurtainManager.opacity, 0.01f)

        PrivacyCurtainManager.setOpacityLevel(0.75f)
        assertEquals(0.75f, PrivacyCurtainManager.opacity, 0.01f)
    }

    @Test
    fun testDisableWhenNotEnabledIsSafe() {
        PrivacyCurtainManager.disable(mockContext)
        assertFalse(PrivacyCurtainManager.isEnabled)
    }

    @Test
    fun testActionItemProperties() {
        val curtainAction = ALL_UTILITIES_ACTIONS.find { it.id == "display:privacy_curtain" }
        assertNotNull(curtainAction)
        assertTrue(curtainAction is SidebarItem.DisplayAction)
        assertEquals("Privacy Curtain", curtainAction?.label)
        assertEquals("display:privacy_curtain", curtainAction?.id)
    }

    @Test
    fun testRegistryDescriptorResolution() {
        val registry = ElementActionRegistry.getInstance(mockContext)
        assertTrue(registry.isRegistered("display:privacy_curtain"))

        val descriptor = registry.getDescriptor("display:privacy_curtain")
        assertNotNull(descriptor)
        assertEquals("Privacy Curtain", descriptor?.displayName)
    }
}
