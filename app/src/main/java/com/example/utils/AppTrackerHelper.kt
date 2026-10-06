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
        if (com.example.core.PageManager.isPageTypePresentInPrefs(prefs, "app_tracker")) return true
        val appPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return com.example.core.PageManager.isPageTypePresentInPrefs(appPrefs, "app_tracker")
    }

    /**
     * Checks if the specific container currently hosts an App Tracker page.
     * Container isolation: only sync within the same container.
     */
    fun hasAppTrackerInContainer(context: Context, containerId: String): Boolean {
        return try {
            val pageManager = com.example.core.PageManager.getInstance(context)
            val pages = pageManager.getPageStack(containerId)
            pages.any { it.pageType == "app_tracker" }
        } catch (e: Exception) {
            false
        }
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

    fun getRecentApps(context: Context, containerId: String = "sidebar"): List<TrackedAppInfo> {
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

        val whitelist = getForceStopWhitelist(context, containerId)

        val pm = context.packageManager
        val trackedApps = mutableListOf<TrackedAppInfo>()

        for ((packageName, lastUsed) in appLastUsed) {
            if (packageName == context.packageName) continue
            if (packageName.contains("launcher", ignoreCase = true)) continue
            if (whitelist.contains(packageName)) continue

            try {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                val appName = pm.getApplicationLabel(appInfo).toString()
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                trackedApps.add(TrackedAppInfo(packageName = packageName, appName = appName, lastUsedTime = lastUsed, isSystem = isSystem))
            } catch (e: Exception) {}
        }

        return trackedApps.sortedByDescending { it.lastUsedTime }.take(28)
    }

    fun getContainerWhitelistKey(containerId: String): String =
        if (containerId.isNotBlank() && containerId != "sidebar") "handle_${containerId}_app_tracker_whitelist" else "app_tracker_whitelist_current"

    fun getForceStopWhitelistKey(containerId: String): String =
        if (containerId.isNotBlank() && containerId != "sidebar") "handle_${containerId}_force_stop_whitelist" else "force_stop_whitelist_current"

    /**
     * On-demand sync between Force Stop and App Tracker whitelist strictly within the same container.
     * Zero background activity. Executed only when invoked.
     */
    fun syncOnDemand(context: Context, containerId: String) {
        if (!hasAppTrackerInContainer(context, containerId)) return
        val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        val forceStopKey = getForceStopWhitelistKey(containerId)
        val trackerKey = getContainerWhitelistKey(containerId)

        val forceStopSet = prefs.getStringSet(forceStopKey, null)
        val trackerSet = prefs.getStringSet(trackerKey, null)

        if (forceStopSet != null && trackerSet == null) {
            prefs.edit().putStringSet(trackerKey, forceStopSet).commit()
            com.example.core.OverlaySyncManager.syncStringSet(context, trackerKey, forceStopSet)
        } else if (trackerSet != null && forceStopSet == null) {
            prefs.edit().putStringSet(forceStopKey, trackerSet).commit()
            com.example.core.OverlaySyncManager.syncStringSet(context, forceStopKey, trackerSet)
        } else if (forceStopSet != null && trackerSet != null && forceStopSet != trackerSet) {
            val merged = forceStopSet.toMutableSet().apply { addAll(trackerSet) }
            prefs.edit().putStringSet(forceStopKey, merged).putStringSet(trackerKey, merged).commit()
            com.example.core.OverlaySyncManager.syncStringSet(context, forceStopKey, merged)
            com.example.core.OverlaySyncManager.syncStringSet(context, trackerKey, merged)
        }
    }

    /**
     * Standalone whitelist retrieval for the Force Stop Apps button.
     * If an App Tracker page is configured in the same container, on-demand sync is performed.
     */
    fun getForceStopWhitelist(context: Context, containerId: String = "sidebar"): Set<String> {
        val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        if (hasAppTrackerInContainer(context, containerId)) {
            syncOnDemand(context, containerId)
        }
        val key = getForceStopWhitelistKey(containerId)
        val set = prefs.getStringSet(key, null)
        if (set != null) return set

        // Fallback: check if container tracker whitelist exists
        val trackerKey = getContainerWhitelistKey(containerId)
        val trackerSet = prefs.getStringSet(trackerKey, null)
        if (trackerSet != null) return trackerSet

        // Backward compatibility fallback for primary/legacy container
        if (containerId == "sidebar" || containerId == "handle_1_swipe_left" || containerId == "handle_1") {
            val legacy = prefs.getStringSet("force_stop_whitelist_current", null)
            if (legacy != null) {
                prefs.edit().putStringSet(key, legacy).commit()
                return legacy
            }
        }

        return emptySet()
    }

    /**
     * Saves standalone whitelist for Force Stop Apps element.
     * On-demand syncs to App Tracker page whitelist only if present in the same container.
     */
    fun saveForceStopWhitelist(context: Context, containerId: String = "sidebar", whitelist: Set<String>) {
        val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        val key = getForceStopWhitelistKey(containerId)
        prefs.edit().putStringSet(key, whitelist).commit()
        if (containerId == "sidebar") {
            prefs.edit().putStringSet("force_stop_whitelist_current", whitelist).commit()
        }
        com.example.core.OverlaySyncManager.syncStringSet(context, key, whitelist)

        if (hasAppTrackerInContainer(context, containerId)) {
            val trackerKey = getContainerWhitelistKey(containerId)
            prefs.edit().putStringSet(trackerKey, whitelist).commit()
            if (containerId == "sidebar") {
                prefs.edit().putStringSet("app_tracker_whitelist_current", whitelist).commit()
            }
            com.example.core.OverlaySyncManager.syncStringSet(context, trackerKey, whitelist)
        }
    }

    fun getWhitelist(context: Context, containerId: String = "sidebar"): Set<String> {
        val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        if (hasAppTrackerInContainer(context, containerId)) {
            syncOnDemand(context, containerId)
        }
        val containerKey = getContainerWhitelistKey(containerId)
        val containerSet = prefs.getStringSet(containerKey, null)
        if (containerSet != null) return containerSet

        val forceStopKey = getForceStopWhitelistKey(containerId)
        val forceStopSet = prefs.getStringSet(forceStopKey, null)
        if (forceStopSet != null) return forceStopSet

        // Backward compatibility fallback for primary/legacy container
        if (containerId == "sidebar" || containerId == "handle_1_swipe_left" || containerId == "handle_1") {
            val legacy = prefs.getStringSet("app_tracker_whitelist_current", null)
            if (legacy != null) {
                prefs.edit().putStringSet(containerKey, legacy).commit()
                return legacy
            }
        }

        return emptySet()
    }

    fun saveWhitelist(context: Context, containerId: String = "sidebar", whitelist: Set<String>) {
        val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        val containerKey = getContainerWhitelistKey(containerId)
        prefs.edit().putStringSet(containerKey, whitelist).commit()
        if (containerId == "sidebar") {
            prefs.edit().putStringSet("app_tracker_whitelist_current", whitelist).commit()
        }
        com.example.core.OverlaySyncManager.syncStringSet(context, containerKey, whitelist)

        // Sync with Force Stop element within the same container
        if (hasAppTrackerInContainer(context, containerId)) {
            val forceStopKey = getForceStopWhitelistKey(containerId)
            prefs.edit().putStringSet(forceStopKey, whitelist).commit()
            if (containerId == "sidebar") {
                prefs.edit().putStringSet("force_stop_whitelist_current", whitelist).commit()
            }
            com.example.core.OverlaySyncManager.syncStringSet(context, forceStopKey, whitelist)
        }
    }

    fun getRunningPackagesToStop(context: Context, containerId: String = "sidebar"): List<String> {
        return getRecentApps(context, containerId).map { it.packageName }
    }

    fun startForceStopSequence(context: Context, containerId: String = "sidebar") {
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

        val packagesToStop = getRunningPackagesToStop(context, containerId)
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
