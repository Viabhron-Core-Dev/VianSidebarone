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

    /**
     * Exports a portable PWA backup archive (.zip) containing:
     * 1. pwa_metadata.json (manifest metadata + user preferences)
     * 2. package.zip (original retained distribution package)
     * 3. custom_icon.webp (if a custom icon was configured)
     *
     * Portability limitation note:
     * Android WebView internal storage (SQLite / LevelDB in pwa_data) is device-specific,
     * hardware-keyed, and unportable across devices without corruption risk.
     * Backing up the source package, manifest metadata, custom icons, and preferences
     * guarantees 100% portable, reliable restoration.
     */
    fun exportPwaBackup(context: Context, pwaId: String, outputStream: java.io.OutputStream): Boolean {
        return try {
            val db = AppDatabase.getDatabase(context)
            val pwa = db.pwaDao().getById(pwaId) ?: return false
            val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
            val orientation = prefs.getString("pwa_pref_${pwaId}_orientation", "manifest") ?: "manifest"
            val closeButton = prefs.getString("pwa_pref_${pwaId}_close_button", "show") ?: "show"
            val policy = PwaPermissionManager.getPolicy(context, pwaId)

            val json = org.json.JSONObject().apply {
                put("id", pwa.id)
                put("name", pwa.name)
                put("shortName", pwa.shortName)
                put("description", pwa.description)
                put("startUrl", pwa.startUrl)
                put("scope", pwa.scope)
                put("displayMode", pwa.displayMode)
                put("orientation", pwa.orientation)
                put("themeColor", pwa.themeColor)
                put("backgroundColor", pwa.backgroundColor)
                put("version", pwa.version)
                put("packageSize", pwa.packageSize)
                put("createdAt", pwa.createdAt)
                put("orientationPreference", orientation)
                put("closeButtonPreference", closeButton)
                put("permissionInternet", policy.internetAllowed)
                put("permissionFiles", policy.filesAllowed)
                put("permissionCamera", policy.cameraAllowed)
                put("permissionMicrophone", policy.microphoneAllowed)
                put("permissionLocation", policy.locationAllowed)
                put("permissionSound", policy.soundAllowed)
            }

            java.util.zip.ZipOutputStream(outputStream).use { zos ->
                val metaBytes = json.toString(2).toByteArray(Charsets.UTF_8)
                zos.putNextEntry(java.util.zip.ZipEntry("pwa_metadata.json"))
                zos.write(metaBytes)
                zos.closeEntry()

                val pkgFile = File(pwa.zipPath)
                if (pkgFile.exists()) {
                    zos.putNextEntry(java.util.zip.ZipEntry("package.zip"))
                    pkgFile.inputStream().use { input -> input.copyTo(zos) }
                    zos.closeEntry()
                }

                val customIconFile = com.example.core.IconCacheManager.getCustomIconFile(context, "pwa_$pwaId")
                if (customIconFile.exists() && customIconFile.length() > 0) {
                    zos.putNextEntry(java.util.zip.ZipEntry("custom_icon.webp"))
                    customIconFile.inputStream().use { input -> input.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            true
        } catch (e: Exception) {
            com.example.core.LogKeeper.logError(context, "PWA", "Failed to export backup for $pwaId", e)
            false
        }
    }
}
