package com.example.core

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import java.util.Calendar
import java.util.Locale

/**
 * DailyDataUsageHelper: Queries total device network bytes (Mobile + Wi-Fi) used today from midnight.
 * Gracefully handles permission restrictions and formats using binary units (MiB, GiB, KiB, B).
 */
object DailyDataUsageHelper {

    fun getTodayDataUsageBytes(context: Context): Long? {
        val networkStatsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            ?: return null

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        var totalBytes = 0L
        var querySucceeded = false

        // Wi-Fi traffic
        try {
            val wifiBucket = networkStatsManager.querySummaryForDevice(
                ConnectivityManager.TYPE_WIFI,
                null,
                startTime,
                endTime
            )
            if (wifiBucket != null) {
                totalBytes += wifiBucket.rxBytes + wifiBucket.txBytes
                querySucceeded = true
            }
        } catch (_: SecurityException) {
            // Usage access permission not granted
        } catch (_: Exception) {
            // Wi-Fi querying error or unsupported
        }

        // Mobile / Cellular traffic
        try {
            val mobileBucket = networkStatsManager.querySummaryForDevice(
                ConnectivityManager.TYPE_MOBILE,
                null,
                startTime,
                endTime
            )
            if (mobileBucket != null) {
                totalBytes += mobileBucket.rxBytes + mobileBucket.txBytes
                querySucceeded = true
            }
        } catch (_: SecurityException) {
            // Usage access permission not granted
        } catch (_: Exception) {
            // Mobile querying error or unsupported
        }

        return if (querySucceeded) totalBytes else null
    }

    fun formatDataBytes(bytes: Long?): String {
        if (bytes == null) return "--"
        val kib = 1024.0
        val mib = kib * 1024.0
        val gib = mib * 1024.0
        return when {
            bytes >= gib -> String.format(Locale.US, "%.2f GiB", bytes / gib)
            bytes >= mib -> String.format(Locale.US, "%.2f MiB", bytes / mib)
            bytes >= kib -> String.format(Locale.US, "%.2f KiB", bytes / kib)
            bytes > 0 -> "$bytes B"
            else -> "0 B"
        }
    }
}
