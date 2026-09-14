package com.example

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import com.example.core.FloatingWindowManager
import com.example.core.LogKeeper

/**
 * Application Entry Point.
 * Distinguishes between the resident Main process (com.example) and the on-demand Heavy process (:heavy).
 * Keeps Main startup ultra-lightweight with zero heavy UI or ML Kit initialization.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Initialize Zero-PII LogKeeper crash handler
        LogKeeper.init(this)

        val processName = getProcessNameCompat()
        val isHeavyProcess = processName?.endsWith(":heavy") == true

        if (isHeavyProcess) {
            // Heavy Process (:heavy): On-demand UI and heavy features. Keep initialization minimal.
            return
        }

        // Main Process (com.example): Resident lightweight daemon.
        // Keep strictly minimal; do not initialize heavy UI caches or databases.
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
        val processName = getProcessNameCompat()
        val isHeavyProcess = processName?.endsWith(":heavy") == true
        if (!isHeavyProcess) {
            FloatingWindowManager.getInstance(this).onTrimMemory(level)
        } else {
            com.example.feature.floating.HeavyFloatingHost.getInstance(this).onTrimMemory(level)
        }
    }
}
