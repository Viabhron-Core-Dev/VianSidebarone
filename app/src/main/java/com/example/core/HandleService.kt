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
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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
    private var serviceStartTime: Long = System.currentTimeMillis()
    private var isFirstSpeedCallback = true
    private var isQueryingTodayData = false

    private var cachedTodayDataFormatted: String = "--"
    private var lastDailyQueryTimestamp: Long = 0L
    private var cachedDayOfYear: Int = -1

    private var lastLoggedSpeedValue = ""
    private var lastLoggedSpeedUnit = ""
    private var lastSpeedDiagnosticLogTimestamp = 0L

    private var lastLoggedIconValue = ""
    private var lastLoggedIconUnit = ""
    private var lastIconLogTimestamp = 0L

    private var lastLiveLoggedMode = ""
    private var lastLiveLoggedVal = ""
    private var lastLiveLoggedUnit = ""
    private var lastLiveLogTimestamp = 0L

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
        serviceStartTime = System.currentTimeMillis()
        LogKeeper.log(this, "StartupDiagnostics", "HandleService onCreate start (t=0ms)")

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        dynamicSpeedIconGenerator = DynamicSpeedIconGenerator(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager != null) {
            isScreenOn = powerManager.isInteractive
        }

        // 1. Build initial foreground notification with initial display state
        val tBuild = System.currentTimeMillis() - serviceStartTime
        LogKeeper.log(this, "StartupDiagnostics", "buildNotification start (t=${tBuild}ms)")
        val initialTitle = "Data: ${getTodayDataFormatted()} • ${formatElapsedTime()}"
        val initialIcon = dynamicSpeedIconGenerator.generateSpeedIcon("0", "kB/s")
        val initialNotification = buildNotification(initialIcon, initialTitle, "Down: 0 kB/s   Up: 0 kB/s")

        // 2. Start Foreground IMMEDIATELY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }
        val tFg = System.currentTimeMillis() - serviceStartTime
        LogKeeper.log(this, "StartupDiagnostics", "startForeground completed (t=${tFg}ms)")

        // 3. Initialize Net Speed Monitor immediately after entering foreground
        val tNetSetup = System.currentTimeMillis() - serviceStartTime
        LogKeeper.log(this, "StartupDiagnostics", "setupNetSpeedManager start (t=${tNetSetup}ms)")
        isSpeedMonitorEnabled = prefs.getBoolean(KEY_NET_SPEED_ENABLED, true)
        setupNetSpeedManager()
        val tNetStarted = System.currentTimeMillis() - serviceStartTime
        LogKeeper.log(this, "StartupDiagnostics", "NetSpeedManager.start completed (t=${tNetStarted}ms)")

        // 4. Secondary component registrations
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenStateReceiver, filter)
        prefs.registerOnSharedPreferenceChangeListener(this)

        handleManager = HandleManager.getInstance(this)
        attachHandles()
        CallRecorderManager.getInstance(this).startListening()

        // 5. Non-essential diagnostic logging moved after service and speed monitor are active
        val metrics = resources.displayMetrics
        val calc24dp = (24f * metrics.density).toInt()
        LogKeeper.log(
            this,
            "IconDiagnostics",
            "Display Metrics: density=${metrics.density}, densityDpi=${metrics.densityDpi}, " +
            "widthPixels=${metrics.widthPixels}, heightPixels=${metrics.heightPixels}, " +
            "scaledDensity=${metrics.scaledDensity}, 24dp_target=${calc24dp}px"
        )
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
            val standbyTitle = "Data: ${getTodayDataFormatted()} • ${formatElapsedTime()}"
            val standbyIcon = dynamicSpeedIconGenerator.generateSpeedIcon("0", "kB/s")
            val standbyNotification = buildNotification(standbyIcon, standbyTitle, "Down: --   Up: --")
            notificationManager.notify(NOTIFICATION_ID, standbyNotification)
        }
    }

    private fun updateSpeedNotification(speedData: SpeedData) {
        try {
            val speedVal = speedData.downValue
            val speedUnit = speedData.downUnit

            // Live Resource selection:
            // if displayed value == "108" and unit == "kB/s":
            //   use the pre-rendered 108 kB/s drawable
            // else:
            //   use the existing runtime Icon.createWithBitmap renderer
            val isResource = speedVal == "108" && speedUnit.equals("kB/s", ignoreCase = true)

            val selectedMode = if (isResource) "RESOURCE" else "RUNTIME"
            val resName = if (isResource) "ic_stat_speed_108_k" else "none"

            val icon = if (isResource) {
                Icon.createWithResource(this, R.drawable.ic_stat_speed_108_k)
            } else {
                dynamicSpeedIconGenerator.generateSpeedIcon(speedVal, speedUnit)
            }

            val titleText = "Data: ${getTodayDataFormatted()} • ${formatElapsedTime()}"
            val contentText = "Down: ${speedData.downFormatted}   Up: ${speedData.upFormatted}"

            val notification = buildNotification(icon, titleText, contentText)
            notificationManager.notify(NOTIFICATION_ID, notification)

            val now = System.currentTimeMillis()
            if (isFirstSpeedCallback) {
                isFirstSpeedCallback = false
                val tFirst = now - serviceStartTime
                LogKeeper.log(
                    this,
                    "StartupDiagnostics",
                    "first speed callback (t=${tFirst}ms): initial 0 notification replaced by live speed notification (val='$speedVal', unit='$speedUnit', mode=$selectedMode)"
                )
            }

            // Minimal diagnostics around the LIVE update path:
            // - displayed speed value
            // - displayed unit
            // - selected icon mode: RESOURCE or RUNTIME
            // - resource name when RESOURCE
            // - confirmation that setSmallIcon received the selected icon
            val modeChanged = (selectedMode != lastLiveLoggedMode)
            val valChanged = (speedVal != lastLiveLoggedVal || speedUnit != lastLiveLoggedUnit)
            val timeElapsed = (now - lastLiveLogTimestamp) > 10000L

            if (isResource || modeChanged || valChanged || timeElapsed) {
                lastLiveLoggedMode = selectedMode
                lastLiveLoggedVal = speedVal
                lastLiveLoggedUnit = speedUnit
                lastLiveLogTimestamp = now
                LogKeeper.log(
                    this,
                    "IconDiagnostics",
                    "LiveUpdate -> displayedVal='$speedVal', displayedUnit='$speedUnit', mode=$selectedMode, resName=$resName, setSmallIconReceived=true, notifyExecuted=true"
                )
            }
        } catch (e: Exception) {
            LogKeeper.logError(this, "HandleService", "Failed to update notification", e)
        }
    }

    private fun getTodayDataFormatted(): String {
        val currentDay = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        val now = System.currentTimeMillis()
        // Non-blocking asynchronous query if day rolled over or 30 seconds elapsed
        if (currentDay != cachedDayOfYear || (now - lastDailyQueryTimestamp) > 30000L) {
            lastDailyQueryTimestamp = now
            cachedDayOfYear = currentDay
            queryTodayDataUsageAsync()
        }
        return cachedTodayDataFormatted
    }

    private fun queryTodayDataUsageAsync() {
        if (isQueryingTodayData) return
        isQueryingTodayData = true
        Thread {
            try {
                val bytes = DailyDataUsageHelper.getTodayDataUsageBytes(this@HandleService)
                val formatted = DailyDataUsageHelper.formatDataBytes(bytes)
                synchronized(this@HandleService) {
                    cachedTodayDataFormatted = formatted
                }
            } catch (_: Exception) {
            } finally {
                isQueryingTodayData = false
            }
        }.start()
    }

    private fun formatElapsedTime(): String {
        val elapsedMillis = System.currentTimeMillis() - serviceStartTime
        val elapsedMin = (elapsedMillis / 60000L).coerceAtLeast(0L)
        return if (elapsedMin < 60) {
            "${elapsedMin}m"
        } else {
            val hours = elapsedMin / 60
            val mins = elapsedMin % 60
            if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
        }
    }

    private fun buildNotification(
        icon: Icon,
        titleText: String,
        contentText: String
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

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(titleText)
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
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
