package com.example.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LogKeeper: Zero-PII Crash & Exception Catcher.
 * Logs only Error types/codes, failed components, timestamps, code-path stack traces,
 * and crash state. NO content, NO credentials, NO PII.
 */
object LogKeeper {
    private const val TAG = "LogKeeper"
    private const val LOG_FILE_NAME = "crash_log.txt"
    private const val MAX_LOG_SIZE = 100 * 1024 // 100 KB max to conserve RAM/disk
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var isMasterEnabled = true

    fun init(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logException(context, "UncaughtException", "Thread[${thread.name}]", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun setMasterEnabled(enabled: Boolean) {
        isMasterEnabled = enabled
    }

    fun isEnabled(): Boolean = isMasterEnabled

    fun logError(context: Context, component: String, message: String, throwable: Throwable? = null) {
        if (!isMasterEnabled) return
        logException(context, "HandledError", component, throwable ?: Exception(message))
    }

    private fun logException(context: Context, errorType: String, component: String, throwable: Throwable) {
        if (!isMasterEnabled) return
        try {
            val logFile = File(context.filesDir, LOG_FILE_NAME)
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                logFile.delete()
            }
            
            FileWriter(logFile, true).use { fw ->
                PrintWriter(fw).use { pw ->
                    pw.println("=== ERROR RECORD ===")
                    pw.println("Timestamp: ${dateFormat.format(Date())}")
                    pw.println("Type: $errorType")
                    pw.println("Component: $component")
                    pw.println("StackTrace:")
                    throwable.printStackTrace(pw)
                    pw.println("====================")
                    pw.println()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log", e)
        }
    }

    fun getLogs(context: Context): String {
        return try {
            val logFile = File(context.filesDir, LOG_FILE_NAME)
            if (logFile.exists()) logFile.readText() else "No logs recorded."
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }
    }

    fun clearLogs(context: Context) {
        try {
            val logFile = File(context.filesDir, LOG_FILE_NAME)
            if (logFile.exists()) logFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear logs", e)
        }
    }
}
