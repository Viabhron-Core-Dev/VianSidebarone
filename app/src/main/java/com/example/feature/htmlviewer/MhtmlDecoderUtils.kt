package com.example.feature.htmlviewer

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Base64
import java.util.Locale

/**
 * Low-level MIME header parsing and transfer-decoding utilities shared by [MhtmlParser]
 * and [HtmlDocumentSanitizer].
 */
internal object MhtmlDecoderUtils {

    private val RFC2047_WORD_REGEX = Regex("=\\?([^?\\s]+)\\?([QqBb])\\?([^?\\s]*)\\?=")
    private val INTER_ENCODED_WORD_WS = Regex("(\\?=)\\s+(=\\?)")

    fun parseHeaders(rawHeaderBlock: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var currentKey: String? = null
        val currentValue = StringBuilder()

        val lines = rawHeaderBlock.split('\n')
        for (rawLine in lines) {
            val line = rawLine.trimEnd('\r')
            if (line.isEmpty()) continue
            if ((line[0] == ' ' || line[0] == '\t') && currentKey != null) {
                currentValue.append(' ').append(line.trim())
            } else {
                if (currentKey != null) {
                    result[currentKey] = currentValue.toString().trim()
                }
                val colon = line.indexOf(':')
                if (colon > 0) {
                    currentKey = line.substring(0, colon).trim().lowercase(Locale.US)
                    currentValue.setLength(0)
                    currentValue.append(line.substring(colon + 1).trim())
                } else {
                    currentKey = null
                    currentValue.setLength(0)
                }
            }
        }
        if (currentKey != null) {
            result[currentKey] = currentValue.toString().trim()
        }
        return result
    }

    /**
     * Decodes RFC 2045 quoted-printable bytes, including soft line breaks with optional
     * trailing spaces/tabs before CR/LF (`=[ \t]*\r?\n`).
     */
    fun decodeQuotedPrintable(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(input.size)
        var i = 0
        while (i < input.size) {
            val b = input[i].toInt() and 0xFF
            if (b == '='.code) {
                // RFC 2045 §6.7: soft line break may have transport-padding spaces/tabs before CRLF/LF
                var ws = i + 1
                while (ws < input.size && (input[ws] == ' '.code.toByte() || input[ws] == '\t'.code.toByte())) {
                    ws++
                }
                if (ws < input.size && input[ws] == '\n'.code.toByte()) {
                    i = ws + 1
                    continue
                }
                if (ws + 1 < input.size &&
                    input[ws] == '\r'.code.toByte() &&
                    input[ws + 1] == '\n'.code.toByte()
                ) {
                    i = ws + 2
                    continue
                }
                if (i + 2 < input.size) {
                    val hi = hexDigit(input[i + 1].toInt() and 0xFF)
                    val lo = hexDigit(input[i + 2].toInt() and 0xFF)
                    if (hi >= 0 && lo >= 0) {
                        out.write((hi shl 4) or lo)
                        i += 3
                        continue
                    }
                }
            }
            out.write(b)
            i++
        }
        return out.toByteArray()
    }

    private fun hexDigit(c: Int): Int = when (c) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'A'.code..'F'.code -> c - 'A'.code + 10
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        else -> -1
    }

    /**
     * Decodes Base64 using RFC 2045 MIME tolerance (handles wrapped lines and skips non-alphabet
     * transport whitespace). Rejects structurally invalid inputs safely with null.
     */
    fun decodeBase64Safely(input: ByteArray): ByteArray? {
        return try {
            val cleaned = ByteArrayOutputStream(input.size)
            for (b in input) {
                val c = b.toInt() and 0xFF
                val isBase64Char = (c in 'A'.code..'Z'.code) ||
                    (c in 'a'.code..'z'.code) ||
                    (c in '0'.code..'9'.code) ||
                    c == '+'.code || c == '/'.code || c == '='.code
                if (isBase64Char) {
                    cleaned.write(c)
                } else if (c != ' '.code && c != '\t'.code && c != '\r'.code && c != '\n'.code) {
                    // Reject clearly corrupt non-Base64 payloads rather than silently mangling bytes
                    return null
                }
            }
            val filtered = cleaned.toByteArray()
            if (filtered.isEmpty()) return ByteArray(0)
            // Pad missing '=' if a generator omitted trailing padding
            val rem = filtered.size % 4
            if (rem == 1) return null
            val padded = if (rem == 0) {
                filtered
            } else {
                val padCount = 4 - rem
                ByteArray(filtered.size + padCount).also { dst ->
                    System.arraycopy(filtered, 0, dst, 0, filtered.size)
                    for (p in 0 until padCount) {
                        dst[filtered.size + p] = '='.code.toByte()
                    }
                }
            }
            Base64.getMimeDecoder().decode(padded)
        } catch (_: Throwable) {
            null
        }
    }

    fun extractMediaType(contentType: String): String =
        contentType.substringBefore(';').trim().lowercase(Locale.US)

    fun extractParameter(headerValue: String, paramName: String): String? {
        val pattern = Regex(
            "(?:^|;)\\s*${Regex.escape(paramName)}\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^;\\s]+))",
            RegexOption.IGNORE_CASE
        )
        val match = pattern.find(headerValue) ?: return null
        return match.groups[1]?.value ?: match.groups[2]?.value ?: match.groups[3]?.value
    }

    fun sniffMetaCharset(bytes: ByteArray): String? {
        val sample = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val direct = Regex("<meta[^>]+charset\\s*=\\s*[\"']?([a-zA-Z0-9._-]+)", RegexOption.IGNORE_CASE)
            .find(sample)
        return direct?.groupValues?.getOrNull(1)?.trim()
    }

    fun resolveCharsetOrUtf8(charsetName: String?): Charset {
        if (charsetName.isNullOrBlank()) return Charsets.UTF_8
        return try {
            Charset.forName(charsetName.trim())
        } catch (_: Throwable) {
            Charsets.UTF_8
        }
    }

    /**
     * Decodes single or multi-word RFC 2047 encoded header values such as Subject lines
     * emitted by Chrome/Blink (`=?UTF-8?Q?...?= =?UTF-8?Q?...?=`).
     */
    fun decodeRfc2047HeaderValue(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.contains("=?")) return trimmed
        // RFC 2047 §6.2: whitespace strictly between adjacent encoded-words is ignored
        val collapsed = INTER_ENCODED_WORD_WS.replace(trimmed, "$1$2")
        return RFC2047_WORD_REGEX.replace(collapsed) { match ->
            val cs = resolveCharsetOrUtf8(match.groupValues[1])
            val enc = match.groupValues[2].uppercase(Locale.US)
            val payload = match.groupValues[3]
            try {
                when (enc) {
                    "B" -> String(Base64.getMimeDecoder().decode(payload), cs)
                    "Q" -> String(
                        decodeQuotedPrintable(payload.replace('_', ' ').toByteArray(Charsets.ISO_8859_1)),
                        cs
                    )
                    else -> match.value
                }
            } catch (_: Throwable) {
                match.value
            }
        }
    }
}
