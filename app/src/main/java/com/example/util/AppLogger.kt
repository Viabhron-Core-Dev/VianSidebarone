package com.example.util

import android.content.Context
import java.io.File
import com.example.core.LogKeeper

object AppLogger {
    fun d(tag: String, msg: String) {
        LogKeeper.writeLog(tag, msg)
        android.util.Log.d(tag, msg)
    }

    fun export(context: Context): File {
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val f = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "litereader_logs_export_$ts.txt")
        f.writeText("Exporting from AppLogger.\nPlease check LiteReader_Log.txt and LiteReader_CrashLog.txt in Downloads.")
        return f
    }
}
