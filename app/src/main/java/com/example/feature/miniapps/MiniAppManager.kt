package com.example.feature.miniapps

import android.content.Context
import android.content.Intent
import com.example.core.FloatingWindow
import com.example.core.FloatingWindowManager

object MiniAppManager {
    fun toggleApp(context: Context, pageType: String) {
        val windows = FloatingWindowManager.activeWindows
        
        // Handle specific standalone windows
        val existing = windows.find { 
            (it is DictionaryFloatingWindow && pageType == "dictionary")
        }
        
        if (existing != null) {
            FloatingWindowManager.removeWindow(existing)
            return
        }

        when (pageType) {
            "dictionary" -> {
                FloatingWindowManager.addWindow(DictionaryFloatingWindow(context))
            }
            else -> {
                // Forward generic PageWindows to the PageWindowService wrapper
                val intent = Intent(context, com.example.service.PageWindowService::class.java)
                intent.action = "TOGGLE"
                intent.putExtra("PAGE_TYPE", pageType)
                context.startService(intent)
            }
        }
    }
}
