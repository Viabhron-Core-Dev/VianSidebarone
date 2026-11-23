package com.example.feature.htmlviewer

import android.content.Context
import com.example.core.LogKeeper
import java.io.File
import java.util.UUID

/**
 * Manages strictly ephemeral app-private cache files for the floating HTML/MHT viewer.
 *
 * Security & privacy invariants:
 * - Stored strictly under `context.codeCacheDir/ephemeral_html_viewer/`, which is an app-private
 *   Android cache directory outside `res/xml/file_paths.xml` and therefore NEVER exposed through
 *   FileProvider.
 * - Uses generated random UUID filenames; never uses original document filenames as paths.
 * - Enforces a single authoritative prepared UTF-8 byte cap ([MAX_PREPARED_HTML_BYTES]) on both
 *   write and read so a file written during intake can never fail read-back due to guard overhead
 *   or charset expansion mismatch.
 * - Deleted on viewer close, failed initialization/wake, non-folded destroy, and startup cleanup.
 */
object HtmlMhtCacheManager {

    private const val TAG = "HtmlMhtCacheManager"
    const val CACHE_SUBDIR_NAME = "ephemeral_html_viewer"
    private const val FILE_PREFIX = "doc_"
    private const val FILE_SUFFIX = ".html"

    /**
     * Authoritative cap for sanitized UTF-8 HTML stored in the temporary viewer cache:
     * 5 MiB document budget plus 64 KiB headroom for injected offline CSP/style guard headers.
     */
    const val MAX_PREPARED_HTML_BYTES: Int = MhtmlParser.DEFAULT_MAX_HTML_BYTES + (64 * 1024)

    sealed class TempWriteResult {
        data class Success(val file: File) : TempWriteResult()
        data class Oversized(val byteLength: Int, val maxBytes: Int) : TempWriteResult()
        data class Failed(val reasonCode: String) : TempWriteResult()
    }

    fun getCacheDir(context: Context): File? {
        val baseCache = context.codeCacheDir ?: return null
        val dir = File(baseCache, CACHE_SUBDIR_NAME)
        if (!dir.exists()) {
            if (!dir.mkdirs() && !dir.exists()) {
                return null
            }
        }
        return dir
    }

    /**
     * Writes sanitized UTF-8 HTML to a unique app-private temporary cache file, enforcing
     * [maxBytes] BEFORE touching disk so write and read caps can never disagree.
     */
    fun writeTempHtmlChecked(
        context: Context,
        sanitizedHtml: String,
        maxBytes: Int = MAX_PREPARED_HTML_BYTES
    ): TempWriteResult {
        val utf8Bytes = sanitizedHtml.toByteArray(Charsets.UTF_8)
        if (utf8Bytes.size > maxBytes) {
            return TempWriteResult.Oversized(utf8Bytes.size, maxBytes)
        }
        val dir = getCacheDir(context) ?: return TempWriteResult.Failed("CACHE_DIR_UNAVAILABLE")
        val uniqueName = "$FILE_PREFIX${UUID.randomUUID().toString().replace("-", "")}$FILE_SUFFIX"
        val target = File(dir, uniqueName)
        return try {
            if (!isInsideCacheDir(dir, target)) {
                return TempWriteResult.Failed("PATH_CONFINEMENT_FAILED")
            }
            target.writeBytes(utf8Bytes)
            TempWriteResult.Success(target)
        } catch (t: Throwable) {
            try {
                if (target.exists()) target.delete()
            } catch (_: Throwable) {}
            LogKeeper.logError(context, TAG, "TEMP_CACHE_WRITE_FAILED type=${t.javaClass.simpleName}", t)
            TempWriteResult.Failed("TEMP_CACHE_WRITE_FAILED")
        }
    }

    /**
     * Convenience wrapper returning the written temp [File] or null on size/IO failure.
     */
    fun writeTempHtml(
        context: Context,
        sanitizedHtml: String,
        maxBytes: Int = MAX_PREPARED_HTML_BYTES
    ): File? {
        return when (val res = writeTempHtmlChecked(context, sanitizedHtml, maxBytes)) {
            is TempWriteResult.Success -> res.file
            else -> null
        }
    }

    /**
     * Reads UTF-8 HTML back from a verified app-private viewer temporary cache file.
     */
    fun readTempHtml(
        context: Context,
        file: File?,
        maxBytes: Int = MAX_PREPARED_HTML_BYTES
    ): String? {
        if (file == null || !file.exists() || !file.isFile) return null
        val dir = getCacheDir(context) ?: return null
        if (!isInsideCacheDir(dir, file)) return null
        return try {
            if (file.length() > maxBytes.toLong()) return null
            file.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            LogKeeper.logError(context, TAG, "TEMP_CACHE_READ_FAILED type=${t.javaClass.simpleName}", t)
            null
        }
    }

    /**
     * Safely deletes a single temporary viewer cache file.
     */
    fun deleteTempFile(file: File?): Boolean {
        if (file == null) return false
        return try {
            if (file.exists()) file.delete() else false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Removes any abandoned viewer cache files (for example after process restart or crash).
     * Also sweeps legacy `context.cacheDir/ephemeral_html_viewer` if any prior build left files there.
     * Any file paths listed in [activeCanonicalPaths] are preserved.
     */
    fun cleanupAbandonedCacheFiles(
        context: Context,
        activeCanonicalPaths: Set<String> = emptySet()
    ): Int {
        var deletedCount = 0
        val candidateDirs = listOfNotNull(
            context.codeCacheDir?.let { File(it, CACHE_SUBDIR_NAME) },
            context.cacheDir?.let { File(it, CACHE_SUBDIR_NAME) }
        )
        for (dir in candidateDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val children = dir.listFiles() ?: continue
            for (child in children) {
                try {
                    val canonical = child.canonicalPath
                    if (canonical in activeCanonicalPaths) continue
                    if (child.isFile && child.delete()) {
                        deletedCount++
                    }
                } catch (_: Throwable) {
                    // Best-effort cleanup
                }
            }
        }
        if (deletedCount > 0) {
            LogKeeper.log(context, TAG, "ABANDONED_CACHE_CLEANED count=$deletedCount")
        }
        return deletedCount
    }

    private fun isInsideCacheDir(cacheDir: File, candidate: File): Boolean {
        return try {
            val basePath = cacheDir.canonicalPath
            val candidatePath = candidate.canonicalPath
            candidatePath.startsWith(basePath + File.separator)
        } catch (_: Throwable) {
            false
        }
    }
}
