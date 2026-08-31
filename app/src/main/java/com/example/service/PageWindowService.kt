package com.example.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.example.feature.miniapps.PageWindow
import com.example.core.FloatingWindowManager

class PageWindowService : Service() {
    
    companion object {
        var instance: PageWindowService? = null
            private set
    }

    private val windows = mutableMapOf<String, PageWindow>()

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pageType = intent?.getStringExtra("PAGE_TYPE") ?: return START_NOT_STICKY
        
        if (intent.action == "TOGGLE") {
            if (windows.containsKey(pageType)) {
                val window = windows[pageType]
                FloatingWindowManager.removeWindow(window!!)
                windows.remove(pageType)
            } else {
                val title = getTitleForPageType(pageType)
                val window = PageWindow(this, pageType, title)
                window.onClose = {
                    windows.remove(pageType)
                }
                windows[pageType] = window
                window.show()
            }
        } else if (intent.action == "CLOSE") {
            if (windows.containsKey(pageType)) {
                val window = windows[pageType]
                FloatingWindowManager.removeWindow(window!!)
                windows.remove(pageType)
            }
        }
        
        return START_NOT_STICKY
    }
    
    private fun getTitleForPageType(type: String): String {
        return when (type) {
            "calculator" -> "Calculator"
            "compass" -> "Compass"
            "scheduler" -> "Short Reminders"
            "notifications" -> "Notifications"
            "app_tracker" -> "App Tracker"
            "resources_tracker" -> "Resources Tracker"
            "file_explorer" -> "File Explorer"
            "local_terminal" -> "Local Terminal"
            "termux" -> "Termux (PRoot)"
            else -> "Window"
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        for (window in windows.values) {
            FloatingWindowManager.removeWindow(window)
        }
        windows.clear()
    }
}
