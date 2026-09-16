package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import com.example.feature.system_hub.VianSideAccessibilityService

class AppTrackerOpenerActivity : Activity() {
    private var packageNames = arrayListOf<String>()
    private var currentIndex = 0
    private var isAutoForceStop = false
    private var hasStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val prefs = getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        isAutoForceStop = prefs.getBoolean("app_tracker_auto_force_stop", false)
        
        packageNames = intent.getStringArrayListExtra("packages") ?: arrayListOf()
        if (packageNames.isEmpty()) {
            finish()
            return
        }
        
        if (isAutoForceStop) {
            VianSideAccessibilityService.isForceStopping = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (packageNames.isEmpty() || isFinishing || isDestroyed) {
            return
        }
        // Advance to next app whenever we resume (initial start or return from previous Settings page)
        openNext()
    }

    private fun openNext() {
        if (currentIndex < packageNames.size) {
            val pkg = packageNames[currentIndex]
            currentIndex++
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$pkg")
                }
                startActivity(intent)
            } catch (e: Exception) {
                // If this package fails to open, immediately proceed to the next
                openNext()
            }
        } else {
            if (isAutoForceStop) {
                VianSideAccessibilityService.isForceStopping = false
            }
            finish()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isAutoForceStop) {
            VianSideAccessibilityService.isForceStopping = false
        }
    }
}
