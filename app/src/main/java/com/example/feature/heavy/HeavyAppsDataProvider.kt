package com.example.feature.heavy

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.UserManager
import com.example.core.IconCacheManager
import com.example.core.LogKeeper
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyCommandType
import com.example.core.ipc.IpcErrorCode
import com.example.core.ipc.IpcResult
import com.example.feature.sidebar.AppInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * HeavyAppsDataProvider: Operates strictly within the Heavy process (:heavy).
 *
 * Responsibilities:
 * 1. Performs expensive installed-package scanning and app discovery.
 * 2. Retrieves app labels and performs alphabetical sorting.
 * 3. Pre-caches downsampled WebP app icons (48dp) to disk via IconCacheManager.
 * 4. Produces compact structured JSON payloads across Binder IPC to Main.
 * 5. Guarantees zero UI rendering in Heavy and preserves container scoping.
 */
object HeavyAppsDataProvider {

    private const val TAG = "HeavyAppsDataProvider"

    fun handleGetAppsData(context: Context?, command: HeavyCommand): IpcResult {
        return try {
            val containerId = command.payload["containerId"] ?: command.targetId ?: ""
            val pageId = command.payload["pageId"] ?: ""
            val precacheIcons = command.payload["precache_icons"]?.toBoolean() ?: true

            val apps = if (context != null) {
                scanInstalledApps(context, precacheIcons = precacheIcons)
            } else {
                emptyList()
            }

            val jsonArray = JSONArray()
            for (app in apps) {
                val obj = JSONObject()
                obj.put("packageName", app.packageName)
                obj.put("label", app.label)
                jsonArray.put(obj)
            }

            val data = mapOf(
                "apps" to jsonArray.toString(),
                "containerId" to containerId,
                "pageId" to pageId,
                "count" to apps.size.toString()
            )

            LogKeeper.writeLog(TAG, "Scanned ${apps.size} apps for container=$containerId in :heavy")
            IpcResult.success("Apps scanned successfully", data)
        } catch (e: Throwable) {
            LogKeeper.writeLog(TAG, "Error discovering apps in :heavy: ${e.message}")
            IpcResult.error(IpcErrorCode.COMMAND_FAILED, e.message ?: "Failed to scan apps")
        }
    }

    fun scanInstalledApps(context: Context, precacheIcons: Boolean = true): List<AppInfo> {
        val appList = mutableListOf<AppInfo>()
        val selfPkg = try { context.packageName } catch (_: Exception) { "" }

        // 1. Discover apps via LauncherApps
        try {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
            if (launcherApps != null && userManager != null) {
                for (profile in userManager.userProfiles) {
                    try {
                        val activities = launcherApps.getActivityList(null, profile)
                        for (activityInfo in activities) {
                            val pkg = activityInfo.applicationInfo.packageName
                            if (pkg == selfPkg) continue
                            val label = activityInfo.label?.toString() ?: pkg
                            appList.add(AppInfo(pkg, label))
                        }
                    } catch (e: Exception) {
                        LogKeeper.writeLog(TAG, "LauncherApps scan profile error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            LogKeeper.writeLog(TAG, "LauncherApps service error: ${e.message}")
        }

        // 2. Discover apps via PackageManager launcher intent activities
        try {
            val pm = context.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)
            for (ri in resolveInfos) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == selfPkg) continue
                val label = ri.loadLabel(pm)?.toString() ?: pkg
                appList.add(AppInfo(pkg, label))
            }
        } catch (e: Exception) {
            LogKeeper.writeLog(TAG, "PackageManager queryIntentActivities error: ${e.message}")
        }

        // Distinct and alphabetical sort performed in Heavy
        val distinctApps = appList.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }

        // Precache icons into compact WebP disk cache if requested
        if (precacheIcons) {
            for (app in distinctApps) {
                try {
                    val file = IconCacheManager.getIconFile(context, app.packageName)
                    if (!file.exists() || file.length() == 0L) {
                        IconCacheManager.captureAndSavePackageIcon(context, app.packageName)
                    }
                } catch (e: Exception) {
                    // Do not fail scan on single icon issue
                }
            }
        }

        return distinctApps
    }
}
