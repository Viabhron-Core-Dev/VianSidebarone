package com.example.feature.settings

import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import com.example.SettingsActivity
import com.example.core.FakeContext
import com.example.core.FakeSharedPreferences
import com.example.feature.reader.BookTrackerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Focused verification for Sidebar Settings navigation structure:
 * - Main Sidebar Settings (`MainSettingsScreen`) has no standalone Book Tracker entry.
 * - Main Sidebar Settings preserves `Reader Settings` navigation and unrelated Settings items.
 * - `ReaderSettingsScreen` preserves its `Open Book Tracker` button launching `BookTrackerActivity`.
 */
class SettingsNavigationStructureTest {

    private fun resolveSourceFile(relativePathFromApp: String): File {
        val candidates = listOf(
            File(relativePathFromApp),
            File("app/$relativePathFromApp"),
            File("../app/$relativePathFromApp")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Could not locate source file: $relativePathFromApp from ${File(".").absolutePath}")
    }

    @Test
    fun testMainSettingsScreenHasNoStandaloneBookTrackerAndKeepsReaderSettings() {
        val settingsSource = resolveSourceFile("src/main/java/com/example/SettingsActivity.kt").readText()

        // MainSettingsScreen must not expose standalone Book Tracker item, click handler, or obsolete tag
        assertFalse(
            "MainSettingsScreen must not contain settings_item_book_tracker testTag",
            settingsSource.contains("settings_item_book_tracker")
        )
        assertFalse(
            "MainSettingsScreen must not contain Book Tracker list item",
            settingsSource.contains("Text(\"Book Tracker\")")
        )
        assertFalse(
            "SettingsActivity main list must not launch BookTrackerActivity directly",
            settingsSource.contains("BookTrackerActivity.launch(")
        )

        // Reader Settings and all other main Settings items remain intact
        assertTrue(settingsSource.contains("Text(\"Reader Settings\")"))
        assertTrue(settingsSource.contains("testTag(\"settings_item_reader_settings\")"))
        assertTrue(settingsSource.contains("onNavigateToReaderSettings()"))
        assertTrue(settingsSource.contains("currentRoute == \"reader_settings\" || currentRoute == \"reader\""))
        assertTrue(settingsSource.contains("testTag(\"settings_item_handles\")"))
        assertTrue(settingsSource.contains("testTag(\"settings_item_netspeed\")"))
        assertTrue(settingsSource.contains("testTag(\"settings_item_call_recorder\")"))
        assertTrue(settingsSource.contains("testTag(\"settings_item_record\")"))
        assertTrue(settingsSource.contains("testTag(\"settings_item_permissions\")"))
        assertTrue(settingsSource.contains("testTag(\"settings_item_log_keeper\")"))
    }

    @Test
    fun testReaderSettingsScreenRetainsOpenBookTrackerButtonAndLaunchContract() {
        val readerSettingsSource = resolveSourceFile(
            "src/main/java/com/example/feature/settings/ReaderSettingsScreen.kt"
        ).readText()

        assertTrue(
            "ReaderSettingsScreen must retain Open Book Tracker button label",
            readerSettingsSource.contains("Text(\"Open Book Tracker\")")
        )
        assertTrue(
            "ReaderSettingsScreen must retain open_book_tracker_button testTag",
            readerSettingsSource.contains("testTag(\"open_book_tracker_button\")")
        )
        assertTrue(
            "ReaderSettingsScreen must still launch BookTrackerActivity",
            readerSettingsSource.contains("BookTrackerActivity.launch(context, openBackupPanel = false)")
        )
        assertTrue(
            "ReaderSettingsScreen must also retain backup manager launch",
            readerSettingsSource.contains("BookTrackerActivity.launch(context, openBackupPanel = true)")
        )

        // Verify BookTrackerActivity remains a valid ComponentActivity and launch() dispatches an Intent cleanly
        assertTrue(
            "BookTrackerActivity must remain a ComponentActivity",
            ComponentActivity::class.java.isAssignableFrom(BookTrackerActivity::class.java)
        )

        val capturedIntents = mutableListOf<Intent>()
        val baseContext = FakeContext(FakeSharedPreferences())
        val capturingContext = object : ContextWrapper(baseContext) {
            override fun startActivity(intent: Intent?) {
                if (intent != null) {
                    capturedIntents.add(intent)
                }
            }
        }

        BookTrackerActivity.launch(capturingContext, openBackupPanel = false)
        assertEquals(
            "BookTrackerActivity.launch must dispatch one Intent from Reader Settings",
            1,
            capturedIntents.size
        )

        // Verify SettingsActivity still routes reader_settings & declares ComponentActivity cleanly
        assertTrue(
            "SettingsActivity must remain a ComponentActivity",
            ComponentActivity::class.java.isAssignableFrom(SettingsActivity::class.java)
        )
    }
}
