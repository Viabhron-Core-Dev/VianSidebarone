package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.example.feature.sidebar.SidebarAppsManager
import com.example.feature.sidebar.SidebarItem
import com.example.feature.sidebar.BubbleDrawable
import com.example.core.HandleService

class FloatingTriggerService : Service() {
    companion object {
        var instance: FloatingTriggerService? = null
            private set
    }

    private lateinit var windowManager: WindowManager
    private val activeTriggers = mutableMapOf<String, View>()
    private lateinit var appsManager: SidebarAppsManager
    private lateinit var prefs: android.content.SharedPreferences

    fun setVisibility(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        activeTriggers.values.forEach { it.visibility = visibility }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        appsManager = SidebarAppsManager(this, prefs, CoroutineScope(Dispatchers.IO), "floating_trigger") {}
        appsManager.ensureLoaded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP_TRIGGER") {
            val targetId = intent.getStringExtra("TARGET_ID")
            if (targetId != null) {
                removeTrigger(targetId)
            }
            if (activeTriggers.isEmpty()) {
                stopSelf()
            }
            return START_NOT_STICKY
        }
        
        val targetId = intent?.getStringExtra("TARGET_ID") ?: return START_NOT_STICKY
        
        if (activeTriggers.containsKey(targetId)) {
            removeTrigger(targetId)
        } else {
            addTrigger(targetId)
        }
        
        return START_STICKY
    }
    
    private fun removeTrigger(targetId: String) {
        val view = activeTriggers.remove(targetId)
        if (view != null) {
            try { windowManager.removeView(view) } catch(e: Exception) {}
        }
    }

    private fun addTrigger(targetId: String) {
        val density = resources.displayMetrics.density
        val size = (56 * density).toInt()
        val imageView = ImageView(this).apply {
            setBackgroundResource(android.R.drawable.dialog_frame) // Or transparent with elevation
        }
        
        // Fetch the icon for targetId using appsManager
        val innerBmp = appsManager.getIconBitmap(targetId)
        imageView.setImageDrawable(BubbleDrawable(innerBmp))

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        val lastX = prefs.getInt("floating_trigger_${targetId}_x", 0)
        val lastY = prefs.getInt("floating_trigger_${targetId}_y", 200)

        val params = WindowManager.LayoutParams(
            size, size,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lastX
            y = lastY
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        imageView.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        executeAction(targetId)
                    } else {
                        prefs.edit()
                            .putInt("floating_trigger_${targetId}_x", params.x)
                            .putInt("floating_trigger_${targetId}_y", params.y)
                            .apply()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(imageView, params)
        activeTriggers[targetId] = imageView
    }
    
    private fun executeAction(targetId: String) {
        val parsed = appsManager.parseId(targetId)
        if (parsed is SidebarItem.App) {
            val intent = packageManager.getLaunchIntentForPackage(parsed.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { startActivity(intent) } catch (e: Exception) {}
            }
        } else if (parsed is SidebarItem.PageWindow) {
            val intent = Intent(this, com.example.service.PageWindowService::class.java)
            intent.action = "TOGGLE"
            intent.putExtra("PAGE_TYPE", parsed.pageType)
            startService(intent)
        } else if (parsed is SidebarItem.Link) {
            try {
                val intent = if (parsed.url.startsWith("intent:")) {
                    Intent.parseUri(parsed.url, Intent.URI_INTENT_SCHEME)
                } else {
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(parsed.url))
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {}
        }
        // Simplified execution for trigger bubbles (macros/actions could be added here)
    }

    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        for (view in activeTriggers.values) {
            try { windowManager.removeView(view) } catch(e: Exception) {}
        }
        activeTriggers.clear()
    }
}
