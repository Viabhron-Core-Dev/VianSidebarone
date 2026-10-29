package com.example.feature.notification

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Data model representing an installed app in the notification filter lists.
 */
data class NotificationAppItem(
    val packageName: String,
    val appName: String,
    val isSystem: Boolean
)

/**
 * Filter options for notification app lists:
 * - ALL: User + System apps
 * - USER: User apps only
 * - SYSTEM: System apps only
 */
enum class NotificationAppFilterCategory {
    ALL,
    USER,
    SYSTEM
}

object NotificationAppFilterHelper {

    /**
     * Determines whether an application is a system app using standard Android application metadata flags.
     */
    fun isSystemApp(appInfo: ApplicationInfo): Boolean {
        return (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }

    /**
     * Loads all installed applications (User and System) from PackageManager.
     */
    fun loadAllNotificationApps(context: Context): List<NotificationAppItem> {
        val pm = try { context.packageManager } catch (_: Throwable) { null } ?: return emptyList()
        val packages = try {
            pm.getInstalledPackages(0)
        } catch (_: Throwable) {
            emptyList()
        }

        return packages.mapNotNull { pkg ->
            val appInfo = pkg.applicationInfo ?: return@mapNotNull null
            val isSystem = isSystemApp(appInfo)
            val name = try {
                appInfo.loadLabel(pm).toString()
            } catch (_: Throwable) {
                pkg.packageName
            }
            val displayName = if (name.isNotBlank()) name else pkg.packageName
            NotificationAppItem(
                packageName = pkg.packageName,
                appName = displayName,
                isSystem = isSystem
            )
        }.distinctBy { it.packageName }.sortedBy { it.appName.lowercase() }
    }

    /**
     * Filters a list of NotificationAppItem based on the selected category:
     * - ALL -> User + System apps
     * - USER -> User apps only
     * - SYSTEM -> System apps only
     */
    fun filterApps(
        apps: List<NotificationAppItem>,
        category: NotificationAppFilterCategory
    ): List<NotificationAppItem> {
        return when (category) {
            NotificationAppFilterCategory.ALL -> apps
            NotificationAppFilterCategory.USER -> apps.filter { !it.isSystem }
            NotificationAppFilterCategory.SYSTEM -> apps.filter { it.isSystem }
        }
    }
}
