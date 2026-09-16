package com.example.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * High-performance, low-latency icon cache manager.
 * Downsamples installed application icons into compact (48x48 / 64x64) WebP files on disk.
 * Eliminates synchronous main-thread PackageManager Binder IPC delays during overlay gestures.
 */
object IconCacheManager {

    private const val TAG = "IconCacheManager"
    private const val CACHE_DIR_NAME = "icons_webp_cache"
    private const val TARGET_ICON_SIZE_DP = 48

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // In-memory memory cache capped at ~1.5MB max
    private val memoryCache = object : LruCache<String, Bitmap>(100) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }

        try {
            val appContext = context.applicationContext
            appContext.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val pkgName = intent?.data?.schemeSpecificPart ?: return
                    when (intent.action) {
                        Intent.ACTION_PACKAGE_REMOVED -> {
                            evictIcon(appContext, pkgName)
                        }
                        Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REPLACED -> {
                            scope.launch {
                                cachePackageIcon(appContext, pkgName)
                            }
                        }
                    }
                }
            }, filter)
        } catch (e: Exception) {
            LogKeeper.writeLog(TAG, "Error registering package receiver: ${e.message}")
        }
    }

    private fun getCacheDir(context: Context): File {
        val dir = File(context.filesDir, CACHE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getIconFile(context: Context, packageName: String): File {
        val safeName = packageName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(getCacheDir(context), "$safeName.webp")
    }

    fun getCachedBitmap(context: Context, packageName: String): Bitmap? {
        // 1. Fast memory lookup
        memoryCache.get(packageName)?.let { return it }

        // 2. Fast disk lookup
        val file = getIconFile(context, packageName)
        if (file.exists() && file.length() > 0) {
            try {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    memoryCache.put(packageName, bitmap)
                    return bitmap
                }
            } catch (e: Exception) {
                // Corrupted file, delete
                file.delete()
            }
        }
        return null
    }

    suspend fun getOrLoadBitmap(context: Context, packageName: String): Bitmap? = withContext(Dispatchers.IO) {
        getCachedBitmap(context, packageName)?.let { return@withContext it }
        return@withContext cachePackageIcon(context, packageName)
    }

    private fun cachePackageIcon(context: Context, packageName: String): Bitmap? {
        try {
            val pm = context.packageManager
            val drawable = pm.getApplicationIcon(packageName)
            val downsampled = downsampleDrawable(context, drawable) ?: return null
            
            // Save to disk as WebP
            val file = getIconFile(context, packageName)
            FileOutputStream(file).use { out ->
                downsampled.compress(Bitmap.CompressFormat.WEBP, 85, out)
            }
            memoryCache.put(packageName, downsampled)
            return downsampled
        } catch (e: Exception) {
            return null
        }
    }

    fun downsampleDrawable(context: Context, drawable: Drawable): Bitmap? {
        val density = context.resources.displayMetrics.density
        val targetPx = Math.round(TARGET_ICON_SIZE_DP * density).coerceIn(48, 128)

        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val bmp = drawable.bitmap
            if (bmp.width <= targetPx && bmp.height <= targetPx) {
                return bmp
            }
            return Bitmap.createScaledBitmap(bmp, targetPx, targetPx, true)
        }

        return try {
            val w = if (drawable.intrinsicWidth > 0) minOf(drawable.intrinsicWidth, targetPx) else targetPx
            val h = if (drawable.intrinsicHeight > 0) minOf(drawable.intrinsicHeight, targetPx) else targetPx
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            if (bitmap.width != targetPx || bitmap.height != targetPx) {
                Bitmap.createScaledBitmap(bitmap, targetPx, targetPx, true)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getCustomIconFile(context: Context, targetId: String): File {
        val safeName = targetId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val customDir = File(context.filesDir, "custom_icons")
        if (!customDir.exists()) {
            customDir.mkdirs()
        }
        return File(customDir, "$safeName.webp")
    }

    suspend fun saveCustomIconFromUri(context: Context, targetId: String, uri: android.net.Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            // Step 1: Query image dimensions without loading full bitmap into memory
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            val density = context.resources.displayMetrics.density
            val targetPx = Math.round(TARGET_ICON_SIZE_DP * density).coerceIn(48, 128)

            // Step 2: Compute inSampleSize
            var inSampleSize = 1
            if (options.outHeight > targetPx || options.outWidth > targetPx) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while ((halfHeight / inSampleSize) >= targetPx && (halfWidth / inSampleSize) >= targetPx) {
                    inSampleSize *= 2
                }
            }

            // Step 3: Decode downsampled bitmap
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val sampledBmp = contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return@withContext null

            // Step 4: Scale to exact targetPx
            val finalBmp = if (sampledBmp.width != targetPx || sampledBmp.height != targetPx) {
                Bitmap.createScaledBitmap(sampledBmp, targetPx, targetPx, true)
            } else {
                sampledBmp
            }

            // Step 5: Save as compact WebP
            val file = getCustomIconFile(context, targetId)
            FileOutputStream(file).use { out ->
                finalBmp.compress(Bitmap.CompressFormat.WEBP, 85, out)
            }

            memoryCache.put("custom_$targetId", finalBmp)
            memoryCache.put(targetId, finalBmp)
            return@withContext finalBmp
        } catch (e: Exception) {
            LogKeeper.writeLog(TAG, "Error saving custom icon: ${e.message}")
            return@withContext null
        }
    }

    fun evictIcon(context: Context, packageName: String) {
        memoryCache.remove(packageName)
        memoryCache.remove("custom_$packageName")
        val file = getIconFile(context, packageName)
        if (file.exists()) {
            file.delete()
        }
        val customFile = getCustomIconFile(context, packageName)
        if (customFile.exists()) {
            customFile.delete()
        }
    }

    fun clearAll(context: Context) {
        memoryCache.evictAll()
        val dir = getCacheDir(context)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }
}
