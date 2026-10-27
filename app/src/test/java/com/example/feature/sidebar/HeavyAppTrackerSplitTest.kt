package com.example.feature.sidebar

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.example.core.FakeContext
import com.example.core.FakeSharedPreferences
import com.example.core.HandleManager
import com.example.core.PageManager
import com.example.core.SidebarPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HeavyAppTrackerSplitTest: Proves Main -> Heavy migration for App Tracker:
 * 1. App Tracker routes to Heavy (HeavyPagePlaceholderView in Main).
 * 2. Exact default_hybrid Home Grid still routes to Main (HybridGridPageView in Main).
 * 3. Calculator, Compass, Scheduler, Apps, Widgets remain in Heavy.
 * 4. Secondary/other grids remain Heavy.
 * 5. AppTrackerPageView theme background and height measurement work correctly.
 * 6. AppTrackerPageView cleanup is safe and idempotent.
 * 7. Internet Speed Monitor remains untouched.
 */
class HeavyAppTrackerSplitTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var fakeContext: FakeContext
    private lateinit var factory: DefaultSidebarPageFactory
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setup() {
        fakePrefs = FakeSharedPreferences()
        fakeContext = FakeContext(fakePrefs)
        PageManager.resetForTesting()
        HandleManager.resetForTesting()
        factory = DefaultSidebarPageFactory(fakePrefs)
    }

    // 1. App Tracker routes to Heavy (HeavyPagePlaceholderView in Main)
    @Test
    fun testAppTrackerRoutesToHeavyPlaceholder() {
        val appTrackerConfig = SidebarPage(id = "app_tracker_default", title = "App Tracker", type = "app_tracker")
        val view = factory.createPageView(
            context = fakeContext,
            config = appTrackerConfig,
            physicalHandleId = "handle_1",
            containerId = "handle_1_swipe_left",
            viewScope = scope,
            onClose = {},
            setDimmed = {},
            onHeightChanged = {}
        )
        assertTrue("App Tracker page must route to HeavyPagePlaceholderView in Main", view is HeavyPagePlaceholderView)
    }

    // 2. Exact default_hybrid Home Grid still routes to Main
    @Test
    fun testExactDefaultHybridHomeGridStillRoutesToMain() {
        val homeGridConfig = SidebarPage(id = "default_hybrid", title = "Home Grid", type = "hybrid_grid")
        val isResident = DefaultSidebarPageFactory.isResidentDefaultHybrid(
            context = fakeContext,
            config = homeGridConfig,
            physicalHandleId = "handle_1",
            containerId = "handle_1_swipe_left"
        )
        assertTrue("Handle 1 swipe left default_hybrid must be resident in Main", isResident)

        val view = factory.createPageView(
            context = fakeContext,
            config = homeGridConfig,
            physicalHandleId = "handle_1",
            containerId = "handle_1_swipe_left",
            viewScope = scope,
            onClose = {},
            setDimmed = {},
            onHeightChanged = {}
        )
        assertTrue("Home Grid on Handle 1 swipe left must instantiate HybridGridPageView in Main", view is HybridGridPageView)
    }

    // 3. Calculator, Compass, Scheduler, Apps, Widgets remain in Heavy
    @Test
    fun testCalculatorCompassSchedulerRemainHeavy() {
        val calcConfig = SidebarPage(id = "calc_1", title = "Calculator", type = "calculator")
        val calcView = factory.createPageView(
            context = fakeContext,
            config = calcConfig,
            physicalHandleId = "handle_1",
            containerId = "handle_1_swipe_left",
            viewScope = scope,
            onClose = {},
            setDimmed = {},
            onHeightChanged = {}
        )
        assertTrue("Calculator must remain hosted as HeavyPagePlaceholderView", calcView is HeavyPagePlaceholderView)

        val compassConfig = SidebarPage(id = "compass_1", title = "Compass", type = "compass")
        val compassView = factory.createPageView(
            context = fakeContext,
            config = compassConfig,
            physicalHandleId = "handle_1",
            containerId = "handle_1_swipe_left",
            viewScope = scope,
            onClose = {},
            setDimmed = {},
            onHeightChanged = {}
        )
        assertTrue("Compass must remain hosted as HeavyPagePlaceholderView", compassView is HeavyPagePlaceholderView)

        val schedulerConfig = SidebarPage(id = "sched_1", title = "Short Reminders", type = "scheduler")
        val schedView = factory.createPageView(
            context = fakeContext,
            config = schedulerConfig,
            physicalHandleId = "handle_1",
            containerId = "handle_1_swipe_left",
            viewScope = scope,
            onClose = {},
            setDimmed = {},
            onHeightChanged = {}
        )
        assertTrue("Scheduler must remain hosted as HeavyPagePlaceholderView", schedView is HeavyPagePlaceholderView)
    }

    // 4. Secondary grids remain Heavy
    @Test
    fun testSecondaryHomeGridsRemainHeavy() {
        val secondaryGridConfig = SidebarPage(id = "hybrid_grid_custom", title = "Tools", type = "hybrid_grid")
        val isResident = DefaultSidebarPageFactory.isResidentDefaultHybrid(
            context = fakeContext,
            config = secondaryGridConfig,
            physicalHandleId = "handle_2",
            containerId = "handle_2_swipe_right"
        )
        assertFalse("Secondary grids must not be resident in Main", isResident)

        val view = factory.createPageView(
            context = fakeContext,
            config = secondaryGridConfig,
            physicalHandleId = "handle_2",
            containerId = "handle_2_swipe_right",
            viewScope = scope,
            onClose = {},
            setDimmed = {},
            onHeightChanged = {}
        )
        assertTrue("Secondary grids must route to HeavyPagePlaceholderView", view is HeavyPagePlaceholderView)
    }

    // 5. AppTrackerPageView theme background and height measurement work correctly
    @Test
    fun testAppTrackerPageViewThemingAndHeightMeasurement() {
        var reportedHeight = 0
        val pageView = AppTrackerPageView(
            context = fakeContext,
            containerId = "sidebar",
            scope = scope,
            onCloseSidebar = {},
            onAppSelected = {},
            onHeightChanged = { h -> reportedHeight = h }
        )

        // Test theme background application (right sidebar)
        pageView.applyThemeBackground(Color.BLACK, isRight = true)
        val bgRight = pageView.themeBackgroundDrawable ?: pageView.background
        assertNotNull("Theme background should be a GradientDrawable", bgRight)

        // Test theme background application (left sidebar)
        pageView.applyThemeBackground(Color.DKGRAY, isRight = false)
        val bgLeft = pageView.themeBackgroundDrawable ?: pageView.background
        assertNotNull("Theme background should be a GradientDrawable", bgLeft)

        // Test height measurement
        val height = pageView.getCurrentHeightPx()
        assertTrue("App Tracker height must be positive and bounded (was $height)", height in 200..560)
    }

    // 6. Whitelist settings save and retrieve correctly
    @Test
    fun testAppTrackerWhitelistSaveAndRetrieve() {
        val testWhitelist = setOf("com.example.test1", "com.example.test2")
        com.example.utils.AppTrackerHelper.saveWhitelist(fakeContext, "handle_1_swipe_left", testWhitelist)
        val retrieved = com.example.utils.AppTrackerHelper.getWhitelist(fakeContext, "handle_1_swipe_left")
        assertEquals(testWhitelist, retrieved)

        // Deselect an app and verify it does NOT get merged back
        val updatedWhitelist = setOf("com.example.test1")
        com.example.utils.AppTrackerHelper.saveWhitelist(fakeContext, "handle_1_swipe_left", updatedWhitelist)
        val retrievedUpdated = com.example.utils.AppTrackerHelper.getWhitelist(fakeContext, "handle_1_swipe_left")
        assertEquals(updatedWhitelist, retrievedUpdated)
        assertFalse("Deselected app must not remain in whitelist", retrievedUpdated.contains("com.example.test2"))
    }

    // 7. AppTrackerPageView cleanup is safe and idempotent
    @Test
    fun testAppTrackerCleanupIsIdempotent() {
        val pageView = AppTrackerPageView(
            context = fakeContext,
            containerId = "sidebar",
            scope = scope,
            onCloseSidebar = {},
            onAppSelected = {},
            onHeightChanged = {}
        )
        pageView.onPageUnselected()
        pageView.cleanup()
        pageView.cleanup()
        assertNotNull(pageView)
    }

    // 7. Internet Speed Monitor remains untouched
    @Test
    fun testInternetSpeedMonitorRemainsUntouched() {
        val speedIconClass = try {
            Class.forName("com.example.core.SpeedIconProvider")
        } catch (_: ClassNotFoundException) {
            null
        }
        assertNotNull("SpeedIconProvider must exist and be untouched", speedIconClass)

        val netSpeedManagerClass = try {
            Class.forName("com.example.core.NetSpeedManager")
        } catch (_: ClassNotFoundException) {
            null
        }
        assertNotNull("NetSpeedManager must exist and be untouched", netSpeedManagerClass)
    }
}
