package com.example.data

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NotificationHistoryBufferTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: Context
    private lateinit var testFilesDir: File

    private class TestContext(private val baseFilesDir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = baseFilesDir
        override fun getPackageName(): String = "com.example"
    }

    @Before
    fun setup() {
        testFilesDir = tempFolder.newFolder("files")
        testContext = TestContext(testFilesDir)
        NotificationHistoryBuffer.clearBuffer(testContext)
    }

    @Test
    fun testRecordAndDrainPending() {
        // Record notifications using lightweight buffer (no Room initialization)
        NotificationHistoryBuffer.record(
            context = testContext,
            packageName = "com.whatsapp",
            appName = "WhatsApp",
            title = "Alice",
            text = "Hello there!",
            isOngoing = false,
            timestamp = 1000L
        )

        NotificationHistoryBuffer.record(
            context = testContext,
            packageName = "com.google.android.gm",
            appName = "Gmail",
            title = "Important Email",
            text = "Meeting at 3pm",
            isOngoing = false,
            timestamp = 2000L
        )

        assertEquals(2, NotificationHistoryBuffer.getPendingCount(testContext))

        // Drain pending notifications
        val drained = NotificationHistoryBuffer.drainPending(testContext)
        assertEquals(2, drained.size)

        assertEquals("com.whatsapp", drained[0].packageName)
        assertEquals("WhatsApp", drained[0].appName)
        assertEquals("Alice", drained[0].title)
        assertEquals("Hello there!", drained[0].text)
        assertEquals(1000L, drained[0].timestamp)
        assertFalse(drained[0].isOngoing)

        assertEquals("com.google.android.gm", drained[1].packageName)
        assertEquals("Important Email", drained[1].title)

        // After drain, buffer file should be empty
        assertEquals(0, NotificationHistoryBuffer.getPendingCount(testContext))
        assertTrue(NotificationHistoryBuffer.drainPending(testContext).isEmpty())
    }

    @Test
    fun testSelfNotificationAndEmptySkipped() {
        // Internal self notifications must be ignored
        NotificationHistoryBuffer.record(
            context = testContext,
            packageName = "com.example",
            appName = "Self",
            title = "Notification",
            text = "Test",
            isOngoing = false
        )
        assertEquals(0, NotificationHistoryBuffer.getPendingCount(testContext))

        // Empty title and text must be ignored
        NotificationHistoryBuffer.record(
            context = testContext,
            packageName = "com.other",
            appName = "Other",
            title = "",
            text = "",
            isOngoing = false
        )
        assertEquals(0, NotificationHistoryBuffer.getPendingCount(testContext))
    }

    @Test
    fun testDebouncePreventsDuplicateSpam() {
        // Rapid identical notification events for same package and title within 15 seconds
        NotificationHistoryBuffer.record(
            context = testContext,
            packageName = "com.music.player",
            appName = "Music",
            title = "Playing Track",
            text = "Artist - Song",
            isOngoing = true,
            timestamp = 1000L
        )

        // Immediate duplicate
        NotificationHistoryBuffer.record(
            context = testContext,
            packageName = "com.music.player",
            appName = "Music",
            title = "Playing Track",
            text = "Artist - Song",
            isOngoing = true,
            timestamp = 2000L
        )

        // Only 1 record should be stored in buffer
        assertEquals(1, NotificationHistoryBuffer.getPendingCount(testContext))
        val drained = NotificationHistoryBuffer.drainPending(testContext)
        assertEquals(1, drained.size)
    }

    @Test
    fun testIngestPendingIntoDao() = runBlocking {
        NotificationHistoryBuffer.record(
            context = testContext,
            packageName = "com.slack",
            appName = "Slack",
            title = "Project Channel",
            text = "Deployment ready",
            isOngoing = false,
            timestamp = 5000L
        )

        val fakeDao = FakeNotificationHistoryDao()
        NotificationHistoryBuffer.ingestPending(testContext, fakeDao)

        assertEquals(1, fakeDao.items.size)
        val item = fakeDao.items[0]
        assertEquals("com.slack", item.packageName)
        assertEquals("Project Channel", item.title)
        assertEquals("Deployment ready", item.text)

        // Buffer is now cleared after ingestion
        assertEquals(0, NotificationHistoryBuffer.getPendingCount(testContext))
    }

    private class FakeNotificationHistoryDao : NotificationHistoryDao {
        val items = mutableListOf<NotificationHistory>()

        override suspend fun insert(notification: NotificationHistory): Long {
            val id = (items.size + 1).toLong()
            items.add(notification.copy(id = id))
            return id
        }

        override suspend fun update(notification: NotificationHistory) {
            val idx = items.indexOfFirst { it.packageName == notification.packageName && it.title == notification.title }
            if (idx != -1) {
                items[idx] = notification
            }
        }

        override suspend fun findLatestByPackageAndTitle(packageName: String, title: String): NotificationHistory? {
            return items.lastOrNull { it.packageName == packageName && it.title == title }
        }

        override fun getAll(): Flow<List<NotificationHistory>> = emptyFlow()
        override fun getFiltered(excludedPackages: List<String>): Flow<List<NotificationHistory>> = emptyFlow()
        override fun searchAll(query: String): Flow<List<NotificationHistory>> = emptyFlow()
        override fun search(query: String, excludedPackages: List<String>): Flow<List<NotificationHistory>> = emptyFlow()
        override suspend fun deleteAll() {
            items.clear()
        }
    }
}
