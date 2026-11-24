package com.example.feature.htmlviewer

import com.example.feature.sidebar.ALL_FLOATING_WINDOWS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64

class HtmlMhtParserAndFormatTest {

    // -------------------------------------------------------------------------
    // 1. MIME & Format Identification Tests
    // -------------------------------------------------------------------------

    @Test
    fun testHtmlAndHtmIdentification() {
        val htmlBytes = "<!DOCTYPE html><html><body><h1>Hello</h1></body></html>".toByteArray(Charsets.UTF_8)

        val resHtml = HtmlMhtFormatDetector.detect(
            uriScheme = "content",
            intentMimeType = "text/html",
            resolvedMimeType = "text/html",
            displayName = "page.html",
            headerBytes = htmlBytes
        )
        assertTrue(resHtml is FormatDetectionResult.Supported)
        assertEquals(DetectedDocumentFormat.HTML, (resHtml as FormatDetectionResult.Supported).format)

        val resHtm = HtmlMhtFormatDetector.detect(
            uriScheme = "file",
            intentMimeType = null,
            resolvedMimeType = "application/xhtml+xml",
            displayName = "notes.HTM",
            headerBytes = htmlBytes
        )
        assertTrue(resHtm is FormatDetectionResult.Supported)
        assertEquals(DetectedDocumentFormat.HTML, (resHtm as FormatDetectionResult.Supported).format)
    }

    @Test
    fun testUtf16HtmlSniffingAndDecodingWorksWhenMimeAndExtensionMissing() {
        val utf16LeBytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "<!DOCTYPE html><html><body><h1>UTF-16 Page</h1></body></html>".toByteArray(Charsets.UTF_16LE)

        val detected = HtmlMhtFormatDetector.detect(
            uriScheme = "content",
            intentMimeType = "application/octet-stream",
            resolvedMimeType = null,
            displayName = "unnamed_document",
            headerBytes = utf16LeBytes
        )
        assertTrue("UTF-16LE HTML must be sniffed cleanly", detected is FormatDetectionResult.Supported)
        assertEquals(DetectedDocumentFormat.HTML, (detected as FormatDetectionResult.Supported).format)

        val decoded = HtmlDocumentSanitizer.decodeHtmlBytes(utf16LeBytes)
        assertTrue(decoded.contains("<h1>UTF-16 Page</h1>"))
    }

    @Test
    fun testMhtAndMhtmlIdentification() {
        val mhtBytes = (
            "From: <Saved by Blink>\r\n" +
                "MIME-Version: 1.0\r\n" +
                "Content-Type: multipart/related; boundary=\"----=_Part_1\"\r\n\r\n" +
                "------=_Part_1\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n\r\n" +
                "<html><body>Saved</body></html>\r\n" +
                "------=_Part_1--"
            ).toByteArray(Charsets.UTF_8)

        val resMht = HtmlMhtFormatDetector.detect(
            uriScheme = "content",
            intentMimeType = "multipart/related",
            resolvedMimeType = "message/rfc822",
            displayName = "article.mht",
            headerBytes = mhtBytes
        )
        assertTrue(resMht is FormatDetectionResult.Supported)
        assertEquals(DetectedDocumentFormat.MHT, (resMht as FormatDetectionResult.Supported).format)

        val resMhtml = HtmlMhtFormatDetector.detect(
            uriScheme = "content",
            intentMimeType = "application/x-mimearchive",
            resolvedMimeType = null,
            displayName = "archive.mhtml",
            headerBytes = mhtBytes
        )
        assertTrue(resMhtml is FormatDetectionResult.Supported)
        assertEquals(DetectedDocumentFormat.MHT, (resMhtml as FormatDetectionResult.Supported).format)
    }

    @Test
    fun testMimeMetadataAndExtensionDisagreementHandledSafely() {
        val mhtBytes = (
            "MIME-Version: 1.0\r\n" +
                "Content-Type: multipart/related; boundary=\"b1\"\r\n\r\n" +
                "--b1\r\nContent-Type: text/html\r\n\r\n<p>MHT</p>\r\n--b1--"
            ).toByteArray(Charsets.UTF_8)

        // File manager reports text/html for an actual .mht archive -> must detect as MHT
        val mhtLabeledAsHtml = HtmlMhtFormatDetector.detect(
            uriScheme = "content",
            intentMimeType = "text/html",
            resolvedMimeType = "text/html",
            displayName = "saved.mht",
            headerBytes = mhtBytes
        )
        assertTrue(mhtLabeledAsHtml is FormatDetectionResult.Supported)
        assertEquals(DetectedDocumentFormat.MHT, (mhtLabeledAsHtml as FormatDetectionResult.Supported).format)

        // File manager reports application/octet-stream for an .html file -> must detect as HTML
        val htmlAsOctetStream = HtmlMhtFormatDetector.detect(
            uriScheme = "content",
            intentMimeType = "application/octet-stream",
            resolvedMimeType = "application/octet-stream",
            displayName = "local_report.html",
            headerBytes = "<html><body><p>Report</p></body></html>".toByteArray(Charsets.UTF_8)
        )
        assertTrue(htmlAsOctetStream is FormatDetectionResult.Supported)
        assertEquals(DetectedDocumentFormat.HTML, (htmlAsOctetStream as FormatDetectionResult.Supported).format)

        // Binary PDF renamed to .html or sent with text/html -> must be rejected
        val pdfBytes = "%PDF-1.7\n1 0 obj\n<<>>\nendobj".toByteArray(Charsets.ISO_8859_1)
        val fakeHtmlPdf = HtmlMhtFormatDetector.detect(
            uriScheme = "content",
            intentMimeType = "text/html",
            resolvedMimeType = "text/html",
            displayName = "tricky.html",
            headerBytes = pdfBytes
        )
        assertTrue(fakeHtmlPdf is FormatDetectionResult.Unsupported)
        assertEquals("BINARY_FILE_REJECTED", (fakeHtmlPdf as FormatDetectionResult.Unsupported).reasonCode)
    }

    @Test
    fun testUnsupportedFileTypesAndInvalidUrisRejected() {
        val plainText = "Just a plain text note with no markup.".toByteArray(Charsets.UTF_8)
        val txtRes = HtmlMhtFormatDetector.detect(
            uriScheme = "content",
            intentMimeType = "text/plain",
            resolvedMimeType = "text/plain",
            displayName = "readme.txt",
            headerBytes = plainText
        )
        assertTrue(txtRes is FormatDetectionResult.Unsupported)

        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val pngRes = HtmlMhtFormatDetector.detect(
            uriScheme = "content",
            intentMimeType = "image/png",
            resolvedMimeType = "image/png",
            displayName = "photo.png",
            headerBytes = pngHeader
        )
        assertTrue(pngRes is FormatDetectionResult.Unsupported)

        val httpRes = HtmlMhtFormatDetector.detect(
            uriScheme = "https",
            intentMimeType = "text/html",
            resolvedMimeType = "text/html",
            displayName = "remote.html",
            headerBytes = "<html><body>Remote</body></html>".toByteArray(Charsets.UTF_8)
        )
        assertTrue(httpRes is FormatDetectionResult.Unsupported)
        assertEquals("UNSUPPORTED_URI_SCHEME", (httpRes as FormatDetectionResult.Unsupported).reasonCode)

        val nullSchemeRes = HtmlMhtFormatDetector.detect(
            uriScheme = null,
            intentMimeType = "text/html",
            resolvedMimeType = null,
            displayName = "doc.html",
            headerBytes = "<html></html>".toByteArray(Charsets.UTF_8)
        )
        assertTrue(nullSchemeRes is FormatDetectionResult.Unsupported)
    }

    @Test
    fun testBoundedReaderRejectsOversizedStreamsSafely() {
        val data = ByteArray(1024) { 'A'.code.toByte() }
        val ok = HtmlMhtFormatDetector.readBoundedBytes(ByteArrayInputStream(data), maxBytes = 1024)
        assertTrue(ok is BoundedReadResult.Success)
        assertEquals(1024, (ok as BoundedReadResult.Success).bytes.size)

        val oversized = HtmlMhtFormatDetector.readBoundedBytes(ByteArrayInputStream(data), maxBytes = 512)
        assertTrue(oversized is BoundedReadResult.Oversized)
    }

    // -------------------------------------------------------------------------
    // 2. MHTML Parser Tests
    // -------------------------------------------------------------------------

    @Test
    fun testValidMultipartArchiveExtractsHtmlAndCountsLeadingAndTrailingIgnoredParts() {
        val fakePngBase64 = Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4))
        // HTML part appears BEFORE trailing CSS and image parts (standard Blink/Chrome order)
        val archive = (
            "From: <Saved by Chrome>\r\n" +
                "Subject: =?UTF-8?Q?Quarterly_=E2=80=94?= =?UTF-8?Q?_Summary?=\r\n" +
                "MIME-Version: 1.0\r\n" +
                "Content-Type: multipart/related;\r\n" +
                "\ttype=\"text/html\";\r\n" +
                "\tboundary=\"----=_NextPart_000_0001\"\r\n" +
                "\r\n" +
                "------=_NextPart_000_0001\r\n" +
                "Content-Type: text/html; charset=\"utf-8\"\r\n" +
                "Content-Transfer-Encoding: 8bit\r\n" +
                "\r\n" +
                "<!DOCTYPE html><html><head><title>Summary</title></head><body><h1>Quarterly Summary</h1><img src=\"cid:logo\"><p>Offline body.</p></body></html>\r\n" +
                "------=_NextPart_000_0001\r\n" +
                "Content-Type: image/png\r\n" +
                "Content-Transfer-Encoding: base64\r\n" +
                "Content-Location: https://example.com/logo.png\r\n" +
                "\r\n" +
                "$fakePngBase64\r\n" +
                "------=_NextPart_000_0001\r\n" +
                "Content-Type: text/css\r\n" +
                "\r\n" +
                "body { color: #111; }\r\n" +
                "------=_NextPart_000_0001--\r\n"
            ).toByteArray(Charsets.UTF_8)

        val result = MhtmlParser.parse(archive)
        assertTrue(result is MhtmlParseResult.Success)
        val success = result as MhtmlParseResult.Success
        assertTrue(success.html.contains("<h1>Quarterly Summary</h1>"))
        assertTrue(success.html.contains("Offline body."))
        assertEquals("Quarterly — Summary", success.subjectTitle)
        assertEquals("Both trailing image and CSS parts must be counted as ignored", 2, success.ignoredNonHtmlPartCount)
        assertFalse(success.html.contains(fakePngBase64))
    }

    @Test
    fun testMhtmlQuotedPrintableIncludingTrailingWhitespaceSoftBreaksAndUtf8() {
        val archive = (
            "MIME-Version: 1.0\r\n" +
                "Content-Type: multipart/related; boundary=\"QP_BOUNDARY\"\r\n\r\n" +
                "--QP_BOUNDARY\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n" +
                "Content-Transfer-Encoding: quoted-printable\r\n\r\n" +
                "<html><body><p>Price =3D 100% =E2=80=94 soft=   \r\n" +
                "break caf=C3=A9</p></body></html>\r\n" +
                "--QP_BOUNDARY--\r\n"
            ).toByteArray(Charsets.ISO_8859_1)

        val result = MhtmlParser.parse(archive)
        assertTrue(result is MhtmlParseResult.Success)
        val html = (result as MhtmlParseResult.Success).html
        assertTrue("Expected decoded '=' and RFC 2045 soft break with trailing spaces in: $html", html.contains("Price = 100% — softbreak café"))
    }

    @Test
    fun testMhtmlBase64AndCharsetHandling() {
        val latin1Html = "<html><body><p>Resume: r\u00e9sum\u00e9 & na\u00efve</p></body></html>"
        val latin1Bytes = latin1Html.toByteArray(Charsets.ISO_8859_1)
        val encoded = Base64.getEncoder().encodeToString(latin1Bytes).chunked(24).joinToString("\r\n")

        val archive = (
            "MIME-Version: 1.0\r\n" +
                "Content-Type: multipart/related; boundary=B64PART\r\n\r\n" +
                "--B64PART\r\n" +
                "Content-Type: text/html; charset=ISO-8859-1\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\n" +
                "$encoded\r\n" +
                "--B64PART--\r\n"
            ).toByteArray(Charsets.ISO_8859_1)

        val result = MhtmlParser.parse(archive)
        assertTrue(result is MhtmlParseResult.Success)
        val success = result as MhtmlParseResult.Success
        assertEquals("ISO-8859-1", success.charsetName)
        assertTrue(success.html.contains("résumé & naïve"))
    }

    @Test
    fun testMhtmlMissingHtmlPartMalformedBoundaryAndOversizedRejected() {
        // 1. Missing HTML part (only image + css)
        val noHtml = (
            "MIME-Version: 1.0\r\n" +
                "Content-Type: multipart/related; boundary=\"B_NO_HTML\"\r\n\r\n" +
                "--B_NO_HTML\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\n" +
                "AAAA\r\n" +
                "--B_NO_HTML\r\n" +
                "Content-Type: text/css\r\n\r\n" +
                "body { color: black; }\r\n" +
                "--B_NO_HTML--\r\n"
            ).toByteArray(Charsets.UTF_8)
        val missingRes = MhtmlParser.parse(noHtml)
        assertTrue(missingRes is MhtmlParseResult.Error)
        assertEquals(MhtmlErrorCode.MISSING_HTML_PART, (missingRes as MhtmlParseResult.Error).code)

        // 2. Malformed / missing boundary
        val badBoundary = (
            "MIME-Version: 1.0\r\n" +
                "Content-Type: multipart/related\r\n\r\n" +
                "--never-declared\r\nContent-Type: text/html\r\n\r\n<p>Hi</p>"
            ).toByteArray(Charsets.UTF_8)
        val boundaryRes = MhtmlParser.parse(badBoundary)
        assertTrue(boundaryRes is MhtmlParseResult.Error)
        assertEquals(MhtmlErrorCode.MALFORMED_BOUNDARY, (boundaryRes as MhtmlParseResult.Error).code)

        // 3. Oversized archive rejected cleanly
        val oversizedArchive = ByteArray(2048) { 'x'.code.toByte() }
        val sizeRes = MhtmlParser.parse(oversizedArchive, maxArchiveBytes = 1024)
        assertTrue(sizeRes is MhtmlParseResult.Error)
        assertEquals(MhtmlErrorCode.OVERSIZED_ARCHIVE, (sizeRes as MhtmlParseResult.Error).code)

        // 4. Unsupported transfer encoding rejected cleanly
        val badEncoding = (
            "MIME-Version: 1.0\r\n" +
                "Content-Type: multipart/related; boundary=\"B_ENC\"\r\n\r\n" +
                "--B_ENC\r\n" +
                "Content-Type: text/html\r\n" +
                "Content-Transfer-Encoding: x-alien-zip\r\n\r\n" +
                "<html></html>\r\n" +
                "--B_ENC--"
            ).toByteArray(Charsets.UTF_8)
        val encRes = MhtmlParser.parse(badEncoding)
        assertTrue(encRes is MhtmlParseResult.Error)
        assertEquals(MhtmlErrorCode.UNSUPPORTED_ENCODING, (encRes as MhtmlParseResult.Error).code)
    }

    @Test
    fun testMultiDotAndUppercaseExtensionsAndSpacedBoundaryMhtmlSniffing() {
        val htmlBytes = "<!DOCTYPE html><html><body><p>Multi-dot doc</p></body></html>".toByteArray(Charsets.UTF_8)
        for (name in listOf("report.v2.final.html", "ARCHIVE.PAGE.2026.HTML", "notes.2026.11.HTM")) {
            val res = HtmlMhtFormatDetector.detect(
                uriScheme = "content",
                intentMimeType = "application/octet-stream",
                resolvedMimeType = null,
                displayName = name,
                headerBytes = htmlBytes
            )
            assertTrue("Expected HTML support for $name", res is FormatDetectionResult.Supported)
            assertEquals(DetectedDocumentFormat.HTML, (res as FormatDetectionResult.Supported).format)
        }

        // Leading blank line + spaced "boundary = ..." in MIME header
        val spacedMhtBytes = (
            "\r\nFrom: <Saved by Blink>\r\n" +
                "MIME-Version: 1.0\r\n" +
                "Content-Type: multipart/related; boundary = \"----=_Spaced_1\"\r\n\r\n" +
                "------=_Spaced_1\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n\r\n" +
                "<html><body>Spaced boundary</body></html>\r\n" +
                "------=_Spaced_1--"
            ).toByteArray(Charsets.UTF_8)

        for (name in listOf("saved.page.2026.mht", "SNAPSHOT.V3.MHTML")) {
            val res = HtmlMhtFormatDetector.detect(
                uriScheme = "content",
                intentMimeType = "application/octet-stream",
                resolvedMimeType = null,
                displayName = name,
                headerBytes = spacedMhtBytes
            )
            assertTrue("Expected MHT support for $name", res is FormatDetectionResult.Supported)
            assertEquals(DetectedDocumentFormat.MHT, (res as FormatDetectionResult.Supported).format)
        }
    }

    @Test
    fun testMhtmlUtf16LeBomPartWithoutDeclaredCharsetDecodesCleanly() {
        val utf16Html = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "<html><body><h1>UTF16 MHT</h1></body></html>".toByteArray(Charsets.UTF_16LE)
        val encoded = Base64.getEncoder().encodeToString(utf16Html)

        val archive = (
            "MIME-Version: 1.0\r\n" +
                "Content-Type: multipart/related; boundary=\"BOM_PART\"\r\n\r\n" +
                "--BOM_PART\r\n" +
                "Content-Type: text/html\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\n" +
                "$encoded\r\n" +
                "--BOM_PART--\r\n"
            ).toByteArray(Charsets.ISO_8859_1)

        val result = MhtmlParser.parse(archive)
        assertTrue("UTF-16LE BOM MHT part should parse cleanly", result is MhtmlParseResult.Success)
        val success = result as MhtmlParseResult.Success
        assertTrue(success.html.contains("<h1>UTF16 MHT</h1>"))
        assertEquals("UTF-16LE", success.charsetName)
    }

    @Test
    fun testMhtmlPrefersRfc2387StartContentIdOverEarlierHtmlSubpart() {
        val archive = (
            "MIME-Version: 1.0\r\n" +
                "Content-Type: multipart/related; type=\"text/html\"; start=\"<main-article@local>\"; boundary=\"START_TEST\"\r\n\r\n" +
                "--START_TEST\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-ID: <ad-iframe@local>\r\n\r\n" +
                "<html><body><p>Secondary iframe fragment</p></body></html>\r\n" +
                "--START_TEST\r\n" +
                "Content-Type: image/png\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\n" +
                "AAAA\r\n" +
                "--START_TEST\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-ID: <main-article@local>\r\n\r\n" +
                "<html><body><h1>Primary Start Article</h1></body></html>\r\n" +
                "--START_TEST\r\n" +
                "Content-Type: text/css\r\n\r\n" +
                "body { color: #000; }\r\n" +
                "--START_TEST--\r\n"
            ).toByteArray(Charsets.UTF_8)

        val result = MhtmlParser.parse(archive)
        assertTrue(result is MhtmlParseResult.Success)
        val success = result as MhtmlParseResult.Success
        assertTrue("Must select RFC 2387 start HTML part", success.html.contains("<h1>Primary Start Article</h1>"))
        assertFalse("Must not select earlier iframe HTML part", success.html.contains("Secondary iframe fragment"))
        assertEquals("Must count ignored non-HTML sibling parts (image + css)", 2, success.ignoredNonHtmlPartCount)
    }

    @Test
    fun testMalformedAndLongHtmlDocumentsPrepareSafely() {
        val malformed = "<p>Unclosed paragraph <div><span>Broken nesting <img src=\"x.png\" alt=\"a > b\"><script>bad()"
        val preparedMalformed = HtmlDocumentSanitizer.prepareForOfflineViewer(malformed)
        assertTrue(preparedMalformed.startsWith("<!DOCTYPE html><html><head>"))
        assertTrue(preparedMalformed.contains("Unclosed paragraph"))
        assertFalse(preparedMalformed.contains("<img", ignoreCase = true))
        assertFalse(preparedMalformed.contains("<script", ignoreCase = true))

        val longHtml = buildString {
            append("<!DOCTYPE html><html><head><title>Long Doc</title></head><body>")
            repeat(1500) { idx ->
                append("<section><h2>Section $idx</h2><p>Paragraph content for scrolling check #$idx.</p></section>")
            }
            append("</body></html>")
        }
        val preparedLong = HtmlDocumentSanitizer.prepareForOfflineViewer(longHtml)
        assertTrue(preparedLong.contains("Section 0"))
        assertTrue(preparedLong.contains("Section 1499"))
        assertTrue(preparedLong.contains("Content-Security-Policy"))
    }

    // -------------------------------------------------------------------------
    // 3. Manifest Registration, Multi-Dot Matching & Add Elements Exclusion Tests
    // -------------------------------------------------------------------------

    @Test
    fun testManifestRegistersSpecificHtmlMhtIntentFiltersWithoutWildcardAndInMainProcess() {
        val manifestFile = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        ).first { it.exists() }
        val xml = manifestFile.readText()

        val activityStart = xml.indexOf("android:name=\".feature.htmlviewer.HtmlMhtViewerActivity\"")
        assertTrue("HtmlMhtViewerActivity must be registered in AndroidManifest.xml", activityStart > 0)
        val activityBlock = xml.substring(activityStart, xml.indexOf("</activity>", activityStart))

        assertTrue(activityBlock.contains("android:exported=\"true\""))
        assertTrue(activityBlock.contains("android.intent.action.VIEW"))
        assertTrue(activityBlock.contains("android:scheme=\"content\""))
        assertTrue(activityBlock.contains("android:mimeType=\"text/html\""))
        assertTrue(activityBlock.contains("android:mimeType=\"application/xhtml+xml\""))
        assertTrue(activityBlock.contains("android:mimeType=\"multipart/related\""))
        assertTrue(activityBlock.contains("android:mimeType=\"message/rfc822\""))
        assertTrue(activityBlock.contains("android:mimeType=\"application/x-mimearchive\""))
        assertFalse(
            "Must NOT use API 31+ pathAdvancedPattern on minSdk 26 (silently ignored on Android 8-11)",
            activityBlock.contains("pathAdvancedPattern")
        )
        assertTrue(
            "Extension filter must use API-26-compatible pathPattern for .html",
            activityBlock.contains("android:pathPattern=\".*\\\\.html\"")
        )
        assertTrue(
            "Extension filter must include multi-dot pathPattern tiers for non-backtracking glob matching",
            activityBlock.contains("android:pathPattern=\".*\\\\..*\\\\.html\"") &&
                activityBlock.contains("android:pathPattern=\".*\\\\..*\\\\..*\\\\.mhtml\"")
        )
        assertTrue(
            "Extension filter must cover uppercase extensions such as .MHTML",
            activityBlock.contains("android:pathPattern=\".*\\\\.MHTML\"")
        )
        val filterBlocks = Regex("<intent-filter>[\\s\\S]*?</intent-filter>")
            .findAll(activityBlock)
            .map { it.value }
            .toList()
        val octetStreamFilters = filterBlocks.filter { it.contains("android:mimeType=\"application/octet-stream\"") }
        assertEquals(
            "Exactly one intent-filter should handle application/octet-stream strictly scoped by HTML/MHT pathPattern",
            1,
            octetStreamFilters.size
        )
        assertTrue(
            "application/octet-stream filter MUST be scoped with explicit HTML/MHT pathPattern entries",
            octetStreamFilters.single().contains("android:pathPattern=\".*\\\\.mht\"") &&
                octetStreamFilters.single().contains("android:pathPattern=\".*\\\\.html\"")
        )
        assertFalse("Viewer must NOT run in :heavy process", activityBlock.contains("android:process=\":heavy\""))
        assertFalse("Viewer must NOT register unrestricted */* MIME filter", activityBlock.contains("*/*"))
        assertFalse("Viewer must NOT hijack http/https URLs", activityBlock.contains("android:scheme=\"http"))
    }

    @Test
    fun testHtmlMhtViewerIsExcludedFromAddElementsAndFloatingPickers() {
        assertFalse(
            "html_mht_viewer must NOT appear in ALL_FLOATING_WINDOWS",
            ALL_FLOATING_WINDOWS.any { it.action.contains("html_mht", ignoreCase = true) }
        )

        val addElementFile = listOf(
            File("src/main/java/com/example/feature/settings/AddElementActivity.kt"),
            File("app/src/main/java/com/example/feature/settings/AddElementActivity.kt")
        ).first { it.exists() }
        val addElementSource = addElementFile.readText()
        assertFalse(
            "AddElementActivity must NOT expose html_mht_viewer",
            addElementSource.contains(HtmlMhtViewerMiniAppInstance.APP_TYPE)
        )
    }
}
