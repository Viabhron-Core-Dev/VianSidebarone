package com.example.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingMiniAppLogFilterTest {

    @Test
    fun testDisabledFilterReturnsRawTextVerbatim() {
        val raw = "[2026-11-05 10:00:00.000] [com.example] [main] [FloatingWindowManager] WINDOW_ATTACH id=regular_win_1"
        val result = FloatingMiniAppLogFilter.filter(raw, enabled = false)
        assertEquals(raw, result)
    }

    @Test
    fun testEmptyLogsReturnEmptyStateMessageWhenEnabled() {
        assertEquals(
            FloatingMiniAppLogFilter.EMPTY_STATE_MESSAGE,
            FloatingMiniAppLogFilter.filter("", enabled = true)
        )
        assertEquals(
            FloatingMiniAppLogFilter.EMPTY_STATE_MESSAGE,
            FloatingMiniAppLogFilter.filter("No records in LiteReader_Log.txt.", enabled = true)
        )
    }

    @Test
    fun testMatchingKnownComponentTags() {
        val tags = listOf(
            "FloatingMiniAppBridge",
            "FloatingMiniAppWindow",
            "FloatingMiniAppLauncherBubble",
            "HeavyFloatingHost",
            "HeavyFloatingHostService",
            "TestNotesImeDebug"
        )

        for (tag in tags) {
            val logLine = "[2026-11-05 10:00:00.000] [com.example] [main] [$tag] Service event occurred"
            assertTrue("Expected tag $tag to be recognized", FloatingMiniAppLogFilter.isMiniAppLogEntry(logLine))

            val errorLine = "[2026-11-05 10:00:00.000] [com.example] [main] [ERROR:$tag] Caught failure"
            assertTrue("Expected ERROR:$tag to be recognized", FloatingMiniAppLogFilter.isMiniAppLogEntry(errorLine))
        }
    }

    @Test
    fun testContextualMiniAppEntriesFromSharedComponents() {
        val matchingSharedLogs = listOf(
            "[2026-11-05 10:00:00.000] [com.example] [main] [FloatingWindowManager] WINDOW_ATTACH_FAILURE id=miniapp_test_notes",
            "[2026-11-05 10:00:01.000] [com.example] [main] [FloatingWindowManager] ATTACH_WINDOW app_type=test_notes id=123",
            "[2026-11-05 10:00:02.000] [com.example] [main] [HeavyPageHostManager] BIND_CONTAINER consumer=miniapp_speed_monitor",
            "[2026-11-05 10:00:03.000] [com.example] [main] [HeavyProcessConnectionManager] SEND_COMMAND target=miniapp_ebook_reader",
            "[2026-11-05 10:00:04.000] [com.example] [main] [HeavyPageHostManager] ATTACH_VIEW type=ebook_reader",
            "[2026-11-05 10:00:05.000] [com.example] [main] [FloatingWindowManager] RESIZE target=miniapp_test_notes width=500 height=600"
        )

        for (log in matchingSharedLogs) {
            assertTrue("Expected contextual log to match: $log", FloatingMiniAppLogFilter.isMiniAppLogEntry(log))
        }
    }

    @Test
    fun testUnrelatedEntriesAreExcluded() {
        val unrelatedLogs = listOf(
            "[2026-11-05 10:00:00.000] [com.example] [main] [FloatingWindowManager] WINDOW_ATTACH id=regular_floating_calc",
            "[2026-11-05 10:00:01.000] [com.example] [main] [FloatingWindowManager] DRAG_START id=sidebar_handle x=100 y=200",
            "[2026-11-05 10:00:02.000] [com.example] [main] [HeavyPageHostManager] BIND_PAGE pageId=pwa_notes_dashboard",
            "[2026-11-05 10:00:03.000] [com.example] [main] [HeavyProcessConnectionManager] SEND_COMMAND cmd=ping target=sidebar_service",
            "[2026-11-05 10:00:04.000] [com.example] [main] [Lifecycle] [Sidebar] Activity resumed: MainActivity",
            "[2026-11-05 10:00:05.000] [com.example] [main] [HeavyPageHostManager] ATTACH_VIEW type=pwa_reader"
        )

        for (log in unrelatedLogs) {
            assertFalse("Expected unrelated log to be excluded: $log", FloatingMiniAppLogFilter.isMiniAppLogEntry(log))
        }

        val joined = unrelatedLogs.joinToString("\n")
        val filtered = FloatingMiniAppLogFilter.filter(joined, enabled = true)
        assertEquals(FloatingMiniAppLogFilter.EMPTY_STATE_MESSAGE, filtered)
    }

    @Test
    fun testPreservesCompleteMultilineExceptionStackTraces() {
        val raw = """
            [2026-11-05 10:00:00.000] [com.example] [main] [FloatingWindowManager] WINDOW_ATTACH id=regular_window
            [2026-11-05 10:00:01.000] [com.example] [main] [ERROR:FloatingMiniAppWindow] Surface attachment failed
            java.lang.NullPointerException: Surface package is null
            	at com.example.feature.floating.FloatingMiniAppWindow.attachSurface(FloatingMiniAppWindow.kt:340)
            	at com.example.feature.floating.FloatingMiniAppWindow.createContentView(FloatingMiniAppWindow.kt:290)
            Caused by: java.lang.IllegalStateException: Host disconnected
            	at com.example.core.ipc.HeavyProcessConnectionManager.getBinder(HeavyProcessConnectionManager.kt:150)
            [2026-11-05 10:00:02.000] [com.example] [main] [HeavyPageHostManager] BIND_PAGE pageId=pwa_browser
        """.trimIndent()

        val filtered = FloatingMiniAppLogFilter.filter(raw, enabled = true)

        assertFalse("Unrelated window should be excluded", filtered.contains("regular_window"))
        assertFalse("Unrelated PWA should be excluded", filtered.contains("pwa_browser"))

        assertTrue("Matching entry header should be present", filtered.contains("[ERROR:FloatingMiniAppWindow] Surface attachment failed"))
        assertTrue("Stack trace line 1 preserved", filtered.contains("FloatingMiniAppWindow.attachSurface(FloatingMiniAppWindow.kt:340)"))
        assertTrue("Stack trace line 2 preserved", filtered.contains("FloatingMiniAppWindow.createContentView(FloatingMiniAppWindow.kt:290)"))
        assertTrue("Caused-by block preserved", filtered.contains("Caused by: java.lang.IllegalStateException: Host disconnected"))
    }

    @Test
    fun testHandlesMultilineCrashReportBlocks() {
        val raw = """
            ================================================================
            === UNCAUGHT EXCEPTION CRASH REPORT ===
            Timestamp: 2026-11-05 10:00:00.000
            Process: com.example:heavy
            Thread: main
            Exception: java.lang.NullPointerException
            Message: Attempt to invoke virtual method on null object reference
            StackTrace:
            java.lang.NullPointerException: Attempt to invoke virtual method on null object reference
            	at com.example.feature.floating.FloatingMiniAppWindow${'$'}createImeBridgeEditText${'$'}1.setText(FloatingMiniAppWindow.kt:377)
            	at android.widget.TextView.<init>(TextView.java:1200)
            ================================================================
            
            ================================================================
            === UNCAUGHT EXCEPTION CRASH REPORT ===
            Timestamp: 2026-11-05 10:05:00.000
            Process: com.example:heavy
            Thread: main
            Exception: java.lang.IllegalStateException
            Message: PWA crashed
            StackTrace:
            java.lang.IllegalStateException: PWA crashed
            	at com.example.feature.pwa.PwaRunner.run(PwaRunner.kt:50)
            ================================================================
        """.trimIndent()

        val filtered = FloatingMiniAppLogFilter.filter(raw, enabled = true)

        assertTrue("Mini-app crash report block must be included", filtered.contains("FloatingMiniAppWindow${'$'}createImeBridgeEditText${'$'}1.setText"))
        assertFalse("Unrelated PWA crash report block must be excluded", filtered.contains("PwaRunner.run"))
    }
}
