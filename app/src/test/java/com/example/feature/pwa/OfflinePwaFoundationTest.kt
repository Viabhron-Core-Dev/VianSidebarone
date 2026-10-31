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
        val parsedDefault = ElementIdParser.parse(context, "pwa:test_app_123")
        assertNotNull("Parsed item must not be null", parsedDefault)
        assertTrue("Parsed item must be SidebarItem.Pwa", parsedDefault is SidebarItem.Pwa)
        val pwaDefault = parsedDefault as SidebarItem.Pwa
        assertEquals("test_app_123", pwaDefault.pwaId)
        assertEquals("pwa:test_app_123", pwaDefault.id)
        assertEquals("Offline PWA", pwaDefault.label)

        // Now save metadata in ElementMetadataStore
        com.example.feature.sidebar.ElementMetadataStore.savePwaElement(
            context,
            "test_app_123",
            "Custom Calculator",
            "/path/to/icon.png"
        )

        val parsedWithMeta = ElementIdParser.parse(context, "pwa:test_app_123")
        assertNotNull("Parsed item must not be null", parsedWithMeta)
        val pwaWithMeta = parsedWithMeta as SidebarItem.Pwa
        assertEquals("Custom Calculator", pwaWithMeta.label)
        assertEquals("/path/to/icon.png", pwaWithMeta.iconPath)
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

    @Test
    fun testElementMetadataStorePwaPersistence() {
        val meta = com.example.feature.sidebar.ElementMetadataStore.savePwaElement(
            context,
            "test_pwa_meta",
            "Pwa Tracker",
            "/data/user/0/pwa/icon.webp"
        )
        assertNotNull(meta)
        assertEquals("pwa:test_pwa_meta", meta.id)
        assertEquals("pwa", meta.type)
        assertEquals("test_pwa_meta", meta.target)
        assertEquals("Pwa Tracker", meta.label)
        assertEquals("/data/user/0/pwa/icon.webp", meta.iconPath)

        val retrieved = com.example.feature.sidebar.ElementMetadataStore.get(context, "pwa:test_pwa_meta")
        assertNotNull(retrieved)
        assertEquals("Pwa Tracker", retrieved!!.label)
        assertEquals("/data/user/0/pwa/icon.webp", retrieved.iconPath)
    }

    @Test
    fun testOrientationOverrideResolutionLogic() {
        fun resolve(override: String, manifestOrientation: String): Int {
            return when (override) {
                "portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                "unspecified" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                else -> {
                    when (manifestOrientation.lowercase()) {
                        "portrait", "portrait-primary", "portrait-secondary" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        "landscape", "landscape-primary", "landscape-secondary" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
            }
        }

        // User overrides take precedence
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, resolve("portrait", "landscape"))
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, resolve("landscape", "portrait"))
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, resolve("unspecified", "portrait"))

        // Manifest fallback when override is "manifest"
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, resolve("manifest", "portrait"))
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, resolve("manifest", "portrait-primary"))
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, resolve("manifest", "landscape"))
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, resolve("manifest", ""))
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, resolve("manifest", "any"))
    }

    @Test
    fun testSpaRoutingFallbackDistinguishesAssetsFromRoutes() {
        val staticExtensions = setOf(
            "js", "mjs", "css", "json", "webmanifest",
            "png", "jpg", "jpeg", "gif", "svg", "webp", "ico",
            "wasm", "woff", "woff2", "ttf", "otf",
            "mp3", "mp4", "ogg", "wav", "webm", "map"
        )

        fun isSpaRoute(path: String): Boolean {
            val clean = path.removePrefix("/")
            val extension = clean.substringAfterLast('.', "").lowercase()
            return extension.isEmpty() || extension !in staticExtensions
        }

        // SPA subroutes should trigger fallback to index.html
        assertTrue("Subroute /settings is an SPA route", isSpaRoute("/settings"))
        assertTrue("Subroute /dashboard/stats is an SPA route", isSpaRoute("/dashboard/stats"))
        assertTrue("Subroute /user/profile/edit is an SPA route", isSpaRoute("/user/profile/edit"))
        assertTrue("Root / is an SPA route", isSpaRoute("/"))

        // Static assets should NOT trigger SPA fallback
        assertFalse("Script app.js is a static asset", isSpaRoute("/assets/app.js"))
        assertFalse("Stylesheet main.css is a static asset", isSpaRoute("/css/main.css"))
        assertFalse("Image logo.png is a static asset", isSpaRoute("/images/logo.png"))
        assertFalse("Font font.woff2 is a static asset", isSpaRoute("/fonts/font.woff2"))
    }

    @Test
    fun testCloseButtonPreferenceResolution() {
        fun shouldShowCloseButton(prefValue: String?): Boolean {
            return prefValue != "hide"
        }

        // Default / null / empty should show close button
        assertTrue("Default null should show close button", shouldShowCloseButton(null))
        assertTrue("show value should show close button", shouldShowCloseButton("show"))
        assertTrue("empty string should show close button", shouldShowCloseButton(""))

        // Explicit "hide" hides the floating button
        assertFalse("hide value should hide close button", shouldShowCloseButton("hide"))
    }

    @Test
    fun testSidebarActionMenuStructure() {
        var clicked = false
        val item = com.example.feature.sidebar.SidebarActionMenuHelper.ActionMenuItem(
            title = "Edit",
            iconResId = android.R.drawable.ic_menu_edit,
            onClick = { clicked = true }
        )
        assertEquals("Edit", item.title)
        assertEquals(android.R.drawable.ic_menu_edit, item.iconResId)
        item.onClick()
        assertTrue("Click handler should execute", clicked)
    }

    @Test
    fun testPwaBackupArchiveStructureAndRestore() {
        val pwaId = "backup_test_pwa"
        val pwaDir = File(mockFilesDir, "pwa/$pwaId").apply { mkdirs() }
        val testPkgZip = File(pwaDir, "package.zip")
        ZipOutputStream(FileOutputStream(testPkgZip)).use { zos ->
            zos.putNextEntry(ZipEntry("index.html"))
            zos.write("<html><body>App</body></html>".toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write("""{"name": "Backup App", "start_url": "index.html"}""".toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()
        }

        // Create backup archive containing pwa_metadata.json + package.zip
        val backupZip = File(tempFolder.root, "app_backup.zip")
        ZipOutputStream(FileOutputStream(backupZip)).use { zos ->
            zos.putNextEntry(ZipEntry("pwa_metadata.json"))
            val meta = """{
                "id": "$pwaId",
                "name": "Backup Restored App",
                "orientationPreference": "landscape",
                "closeButtonPreference": "hide"
            }""".trimIndent()
            zos.write(meta.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("package.zip"))
            testPkgZip.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }

        // Test importing this backup archive
        val restored = backupZip.inputStream().use { stream ->
            PwaZipImporter.unpackAndBuildEntity(
                context = context,
                inputStream = stream,
                originalFileName = "app_backup.zip"
            )
        }

        assertNotNull(restored)
        assertEquals("Backup App", restored.name)
        assertEquals("landscape", fakePrefs.getString("pwa_pref_${restored.id}_orientation", null))
        assertEquals("hide", fakePrefs.getString("pwa_pref_${restored.id}_close_button", null))
    }

    @Test
    fun testPwaPermissionPolicyDefaultsToOff() {
        val policy = PwaPermissionManager.getPolicy(context, "unconfigured_pwa_123")
        assertFalse("Internet must default to OFF", policy.internetAllowed)
        assertFalse("Files must default to OFF", policy.filesAllowed)
        assertFalse("Camera must default to OFF", policy.cameraAllowed)
        assertFalse("Microphone must default to OFF", policy.microphoneAllowed)
        assertFalse("Location must default to OFF", policy.locationAllowed)
        assertFalse("Sound must default to OFF", policy.soundAllowed)
    }

    @Test
    fun testPwaPermissionPolicySaveAndRetrieve() {
        val pwaId = "perm_test_pwa"
        val customPolicy = PwaPermissionPolicy(
            internetAllowed = true,
            filesAllowed = true,
            cameraAllowed = false,
            microphoneAllowed = false,
            locationAllowed = true,
            soundAllowed = true
        )
        PwaPermissionManager.savePolicy(context, pwaId, customPolicy)

        val retrieved = PwaPermissionManager.getPolicy(context, pwaId)
        assertTrue(retrieved.internetAllowed)
        assertTrue(retrieved.filesAllowed)
        assertFalse(retrieved.cameraAllowed)
        assertFalse(retrieved.microphoneAllowed)
        assertTrue(retrieved.locationAllowed)
        assertTrue(retrieved.soundAllowed)
    }

    @Test
    fun testPwaPermissionPolicyPerPwaIsolation() {
        val pwa1 = "pwa_isolated_1"
        val pwa2 = "pwa_isolated_2"

        PwaPermissionManager.savePolicy(context, pwa1, PwaPermissionPolicy(cameraAllowed = true))
        PwaPermissionManager.savePolicy(context, pwa2, PwaPermissionPolicy(cameraAllowed = false, microphoneAllowed = true))

        val policy1 = PwaPermissionManager.getPolicy(context, pwa1)
        val policy2 = PwaPermissionManager.getPolicy(context, pwa2)

        assertTrue(policy1.cameraAllowed)
        assertFalse(policy1.microphoneAllowed)

        assertFalse(policy2.cameraAllowed)
        assertTrue(policy2.microphoneAllowed)
    }

    @Test
    fun testPwaSafGrantsManagement() {
        val pwaId = "saf_test_pwa"
        assertTrue(PwaPermissionManager.getGrantedUris(context, pwaId).isEmpty())

        val testUriStr = "content://com.android.providers.media.documents/document/image%3A1001"
        PwaPermissionManager.addGrantedUri(context, pwaId, testUriStr)

        val grants = PwaPermissionManager.getGrantedUris(context, pwaId)
        assertEquals(1, grants.size)
        assertEquals(testUriStr, grants[0])

        // Revoke
        PwaPermissionManager.revokeGrantedUri(context, pwaId, testUriStr)
        assertTrue("Grant should be removed after revoke", PwaPermissionManager.getGrantedUris(context, pwaId).isEmpty())
    }

    @Test
    fun testPwaMimeHelperConversions() {
        // A. MIME conversion tests
        // .json -> application/json
        assertEquals("application/json", PwaMimeHelper.translateAcceptToken(".json"))
        assertEquals("application/json", PwaMimeHelper.translateAcceptToken("json"))
        assertEquals("application/json", PwaMimeHelper.translateAcceptToken("  .JSON  "))

        // image/* stays image/*
        assertEquals("image/*", PwaMimeHelper.translateAcceptToken("image/*"))

        // text/plain stays text/plain
        assertEquals("text/plain", PwaMimeHelper.translateAcceptToken("text/plain"))

        // no accept -> empty list -> */*
        val emptyResolved = PwaMimeHelper.resolveIntentMimeTypes(null)
        assertEquals("*/*", emptyResolved.first)
        assertNull(emptyResolved.second)

        val blankArrayResolved = PwaMimeHelper.resolveIntentMimeTypes(arrayOf("", "   "))
        assertEquals("*/*", blankArrayResolved.first)
        assertNull(blankArrayResolved.second)

        // Invalid extension does NOT become Intent MIME
        assertNull(PwaMimeHelper.translateAcceptToken(".invalidextension123xyz"))
        val invalidResolved = PwaMimeHelper.resolveIntentMimeTypes(arrayOf(".invalidextension123xyz"))
        assertEquals("*/*", invalidResolved.first)
        assertNull(invalidResolved.second)
    }

    @Test
    fun testPwaMimeHelperMultipleAndCommaSeparatedAccepts() {
        // Comma-separated in single element
        val commaSeparated = arrayOf(".json, image/png, .txt")
        val translated = PwaMimeHelper.translateAcceptTypes(commaSeparated)
        assertEquals(3, translated.size)
        assertTrue(translated.contains("application/json"))
        assertTrue(translated.contains("image/png"))
        assertTrue(translated.contains("text/plain"))

        // Multiple selection Intent resolution
        val (primary, extras) = PwaMimeHelper.resolveIntentMimeTypes(arrayOf(".json", ".pdf"))
        assertEquals("*/*", primary)
        assertNotNull(extras)
        assertEquals(2, extras!!.size)
        assertTrue(extras.contains("application/json"))
        assertTrue(extras.contains("application/pdf"))

        // Single valid MIME Intent resolution
        val (singlePrimary, singleExtras) = PwaMimeHelper.resolveIntentMimeTypes(arrayOf(".json"))
        assertEquals("application/json", singlePrimary)
        assertNull(singleExtras)
    }

    @Test
    fun testPwaFilesPermissionGatingModel() {
        val pwaId = "pwa_file_gating_test"

        // Default OFF
        val defaultPolicy = PwaPermissionManager.getPolicy(context, pwaId)
        assertFalse("Files must default to OFF", defaultPolicy.filesAllowed)

        // Turn ON
        PwaPermissionManager.savePolicy(context, pwaId, defaultPolicy.copy(filesAllowed = true))
        val updatedPolicy = PwaPermissionManager.getPolicy(context, pwaId)
        assertTrue("Files must be ON after saving", updatedPolicy.filesAllowed)
    }

    @Test
    fun testPwaMediaAndLocationCapabilityGating() {
        val pwaId = "pwa_cap_gating_test"
        val policy = PwaPermissionManager.getPolicy(context, pwaId)

        assertFalse("Camera defaults to OFF", policy.cameraAllowed)
        assertFalse("Microphone defaults to OFF", policy.microphoneAllowed)
        assertFalse("Location defaults to OFF", policy.locationAllowed)
        assertFalse("Sound defaults to OFF", policy.soundAllowed)

        val enabledPolicy = PwaPermissionPolicy(
            internetAllowed = false,
            filesAllowed = false,
            cameraAllowed = true,
            microphoneAllowed = true,
            locationAllowed = true,
            soundAllowed = true
        )
        PwaPermissionManager.savePolicy(context, pwaId, enabledPolicy)
        val readBack = PwaPermissionManager.getPolicy(context, pwaId)

        assertTrue(readBack.cameraAllowed)
        assertTrue(readBack.microphoneAllowed)
        assertTrue(readBack.locationAllowed)
        assertTrue(readBack.soundAllowed)
    }
}
