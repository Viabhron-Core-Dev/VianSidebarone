package com.example.core

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import java.util.Calendar
import java.util.Locale

/**
 * DailyDataUsageHelper: Queries total device network bytes (Mobile + Wi-Fi) used today from midnight.
 * Gracefully handles permission restrictions, provides manual daily correction/offset management,
 * and formats using binary units (MiB, GiB, KiB, B).
 */
object DailyDataUsageHelper {

    private const val PREFS_NAME = "DailyDataUsagePrefs"
    private const val KEY_OFFSET_BYTES = "daily_offset_bytes"
    private const val KEY_CORRECTION_DATE = "daily_correction_date"
    private const val KEY_CORRECTION_ACTIVE = "daily_correction_active"
    private const val KEY_DESIRED_VALUE = "daily_desired_value"
    private const val KEY_DESIRED_UNIT = "daily_desired_unit"

    data class DailyCorrectionInfo(
        val isActive: Boolean,
        val offsetBytes: Long,
        val dateKey: String,
        val desiredValue: Double,
        val desiredUnit: String
    )

    fun getTodayDateKey(): String {
        val cal = Calendar.getInstance()
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return String.format(Locale.US, "%04d-%02d-%02d", y, m, d)
    }

    /**
     * Queries raw device network bytes (Mobile + Wi-Fi) used today from midnight.
     */
    fun getRawTodayDataUsageBytes(context: Context): Long? {
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

    /**
     * Returns today's data usage bytes, applying any manual daily correction offset active for today.
     * Newly measured network usage accumulates on top of the corrected total.
     */
    fun getTodayDataUsageBytes(context: Context): Long? {
        val raw = getRawTodayDataUsageBytes(context)
        val correction = getCorrectionInfo(context)
        if (correction != null && correction.isActive && correction.dateKey == getTodayDateKey()) {
            val base = raw ?: 0L
            return (base + correction.offsetBytes).coerceAtLeast(0L)
        }
        return raw
    }

    /**
     * Sets a manual daily data usage correction.
     * Computes the offset so that newly measured network usage continues accumulating on top of the corrected total.
     * Automatically expires at next local calendar day.
     */
    fun setDailyDataUsageCorrection(
        context: Context,
        desiredBytes: Long,
        desiredValue: Double,
        desiredUnit: String
    ): Boolean {
        if (desiredBytes < 0) return false
        val raw = getRawTodayDataUsageBytes(context) ?: 0L
        val offset = desiredBytes - raw
        val dateKey = getTodayDateKey()

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.edit()
            .putBoolean(KEY_CORRECTION_ACTIVE, true)
            .putLong(KEY_OFFSET_BYTES, offset)
            .putString(KEY_CORRECTION_DATE, dateKey)
            .putString(KEY_DESIRED_VALUE, desiredValue.toString())
            .putString(KEY_DESIRED_UNIT, desiredUnit)
            .commit()
    }

    /**
     * Clears any active manual daily data usage correction.
     */
    fun clearDailyDataUsageCorrection(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_CORRECTION_ACTIVE)
            .remove(KEY_OFFSET_BYTES)
            .remove(KEY_CORRECTION_DATE)
            .remove(KEY_DESIRED_VALUE)
            .remove(KEY_DESIRED_UNIT)
            .commit()
    }

    /**
     * Retrieves the current manual correction info if active and still valid for today.
     */
    fun getCorrectionInfo(context: Context): DailyCorrectionInfo? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val active = prefs.getBoolean(KEY_CORRECTION_ACTIVE, false)
        if (!active) return null
        val date = prefs.getString(KEY_CORRECTION_DATE, null) ?: return null
        if (date != getTodayDateKey()) {
            // Automatically reset when a new calendar day arrives
            clearDailyDataUsageCorrection(context)
            return null
        }
        val offset = prefs.getLong(KEY_OFFSET_BYTES, 0L)
        val desVal = prefs.getString(KEY_DESIRED_VALUE, "0")?.toDoubleOrNull() ?: 0.0
        val desUnit = prefs.getString(KEY_DESIRED_UNIT, "MB") ?: "MB"
        return DailyCorrectionInfo(true, offset, date, desVal, desUnit)
    }

    /**
     * Converts a value in B, KB, MB, or GB to bytes.
     */
    fun convertToBytes(value: Double, unit: String): Long {
        if (value < 0) return 0L
        val factor = when (unit.uppercase(Locale.US)) {
            "B" -> 1.0
            "KB", "KIB" -> 1024.0
            "MB", "MIB" -> 1024.0 * 1024.0
            "GB", "GIB" -> 1024.0 * 1024.0 * 1024.0
            else -> 1024.0 * 1024.0
        }
        return (value * factor).toLong().coerceAtLeast(0L)
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
