package com.example.feature.language

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.view.WindowManager
import com.example.core.FakeSharedPreferences
import com.example.core.FloatingWindowManager
import com.example.feature.floating.DurableMiniAppStore
import com.example.feature.floating.FloatingMiniAppBridge
import com.example.feature.floating.FloatingMiniAppWindow
import com.example.feature.floating.MiniAppRecoverySnapshot
import com.example.feature.floating.MiniAppState
import com.example.feature.settings.ROUTE_DICTIONARY_TRANSLATION_SETTINGS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy

class DictionaryAndTranslationMiniAppTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class MockTestContext(
        private val prefs: SharedPreferences,
        private val baseFilesDir: File
    ) : ContextWrapper(null) {
        val startedIntents = mutableListOf<Intent>()

        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example"
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getFilesDir(): File = baseFilesDir

        override fun startActivity(intent: Intent?) {
            if (intent != null) startedIntents.add(intent)
        }

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
            ) { _, _, _ -> null } as WindowManager
        }

        override fun getSystemService(name: String): Any? {
            return if (name == Context.WINDOW_SERVICE) mockWindowManager else null
        }
    }

    private lateinit var context: MockTestContext
    private lateinit var bridge: FloatingMiniAppBridge
    private lateinit var store: DurableMiniAppStore

    @Before
    fun setUp() {
        val prefs = FakeSharedPreferences()
        context = MockTestContext(prefs, tempFolder.newFolder("lang_test"))
        DurableMiniAppStore.resetForTesting()
        FloatingMiniAppBridge.resetForTesting()
        val wm = FloatingWindowManager.getInstance(context)
        wm.getAllWindows().map { it.windowId }.forEach { wm.unregisterWindow(it) }
        bridge = FloatingMiniAppBridge.getInstance(context)
        store = DurableMiniAppStore.getInstance(context)
    }

    @After
    fun tearDown() {
        FloatingMiniAppBridge.resetForTesting()
        val wm = FloatingWindowManager.getInstance(context)
        wm.getAllWindows().map { it.windowId }.forEach { wm.unregisterWindow(it) }
        DurableMiniAppStore.resetForTesting()
    }

    @Test
    fun testDictionaryAndTranslationLaunchIndependentlyWithCompactBoundsAndSharedChrome() {
        assertTrue(bridge.isMainOnlyMiniApp("dictionary"))
        assertTrue(bridge.isMainOnlyMiniApp("translation"))

        val dictWin = bridge.openMiniApp("miniapp_dictionary", "dictionary")
        val transWin = bridge.openMiniApp("miniapp_translation", "translation")

        assertNotEquals("Dictionary and Translation must have separate window IDs", dictWin.windowId, transWin.windowId)
        assertTrue(dictWin.isDictionaryApp())
        assertFalse(dictWin.isTranslationApp())
        assertTrue(transWin.isTranslationApp())
        assertFalse(transWin.isDictionaryApp())

        // Both must keep the shared bottom-right three-control pill [ x | - | resize ]
        assertNotNull(dictWin.getBottomRightStickerViewForTesting())
        assertNotNull(transWin.getBottomRightStickerViewForTesting())
        assertTrue(dictWin.isStickerResizeVisibleForTesting())
        assertTrue(transWin.isStickerResizeVisibleForTesting())

        // Both must use compact sensible initial bounds rather than oversized hardcoded 650x850
        assertTrue("Dictionary initial width must fit screen compactly", dictWin.bounds.width in 200..960)
        assertTrue("Dictionary initial height must be compact", dictWin.bounds.height < 850)
        assertTrue("Translation initial width must fit screen compactly", transWin.bounds.width in 200..980)
        assertTrue("Translation initial height must be compact", transWin.bounds.height < 850)

        // Separate mini-app instances (not combined tabs)
        assertNotNull(dictWin.getDictionaryInstanceForTesting())
        assertNull(dictWin.getTranslationInstanceForTesting())
        assertNotNull(transWin.getTranslationInstanceForTesting())
        assertNull(transWin.getDictionaryInstanceForTesting())
    }

    @Test
    fun testDictionaryStateSearchClearAndSleepWakePersistence() {
        val dictWin = bridge.openMiniApp("miniapp_dictionary", "dictionary")
        val instance = dictWin.getDictionaryInstanceForTesting()!!

        assertEquals("", instance.getLastSubmittedQueryForTesting())
        instance.submitSearch("ephemeral")
        assertEquals("ephemeral", instance.getLastSubmittedQueryForTesting())
        assertEquals("ephemeral", instance.getCurrentQueryForTesting())

        var sleepDone = false
        bridge.sleepMiniApp("miniapp_dictionary") { sleepDone = it }
        assertTrue(sleepDone)

        val snap = store.loadSnapshot("miniapp_dictionary")
        assertNotNull(snap)
        assertEquals(MiniAppState.SLEEPING, snap!!.state)
        assertEquals("ephemeral", snap.payload[DictionaryMiniAppInstance.KEY_QUERY])
        assertEquals("ephemeral", snap.payload[DictionaryMiniAppInstance.KEY_SUBMITTED_QUERY])

        bridge.wakeMiniApp("miniapp_dictionary")
        val woken = FloatingWindowManager.getInstance(context).getWindow("miniapp_dictionary") as FloatingMiniAppWindow
        assertEquals("ephemeral", woken.getDictionaryInstanceForTesting()?.getLastSubmittedQueryForTesting())

        woken.getDictionaryInstanceForTesting()?.clearSearch()
        assertEquals("", woken.getDictionaryInstanceForTesting()?.getLastSubmittedQueryForTesting())
    }

    @Test
    fun testTranslationStateLanguageSelectorsSwapHonestCopyAndSleepWakePersistence() {
        val transWin = bridge.openMiniApp("miniapp_translation", "translation")
        val instance = transWin.getTranslationInstanceForTesting()!!

        assertEquals("Auto-detect", instance.getSourceLanguageForTesting())
        assertEquals("English", instance.getTargetLanguageForTesting())
        assertFalse("Swap must safely refuse when source is Auto-detect", instance.swapLanguages())

        instance.selectSourceLanguage("Spanish")
        instance.selectTargetLanguage("Japanese")
        assertTrue(instance.swapLanguages())
        assertEquals("Japanese", instance.getSourceLanguageForTesting())
        assertEquals("Spanish", instance.getTargetLanguageForTesting())

        instance.submitTranslation("Good morning")
        assertNull("Must never fabricate fake translation output", instance.getTranslatedOutputForTesting())
        assertFalse("Copy must return false when no real translation exists", instance.copyOutputToClipboard(context))

        var sleepDone = false
        bridge.sleepMiniApp("miniapp_translation") { sleepDone = it }
        assertTrue(sleepDone)

        bridge.wakeMiniApp("miniapp_translation")
        val woken = FloatingWindowManager.getInstance(context).getWindow("miniapp_translation") as FloatingMiniAppWindow
        val restored = woken.getTranslationInstanceForTesting()!!
        assertEquals("Japanese", restored.getSourceLanguageForTesting())
        assertEquals("Spanish", restored.getTargetLanguageForTesting())
        assertEquals("Good morning", restored.getInputTextForTesting())
    }

    @Test
    fun testBothMiniAppsOpenSharedDictionaryTranslationSettingsRoute() {
        bridge.launchDictionaryTranslationSettings("miniapp_dictionary")
        bridge.launchDictionaryTranslationSettings("miniapp_translation")

        assertEquals(2, context.startedIntents.size)
        // Also verify window top-bar Edit buttons exist on both floating windows
        val dictWin = bridge.openMiniApp("miniapp_dictionary", "dictionary")
        val transWin = bridge.openMiniApp("miniapp_translation", "translation")
        assertNotNull("Dictionary top bar must expose Edit button", dictWin.getTopBarNoteEditButtonForTesting())
        assertNotNull("Translation top bar must expose Edit button", transWin.getTopBarNoteEditButtonForTesting())

        // Verify bridge launch intent targets ROUTE_DICTIONARY_TRANSLATION_SETTINGS in SettingsActivity
        val bridgeSrc = listOf(
            File("src/main/java/com/example/feature/floating/FloatingMiniAppBridge.kt"),
            File("app/src/main/java/com/example/feature/floating/FloatingMiniAppBridge.kt")
        ).first { it.exists() }.readText()
        assertTrue(bridgeSrc.contains("ROUTE_DICTIONARY_TRANSLATION_SETTINGS"))
        assertTrue(bridgeSrc.contains("launchDictionaryTranslationSettings"))
    }

    @Test
    fun testSharedSettingsAndAddElementEntriesAreWiredCorrectly() {
        val settingsScreen = listOf(
            File("src/main/java/com/example/feature/settings/DictionaryTranslationSettingsScreen.kt"),
            File("app/src/main/java/com/example/feature/settings/DictionaryTranslationSettingsScreen.kt")
        ).first { it.exists() }.readText()
        assertTrue(settingsScreen.contains("settings_section_dictionary"))
        assertTrue(settingsScreen.contains("settings_section_translation"))
        assertTrue(settingsScreen.contains("StarDict Support"))
        assertTrue(settingsScreen.contains("Translation Models & Packs"))
        assertEquals("dictionary_translation_settings", ROUTE_DICTIONARY_TRANSLATION_SETTINGS)

        val settingsActivitySrc = listOf(
            File("src/main/java/com/example/SettingsActivity.kt"),
            File("app/src/main/java/com/example/SettingsActivity.kt")
        ).first { it.exists() }.readText()
        assertTrue(settingsActivitySrc.contains("ROUTE_DICTIONARY_TRANSLATION_SETTINGS"))
        assertTrue(settingsActivitySrc.contains("DictionaryTranslationSettingsScreen"))

        val addElementSrc = listOf(
            File("src/main/java/com/example/feature/settings/AddElementActivity.kt"),
            File("app/src/main/java/com/example/feature/settings/AddElementActivity.kt")
        ).first { it.exists() }.readText()
        assertTrue(addElementSrc.contains("\"Floating Trigger\""))
        assertTrue(addElementSrc.contains("system:dictionary_floating"))
        assertTrue(addElementSrc.contains("system:translation_floating"))
    }
}
