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
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.feature.sidebar.SidebarManager

/**
 * HandleService: Persistent resident foreground service in the Main process (com.example).
 * Manages floating trigger handles, network speed monitor with dynamic status-bar icon,
 * and call recorder sensor state.
 */
class HandleService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var handleManager: HandleManager
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

    private var lastLiveLoggedVal = ""
    private var lastLiveLoggedUnit = ""
    private var lastLiveLogTimestamp = 0L

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    LogKeeper.logLifecycle(context, "HandleService", "SCREEN_ON", "Handles attached, NetSpeed resumed")
                    netSpeedManager?.setScreenState(true)
                    attachHandles()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    LogKeeper.logLifecycle(context, "HandleService", "SCREEN_OFF", "Handles detached, Sidebar closed, NetSpeed suspended, CallSensor active")
                    netSpeedManager?.setScreenState(false)
                    SidebarManager.getInstance(this@HandleService).closeContainer()
                    detachHandles()
                }
                Intent.ACTION_USER_PRESENT -> {
                    isScreenOn = true
                    LogKeeper.logLifecycle(context, "HandleService", "USER_PRESENT", "Keyguard unlocked - ensuring handles attached")
                    if (activeHandleViews.isEmpty()) {
                        attachHandles()
                    }
                }
                ACTION_RELOAD_HANDLES -> {
                    LogKeeper.logLifecycle(context, "HandleService", "RELOAD_HANDLES", "Reloading handles on configuration change")
                    if (isScreenOn && Settings.canDrawOverlays(this@HandleService)) {
                        attachHandles()
                    }
                }
                OverlaySyncManager.ACTION_SYNC_PREF -> {
                    LogKeeper.logLifecycle(context, "HandleService", "ACTION_SYNC_PREF", "Syncing preferences from Heavy process")
                    handleSyncPrefIntent(intent)
                }
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "vian_core_service_channel"
        const val NOTIFICATION_ID = 1001
        const val KEY_NET_SPEED_ENABLED = "net_speed_enabled"
        const val ACTION_RELOAD_HANDLES = "com.example.action.RELOAD_HANDLES"

        fun startIfConfigured(context: Context) {
            try {
                val prefs = context.getSharedPreferences(HandleManager.PREFS_NAME, Context.MODE_PRIVATE)
                val autoStart = prefs.getBoolean("auto_start_on_boot", true)
                val canOverlay = Settings.canDrawOverlays(context)
                LogKeeper.log(
                    context,
                    "HandleService",
                    "startIfConfigured called: autoStart=$autoStart, canDrawOverlays=$canOverlay"
                )
                if (autoStart && canOverlay) {
                    LogKeeper.log(context, "HandleService", "startIfConfigured: launching service")
                    start(context)
                } else {
                    LogKeeper.log(
                        context,
                        "HandleService",
                        "startIfConfigured skipped (autoStart=$autoStart, canOverlay=$canOverlay)"
                    )
                }
            } catch (e: Exception) {
                LogKeeper.logError(context, "HandleService", "Failed to startIfConfigured", e)
            }
        }

        fun start(context: Context) {
            val serviceIntent = Intent(context, HandleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }

        fun stop(context: Context) {
            val serviceIntent = Intent(context, HandleService::class.java)
            context.stopService(serviceIntent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceStartTime = System.currentTimeMillis()
        LogKeeper.logLifecycle(this, "HandleService", "CREATED")

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager != null) {
            isScreenOn = powerManager.isInteractive
        }

        // 1. Build initial foreground notification with initial display state
        val initialTitle = "Data: ${getTodayDataFormatted()} • ${formatElapsedTime()}"
        val initialIconResId = SpeedIconProvider.resolve("0", "kB/s").resId
        val initialNotification = buildNotification(initialIconResId, initialTitle, "Down: 0 kB/s   Up: 0 kB/s")

        // 2. Start Foreground IMMEDIATELY to satisfy system startForegroundService contract
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
            LogKeeper.logLifecycle(this, "HandleService", "FOREGROUND_STARTED", "Primary startForeground succeeded (icon=$initialIconResId)")
        } catch (e: Exception) {
            LogKeeper.logError(this, "HandleService", "Primary startForeground failed; retrying with guaranteed system fallback icon", e)
            val fallbackNotification = buildNotification(R.drawable.ic_speed, initialTitle, "Down: 0 kB/s   Up: 0 kB/s")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    fallbackNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, fallbackNotification)
            }
            LogKeeper.logLifecycle(this, "HandleService", "FOREGROUND_STARTED", "Fallback startForeground succeeded with R.drawable.ic_speed")
        }

        // 3. Validate runtime configuration
        val autoStart = prefs.getBoolean("auto_start_on_boot", true)
        if (!autoStart) {
            LogKeeper.log(this, "HandleService", "Stopping service in onCreate: auto_start_on_boot=false")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // 4. Initialize Net Speed Monitor immediately after entering foreground
        isSpeedMonitorEnabled = prefs.getBoolean(KEY_NET_SPEED_ENABLED, true)
        setupNetSpeedManager()

        // 5. Secondary component registrations
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(ACTION_RELOAD_HANDLES)
            addAction(OverlaySyncManager.ACTION_SYNC_PREF)
        }
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        prefs.registerOnSharedPreferenceChangeListener(this)

        handleManager = HandleManager.getInstance(this)
        if (isScreenOn && Settings.canDrawOverlays(this)) {
            attachHandles()
        }
        CallRecorderManager.getInstance(this).startListening()
        SidebarManager.getInstance(this).registerReceiver(this)
        com.example.feature.element.ElementActionRegistry.getInstance(this).registerReceiver(this)

        // 6. Notify MainProcessRecovery of successful startup to monitor stable runtime
        MainProcessRecovery.onServiceStarted(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager != null) {
            isScreenOn = powerManager.isInteractive
        }
        val autoStart = prefs.getBoolean("auto_start_on_boot", true)
        if (!autoStart) {
            LogKeeper.log(this, "HandleService", "Stopping service in onStartCommand: auto_start_on_boot=false")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent == null) {
            LogKeeper.logLifecycle(this, "HandleService", "SERVICE_RECREATED_STICKY", "Recreated by Android framework after process termination (START_STICKY)")
            if (isScreenOn && Settings.canDrawOverlays(this)) {
                attachHandles()
            }
        } else {
            when (intent.action) {
                OverlaySyncManager.ACTION_SYNC_PREF -> {
                    handleSyncPrefIntent(intent)
                }
                ACTION_RELOAD_HANDLES -> {
                    if (isScreenOn && Settings.canDrawOverlays(this)) {
                        attachHandles()
                    }
                }
                else -> {
                    if (isScreenOn && Settings.canDrawOverlays(this)) {
                        attachHandles()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        LogKeeper.logLifecycle(this, "HandleService", "TASK_REMOVED", "App task removed from recent apps - ensuring service remains active")
        startIfConfigured(this)
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
            val standbyIconResId = SpeedIconProvider.resolve("0", "kB/s").resId
            val standbyNotification = buildNotification(standbyIconResId, standbyTitle, "Down: --   Up: --")
            notificationManager.notify(NOTIFICATION_ID, standbyNotification)
        }
    }

    private fun updateSpeedNotification(speedData: SpeedData) {
        try {
            val speedVal = speedData.downValue
            val speedUnit = speedData.downUnit

            // Final Pre-Rendered Resource Lookup:
            // speed value -> display formatting -> resource ID lookup -> Notification.Builder.setSmallIcon(resourceId)
            val iconInfo = SpeedIconProvider.resolve(speedVal, speedUnit)
            val selectedResId = iconInfo.resId
            val selectedResName = iconInfo.resName

            val titleText = "Data: ${getTodayDataFormatted()} • ${formatElapsedTime()}"
            val contentText = "Down: ${speedData.downFormatted}   Up: ${speedData.upFormatted}"

            val notification = buildNotification(selectedResId, titleText, contentText)
            notificationManager.notify(NOTIFICATION_ID, notification)

            val now = System.currentTimeMillis()
            if (isFirstSpeedCallback) {
                isFirstSpeedCallback = false
                val tFirst = now - serviceStartTime
                LogKeeper.log(
                    this,
                    "StartupDiagnostics",
                    "first speed callback (t=${tFirst}ms): initial notification replaced by live speed notification (val='$speedVal', unit='$speedUnit', resName=$selectedResName)"
                )
            }

            // Concise diagnostics:
            // displayed value, displayed unit, selected resource name, and confirmation that setSmallIcon received the resource ID
            val valChanged = (speedVal != lastLiveLoggedVal || speedUnit != lastLiveLoggedUnit)
            val timeElapsed = (now - lastLiveLogTimestamp) > 10000L

            if (valChanged || timeElapsed) {
                lastLiveLoggedVal = speedVal
                lastLiveLoggedUnit = speedUnit
                lastLiveLogTimestamp = now
                LogKeeper.log(
                    this,
                    "IconDiagnostics",
                    "LiveUpdate -> displayedVal='$speedVal', displayedUnit='$speedUnit', mode=RESOURCE, resName=$selectedResName, setSmallIconReceived=true, notifyExecuted=true"
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

    private val validatedIconCache = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    private fun resolveSafeIconResId(candidateResId: Int): Int {
        if (candidateResId > 0 && isValidDrawableResource(candidateResId)) {
            return candidateResId
        }
        val speedZero = SpeedIconProvider.resolve("0", "kB/s").resId
        if (speedZero > 0 && isValidDrawableResource(speedZero)) {
            return speedZero
        }
        return R.drawable.ic_speed
    }

    private fun isValidDrawableResource(resId: Int): Boolean {
        if (resId <= 0) return false
        return validatedIconCache.getOrPut(resId) {
            try {
                // Actually verify that the Android framework can decode and create this drawable
                val drawable = ContextCompat.getDrawable(this, resId)
                drawable != null
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun buildNotification(
        iconResId: Int,
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

        val safeIcon = resolveSafeIconResId(iconResId)
        if (safeIcon != iconResId) {
            LogKeeper.logError(
                this,
                "HandleService",
                "Unsafe/unusable icon candidate detected (resId=$iconResId); substituted safe fallback icon (resId=$safeIcon)"
            )
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(safeIcon)
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

    private fun handleSyncPrefIntent(intent: Intent) {
        val key = intent.getStringExtra(OverlaySyncManager.EXTRA_KEY) ?: ""
        val value = intent.getStringExtra(OverlaySyncManager.EXTRA_VALUE) ?: ""
        val type = intent.getStringExtra(OverlaySyncManager.EXTRA_TYPE) ?: ""

        when (type) {
            "STRING" -> prefs.edit().putString(key, value).commit()
            "INT" -> prefs.edit().putInt(key, value.toIntOrNull() ?: 0).commit()
            "BOOLEAN" -> prefs.edit().putBoolean(key, value.toBoolean()).commit()
            "FLOAT" -> prefs.edit().putFloat(key, value.toFloatOrNull() ?: 0f).commit()
            "REMOVE" -> prefs.edit().remove(key).commit()
            "SYNC_HANDLE" -> {
                val bundle = intent.extras
                if (bundle != null) {
                    val editor = prefs.edit()
                    for (k in bundle.keySet()) {
                        if (k.startsWith("handle_") || k == HandleManager.KEY_HANDLE_IDS || k == HandleManager.KEY_HANDLES_COUNT) {
                            when (val v = bundle.get(k)) {
                                is String -> editor.putString(k, v)
                                is Boolean -> editor.putBoolean(k, v)
                                is Int -> editor.putInt(k, v)
                                is Float -> editor.putFloat(k, v)
                            }
                        }
                    }
                    editor.commit()
                }
            }
        }
        if (isScreenOn && Settings.canDrawOverlays(this)) {
            attachHandles()
        }
    }

    private fun attachHandles() {
        // Remove existing attached views
        detachHandles()

        if (!Settings.canDrawOverlays(this)) {
            LogKeeper.log(this, "HandleService", "attachHandles skipped: SYSTEM_ALERT_WINDOW permission not granted")
            return
        }

        val configs = handleManager.getActiveHandles()
        for (config in configs) {
            val handleView = TriggerHandleView(this, config) { actionKey, gesture, handleConfig ->
                handleGestureAction(actionKey, gesture, handleConfig)
            }
            try {
                handleView.attachToWindow()
                activeHandleViews.add(handleView)
            } catch (e: Exception) {
                LogKeeper.logError(this, "HandleService", "Failed to attach handle view ${config.id}", e)
            }
        }
    }

    private fun detachHandles() {
        for (view in activeHandleViews) {
            try {
                view.detachFromWindow()
            } catch (e: Exception) {
                // Ignore if already detached
            }
        }
        activeHandleViews.clear()
    }

    private fun handleGestureAction(actionKey: String, gesture: String, handleConfig: HandleConfig) {
        val containerId = HandleManager.getContainerId(handleConfig.id, gesture)
        when (val target = HandleManager.resolveGestureTarget(handleConfig.id, gesture, actionKey)) {
            is GestureTarget.Container -> {
                // Synchronously resolve active Sidebar container in Main process
                SidebarManager.getInstance(this).openTarget(handleConfig.id, gesture, target)

                // Dispatch broadcast for Sidebar trigger with independent container identity
                val intent = Intent("com.example.action.OPEN_SIDEBAR").apply {
                    putExtra("handle_id", handleConfig.id)
                    putExtra("gesture", gesture)
                    putExtra("container_id", target.containerId)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
                sendBroadcast(intent)
            }
            is GestureTarget.None -> {
                // No action
            }
            is GestureTarget.Action -> {
                val intent = Intent("com.example.action.TRIGGER_ACTION").apply {
                    putExtra("action_key", target.actionKey)
                    putExtra("handle_id", handleConfig.id)
                    putExtra("gesture", gesture)
                    putExtra("container_id", containerId)
                }
                sendBroadcast(intent)
            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == null) return
        if (key.startsWith("handle_") || key == HandleManager.KEY_HANDLES_COUNT) {
            if (isScreenOn) {
                attachHandles()
            }
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

    override fun onDestroy() {
        super.onDestroy()
        LogKeeper.logLifecycle(this, "HandleService", "DESTROYED")
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            // Ignored if already unregistered
        }

        netSpeedManager?.stop()
        netSpeedManager = null

        CallRecorderManager.getInstance(this).stopListening()
        SidebarManager.getInstance(this).dismiss()
        SidebarManager.getInstance(this).unregisterReceiver(this)
        com.example.feature.element.ElementActionRegistry.getInstance(this).unregisterReceiver(this)
        detachHandles()
        MainProcessRecovery.onServiceDestroyed()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
