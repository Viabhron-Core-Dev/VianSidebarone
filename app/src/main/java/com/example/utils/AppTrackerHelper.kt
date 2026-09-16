package com.example.utils

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import com.example.AppTrackerOpenerActivity
import com.example.feature.sidebar.TrackedAppInfo
import java.util.concurrent.TimeUnit

object AppTrackerHelper {

    fun isAppTrackerConfigured(context: Context): Boolean {
        val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        if (PageManager.isPageTypePresent(prefs, "app_tracker")) return true
        val appPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return PageManager.isPageTypePresent(appPrefs, "app_tracker")
    }

    fun checkUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun getRecentApps(context: Context): List<TrackedAppInfo> {
        if (!checkUsageStatsPermission(context)) return emptyList()

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyList()

        val endTime = System.currentTimeMillis()
        val startTime = endTime - TimeUnit.HOURS.toMillis(24)

        val appLastUsed = mutableMapOf<String, Long>()

        // 1. Primary retrieval via UsageEvents
        try {
            val events = usageStatsManager.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    appLastUsed[event.packageName] = event.timeStamp
                }
            }
        } catch (e: Exception) {}

        // 2. Fallback / supplement via queryUsageStats in case events were pruned
        try {
            val statsList = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_BEST, startTime, endTime)
            if (statsList != null) {
                for (stats in statsList) {
                    val lastTime = stats.lastTimeUsed
                    if (lastTime > 0) {
                        val current = appLastUsed[stats.packageName] ?: 0L
                        if (lastTime > current) {
                            appLastUsed[stats.packageName] = lastTime
                        }
                    }
                }
            }
        } catch (e: Exception) {}

        val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        val whitelist = prefs.getStringSet("app_tracker_whitelist_current", emptySet()) ?: emptySet()

        val pm = context.packageManager
        val trackedApps = mutableListOf<TrackedAppInfo>()

        for ((packageName, lastUsed) in appLastUsed) {
            if (packageName == context.packageName) continue
            if (packageName.contains("launcher", ignoreCase = true)) continue
            if (whitelist.contains(packageName)) continue

            try {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                val appName = pm.getApplicationLabel(appInfo).toString()
                trackedApps.add(TrackedAppInfo(packageName = packageName, appName = appName, lastUsedTime = lastUsed))
            } catch (e: Exception) {}
        }

        return trackedApps.sortedByDescending { it.lastUsedTime }.take(28)
    }

    fun getRunningPackagesToStop(context: Context): List<String> {
        return getRecentApps(context).map { it.packageName }
    }

    fun startForceStopSequence(context: Context) {
        if (!checkUsageStatsPermission(context)) {
            Toast.makeText(context, "Grant Usage Access to track active apps", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {}
            return
        }

        val packagesToStop = getRunningPackagesToStop(context)
        if (packagesToStop.isEmpty()) {
            Toast.makeText(context, "No running apps to stop", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(context, AppTrackerOpenerActivity::class.java).apply {
            putStringArrayListExtra("packages", ArrayList(packagesToStop))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
