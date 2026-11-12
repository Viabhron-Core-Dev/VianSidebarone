package com.example.feature.pwa.floating

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.example.core.FakeSharedPreferences
import com.example.core.WindowBounds
import com.example.feature.floating.HeavyFloatingHost
import com.example.feature.floating.MiniAppLifecycleState
import com.example.feature.floating.MiniAppRecoverySnapshot
import com.example.feature.pwa.PwaPermissionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FloatingPwaMiniAppTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: MockFloatingPwaContext
    private lateinit var tempDir: File
    private lateinit var fakePrefs: FakeSharedPreferences

    private class MockFloatingPwaContext(
        private val prefs: SharedPreferences,
        private val baseFilesDir: File
    ) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example"
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getFilesDir(): File = baseFilesDir
        override fun getCacheDir(): File = baseFilesDir
        override fun getSystemService(name: String): Any? = null
    }

    @Before
    fun setUp() {
        tempDir = tempFolder.newFolder("floating_pwa_test")
        fakePrefs = FakeSharedPreferences()
        testContext = MockFloatingPwaContext(fakePrefs, tempDir)
        HeavyFloatingHost.resetForTesting()
    }

    @Test
    fun testHeavyHostInstantiatesFloatingPwaMiniAppInstance() {
        val host = HeavyFloatingHost.getInstance(testContext)
        val instance = host.startInstance(
            instanceId = "floating_pwa_1",
            appType = FloatingPwaMiniAppInstance.APP_TYPE,
            initialBounds = WindowBounds(80, 120, 620, 820)
        )

        assertNotNull("HeavyFloatingHost must instantiate FloatingPwaMiniAppInstance", instance)
        assertTrue(instance is FloatingPwaMiniAppInstance)
        assertEquals(MiniAppLifecycleState.RESUMED, instance?.lifecycleState)

        host.releaseInstance("floating_pwa_1", "test_teardown")
    }

    @Test
    fun testSnapshotPersistenceAndRestore() {
        val instance = FloatingPwaMiniAppInstance("floating_pwa_snapshot", FloatingPwaMiniAppInstance.APP_TYPE)
        val initialSnapshot = MiniAppRecoverySnapshot(
            instanceId = "floating_pwa_snapshot",
            appType = FloatingPwaMiniAppInstance.APP_TYPE,
            payload = mutableMapOf(
                FloatingPwaMiniAppInstance.KEY_PWA_ID to "pwa_123",
                FloatingPwaMiniAppInstance.KEY_CURRENT_URL to "https://pwa-pwa_123.local/index.html",
                FloatingPwaMiniAppInstance.KEY_CURRENT_TITLE to "Offline Calculator"
            )
        )

        instance.onCreate(initialSnapshot)
        instance.onStart()
        instance.onResume()

        assertEquals("pwa_123", instance.getActivePwaIdForTesting())
        assertEquals("https://pwa-pwa_123.local/index.html", instance.getCurrentUrlForTesting())
        assertEquals("Offline Calculator", instance.getCurrentTitleForTesting())

        val savedState = instance.onSaveState()
        assertEquals("pwa_123", savedState[FloatingPwaMiniAppInstance.KEY_PWA_ID])
        assertEquals("https://pwa-pwa_123.local/index.html", savedState[FloatingPwaMiniAppInstance.KEY_CURRENT_URL])
        assertEquals("Offline Calculator", savedState[FloatingPwaMiniAppInstance.KEY_CURRENT_TITLE])

        val sleepPayload = instance.prepareForSleep()
        assertEquals(MiniAppLifecycleState.SLEEPING, instance.lifecycleState)
        assertEquals("pwa_123", sleepPayload[FloatingPwaMiniAppInstance.KEY_PWA_ID])
        assertEquals("https://pwa-pwa_123.local/index.html", sleepPayload[FloatingPwaMiniAppInstance.KEY_CURRENT_URL])
        assertEquals("Offline Calculator", sleepPayload[FloatingPwaMiniAppInstance.KEY_CURRENT_TITLE])

        instance.releaseRuntime()
        instance.onDestroy()
        assertEquals(MiniAppLifecycleState.DESTROYED, instance.lifecycleState)
        assertNull(instance.getWebViewForTesting())
    }

    @Test
    fun testUrlNormalizationAndVirtualOriginHelpers() {
        assertEquals("about:blank", FloatingPwaUrlHelper.normalizeInputUrl("   "))
        assertEquals("https://example.com/app", FloatingPwaUrlHelper.normalizeInputUrl("example.com/app"))
        assertEquals("http://localhost:8080", FloatingPwaUrlHelper.normalizeInputUrl("http://localhost:8080"))
        assertEquals("https://docs.example.org", FloatingPwaUrlHelper.normalizeInputUrl("https://docs.example.org"))

        assertEquals(
            "https://pwa-abc123.local/app/index.html",
            FloatingPwaUrlHelper.buildVirtualOriginUrl("abc123", "/app/index.html")
        )
        assertEquals(
            "https://pwa-abc123.local/index.html",
            FloatingPwaUrlHelper.buildVirtualOriginUrl("abc123", "./index.html")
        )

        assertTrue(FloatingPwaUrlHelper.isVirtualOrLocalHost("pwa-abc123.local", "pwa-abc123.local"))
        assertTrue(FloatingPwaUrlHelper.isVirtualOrLocalHost("sub.local", null))
        assertTrue(FloatingPwaUrlHelper.isVirtualOrLocalHost("localhost", null))
        assertFalse(FloatingPwaUrlHelper.isVirtualOrLocalHost("evil.com", "pwa-abc123.local"))
    }

    @Test
    fun testNetworkPermissionPolicyEnforcement() {
        val offlinePolicy = PwaPermissionPolicy(internetAllowed = false)
        val onlinePolicy = PwaPermissionPolicy(internetAllowed = true)

        // Local virtual origin is always allowed even when offlinePolicy has internetAllowed=false
        assertTrue(
            FloatingPwaUrlHelper.shouldAllowNetworkRequest(
                hasActivePwa = true,
                policy = offlinePolicy,
                scheme = "https",
                host = "pwa-app1.local",
                virtualHost = "pwa-app1.local"
            )
        )

        // External HTTPS blocked for active offline PWA when internetAllowed=false
        assertFalse(
            FloatingPwaUrlHelper.shouldAllowNetworkRequest(
                hasActivePwa = true,
                policy = offlinePolicy,
                scheme = "https",
                host = "api.example.com",
                virtualHost = "pwa-app1.local"
            )
        )

        // External HTTPS allowed when policy.internetAllowed=true
        assertTrue(
            FloatingPwaUrlHelper.shouldAllowNetworkRequest(
                hasActivePwa = true,
                policy = onlinePolicy,
                scheme = "https",
                host = "api.example.com",
                virtualHost = "pwa-app1.local"
            )
        )

        // External HTTPS allowed in floating web URL mode (no active offline PWA)
        assertTrue(
            FloatingPwaUrlHelper.shouldAllowNetworkRequest(
                hasActivePwa = false,
                policy = offlinePolicy,
                scheme = "https",
                host = "api.example.com",
                virtualHost = null
            )
        )
    }

    @Test
    fun testResolveSafeLocalFileBlocksDirectoryTraversal() {
        val webRoot = File(tempDir, "pwa_bundle").apply { mkdirs() }
        val secret = File(tempDir, "secret.txt").apply { writeText("top-secret") }
        assertTrue(secret.exists())
        val index = File(webRoot, "assets/app.js").apply {
            parentFile?.mkdirs()
            writeText("console.log('ok');")
        }

        val resolved = FloatingPwaUrlHelper.resolveSafeLocalFile(webRoot, "/assets/app.js")
        assertNotNull(resolved)
        assertEquals(index.canonicalPath, resolved?.canonicalPath)

        val traversal = FloatingPwaUrlHelper.resolveSafeLocalFile(webRoot, "/../secret.txt")
        assertNull("Path traversal outside webRoot must be blocked", traversal)
    }

    @Test
    fun testHandleCommandWithoutWebViewIsSafe() {
        val instance = FloatingPwaMiniAppInstance("floating_pwa_cmd", FloatingPwaMiniAppInstance.APP_TYPE)
        instance.onCreate(null)
        assertTrue(instance.handleCommand(testContext, FloatingPwaMiniAppInstance.CMD_PWA_BACK, emptyMap()))
        assertTrue(instance.handleCommand(testContext, FloatingPwaMiniAppInstance.CMD_PWA_FORWARD, emptyMap()))
        assertTrue(instance.handleCommand(testContext, FloatingPwaMiniAppInstance.CMD_PWA_RELOAD, emptyMap()))
        assertTrue(instance.handleCommand(testContext, FloatingPwaMiniAppInstance.CMD_PWA_STOP, emptyMap()))
        assertFalse(instance.handleCommand(testContext, "unknown_action", emptyMap()))
        instance.onDestroy()
    }
}
