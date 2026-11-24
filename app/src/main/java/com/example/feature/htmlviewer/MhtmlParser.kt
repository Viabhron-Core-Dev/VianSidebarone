package com.example.feature.htmlviewer

import java.util.Locale

enum class MhtmlErrorCode {
    OVERSIZED_ARCHIVE,
    MALFORMED_ARCHIVE,
    MALFORMED_BOUNDARY,
    MISSING_HTML_PART,
    UNSUPPORTED_ENCODING,
    OVERSIZED_HTML
}

sealed class MhtmlParseResult {
    data class Success(
        val html: String,
        val charsetName: String,
        val subjectTitle: String?,
        val ignoredNonHtmlPartCount: Int
    ) : MhtmlParseResult()

    data class Error(
        val code: MhtmlErrorCode,
        val userMessage: String
    ) : MhtmlParseResult()
}

/**
 * Small, bounded MIME multipart parser tailored for local .mht / .mhtml web archives.
 *
 * Memory & security invariants:
 * - Uses zero-copy byte index ranges (`start until endExclusive`) to scan parts and headers.
 * - Never copies embedded image, media, font, CSS, JS, or attachment byte payloads.
 * - Copies and decodes ONLY the single primary HTML part (bounded by [maxHtmlBytes]).
 */
object MhtmlParser {

    const val DEFAULT_MAX_ARCHIVE_BYTES: Int = 10 * 1024 * 1024 // 10 MiB
    const val DEFAULT_MAX_HTML_BYTES: Int = 5 * 1024 * 1024 // 5 MiB
    const val MAX_PARTS: Int = 256
    const val MAX_NESTING_DEPTH: Int = 4

    private data class ByteSlice(val start: Int, val endExclusive: Int) {
        val size: Int get() = (endExclusive - start).coerceAtLeast(0)
    }

    private data class SlicedMimeEntity(
        val headers: Map<String, String>,
        val body: ByteSlice
    )

    fun parse(
        archiveBytes: ByteArray,
        maxArchiveBytes: Int = DEFAULT_MAX_ARCHIVE_BYTES,
        maxHtmlBytes: Int = DEFAULT_MAX_HTML_BYTES
    ): MhtmlParseResult {
        if (archiveBytes.isEmpty()) {
            return MhtmlParseResult.Error(
                MhtmlErrorCode.MALFORMED_ARCHIVE,
                "This MHT archive is empty."
            )
        }
        if (archiveBytes.size > maxArchiveBytes) {
            return MhtmlParseResult.Error(
                MhtmlErrorCode.OVERSIZED_ARCHIVE,
                "This MHT archive is too large to open in the lightweight viewer."
            )
        }

        val root = splitHeadersAndBody(archiveBytes, ByteSlice(0, archiveBytes.size))
            ?: return MhtmlParseResult.Error(
                MhtmlErrorCode.MALFORMED_ARCHIVE,
                "This MHT archive has malformed MIME headers."
            )

        val subjectTitle = root.headers["subject"]?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { MhtmlDecoderUtils.decodeRfc2047HeaderValue(it) }
        val topContentType = root.headers["content-type"]?.trim()
            ?: return MhtmlParseResult.Error(
                MhtmlErrorCode.MALFORMED_ARCHIVE,
                "This MHT archive is missing a Content-Type header."
            )

        val mediaType = MhtmlDecoderUtils.extractMediaType(topContentType)
        if (mediaType == "text/html" || mediaType == "application/xhtml+xml") {
            return decodeHtmlEntity(
                sourceBytes = archiveBytes,
                entity = root,
                contentTypeHeader = topContentType,
                subjectTitle = subjectTitle,
                ignoredPartsCount = 0,
                maxHtmlBytes = maxHtmlBytes
            )
        }

        if (!mediaType.startsWith("multipart/")) {
            return MhtmlParseResult.Error(
                MhtmlErrorCode.MISSING_HTML_PART,
                "No HTML webpage content was found inside this MHT archive."
            )
        }

        val ignoredCounter = IntArray(1)
        return parseMultipartEntity(
            sourceBytes = archiveBytes,
            entity = root,
            contentTypeHeader = topContentType,
            subjectTitle = subjectTitle,
            ignoredCounter = ignoredCounter,
            depth = 0,
            maxHtmlBytes = maxHtmlBytes
        )
    }

    private fun parseMultipartEntity(
        sourceBytes: ByteArray,
        entity: SlicedMimeEntity,
        contentTypeHeader: String,
        subjectTitle: String?,
        ignoredCounter: IntArray,
        depth: Int,
        maxHtmlBytes: Int
    ): MhtmlParseResult {
        if (depth > MAX_NESTING_DEPTH) {
            return MhtmlParseResult.Error(
                MhtmlErrorCode.MALFORMED_ARCHIVE,
                "This MHT archive has unsupported MIME nesting."
            )
        }

        val boundary = MhtmlDecoderUtils.extractParameter(contentTypeHeader, "boundary")
        if (boundary.isNullOrBlank() || boundary.length > 200) {
            return MhtmlParseResult.Error(
                MhtmlErrorCode.MALFORMED_BOUNDARY,
                "This MHT archive has a missing or malformed MIME boundary."
            )
        }

        val partSlices = splitMultipartBodySlices(sourceBytes, entity.body, boundary)
        if (partSlices.isEmpty()) {
            return MhtmlParseResult.Error(
                MhtmlErrorCode.MALFORMED_BOUNDARY,
                "Could not locate valid MIME parts for the declared boundary."
            )
        }

        val startContentId = MhtmlDecoderUtils.extractParameter(contentTypeHeader, "start")
            ?.let { normalizeContentId(it) }
        val orderedIndices = orderPartIndicesForStartCid(sourceBytes, partSlices, startContentId)

        var firstEncodingError: MhtmlParseResult.Error? = null
        var resolvedHtmlResult: MhtmlParseResult.Success? = null
        var resolvedPartIndex = -1

        for (index in orderedIndices) {
            val part = splitHeadersAndBody(sourceBytes, partSlices[index]) ?: continue
            val partContentType = part.headers["content-type"] ?: "text/plain"
            val partMediaType = MhtmlDecoderUtils.extractMediaType(partContentType)
            val disposition = part.headers["content-disposition"]?.lowercase(Locale.US) ?: ""

            if (isIgnoredNonHtmlPart(partMediaType, disposition)) {
                continue
            }

            if (partMediaType == "text/html" || partMediaType == "application/xhtml+xml") {
                val decoded = decodeHtmlEntity(
                    sourceBytes = sourceBytes,
                    entity = part,
                    contentTypeHeader = partContentType,
                    subjectTitle = subjectTitle,
                    ignoredPartsCount = ignoredCounter[0],
                    maxHtmlBytes = maxHtmlBytes
                )
                if (decoded is MhtmlParseResult.Success) {
                    resolvedHtmlResult = decoded
                    resolvedPartIndex = index
                    break
                } else if (decoded is MhtmlParseResult.Error) {
                    if (decoded.code == MhtmlErrorCode.OVERSIZED_HTML) return decoded
                    if (firstEncodingError == null) firstEncodingError = decoded
                }
            } else if (partMediaType.startsWith("multipart/")) {
                val nested = parseMultipartEntity(
                    sourceBytes = sourceBytes,
                    entity = part,
                    contentTypeHeader = partContentType,
                    subjectTitle = subjectTitle,
                    ignoredCounter = ignoredCounter,
                    depth = depth + 1,
                    maxHtmlBytes = maxHtmlBytes
                )
                if (nested is MhtmlParseResult.Success) {
                    resolvedHtmlResult = nested
                    resolvedPartIndex = index
                    break
                } else if (nested is MhtmlParseResult.Error && nested.code == MhtmlErrorCode.OVERSIZED_HTML) {
                    return nested
                }
            }
        }

        if (resolvedHtmlResult != null) {
            // Count all non-HTML sibling parts (zero-copy header scan) for accurate reporting
            var siblingIgnored = 0
            for (idx in partSlices.indices) {
                if (idx == resolvedPartIndex) continue
                val sibling = splitHeadersAndBody(sourceBytes, partSlices[idx]) ?: continue
                val siblingCt = sibling.headers["content-type"] ?: "text/plain"
                val siblingMedia = MhtmlDecoderUtils.extractMediaType(siblingCt)
                val siblingDisp = sibling.headers["content-disposition"]?.lowercase(Locale.US) ?: ""
                if (isIgnoredNonHtmlPart(siblingMedia, siblingDisp) ||
                    (siblingMedia != "text/html" && siblingMedia != "application/xhtml+xml" && !siblingMedia.startsWith("multipart/"))
                ) {
                    siblingIgnored++
                }
            }
            ignoredCounter[0] += siblingIgnored
            return resolvedHtmlResult.copy(ignoredNonHtmlPartCount = ignoredCounter[0])
        }

        return firstEncodingError ?: MhtmlParseResult.Error(
            MhtmlErrorCode.MISSING_HTML_PART,
            "No HTML document part was found in this MHT archive."
        )
    }

    private fun normalizeContentId(raw: String): String =
        raw.trim().removePrefix("<").removeSuffix(">").trim().lowercase(Locale.US)

    private fun orderPartIndicesForStartCid(
        sourceBytes: ByteArray,
        partSlices: List<ByteSlice>,
        startContentId: String?
    ): List<Int> {
        if (startContentId.isNullOrEmpty()) return partSlices.indices.toList()
        var preferredIdx = -1
        for (idx in partSlices.indices) {
            val part = splitHeadersAndBody(sourceBytes, partSlices[idx]) ?: continue
            val cid = part.headers["content-id"]?.let { normalizeContentId(it) }
            if (cid == startContentId) {
                preferredIdx = idx
                break
            }
        }
        if (preferredIdx <= 0) return partSlices.indices.toList()
        val ordered = ArrayList<Int>(partSlices.size)
        ordered.add(preferredIdx)
        for (idx in partSlices.indices) {
            if (idx != preferredIdx) ordered.add(idx)
        }
        return ordered
    }

    private fun isIgnoredNonHtmlPart(partMediaType: String, disposition: String): Boolean {
        return disposition.startsWith("attachment") ||
            partMediaType.startsWith("image/") ||
            partMediaType.startsWith("audio/") ||
            partMediaType.startsWith("video/") ||
            partMediaType.startsWith("font/") ||
            partMediaType == "text/css" ||
            partMediaType == "application/javascript" ||
            partMediaType == "text/javascript"
    }

    private fun decodeHtmlEntity(
        sourceBytes: ByteArray,
        entity: SlicedMimeEntity,
        contentTypeHeader: String,
        subjectTitle: String?,
        ignoredPartsCount: Int,
        maxHtmlBytes: Int
    ): MhtmlParseResult {
        val encoding = entity.headers["content-transfer-encoding"]
            ?.trim()
            ?.lowercase(Locale.US)
            ?: "8bit"

        // Pre-check raw unencoded size before copying when encoding does not shrink payload
        if ((encoding.isEmpty() || encoding == "7bit" || encoding == "8bit" || encoding == "binary") &&
            entity.body.size > maxHtmlBytes
        ) {
            return MhtmlParseResult.Error(
                MhtmlErrorCode.OVERSIZED_HTML,
                "The extracted HTML document is too large to display safely."
            )
        }

        val rawPartBytes = sourceBytes.copyOfRange(entity.body.start, entity.body.endExclusive)
        val decodedBytes = when (encoding) {
            "", "7bit", "8bit", "binary" -> rawPartBytes
            "quoted-printable" -> MhtmlDecoderUtils.decodeQuotedPrintable(rawPartBytes)
            "base64" -> MhtmlDecoderUtils.decodeBase64Safely(rawPartBytes)
                ?: return MhtmlParseResult.Error(
                    MhtmlErrorCode.MALFORMED_ARCHIVE,
                    "The Base64 HTML content inside this MHT archive could not be decoded."
                )
            else -> return MhtmlParseResult.Error(
                MhtmlErrorCode.UNSUPPORTED_ENCODING,
                "Unsupported MHT transfer encoding: $encoding."
            )
        }

        if (decodedBytes.size > maxHtmlBytes) {
            return MhtmlParseResult.Error(
                MhtmlErrorCode.OVERSIZED_HTML,
                "The extracted HTML document is too large to display safely."
            )
        }

        val bomCharset = HtmlDocumentSanitizer.detectBomCharset(decodedBytes)
        val charset = bomCharset
            ?: MhtmlDecoderUtils.extractParameter(contentTypeHeader, "charset")
                ?.let { MhtmlDecoderUtils.resolveCharsetOrUtf8(it) }
            ?: MhtmlDecoderUtils.sniffMetaCharset(decodedBytes)
                ?.let { MhtmlDecoderUtils.resolveCharsetOrUtf8(it) }
            ?: Charsets.UTF_8
        val html = String(decodedBytes, charset).removePrefix("\uFEFF")

        return MhtmlParseResult.Success(
            html = html,
            charsetName = charset.name(),
            subjectTitle = subjectTitle,
            ignoredNonHtmlPartCount = ignoredPartsCount
        )
    }

    private fun splitHeadersAndBody(bytes: ByteArray, slice: ByteSlice): SlicedMimeEntity? {
        var headerEnd = -1
        var bodyStart = -1

        var start = slice.start
        while (start < slice.endExclusive &&
            (bytes[start] == '\r'.code.toByte() || bytes[start] == '\n'.code.toByte())
        ) {
            start++
        }

        var i = start
        while (i < slice.endExclusive) {
            if (i + 3 < slice.endExclusive &&
                bytes[i] == '\r'.code.toByte() &&
                bytes[i + 1] == '\n'.code.toByte() &&
                bytes[i + 2] == '\r'.code.toByte() &&
                bytes[i + 3] == '\n'.code.toByte()
            ) {
                headerEnd = i
                bodyStart = i + 4
                break
            }
            if (i + 1 < slice.endExclusive &&
                bytes[i] == '\n'.code.toByte() &&
                bytes[i + 1] == '\n'.code.toByte()
            ) {
                headerEnd = i
                bodyStart = i + 2
                break
            }
            i++
        }

        if (headerEnd <= start || bodyStart < 0 || bodyStart > slice.endExclusive) {
            return null
        }

        val headerStr = String(bytes, start, headerEnd - start, Charsets.ISO_8859_1)
        val headers = MhtmlDecoderUtils.parseHeaders(headerStr)
        if (headers.isEmpty()) return null
        return SlicedMimeEntity(headers, ByteSlice(bodyStart, slice.endExclusive))
    }

    private fun splitMultipartBodySlices(
        sourceBytes: ByteArray,
        body: ByteSlice,
        boundary: String
    ): List<ByteSlice> {
        val marker = "--$boundary".toByteArray(Charsets.ISO_8859_1)
        val closingMarker = "--$boundary--".toByteArray(Charsets.ISO_8859_1)
        val positions = ArrayList<Pair<Int, Boolean>>() // (lineStartIndex, isClosing)

        var lineStart = body.start
        while (lineStart < body.endExclusive && positions.size <= MAX_PARTS + 2) {
            var lineEnd = lineStart
            while (lineEnd < body.endExclusive && sourceBytes[lineEnd] != '\n'.code.toByte()) {
                lineEnd++
            }
            var contentEnd = lineEnd
            if (contentEnd > lineStart && sourceBytes[contentEnd - 1] == '\r'.code.toByte()) {
                contentEnd--
            }
            while (contentEnd > lineStart &&
                (sourceBytes[contentEnd - 1] == ' '.code.toByte() || sourceBytes[contentEnd - 1] == '\t'.code.toByte())
            ) {
                contentEnd--
            }

            val lineLen = contentEnd - lineStart
            if (lineLen == closingMarker.size && matchesAt(sourceBytes, lineStart, closingMarker, body.endExclusive)) {
                positions.add(Pair(lineStart, true))
                break
            } else if (lineLen == marker.size && matchesAt(sourceBytes, lineStart, marker, body.endExclusive)) {
                positions.add(Pair(lineStart, false))
            }

            lineStart = if (lineEnd < body.endExclusive) lineEnd + 1 else body.endExclusive
        }

        if (positions.isEmpty()) return emptyList()

        val parts = ArrayList<ByteSlice>()
        for (idx in positions.indices) {
            val (pos, isClosing) = positions[idx]
            if (isClosing) break
            var partStart = pos + marker.size
            while (partStart < body.endExclusive && sourceBytes[partStart] != '\n'.code.toByte()) {
                partStart++
            }
            if (partStart < body.endExclusive && sourceBytes[partStart] == '\n'.code.toByte()) {
                partStart++
            }
            val nextBoundaryStart = if (idx + 1 < positions.size) positions[idx + 1].first else body.endExclusive
            var partEnd = nextBoundaryStart
            if (partEnd > partStart && sourceBytes[partEnd - 1] == '\n'.code.toByte()) {
                partEnd--
                if (partEnd > partStart && sourceBytes[partEnd - 1] == '\r'.code.toByte()) {
                    partEnd--
                }
            }
            if (partEnd > partStart && parts.size < MAX_PARTS) {
                parts.add(ByteSlice(partStart, partEnd))
            }
        }
        return parts
    }

    private fun matchesAt(source: ByteArray, offset: Int, target: ByteArray, limit: Int): Boolean {
        if (offset + target.size > limit) return false
        for (i in target.indices) {
            if (source[offset + i] != target[i]) return false
        }
        return true
    }

    internal fun sniffMetaCharset(bytes: ByteArray): String? = MhtmlDecoderUtils.sniffMetaCharset(bytes)

    internal fun resolveCharsetOrUtf8(charsetName: String?) = MhtmlDecoderUtils.resolveCharsetOrUtf8(charsetName)
}
