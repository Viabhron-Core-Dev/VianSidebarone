package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.appwidget.AppWidgetHost


object QuickTileHandler {
    fun handleQuickTileAction(context: Context, action: String) {}
}

object MediaVolumeHandler {
    fun handleVolumeAction(context: Context, stream: String, action: String) {}
    fun handleMediaAction(context: Context, action: String) {}
}

object DisplayHandler {
    fun handleDisplayAction(context: Context, action: String) {}
}

object AppWidgetHelper {
    fun launchAppWidgetPicker(context: Context) {}
    fun getHost(context: Context): AppWidgetHost = AppWidgetHost(context, 1024)
}

class VianSideAccessibilityService : android.accessibilityservice.AccessibilityService() {
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}
    fun performAction(action: String): Boolean = false
    companion object {
        var isForceStopping = false
        val instance: VianSideAccessibilityService? = null
    }
}

class FloatingReaderService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
