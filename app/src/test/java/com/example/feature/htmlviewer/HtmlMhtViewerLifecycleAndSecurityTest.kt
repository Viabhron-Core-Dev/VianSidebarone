package com.example.feature.htmlviewer

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import com.example.core.FakeSharedPreferences
import com.example.core.FloatingWindowManager
import com.example.core.WindowBounds
import com.example.core.ipc.HeavyProcessConnectionManager
import com.example.feature.floating.DurableMiniAppStore
import com.example.feature.floating.FloatingMiniAppBridge
import com.example.feature.floating.FloatingMiniAppLauncherBubble
import com.example.feature.floating.FloatingMiniAppWindow
import com.example.feature.floating.MiniAppRecoverySnapshot
import com.example.feature.floating.MiniAppState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference

class HtmlMhtViewerLifecycleAndSecurityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class MockTestContext(
        private val prefs: SharedPreferences,
        private val baseFilesDir: File,
        private val baseCacheDir: File,
        private val baseCodeCacheDir: File
    ) : ContextWrapper(null) {
        var failWindowAddView: Boolean = false

        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example"
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getFilesDir(): File = baseFilesDir
        override fun getCacheDir(): File = baseCacheDir
        override fun getCodeCacheDir(): File = baseCodeCacheDir

        private val fakeResources: android.content.res.Resources by lazy {
            val dm = android.util.DisplayMetrics().apply {
                density = 2f
                widthPixels = 1080
                heightPixels = 1920
            }
            val conf = android.content.res.Configuration()
            val am = try {
                android.content.res.AssetManager::class.java.getConstructor().newInstance()
            } catch (_: Throwable) {
                android.content.res.AssetManager::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            }
            @Suppress("DEPRECATION")
            object : android.content.res.Resources(am, dm, conf) {
                override fun getDisplayMetrics(): android.util.DisplayMetrics = dm
            }
        }

        override fun getResources(): android.content.res.Resources = fakeResources

        private val mockWindowManager: WindowManager by lazy {
            Proxy.newProxyInstance(
                WindowManager::class.java.classLoader,
                arrayOf(WindowManager::class.java)
            ) { _, method, _ ->
                if (method.name == "addView" && failWindowAddView) {
                    throw WindowManager.BadTokenException("Simulated overlay failure")
                }
                null
            } as WindowManager
        }

        override fun getSystemService(name: String): Any? {
            return if (name == Context.WINDOW_SERVICE) mockWindowManager else null
        }
    }

    private class FakeWebViewHandle(context: Context) : HtmlWebViewHandle {
        override val view: View = View(context)
        override var isDestroyed: Boolean = false
            private set
        override var appliedSecurityConfig: WebViewSecurityConfig? = null
            private set
        var loadedHtml: String? = null
            private set
        var securityApplyCount: Int = 0
            private set
        var pauseCount: Int = 0
            private set
        var resumeCount: Int = 0
            private set

        override fun applySecurityRestrictions(
            config: WebViewSecurityConfig,
            onNavigationBlocked: ((String) -> Unit)?
        ) {
            appliedSecurityConfig = config
            securityApplyCount++
        }

        override fun loadOfflineHtml(html: String) {
            loadedHtml = html
        }

        override fun onPause() {
            pauseCount++
        }

        override fun onResume() {
            resumeCount++
        }

        override fun stopAndDestroy() {
            isDestroyed = true
        }
    }

    private lateinit var context: MockTestContext
    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var codeCacheDir: File
    private lateinit var bridge: FloatingMiniAppBridge
    private lateinit var store: DurableMiniAppStore
    private lateinit var windowManager: FloatingWindowManager
    private val createdHandles = mutableListOf<FakeWebViewHandle>()

    @Before
    fun setUp() {
        createdHandles.clear()
        val prefs = FakeSharedPreferences()
        filesDir = tempFolder.newFolder("files")
        cacheDir = tempFolder.newFolder("cache")
        codeCacheDir = tempFolder.newFolder("code_cache")
        context = MockTestContext(prefs, filesDir, cacheDir, codeCacheDir)

        HtmlMhtViewerMiniAppInstance.testWebViewFactory = { ctx ->
            FakeWebViewHandle(ctx).also { createdHandles.add(it) }
        }

        DurableMiniAppStore.resetForTesting()
        FloatingMiniAppBridge.resetForTesting()
        FloatingWindowManager.resetForTesting()

        store = DurableMiniAppStore.getInstance(context)
        windowManager = FloatingWindowManager.getInstance(context)
        bridge = FloatingMiniAppBridge.getInstance(context)
    }

    @After
    fun tearDown() {
        HtmlMhtViewerMiniAppInstance.testWebViewFactory = null
        FloatingMiniAppBridge.resetForTesting()
        FloatingWindowManager.resetForTesting()
        DurableMiniAppStore.resetForTesting()
    }

    private fun writePreparedTempHtml(rawHtml: String): File {
        val sanitized = HtmlDocumentSanitizer.prepareForOfflineViewer(rawHtml)
        return requireNotNull(HtmlMhtCacheManager.writeTempHtml(context, sanitized))
    }

    // -------------------------------------------------------------------------
    // 1. Cache Isolation & Unified Size Cap Tests
    // -------------------------------------------------------------------------

    @Test
    fun testTempCacheLivesInCodeCacheDirOutsideFileProviderCacheRoot() {
        val tempFile = writePreparedTempHtml("<html><body><p>Isolated cache</p></body></html>")
        assertTrue(tempFile.exists())
        assertTrue(
            "Temp file must live under codeCacheDir, outside FileProvider's cacheDir",
            tempFile.canonicalPath.startsWith(codeCacheDir.canonicalPath + File.separator)
        )
        assertFalse(
            "Temp file must NOT be stored inside FileProvider-exposed cacheDir",
            tempFile.canonicalPath.startsWith(cacheDir.canonicalPath + File.separator)
        )
    }

    @Test
    fun testNearFiveMegabyteSanitizedHtmlRoundTripsCleanlyAndOversizedRejectedBeforeWrite() {
        // Exact 5 MiB raw HTML + injected offline guard header (~450B) must succeed write & read
        val bodyPayload = "A".repeat(MhtmlParser.DEFAULT_MAX_HTML_BYTES - 64)
        val rawNear5MiB = "<html><body>$bodyPayload</body></html>"
        val sanitized = HtmlDocumentSanitizer.prepareForOfflineViewer(rawNear5MiB)
        val writeRes = HtmlMhtCacheManager.writeTempHtmlChecked(context, sanitized)
        assertTrue("Valid 5 MiB document + injected guard must fit unified cache cap", writeRes is HtmlMhtCacheManager.TempWriteResult.Success)
        val writtenFile = (writeRes as HtmlMhtCacheManager.TempWriteResult.Success).file
        val readBack = HtmlMhtCacheManager.readTempHtml(context, writtenFile)
        assertNotNull("Written file must read back cleanly with matching cap", readBack)
        HtmlMhtCacheManager.deleteTempFile(writtenFile)

        // Over the unified prepared cap must fail cleanly BEFORE writing to disk
        val tooLarge = "B".repeat(HtmlMhtCacheManager.MAX_PREPARED_HTML_BYTES + 128)
        val oversizedRes = HtmlMhtCacheManager.writeTempHtmlChecked(context, tooLarge)
        assertTrue(oversizedRes is HtmlMhtCacheManager.TempWriteResult.Oversized)
        val cacheDirFiles = HtmlMhtCacheManager.getCacheDir(context)?.listFiles().orEmpty()
        assertTrue("Oversized write must not leave any file on disk", cacheDirFiles.isEmpty())
    }

    // -------------------------------------------------------------------------
    // 2. Lifecycle, Bounds, Pause/Resume & Failure Cleanup Tests
    // -------------------------------------------------------------------------

    @Test
    fun testViewerCreationIsMainOnlyBoundedAndLeavesZeroDurableSnapshots() {
        val tempFile = writePreparedTempHtml("<html><body><h1>Offline Guide</h1><p>Paragraph text.</p></body></html>")
        assertTrue(tempFile.exists())

        val result = bridge.openEphemeralHtmlViewer(
            tempHtmlFile = tempFile,
            displayTitle = "guide.html",
            formatLabel = "HTML"
        )
        assertTrue(result is FloatingMiniAppBridge.HtmlViewerOpenResult.Success)
        val success = result as FloatingMiniAppBridge.HtmlViewerOpenResult.Success

        assertEquals("guide.html", success.window.getWindowTitleForTesting())
        assertTrue(success.window.isHtmlMhtViewerApp())
        assertTrue(success.window.isMainOnlyApp())
        assertTrue(success.window.isHtmlMhtViewerReady())
        assertEquals(1, createdHandles.size)
        assertFalse(createdHandles[0].isDestroyed)
        assertTrue(createdHandles[0].loadedHtml!!.contains("<h1>Offline Guide</h1>"))

        // Bounded floating dimensions (never unbounded)
        assertTrue(success.window.bounds.width in 240..1080)
        assertTrue(success.window.bounds.height in 280..1920)

        // Pause / resume hooks wired through hide / show
        windowManager.hideWindow(success.instanceId)
        assertEquals(1, createdHandles[0].pauseCount)
        windowManager.showWindow(success.instanceId)
        assertTrue(createdHandles[0].resumeCount >= 1)

        // Zero durable snapshots & zero Heavy process consumers
        assertTrue(store.getAllSnapshots().isEmpty())
        assertNull(store.loadSnapshot(success.instanceId))
        val heavyConsumers = HeavyProcessConnectionManager.getInstance(context).getActiveConsumers()
        assertFalse(heavyConsumers.any { it.contains(success.instanceId) })
    }

    @Test
    fun testWebViewInitFailureCleansUpWindowSessionAndDeletesTempFile() {
        HtmlMhtViewerMiniAppInstance.testWebViewFactory = {
            throw IllegalStateException("Simulated WebView package failure")
        }
        val tempFile = writePreparedTempHtml("<html><body><p>Should clean up</p></body></html>")
        assertTrue(tempFile.exists())

        val result = bridge.openEphemeralHtmlViewer(
            tempHtmlFile = tempFile,
            displayTitle = "broken.html",
            formatLabel = "HTML"
        )
        assertTrue(result is FloatingMiniAppBridge.HtmlViewerOpenResult.Failure)
        val failure = result as FloatingMiniAppBridge.HtmlViewerOpenResult.Failure
        assertEquals("WEBVIEW_INIT_FAILED", failure.reasonCode)

        assertFalse("Temp cache file must be deleted on WebView init failure", tempFile.exists())
        assertTrue(windowManager.getAllWindows().isEmpty())
        assertTrue(bridge.getActiveEphemeralHtmlViewerIdsForTesting().isEmpty())
        assertTrue(store.getAllSnapshots().isEmpty())
    }

    @Test
    fun testOverlayAttachFailureCleansUpAndDeletesTempFile() {
        context.failWindowAddView = true
        val tempFile = writePreparedTempHtml("<html><body><p>Overlay blocked</p></body></html>")
        assertTrue(tempFile.exists())

        val result = bridge.openEphemeralHtmlViewer(
            tempHtmlFile = tempFile,
            displayTitle = "blocked.html",
            formatLabel = "HTML"
        )
        assertTrue(result is FloatingMiniAppBridge.HtmlViewerOpenResult.Failure)
        assertFalse("Temp file must be deleted when overlay attach fails", tempFile.exists())
        assertTrue(windowManager.getAllWindows().isEmpty())
        assertTrue(bridge.getActiveEphemeralHtmlViewerIdsForTesting().isEmpty())
    }

    @Test
    fun testFoldDestroysWebViewUnfoldRecreatesAndRepeatedCyclesNeverDuplicate() {
        val tempFile = writePreparedTempHtml("<html><body><h1>Repeatable</h1></body></html>")
        val opened = bridge.openEphemeralHtmlViewer(tempFile, "repeatable.html", "HTML")
            as FloatingMiniAppBridge.HtmlViewerOpenResult.Success
        val id = opened.instanceId

        // Move and maximize/restore before folding -> verify zero durable writes and preserved in-memory bounds
        val movedBounds = WindowBounds(90, 140, 720, 900)
        opened.window.setWindowBounds(movedBounds)
        bridge.updateEphemeralHtmlViewerBounds(id, movedBounds)
        opened.window.toggleMaximize()
        assertTrue(opened.window.isMaximized)
        opened.window.toggleMaximize()
        assertFalse(opened.window.isMaximized)
        assertTrue("Drag/maximize on ephemeral viewer must never write durable snapshots", store.getAllSnapshots().isEmpty())

        assertEquals(1, createdHandles.size)
        assertEquals(1, createdHandles.count { !it.isDestroyed })

        // Repeat fold -> unfold 3 times
        for (cycle in 1..3) {
            var foldCompleted = false
            bridge.sleepMiniApp(id) { ok -> foldCompleted = ok }
            assertTrue("Fold cycle $cycle should succeed", foldCompleted)

            assertEquals("Zero live WebViews allowed while folded (cycle $cycle)", 0, createdHandles.count { !it.isDestroyed })
            assertNull("Main window must be unregistered while folded", windowManager.getWindow(id))
            assertNotNull("Folded ephemeral bubble should exist", windowManager.getWindow("bubble_$id"))
            assertTrue("Temp file must remain while folded for unfold", tempFile.exists())
            assertTrue("Zero durable snapshots allowed while folded", store.getAllSnapshots().isEmpty())

            // Unfold back
            bridge.wakeMiniApp(id)
            assertNull("Folded bubble must be dismissed after unfold", windowManager.getWindow("bubble_$id"))
            val restoredWindow = windowManager.getWindow(id) as? FloatingMiniAppWindow
            assertNotNull("Viewer window must be recreated on unfold (cycle $cycle)", restoredWindow)
            assertTrue(restoredWindow!!.isHtmlMhtViewerReady())
            assertEquals(movedBounds.width, restoredWindow.bounds.width)
            assertEquals(movedBounds.height, restoredWindow.bounds.height)
            assertEquals("Exactly 1 live WebView must exist after unfold (cycle $cycle)", 1, createdHandles.count { !it.isDestroyed })
            assertEquals(cycle + 1, createdHandles.size)

            val activeHandle = createdHandles.last()
            assertNotNull(activeHandle.appliedSecurityConfig)
            assertFalse(activeHandle.appliedSecurityConfig!!.javaScriptEnabled)
            assertTrue(activeHandle.appliedSecurityConfig!!.blockNetworkLoads)
            assertTrue(activeHandle.loadedHtml!!.contains("<h1>Repeatable</h1>"))

            assertEquals(
                1,
                windowManager.getAllWindows().count { it is FloatingMiniAppWindow && it.windowId == id }
            )
        }
    }

    @Test
    fun testWakeFailureCleansUpOrphanedSessionAndDeletesTempFile() {
        val tempFile = writePreparedTempHtml("<html><body><p>Fold then fail wake</p></body></html>")
        val opened = bridge.openEphemeralHtmlViewer(tempFile, "wakefail.html", "HTML")
            as FloatingMiniAppBridge.HtmlViewerOpenResult.Success
        val id = opened.instanceId

        bridge.sleepMiniApp(id)
        assertTrue(tempFile.exists())
        assertNotNull(bridge.getEphemeralHtmlViewerSession(id))

        // Simulate overlay permission revoked while folded
        context.failWindowAddView = true
        bridge.wakeMiniApp(id)

        assertFalse("Temp file must be deleted on wake failure (no orphan)", tempFile.exists())
        assertNull("Orphaned ephemeral session must be removed on wake failure", bridge.getEphemeralHtmlViewerSession(id))
        assertNull(windowManager.getWindow(id))
        assertNull(windowManager.getWindow("bubble_$id"))
    }

    @Test
    fun testDirectWindowUnregisterOutsideFoldDeletesTempFileAndSession() {
        val tempFile = writePreparedTempHtml("<html><body><p>Direct teardown</p></body></html>")
        val opened = bridge.openEphemeralHtmlViewer(tempFile, "direct.html", "HTML")
            as FloatingMiniAppBridge.HtmlViewerOpenResult.Success
        val id = opened.instanceId

        // Direct WindowManager unregister without calling closeMiniApp or sleepMiniApp
        windowManager.unregisterWindow(id)

        assertEquals(0, createdHandles.count { !it.isDestroyed })
        assertFalse("Non-folded direct unregister must delete temp cache file", tempFile.exists())
        assertNull("Non-folded direct unregister must clear ephemeral session", bridge.getEphemeralHtmlViewerSession(id))
    }

    @Test
    fun testDirectWindowFoldAndUnfoldDestroysAndRecreatesWebViewCleanly() {
        val tempFile = writePreparedTempHtml("<html><body><p>TrimMemory fold test</p></body></html>")
        val opened = bridge.openEphemeralHtmlViewer(tempFile, "trim.html", "HTML")
            as FloatingMiniAppBridge.HtmlViewerOpenResult.Success
        val id = opened.instanceId

        assertEquals(1, createdHandles.count { !it.isDestroyed })
        assertTrue(windowManager.foldWindow(id))
        assertEquals(0, createdHandles.count { !it.isDestroyed })

        assertTrue(windowManager.unfoldWindow(id))
        assertEquals(1, createdHandles.count { !it.isDestroyed })
        assertEquals(2, createdHandles.size)
        assertTrue(opened.window.isHtmlMhtViewerReady())
    }

    @Test
    fun testCloseDestroysRuntimeDeletesTempFileCreatesNoBubbleAndLeavesNoHistory() {
        val tempFile = writePreparedTempHtml("<html><body><p>Private one-time doc</p></body></html>")
        val opened = bridge.openEphemeralHtmlViewer(tempFile, "private.html", "HTML")
            as FloatingMiniAppBridge.HtmlViewerOpenResult.Success
        val id = opened.instanceId

        var closeDone = false
        bridge.closeMiniApp(id, deleteDurable = false) { ok -> closeDone = ok }
        assertTrue(closeDone)

        assertEquals(0, createdHandles.count { !it.isDestroyed })
        assertFalse("Temp cache file MUST be deleted on close", tempFile.exists())
        assertNull("Window must be unregistered on close", windowManager.getWindow(id))
        assertNull("No launcher bubble may exist after close", windowManager.getWindow("bubble_$id"))
        assertTrue(windowManager.getAllWindows().none { it is FloatingMiniAppLauncherBubble })
        assertTrue(bridge.getActiveEphemeralHtmlViewerIdsForTesting().isEmpty())
        assertTrue("No durable snapshot or document history may be written", store.getAllSnapshots().isEmpty())
    }

    @Test
    fun testDurableGuardsBlockOpenMiniAppToggleAndRestoreActiveMiniAppsForHtmlViewer() {
        // 1. Direct openMiniApp must refuse ephemeral appType so no durable snapshot can ever be created
        try {
            bridge.openMiniApp("bad_id", HtmlMhtViewerMiniAppInstance.APP_TYPE)
            fail("openMiniApp must reject html_mht_viewer")
        } catch (_: IllegalArgumentException) {
            // Expected
        }
        assertTrue(store.getAllSnapshots().isEmpty())

        // 2. toggleMiniApp must no-op for ephemeral appType
        bridge.toggleMiniApp(HtmlMhtViewerMiniAppInstance.APP_TYPE)
        assertTrue(store.getAllSnapshots().isEmpty())
        assertTrue(windowManager.getAllWindows().isEmpty())

        // 3. Even if a stray snapshot existed on disk, restoreActiveMiniApps must never restore it
        store.saveSnapshot(
            MiniAppRecoverySnapshot(
                instanceId = "stray_html_viewer",
                appType = HtmlMhtViewerMiniAppInstance.APP_TYPE,
                state = MiniAppState.ACTIVE,
                windowBounds = WindowBounds(100, 100, 500, 600)
            )
        )
        val restored = bridge.restoreActiveMiniApps()
        assertTrue(restored.isEmpty())
        assertNull(windowManager.getWindow("stray_html_viewer"))
    }

    @Test
    fun testMultipleDocumentsOpenIndependentlyWithoutInstanceCollisions() {
        val fileA = writePreparedTempHtml("<html><body><h1>Doc A</h1></body></html>")
        val fileB = writePreparedTempHtml("<html><body><h1>Doc B</h1></body></html>")

        val openA = bridge.openEphemeralHtmlViewer(fileA, "alpha.html", "HTML")
            as FloatingMiniAppBridge.HtmlViewerOpenResult.Success
        val openB = bridge.openEphemeralHtmlViewer(fileB, "beta.mht", "MHT")
            as FloatingMiniAppBridge.HtmlViewerOpenResult.Success

        assertNotEquals(openA.instanceId, openB.instanceId)
        assertNotEquals(fileA.name, fileB.name)
        assertEquals("alpha.html", openA.window.getWindowTitleForTesting())
        assertEquals("beta.mht", openB.window.getWindowTitleForTesting())
        assertEquals(2, createdHandles.count { !it.isDestroyed })

        bridge.closeMiniApp(openA.instanceId, deleteDurable = true)
        assertFalse(fileA.exists())
        assertTrue(fileB.exists())
        assertNotNull(windowManager.getWindow(openB.instanceId))
        assertEquals(1, createdHandles.count { !it.isDestroyed })

        bridge.closeMiniApp(openB.instanceId, deleteDurable = true)
        assertFalse(fileB.exists())
        assertEquals(0, createdHandles.count { !it.isDestroyed })
    }

    @Test
    fun testAbandonedCacheCleanupRemovesOrphanFilesInBothCodeCacheAndLegacyCacheDir() {
        val orphan1 = writePreparedTempHtml("<html><body>Orphan 1</body></html>")
        val orphan2 = writePreparedTempHtml("<html><body>Orphan 2</body></html>")
        val legacyDir = File(cacheDir, HtmlMhtCacheManager.CACHE_SUBDIR_NAME).apply { mkdirs() }
        val legacyOrphan = File(legacyDir, "doc_legacy.html").apply { writeText("<html>legacy</html>") }
        assertTrue(orphan1.exists() && orphan2.exists() && legacyOrphan.exists())

        val cleaned = HtmlMhtCacheManager.cleanupAbandonedCacheFiles(
            context = context,
            activeCanonicalPaths = setOf(orphan2.canonicalPath)
        )
        assertEquals(2, cleaned)
        assertFalse(orphan1.exists())
        assertFalse(legacyOrphan.exists())
        assertTrue(orphan2.exists())

        val cleanedAll = HtmlMhtCacheManager.cleanupAbandonedCacheFiles(context)
        assertEquals(1, cleanedAll)
        assertFalse(orphan2.exists())
    }

    // -------------------------------------------------------------------------
    // 3. Security & Sanitizer Fidelity Tests
    // -------------------------------------------------------------------------

    @Test
    fun testStrictWebViewSecurityConfigAndNavigationBlocking() {
        val tempFile = writePreparedTempHtml("<html><body><a href=\"https://evil.example\">Link</a></body></html>")
        val opened = bridge.openEphemeralHtmlViewer(tempFile, "sec.html", "HTML")
            as FloatingMiniAppBridge.HtmlViewerOpenResult.Success

        val cfg = createdHandles.single().appliedSecurityConfig!!
        assertFalse("JavaScript must be disabled", cfg.javaScriptEnabled)
        assertFalse("JS window opening must be disabled", cfg.javaScriptCanOpenWindowsAutomatically)
        assertTrue("Network loads must be blocked", cfg.blockNetworkLoads)
        assertTrue("Network images must be blocked", cfg.blockNetworkImage)
        assertFalse("Automatic image loading must be disabled", cfg.loadsImagesAutomatically)
        assertFalse("File access must be disabled", cfg.allowFileAccess)
        assertFalse("Content access must be disabled", cfg.allowContentAccess)
        assertFalse("File URL cross-access must be disabled", cfg.allowFileAccessFromFileURLs)
        assertFalse("Universal file URL access must be disabled", cfg.allowUniversalAccessFromFileURLs)
        assertFalse("DOM storage must be disabled", cfg.domStorageEnabled)
        assertFalse("Database must be disabled", cfg.databaseEnabled)
        assertEquals(WebSettings.LOAD_NO_CACHE, cfg.cacheMode)
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, cfg.mixedContentMode)

        // Navigation blocking
        assertFalse(HtmlMhtWebViewSecurity.shouldBlockNavigation("about:blank"))
        assertFalse(HtmlMhtWebViewSecurity.shouldBlockNavigation("about:blank#chapter-2"))
        assertTrue(HtmlMhtWebViewSecurity.shouldBlockNavigation("https://example.com"))
        assertTrue(HtmlMhtWebViewSecurity.shouldBlockNavigation("http://example.com/page"))
        assertTrue(HtmlMhtWebViewSecurity.shouldBlockNavigation("file:///data/data/com.example/files/secret"))
        assertTrue(HtmlMhtWebViewSecurity.shouldBlockNavigation("content://com.example.provider/cache/doc.html"))
        assertTrue(HtmlMhtWebViewSecurity.shouldBlockNavigation("javascript:alert(1)"))
        assertTrue(HtmlMhtWebViewSecurity.shouldBlockNavigation("intent://scan/#Intent;end"))
        assertTrue(HtmlMhtWebViewSecurity.shouldBlockNavigation("data:text/html,<script>alert(1)</script>"))

        // Subresource blocking
        assertTrue(HtmlMhtWebViewSecurity.shouldBlockSubresource("https://cdn.example.com/app.css"))
        assertTrue(HtmlMhtWebViewSecurity.shouldBlockSubresource("https://cdn.example.com/pic.png"))
        assertTrue(HtmlMhtWebViewSecurity.shouldBlockSubresource("data:image/png;base64,AAAA"))
        assertTrue(HtmlMhtWebViewSecurity.shouldBlockSubresource("file:///sdcard/photo.jpg"))
        assertTrue(HtmlMhtWebViewSecurity.shouldBlockSubresource("cid:image001@mht"))

        bridge.closeMiniApp(opened.instanceId, deleteDurable = true)
    }

    @Test
    fun testSanitizerScrubsCssUrlsAndTagsWithoutCorruptingVisibleProseOrBlockingZoom() {
        val raw = """
            <!DOCTYPE html>
            <html>
            <head>
              <title>Clean Article</title>
              <link rel="stylesheet" href="https://example.com/style.css">
              <meta http-equiv="refresh" content="0;url=https://evil.example">
              <script>alert('xss')</script>
              <style>
                @import url('https://example.com/remote.css');
                .hero { background-image: url('https://example.com/bg.jpg'); color: #222; }
              </style>
            </head>
            <body>
              <h1>Article Heading</h1>
              <div style="background: url(https://example.com/inline.png); color: #333;">Inline CSS</div>
              <img src="data:image/png;base64,iVBORw0KGgo=" alt="a > b">
              <img src="https://example.com/remote.jpg">
              <picture><source srcset="https://example.com/a.webp"><img src="a.jpg"></picture>
              <iframe src="https://example.com/frame"></iframe>
              <p>In CSS you write url(hero.png) or call url(endpoint) in visible prose.</p>
            </body>
            </html>
        """.trimIndent()

        val prepared = HtmlDocumentSanitizer.prepareForOfflineViewer(raw)
        assertTrue(prepared.contains("Content-Security-Policy"))
        assertTrue(prepared.contains("img-src 'none'"))
        assertTrue(prepared.contains("script-src 'none'"))
        assertTrue(prepared.contains("svg image"))
        assertFalse("Must not inject overflow-x: hidden which breaks pinch-zoom panning", prepared.contains("overflow-x: hidden"))
        assertTrue(prepared.contains("<h1>Article Heading</h1>"))
        assertTrue(
            "Visible body prose containing url(...) must NOT be corrupted",
            prepared.contains("In CSS you write url(hero.png) or call url(endpoint) in visible prose.")
        )
        assertFalse("Scripts must be stripped", prepared.contains("<script", ignoreCase = true))
        assertFalse("Embedded/remote img tags (including alt with '>') must be stripped", prepared.contains("<img", ignoreCase = true))
        assertFalse("Attribute fragment after '>' inside alt must not leak", prepared.contains("b\">"))
        assertFalse("Picture tags must be stripped", prepared.contains("<picture", ignoreCase = true))
        assertFalse("Iframes must be stripped", prepared.contains("<iframe", ignoreCase = true))
        assertFalse("External stylesheet links must be stripped", prepared.contains("example.com/style.css"))
        assertFalse("CSS @import must be scrubbed", prepared.contains("@import"))
        assertFalse("Meta refresh redirects must be stripped", prepared.contains("http-equiv=\"refresh\"", ignoreCase = true))
        assertFalse("Style block CSS url() loads must be scrubbed", prepared.contains("example.com/bg.jpg"))
        assertFalse("Inline style attribute CSS url() loads must be scrubbed", prepared.contains("example.com/inline.png"))
    }

    @Test
    fun testSanitizerStripsRestrictiveDocumentViewportSoPinchZoomRemainsEnabled() {
        val raw = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
              <title>Locked Mobile Page</title>
            </head>
            <body><p>Zoomable text</p></body>
            </html>
        """.trimIndent()

        val prepared = HtmlDocumentSanitizer.prepareForOfflineViewer(raw)
        assertFalse("Restrictive user-scalable=no viewport must be stripped", prepared.contains("user-scalable=no"))
        assertFalse("Restrictive maximum-scale=1.0 viewport must be stripped", prepared.contains("maximum-scale=1.0"))
        assertEquals(
            "Exactly one injected zoom-capable viewport tag should remain",
            1,
            Regex("<meta\\b[^>]*name\\s*=\\s*\"viewport\"", RegexOption.IGNORE_CASE).findAll(prepared).count()
        )
    }

    @Test
    fun testPrepareDocumentHandlesPermissionDeniedUnreadableIoErrorOversizedAndTracksTempFile() {
        assertEquals("report.v2.html", HtmlMhtViewerActivity.extractFallbackDisplayName("primary:Download/report.v2.html"))
        assertEquals("saved.mht", HtmlMhtViewerActivity.extractFallbackDisplayName("msf:saved.mht"))

        // 1. SecurityException -> URI_PERMISSION_DENIED
        val permRef = AtomicReference<File?>(null)
        val permRes = HtmlMhtViewerActivity.prepareDocumentInternal(
            context = context,
            uriScheme = "content",
            intentMimeType = "text/html",
            displayName = "report.v2.html",
            resolvedMime = "text/html",
            createdTempFileRef = permRef,
            streamOpener = { throw SecurityException("Permission revoked") }
        )
        assertTrue(permRes is HtmlMhtViewerActivity.PrepareDocumentResult.Error)
        assertEquals("URI_PERMISSION_DENIED", (permRes as HtmlMhtViewerActivity.PrepareDocumentResult.Error).reasonCode)
        assertNull(permRef.get())

        // 2. Null stream -> URI_UNREADABLE
        val nullRes = HtmlMhtViewerActivity.prepareDocumentInternal(
            context = context,
            uriScheme = "content",
            intentMimeType = "text/html",
            displayName = "report.v2.html",
            resolvedMime = "text/html",
            streamOpener = { null }
        )
        assertTrue(nullRes is HtmlMhtViewerActivity.PrepareDocumentResult.Error)
        assertEquals("URI_UNREADABLE", (nullRes as HtmlMhtViewerActivity.PrepareDocumentResult.Error).reasonCode)

        // 3. IOException -> URI_READ_FAILED
        val ioRes = HtmlMhtViewerActivity.prepareDocumentInternal(
            context = context,
            uriScheme = "content",
            intentMimeType = "text/html",
            displayName = "report.v2.html",
            resolvedMime = "text/html",
            streamOpener = { throw IOException("Deleted while opening") }
        )
        assertTrue(ioRes is HtmlMhtViewerActivity.PrepareDocumentResult.Error)
        assertEquals("URI_READ_FAILED", (ioRes as HtmlMhtViewerActivity.PrepareDocumentResult.Error).reasonCode)

        // 4. Oversized HTML (> 5 MiB) -> OVERSIZED_HTML & no temp file written
        val oversizedHtml = ("<html><body>" + "X".repeat(MhtmlParser.DEFAULT_MAX_HTML_BYTES + 128) + "</body></html>")
            .toByteArray(Charsets.UTF_8)
        val bigRes = HtmlMhtViewerActivity.prepareDocumentInternal(
            context = context,
            uriScheme = "content",
            intentMimeType = "text/html",
            displayName = "huge.html",
            resolvedMime = "text/html",
            streamOpener = { ByteArrayInputStream(oversizedHtml) }
        )
        assertTrue(bigRes is HtmlMhtViewerActivity.PrepareDocumentResult.Error)
        assertEquals("OVERSIZED_HTML", (bigRes as HtmlMhtViewerActivity.PrepareDocumentResult.Error).reasonCode)

        // 5. Valid HTML -> Ready, temp file tracked in AtomicReference inside codeCacheDir
        val validRef = AtomicReference<File?>(null)
        val validHtml = "<!DOCTYPE html><html><body><h1>Prepared</h1></body></html>".toByteArray(Charsets.UTF_8)
        val okRes = HtmlMhtViewerActivity.prepareDocumentInternal(
            context = context,
            uriScheme = "content",
            intentMimeType = "application/octet-stream",
            displayName = HtmlMhtViewerActivity.extractFallbackDisplayName("primary:Download/report.v2.html"),
            resolvedMime = "application/octet-stream",
            createdTempFileRef = validRef,
            streamOpener = { ByteArrayInputStream(validHtml) }
        )
        assertTrue(okRes is HtmlMhtViewerActivity.PrepareDocumentResult.Ready)
        val ready = okRes as HtmlMhtViewerActivity.PrepareDocumentResult.Ready
        assertEquals("report.v2.html", ready.displayTitle)
        assertEquals(DetectedDocumentFormat.HTML, ready.format)
        assertEquals("Temp file must be recorded in createdTempFileRef for cancellation cleanup", ready.tempFile, validRef.get())
        assertTrue(ready.tempFile.exists())
        HtmlMhtCacheManager.deleteTempFile(ready.tempFile)
        assertFalse(ready.tempFile.exists())
    }

    @Test
    fun testCloseWhileFoldedDeletesTempFileRemovesBubbleAndLeavesZeroSnapshots() {
        val tempFile = writePreparedTempHtml("<html><body><p>Fold then close</p></body></html>")
        val opened = bridge.openEphemeralHtmlViewer(tempFile, "folded_close.html", "HTML")
            as FloatingMiniAppBridge.HtmlViewerOpenResult.Success
        val id = opened.instanceId

        bridge.sleepMiniApp(id)
        assertNotNull(windowManager.getWindow("bubble_$id"))
        assertTrue(tempFile.exists())

        var closeDone = false
        bridge.closeMiniApp(id, deleteDurable = false) { ok -> closeDone = ok }
        assertTrue(closeDone)
        assertNull("Folded bubble must be destroyed on close", windowManager.getWindow("bubble_$id"))
        assertNull("Window must remain absent", windowManager.getWindow(id))
        assertNull("Ephemeral session must be removed", bridge.getEphemeralHtmlViewerSession(id))
        assertFalse("Temp cache file must be deleted when closing while folded", tempFile.exists())
        assertTrue(store.getAllSnapshots().isEmpty())
    }

    @Test
    fun testFoldBubbleAttachFailureCleansUpSessionAndDeletesTempFile() {
        val tempFile = writePreparedTempHtml("<html><body><p>Fold bubble fail</p></body></html>")
        val opened = bridge.openEphemeralHtmlViewer(tempFile, "foldfail.html", "HTML")
            as FloatingMiniAppBridge.HtmlViewerOpenResult.Success
        val id = opened.instanceId

        // Revoke overlay addView right before folding so bubble attach fails
        context.failWindowAddView = true
        var foldResult: Boolean? = null
        bridge.sleepMiniApp(id) { ok -> foldResult = ok }

        assertEquals(false, foldResult)
        assertNull("No orphaned bubble may remain", windowManager.getWindow("bubble_$id"))
        assertNull("Orphaned ephemeral session must be removed on fold bubble failure", bridge.getEphemeralHtmlViewerSession(id))
        assertFalse("Temp cache file must be deleted on fold bubble failure", tempFile.exists())
        assertEquals(0, createdHandles.count { !it.isDestroyed })
    }

    @Test
    fun testSequentialDocumentOpensAndLiveThemeRefreshWorkCleanly() {
        val file1 = writePreparedTempHtml("<html><body><h1>First</h1></body></html>")
        val open1 = bridge.openEphemeralHtmlViewer(file1, "first.html", "HTML")
            as FloatingMiniAppBridge.HtmlViewerOpenResult.Success
        // Live system theme refresh on active viewer must not throw or create durable state
        open1.window.refreshSystemThemeIfApplicable()
        assertTrue(store.getAllSnapshots().isEmpty())

        bridge.closeMiniApp(open1.instanceId, deleteDurable = true)
        assertFalse(file1.exists())

        val file2 = writePreparedTempHtml("<html><body><h1>Second</h1></body></html>")
        val open2 = bridge.openEphemeralHtmlViewer(file2, "second.mht", "MHT")
            as FloatingMiniAppBridge.HtmlViewerOpenResult.Success
        assertNotEquals(open1.instanceId, open2.instanceId)
        assertEquals("second.mht", open2.window.getWindowTitleForTesting())
        assertTrue(createdHandles.last().loadedHtml!!.contains("<h1>Second</h1>"))

        bridge.closeMiniApp(open2.instanceId, deleteDurable = true)
        assertFalse(file2.exists())
        assertTrue(bridge.getActiveEphemeralHtmlViewerIdsForTesting().isEmpty())
    }
}
