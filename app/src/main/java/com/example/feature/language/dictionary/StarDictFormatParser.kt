package com.example.feature.language.dictionary

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Pure-JVM & Android compatible parser for StarDict `.ifo` headers and `.dict` definition records.
 *
 * Supports:
 * - StarDict v2.4.2 (32-bit offsets) and v3.0.0 (`idxoffsetbits=32` or `64`).
 * - Both `sametypesequence`-driven records and self-tagged per-field records.
 * - Textual types: `m` (plain text), `h` (HTML), `x` (XDXF), `g` (Pango), `t` (phonetic), `w` (MediaWiki).
 * - Graceful skipping/handling of unsupported binary resources (`W`, `P`, `X`, `r`, `y`, `k`, etc.).
 */
object StarDictFormatParser {

    private const val IFO_MAGIC = "StarDict's dict ifo file"

    data class DecodedDefinition(
        val text: String,
        val phonetic: String? = null,
        val hasUnsupportedBinaryOnly: Boolean = false
    )

    /**
     * Parses and validates a StarDict `.ifo` header.
     * Throws [IllegalArgumentException] with an actionable message if invalid or incomplete.
     */
    fun parseIfo(rawContent: String): StarDictIfo {
        val cleaned = rawContent.removePrefix("\uFEFF")
        val lines = cleaned.lineSequence().map { it.trimEnd('\r') }.toList()
        if (lines.isEmpty() || lines.first().trim() != IFO_MAGIC) {
            throw IllegalArgumentException("Invalid StarDict .ifo header (missing magic line).")
        }

        val props = LinkedHashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            val eqIdx = line.indexOf('=')
            if (eqIdx <= 0) continue
            val key = line.substring(0, eqIdx).trim().lowercase()
            val value = line.substring(eqIdx + 1).trim()
            props[key] = value
        }

        val version = props["version"].orEmpty()
        if (version != "2.4.2" && version != "3.0.0") {
            throw IllegalArgumentException("Unsupported StarDict version: '${version.ifEmpty { "missing" }}'.")
        }

        val bookName = props["bookname"]?.trim().orEmpty()
        if (bookName.isEmpty()) {
            throw IllegalArgumentException("StarDict .ifo is missing required 'bookname'.")
        }

        val wordCount = props["wordcount"]?.toLongOrNull()
            ?: throw IllegalArgumentException("StarDict .ifo is missing valid 'wordcount'.")
        if (wordCount <= 0L) {
            throw IllegalArgumentException("StarDict dictionary '$bookName' contains no words.")
        }

        val idxFileSize = props["idxfilesize"]?.toLongOrNull()
            ?: throw IllegalArgumentException("StarDict .ifo is missing valid 'idxfilesize'.")
        if (idxFileSize <= 0L) {
            throw IllegalArgumentException("StarDict .ifo has invalid 'idxfilesize'.")
        }

        val synWordCount = props["synwordcount"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

        val offsetBits = props["idxoffsetbits"]?.toIntOrNull() ?: 32
        if (offsetBits != 32 && offsetBits != 64) {
            throw IllegalArgumentException("Unsupported idxoffsetbits value: $offsetBits (expected 32 or 64).")
        }

        return StarDictIfo(
            version = version,
            bookName = bookName,
            wordCount = wordCount,
            synWordCount = synWordCount,
            idxFileSize = idxFileSize,
            idxOffsetBits = offsetBits,
            sameTypeSequence = props["sametypesequence"].orEmpty(),
            author = props["author"].orEmpty(),
            email = props["email"].orEmpty(),
            website = props["website"].orEmpty(),
            description = props["description"].orEmpty(),
            date = props["date"].orEmpty()
        )
    }

    /**
     * Decodes a raw StarDict `.dict` definition slice into clean readable text and optional phonetic
     * pronunciation without crashing on binary or unsupported field types.
     */
    fun decodeDefinitionBytes(rawBytes: ByteArray, sameTypeSequence: String): DecodedDefinition {
        if (rawBytes.isEmpty()) {
            return DecodedDefinition(text = "", phonetic = null, hasUnsupportedBinaryOnly = false)
        }

        val fields = if (sameTypeSequence.isNotEmpty()) {
            parseSameTypeSequenceFields(rawBytes, sameTypeSequence)
        } else {
            parseSelfTaggedFields(rawBytes)
        }

        val textParts = mutableListOf<String>()
        var phonetic: String? = null
        var sawUnsupportedBinary = false

        for ((typeChar, payload) in fields) {
            when (typeChar) {
                'm', 'l' -> {
                    val decoded = decodeUtf8Safe(payload).trim()
                    if (decoded.isNotEmpty()) textParts.add(decoded)
                }
                'h' -> {
                    val formatted = formatHtmlDefinition(decodeUtf8Safe(payload))
                    if (formatted.isNotEmpty()) textParts.add(formatted)
                }
                'x' -> {
                    val formatted = formatXdxfDefinition(decodeUtf8Safe(payload))
                    if (formatted.isNotEmpty()) textParts.add(formatted)
                }
                'g' -> {
                    val formatted = formatPangoDefinition(decodeUtf8Safe(payload))
                    if (formatted.isNotEmpty()) textParts.add(formatted)
                }
                'w' -> {
                    val formatted = formatMediaWikiDefinition(decodeUtf8Safe(payload))
                    if (formatted.isNotEmpty()) textParts.add(formatted)
                }
                't' -> {
                    val rawPhonetic = decodeUtf8Safe(payload).trim()
                    if (rawPhonetic.isNotEmpty()) {
                        val wrapped = if (rawPhonetic.startsWith("/") || rawPhonetic.startsWith("[")) {
                            rawPhonetic
                        } else {
                            "/$rawPhonetic/"
                        }
                        if (phonetic == null) {
                            phonetic = wrapped
                        } else {
                            textParts.add(wrapped)
                        }
                    }
                }
                'y' -> {
                    val decoded = decodeUtf8Safe(payload).trim()
                    if (decoded.isNotEmpty()) textParts.add(decoded)
                }
                else -> {
                    // Uppercase types (W=wav, P=picture, X=experimental binary) or unsupported types
                    sawUnsupportedBinary = true
                }
            }
        }

        val combined = textParts.joinToString("\n\n").trim()
        return if (combined.isEmpty() && phonetic == null && sawUnsupportedBinary) {
            DecodedDefinition(
                text = "This entry only contains an unsupported binary resource.",
                phonetic = null,
                hasUnsupportedBinaryOnly = true
            )
        } else {
            DecodedDefinition(
                text = combined,
                phonetic = phonetic,
                hasUnsupportedBinaryOnly = false
            )
        }
    }

    private fun parseSameTypeSequenceFields(
        rawBytes: ByteArray,
        sequence: String
    ): List<Pair<Char, ByteArray>> {
        val result = ArrayList<Pair<Char, ByteArray>>(sequence.length)
        var pos = 0
        val total = rawBytes.size

        for (idx in sequence.indices) {
            if (pos >= total) break
            val typeChar = sequence[idx]
            val isLast = (idx == sequence.lastIndex)

            if (isLast) {
                val slice = rawBytes.copyOfRange(pos, total)
                val trimmedSlice = if (typeChar.isLowerCase() && slice.isNotEmpty() && slice.last() == 0.toByte()) {
                    slice.copyOf(slice.size - 1)
                } else {
                    slice
                }
                result.add(typeChar to trimmedSlice)
                pos = total
            } else if (typeChar.isUpperCase()) {
                if (pos + 4 > total) break
                val size = ByteBuffer.wrap(rawBytes, pos, 4).order(ByteOrder.BIG_ENDIAN).int
                pos += 4
                if (size < 0 || pos + size > total) break
                result.add(typeChar to rawBytes.copyOfRange(pos, pos + size))
                pos += size
            } else {
                var end = pos
                while (end < total && rawBytes[end] != 0.toByte()) {
                    end++
                }
                result.add(typeChar to rawBytes.copyOfRange(pos, end))
                pos = if (end < total) end + 1 else total
            }
        }
        return result
    }

    private fun parseSelfTaggedFields(rawBytes: ByteArray): List<Pair<Char, ByteArray>> {
        val result = mutableListOf<Pair<Char, ByteArray>>()
        var pos = 0
        val total = rawBytes.size

        while (pos < total) {
            val typeChar = rawBytes[pos].toInt().toChar()
            pos += 1
            if (pos > total) break

            if (typeChar.isUpperCase()) {
                if (pos + 4 > total) break
                val size = ByteBuffer.wrap(rawBytes, pos, 4).order(ByteOrder.BIG_ENDIAN).int
                pos += 4
                if (size < 0 || pos + size > total) break
                result.add(typeChar to rawBytes.copyOfRange(pos, pos + size))
                pos += size
            } else if (typeChar in 'a'..'z') {
                var end = pos
                while (end < total && rawBytes[end] != 0.toByte()) {
                    end++
                }
                result.add(typeChar to rawBytes.copyOfRange(pos, end))
                pos = if (end < total) end + 1 else total
            } else {
                // Fallback if a malformed entry lacks a valid type tag: treat remaining bytes as plain text
                result.add('m' to rawBytes.copyOfRange(pos - 1, total))
                break
            }
        }
        return result
    }

    private fun decodeUtf8Safe(bytes: ByteArray): String {
        return String(bytes, StandardCharsets.UTF_8)
            .replace("\u0000", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
    }

    internal fun formatHtmlDefinition(html: String): String {
        var text = html
        text = text.replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), "")
        text = text.replace(Regex("(?i)<br\\s*/?>"), "\n")
        text = text.replace(Regex("(?i)</(p|div|li|tr|h[1-6]|dd|dt|blockquote)>"), "\n")
        text = text.replace(Regex("(?i)<li[^>]*>"), "• ")
        text = text.replace(Regex("<[^>]+>"), "")
        return collapseWhitespaceLines(unescapeXmlHtmlEntities(text))
    }

    internal fun formatXdxfDefinition(xdxf: String): String {
        var text = xdxf
        text = text.replace(Regex("(?is)<k[^>]*>.*?</k>"), "")
        text = text.replace(Regex("(?i)<br\\s*/?>"), "\n")
        text = text.replace(Regex("(?i)<ex[^>]*>"), "\n  \"")
        text = text.replace(Regex("(?i)</ex>"), "\"\n")
        text = text.replace(Regex("(?i)<tr[^>]*>"), "[")
        text = text.replace(Regex("(?i)</tr>"), "] ")
        text = text.replace(Regex("(?i)</(def|dtrn|co|c|blockquote)>"), "\n")
        text = text.replace(Regex("<[^>]+>"), "")
        return collapseWhitespaceLines(unescapeXmlHtmlEntities(text))
    }

    internal fun formatPangoDefinition(pango: String): String {
        val stripped = pango.replace(Regex("<[^>]+>"), "")
        return collapseWhitespaceLines(unescapeXmlHtmlEntities(stripped))
    }

    internal fun formatMediaWikiDefinition(wiki: String): String {
        var text = wiki
        // [[target|label]] -> label, [[target]] -> target
        text = text.replace(Regex("\\[\\[[^|\\]]+\\|([^\\]]+)\\]\\]"), "$1")
        text = text.replace(Regex("\\[\\[([^\\]]+)\\]\\]"), "$1")
        // Bold / italic quotes
        text = text.replace("'''", "").replace("''", "")
        // Headings === Title === -> Title
        text = text.replace(Regex("(?m)^=+\\s*(.*?)\\s*=+$"), "$1")
        // Bullet/numbered list prefixes
        text = text.replace(Regex("(?m)^[*#]+\\s*"), "• ")
        // Strip remaining templates {{...}} cleanly to inner readable text if simple
        text = text.replace(Regex("\\{\\{([^{}|]+)\\}\\}"), "($1)")
        text = text.replace(Regex("\\{\\{[^{}]*\\}\\}"), "")
        return collapseWhitespaceLines(unescapeXmlHtmlEntities(text))
    }

    private fun unescapeXmlHtmlEntities(input: String): String {
        if (!input.contains('&')) return input
        var out = input
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
        out = Regex("&#(\\d+);").replace(out) { match ->
            val code = match.groupValues[1].toIntOrNull()
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else match.value
        }
        out = Regex("&#[xX]([0-9a-fA-F]+);").replace(out) { match ->
            val code = match.groupValues[1].toIntOrNull(16)
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else match.value
        }
        return out
    }

    private fun collapseWhitespaceLines(text: String): String {
        val lines = text.split('\n').map { it.replace(Regex("[ \\t]+"), " ").trim() }
        val sb = StringBuilder()
        var consecutiveBlank = 0
        for (line in lines) {
            if (line.isEmpty()) {
                if (sb.isNotEmpty() && consecutiveBlank == 0) {
                    sb.append('\n')
                    consecutiveBlank++
                }
            } else {
                if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                sb.append(line)
                consecutiveBlank = 0
            }
        }
        return sb.toString().trim()
    }
}
