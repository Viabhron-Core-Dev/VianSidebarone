package com.example.core

import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogKeeper {
    var isEnabled = true
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var appContext: android.content.Context? = null
    
    private const val NORMAL_LOG_FILE_NAME = "LiteReader_Log.txt"
    private const val CRASH_LOG_FILE_NAME = "LiteReader_CrashLog.txt"
    private const val MAX_LOG_FILE_SIZE = 1 * 1024 * 1024L // 1 MB limit for normal log

    fun initialize(context: android.content.Context) {
        appContext = context.applicationContext
        cleanLegacyLogFiles()
        
        if (!isEnabled) return
        if (defaultHandler == null) {
            defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                saveCrashLog(thread, throwable)
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    fun getTargetLogDirectory(context: android.content.Context? = null): File? {
        val ctx = context?.applicationContext ?: appContext
        return ctx?.filesDir
    }

    private fun cleanLegacyLogFiles() {
        try {
            val dir = getTargetLogDirectory() ?: return
            val legacyFiles = dir.listFiles { file ->
                val name = file.name
                (name.startsWith("LiteReader_Log_") || name.startsWith("LiteReader_CrashLog_")) &&
                    name != NORMAL_LOG_FILE_NAME && name != CRASH_LOG_FILE_NAME
            }
            legacyFiles?.forEach { file ->
                try {
                    file.delete()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun writeLog(tag: String, message: String) {
        if (!isEnabled) return
        try {
            val dir = getTargetLogDirectory() ?: return
            val logFile = File(dir, NORMAL_LOG_FILE_NAME)

            // Roll over if exceeds maximum file size to keep low memory/disk footprint
            if (logFile.exists() && logFile.length() > MAX_LOG_FILE_SIZE) {
                try {
                    val lines = logFile.readLines()
                    if (lines.size > 200) {
                        val retained = lines.takeLast(100)
                        logFile.writeText("--- [Log Rolled Over] ---\n" + retained.joinToString("\n") + "\n")
                    } else {
                        logFile.writeText("")
                    }
                } catch (e: Exception) {
                    logFile.writeText("")
                }
            }

            val writer = FileWriter(logFile, true)
            val timeExact = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            writer.appendLine("--- [$timeExact] [$tag] ---")
            writer.appendLine(message)
            writer.appendLine("")
            writer.flush()
            writer.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    private fun saveCrashLog(thread: Thread, throwable: Throwable) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val dir = getTargetLogDirectory() ?: return
            val crashFile = File(dir, CRASH_LOG_FILE_NAME)

            val writer = FileWriter(crashFile, false) // Overwrite to keep strictly 1 crash file
            writer.appendLine("==========================================")
            writer.appendLine("           LiteReader Crash Log           ")
            writer.appendLine("==========================================")
            writer.appendLine("Timestamp: $timestamp")
            writer.appendLine("Thread: ${thread.name}")
            writer.appendLine("Exception: ${throwable.javaClass.name}")
            writer.appendLine("Message: ${throwable.message}")
            writer.appendLine("---------------- Stack Trace ----------------")
            writer.appendLine(Log.getStackTraceString(throwable))
            writer.appendLine("==========================================")
            writer.flush()
            writer.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
