package com.example

import android.app.Application
import android.util.Log
import com.example.core.LogKeeper

class App : Application() {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.example.core.FloatingWindowManager.onTrimMemory(level)
    }

    override fun onCreate() {
        super.onCreate()
        LogKeeper.initialize(this)
        com.example.core.IconCacheManager.init(this)
        
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
