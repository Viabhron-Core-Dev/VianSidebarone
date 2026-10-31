package com.example.feature.pwa

import android.webkit.MimeTypeMap

/**
 * PwaMimeHelper: Validates and translates HTML file chooser accept types into
 * valid Android Intent MIME configurations.
 *
 * Prevents invalid MIME types such as ".json" from being passed to Intent.setType(),
 * which causes ActivityNotFoundException crashes on Android.
 */
object PwaMimeHelper {

    private val EXTENSION_TO_MIME_MAP = mapOf(
        "json" to "application/json",
        "txt" to "text/plain",
        "text" to "text/plain",
        "csv" to "text/csv",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "xml" to "application/xml",
        "html" to "text/html",
        "htm" to "text/html",
        "js" to "application/javascript",
        "mjs" to "application/javascript",
        "css" to "text/css",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "svg" to "image/svg+xml",
        "webp" to "image/webp",
        "ico" to "image/x-icon",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "m4a" to "audio/mp4",
        "flac" to "audio/flac",
        "mp4" to "video/mp4",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "avi" to "video/x-msvideo"
    )

    /**
     * Checks if a string conforms to the standard type/subtype MIME format.
     */
    fun isValidMimeType(candidate: String): Boolean {
        if (candidate.isBlank()) return false
        val parts = candidate.split('/')
        if (parts.size != 2) return false
        val type = parts[0].trim()
        val subtype = parts[1].trim()
        if (type.isEmpty() || subtype.isEmpty()) return false
        // Disallow file extensions erroneously containing slashes or invalid characters
        if (type.contains('.') || subtype.contains(' ') || type.contains(' ')) return false
        return true
    }

    /**
     * Translates a single accept token (e.g. .json, image wildcard, text/plain)
     * into a valid Android MIME type, or null if invalid or unmapped.
     */
    fun translateAcceptToken(token: String): String? {
        val clean = token.trim().lowercase()
        if (clean.isEmpty()) return null

        // If it's already a valid MIME type (e.g. application/json, image wildcard, wildcard)
        if (isValidMimeType(clean)) {
            return clean
        }

        // If it's an extension starting with '.' (e.g. ".json") or a bare extension
        val ext = clean.removePrefix(".").trim()
        if (ext.isEmpty()) return null

        // Check custom well-known map first
        EXTENSION_TO_MIME_MAP[ext]?.let { return it }

        // Check system MimeTypeMap
        val systemMime = try {
            MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext)
        } catch (_: Throwable) {
            null
        }

        if (systemMime != null && isValidMimeType(systemMime)) {
            return systemMime
        }

        return null
    }

    /**
     * Translates an array of accept types from WebChromeClient.FileChooserParams
     * into a distinct list of valid Android MIME types.
     * Splits comma-separated values and eliminates invalid extensions.
     */
    fun translateAcceptTypes(rawAcceptTypes: Array<String>?): List<String> {
        if (rawAcceptTypes == null || rawAcceptTypes.isEmpty()) {
            return emptyList()
        }

        val result = mutableListOf<String>()
        for (raw in rawAcceptTypes) {
            if (raw.isBlank()) continue
            // HTML accept attributes can be comma-separated: ".json, .txt, application/pdf"
            val tokens = raw.split(',')
            for (token in tokens) {
                val translated = translateAcceptToken(token)
                if (translated != null && !result.contains(translated)) {
                    result.add(translated)
                }
            }
        }
        return result
    }

    /**
     * Determines the primary MIME type and any extra MIME types array for Intent(ACTION_OPEN_DOCUMENT).
     *
     * Rules:
     * - No valid accept types -> ("* / *", null)
     * - Exactly one valid MIME type -> (mime, null)
     * - Multiple valid MIME types -> ("* / *", arrayOf(mime1, mime2, ...))
     */
    fun resolveIntentMimeTypes(rawAcceptTypes: Array<String>?): Pair<String, Array<String>?> {
        val validTypes = translateAcceptTypes(rawAcceptTypes)

        return when {
            validTypes.isEmpty() -> {
                Pair("*/*", null)
            }
            validTypes.size == 1 -> {
                Pair(validTypes[0], null)
            }
            else -> {
                Pair("*/*", validTypes.toTypedArray())
            }
        }
    }
}
