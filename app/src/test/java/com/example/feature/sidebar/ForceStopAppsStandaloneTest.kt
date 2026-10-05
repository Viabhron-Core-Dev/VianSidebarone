package com.example.feature.sidebar

import com.example.core.FakeContext
import com.example.core.FakeSharedPreferences
import com.example.core.HandleManager
import com.example.core.PageManager
import com.example.core.SidebarPage
import com.example.utils.AppTrackerHelper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * ForceStopAppsStandaloneTest: Verifies that the Force Stop Apps button operates as a standalone
 * element with container-scoped whitelist configuration, on-demand synchronization when an App Tracker
 * page exists in the same container, and zero cross-container leakage.
 */
class ForceStopAppsStandaloneTest {

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
    fun testForceStopWhitelistStandalonePersistenceAndIsolation() {
        val container1 = "container_alpha"
        val container2 = "container_beta"

        val whitelist1 = setOf("com.example.app1", "com.example.app2")
        val whitelist2 = setOf("com.example.app3", "com.example.app4")

        // Save independently
        AppTrackerHelper.saveForceStopWhitelist(fakeContext, container1, whitelist1)
        AppTrackerHelper.saveForceStopWhitelist(fakeContext, container2, whitelist2)

        // Read independently
        val read1 = AppTrackerHelper.getForceStopWhitelist(fakeContext, container1)
        val read2 = AppTrackerHelper.getForceStopWhitelist(fakeContext, container2)

        assertEquals(whitelist1, read1)
        assertEquals(whitelist2, read2)
        assertNotEquals(read1, read2)
    }

    @Test
    fun testOnDemandSyncWhenAppTrackerPageInSameContainer() {
        val containerId = "container_with_tracker"

        // Configure PageManager to have an app_tracker page in containerId
        val trackerPage = SidebarPage(pageId = "page_tracker", pageType = "app_tracker", title = "Tracker")
        val pageManager = PageManager.getInstance(fakeContext)
        pageManager.savePageStack(containerId, listOf(trackerPage))

        assertTrue(AppTrackerHelper.hasAppTrackerInContainer(fakeContext, containerId))

        // Saving via Force Stop should sync to tracker on-demand
        val forceStopSet = setOf("com.test.app1", "com.test.app2")
        AppTrackerHelper.saveForceStopWhitelist(fakeContext, containerId, forceStopSet)

        val trackerWhitelist = AppTrackerHelper.getWhitelist(fakeContext, containerId)
        assertEquals(forceStopSet, trackerWhitelist)

        // Updating via Tracker should sync back to Force Stop on-demand
        val updatedSet = setOf("com.test.app1", "com.test.app3")
        AppTrackerHelper.saveWhitelist(fakeContext, containerId, updatedSet)

        val forceStopWhitelist = AppTrackerHelper.getForceStopWhitelist(fakeContext, containerId)
        assertEquals(updatedSet, forceStopWhitelist)
    }

    @Test
    fun testNoSyncWhenAppTrackerPageNotInSameContainer() {
        val containerWithoutTracker = "container_standalone"
        val otherContainerWithTracker = "container_tracker_isolated"

        // Configure otherContainerWithTracker with app_tracker
        val pageManager = PageManager.getInstance(fakeContext)
        pageManager.savePageStack(otherContainerWithTracker, listOf(
            SidebarPage(pageId = "page_other", pageType = "app_tracker", title = "Other Tracker")
        ))
        // containerWithoutTracker only has hybrid_grid
        pageManager.savePageStack(containerWithoutTracker, listOf(
            SidebarPage(pageId = "page_grid", pageType = "hybrid_grid", title = "Grid")
        ))

        assertFalse(AppTrackerHelper.hasAppTrackerInContainer(fakeContext, containerWithoutTracker))
        assertTrue(AppTrackerHelper.hasAppTrackerInContainer(fakeContext, otherContainerWithTracker))

        // Save to containerWithoutTracker
        val standaloneSet = setOf("com.standalone.music", "com.standalone.chat")
        AppTrackerHelper.saveForceStopWhitelist(fakeContext, containerWithoutTracker, standaloneSet)

        // otherContainerWithTracker should NOT be affected
        val otherWhitelist = AppTrackerHelper.getWhitelist(fakeContext, otherContainerWithTracker)
        assertTrue(otherWhitelist.isEmpty())

        // And containerWithoutTracker tracker key should remain empty
        val trackerKey = AppTrackerHelper.getContainerWhitelistKey(containerWithoutTracker)
        assertNull(fakePrefs.getStringSet(trackerKey, null))
    }

    @Test
    fun testForceStopSystemActionIdentification() {
        val forceStopAction = SidebarItem.SystemAction(
            action = "force_stop_running_apps",
            label = "Force Stop Apps",
            iconResId = android.R.drawable.ic_menu_close_clear_cancel
        )
        val regularAction = SidebarItem.SystemAction(
            action = "screen_record",
            label = "Screen Record",
            iconResId = android.R.drawable.ic_media_pause
        )

        val isForceStop1 = forceStopAction.action == "force_stop_running_apps"
        val isForceStop2 = regularAction.action == "force_stop_running_apps"

        assertTrue(isForceStop1)
        assertFalse(isForceStop2)
    }
}
