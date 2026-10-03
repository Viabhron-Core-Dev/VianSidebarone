package com.example.feature.element

import android.content.Context
import android.content.ContextWrapper
import com.example.feature.sidebar.ElementMetadata
import com.example.feature.sidebar.SidebarItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LinkCustomTabIntegrationTest {

    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example"
    }

    private lateinit var context: TestContext

    @Before
    fun setUp() {
        context = TestContext()
        CustomTabLauncher.testCustomTabOpener = null
    }

    @After
    fun tearDown() {
        CustomTabLauncher.testCustomTabOpener = null
    }

    @Test
    fun testHttpsUrlValidationStrict() {
        // Valid HTTPS URLs
        assertTrue(CustomTabLauncher.isValidHttpsUrl("https://google.com"))
        assertTrue(CustomTabLauncher.isValidHttpsUrl("https://example.org/path/resource?query=1#frag"))
        assertTrue(CustomTabLauncher.isValidHttpsUrl("HTTPS://GITHUB.COM/AISTUDIO"))
        assertTrue(CustomTabLauncher.isValidHttpsUrl("  https://subdomain.domain.co.uk:8443/api  "))

        // Strictly reject HTTP and non-HTTPS protocols
        assertFalse(CustomTabLauncher.isValidHttpsUrl("http://insecure.example.com"))
        assertFalse(CustomTabLauncher.isValidHttpsUrl("http://google.com"))
        assertFalse(CustomTabLauncher.isValidHttpsUrl("ftp://ftp.example.com"))
        assertFalse(CustomTabLauncher.isValidHttpsUrl("file:///android_asset/page.html"))
        assertFalse(CustomTabLauncher.isValidHttpsUrl("content://media/external/images"))
        assertFalse(CustomTabLauncher.isValidHttpsUrl("javascript:alert('xss')"))

        // Strictly reject invalid, empty, or blank inputs
        assertFalse(CustomTabLauncher.isValidHttpsUrl(""))
        assertFalse(CustomTabLauncher.isValidHttpsUrl("   "))
        assertFalse(CustomTabLauncher.isValidHttpsUrl(null))
        assertFalse(CustomTabLauncher.isValidHttpsUrl("not_a_url"))
        assertFalse(CustomTabLauncher.isValidHttpsUrl("https://"))
    }

    @Test
    fun testElementMetadataJsonSerializationWithBrowserAndAccount() {
        val meta = ElementMetadata(
            id = "link:uuid-1234",
            type = "link",
            target = "https://example.com/docs",
            label = "Documentation",
            iconPath = "/cache/link_icon.webp",
            browserPackage = "org.mozilla.firefox",
            account = "Work Profile"
        )

        val json = meta.toJson()
        assertEquals("link:uuid-1234", json.getString("id"))
        assertEquals("link", json.getString("type"))
        assertEquals("https://example.com/docs", json.getString("target"))
        assertEquals("Documentation", json.getString("label"))
        assertEquals("/cache/link_icon.webp", json.getString("iconPath"))
        assertEquals("org.mozilla.firefox", json.getString("browserPackage"))
        assertEquals("Work Profile", json.getString("account"))

        val deserialized = ElementMetadata.fromJson(json.toString())
        assertNotNull(deserialized)
        assertEquals("link:uuid-1234", deserialized!!.id)
        assertEquals("org.mozilla.firefox", deserialized.browserPackage)
        assertEquals("Work Profile", deserialized.account)
    }

    @Test
    fun testElementMetadataBackwardCompatibilityWithoutBrowserOrAccount() {
        val rawLegacyJson = """
            {
                "id": "link:legacy-uuid",
                "type": "link",
                "target": "https://legacy.example.com",
                "label": "Legacy Link",
                "iconPath": ""
            }
        """.trimIndent()

        val deserialized = ElementMetadata.fromJson(rawLegacyJson)
        assertNotNull(deserialized)
        assertEquals("link:legacy-uuid", deserialized!!.id)
        assertEquals("https://legacy.example.com", deserialized.target)
        assertNull(deserialized.browserPackage)
        assertNull(deserialized.account)
    }

    @Test
    fun testSidebarItemLinkSerialization() {
        val linkItem = SidebarItem.Link(
            uuid = "test-uuid-456",
            url = "https://aistudio.google.com",
            label = "AI Studio",
            iconPath = "/data/icon.webp",
            browserPackage = "com.android.chrome",
            account = "Developer",
            id = "link:test-uuid-456"
        )

        assertEquals("link:test-uuid-456", linkItem.id)
        assertEquals("com.android.chrome", linkItem.browserPackage)
        assertEquals("Developer", linkItem.account)

        val serialized = linkItem.toSerializedId()
        assertTrue(serialized.startsWith("link:test-uuid-456:{"))
        assertTrue(serialized.contains("\"browserPackage\":\"com.android.chrome\""))
        assertTrue(serialized.contains("\"account\":\"Developer\""))
        assertTrue(serialized.contains("\"url\":\"https://aistudio.google.com\""))
    }

    @Test
    fun testOpenLinkRejectsHttpAndInvokesLauncherForHttps() {
        var launchedUrl: String? = null
        var launchedBrowser: String? = null

        CustomTabLauncher.testCustomTabOpener = { _, url, browser ->
            launchedUrl = url
            launchedBrowser = browser
            true
        }

        // 1. Non-HTTPS link is rejected before opener callback
        CustomTabLauncher.testCustomTabOpener = null
        val httpResult = CustomTabLauncher.openLink(context, "http://insecure.org", "com.android.chrome")
        assertFalse("HTTP URL must be rejected", httpResult)
        assertNull(launchedUrl)

        // 2. Verified HTTPS link passes to opener
        CustomTabLauncher.testCustomTabOpener = { _, url, browser ->
            launchedUrl = url
            launchedBrowser = browser
            true
        }
        val httpsResult = CustomTabLauncher.openLink(context, "https://secure.org/portal", "org.mozilla.firefox")
        assertTrue("Valid HTTPS URL must succeed", httpsResult)
        assertEquals("https://secure.org/portal", launchedUrl)
        assertEquals("org.mozilla.firefox", launchedBrowser)
    }

    @Test
    fun testElementActionDispatcherExecutesLinkViaCustomTab() {
        var dispatchedUrl: String? = null
        var dispatchedBrowser: String? = null

        CustomTabLauncher.testCustomTabOpener = { _, url, browser ->
            dispatchedUrl = url
            dispatchedBrowser = browser
            true
        }

        val linkItem = SidebarItem.Link(
            uuid = "item-777",
            url = "https://en.wikipedia.org",
            label = "Wikipedia",
            iconPath = null,
            browserPackage = "com.android.chrome",
            account = null,
            id = "link:item-777"
        )

        val handled = ElementActionDispatcher.execute(context, linkItem)
        assertTrue("ElementActionDispatcher should handle Link successfully", handled)
        assertEquals("https://en.wikipedia.org", dispatchedUrl)
        assertEquals("com.android.chrome", dispatchedBrowser)
    }
}
