package com.example.feature.pwa

import android.content.Context
import com.example.data.AppDatabase
import java.io.File

/**
 * PwaStorageManager: Manages physical package storage, deletion cleanup,
 * and isolated origin/data paths for Offline PWAs.
 */
object PwaStorageManager {

    /**
     * Completely cleans up all persisted package files, extracted assets,
     * isolated local WebView storage, and the database record.
     */
    fun deletePwa(context: Context, pwaId: String) {
        deletePwaFiles(context, pwaId)
        try {
            AppDatabase.getDatabase(context).pwaDao().deleteById(pwaId)
        } catch (_: Throwable) {}
    }

    /**
     * Deletes physical package files, unpacked assets, and isolated WebView storage.
     */
    fun deletePwaFiles(context: Context, pwaId: String) {
        try {
            // 1. Delete package & unpacked assets
            val pwaDir = File(context.filesDir, "pwa/$pwaId")
            if (pwaDir.exists()) {
                pwaDir.deleteRecursively()
            }

            // 2. Delete isolated WebView data/storage directory
            val dataDir = File(context.filesDir, "pwa_data/$pwaId")
            if (dataDir.exists()) {
                dataDir.deleteRecursively()
            }

            // 3. Delete isolated WebView cache directory
            val cacheDir = File(context.cacheDir, "pwa_cache/$pwaId")
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
            }
        } catch (_: Throwable) {}
    }

    /**
     * Returns an isolated data directory for the PWA WebView runtime.
     * Guarantees storage isolation between different imported PWAs.
     */
    fun getIsolatedDataDir(context: Context, pwaId: String): File {
        val dir = File(context.filesDir, "pwa_data/$pwaId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Returns an isolated cache directory for the PWA WebView runtime.
     */
    fun getIsolatedCacheDir(context: Context, pwaId: String): File {
        val dir = File(context.cacheDir, "pwa_cache/$pwaId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Formats bytes into a human-readable size string.
     */
    fun formatPackageSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return String.format(java.util.Locale.US, "%.1f %s", value, units[digitGroups])
    }
}
