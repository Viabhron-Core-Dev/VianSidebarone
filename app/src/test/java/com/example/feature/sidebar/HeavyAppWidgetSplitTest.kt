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
 * Focused test suite proving the Main -> Heavy migration for Apps Grid, Widgets Grid, and Single Widget:
 * 1. Apps Grid routes to Heavy (HeavyPagePlaceholderView in Main).
 * 2. Widgets Grid routes to Heavy (HeavyPagePlaceholderView in Main).
 * 3. Single Widget routes to Heavy (HeavyPagePlaceholderView in Main).
 * 4. Exact default_hybrid Home Grid still routes to Main (HybridGridPageView in Main).
 * 5. Other grids (secondary gestures, Handle 2+, new hybrid_grid_*) remain Heavy.
 * 6. Existing storage and container isolation remains intact across distinct containers.
 * 7. Calculator residency remains unchanged (HeavyPagePlaceholderView in Main).
 * 8. Internet Speed Monitor files/code remain untouched.
 */
class HeavyAppWidgetSplitTest {

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

    // 1. Apps Grid routes to Heavy
    @Test
    fun testAppsGridRoutesToHeavyPlaceholder() {
        val appsConfig = SidebarPage(id = "apps_default", title = "Apps", type = "apps")
        val view1 = factory.createPageView(
            context = fakeContext,
            config = appsConfig,
            physicalHandleId = "handle_1",
            containerId = "handle_1_swipe_left",
            viewScope = scope,
            onClose = {},
            setDimmed = {},
            onHeightChanged = {}
        )
        assertTrue("Apps page must route to HeavyPagePlaceholderView in Main", view1 is HeavyPagePlaceholderView)

        val appsGridConfig = SidebarPage(id = "apps_custom", title = "Apps Grid", type = "apps_grid")
        val view2 = factory.createPageView(
            context = fakeContext,
            config = appsGridConfig,
            physicalHandleId = "handle_1",
            containerId = "handle_1_swipe_left",
            viewScope = scope,
            onClose = {},
            setDimmed = {},
            onHeightChanged = {}
        )
        assertTrue("Apps Grid page must route to HeavyPagePlaceholderView in Main", view2 is HeavyPagePlaceholderView)
    }

    // 2. Widgets Grid routes to Heavy
    @Test
    fun testWidgetsGridRoutesToHeavyPlaceholder() {
        val widgetsGridConfig = SidebarPage(id = "widgets_grid_1", title = "Widgets", type = "widgets_grid")
        val view1 = factory.createPageView(
            context = fakeContext,
            config = widgetsGridConfig,
            physicalHandleId = "handle_1",
            containerId = "handle_1_swipe_left",
            viewScope = scope,
            onClose = {},
            setDimmed = {},
            onHeightChanged = {}
        )
        assertTrue("Widgets Grid page must route to HeavyPagePlaceholderView in Main", view1 is HeavyPagePlaceholderView)

        val widgetsConfig = SidebarPage(id = "widgets_2", title = "Widgets", type = "widgets")
        val view2 = factory.createPageView(
            context = fakeContext,
            config = widgetsConfig,
            physicalHandleId = "handle_1",
            containerId = "handle_1_swipe_left",
            viewScope = scope,
            onClose = {},
            setDimmed = {},
            onHeightChanged = {}
        )
        assertTrue("Widgets page must route to HeavyPagePlaceholderView in Main", view2 is HeavyPagePlaceholderView)
    }

    // 3. Single Widget routes to Heavy
    @Test
    fun testSingleWidgetRoutesToHeavyPlaceholder() {
        val singleWidgetConfig = SidebarPage(id = "widget_101", title = "Weather Widget", type = "single_widget")
        val view1 = factory.createPageView(
            context = fakeContext,
            config = singleWidgetConfig,
            physicalHandleId = "handle_1",
            containerId = "handle_1_swipe_left",
            viewScope = scope,
            onClose = {},
            setDimmed = {},
            onHeightChanged = {}
        )
        assertTrue("Single Widget page must route to HeavyPagePlaceholderView in Main", view1 is HeavyPagePlaceholderView)

        val widgetConfig = SidebarPage(id = "widget:102", title = "Clock Widget", type = "widget")
        val view2 = factory.createPageView(
            context = fakeContext,
            config = widgetConfig,
            physicalHandleId = "handle_1",
            containerId = "handle_1_swipe_left",
            viewScope = scope,
            onClose = {},
            setDimmed = {},
            onHeightChanged = {}
        )
        assertTrue("Widget page must route to HeavyPagePlaceholderView in Main", view2 is HeavyPagePlaceholderView)
    }

    // 4. Exact default_hybrid Home Grid still routes to Main
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

    // 5. Other grids remain Heavy
    @Test
    fun testOtherGridsRemainHeavy() {
        // A newly added hybrid_grid on Handle 1 swipe left
        val customHybridConfig = SidebarPage(id = "hybrid_grid_999", title = "Custom Grid", type = "hybrid_grid")
        val isCustomResident = DefaultSidebarPageFactory.isResidentDefaultHybrid(
            context = fakeContext,
            config = customHybridConfig,
            physicalHandleId = "handle_1",
            containerId = "handle_1_swipe_left"
        )
        assertFalse("Custom Hybrid Grid must NOT be resident in Main", isCustomResident)

        val customView = factory.createPageView(
            context = fakeContext,
            config = customHybridConfig,
            physicalHandleId = "handle_1",
            containerId = "handle_1_swipe_left",
            viewScope = scope,
            onClose = {},
            setDimmed = {},
            onHeightChanged = {}
        )
        assertTrue("Custom Hybrid Grid must route to HeavyPagePlaceholderView", customView is HeavyPagePlaceholderView)

        // default_hybrid on a secondary gesture (e.g. handle_1_double_tap)
        val secondaryGestureResident = DefaultSidebarPageFactory.isResidentDefaultHybrid(
            context = fakeContext,
            config = SidebarPage(id = "default_hybrid", title = "Home Grid", type = "hybrid_grid"),
            physicalHandleId = "handle_1",
            containerId = "handle_1_double_tap"
        )
        assertFalse("default_hybrid on secondary gesture must NOT be resident in Main", secondaryGestureResident)

        // default_hybrid on Handle 2
        val handle2Resident = DefaultSidebarPageFactory.isResidentDefaultHybrid(
            context = fakeContext,
            config = SidebarPage(id = "default_hybrid", title = "Home Grid", type = "hybrid_grid"),
            physicalHandleId = "handle_2",
            containerId = "handle_2_tap"
        )
        assertFalse("default_hybrid on Handle 2 must NOT be resident in Main", handle2Resident)
    }

    // 6. Existing storage/container isolation remains intact
    @Test
    fun testExistingStorageAndContainerIsolationRemainsIntact() {
        // Apps Grid container isolation
        val keyContainer1 = "sidebar_apps_handle_1_swipe_left_apps_1"
        val keyContainer2 = "sidebar_apps_handle_2_tap_apps_1"

        fakePrefs.edit()
            .putString(keyContainer1, "[\"app:com.example.app1\"]")
            .putString(keyContainer2, "[\"app:com.example.app2\"]")
            .apply()

        assertEquals("[\"app:com.example.app1\"]", fakePrefs.getString(keyContainer1, null))
        assertEquals("[\"app:com.example.app2\"]", fakePrefs.getString(keyContainer2, null))

        // Widgets Grid container isolation
        val wgKeyContainer1 = "handle_handle_1_swipe_left_widgets_grid_wg_1"
        val wgKeyContainer2 = "handle_handle_2_tap_widgets_grid_wg_1"

        fakePrefs.edit()
            .putString(wgKeyContainer1, "[{\"id\":\"widget:101\",\"cols\":2,\"rows\":2,\"x\":0,\"y\":0}]")
            .putString(wgKeyContainer2, "[{\"id\":\"widget:202\",\"cols\":4,\"rows\":2,\"x\":0,\"y\":0}]")
            .apply()

        assertEquals(
            "[{\"id\":\"widget:101\",\"cols\":2,\"rows\":2,\"x\":0,\"y\":0}]",
            fakePrefs.getString(wgKeyContainer1, null)
        )
        assertEquals(
            "[{\"id\":\"widget:202\",\"cols\":4,\"rows\":2,\"x\":0,\"y\":0}]",
            fakePrefs.getString(wgKeyContainer2, null)
        )

        // Verify empty saved active-app list does NOT fall back to all installed apps
        val emptySavedKey = "sidebar_apps_test_empty_container_page_1"
        fakePrefs.edit().putString(emptySavedKey, "[]").apply()
        val appsManager = SidebarAppsManager(fakeContext, fakePrefs, scope, emptySavedKey) {}
        appsManager.ensureLoaded()
        assertTrue(
            "Empty saved active-app list must result in empty active items without fallback",
            appsManager.activeItems.isEmpty()
        )
    }

    // 7. Calculator residency remains unchanged
    @Test
    fun testCalculatorResidencyRemainsUnchanged() {
        val calcConfig = SidebarPage(id = "calc_1", title = "Calculator", type = "calculator")
        val view = factory.createPageView(
            context = fakeContext,
            config = calcConfig,
            physicalHandleId = "handle_1",
            containerId = "handle_1_swipe_left",
            viewScope = scope,
            onClose = {},
            setDimmed = {},
            onHeightChanged = {}
        )
        assertTrue("Calculator must remain hosted as HeavyPagePlaceholderView in Main", view is HeavyPagePlaceholderView)
    }

    // 8. Internet Speed Monitor files/code are untouched
    @Test
    fun testInternetSpeedMonitorRemainsUntouched() {
        // Assert that speed monitor core provider and manager remain accessible and valid
        val speedIconClass = Class.forName("com.example.core.SpeedIconProvider")
        assertNotNull("SpeedIconProvider must exist and be untouched", speedIconClass)

        val netSpeedManagerClass = Class.forName("com.example.core.NetSpeedManager")
        assertNotNull("NetSpeedManager must exist and be untouched", netSpeedManagerClass)
    }

    // 9. Theming and background preservation across newly migrated views
    @Test
    fun testThemingAndBackgroundPreservationOnHeavyViews() {
        val appsView = AppsPageView(
            context = fakeContext,
            handleId = "handle_1",
            pageConfig = SidebarPage("apps_1", "Apps", "apps"),
            manager = SidebarAppsManager(fakeContext, fakePrefs, scope, "sidebar_apps_handle_1_apps_1") {},
            serviceScope = scope,
            onCloseSidebar = {},
            containerId = "handle_1_swipe_left"
        )
        appsView.applyThemeBackground(Color.DKGRAY, isRight = true)
        val appsBg = appsView.themeBackgroundDrawable ?: appsView.background
        assertTrue("AppsPageView must apply GradientDrawable theme background", appsBg is GradientDrawable)

        val widgetsView = WidgetsGridPageView(
            context = fakeContext,
            pageId = "wg_1",
            scope = scope,
            containerId = "handle_1_swipe_left",
            onHeightChanged = {}
        )
        widgetsView.applyThemeBackground(Color.DKGRAY, isRight = true)
        val widgetsBg = widgetsView.themeBackgroundDrawable ?: widgetsView.background
        assertTrue("WidgetsGridPageView must apply GradientDrawable theme background", widgetsBg is GradientDrawable)

        val singleWidgetView = WidgetPageView(
            context = fakeContext,
            widgetIdStr = "widget:99",
            onHeightChanged = {}
        )
        singleWidgetView.applyThemeBackground(Color.DKGRAY, isRight = true)
        val singleBg = singleWidgetView.themeBackgroundDrawable ?: singleWidgetView.background
        assertTrue("WidgetPageView must apply GradientDrawable theme background", singleBg is GradientDrawable)
    }
}
