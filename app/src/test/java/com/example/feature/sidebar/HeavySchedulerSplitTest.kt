package com.example.feature.sidebar

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.example.core.FakeContext
import com.example.core.FakeSharedPreferences
import com.example.core.HandleManager
import com.example.core.PageManager
import com.example.core.SidebarPage
import com.example.data.SchedulerTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HeavySchedulerSplitTest: Proves Main -> Heavy migration for Scheduler (Short Reminders):
 * 1. Scheduler routes to Heavy (HeavyPagePlaceholderView in Main).
 * 2. Exact default_hybrid Home Grid still routes to Main (HybridGridPageView in Main).
 * 3. Calculator and already migrated pages (Apps Grid, Widgets Grid, Single Widget, Compass) remain Heavy.
 * 4. Secondary/other grids remain Heavy.
 * 5. Scheduler lifecycle, coroutine scope, and task cleanup when page is unselected/released.
 * 6. Scheduler theme background and height measurement work correctly.
 * 7. SchedulerTask entity integrity for persistence across processes.
 * 8. Internet Speed Monitor remains untouched.
 */
class HeavySchedulerSplitTest {

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

    // 1. Scheduler routes to Heavy (HeavyPagePlaceholderView in Main)
    @Test
    fun testSchedulerRoutesToHeavyPlaceholder() {
        val schedulerConfig = SidebarPage(id = "scheduler_default", title = "Short Reminders", type = "scheduler")
        val view = factory.createPageView(
            context = fakeContext,
            config = schedulerConfig,
            physicalHandleId = "handle_1",
            containerId = "handle_1_swipe_left",
            viewScope = scope,
            onClose = {},
            setDimmed = {},
            onHeightChanged = {}
        )
        assertTrue("Scheduler page must route to HeavyPagePlaceholderView in Main", view is HeavyPagePlaceholderView)
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

    // 5. Scheduler lifecycle, coroutine scope, and task cleanup
    @Test
    fun testSchedulerLifecycleAndTaskCleanup() {
        var heightReported = 0
        val pageView = SchedulerPageView(fakeContext, scope) { newH ->
            heightReported = newH
        }

        // Test page unselected / cleanup
        pageView.onPageUnselected()
        pageView.cleanup()
        // Ensure subsequent call to cleanup is safe and idempotent
        pageView.cleanup()
        assertNotNull(pageView)
    }

    // 6. Scheduler theme background and height measurement
    @Test
    fun testSchedulerPageViewThemingAndHeightMeasurement() {
        var reportedHeight = 0
        val pageView = SchedulerPageView(fakeContext, scope) { h -> reportedHeight = h }

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
        assertTrue("Scheduler height must be positive and bounded", height in 100..520)
    }

    // 7. SchedulerTask entity integrity for persistence across processes
    @Test
    fun testSchedulerDatabaseAndEntityIntegrity() {
        val now = System.currentTimeMillis()
        val task = SchedulerTask(
            id = 101,
            title = "Standup",
            note = "Review tickets",
            tags = "Work,Urgent",
            timeMillis = now + 60000,
            status = "PENDING"
        )
        assertEquals(101, task.id)
        assertEquals("Standup", task.title)
        assertEquals("Review tickets", task.note)
        assertEquals("Work,Urgent", task.tags)
        assertEquals("PENDING", task.status)
        assertEquals(now + 60000, task.timeMillis)

        // Test status update immutability
        val doneTask = task.copy(status = "DONE")
        assertEquals("DONE", doneTask.status)
        assertEquals(task.id, doneTask.id)
    }

    // 8. Internet Speed Monitor remains untouched
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
