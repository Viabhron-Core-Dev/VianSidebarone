package com.example.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * FaviconFetcher: High-resolution website icon fetcher and cache engine.
 * Fetches the site's own brand icon (e.g. Duolingo owl, ChatGPT icon, Google G)
 * asynchronously without blocking the UI and stores it as a compact WebP file.
 */
object FaviconFetcher {

    private const val TAG = "FaviconFetcher"
    private const val TIMEOUT_MS = 4000
    private const val TARGET_ICON_SIZE_DP = 56

    suspend fun fetchAndCacheSiteIcon(
        context: Context,
        uuid: String,
        siteUrl: String
    ): String? = withContext(Dispatchers.IO) {
        val host = extractHost(siteUrl) ?: return@withContext null
        val safeName = "link_${uuid.replace(Regex("[^a-zA-Z0-9._-]"), "_")}.webp"
        val outputFile = File(IconCacheManager.getCacheDir(context), safeName)

        val candidateUrls = listOf(
            "https://www.google.com/s2/favicons?domain=$host&sz=128",
            "https://t3.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://$host&size=128",
            "https://$host/apple-touch-icon.png",
            "https://$host/apple-touch-icon-precomposed.png",
            "https://icons.duckduckgo.com/ip3/$host.ico",
            "https://$host/favicon.ico"
        )

        for (cand in candidateUrls) {
            val bitmap = downloadBitmap(cand)
            if (bitmap != null && bitmap.width >= 12 && bitmap.height >= 12) {
                val density = context.resources.displayMetrics.density
                val targetPx = Math.round(TARGET_ICON_SIZE_DP * density).coerceIn(48, 144)
                val scaled = if (bitmap.width != targetPx || bitmap.height != targetPx) {
                    Bitmap.createScaledBitmap(bitmap, targetPx, targetPx, true)
                } else {
                    bitmap
                }

                try {
                    FileOutputStream(outputFile).use { out ->
                        scaled.compress(Bitmap.CompressFormat.WEBP, 90, out)
                    }
                    if (scaled != bitmap) {
                        bitmap.recycle()
                    }
                    LogKeeper.writeLog(TAG, "Cached favicon for $host -> ${outputFile.name}")
                    return@withContext outputFile.absolutePath
                } catch (e: Exception) {
                    LogKeeper.writeLog(TAG, "Error saving favicon for $host: ${e.message}")
                }
            }
        }

        return@withContext null
    }

    private fun extractHost(rawUrl: String): String? {
        return try {
            val normalized = if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                "https://$rawUrl"
            } else {
                rawUrl
            }
            val uri = Uri.parse(normalized)
            uri.host?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun downloadBitmap(urlStr: String): Bitmap? {
        var conn: HttpURLConnection? = null
        var stream: InputStream? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            }
            conn.connect()
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                stream = conn.inputStream
                BitmapFactory.decodeStream(stream)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            try { stream?.close() } catch (_: Exception) {}
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
