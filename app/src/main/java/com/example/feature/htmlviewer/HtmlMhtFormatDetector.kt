package com.example.feature.htmlviewer

import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

enum class DetectedDocumentFormat(val label: String) {
    HTML("HTML"),
    MHT("MHT")
}

sealed class FormatDetectionResult {
    data class Supported(
        val format: DetectedDocumentFormat,
        val reason: String
    ) : FormatDetectionResult()

    data class Unsupported(
        val reasonCode: String,
        val userMessage: String
    ) : FormatDetectionResult()
}

sealed class BoundedReadResult {
    data class Success(val bytes: ByteArray) : BoundedReadResult()
    data class Oversized(val maxBytes: Int) : BoundedReadResult()
    data class ReadFailure(val cause: Throwable? = null) : BoundedReadResult()
}

/**
 * Identifies supported local HTML/HTM and MHT/MHTML documents using URI scheme validation,
 * provider MIME types, display-name extensions, and bounded header sniffing.
 */
object HtmlMhtFormatDetector {

    const val DEFAULT_MAX_FILE_BYTES: Int = 10 * 1024 * 1024 // 10 MiB
    const val HEADER_SNIFF_BYTES: Int = 4096

    val SUPPORTED_HTML_MIME_TYPES: Set<String> = setOf(
        "text/html",
        "application/xhtml+xml"
    )

    val SUPPORTED_MHT_MIME_TYPES: Set<String> = setOf(
        "multipart/related",
        "message/rfc822",
        "application/x-mimearchive",
        "application/mime",
        "text/mhtml"
    )

    private val GENERIC_MIME_TYPES: Set<String> = setOf(
        "application/octet-stream",
        "text/plain",
        "application/binary",
        "binary/octet-stream"
    )

    private val HTML_EXTENSIONS: Set<String> = setOf("html", "htm")
    private val MHT_EXTENSIONS: Set<String> = setOf("mht", "mhtml")
    private val ALLOWED_URI_SCHEMES: Set<String> = setOf("content", "file")

    fun isAllowedUriScheme(uri: Uri?): Boolean {
        val scheme = uri?.scheme?.lowercase(Locale.US) ?: return false
        return scheme in ALLOWED_URI_SCHEMES
    }

    fun isAllowedUriScheme(scheme: String?): Boolean {
        val normalized = scheme?.trim()?.lowercase(Locale.US) ?: return false
        return normalized in ALLOWED_URI_SCHEMES
    }

    fun extractExtension(displayName: String?): String? {
        if (displayName.isNullOrBlank()) return null
        val cleaned = displayName.substringAfterLast('/').substringAfterLast('\\').substringBefore('?').substringBefore('#')
        val dot = cleaned.lastIndexOf('.')
        if (dot <= 0 || dot >= cleaned.length - 1) return null
        return cleaned.substring(dot + 1).trim().lowercase(Locale.US)
    }

    fun sanitizeDisplayTitle(displayName: String?, fallback: String = "Document"): String {
        val raw = displayName?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.replace(Regex("[\\r\\n\\t\\u0000-\\u001F\\u007F]"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?: ""
        if (raw.isEmpty()) return fallback
        return if (raw.length > 64) raw.take(61).trimEnd() + "..." else raw
    }

    fun normalizeMimeType(mimeType: String?): String? {
        if (mimeType.isNullOrBlank()) return null
        return mimeType.substringBefore(';').trim().lowercase(Locale.US).takeIf { it.isNotEmpty() }
    }

    /**
     * Reads at most [maxBytes] from [inputStream]. If more bytes remain, stops immediately
     * and returns [BoundedReadResult.Oversized] without exhausting memory.
     */
    fun readBoundedBytes(
        inputStream: InputStream,
        maxBytes: Int = DEFAULT_MAX_FILE_BYTES
    ): BoundedReadResult {
        return try {
            val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = inputStream.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) {
                    return BoundedReadResult.Oversized(maxBytes)
                }
                out.write(buffer, 0, read)
            }
            BoundedReadResult.Success(out.toByteArray())
        } catch (t: Throwable) {
            BoundedReadResult.ReadFailure(t)
        }
    }

    /**
     * Detects whether a local file is HTML, MHT, or unsupported.
     * Resolves MIME vs extension disagreements safely via bounded header sniffing.
     */
    fun detect(
        uriScheme: String?,
        intentMimeType: String?,
        resolvedMimeType: String?,
        displayName: String?,
        headerBytes: ByteArray
    ): FormatDetectionResult {
        if (!isAllowedUriScheme(uriScheme)) {
            return FormatDetectionResult.Unsupported(
                reasonCode = "UNSUPPORTED_URI_SCHEME",
                userMessage = "Only local HTML and MHT documents can be opened here."
            )
        }

        if (headerBytes.isEmpty()) {
            return FormatDetectionResult.Unsupported(
                reasonCode = "EMPTY_DOCUMENT",
                userMessage = "This file is empty and cannot be displayed."
            )
        }

        if (hasKnownBinaryMagic(headerBytes)) {
            return FormatDetectionResult.Unsupported(
                reasonCode = "BINARY_FILE_REJECTED",
                userMessage = "Unsupported file format. Only local HTML, HTM, MHT, and MHTML files are supported."
            )
        }

        val sniffLen = minOf(headerBytes.size, HEADER_SNIFF_BYTES)
        val sniffText = decodeHeaderForSniffing(headerBytes, sniffLen)
        val mimeCandidates = listOfNotNull(
            normalizeMimeType(intentMimeType),
            normalizeMimeType(resolvedMimeType)
        )
        val ext = extractExtension(displayName)

        val looksLikeMhtHeader = looksLikeMhtmlHeader(sniffText)
        val looksLikeHtmlContent = looksLikeHtmlMarkup(sniffText)

        // 1. Content sniff takes precedence when an MHT archive is mislabeled as text/html or octet-stream
        if (looksLikeMhtHeader) {
            return FormatDetectionResult.Supported(DetectedDocumentFormat.MHT, "mhtml_header_sniff")
        }

        // 2. Explicit MHT extension or MHT MIME type
        if (ext in MHT_EXTENSIONS || mimeCandidates.any { it in SUPPORTED_MHT_MIME_TYPES }) {
            // If an .mht/.mhtml file actually contains raw HTML without MIME envelope, render it safely as HTML only if unmistakable HTML
            if (ext in MHT_EXTENSIONS && looksLikeHtmlContent && !sniffText.contains("MIME-Version", ignoreCase = true)) {
                return FormatDetectionResult.Supported(DetectedDocumentFormat.HTML, "mht_ext_raw_html_sniff")
            }
            return FormatDetectionResult.Supported(DetectedDocumentFormat.MHT, "mht_metadata")
        }

        // 3. Explicit HTML extension or HTML MIME type
        val hasHtmlExt = ext in HTML_EXTENSIONS
        val hasHtmlMime = mimeCandidates.any { it in SUPPORTED_HTML_MIME_TYPES }
        if (hasHtmlExt || hasHtmlMime) {
            // If MIME says text/html but extension is explicitly an unrelated non-web format (e.g. .pdf, .jpg, .zip) and content is not HTML, reject
            if (!hasHtmlExt && ext != null && !looksLikeHtmlContent) {
                return FormatDetectionResult.Unsupported(
                    reasonCode = "EXTENSION_CONTENT_MISMATCH",
                    userMessage = "Unsupported file format. This file does not appear to be a valid HTML or MHT document."
                )
            }
            return FormatDetectionResult.Supported(DetectedDocumentFormat.HTML, "html_metadata")
        }

        // 4. Generic/missing MIME & missing extension: accept only if content unmistakably starts with HTML markup
        val onlyGenericOrMissingMime = mimeCandidates.isEmpty() || mimeCandidates.all { it in GENERIC_MIME_TYPES }
        if (onlyGenericOrMissingMime && ext == null && looksLikeHtmlContent) {
            return FormatDetectionResult.Supported(DetectedDocumentFormat.HTML, "html_content_sniff")
        }

        return FormatDetectionResult.Unsupported(
            reasonCode = "UNSUPPORTED_FORMAT",
            userMessage = "Unsupported file format. Only local .html, .htm, .mht, and .mhtml files are supported."
        )
    }

    internal fun hasKnownBinaryMagic(bytes: ByteArray): Boolean {
        if (bytes.size >= 4) {
            // PDF: %PDF
            if (bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte()) return true
            // ZIP / EPUB / APK / DOCX: PK\x03\x04
            if (bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() && bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()) return true
            // PNG: \x89PNG
            if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return true
            // GIF: GIF8
            if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()) return true
            // ELF: \x7FELF
            if (bytes[0] == 0x7F.toByte() && bytes[1] == 0x45.toByte() && bytes[2] == 0x4C.toByte() && bytes[3] == 0x46.toByte()) return true
        }
        if (bytes.size >= 3) {
            // JPEG: FF D8 FF
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return true
        }
        // Reject files with high density of NUL bytes unless UTF-16 BOM is present
        val hasUtf16Bom = bytes.size >= 2 && (
            (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) ||
                (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            )
        if (!hasUtf16Bom) {
            val checkLen = minOf(bytes.size, 512)
            var nulCount = 0
            for (i in 0 until checkLen) {
                if (bytes[i] == 0.toByte()) nulCount++
            }
            if (nulCount > 4) return true
        }
        return false
    }

    internal fun looksLikeMhtmlHeader(sniffText: String): Boolean {
        val trimmedStart = sniffText.trimStart('\uFEFF', '\r', '\n')
        val headerBlock = trimmedStart.substringBefore("\r\n\r\n").substringBefore("\n\n")
        val lower = headerBlock.lowercase(Locale.US)
        val hasMimeVersion = Regex("(?m)^mime-version\\s*:").containsMatchIn(lower)
        val hasMultipartContentType = Regex("(?m)^content-type\\s*:\\s*multipart/").containsMatchIn(lower)
        val hasBoundaryParam = Regex("\\bboundary\\s*=").containsMatchIn(lower)
        val hasSnapshotHeader = Regex("(?m)^(from|snapshot-content-location|content-location)\\s*:").containsMatchIn(lower)
        return (hasMimeVersion && (hasMultipartContentType || hasBoundaryParam || hasSnapshotHeader)) ||
            (hasMultipartContentType && hasBoundaryParam)
    }

    internal fun looksLikeHtmlMarkup(sniffText: String): Boolean {
        val trimmed = sniffText.trimStart('\uFEFF', ' ', '\t', '\r', '\n').lowercase(Locale.US)
        if (trimmed.startsWith("<!doctype html") ||
            trimmed.startsWith("<html") ||
            trimmed.startsWith("<?xml") ||
            trimmed.startsWith("<head") ||
            trimmed.startsWith("<body")
        ) {
            return true
        }
        return Regex("<(html|head|body|title|meta|h[1-6]|div|p|article|section|table)\\b").containsMatchIn(trimmed)
    }

    internal fun decodeHeaderForSniffing(bytes: ByteArray, length: Int): String {
        val len = minOf(bytes.size, length)
        if (len >= 2) {
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                val evenLen = len and 1.inv()
                return String(bytes, 0, evenLen, Charsets.UTF_16BE).removePrefix("\uFEFF")
            }
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                val evenLen = len and 1.inv()
                return String(bytes, 0, evenLen, Charsets.UTF_16LE).removePrefix("\uFEFF")
            }
        }
        if (len >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, len - 3, Charsets.UTF_8)
        }
        return String(bytes, 0, len, Charsets.ISO_8859_1)
    }
}
