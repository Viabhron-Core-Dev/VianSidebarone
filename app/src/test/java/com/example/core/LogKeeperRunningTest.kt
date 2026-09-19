package com.example.core

import com.example.ActiveProcessItem
import com.example.ActiveServiceItem
import com.example.LifecycleEventItem
import com.example.RunningRuntimeState
import com.example.formatDuration
import com.example.formatRunningSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogKeeperRunningTest {

    @Test
    fun testFormatDuration() {
        assertEquals("0s", formatDuration(0L))
        assertEquals("45s", formatDuration(45_000L))
        assertEquals("2m 30s", formatDuration(150_000L))
        assertEquals("1h 15m", formatDuration(4_500_000L))
        assertEquals("1d 2h", formatDuration(93_600_000L))
    }

    @Test
    fun testRunningSummaryFormatting() {
        val processes = listOf(
            ActiveProcessItem(
                name = "Main Process",
                isMain = true,
                isHeavy = false,
                pid = 1234,
                importance = "Foreground Service",
                isRunning = true
            ),
            ActiveProcessItem(
                name = "Heavy Process",
                isMain = false,
                isHeavy = true,
                pid = 5678,
                importance = "Visible",
                isRunning = true
            )
        )

        val services = listOf(
            ActiveServiceItem(
                shortName = "HandleService",
                fullName = "com.example.service.HandleService",
                processName = "com.example",
                isMainProcess = true,
                isForeground = true,
                started = true,
                activeSinceMs = 1000L,
                startTimeFormatted = "10:00:00",
                durationFormatted = "15m 30s"
            )
        )

        val events = listOf(
            LifecycleEventItem(
                timestamp = "10:02:35",
                process = "Main",
                isMainProcess = true,
                thread = "main",
                component = "MainActivity",
                eventType = "STOPPED",
                message = "Activity stopped: MainActivity",
                durationText = "2m 30s"
            )
        )

        val state = RunningRuntimeState(
            processes = processes,
            activeServices = services,
            lifecycleEvents = events
        )

        val summary = formatRunningSummary(state)
        assertNotNull(summary)
        assertTrue(summary.contains("=== RUNTIME PROCESSES ==="))
        assertTrue(summary.contains("Main Process: RUNNING (PID: 1234, Foreground Service)"))
        assertTrue(summary.contains("Heavy Process: RUNNING (PID: 5678, Visible)"))
        assertTrue(summary.contains("=== ACTIVE SERVICES (1) ==="))
        assertTrue(summary.contains("HandleService [Main] [Foreground]"))
        assertTrue(summary.contains("=== RECENT LIFECYCLE EVENTS (1) ==="))
        assertTrue(summary.contains("[10:02:35] [Main] [STOPPED] [MainActivity]"))
        assertTrue(summary.contains("(Duration: 2m 30s)"))
    }
}

