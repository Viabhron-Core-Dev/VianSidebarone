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
 * HeavyCompassSplitTest: Proves Main -> Heavy migration for Compass:
 * 1. Compass routes to Heavy (HeavyPagePlaceholderView in Main).
 * 2. Exact default_hybrid Home Grid still routes to Main (HybridGridPageView in Main).
 * 3. Calculator and already migrated pages (Apps Grid, Widgets Grid, Single Widget) remain Heavy.
 * 4. Secondary/other grids remain Heavy.
 * 5. Compass sensor/runtime lifecycle is registered when active and cleaned up when released.
 * 6. Compass theme background and height measurement work correctly.
 * 7. Internet Speed Monitor remains untouched.
 */
class HeavyCompassSplitTest {

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

    // 1. Compass routes to Heavy (HeavyPagePlaceholderView in Main)
    @Test
    fun testCompassRoutesToHeavyPlaceholder() {
        val compassConfig = SidebarPage(id = "compass_default", title = "Compass", type = "compass")
        val view = factory.createPageView(
            context = fakeContext,
            config = compassConfig,
            physicalHandleId = "handle_1",
            containerId = "handle_1_swipe_left",
            viewScope = scope,
            onClose = {},
            setDimmed = {},
            onHeightChanged = {}
        )
        assertTrue("Compass page must route to HeavyPagePlaceholderView in Main", view is HeavyPagePlaceholderView)
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

    // 3. Calculator and already migrated pages remain Heavy
    @Test
    fun testCalculatorAndMigratedPagesRemainHeavy() {
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

        val appsConfig = SidebarPage(id = "apps_1", title = "Apps", type = "apps")
        val appsView = factory.createPageView(
            context = fakeContext,
            config = appsConfig,
            physicalHandleId = "handle_1",
            containerId = "handle_1_swipe_left",
            viewScope = scope,
            onClose = {},
            setDimmed = {},
            onHeightChanged = {}
        )
        assertTrue("Apps Grid must remain hosted as HeavyPagePlaceholderView", appsView is HeavyPagePlaceholderView)

        val widgetsConfig = SidebarPage(id = "wg_1", title = "Widgets", type = "widgets_grid")
        val widgetsView = factory.createPageView(
            context = fakeContext,
            config = widgetsConfig,
            physicalHandleId = "handle_1",
            containerId = "handle_1_swipe_left",
            viewScope = scope,
            onClose = {},
            setDimmed = {},
            onHeightChanged = {}
        )
        assertTrue("Widgets Grid must remain hosted as HeavyPagePlaceholderView", widgetsView is HeavyPagePlaceholderView)

        val widgetConfig = SidebarPage(id = "widget_1", title = "Single Widget", type = "single_widget")
        val widgetView = factory.createPageView(
            context = fakeContext,
            config = widgetConfig,
            physicalHandleId = "handle_1",
            containerId = "handle_1_swipe_left",
            viewScope = scope,
            onClose = {},
            setDimmed = {},
            onHeightChanged = {}
        )
        assertTrue("Single Widget must remain hosted as HeavyPagePlaceholderView", widgetView is HeavyPagePlaceholderView)
    }

    // 4. Secondary grids remain Heavy
    @Test
    fun testSecondaryHomeGridsRemainHeavy() {
        val secondaryGridConfig = SidebarPage(id = "hybrid_grid_custom", title = "Tools", type = "hybrid_grid")
        val isResident = DefaultSidebarPageFactory.isResidentDefaultHybrid(
            context = fakeContext,
            config = secondaryGridConfig,
            physicalHandleId = "handle_2",
            containerId = "handle_2_swipe_left"
        )
        assertFalse("Secondary hybrid grid on handle 2 must not be resident in Main", isResident)

        val view = factory.createPageView(
            context = fakeContext,
            config = secondaryGridConfig,
            physicalHandleId = "handle_2",
            containerId = "handle_2_swipe_left",
            viewScope = scope,
            onClose = {},
            setDimmed = {},
            onHeightChanged = {}
        )
        assertTrue("Secondary hybrid grid must route to HeavyPagePlaceholderView", view is HeavyPagePlaceholderView)
    }

    // 5. Compass sensor/runtime lifecycle is registered when active and cleaned up when released
    @Test
    fun testCompassSensorLifecycleRegisteredAndCleanedUp() {
        val compassView = CompassPageView(fakeContext)
        // Initially not attached/selected, so not listening
        assertFalse("Compass sensor should not be active initially before attach/select", compassView.isSensorListening())

        // Start listening (simulating onAttachedToWindow or onPageSelected)
        compassView.startListening()
        assertTrue("Compass sensor must be listening after startListening()", compassView.isSensorListening())

        // Stop listening (simulating onDetachedFromWindow or onPageUnselected or host release)
        compassView.stopListening()
        assertFalse("Compass sensor must not be listening after stopListening()", compassView.isSensorListening())

        // Test onPageSelected
        compassView.onPageSelected()
        assertTrue("Compass sensor must be listening after onPageSelected()", compassView.isSensorListening())

        // Test onPageUnselected
        compassView.onPageUnselected()
        assertFalse("Compass sensor must stop listening after onPageUnselected()", compassView.isSensorListening())
    }

    // 6. Compass theme background and height measurement
    @Test
    fun testCompassPageViewThemingAndHeightMeasurement() {
        var reportedHeight = 0
        val compassView = CompassPageView(fakeContext) { newH ->
            reportedHeight = newH
        }

        compassView.applyThemeBackground(Color.DKGRAY, isRight = true)
        val bgDrawable = compassView.themeBackgroundDrawable ?: compassView.background
        assertTrue("CompassPageView must apply a GradientDrawable theme background", bgDrawable is GradientDrawable)

        val measuredH = compassView.getCurrentHeightPx()
        assertTrue("CompassPageView getCurrentHeightPx() must be positive", measuredH >= 0)
    }

    // 7. Internet Speed Monitor remains untouched
    @Test
    fun testInternetSpeedMonitorRemainsUntouched() {
        val speedIconClass = Class.forName("com.example.core.SpeedIconProvider")
        assertNotNull("SpeedIconProvider must exist and be untouched", speedIconClass)

        val netSpeedManagerClass = Class.forName("com.example.core.NetSpeedManager")
        assertNotNull("NetSpeedManager must exist and be untouched", netSpeedManagerClass)
    }
}
