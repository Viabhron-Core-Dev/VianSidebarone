package com.example.feature.miniapps.browser


import android.app.Service
import android.content.Intent
import android.os.IBinder

class FloatingBrowserService : Service() {

    private val activeWindows = mutableListOf<FloatingBrowserWindowManager>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "OPEN_URL") {
            val url = intent.getStringExtra("URL")
            if (url != null) {
                val wm = FloatingBrowserWindowManager(this) { window ->
                    activeWindows.remove(window)
                    if (activeWindows.isEmpty()) {
                        stopSelf()
                    }
                }
                activeWindows.add(wm)
                wm.show(url)
            }
        }
        return START_NOT_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        val windowsToClose = activeWindows.toList()
        for (wm in windowsToClose) {
            wm.close()
        }
        activeWindows.clear()
    }
}
