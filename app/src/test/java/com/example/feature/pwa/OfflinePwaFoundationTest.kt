package com.example.feature.pwa

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.example.core.FakeSharedPreferences
import com.example.feature.element.ElementIdParser
import com.example.feature.sidebar.SidebarItem
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfflinePwaFoundationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockFilesDir: File
    private lateinit var mockCacheDir: File
    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var context: MockTestContext

    private class MockTestContext(
        private val files: File,
        private val cache: File,
        private val prefs: SharedPreferences
    ) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example"
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    @Before
    fun setUp() {
        mockFilesDir = tempFolder.newFolder("files")
        mockCacheDir = tempFolder.newFolder("cache")
        fakePrefs = FakeSharedPreferences()
        context = MockTestContext(mockFilesDir, mockCacheDir, fakePrefs)
    }

    @Test
    fun testManifestParserExtractsMetadataAndBestIcon() {
        val manifestJson = """
            {
                "name": "My Offline PWA",
                "short_name": "OfflinePWA",
                "start_url": "./index.html?source=pwa",
                "display": "standalone",
                "orientation": "portrait",
                "theme_color": "#123456",
                "background_color": "#FAFAFA",
                "icons": [
                    { "src": "icon-small.png", "sizes": "64x64", "type": "image/png" },
                    { "src": "icon-large.png", "sizes": "512x512", "type": "image/png" },
                    { "src": "icon-medium.png", "sizes": "192x192", "type": "image/png" }
                ]
            }
        """.trimIndent()

        val parsed = PwaManifestParser.parseJson(manifestJson)
        assertEquals("My Offline PWA", parsed.name)
        assertEquals("OfflinePWA", parsed.shortName)
        assertEquals("index.html?source=pwa", parsed.startUrl)
        assertEquals("standalone", parsed.displayMode)
        assertEquals("portrait", parsed.orientation)
        assertEquals("#123456", parsed.themeColor)
        assertEquals("#FAFAFA", parsed.backgroundColor)
        // 512x512 is the largest icon
        assertEquals("icon-large.png", parsed.iconRelativePath)
    }

    @Test
    fun testManifestParserHandlesMinimalOrEmptyJson() {
        val parsed = PwaManifestParser.parseJson("{}", "Default Fallback")
        assertEquals("Default Fallback", parsed.name)
        assertEquals("index.html", parsed.startUrl)
        assertEquals("standalone", parsed.displayMode)
        assertNull(parsed.iconRelativePath)
    }

    @Test
    fun testZipImporterUnpacksSafelyAndRetainsPackageZip() {
        // Create a test zip with index.html and manifest.json
        val zipFile = File(tempFolder.root, "test_dist.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            val manifestContent = """{"name": "Calculator PWA", "start_url": "index.html"}"""
            zos.write(manifestContent.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("index.html"))
            val htmlContent = "<html><body><h1>Calculator PWA</h1></body></html>"
            zos.write(htmlContent.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()
        }

        val result = zipFile.inputStream().use { stream ->
            PwaZipImporter.unpackAndBuildEntity(
                context = context,
                inputStream = stream,
                originalFileName = "calculator_dist.zip"
            )
        }

        assertNotNull("Import result should succeed", result)
        assertEquals("Calculator PWA", result.name)
        assertEquals("index.html", result.startUrl)

        // Verify permanent zip retention
        val permanentZip = File(result.zipPath)
        assertTrue("Permanent zip package must exist on disk", permanentZip.exists())
        assertTrue("Permanent zip package must be non-empty", permanentZip.length() > 0)

        // Verify unpacked web assets
        val unpackedDir = File(result.unpackedPath)
        assertTrue("Unpacked directory must exist", unpackedDir.exists())
        val indexFile = File(unpackedDir, "index.html")
        assertTrue("index.html must be unpacked", indexFile.exists())
        val manifestFile = File(unpackedDir, "manifest.json")
        assertTrue("manifest.json must be unpacked", manifestFile.exists())
    }

    @Test
    fun testStorageManagerDeletesAllExtractedAssetsAndPermanentZip() {
        val pwaId = "cleanup_test_pwa"
        val pwaDir = File(mockFilesDir, "pwa/$pwaId").apply { mkdirs() }
        val packageZip = File(pwaDir, "package.zip").apply { writeText("dummy zip content") }
        val unpackedDir = File(pwaDir, "unpacked").apply { mkdirs() }
        File(unpackedDir, "index.html").writeText("<html></html>")

        val dataDir = File(mockFilesDir, "pwa_data/$pwaId").apply { mkdirs() }
        File(dataDir, "dom_storage.db").writeText("db")

        val cacheDir = File(mockCacheDir, "pwa_cache/$pwaId").apply { mkdirs() }
        File(cacheDir, "cache.tmp").writeText("cache")

        assertTrue(packageZip.exists())
        assertTrue(dataDir.exists())
        assertTrue(cacheDir.exists())

        // Clean up through storage manager
        PwaStorageManager.deletePwaFiles(context, pwaId)

        assertFalse("Package zip directory must be deleted", pwaDir.exists())
        assertFalse("Data directory must be deleted", dataDir.exists())
        assertFalse("Cache directory must be deleted", cacheDir.exists())
    }

    @Test
    fun testElementIdParserParsesPwaPrefix() {
        val parsed = ElementIdParser.parse(context, "pwa:test_app_123")
        assertNotNull("Parsed item must not be null", parsed)
        assertTrue("Parsed item must be SidebarItem.Pwa", parsed is SidebarItem.Pwa)
        val pwaItem = parsed as SidebarItem.Pwa
        assertEquals("test_app_123", pwaItem.pwaId)
        assertEquals("pwa:test_app_123", pwaItem.id)
    }

    @Test
    fun testStorageManagerPathIsolation() {
        val dir1 = PwaStorageManager.getIsolatedDataDir(context, "app1")
        val dir2 = PwaStorageManager.getIsolatedDataDir(context, "app2")
        assertNotEquals("PWA data paths must be isolated per app ID", dir1.absolutePath, dir2.absolutePath)
        assertTrue(dir1.absolutePath.endsWith("pwa_data/app1"))
        assertTrue(dir2.absolutePath.endsWith("pwa_data/app2"))

        val cache1 = PwaStorageManager.getIsolatedCacheDir(context, "app1")
        val cache2 = PwaStorageManager.getIsolatedCacheDir(context, "app2")
        assertNotEquals("PWA cache paths must be isolated per app ID", cache1.absolutePath, cache2.absolutePath)
        assertTrue(cache1.absolutePath.endsWith("pwa_cache/app1"))
        assertTrue(cache2.absolutePath.endsWith("pwa_cache/app2"))
    }
}
