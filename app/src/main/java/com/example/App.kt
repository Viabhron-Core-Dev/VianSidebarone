package com.example

import android.app.Application
import android.os.Build
import android.util.Log
import com.example.core.LogKeeper

class App : Application() {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.example.core.FloatingWindowManager.onTrimMemory(level)
        com.example.core.DynamicSpeedIconGenerator.onTrimMemory(level)
    }

    override fun onCreate() {
        super.onCreate()
        LogKeeper.initialize(this)
        
        // Lazy initialize UI icon caches only in UI/sidebar processes to keep :core process lightweight
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            null
        }
        val isCoreProcess = processName?.endsWith(":core") == true
        if (!isCoreProcess) {
            com.example.core.IconCacheManager.init(this)
        }
        
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            try {
                val stackTrace = Log.getStackTraceString(exception)
                LogKeeper.writeLog("CRASH", "FATAL EXCEPTION in thread ${thread.name}: ${exception.message}\n$stackTrace")
            } catch (e: Exception) {
                Log.e("App", "Error writing crash log", e)
            }
            defaultHandler?.uncaughtException(thread, exception)
        }
    }
}
