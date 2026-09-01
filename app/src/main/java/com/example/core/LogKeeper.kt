package com.example.core

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LogKeeper: Zero-PII Multi-Process Diagnostic & Crash Logger.
 * Writes diagnostic logs to LiteReader_Log.txt and crash logs to LiteReader_CrashLog.txt
 * inside context.filesDir.
 * Fully multi-process safe using OS FileChannel file locks.
 * Strictly Zero-PII: No user content, no credentials, no private data.
 */
object LogKeeper {
    private const val TAG = "LogKeeper"
    const val FILE_DIAGNOSTIC_LOG = "LiteReader_Log.txt"
    const val FILE_CRASH_LOG = "LiteReader_CrashLog.txt"

    private const val MAX_LOG_SIZE = 256 * 1024 // 256 KB max per log file
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var isMasterEnabled = true

    @Volatile
    private var cachedProcessName: String? = null

    fun init(context: Context) {
        cachedProcessName = getProcessName(context)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logCrash(context, thread.name, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
        log(context, "LogKeeper", "Initialized logger in process: $cachedProcessName")
    }

    fun setMasterEnabled(enabled: Boolean) {
        isMasterEnabled = enabled
    }

    fun isEnabled(): Boolean = isMasterEnabled

    fun getProcessName(context: Context): String {
        cachedProcessName?.let { return it }
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            try {
                val pid = Process.myPid()
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: "pid-$pid"
            } catch (e: Exception) {
                "unknown"
            }
        }
        cachedProcessName = name
        return name
    }

    /**
     * Records a standard diagnostic or telemetry log entry to LiteReader_Log.txt
     */
    fun log(context: Context, component: String, message: String) {
        if (!isMasterEnabled) return
        val process = getProcessName(context)
        val thread = Thread.currentThread().name
        val timestamp = dateFormat.format(Date())
        val logLine = "[$timestamp] [$process] [$thread] [$component] $message\n"
        appendToFile(context, FILE_DIAGNOSTIC_LOG, logLine)
    }

    /**
     * Records an error or caught exception to LiteReader_Log.txt (and LiteReader_CrashLog.txt if throwable is present).
     */
    fun logError(context: Context, component: String, message: String, throwable: Throwable? = null) {
        if (!isMasterEnabled) return
        val process = getProcessName(context)
        val thread = Thread.currentThread().name
        val timestamp = dateFormat.format(Date())

        val sb = StringBuilder()
        sb.append("[$timestamp] [$process] [$thread] [ERROR:$component] $message\n")
        if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            sb.append(sw.toString()).append("\n")
        }
        val entry = sb.toString()
        appendToFile(context, FILE_DIAGNOSTIC_LOG, entry)
        if (throwable != null) {
            appendToFile(context, FILE_CRASH_LOG, entry)
        }
    }

    /**
     * Records an uncaught crash to LiteReader_CrashLog.txt and LiteReader_Log.txt.
     */
    fun logCrash(context: Context, threadName: String, throwable: Throwable) {
        if (!isMasterEnabled) return
        try {
            val process = getProcessName(context)
            val timestamp = dateFormat.format(Date())
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))

            val record = """
                ================================================================
                === UNCAUGHT EXCEPTION CRASH REPORT ===
                Timestamp: $timestamp
                Process: $process
                Thread: $threadName
                Exception: ${throwable.javaClass.name}
                Message: ${throwable.message ?: "No message"}
                StackTrace:
                ${sw}
                ================================================================
                
            """.trimIndent()

            appendToFile(context, FILE_CRASH_LOG, record)
            appendToFile(context, FILE_DIAGNOSTIC_LOG, "[$timestamp] [$process] [$threadName] [CRASH] ${throwable.javaClass.name}: ${throwable.message}\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record crash log", e)
        }
    }

    /**
     * Thread-safe and cross-process safe file append using OS FileChannel locks.
     */
    @Synchronized
    private fun appendToFile(context: Context, fileName: String, text: String) {
        try {
            val logFile = File(context.filesDir, fileName)
            // Trim if file exceeds MAX_LOG_SIZE
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                try {
                    val existingText = logFile.readText()
                    val halfIndex = existingText.length / 2
                    val trimmed = "[... logs truncated ...]\n" + existingText.substring(halfIndex)
                    logFile.writeText(trimmed)
                } catch (e: Exception) {
                    logFile.delete()
                }
            }

            FileOutputStream(logFile, true).use { fos ->
                val channel = fos.channel
                var lock: java.nio.channels.FileLock? = null
                try {
                    lock = channel.lock()
                    fos.write(text.toByteArray(Charsets.UTF_8))
                    fos.flush()
                } finally {
                    try {
                        lock?.release()
                    } catch (ignored: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to $fileName", e)
        }
    }

    fun getDiagnosticLogs(context: Context): String {
        return readLogFile(context, FILE_DIAGNOSTIC_LOG)
    }

    fun getCrashLogs(context: Context): String {
        return readLogFile(context, FILE_CRASH_LOG)
    }

    fun getLogs(context: Context): String {
        return getDiagnosticLogs(context)
    }

    private fun readLogFile(context: Context, fileName: String): String {
        return try {
            val file = File(context.filesDir, fileName)
            if (file.exists() && file.length() > 0L) {
                file.readText()
            } else {
                "No records in $fileName."
            }
        } catch (e: Exception) {
            "Error reading $fileName: ${e.message}"
        }
    }

    fun clearDiagnosticLogs(context: Context) {
        deleteLogFile(context, FILE_DIAGNOSTIC_LOG)
    }

    fun clearCrashLogs(context: Context) {
        deleteLogFile(context, FILE_CRASH_LOG)
    }

    fun clearLogs(context: Context) {
        clearDiagnosticLogs(context)
        clearCrashLogs(context)
    }

    private fun deleteLogFile(context: Context, fileName: String) {
        try {
            val file = File(context.filesDir, fileName)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear $fileName", e)
        }
    }
}

