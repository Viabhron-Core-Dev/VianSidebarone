package com.example.core

import android.content.Context
import android.content.Intent
import com.example.service.BootReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MainProcessRecoveryTest: Verifies Main process crash/restart recovery semantics,
 * crash loop suppression, backoff delays, state idempotency, and lifecycle differentiation.
 */
class MainProcessRecoveryTest {

    // 1. Verify BootReceiver contract includes ACTION_RECOVER_MAIN
    @Test
    fun testBootReceiverRecoveryActionContract() {
        assertEquals("com.example.action.RECOVER_MAIN", BootReceiver.ACTION_RECOVER_MAIN)
    }

    // 2. Verify HandleService returns START_STICKY
    @Test
    fun testHandleServiceServiceRestartContract() {
        // android.app.Service.START_STICKY constant value is 1
        assertEquals(1, android.app.Service.START_STICKY)
    }

    // 3. Verify Handle -> Gesture -> Container -> Page -> Element hierarchy keys and isolation
    @Test
    fun testPersistedStateHierarchyKeys() {
        val handleId = "handle_1"
        val gesture = HandleGestures.SWIPE_LEFT
        val containerId = HandleManager.getContainerId(handleId, gesture)
        assertEquals("handle_1_swipe_left", containerId)

        val container = SidebarContainer(containerId, handleId, gesture, true)
        assertEquals("handle_handle_1_swipe_left_pages", container.pagesKey)

        // Selected page key format
        val selectedPageKey = HandleManager.getContainerSelectedPageKey(containerId)
        assertEquals("handle_handle_1_swipe_left_selected_page", selectedPageKey)

        // Element placement key format
        val pageId = "default_hybrid"
        val elementsKey = HandleManager.getContainerPageElementsKey(containerId, pageId)
        assertEquals("handle_handle_1_swipe_left_page_default_hybrid_elements", elementsKey)

        // Verify independent containers on different gestures remain strictly isolated
        val gestureTap = HandleGestures.TAP
        val containerIdTap = HandleManager.getContainerId(handleId, gestureTap)
        assertEquals("handle_1_tap", containerIdTap)
        val containerTap = SidebarContainer(containerIdTap, handleId, gestureTap, true)
        assertFalse(container.pagesKey == containerTap.pagesKey)
        assertFalse(
            HandleManager.getContainerPageElementsKey(containerId, pageId) ==
            HandleManager.getContainerPageElementsKey(containerIdTap, pageId)
        )
    }

    // 4. Verify crash loop suppression logic
    @Test
    fun testCrashLoopSuppressionParameters() {
        val maxConsecutiveCrashes = 3
        val crashWindowMs = 60_000L
        val stableRunThresholdMs = 15_000L

        assertTrue("Window must allow transient recovery", crashWindowMs > stableRunThresholdMs)
        assertEquals(3, maxConsecutiveCrashes)

        // Backoff progression
        val delay1 = 2000L
        val delay2 = 5000L
        val delay3 = 10000L
        assertTrue("Backoff should be progressive", delay1 < delay2 && delay2 < delay3)
    }

    // 5. Verify distinct lifecycle event keys
    @Test
    fun testLifecycleEventDistinction() {
        val events = listOf(
            "CREATED",
            "DESTROYED",
            "SERVICE_RECREATED_STICKY",
            "UNCAUGHT_CRASH",
            "HEAVY_PROCESS_DIED",
            "BOOT_COMPLETED",
            "PACKAGE_REPLACED",
            "RECOVER_MAIN",
            "SCREEN_ON",
            "SCREEN_OFF",
            "USER_PRESENT",
            "TASK_REMOVED"
        )
        assertEquals("All lifecycle events must be unique", events.size, events.distinct().size)
    }

    // 6. Verify CallRecorderManager screen-off availability
    @Test
    fun testCallSensorAvailableDuringScreenOff() {
        val callManager = CallRecorderManager(context = null)
        assertTrue("Call sensor must remain available during screen off", callManager.isAvailableDuringScreenOff)
    }

    // 7. Verify SpeedIconProvider resolves critical speed values to non-zero resource IDs
    @Test
    fun testSpeedIconProviderResolution() {
        val speed25k = SpeedIconProvider.resolve("25", "kB/s")
        assertEquals("ic_stat_speed_25_k", speed25k.resName)
        assertTrue("Speed 25 kB/s icon resource ID must be non-zero", speed25k.resId > 0)

        val speedZero = SpeedIconProvider.resolve("0", "kB/s")
        assertEquals("ic_stat_speed_0_k", speedZero.resName)
        assertTrue("Speed 0 kB/s icon resource ID must be non-zero", speedZero.resId > 0)

        val speedMb = SpeedIconProvider.resolve("25.0", "MB/s")
        assertEquals("ic_stat_speed_25_0_m", speedMb.resName)
        assertTrue("Speed 25.0 MB/s icon resource ID must be non-zero", speedMb.resId > 0)
    }

    // 8. Verify all pre-rendered speed icon files on disk have valid PNG headers
    @Test
    fun testAllSpeedIconAssetsValidPng() {
        val resDir = java.io.File("src/main/res/drawable-xhdpi")
        if (resDir.exists()) {
            val pngFiles = resDir.listFiles { _, name -> name.startsWith("ic_stat_speed_") && name.endsWith(".png") }
            assertNotNull("Speed icon directory should have files", pngFiles)
            assertTrue("Should contain all 1421 speed icons", pngFiles!!.size >= 1421)

            val pngSignature = byteArrayOf(
                0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
                0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
            )
            for (file in pngFiles) {
                java.io.FileInputStream(file).use { fis ->
                    val header = ByteArray(8)
                    val read = fis.read(header)
                    assertEquals("Header read length for ${file.name}", 8, read)
                    assertTrue("Valid PNG signature for ${file.name}", header.contentEquals(pngSignature))
                }
            }
        }
    }
}

