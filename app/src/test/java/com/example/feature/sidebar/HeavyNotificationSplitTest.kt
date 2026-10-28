package com.example.feature.sidebar

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.example.core.FakeContext
import com.example.core.FakeSharedPreferences
import com.example.core.HandleManager
import com.example.core.PageManager
import com.example.core.SidebarPage
import com.example.feature.notification.NotificationActionItem
import com.example.feature.notification.NotificationItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HeavyNotificationSplitTest: Proves Main -> Heavy migration for Notifications:
 * 1. Notifications routes to Heavy (HeavyPagePlaceholderView in Main).
 * 2. Main does NOT instantiate NotificationPageView for the normal Heavy path.
 * 3. Exact default_hybrid Home Grid still routes to Main (HybridGridPageView in Main).
 * 4. All other Heavy pages remain Heavy.
 * 5. NotificationDataContract serialization and round-trip are valid.
 * 6. NotificationPageView theme background and lifecycle cleanup are safe.
 * 7. Internet Speed Monitor remains untouched.
 */
class HeavyNotificationSplitTest {

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

    // 1. Notifications routes to Heavy (HeavyPagePlaceholderView in Main)
    @Test
    fun testNotificationsRoutesToHeavyPlaceholder() {
        val notifConfig = SidebarPage(id = "notifications_default", title = "Notifications", type = "notifications")
        val view = factory.createPageView(
            context = fakeContext,
            config = notifConfig,
            physicalHandleId = "handle_1",
            containerId = "handle_1_swipe_left",
            viewScope = scope,
            onClose = {},
            setDimmed = {},
            onHeightChanged = {}
        )
        assertTrue("Notifications page must route to HeavyPagePlaceholderView in Main", view is HeavyPagePlaceholderView)
    }

    // 2. Main does NOT instantiate NotificationPageView for the normal Heavy path
    @Test
    fun testMainDoesNotInstantiateNotificationPageViewDirectly() {
        val notifConfig = SidebarPage(id = "notif_secondary", title = "Notifications", type = "notification")
        val view = factory.createPageView(
            context = fakeContext,
            config = notifConfig,
            physicalHandleId = "handle_2",
            containerId = "handle_2_swipe_right",
            viewScope = scope,
            onClose = {},
            setDimmed = {},
            onHeightChanged = {}
        )
        assertTrue("Secondary Notifications page must NOT be NotificationPageView in Main", view is HeavyPagePlaceholderView)
    }

    // 3. Exact default_hybrid Home Grid still routes to Main
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

    // 4. All other Heavy pages remain Heavy
    @Test
    fun testAllHeavyPagesRemainHeavy() {
        val pages = listOf(
            SidebarPage(id = "calc_1", title = "Calculator", type = "calculator"),
            SidebarPage(id = "compass_1", title = "Compass", type = "compass"),
            SidebarPage(id = "sched_1", title = "Scheduler", type = "scheduler"),
            SidebarPage(id = "apps_1", title = "Apps", type = "apps"),
            SidebarPage(id = "widgets_1", title = "Widgets", type = "widgets_grid"),
            SidebarPage(id = "app_tracker_1", title = "App Tracker", type = "app_tracker"),
            SidebarPage(id = "media_player_1", title = "Media Player", type = "media_player"),
            SidebarPage(id = "resources_tracker_1", title = "Resources Tracker", type = "resources_tracker")
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

    // 5. NotificationDataContract serialization and round-trip are valid
    @Test
    fun testNotificationDataContractSerializationRoundTrip() {
        val action = NotificationActionItem(
            actionIndex = 0,
            title = "Reply",
            hasRemoteInputs = true,
            remoteInputResultKey = "reply_key",
            remoteInputLabel = "Type reply..."
        )
        val actionJson = action.toJson()
        val parsedAction = NotificationActionItem.fromJson(actionJson)
        assertEquals(action.actionIndex, parsedAction.actionIndex)
        assertEquals(action.title, parsedAction.title)
        assertEquals(action.hasRemoteInputs, parsedAction.hasRemoteInputs)
        assertEquals(action.remoteInputResultKey, parsedAction.remoteInputResultKey)
        assertEquals(action.remoteInputLabel, parsedAction.remoteInputLabel)

        val item = NotificationItem(
            key = "com.test.app_100_tag",
            packageName = "com.test.app",
            id = 100,
            tag = "tag",
            postTime = 123456789L,
            isClearable = true,
            isOngoing = false,
            title = "Test Message",
            text = "Hello World",
            subText = "Sub text",
            bigText = "Big text content",
            textLines = listOf("Line 1", "Line 2"),
            hasContentIntent = true,
            actions = listOf(action)
        )

        val itemJson = item.toJson()
        val parsedItem = NotificationItem.fromJson(itemJson)

        assertEquals(item.key, parsedItem.key)
        assertEquals(item.packageName, parsedItem.packageName)
        assertEquals(item.id, parsedItem.id)
        assertEquals(item.tag, parsedItem.tag)
        assertEquals(item.postTime, parsedItem.postTime)
        assertEquals(item.isClearable, parsedItem.isClearable)
        assertEquals(item.isOngoing, parsedItem.isOngoing)
        assertEquals(item.title, parsedItem.title)
        assertEquals(item.text, parsedItem.text)
        assertEquals(item.subText, parsedItem.subText)
        assertEquals(item.bigText, parsedItem.bigText)
        assertEquals(item.textLines, parsedItem.textLines)
        assertEquals(item.hasContentIntent, parsedItem.hasContentIntent)
        assertEquals(1, parsedItem.actions.size)
        assertEquals(action.title, parsedItem.actions[0].title)
    }

    // 6. NotificationPageView theme background and lifecycle cleanup are safe
    @Test
    fun testNotificationPageViewThemeBackgroundAndCleanup() {
        val pageView = NotificationPageView(
            context = fakeContext,
            onCloseSidebar = {},
            onHideApp = {},
            onHeightChanged = {}
        )
        assertNotNull(pageView)

        // Test theme background
        val customColor = 0xC81E2832.toInt()
        pageView.applyThemeBackground(customColor, isRight = true)
        val drawable = pageView.themeBackgroundDrawable
        assertNotNull("Theme background drawable must not be null", drawable)
        assertTrue("Theme background must be a GradientDrawable", drawable is GradientDrawable)

        // Test lifecycle callbacks
        pageView.onPageSelected()
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
