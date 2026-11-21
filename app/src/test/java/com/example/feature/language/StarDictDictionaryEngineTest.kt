package com.example.feature.language

import android.content.Context
import android.content.ContextWrapper
import com.example.feature.language.dictionary.DictionaryImportResult
import com.example.feature.language.dictionary.DictionaryLookupOutcome
import com.example.feature.language.dictionary.DictionaryService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StarDictDictionaryEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class TestContext(private val baseDir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example"
        override fun getFilesDir(): File = baseDir
    }

    private data class TestDictSpec(
        val folderPrefix: String = "",
        val baseName: String = "sample",
        val bookName: String,
        val entries: List<Pair<String, ByteArray>>,
        val synonyms: List<Pair<String, Int>> = emptyList(),
        val sameTypeSequence: String = "m",
        val idxOffsetBits: Int = 32,
        val compressIdxGz: Boolean = false,
        val compressDictDz: Boolean = false,
        val author: String = "Test Author",
        val description: String = "Offline StarDict test dictionary"
    )

    private lateinit var rootDir: File
    private lateinit var context: TestContext
    private lateinit var service: DictionaryService

    @Before
    fun setUp() {
        DictionaryService.resetForTesting()
        rootDir = tempFolder.newFolder("stardict_root")
        context = TestContext(tempFolder.newFolder("app_files"))
        service = DictionaryService.createForTesting(context, rootDir)
    }

    @After
    fun tearDown() {
        DictionaryService.resetForTesting()
    }

    private fun buildStarDictZip(zipFile: File, specs: List<TestDictSpec>): File {
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            for (spec in specs) {
                val dictOut = ByteArrayOutputStream()
                val idxOut = ByteArrayOutputStream()
                val idxData = DataOutputStream(idxOut)

                for ((word, rawEntryBytes) in spec.entries) {
                    val offset = dictOut.size().toLong()
                    dictOut.write(rawEntryBytes)
                    val size = rawEntryBytes.size.toLong()

                    idxData.write(word.toByteArray(StandardCharsets.UTF_8))
                    idxData.writeByte(0)
                    if (spec.idxOffsetBits == 64) {
                        idxData.writeLong(offset)
                    } else {
                        idxData.writeInt(offset.toInt())
                    }
                    idxData.writeInt(size.toInt())
                }
                idxData.flush()
                val rawIdxBytes = idxOut.toByteArray()
                val rawDictBytes = dictOut.toByteArray()

                val prefix = if (spec.folderPrefix.isEmpty()) "" else "${spec.folderPrefix.trimEnd('/')}/"
                val version = if (spec.idxOffsetBits == 64) "3.0.0" else "2.4.2"

                val ifoContent = buildString {
                    append("StarDict's dict ifo file\n")
                    append("version=$version\n")
                    append("wordcount=${spec.entries.size}\n")
                    if (spec.synonyms.isNotEmpty()) {
                        append("synwordcount=${spec.synonyms.size}\n")
                    }
                    append("idxfilesize=${rawIdxBytes.size}\n")
                    if (spec.idxOffsetBits == 64) {
                        append("idxoffsetbits=64\n")
                    }
                    append("bookname=${spec.bookName}\n")
                    if (spec.sameTypeSequence.isNotEmpty()) {
                        append("sametypesequence=${spec.sameTypeSequence}\n")
                    }
                    if (spec.author.isNotEmpty()) append("author=${spec.author}\n")
                    if (spec.description.isNotEmpty()) append("description=${spec.description}\n")
                }.toByteArray(StandardCharsets.UTF_8)

                zos.putNextEntry(ZipEntry("$prefix${spec.baseName}.ifo"))
                zos.write(ifoContent)
                zos.closeEntry()

                if (spec.compressIdxGz) {
                    zos.putNextEntry(ZipEntry("$prefix${spec.baseName}.idx.gz"))
                    zos.write(gzipBytes(rawIdxBytes))
                    zos.closeEntry()
                } else {
                    zos.putNextEntry(ZipEntry("$prefix${spec.baseName}.idx"))
                    zos.write(rawIdxBytes)
                    zos.closeEntry()
                }

                if (spec.compressDictDz) {
                    zos.putNextEntry(ZipEntry("$prefix${spec.baseName}.dict.dz"))
                    zos.write(gzipBytes(rawDictBytes))
                    zos.closeEntry()
                } else {
                    zos.putNextEntry(ZipEntry("$prefix${spec.baseName}.dict"))
                    zos.write(rawDictBytes)
                    zos.closeEntry()
                }

                if (spec.synonyms.isNotEmpty()) {
                    val synOut = ByteArrayOutputStream()
                    val synData = DataOutputStream(synOut)
                    for ((synWord, wordIdx) in spec.synonyms) {
                        synData.write(synWord.toByteArray(StandardCharsets.UTF_8))
                        synData.writeByte(0)
                        synData.writeInt(wordIdx)
                    }
                    synData.flush()
                    zos.putNextEntry(ZipEntry("$prefix${spec.baseName}.syn"))
                    zos.write(synOut.toByteArray())
                    zos.closeEntry()
                }
            }
        }
        return zipFile
    }

    private fun gzipBytes(data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(data) }
        return baos.toByteArray()
    }

    private fun utf8(s: String): ByteArray = s.toByteArray(StandardCharsets.UTF_8)

    @Test
    fun testValidZipImportExactCaseInsensitiveAndNoResultLookup() {
        val zip = buildStarDictZip(
            tempFolder.newFile("english_basic.zip"),
            listOf(
                TestDictSpec(
                    bookName = "Concise English",
                    entries = listOf(
                        "Ephemeral" to utf8("Lasting for a very short time."),
                        "serendipity" to utf8("The occurrence of events by chance in a happy way.")
                    )
                )
            )
        )

        val result = service.importFromFile(zip)
        assertTrue("Valid StarDict ZIP should import cleanly", result is DictionaryImportResult.Success)
        val installed = (result as DictionaryImportResult.Success).installed
        assertEquals(1, installed.size)
        assertEquals("Concise English", installed[0].name)
        assertEquals(2L, installed[0].wordCount)
        assertTrue(installed[0].storageBytes > 0L)

        // Exact lookup
        val exact = service.lookup("serendipity")
        assertTrue(exact is DictionaryLookupOutcome.Found)
        val exactEntry = (exact as DictionaryLookupOutcome.Found).entries.single()
        assertEquals("Concise English", exactEntry.dictionaryName)
        assertEquals("serendipity", exactEntry.headword)
        assertTrue(exactEntry.definitionText.contains("happy way"))

        // Case-insensitive lookup
        val ci = service.lookup("ePhEmErAl")
        assertTrue(ci is DictionaryLookupOutcome.Found)
        val ciEntry = (ci as DictionaryLookupOutcome.Found).entries.single()
        assertEquals("Ephemeral", ciEntry.headword)
        assertTrue(ciEntry.definitionText.contains("very short time"))

        // Missing word returns honest NotFound
        val missing = service.lookup("nonexistentwordxyz")
        assertTrue(missing is DictionaryLookupOutcome.NotFound)
        assertEquals(1, (missing as DictionaryLookupOutcome.NotFound).searchedDictionaries)
    }

    @Test
    fun testMultiDictionaryZipImportAttributionPriorityAndEnableDisable() {
        val zip = buildStarDictZip(
            tempFolder.newFile("multi_pack.zip"),
            listOf(
                TestDictSpec(
                    folderPrefix = "dict_a",
                    baseName = "alpha",
                    bookName = "Alpha Dictionary",
                    entries = listOf("apple" to utf8("Alpha definition: crisp orchard fruit."))
                ),
                TestDictSpec(
                    folderPrefix = "dict_b",
                    baseName = "beta",
                    bookName = "Beta Lexicon",
                    entries = listOf("apple" to utf8("Beta definition: Malus domestica tree fruit."))
                )
            )
        )

        val res = service.importFromFile(zip)
        assertTrue(res is DictionaryImportResult.Success)
        val dicts = service.getInstalledDictionaries()
        assertEquals(2, dicts.size)
        val firstId = dicts[0].id
        val secondId = dicts[1].id

        // Lookup returns both attributed entries in priority order
        val found1 = service.lookup("apple") as DictionaryLookupOutcome.Found
        assertEquals(2, found1.entries.size)
        assertEquals(dicts[0].name, found1.entries[0].dictionaryName)
        assertEquals(dicts[1].name, found1.entries[1].dictionaryName)

        // Move second dictionary up to priority #1
        assertTrue(service.moveDictionaryPriority(secondId, moveUp = true))
        val found2 = service.lookup("apple") as DictionaryLookupOutcome.Found
        assertEquals(dicts[1].name, found2.entries[0].dictionaryName)
        assertEquals(dicts[0].name, found2.entries[1].dictionaryName)

        // Disable top-priority dictionary -> only the remaining enabled dictionary responds
        assertTrue(service.setDictionaryEnabled(secondId, false))
        val found3 = service.lookup("apple") as DictionaryLookupOutcome.Found
        assertEquals(1, found3.entries.size)
        assertEquals(dicts[0].name, found3.entries[0].dictionaryName)

        // Disable all dictionaries -> honest AllDictionariesDisabled
        assertTrue(service.setDictionaryEnabled(firstId, false))
        val disabled = service.lookup("apple")
        assertTrue(disabled is DictionaryLookupOutcome.AllDictionariesDisabled)
    }

    @Test
    fun testCompressedIdxGzAndDictDzAnd64BitOffsets() {
        val zip = buildStarDictZip(
            tempFolder.newFile("compressed_64bit.zip"),
            listOf(
                TestDictSpec(
                    baseName = "wide64",
                    bookName = "64-bit Compressed Dict",
                    idxOffsetBits = 64,
                    compressIdxGz = true,
                    compressDictDz = true,
                    entries = listOf(
                        "aurora" to utf8("Natural light display in polar skies."),
                        "zenith" to utf8("The point in the sky directly above an observer.")
                    )
                )
            )
        )

        val res = service.importFromFile(zip)
        assertTrue("64-bit .idx.gz + .dict.dz ZIP must import cleanly", res is DictionaryImportResult.Success)
        val installed = (res as DictionaryImportResult.Success).installed.single()
        assertEquals(64, installed.idxOffsetBits)

        val hit = service.lookup("zenith") as DictionaryLookupOutcome.Found
        assertEquals("zenith", hit.entries.single().headword)
        assertTrue(hit.entries.single().definitionText.contains("directly above"))
    }

    @Test
    fun testSupportedTextualTypesSelfTaggedAndBinaryResourceGracefulFallback() {
        // 1. Multi-field sametypesequence = "t" + "h" (phonetic + HTML)
        val thBytes = ByteArrayOutputStream().apply {
            write(utf8("kæt"))
            write(0)
            write(utf8("<b>noun</b><br/>A small domesticated &amp; carnivorous mammal."))
        }.toByteArray()

        // 2. Self-tagged entries (empty sametypesequence): 'x' (XDICT/XML), 'g' (Pango), 'w' (Wiki), and binary 'P'
        val selfTaggedXmlPangoWiki = ByteArrayOutputStream().apply {
            write('t'.code)
            write(utf8("lʌk"))
            write(0)
            write('x'.code)
            write(utf8("<k>luck</k> <dtrn>Success brought by chance</dtrn>"))
            write(0)
            write('g'.code)
            write(utf8("<i>good fortune</i>"))
            write(0)
            write('w'.code)
            write(utf8("'''Also''' see [[serendipity|happy chance]]"))
            write(0)
        }.toByteArray()

        val binaryOnlyEntry = ByteArrayOutputStream().apply {
            write('P'.code) // Uppercase binary picture payload: 4-byte size + bytes
            val payload = byteArrayOf(1, 2, 3, 4)
            val dos = DataOutputStream(this)
            dos.writeInt(payload.size)
            dos.write(payload)
            dos.flush()
        }.toByteArray()

        val zip = buildStarDictZip(
            tempFolder.newFile("types_test.zip"),
            listOf(
                TestDictSpec(
                    folderPrefix = "th",
                    baseName = "thdict",
                    bookName = "Phonetic HTML Dict",
                    sameTypeSequence = "th",
                    entries = listOf("cat" to thBytes)
                ),
                TestDictSpec(
                    folderPrefix = "selftag",
                    baseName = "stdict",
                    bookName = "Self-Tagged Dict",
                    sameTypeSequence = "",
                    entries = listOf(
                        "luck" to selfTaggedXmlPangoWiki,
                        "diagram" to binaryOnlyEntry
                    )
                )
            )
        )

        val res = service.importFromFile(zip)
        assertTrue(res is DictionaryImportResult.Success)

        val catHit = (service.lookup("cat") as DictionaryLookupOutcome.Found).entries.single()
        assertEquals("/kæt/", catHit.phonetic)
        assertTrue(catHit.definitionText.contains("noun"))
        assertTrue(catHit.definitionText.contains("domesticated & carnivorous mammal"))
        assertFalse(catHit.definitionText.contains("<b>"))

        val luckHit = (service.lookup("luck") as DictionaryLookupOutcome.Found).entries.single()
        assertEquals("/lʌk/", luckHit.phonetic)
        assertTrue(luckHit.definitionText.contains("Success brought by chance"))
        assertTrue(luckHit.definitionText.contains("good fortune"))
        assertTrue(luckHit.definitionText.contains("happy chance"))

        val binHit = (service.lookup("diagram") as DictionaryLookupOutcome.Found).entries.single()
        assertTrue(binHit.hasUnsupportedBinaryOnly)
        assertTrue(binHit.definitionText.contains("unsupported binary resource", ignoreCase = true))
    }

    @Test
    fun testUtf8MultibyteHeadwordsAndSynSynonymLookup() {
        val zip = buildStarDictZip(
            tempFolder.newFile("utf8_syn.zip"),
            listOf(
                TestDictSpec(
                    baseName = "utf8syn",
                    bookName = "Multilingual & Synonyms",
                    entries = listOf(
                        "café" to utf8("A small restaurant selling light meals and drinks."),
                        "नमस्ते" to utf8("A respectful Hindi greeting."),
                        "run" to utf8("Move at a speed faster than a walk.")
                    ),
                    synonyms = listOf(
                        "ran" to 2,
                        "running" to 2,
                        "coffeehouse" to 0
                    )
                )
            )
        )

        val res = service.importFromFile(zip)
        assertTrue(res is DictionaryImportResult.Success)
        val installed = (res as DictionaryImportResult.Success).installed.single()
        assertEquals(3L, installed.synonymCount)

        // UTF-8 lookups
        val cafeHit = (service.lookup("CAFÉ") as DictionaryLookupOutcome.Found).entries.single()
        assertEquals("café", cafeHit.headword)

        val hindiHit = (service.lookup("नमस्ते") as DictionaryLookupOutcome.Found).entries.single()
        assertTrue(hindiHit.definitionText.contains("Hindi greeting"))

        // Synonym lookup resolves to target headword and exposes matchedSynonym
        val synHit = (service.lookup("running") as DictionaryLookupOutcome.Found).entries.single()
        assertEquals("run", synHit.headword)
        assertEquals("running", synHit.matchedSynonym)
        assertTrue(synHit.definitionText.contains("faster than a walk"))
    }

    @Test
    fun testInvalidZipDuplicateRejectionPathTraversalDeletePersistenceAndOrphanCleanup() {
        // 1. Invalid / incomplete ZIP (only .ifo, missing .idx and .dict)
        val invalidZip = tempFolder.newFile("incomplete.zip")
        ZipOutputStream(FileOutputStream(invalidZip)).use { zos ->
            zos.putNextEntry(ZipEntry("broken.ifo"))
            zos.write(utf8("StarDict's dict ifo file\nversion=2.4.2\nwordcount=1\nidxfilesize=10\nbookname=Broken\n"))
            zos.closeEntry()
        }
        val badRes = service.importFromFile(invalidZip)
        assertTrue("Incomplete ZIP must fail", badRes is DictionaryImportResult.Failure)
        assertEquals(0, service.getInstalledDictionaries().size)

        // 2. ZIP path traversal must be rejected safely
        val traversalZip = tempFolder.newFile("evil_traversal.zip")
        ZipOutputStream(FileOutputStream(traversalZip)).use { zos ->
            zos.putNextEntry(ZipEntry("../../escaped.ifo"))
            zos.write(utf8("malicious"))
            zos.closeEntry()
        }
        val travRes = service.importFromFile(traversalZip)
        assertTrue("ZIP path traversal must be rejected", travRes is DictionaryImportResult.Failure)
        assertFalse(File(rootDir.parentFile, "escaped.ifo").exists())

        // 3. Orphaned staging directory cleanup
        val orphanDir = File(rootDir, ".staging_interrupted_test").apply {
            mkdirs()
            File(this, "partial.dict").writeText("partial")
        }
        assertTrue(orphanDir.exists())
        val cleaned = service.cleanupOrphanedStagingDirs()
        assertEquals(1, cleaned)
        assertFalse(orphanDir.exists())

        // 4. Import valid dict, verify duplicate rejection, restart persistence, and clean deletion
        val validZip = buildStarDictZip(
            tempFolder.newFile("persist.zip"),
            listOf(
                TestDictSpec(
                    bookName = "Persistent Lexicon",
                    entries = listOf("anchor" to utf8("A heavy object used to moor a vessel."))
                )
            )
        )
        val firstImport = service.importFromFile(validZip)
        assertTrue(firstImport is DictionaryImportResult.Success)
        val dictId = (firstImport as DictionaryImportResult.Success).installed.single().id

        // Re-importing identical ZIP must reject duplicate instead of silently overwriting
        val duplicateImport = service.importFromFile(validZip)
        assertTrue("Duplicate dictionary must be rejected", duplicateImport is DictionaryImportResult.Failure)
        assertEquals(1, service.getInstalledDictionaries().size)

        // Simulate app restart by recreating service against same rootDir
        DictionaryService.resetForTesting()
        val restarted = DictionaryService.createForTesting(context, rootDir)
        assertEquals(1, restarted.getInstalledDictionaries().size)
        val afterRestart = restarted.lookup("anchor")
        assertTrue(afterRestart is DictionaryLookupOutcome.Found)

        // Delete removes both catalog entry and disk files
        val dictDir = File(rootDir, dictId)
        assertTrue(dictDir.isDirectory)
        assertTrue(restarted.deleteDictionary(dictId))
        assertFalse("Dictionary files must be deleted cleanly", dictDir.exists())
        assertEquals(0, restarted.getInstalledDictionaries().size)
        assertTrue(restarted.lookup("anchor") is DictionaryLookupOutcome.NoDictionariesInstalled)
    }

    @Test
    fun testGenuinePartOfSpeechExtractionAndNoFabricationAndConciseMeaning() {
        val xdxfEntry = ByteArrayOutputStream().apply {
            write('x'.code)
            write(utf8("<k>lantern</k><gr>n.</gr><dtrn>A lamp with a transparent case protecting the flame.</dtrn>"))
            write(0)
        }.toByteArray()

        val htmlPosEntry = ByteArrayOutputStream().apply {
            write('h'.code)
            write(utf8("<span class=\"pos\">adj.</span><br/>Shining brightly with reflected light."))
            write(0)
        }.toByteArray()

        val plainNoPosEntry = ByteArrayOutputStream().apply {
            write('m'.code)
            write(utf8("A small portable device used for navigation at sea.\n\nSecondary extended nautical usage with historical details that go on for quite some length."))
            write(0)
        }.toByteArray()

        val plainPrefixPosEntry = ByteArrayOutputStream().apply {
            write('m'.code)
            write(utf8("v. Move swiftly on foot."))
            write(0)
        }.toByteArray()

        val zip = buildStarDictZip(
            tempFolder.newFile("pos_test.zip"),
            listOf(
                TestDictSpec(
                    baseName = "posdict",
                    bookName = "POS Test Lexicon",
                    sameTypeSequence = "",
                    entries = listOf(
                        "lantern" to xdxfEntry,
                        "luminous" to htmlPosEntry,
                        "sextant" to plainNoPosEntry,
                        "sprint" to plainPrefixPosEntry
                    )
                )
            )
        )

        val res = service.importFromFile(zip)
        assertTrue(res is DictionaryImportResult.Success)

        val lantern = (service.lookup("lantern") as DictionaryLookupOutcome.Found).entries.single()
        assertEquals("noun", lantern.partOfSpeech)

        val luminous = (service.lookup("luminous") as DictionaryLookupOutcome.Found).entries.single()
        assertEquals("adjective", luminous.partOfSpeech)

        val sprint = (service.lookup("sprint") as DictionaryLookupOutcome.Found).entries.single()
        assertEquals("verb", sprint.partOfSpeech)

        // When no POS metadata exists, it MUST remain null (never invented)
        val sextant = (service.lookup("sextant") as DictionaryLookupOutcome.Found).entries.single()
        assertEquals(null, sextant.partOfSpeech)

        // Concise excerpt extracts only the primary paragraph without fabricating ranking labels
        val concise = com.example.feature.language.dictionary.StarDictFormatParser.extractConciseMeaning(sextant.definitionText)
        assertTrue(concise.contains("navigation at sea"))
        assertFalse("Concise popup excerpt must omit secondary paragraphs", concise.contains("Secondary extended"))
        assertFalse(concise.contains("Most Common", ignoreCase = true))
    }
}
