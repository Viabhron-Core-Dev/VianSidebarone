package com.example.core

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

/**
 * HandleService: Persistent resident foreground service in the `:core` process.
 * Manages floating trigger handles, network speed monitor with dynamic status-bar icon,
 * and call recorder sensor state.
 */
class HandleService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var handleManager: HandleManager
    private lateinit var dynamicSpeedIconGenerator: DynamicSpeedIconGenerator
    private lateinit var notificationManager: NotificationManager

    private var netSpeedManager: NetSpeedManager? = null
    private val activeHandleViews = mutableListOf<TriggerHandleView>()

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(HandleManager.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private var isSpeedMonitorEnabled = true
    private var isScreenOn = true

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    netSpeedManager?.setScreenState(true)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    netSpeedManager?.setScreenState(false)
                }
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "vian_core_service_channel"
        const val NOTIFICATION_ID = 1001
        const val KEY_NET_SPEED_ENABLED = "net_speed_enabled"
        const val ACTION_RELOAD_HANDLES = "com.example.action.RELOAD_HANDLES"
    }

    override fun onCreate() {
        super.onCreate()
        handleManager = HandleManager.getInstance(this)
        dynamicSpeedIconGenerator = DynamicSpeedIconGenerator(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()

        // Start Foreground immediately
        val initialNotification = buildNotification("0", "B", "0 B/s", "0 B/s")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        // Register Screen On/Off receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenStateReceiver, filter)

        // Register Preference Listener
        prefs.registerOnSharedPreferenceChangeListener(this)

        // Initialize Net Speed Monitor
        isSpeedMonitorEnabled = prefs.getBoolean(KEY_NET_SPEED_ENABLED, true)
        setupNetSpeedManager()

        // Attach Active Handles
        attachHandles()

        // Start Call Recorder listener if enabled
        CallRecorderManager.getInstance(this).startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RELOAD_HANDLES) {
            attachHandles()
        }
        return START_STICKY
    }

    private fun setupNetSpeedManager() {
        if (isSpeedMonitorEnabled) {
            if (netSpeedManager == null) {
                netSpeedManager = NetSpeedManager { speedData ->
                    if (isScreenOn) {
                        updateSpeedNotification(speedData)
                    }
                }
            }
            netSpeedManager?.start()
        } else {
            netSpeedManager?.stop()
            netSpeedManager = null
            // Update to a static standby notification
            val standbyNotification = buildNotification("0", "B", "Standby", "Net speed disabled")
            notificationManager.notify(NOTIFICATION_ID, standbyNotification)
        }
    }

    private fun updateSpeedNotification(speedData: SpeedData) {
        try {
            val notification = buildNotification(
                speedData.downValue,
                speedData.downUnit,
                "↓ ${speedData.downFormatted}   ↑ ${speedData.upFormatted}",
                "VianSide Resident Service Active"
            )
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            LogKeeper.logError(this, "HandleService", "Failed to update notification", e)
        }
    }

    private fun buildNotification(
        speedVal: String,
        speedUnit: String,
        contentText: String,
        subText: String
    ): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val icon = dynamicSpeedIconGenerator.generateSpeedIcon(speedVal, speedUnit)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle("VianSide Core")
            .setContentText(contentText)
            .setSubText(subText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VianSide Core Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps edge gesture handles and internet speed monitor active."
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun attachHandles() {
        // Remove existing attached views
        detachHandles()

        val configs = handleManager.getActiveHandles()
        for (config in configs) {
            val handleView = TriggerHandleView(this, config) { actionKey, handleConfig ->
                handleGestureAction(actionKey, handleConfig)
            }
            handleView.attachToWindow()
            activeHandleViews.add(handleView)
        }
    }

    private fun detachHandles() {
        for (view in activeHandleViews) {
            view.detachFromWindow()
        }
        activeHandleViews.clear()
    }

    private fun handleGestureAction(actionKey: String, handleConfig: HandleConfig) {
        when (actionKey) {
            HandleManager.ACTION_OPEN_SIDEBAR -> {
                // Dispatch intent for Sidebar trigger (to be consumed by :sidebar process in subsequent steps)
                val intent = Intent("com.example.action.OPEN_SIDEBAR").apply {
                    putExtra("handle_id", handleConfig.id)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
                sendBroadcast(intent)
            }
            HandleManager.ACTION_NONE -> {
                // No action
            }
            else -> {
                val intent = Intent("com.example.action.TRIGGER_ACTION").apply {
                    putExtra("action_key", actionKey)
                    putExtra("handle_id", handleConfig.id)
                }
                sendBroadcast(intent)
            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == null) return
        if (key.startsWith("handle_") || key == HandleManager.KEY_HANDLES_COUNT) {
            attachHandles()
        } else if (key == KEY_NET_SPEED_ENABLED) {
            isSpeedMonitorEnabled = prefs.getBoolean(KEY_NET_SPEED_ENABLED, true)
            setupNetSpeedManager()
        } else if (key == CallRecorderManager.KEY_CALL_RECORDER_ENABLED) {
            if (CallRecorderManager.getInstance(this).isEnabled()) {
                CallRecorderManager.getInstance(this).startListening()
            } else {
                CallRecorderManager.getInstance(this).stopListening()
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) {
            dynamicSpeedIconGenerator.onTrimMemory()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            // Ignored if already unregistered
        }

        netSpeedManager?.stop()
        netSpeedManager = null

        CallRecorderManager.getInstance(this).stopListening()
        detachHandles()
        dynamicSpeedIconGenerator.onTrimMemory()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
