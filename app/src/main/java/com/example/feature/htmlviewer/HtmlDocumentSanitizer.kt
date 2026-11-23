package com.example.feature.htmlviewer

import java.nio.charset.Charset

/**
 * Prepares local HTML content for safe, offline, image-free rendering inside the ephemeral
 * floating WebView:
 * - Strips scripts, frames, plugins, images, media elements, meta redirects, and external resource links.
 * - Scrubs CSS `url(...)` and `@import` strictly inside `<style>` blocks and `style="..."` attributes
 *   without mutating visible document body prose or code samples.
 * - Injects a strict offline Content-Security-Policy and responsive word-wrap styling without
 *   blocking horizontal zoom/panning on wide tables.
 */
object HtmlDocumentSanitizer {

    const val OFFLINE_CSP_CONTENT =
        "default-src 'none'; style-src 'unsafe-inline'; img-src 'none'; script-src 'none'; " +
            "connect-src 'none'; frame-src 'none'; font-src 'none'; media-src 'none'; object-src 'none';"

    private const val INJECTED_HEAD_GUARD =
        "<meta charset=\"UTF-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"$OFFLINE_CSP_CONTENT\">" +
            "<style>" +
            "img, picture, source, video, audio, iframe, frame, frameset, object, embed, applet, svg image { display: none !important; } " +
            "html, body { word-wrap: break-word; overflow-wrap: break-word; } " +
            "pre, code { white-space: pre-wrap !important; word-break: break-word !important; } " +
            "</style>"

    // Quote-aware attribute matcher so literal '>' inside quoted attribute values (e.g. alt="a > b") does not break stripping
    private const val ATTR_CHARS = "(?:[^\"'>]|\"[^\"]*\"|'[^']*')*"

    private val SCRIPT_BLOCK_REGEX = Regex("<script\\b$ATTR_CHARS>[\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE)
    private val SCRIPT_SELF_REGEX = Regex("<script\\b$ATTR_CHARS/?>", RegexOption.IGNORE_CASE)
    private val IFRAME_BLOCK_REGEX = Regex("<iframe\\b$ATTR_CHARS>[\\s\\S]*?</iframe\\s*>", RegexOption.IGNORE_CASE)
    private val IFRAME_SELF_REGEX = Regex("<iframe\\b$ATTR_CHARS/?>", RegexOption.IGNORE_CASE)
    private val OBJECT_BLOCK_REGEX = Regex("<(object|applet)\\b$ATTR_CHARS>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE)
    private val EMBED_TAG_REGEX = Regex("<(embed|object|applet)\\b$ATTR_CHARS/?>", RegexOption.IGNORE_CASE)
    private val PICTURE_BLOCK_REGEX = Regex("<picture\\b$ATTR_CHARS>[\\s\\S]*?</picture\\s*>", RegexOption.IGNORE_CASE)
    private val MEDIA_BLOCK_REGEX = Regex("<(video|audio)\\b$ATTR_CHARS>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE)
    private val IMG_TAG_REGEX = Regex("<(img|source|video|audio)\\b$ATTR_CHARS/?>", RegexOption.IGNORE_CASE)
    private val META_REFRESH_REGEX = Regex(
        "<meta\\b$ATTR_CHARS\\bhttp-equiv\\s*=\\s*[\"']?refresh[\"']?$ATTR_CHARS>",
        RegexOption.IGNORE_CASE
    )
    private val EXTERNAL_LINK_REGEX = Regex("<link\\b$ATTR_CHARS/?>", RegexOption.IGNORE_CASE)
    private val BASE_TAG_REGEX = Regex("<base\\b$ATTR_CHARS/?>", RegexOption.IGNORE_CASE)

    // Scoped CSS sanitization: touch ONLY <style> blocks and style="..." / style='...' attributes
    private val STYLE_BLOCK_REGEX = Regex("(<style\\b$ATTR_CHARS>)([\\s\\S]*?)(</style\\s*>)", RegexOption.IGNORE_CASE)
    private val INLINE_STYLE_DOUBLE_REGEX = Regex("(\\bstyle\\s*=\\s*\")([^\"]*)(\")", RegexOption.IGNORE_CASE)
    private val INLINE_STYLE_SINGLE_REGEX = Regex("(\\bstyle\\s*=\\s*')([^']*)(')", RegexOption.IGNORE_CASE)
    private val CSS_URL_REGEX = Regex("url\\s*\\([^)]*\\)", RegexOption.IGNORE_CASE)
    private val CSS_IMPORT_REGEX = Regex("@import\\b[^;\\r\\n]*;?", RegexOption.IGNORE_CASE)

    private val HEAD_OPEN_REGEX = Regex("<head\\b$ATTR_CHARS>", RegexOption.IGNORE_CASE)
    private val HTML_OPEN_REGEX = Regex("<html\\b$ATTR_CHARS>", RegexOption.IGNORE_CASE)
    private val TITLE_REGEX = Regex("<title\\b$ATTR_CHARS>([\\s\\S]*?)</title\\s*>", RegexOption.IGNORE_CASE)

    fun decodeHtmlBytes(rawBytes: ByteArray, declaredCharset: String? = null): String {
        val bomCharset = detectBomCharset(rawBytes)
        val charset = bomCharset
            ?: declaredCharset?.let { MhtmlParser.resolveCharsetOrUtf8(it) }
            ?: MhtmlParser.sniffMetaCharset(rawBytes)?.let { MhtmlParser.resolveCharsetOrUtf8(it) }
            ?: Charsets.UTF_8
        return String(rawBytes, charset).removePrefix("\uFEFF")
    }

    fun extractTitle(html: String): String? {
        val match = TITLE_REGEX.find(html) ?: return null
        val decoded = match.groupValues[1]
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return decoded.takeIf { it.isNotEmpty() }?.let {
            HtmlMhtFormatDetector.sanitizeDisplayTitle(it)
        }
    }

    fun prepareForOfflineViewer(rawHtml: String): String {
        var cleaned = rawHtml.removePrefix("\uFEFF")
        cleaned = SCRIPT_BLOCK_REGEX.replace(cleaned, "")
        cleaned = SCRIPT_SELF_REGEX.replace(cleaned, "")
        cleaned = IFRAME_BLOCK_REGEX.replace(cleaned, "")
        cleaned = IFRAME_SELF_REGEX.replace(cleaned, "")
        cleaned = OBJECT_BLOCK_REGEX.replace(cleaned, "")
        cleaned = EMBED_TAG_REGEX.replace(cleaned, "")
        cleaned = PICTURE_BLOCK_REGEX.replace(cleaned, "")
        cleaned = MEDIA_BLOCK_REGEX.replace(cleaned, "")
        cleaned = IMG_TAG_REGEX.replace(cleaned, "")
        cleaned = META_REFRESH_REGEX.replace(cleaned, "")
        cleaned = EXTERNAL_LINK_REGEX.replace(cleaned, "")
        cleaned = BASE_TAG_REGEX.replace(cleaned, "")

        // Scrub CSS url(...) and @import strictly inside <style> blocks and style attributes
        cleaned = STYLE_BLOCK_REGEX.replace(cleaned) { match ->
            match.groupValues[1] + sanitizeCssBlock(match.groupValues[2]) + match.groupValues[3]
        }
        cleaned = INLINE_STYLE_DOUBLE_REGEX.replace(cleaned) { match ->
            match.groupValues[1] + sanitizeCssBlock(match.groupValues[2]) + match.groupValues[3]
        }
        cleaned = INLINE_STYLE_SINGLE_REGEX.replace(cleaned) { match ->
            match.groupValues[1] + sanitizeCssBlock(match.groupValues[2]) + match.groupValues[3]
        }

        val headMatch = HEAD_OPEN_REGEX.find(cleaned)
        if (headMatch != null) {
            val insertIdx = headMatch.range.last + 1
            return cleaned.substring(0, insertIdx) + INJECTED_HEAD_GUARD + cleaned.substring(insertIdx)
        }

        val htmlMatch = HTML_OPEN_REGEX.find(cleaned)
        if (htmlMatch != null) {
            val insertIdx = htmlMatch.range.last + 1
            return cleaned.substring(0, insertIdx) + "<head>$INJECTED_HEAD_GUARD</head>" + cleaned.substring(insertIdx)
        }

        return "<!DOCTYPE html><html><head>$INJECTED_HEAD_GUARD</head><body>$cleaned</body></html>"
    }

    private fun sanitizeCssBlock(css: String): String {
        val noImport = CSS_IMPORT_REGEX.replace(css, "")
        return CSS_URL_REGEX.replace(noImport, "none")
    }

    internal fun detectBomCharset(bytes: ByteArray): Charset? {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return Charsets.UTF_8
        }
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return Charsets.UTF_16BE
            }
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return Charsets.UTF_16LE
            }
        }
        return null
    }
}
