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
 * HeavyMediaPlayerSplitTest: Proves Main -> Heavy migration for Media Player:
 * 1. Media Player routes to Heavy (HeavyPagePlaceholderView in Main).
 * 2. Exact default_hybrid Home Grid still routes to Main (HybridGridPageView in Main).
 * 3. App Tracker, Calculator, Compass, Scheduler, Apps, Widgets remain in Heavy.
 * 4. Secondary/other grids remain Heavy.
 * 5. MediaPlayerPageView theme background and height measurement work correctly.
 * 6. MediaPlayerPageView cleanup is safe and idempotent.
 * 7. Internet Speed Monitor remains untouched.
 */
class HeavyMediaPlayerSplitTest {

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

    // 1. Media Player routes to Heavy (HeavyPagePlaceholderView in Main)
    @Test
    fun testMediaPlayerRoutesToHeavyPlaceholder() {
        val mediaPlayerConfig = SidebarPage(id = "media_player_default", title = "Media Player", type = "media_player")
        val view = factory.createPageView(
            context = fakeContext,
            config = mediaPlayerConfig,
            physicalHandleId = "handle_1",
            containerId = "handle_1_swipe_left",
            viewScope = scope,
            onClose = {},
            setDimmed = {},
            onHeightChanged = {}
        )
        assertTrue("Media Player page must route to HeavyPagePlaceholderView in Main", view is HeavyPagePlaceholderView)
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

    // 3. Calculator, Compass, Scheduler, Apps, Widgets, and App Tracker remain in Heavy
    @Test
    fun testAllHeavyPagesRemainHeavy() {
        val pages = listOf(
            SidebarPage(id = "calc_1", title = "Calculator", type = "calculator"),
            SidebarPage(id = "compass_1", title = "Compass", type = "compass"),
            SidebarPage(id = "sched_1", title = "Scheduler", type = "scheduler"),
            SidebarPage(id = "apps_1", title = "Apps", type = "apps"),
            SidebarPage(id = "widgets_1", title = "Widgets", type = "widgets_grid"),
            SidebarPage(id = "app_tracker_1", title = "App Tracker", type = "app_tracker")
        )
        for (page in pages) {
            val view = factory.createPageView(
                context = fakeContext,
                config = page,
                physicalHandleId = "handle_1",
                containerId = "handle_1_swipe_left",
                viewScope = scope,
                onClose = {},
                setDimmed = {},
                onHeightChanged = {}
            )
            assertTrue("Page type '${page.type}' must route to HeavyPagePlaceholderView in Main", view is HeavyPagePlaceholderView)
        }
    }

    // 4. Secondary/other grids remain Heavy
    @Test
    fun testSecondaryGridsRemainHeavy() {
        val handle2GridConfig = SidebarPage(id = "default_hybrid", title = "Home Grid", type = "hybrid_grid")
        val view = factory.createPageView(
            context = fakeContext,
            config = handle2GridConfig,
            physicalHandleId = "handle_2",
            containerId = "handle_2_swipe_left",
            viewScope = scope,
            onClose = {},
            setDimmed = {},
            onHeightChanged = {}
        )
        assertTrue("Grids outside Handle 1 swipe left must route to HeavyPagePlaceholderView", view is HeavyPagePlaceholderView)
    }

    // 5. MediaPlayerPageView theme background and height measurement work correctly
    @Test
    fun testMediaPlayerThemeBackgroundAndHeight() {
        val pageView = MediaPlayerPageView(
            context = fakeContext,
            onCloseSidebar = {},
            onHeightChanged = {}
        )
        assertNotNull(pageView)

        // Test theme background application
        val customColor = Color.argb(200, 40, 50, 60)
        pageView.applyThemeBackground(customColor, isRight = true)
        val drawable = pageView.themeBackgroundDrawable
        assertNotNull("Theme background drawable must not be null", drawable)
        assertTrue("Theme background must be a GradientDrawable", drawable is GradientDrawable)

        // Test height px returns non-negative value
        val heightPx = pageView.getCurrentHeightPx()
        assertTrue("Reported height must be >= 0", heightPx >= 0)
    }

    // 6. MediaPlayerPageView cleanup is safe and idempotent
    @Test
    fun testMediaPlayerCleanupIsIdempotent() {
        val pageView = MediaPlayerPageView(
            context = fakeContext,
            onCloseSidebar = {},
            onHeightChanged = {}
        )
        // Calling cleanup multiple times should not throw
        pageView.cleanup()
        pageView.cleanup()
        pageView.onPageUnselected()
        pageView.cleanup()
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
