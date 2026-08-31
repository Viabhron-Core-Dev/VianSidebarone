package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import android.view.WindowManager
import com.example.feature.sidebar.SidebarManager

class SidebarService : Service() {

    companion object {
        var instance: SidebarService? = null
            private set
    }

    private lateinit var sidebarManager: SidebarManager
    private lateinit var prefs: SharedPreferences
    private lateinit var windowManager: WindowManager

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        sidebarManager = SidebarManager(this, prefs, windowManager) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "com.example.ACTION_CLOSE_SIDEBAR" || action == "CLOSE_SIDEBAR") {
            sidebarManager.closeSidebar()
            return START_NOT_STICKY
        }
        sidebarManager.handleIntent(intent)
        return START_NOT_STICKY
    }

    fun closeSidebar() {
        sidebarManager.closeSidebar()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        sidebarManager.closeSidebar()
        if (instance == this) {
            instance = null
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        sidebarManager.onTrimMemory(level)
    }
}
