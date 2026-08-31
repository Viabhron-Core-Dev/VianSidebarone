package com.example

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import com.example.core.LogKeeper

/**
 * Application Entry Point.
 * Keeps Process 1 (:core) startup ultra-lightweight with zero heavy UI or ML Kit initialization.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Initialize Zero-PII LogKeeper crash handler
        LogKeeper.init(this)

        val processName = getProcessNameCompat()
        val isCoreProcess = processName?.endsWith(":core") == true

        if (isCoreProcess) {
            // Process 1 (:core): Keep strictly minimal, do not initialize UI caches, databases, or ML models
            return
        }
    }

    private fun getProcessNameCompat(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            try {
                val pid = Process.myPid()
                val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
    }
}
