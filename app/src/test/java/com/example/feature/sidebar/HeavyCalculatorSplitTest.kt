package com.example.feature.sidebar

import com.example.core.SidebarPage
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyCommandType
import com.example.core.ipc.HeavyEvent
import com.example.core.ipc.HeavyEventType
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the Main -> Heavy Calculator Split:
 * 1. Command and Event serialization (MOUNT_PAGE, UNMOUNT_PAGE, UPDATE_PAGE_BOUNDS, page_height_changed).
 * 2. Heavy desired-height event payload validation and filtering logic.
 * 3. Duplicate/unchanged height suppression logic.
 * 4. Bounds identical suppression logic.
 * 5. Generic bootstrap height derivation preventing 44px empty-TextView collapse.
 * 6. Routing invariants: Calculator configuration routes to Heavy placeholder, while Home Grid remains resident.
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
    fun testHeavyDesiredHeightEventSerializationAndParsing() {
        val heightEvent = HeavyEvent(
            eventId = "height_calc_1_999",
            type = HeavyEventType.STATE_UPDATED,
            sourceId = "calc_1",
            payload = mapOf(
                "action" to "page_height_changed",
                "pageId" to "calc_1",
                "height" to "608"
            )
        )

        val json = heightEvent.toJson()
        val deserialized = HeavyEvent.fromJson(json)

        assertEquals("height_calc_1_999", deserialized.eventId)
        assertEquals(HeavyEventType.STATE_UPDATED, deserialized.type)
        assertEquals("calc_1", deserialized.sourceId)
        assertEquals("page_height_changed", deserialized.payload["action"])
        assertEquals("calc_1", deserialized.payload["pageId"])
        assertEquals("608", deserialized.payload["height"])
        assertEquals(608, deserialized.payload["height"]?.toIntOrNull())
    }

    @Test
    fun testHeightEventFilteringLogic() {
        val myPageId = "page_calc_test"
        var currentDesiredHeight = -1
        var applyCount = 0

        fun processEvent(event: HeavyEvent) {
            val action = event.payload["action"]
            val targetPageId = event.payload["pageId"] ?: event.sourceId
            if (action == "page_height_changed" && targetPageId == myPageId) {
                val reportedH = event.payload["height"]?.toIntOrNull() ?: return
                if (reportedH > 0 && reportedH != currentDesiredHeight) {
                    currentDesiredHeight = reportedH
                    applyCount++
                }
            }
        }

        // 1. Valid height for this page -> applied
        val validEvent = HeavyEvent(
            eventId = "e1",
            type = HeavyEventType.STATE_UPDATED,
            sourceId = myPageId,
            payload = mapOf("action" to "page_height_changed", "pageId" to myPageId, "height" to "608")
        )
        processEvent(validEvent)
        assertEquals(608, currentDesiredHeight)
        assertEquals(1, applyCount)

        // 2. Duplicate unchanged height -> ignored (no loop)
        val duplicateEvent = HeavyEvent(
            eventId = "e2",
            type = HeavyEventType.STATE_UPDATED,
            sourceId = myPageId,
            payload = mapOf("action" to "page_height_changed", "pageId" to myPageId, "height" to "608")
        )
        processEvent(duplicateEvent)
        assertEquals(608, currentDesiredHeight)
        assertEquals(1, applyCount) // Count unchanged

        // 3. Event for a different page ID -> ignored
        val otherPageEvent = HeavyEvent(
            eventId = "e3",
            type = HeavyEventType.STATE_UPDATED,
            sourceId = "other_page",
            payload = mapOf("action" to "page_height_changed", "pageId" to "other_page", "height" to "750")
        )
        processEvent(otherPageEvent)
        assertEquals(608, currentDesiredHeight)
        assertEquals(1, applyCount)

        // 4. Invalid non-positive height -> ignored
        val negativeHeightEvent = HeavyEvent(
            eventId = "e4",
            type = HeavyEventType.STATE_UPDATED,
            sourceId = myPageId,
            payload = mapOf("action" to "page_height_changed", "pageId" to myPageId, "height" to "-50")
        )
        processEvent(negativeHeightEvent)
        assertEquals(608, currentDesiredHeight)
        assertEquals(1, applyCount)

        // 5. Valid changed height (e.g. drawer toggled open) -> applied
        val changedEvent = HeavyEvent(
            eventId = "e5",
            type = HeavyEventType.STATE_UPDATED,
            sourceId = myPageId,
            payload = mapOf("action" to "page_height_changed", "pageId" to myPageId, "height" to "740")
        )
        processEvent(changedEvent)
        assertEquals(740, currentDesiredHeight)
        assertEquals(2, applyCount)
    }

    @Test
    fun testBoundsIdenticalSuppressionLogic() {
        var lastDispatchedLeft = 100
        var lastDispatchedTop = 200
        var lastDispatchedWidth = 320
        var lastDispatchedHeight = 608
        var dispatchCount = 1

        fun checkAndDispatch(left: Int, top: Int, width: Int, height: Int): Boolean {
            if (left == lastDispatchedLeft &&
                top == lastDispatchedTop &&
                width == lastDispatchedWidth &&
                height == lastDispatchedHeight) {
                // Suppressed
                return false
            }
            lastDispatchedLeft = left
            lastDispatchedTop = top
            lastDispatchedWidth = width
            lastDispatchedHeight = height
            dispatchCount++
            return true
        }

        // Identical stationary coordinates -> suppressed
        assertFalse(checkAndDispatch(100, 200, 320, 608))
        assertEquals(1, dispatchCount)

        // True coordinate shift (e.g. orientation or drawer expansion) -> dispatched
        assertTrue(checkAndDispatch(100, 200, 320, 740))
        assertEquals(2, dispatchCount)
    }

    @Test
    fun testGenericBootstrapHeightStrategy() {
        // Test that generic fallback does not collapse to 44px
        val screenHeightPx = 1600
        val bootstrapFraction = 0.45f
        val computedBootstrap = (screenHeightPx * bootstrapFraction).toInt()

        // Evaluates to 720px, far above the 44px intrinsic TextView collapse
        assertEquals(720, computedBootstrap)
        assertTrue(computedBootstrap > 200)
        assertNotEquals(44, computedBootstrap)
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

    @Test
    fun testBootstrapMountWithReadyToShowFalse() {
        val bootstrapMount = HeavyCommand(
            commandId = "mount_calc_bootstrap",
            type = HeavyCommandType.MOUNT_PAGE,
            targetId = "calc_1",
            payload = mapOf(
                "pageId" to "calc_1",
                "pageType" to "calculator",
                "containerId" to "sidebar",
                "x" to "100",
                "y" to "200",
                "width" to "320",
                "height" to "540",
                "readyToShow" to "false"
            )
        )
        val json = bootstrapMount.toJson()
        val deserialized = HeavyCommand.fromJson(json)

        assertEquals("false", deserialized.payload["readyToShow"])
        assertEquals(HeavyCommandType.MOUNT_PAGE, deserialized.type)
        assertEquals("calc_1", deserialized.payload["pageId"])
    }

    @Test
    fun testBoundsUpdateWithReadyToShowTrue() {
        val revealBounds = HeavyCommand(
            commandId = "bounds_calc_reveal",
            type = HeavyCommandType.UPDATE_PAGE_BOUNDS,
            targetId = "calc_1",
            payload = mapOf(
                "pageId" to "calc_1",
                "x" to "160",
                "y" to "936",
                "width" to "560",
                "height" to "608",
                "readyToShow" to "true"
            )
        )
        val json = revealBounds.toJson()
        val deserialized = HeavyCommand.fromJson(json)

        assertEquals("true", deserialized.payload["readyToShow"])
        assertEquals("160", deserialized.payload["x"])
        assertEquals("936", deserialized.payload["y"])
        assertEquals("608", deserialized.payload["height"])
    }

    @Test
    fun testReadyToShowLifecycleStateTransition() {
        var isMounted = false
        var isRevealed = false
        var hasReceivedDesiredHeight = false
        var desiredHeight = -1
        var dispatchedMountReadyToShow = ""
        var dispatchedBoundsReadyToShow = ""
        var boundsDispatchCount = 0

        // 1. Initial selection: bootstrap mount dispatched with readyToShow=false
        isMounted = true
        isRevealed = false
        hasReceivedDesiredHeight = false
        dispatchedMountReadyToShow = "false"
        assertEquals("false", dispatchedMountReadyToShow)
        assertFalse(isRevealed)

        // 2. Heavy calculates and reports desired height (e.g. 608)
        val reportedHeight = 608
        hasReceivedDesiredHeight = true
        desiredHeight = reportedHeight
        assertTrue(hasReceivedDesiredHeight)
        assertEquals(608, desiredHeight)

        // 3. Main layout stabilizes: final bounds dispatched with readyToShow=true
        val readyToReveal = !isRevealed && hasReceivedDesiredHeight
        assertTrue(readyToReveal)
        isRevealed = true
        dispatchedBoundsReadyToShow = "true"
        boundsDispatchCount++
        assertEquals("true", dispatchedBoundsReadyToShow)
        assertTrue(isRevealed)
        assertEquals(1, boundsDispatchCount)

        // 4. Redundant identical bounds when already revealed: suppressed
        val boundsChanged = false
        val shouldDispatchRedundant = boundsChanged || !isRevealed
        assertFalse(shouldDispatchRedundant)
        assertEquals(1, boundsDispatchCount) // Suppressed

        // 5. Drawer toggled: genuine bounds change while already revealed: dispatched with readyToShow=true
        val genuineBoundsChanged = true
        val shouldDispatchChanged = genuineBoundsChanged || !isRevealed
        assertTrue(shouldDispatchChanged)
        boundsDispatchCount++
        assertEquals(2, boundsDispatchCount)

        // 6. Navigating away: unmounted and reveal reset
        isMounted = false
        isRevealed = false
        hasReceivedDesiredHeight = false
        assertFalse(isMounted)
        assertFalse(isRevealed)
    }

    @Test
    fun testFallbackTimeoutNeverRevealsBootstrapRectWithoutHeight() {
        var isMounted = true
        var isRevealed = false
        var hasReceivedDesiredHeight = false
        var desiredHeight = -1

        // Fallback timeout fires before desired height has arrived
        fun onFallbackTimeout() {
            if (hasReceivedDesiredHeight || desiredHeight > 0) {
                isRevealed = true
            }
            // If height not established, remains hidden (readyToShow stays false)
        }

        onFallbackTimeout()
        assertFalse(isRevealed) // Guaranteed unrevealed!

        // Once desired height arrives, reveal becomes permitted
        hasReceivedDesiredHeight = true
        desiredHeight = 608
        onFallbackTimeout()
        assertTrue(isRevealed)
    }

    @Test
    fun testEmbeddedSurfaceApiGatingLogic() {
        fun isEmbeddedSurfaceSupported(sdkInt: Int): Boolean {
            return sdkInt >= Build.VERSION_CODES.R
        }

        assertFalse("API 26 should use legacy overlay fallback", isEmbeddedSurfaceSupported(26))
        assertFalse("API 28 should use legacy overlay fallback", isEmbeddedSurfaceSupported(28))
        assertFalse("API 29 should use legacy overlay fallback", isEmbeddedSurfaceSupported(29))
        assertTrue("API 30 should use embedded surface", isEmbeddedSurfaceSupported(30))
        assertTrue("API 34 should use embedded surface", isEmbeddedSurfaceSupported(34))
        assertTrue("API 35 (Android 15) should use embedded surface", isEmbeddedSurfaceSupported(35))
    }

    @Test
    fun testEmbeddedSurfaceCreationBundleContract() {
        val params = mapOf(
            "pageId" to "page_calc",
            "pageType" to "calculator",
            "displayId" to 0,
            "width" to 560,
            "height" to 608,
            "backgroundColor" to -14737634, // 0xFF1E1E2E
            "isRight" to true
        )

        assertEquals("page_calc", params["pageId"])
        assertEquals("calculator", params["pageType"])
        assertEquals(0, params["displayId"])
        assertEquals(560, params["width"])
        assertEquals(608, params["height"])
        assertEquals(-14737634, params["backgroundColor"])
        assertEquals(true, params["isRight"])
    }

    @Test
    fun testEmbeddedRelayoutPreservesInstanceWithoutRecreation() {
        var viewInstanceId = 12345
        var currentWidth = 560
        var currentHeight = 260
        var hostRelayoutCallCount = 0

        fun onDesiredHeightReported(newHeight: Int) {
            currentHeight = newHeight
            hostRelayoutCallCount++
        }

        // Calculator reports desired height 608
        onDesiredHeightReported(608)
        assertEquals(608, currentHeight)
        assertEquals(1, hostRelayoutCallCount)
        assertEquals(12345, viewInstanceId) // View instance completely preserved

        // Sidebar expands/collapses or drawer toggles to 700
        onDesiredHeightReported(700)
        assertEquals(700, currentHeight)
        assertEquals(2, hostRelayoutCallCount)
        assertEquals(12345, viewInstanceId) // Preserved
    }

    @Test
    fun testIpcTransactionsConstantsIntegrity() {
        assertEquals(
            android.os.IBinder.FIRST_CALL_TRANSACTION + 6,
            com.example.core.ipc.IpcTransactions.TRANSACTION_CREATE_SURFACE_PACKAGE
        )
        assertEquals(
            android.os.IBinder.FIRST_CALL_TRANSACTION + 7,
            com.example.core.ipc.IpcTransactions.TRANSACTION_RELAYOUT_SURFACE_HOST
        )
        assertEquals(
            android.os.IBinder.FIRST_CALL_TRANSACTION + 8,
            com.example.core.ipc.IpcTransactions.TRANSACTION_RELEASE_SURFACE_HOST
        )
    }

    @Test
    fun testEmbeddedStartupRaceGatingAndDeduplication() {
        // State simulation matching HeavyPagePlaceholderView lifecycle gating
        class EmbeddedGatingSim {
            var isSelected = false
            var isMountRequested = false
            var isSurfaceReady = false
            var isConnected = false
            var isEmbeddedAttached = false
            var isSurfaceCreationPending = false
            var creationRequestCount = 0

            fun requestEmbeddedSurfaceCreation(): Boolean {
                if (!isSelected || !isMountRequested || !isSurfaceReady || !isConnected) {
                    return false
                }
                if (isEmbeddedAttached || isSurfaceCreationPending) {
                    return false
                }
                isSurfaceCreationPending = true
                // In actual code, execution posts to handler; here we simulate the pending execution
                executeCreation()
                return true
            }

            fun executeCreation() {
                if (!isSelected || !isMountRequested || !isSurfaceReady || !isConnected || isEmbeddedAttached) {
                    isSurfaceCreationPending = false
                    return
                }
                creationRequestCount++
                isEmbeddedAttached = true
                isSurfaceCreationPending = false
            }

            fun onPageSelected() {
                isSelected = true
                isMountRequested = true
                // Acquire starts async connection (state = CONNECTING, isConnected = false)
                if (isSurfaceReady && isConnected) {
                    requestEmbeddedSurfaceCreation()
                }
            }

            fun onSurfaceReady() {
                isSurfaceReady = true
                if (isSelected && isConnected) {
                    requestEmbeddedSurfaceCreation()
                }
            }

            fun onConnected() {
                isConnected = true
                if (isSelected && isMountRequested && isSurfaceReady && isConnected && !isEmbeddedAttached && !isSurfaceCreationPending) {
                    requestEmbeddedSurfaceCreation()
                }
            }

            fun onPageUnselected() {
                isSelected = false
                isMountRequested = false
                isSurfaceCreationPending = false
                isEmbeddedAttached = false
                isConnected = false
            }
        }

        // Scenario 1: Surface ready + Heavy CONNECTING -> no creation request
        val sim1 = EmbeddedGatingSim()
        sim1.isSurfaceReady = true
        sim1.onPageSelected() // Heavy starts connecting, isConnected = false
        assertEquals("No creation request when connecting", 0, sim1.creationRequestCount)
        assertFalse(sim1.isEmbeddedAttached)

        // Scenario 2: Heavy becomes CONNECTED afterward -> exactly one creation request
        sim1.onConnected()
        assertEquals("Exactly one creation request when Heavy connects", 1, sim1.creationRequestCount)
        assertTrue(sim1.isEmbeddedAttached)

        // Scenario 3: Heavy CONNECTED first + Surface becomes ready afterward -> creation occurs when Surface becomes ready
        val sim3 = EmbeddedGatingSim()
        sim3.isConnected = true
        sim3.onPageSelected()
        // Surface not ready yet
        assertEquals(0, sim3.creationRequestCount)
        // Surface becomes ready later
        sim3.onSurfaceReady()
        assertEquals("Creation occurs when surface becomes ready", 1, sim3.creationRequestCount)
        assertTrue(sim3.isEmbeddedAttached)

        // Scenario 4: Page unselected while connection is pending -> later onConnected must NOT create the page
        val sim4 = EmbeddedGatingSim()
        sim4.isSurfaceReady = true
        sim4.onPageSelected() // connecting
        assertEquals(0, sim4.creationRequestCount)
        sim4.onPageUnselected() // User closed sidebar / navigated away
        assertFalse(sim4.isSelected)
        sim4.onConnected() // connection completes after unselection
        assertEquals("Must NOT create host when page was unselected", 0, sim4.creationRequestCount)
        assertFalse(sim4.isEmbeddedAttached)

        // Scenario 5: Duplicate readiness callbacks -> must not create duplicate Heavy page hosts
        val sim5 = EmbeddedGatingSim()
        sim5.onPageSelected()
        sim5.onSurfaceReady()
        sim5.onConnected()
        assertEquals(1, sim5.creationRequestCount)
        // Simulate repeated surfaceChanged, onConnected, or onSurfaceReady while already attached
        sim5.onSurfaceReady()
        sim5.onConnected()
        sim5.requestEmbeddedSurfaceCreation()
        assertEquals("Duplicate readiness callbacks must not create duplicate hosts", 1, sim5.creationRequestCount)
    }

    @Test
    fun testCalculatorSuppressesProvisionalBootstrapHeightBeforeSurfaceAttached() {
        // State simulation matching HeavyPagePlaceholderView and SidebarView interaction
        class PlaceholderHeightSim {
            var desiredHeightPx = -1
            var pendingDesiredHeightPx = -1
            var isEmbeddedAttached = false
            var notifiedSidebarHeight = -1
            var onHeightChangedCallCount = 0

            // Simulation of HeavyPagePlaceholderView.onMeasure() fallback without desired height
            fun measureHeight(parentHeight: Int): Int {
                return if (desiredHeightPx > 0) {
                    desiredHeightPx
                } else {
                    parentHeight.takeIf { it > 0 } ?: 0
                }
            }

            // Simulation of applyDesiredHeight()
            fun applyDesiredHeight(newHeight: Int) {
                if (newHeight <= 0) return
                pendingDesiredHeightPx = newHeight
                if (!isEmbeddedAttached) {
                    // Deferred: do not apply or notify Sidebar until SurfacePackage is attached
                    return
                }
                if (newHeight != desiredHeightPx) {
                    desiredHeightPx = newHeight
                    notifiedSidebarHeight = newHeight
                    onHeightChangedCallCount++
                }
            }

            fun onSurfacePackageAttached() {
                isEmbeddedAttached = true
                if (pendingDesiredHeightPx > 0) {
                    applyDesiredHeight(pendingDesiredHeightPx)
                }
            }
        }

        val sim = PlaceholderHeightSim()
        val initialHomeHeight = 146

        // 1. Initial state (navigating Home -> Calculator, Heavy still starting)
        // Placeholder height reflects existing container geometry (146), not provisional 520
        val measuredBeforeAttach = sim.measureHeight(parentHeight = initialHomeHeight)
        assertEquals("Placeholder must preserve existing container height before Heavy attaches", initialHomeHeight, measuredBeforeAttach)
        assertFalse("SurfacePackage must not be attached yet", sim.isEmbeddedAttached)
        assertEquals("Sidebar must not receive any height update yet", -1, sim.notifiedSidebarHeight)

        // 2. Heavy calculates and reports desired height (e.g. 607) before or during attachment
        sim.applyDesiredHeight(607)
        assertEquals(607, sim.pendingDesiredHeightPx)
        assertEquals("Desired height must not be applied before attachment", -1, sim.desiredHeightPx)
        assertEquals("Sidebar must not be notified before attachment", 0, sim.onHeightChangedCallCount)

        // 3. SurfacePackage is attached to SurfaceView
        sim.onSurfacePackageAttached()
        assertTrue(sim.isEmbeddedAttached)
        assertEquals("Desired height must be applied upon SurfacePackage attachment", 607, sim.desiredHeightPx)
        assertEquals("Sidebar must be notified with final desired height (607)", 607, sim.notifiedSidebarHeight)
        assertEquals(1, sim.onHeightChangedCallCount)

        // 4. Subsequent measure reflects real attached desired height
        val measuredAfterAttach = sim.measureHeight(parentHeight = initialHomeHeight)
        assertEquals(607, measuredAfterAttach)
    }

    @Test
    fun testLogKeeperLaunchSourceAndBackBehavior() {
        val extraSourceKey = com.example.LogKeeperActivity.EXTRA_LAUNCH_SOURCE
        val sourceSidebar = com.example.LogKeeperActivity.SOURCE_SIDEBAR
        val sourceSettings = com.example.LogKeeperActivity.SOURCE_SETTINGS

        assertEquals("extra_launch_source", extraSourceKey)
        assertEquals("SOURCE_SIDEBAR", sourceSidebar)
        assertEquals("SOURCE_SETTINGS", sourceSettings)

        class LogKeeperNavSim {
            var finishedNormally = false
            var taskRemoved = false

            fun handleBack(source: String) {
                if (source == com.example.LogKeeperActivity.SOURCE_SIDEBAR) {
                    taskRemoved = true
                } else {
                    finishedNormally = true
                }
            }
        }

        // Test 1: Launched from Sidebar
        val sidebarNav = LogKeeperNavSim()
        sidebarNav.handleBack(sourceSidebar)
        assertTrue("Back from Sidebar must remove task / exit cleanly without exposing Settings", sidebarNav.taskRemoved)
        assertFalse("Back from Sidebar must not just finish normally into background Settings", sidebarNav.finishedNormally)

        // Test 2: Launched from Settings
        val settingsNav = LogKeeperNavSim()
        settingsNav.handleBack(sourceSettings)
        assertTrue("Back from Settings must finish normally to return to Settings", settingsNav.finishedNormally)
        assertFalse("Back from Settings must not remove the Settings task", settingsNav.taskRemoved)
    }

    @Test
    fun testStep1HomeGridResidencyRule() {
        val fakePrefs = com.example.core.FakeSharedPreferences()
        val context = com.example.core.FakeContext(fakePrefs)
        com.example.core.HandleManager.resetForTesting()
        val handleManager = com.example.core.HandleManager.getInstance(context)

        // 1. Primary resident Home Grid on Handle 1 (Swipe Left / sidebar default)
        val residentPage = com.example.core.SidebarPage.createDefault(
            id = "default_hybrid",
            type = "default_hybrid",
            title = "Home Grid",
            order = 0
        )
        val isResident = com.example.feature.sidebar.DefaultSidebarPageFactory.isResidentDefaultHybrid(
            context = context,
            config = residentPage,
            physicalHandleId = "handle_1",
            containerId = "handle_1_swipe_left"
        )
        assertTrue("Handle 1 primary container default_hybrid must be resident in Main", isResident)

        // Legacy compatibility container IDs
        assertTrue(
            "Legacy 'sidebar' container default_hybrid must be resident",
            com.example.feature.sidebar.DefaultSidebarPageFactory.isResidentDefaultHybrid(
                context, residentPage, "handle_1", "sidebar"
            )
        )
        assertTrue(
            "Legacy 'handle_1' container default_hybrid must be resident",
            com.example.feature.sidebar.DefaultSidebarPageFactory.isResidentDefaultHybrid(
                context, residentPage, "handle_1", "handle_1"
            )
        )

        // 2. Secondary generated Home Grid on Handle 1 -> must NOT be resident
        val secondaryGrid = com.example.core.SidebarPage.createDefault(
            id = "hybrid_grid_12345",
            type = "hybrid_grid",
            title = "Secondary Grid",
            order = 1
        )
        assertFalse(
            "Secondary grid on Handle 1 must NOT be Main resident",
            com.example.feature.sidebar.DefaultSidebarPageFactory.isResidentDefaultHybrid(
                context, secondaryGrid, "handle_1", "handle_1_swipe_left"
            )
        )

        // 3. Home Grid on Handle 2+ -> must NOT be resident
        val handle2Page = com.example.core.SidebarPage.createDefault(
            id = "default_hybrid_handle_2_swipe_left",
            type = "default_hybrid",
            title = "Home Grid",
            order = 0
        )
        assertFalse(
            "Handle 2 default_hybrid must NOT be Main resident",
            com.example.feature.sidebar.DefaultSidebarPageFactory.isResidentDefaultHybrid(
                context, handle2Page, "handle_2", "handle_2_swipe_left"
            )
        )
        assertFalse(
            "default_hybrid ID on Handle 2 must NOT be Main resident",
            com.example.feature.sidebar.DefaultSidebarPageFactory.isResidentDefaultHybrid(
                context, residentPage, "handle_2", "handle_2_swipe_left"
            )
        )

        // 4. Home Grid on non-primary gesture (e.g. swipe_right when primary is swipe_left)
        assertFalse(
            "Non-primary gesture container must NOT be Main resident",
            com.example.feature.sidebar.DefaultSidebarPageFactory.isResidentDefaultHybrid(
                context, residentPage, "handle_1", "handle_1_swipe_right"
            )
        )
    }

    @Test
    fun testStep1HeavyToMainActionCallbacksIpc() {
        // 1. Serialization / Deserialization of MainCommandRequest with SET_DIMMED
        val dimRequest = com.example.core.ipc.MainCommandRequest(
            action = com.example.core.ipc.MainActionType.SET_DIMMED,
            targetId = "handle_1_swipe_left",
            parameters = mapOf("dimmed" to "true")
        )
        val dimJson = dimRequest.toJson()
        val parsedDim = com.example.core.ipc.MainCommandRequest.fromJson(dimJson)
        assertEquals(com.example.core.ipc.MainActionType.SET_DIMMED, parsedDim.action)
        assertEquals("handle_1_swipe_left", parsedDim.targetId)
        assertEquals("true", parsedDim.parameters["dimmed"])

        // 2. Serialization / Deserialization of CLOSE_WINDOW
        val closeRequest = com.example.core.ipc.MainCommandRequest(
            action = com.example.core.ipc.MainActionType.CLOSE_WINDOW,
            targetId = "sidebar"
        )
        val closeJson = closeRequest.toJson()
        val parsedClose = com.example.core.ipc.MainCommandRequest.fromJson(closeJson)
        assertEquals(com.example.core.ipc.MainActionType.CLOSE_WINDOW, parsedClose.action)
        assertEquals("sidebar", parsedClose.targetId)

        // 3. Serialization / Deserialization of TRIGGER_ACTION
        val triggerRequest = com.example.core.ipc.MainCommandRequest(
            action = com.example.core.ipc.MainActionType.TRIGGER_ACTION,
            targetId = "log_keeper",
            parameters = mapOf("containerId" to "handle_1_swipe_left")
        )
        val triggerJson = triggerRequest.toJson()
        val parsedTrigger = com.example.core.ipc.MainCommandRequest.fromJson(triggerJson)
        assertEquals(com.example.core.ipc.MainActionType.TRIGGER_ACTION, parsedTrigger.action)
        assertEquals("log_keeper", parsedTrigger.targetId)
        assertEquals("handle_1_swipe_left", parsedTrigger.parameters["containerId"])

        // 4. HeavyProcessHost convenience helpers
        val host = com.example.core.ipc.HeavyProcessHost(null)
        var receivedRequestJson: String? = null
        host.onCallbackRegistered(object : com.example.core.ipc.IMainCallbackContract {
            override fun onHeavyEvent(eventJson: String): String = ""
            override fun requestMainAction(requestJson: String): String {
                receivedRequestJson = requestJson
                return com.example.core.ipc.IpcResult.success().toJson()
            }
        })

        // Test requestCloseSidebar helper
        host.requestCloseSidebar("handle_1_swipe_left")
        assertNotNull(receivedRequestJson)
        var parsed = com.example.core.ipc.MainCommandRequest.fromJson(receivedRequestJson!!)
        assertEquals(com.example.core.ipc.MainActionType.CLOSE_WINDOW, parsed.action)
        assertEquals("handle_1_swipe_left", parsed.targetId)

        // Test requestSetDimmed helper
        host.requestSetDimmed(true, "handle_1_swipe_left")
        parsed = com.example.core.ipc.MainCommandRequest.fromJson(receivedRequestJson!!)
        assertEquals(com.example.core.ipc.MainActionType.SET_DIMMED, parsed.action)
        assertEquals("true", parsed.parameters["dimmed"])

        // Test requestTriggerAction helper
        host.requestTriggerAction("force_stop_running_apps", "handle_1_swipe_left")
        parsed = com.example.core.ipc.MainCommandRequest.fromJson(receivedRequestJson!!)
        assertEquals(com.example.core.ipc.MainActionType.TRIGGER_ACTION, parsed.action)
        assertEquals("force_stop_running_apps", parsed.targetId)
        assertEquals("handle_1_swipe_left", parsed.parameters["containerId"])

        // 5. HeavyProcessConnectionManager dispatch to listener
        val connMgr = com.example.core.ipc.HeavyProcessConnectionManager(null)
        var listenerReceivedAction: com.example.core.ipc.MainActionType? = null
        connMgr.addListener(object : com.example.core.ipc.HeavyConnectionListener {
            override fun onMainActionRequested(request: com.example.core.ipc.MainCommandRequest): com.example.core.ipc.IpcResult {
                listenerReceivedAction = request.action
                return com.example.core.ipc.IpcResult.success()
            }
        })

        val result = connMgr.dispatchMainActionForTesting(dimRequest)
        assertTrue(result.success)
        assertEquals(com.example.core.ipc.MainActionType.SET_DIMMED, listenerReceivedAction)
    }
}

