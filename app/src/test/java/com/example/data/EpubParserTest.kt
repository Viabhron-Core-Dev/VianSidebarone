package com.example.data

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: Context
    private lateinit var tempDir: File

    private class MockEpubContext(private val baseDir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = baseDir
        override fun getPackageName(): String = "com.example"
    }

    @Before
    fun setUp() {
        tempDir = tempFolder.newFolder("epub_test")
        testContext = MockEpubContext(tempDir)
    }

    /**
     * Case 1: "chapter_1", "chapter_2", "chapter_10" natural numeric sorting.
     */
    @Test
    fun testNaturalNumericOrderingVsAlphabetical() {
        val list = listOf("chapter_10.xhtml", "chapter_1.xhtml", "chapter_2.xhtml")
        val sorted = list.sortedWith { a, b -> EpubParser.naturalCompare(a, b) }
        assertEquals(
            "Natural comparator must place chapter_1 before chapter_2, and chapter_2 before chapter_10",
            listOf("chapter_1.xhtml", "chapter_2.xhtml", "chapter_10.xhtml"),
            sorted
        )

        // Additional edge cases: multi-digit chapters and alphanumeric chapters
        val complexList = listOf("ch20.html", "ch2.html", "ch10.html", "ch1.html")
        val complexSorted = complexList.sortedWith { a, b -> EpubParser.naturalCompare(a, b) }
        assertEquals("ch1.html", complexSorted[0])
        assertEquals("ch2.html", complexSorted[1])
        assertEquals("ch10.html", complexSorted[2])
        assertEquals("ch20.html", complexSorted[3])
    }

    /**
     * Case 2: Spine order different from ZIP entry order.
     * The OPF spine must be treated as authoritative, regardless of the order in which entries were added to the ZIP.
     */
    @Test
    fun testSpineOrderAuthoritativeOverZipEntryOrder() = runBlocking {
        val epubFile = File(tempDir, "spine_test.epub")
        createZip(epubFile) { zos ->
            // Container
            writeEntry(zos, "META-INF/container.xml", containerXml("OEBPS/content.opf"))

            // ZIP entry order: Ch 3 first, then Ch 1, then Ch 2
            writeEntry(zos, "OEBPS/text/ch3.xhtml", htmlDoc("Chapter Three", "Content of Chapter Three"))
            writeEntry(zos, "OEBPS/text/ch1.xhtml", htmlDoc("Chapter One", "Content of Chapter One"))
            writeEntry(zos, "OEBPS/text/ch2.xhtml", htmlDoc("Chapter Two", "Content of Chapter Two"))

            // OPF with authoritative spine: ch1 -> ch2 -> ch3
            val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>Spine Authority Test</dc:title>
  </metadata>
  <manifest>
    <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
    <item id="c3" href="text/ch3.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="c1"/>
    <itemref idref="c2"/>
    <itemref idref="c3"/>
  </spine>
</package>"""
            writeEntry(zos, "OEBPS/content.opf", opf)
        }

        val result = EpubParser.parseEpub(testContext, 101, epubFile)
        assertEquals("Must parse exactly 3 chapters", 3, result.chapterCount)

        val ch0 = EpubParser.getChapterText(testContext, 101, 0)
        val ch1 = EpubParser.getChapterText(testContext, 101, 1)
        val ch2 = EpubParser.getChapterText(testContext, 101, 2)

        assertTrue("First chapter must be Chapter One from spine", ch0.contains("Content of Chapter One"))
        assertTrue("Second chapter must be Chapter Two from spine", ch1.contains("Content of Chapter Two"))
        assertTrue("Third chapter must be Chapter Three from spine", ch2.contains("Content of Chapter Three"))
    }

    /**
     * Case 3: Nested OPF directories and relative hrefs with '../' and '.'
     */
    @Test
    fun testNestedOpfDirectoriesAndRelativeHrefs() = runBlocking {
        assertEquals(
            "OPS/sub/chapter1.xhtml",
            EpubParser.normalizeZipPath("OPS/content/", "../sub/chapter1.xhtml")
        )
        assertEquals(
            "OEBPS/text/chapter1.xhtml",
            EpubParser.normalizeZipPath("OEBPS/xml/", "../text/chapter1.xhtml")
        )

        val epubFile = File(tempDir, "nested_opf.epub")
        createZip(epubFile) { zos ->
            writeEntry(zos, "META-INF/container.xml", containerXml("OPS/package/content.opf"))
            writeEntry(zos, "OPS/text/chapter1.xhtml", htmlDoc("Ch 1", "Nested relative content"))

            val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <manifest>
    <item id="c1" href="../text/chapter1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="c1"/>
  </spine>
</package>"""
            writeEntry(zos, "OPS/package/content.opf", opf)
        }

        val result = EpubParser.parseEpub(testContext, 102, epubFile)
        assertEquals(1, result.chapterCount)
        val text = EpubParser.getChapterText(testContext, 102, 0)
        assertTrue(text.contains("Nested relative content"))
    }

    /**
     * Case 4: URL-encoded hrefs in OPF manifest (e.g. Chapter%201.xhtml).
     */
    @Test
    fun testUrlEncodedHrefs() = runBlocking {
        val epubFile = File(tempDir, "url_encoded.epub")
        createZip(epubFile) { zos ->
            writeEntry(zos, "META-INF/container.xml", containerXml("OEBPS/content.opf"))
            // Actual file in zip has decoded space
            writeEntry(zos, "OEBPS/Chapter 1 Intro.xhtml", htmlDoc("Title", "Decoded space content"))

            val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <manifest>
    <item id="c1" href="Chapter%201%20Intro.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="c1"/>
  </spine>
</package>"""
            writeEntry(zos, "OEBPS/content.opf", opf)
        }

        val result = EpubParser.parseEpub(testContext, 103, epubFile)
        assertEquals(1, result.chapterCount)
        val text = EpubParser.getChapterText(testContext, 103, 0)
        assertTrue(text.contains("Decoded space content"))
    }

    /**
     * Case 5: Similar filenames in different directories must not match ambiguously.
     */
    @Test
    fun testSimilarFilenamesInDifferentDirectoriesNotConfused() = runBlocking {
        val epubFile = File(tempDir, "similar_names.epub")
        createZip(epubFile) { zos ->
            writeEntry(zos, "META-INF/container.xml", containerXml("content.opf"))
            writeEntry(zos, "volume1/part1.xhtml", htmlDoc("V1", "Volume 1 Part 1"))
            writeEntry(zos, "volume2/part1.xhtml", htmlDoc("V2", "Volume 2 Part 1"))

            // OPF only includes volume2/part1.xhtml
            val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <manifest>
    <item id="v2" href="volume2/part1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="v2"/>
  </spine>
</package>"""
            writeEntry(zos, "content.opf", opf)
        }

        val result = EpubParser.parseEpub(testContext, 104, epubFile)
        assertEquals(1, result.chapterCount)
        val text = EpubParser.getChapterText(testContext, 104, 0)
        assertTrue("Must strictly resolve volume 2, not volume 1", text.contains("Volume 2 Part 1"))
    }

    /**
     * Case 6: Navigation documents (properties="nav", toc.ncx, linear="no") excluded from reading chapters.
     */
    @Test
    fun testNavigationAndNonReadingDocumentsExcluded() = runBlocking {
        val epubFile = File(tempDir, "nav_exclusion.epub")
        createZip(epubFile) { zos ->
            writeEntry(zos, "META-INF/container.xml", containerXml("OEBPS/content.opf"))
            writeEntry(zos, "OEBPS/toc.ncx", "<ncx><navMap/></ncx>")
            writeEntry(zos, "OEBPS/nav.xhtml", htmlDoc("TOC", "Navigation Document Body"))
            writeEntry(zos, "OEBPS/cover.xhtml", htmlDoc("Cover", "Cover Page"))
            writeEntry(zos, "OEBPS/chapter1.xhtml", htmlDoc("Chapter 1", "Actual Story Chapter 1"))
            writeEntry(zos, "OEBPS/chapter2.xhtml", htmlDoc("Chapter 2", "Actual Story Chapter 2"))

            val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="cov" href="cover.xhtml" media-type="application/xhtml+xml"/>
    <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="cov" linear="no"/>
    <itemref idref="nav"/>
    <itemref idref="c1"/>
    <itemref idref="c2"/>
  </spine>
</package>"""
            writeEntry(zos, "OEBPS/content.opf", opf)
        }

        val result = EpubParser.parseEpub(testContext, 105, epubFile)
        assertEquals("Only chapter 1 and 2 should be included in reading flow", 2, result.chapterCount)

        val text1 = EpubParser.getChapterText(testContext, 105, 0)
        val text2 = EpubParser.getChapterText(testContext, 105, 1)

        assertTrue("First chapter should be Chapter 1", text1.contains("Actual Story Chapter 1"))
        assertTrue("Second chapter should be Chapter 2", text2.contains("Actual Story Chapter 2"))
    }

    /**
     * Case 7: Missing or malformed spine falls back to deterministic natural numeric order.
     */
    @Test
    fun testMissingOrMalformedSpineFallsBackToNaturalOrder() = runBlocking {
        val epubFile = File(tempDir, "broken_spine.epub")
        createZip(epubFile) { zos ->
            // Container pointing to OPF with empty spine
            writeEntry(zos, "META-INF/container.xml", containerXml("content.opf"))
            writeEntry(zos, "chapter_10.xhtml", htmlDoc("Ch 10", "Chapter 10 content"))
            writeEntry(zos, "chapter_1.xhtml", htmlDoc("Ch 1", "Chapter 1 content"))
            writeEntry(zos, "chapter_2.xhtml", htmlDoc("Ch 2", "Chapter 2 content"))

            // Corrupt/empty spine in OPF
            val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <manifest/>
  <spine/>
</package>"""
            writeEntry(zos, "content.opf", opf)
        }

        val result = EpubParser.parseEpub(testContext, 106, epubFile)
        assertEquals("Must fall back and find all 3 html files", 3, result.chapterCount)

        val ch0 = EpubParser.getChapterText(testContext, 106, 0)
        val ch1 = EpubParser.getChapterText(testContext, 106, 1)
        val ch2 = EpubParser.getChapterText(testContext, 106, 2)

        assertTrue("Fallback must order chapter 1 first", ch0.contains("Chapter 1 content"))
        assertTrue("Fallback must order chapter 2 second", ch1.contains("Chapter 2 content"))
        assertTrue("Fallback must order chapter 10 third", ch2.contains("Chapter 10 content"))
    }

    /**
     * Case 8: Repeated parsing produces an identical, stable chapter sequence.
     */
    @Test
    fun testRepeatedParsingProducesIdenticalStableChapterSequence() = runBlocking {
        val epubFile = File(tempDir, "idempotent.epub")
        createZip(epubFile) { zos ->
            writeEntry(zos, "META-INF/container.xml", containerXml("content.opf"))
            writeEntry(zos, "ch1.xhtml", htmlDoc("Chapter 1", "Repeatable text 1"))
            writeEntry(zos, "ch2.xhtml", htmlDoc("Chapter 2", "Repeatable text 2"))

            val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>Idempotent Test Book</dc:title>
  </metadata>
  <manifest>
    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="c1"/>
    <itemref idref="c2"/>
  </spine>
</package>"""
            writeEntry(zos, "content.opf", opf)
        }

        val run1 = EpubParser.parseEpub(testContext, 107, epubFile)
        val toc1 = EpubParser.getChapterList(testContext, 107)

        val run2 = EpubParser.parseEpub(testContext, 107, epubFile)
        val toc2 = EpubParser.getChapterList(testContext, 107)

        assertEquals(run1.chapterCount, run2.chapterCount)
        assertEquals(toc1.size, toc2.size)
        for (i in toc1.indices) {
            assertEquals(toc1[i].title, toc2[i].title)
            assertEquals(toc1[i].index, toc2[i].index)
        }
    }

    /**
     * Case 9: Plain TXT parsing splits on natural chapter headings.
     */
    @Test
    fun testTxtParsingWithChapterHeadings() = runBlocking {
        val txtFile = File(tempDir, "sample.txt")
        val txtContent = """
Chapter 1: The Beginning
It was a bright cold day in April, and the clocks were striking thirteen.

Chapter 2: The Journey
Winston Smith, his chin nuzzled into his breast in an effort to escape the vile wind, slipped quickly through the glass doors.
        """.trimIndent()
        txtFile.writeText(txtContent)

        val count = EpubParser.parseTxtToText(testContext, 108, txtFile)
        assertEquals(2, count)

        val toc = EpubParser.getChapterList(testContext, 108)
        assertEquals(2, toc.size)
        assertEquals("Chapter 1: The Beginning", toc[0].title)
        assertEquals("Chapter 2: The Journey", toc[1].title)

        val ch1 = EpubParser.getChapterText(testContext, 108, 0)
        assertTrue(ch1.contains("clocks were striking thirteen"))
    }

    // --- Helper methods to generate test ZIP archives ---

    private fun createZip(target: File, writer: (ZipOutputStream) -> Unit) {
        ZipOutputStream(FileOutputStream(target)).use { zos ->
            writer(zos)
        }
    }

    private fun writeEntry(zos: ZipOutputStream, path: String, content: String) {
        zos.putNextEntry(ZipEntry(path))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun containerXml(opfPath: String): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""
    }

    private fun htmlDoc(title: String, body: String): String {
        return """<!DOCTYPE html>
<html>
<head><title>$title</title></head>
<body>
<h1>$title</h1>
<p>$body</p>
</body>
</html>"""
    }
}
