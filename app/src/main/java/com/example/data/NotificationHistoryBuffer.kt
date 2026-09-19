package com.example.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

data class BufferedNotification(
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val isOngoing: Boolean
)

/**
 * Lightweight, durable append-only buffer for incoming notification history events.
 *
 * Allows AppNotificationListener running in the resident Main process to persist notifications
 * immediately to disk using OS-level FileChannel locks without initializing Room, SQLite,
 * or the heavy AppDatabase instance in the resident daemon.
 *
 * When NotificationHistoryActivity is opened (on-demand in the UI / Heavy process), it drains
 * this buffer and ingests the records into Room (NotificationHistoryDao) with deduplication.
 */
object NotificationHistoryBuffer {
    private const val TAG = "NotificationHistoryBuffer"
    const val FILE_BUFFER = "notification_history_buffer.log"
    private const val MAX_BUFFER_SIZE = 512 * 1024L // 512 KB cap

    private data class RecentItem(val text: String, val timestamp: Long, val isOngoing: Boolean)
    private val recentCache = ConcurrentHashMap<String, RecentItem>()

    internal fun serialize(item: BufferedNotification): String {
        val enc = Base64.getEncoder()
        val p = enc.encodeToString(item.packageName.toByteArray(Charsets.UTF_8))
        val a = enc.encodeToString(item.appName.toByteArray(Charsets.UTF_8))
        val t = enc.encodeToString(item.title.toByteArray(Charsets.UTF_8))
        val x = enc.encodeToString(item.text.toByteArray(Charsets.UTF_8))
        return "${item.timestamp}|${item.isOngoing}|$p|$a|$t|$x\n"
    }

    internal fun deserialize(line: String): BufferedNotification? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split('|')
        if (parts.size < 6) return null
        val timestamp = parts[0].toLongOrNull() ?: return null
        val isOngoing = parts[1].toBoolean()
        val dec = Base64.getDecoder()
        return try {
            val pkg = String(dec.decode(parts[2]), Charsets.UTF_8)
            val app = String(dec.decode(parts[3]), Charsets.UTF_8)
            val title = String(dec.decode(parts[4]), Charsets.UTF_8)
            val text = String(dec.decode(parts[5]), Charsets.UTF_8)
            BufferedNotification(
                packageName = pkg,
                appName = app,
                title = title,
                text = text,
                timestamp = timestamp,
                isOngoing = isOngoing
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Appends a notification event to the durable buffer file.
     * Safe to call from the resident Main process without touching Room.
     */
    @Synchronized
    fun record(
        context: Context,
        packageName: String,
        appName: String,
        title: String,
        text: String,
        isOngoing: Boolean,
        timestamp: Long = System.currentTimeMillis()
    ) {
        if (packageName.isBlank() || (title.isBlank() && text.isBlank())) return
        if (packageName == context.packageName) return

        // In-memory debounce to filter out high-frequency identical noise
        val key = "$packageName:$title"
        val last = recentCache[key]
        if (last != null && last.text == text && (isOngoing || (timestamp - last.timestamp < 15_000L))) {
            return
        }
        recentCache[key] = RecentItem(text, timestamp, isOngoing)

        try {
            val file = File(context.filesDir, FILE_BUFFER)
            if (file.exists() && file.length() > MAX_BUFFER_SIZE) {
                trimBuffer(file)
            }

            val item = BufferedNotification(
                packageName = packageName,
                appName = appName,
                title = title,
                text = text,
                timestamp = timestamp,
                isOngoing = isOngoing
            )
            val line = serialize(item)

            FileOutputStream(file, true).use { fos ->
                val channel = fos.channel
                var lock: FileLock? = null
                try {
                    lock = channel.lock()
                    fos.write(line.toByteArray(Charsets.UTF_8))
                    fos.flush()
                } finally {
                    try {
                        lock?.release()
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append notification to buffer", e)
        }
    }

    /**
     * Atomically reads all pending buffered notifications and clears the buffer file.
     * Cross-process safe using OS FileChannel locks.
     */
    @Synchronized
    fun drainPending(context: Context): List<BufferedNotification> {
        val file = File(context.filesDir, FILE_BUFFER)
        if (!file.exists() || file.length() == 0L) return emptyList()

        val items = mutableListOf<BufferedNotification>()
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val channel = raf.channel
                var lock: FileLock? = null
                try {
                    lock = channel.lock()
                    val length = raf.length()
                    if (length > 0) {
                        val bytes = ByteArray(length.toInt())
                        raf.readFully(bytes)
                        val content = String(bytes, Charsets.UTF_8)
                        content.lineSequence().forEach { line ->
                            val parsed = deserialize(line)
                            if (parsed != null) {
                                items.add(parsed)
                            }
                        }
                        // Truncate file after successful read
                        raf.setLength(0)
                    }
                } finally {
                    try {
                        lock?.release()
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to drain notification buffer", e)
        }
        return items
    }

    /**
     * Ingests all pending buffered items into Room using the provided DAO.
     * Should be called from the Heavy / UI process where Room is already initialized.
     */
    suspend fun ingestPending(context: Context, dao: NotificationHistoryDao) {
        val pending = drainPending(context)
        if (pending.isEmpty()) return

        for (item in pending) {
            try {
                val existing = dao.findLatestByPackageAndTitle(item.packageName, item.title)
                val now = item.timestamp
                if (existing != null && (item.isOngoing || (now - existing.timestamp < 15_000L && existing.title == item.title))) {
                    dao.update(existing.copy(text = item.text, timestamp = now))
                } else {
                    val history = NotificationHistory(
                        packageName = item.packageName,
                        appName = item.appName,
                        title = item.title,
                        text = item.text,
                        timestamp = now
                    )
                    dao.insert(history)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error inserting buffered notification into Room", e)
            }
        }
    }

    /**
     * Clears the buffer file and in-memory debounce cache.
     */
    @Synchronized
    fun clearBuffer(context: Context) {
        recentCache.clear()
        val file = File(context.filesDir, FILE_BUFFER)
        if (!file.exists()) return
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val channel = raf.channel
                var lock: FileLock? = null
                try {
                    lock = channel.lock()
                    raf.setLength(0)
                } finally {
                    try {
                        lock?.release()
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear buffer file", e)
        }
    }

    /**
     * Returns the approximate number of pending items in the buffer.
     */
    fun getPendingCount(context: Context): Int {
        val file = File(context.filesDir, FILE_BUFFER)
        if (!file.exists() || file.length() == 0L) return 0
        return try {
            file.bufferedReader().useLines { lines -> lines.count { it.isNotBlank() } }
        } catch (_: Exception) {
            0
        }
    }

    private fun trimBuffer(file: File) {
        try {
            val lines = file.readLines()
            if (lines.size > 100) {
                val retained = lines.takeLast(lines.size / 2)
                file.writeText(retained.joinToString("\n") + "\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error trimming buffer file", e)
        }
    }
}
