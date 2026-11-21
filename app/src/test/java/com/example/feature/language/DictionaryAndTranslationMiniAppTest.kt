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
import com.example.feature.floating.MiniAppState
import com.example.feature.language.dictionary.DictionaryImportResult
import com.example.feature.language.dictionary.DictionaryLookupOutcome
import com.example.feature.language.dictionary.DictionaryService
import com.example.feature.language.translation.TranslationOutcome
import com.example.feature.language.translation.TranslationService
import com.example.feature.selection.MeaningSelectionActivity
import com.example.feature.selection.TranslateSelectionActivity
import com.example.feature.settings.ROUTE_DICTIONARY_TRANSLATION_SETTINGS
import kotlinx.coroutines.runBlocking
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
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    private lateinit var dictionaryService: DictionaryService
    private lateinit var translationBackend: TranslationServiceTest.FakeTranslationEngineBackend
    private lateinit var translationService: TranslationService

    @Before
    fun setUp() {
        val prefs = FakeSharedPreferences()
        val filesDir = tempFolder.newFolder("lang_test")
        context = MockTestContext(prefs, filesDir)
        DurableMiniAppStore.resetForTesting()
        FloatingMiniAppBridge.resetForTesting()
        DictionaryService.resetForTesting()
        TranslationService.resetForTesting()

        dictionaryService = DictionaryService.createForTesting(context, File(filesDir, "stardict_dictionaries"))
        translationBackend = TranslationServiceTest.FakeTranslationEngineBackend()
        translationService = TranslationService.createForTesting(
            context,
            File(filesDir, "mlkit_translation/prefs.json"),
            translationBackend
        )

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
        DictionaryService.resetForTesting()
        TranslationService.resetForTesting()
    }

    private fun createSimpleStarDictZip(name: String, word: String, definition: String): File {
        val zip = tempFolder.newFile("${name.replace(" ", "_")}.zip")
        val defBytes = definition.toByteArray(StandardCharsets.UTF_8)
        val idxBaos = ByteArrayOutputStream()
        DataOutputStream(idxBaos).use { dos ->
            dos.write(word.toByteArray(StandardCharsets.UTF_8))
            dos.writeByte(0)
            dos.writeInt(0)
            dos.writeInt(defBytes.size)
        }
        val idxBytes = idxBaos.toByteArray()
        val ifo = "StarDict's dict ifo file\nversion=2.4.2\nwordcount=1\nidxfilesize=${idxBytes.size}\nbookname=$name\nsametypesequence=m\n"
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("d.ifo")); zos.write(ifo.toByteArray(StandardCharsets.UTF_8)); zos.closeEntry()
            zos.putNextEntry(ZipEntry("d.idx")); zos.write(idxBytes); zos.closeEntry()
            zos.putNextEntry(ZipEntry("d.dict")); zos.write(defBytes); zos.closeEntry()
        }
        return zip
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

        // Both keep the shared bottom-right three-control pill [ x | - | resize ]
        assertNotNull(dictWin.getBottomRightStickerViewForTesting())
        assertNotNull(transWin.getBottomRightStickerViewForTesting())
        assertTrue(dictWin.isStickerResizeVisibleForTesting())
        assertTrue(transWin.isStickerResizeVisibleForTesting())

        assertTrue("Dictionary initial width must fit screen compactly", dictWin.bounds.width in 200..960)
        assertTrue("Dictionary initial height must be compact", dictWin.bounds.height < 850)
        assertTrue("Translation initial width must fit screen compactly", transWin.bounds.width in 200..980)
        assertTrue("Translation initial height must be compact", transWin.bounds.height < 850)

        assertNotNull(dictWin.getDictionaryInstanceForTesting())
        assertNull(dictWin.getTranslationInstanceForTesting())
        assertNotNull(transWin.getTranslationInstanceForTesting())
        assertNull(transWin.getDictionaryInstanceForTesting())
    }

    @Test
    fun testDictionaryMiniAppAndMeaningPopupShareEngineAndReflectSettingsChanges() {
        // Before import -> NoDictionariesInstalled
        assertTrue(MeaningSelectionActivity.performSharedLookup(dictionaryService, "ephemeral") is DictionaryLookupOutcome.NoDictionariesInstalled)

        // Import dictionary via shared DictionaryService (same service used by Settings)
        val zip = createSimpleStarDictZip("Oxford Sample", "ephemeral", "Lasting for a very short time.")
        val importRes = dictionaryService.importFromFile(zip)
        assertTrue(importRes is DictionaryImportResult.Success)
        val dictId = (importRes as DictionaryImportResult.Success).installed.single().id

        // Meaning popup gets real attributed definition immediately
        val popupOutcome = MeaningSelectionActivity.performSharedLookup(dictionaryService, "  ephemeral ")
        assertTrue(popupOutcome is DictionaryLookupOutcome.Found)
        val popupEntry = (popupOutcome as DictionaryLookupOutcome.Found).entries.single()
        assertEquals("Oxford Sample", popupEntry.dictionaryName)
        assertTrue(popupEntry.definitionText.contains("short time"))

        // Floating Dictionary mini-app searches and persists across sleep/wake
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

        // Settings disable -> both popup and service reflect immediately
        dictionaryService.setDictionaryEnabled(dictId, false)
        assertTrue(MeaningSelectionActivity.performSharedLookup(dictionaryService, "ephemeral") is DictionaryLookupOutcome.AllDictionariesDisabled)

        woken.getDictionaryInstanceForTesting()?.clearSearch()
        assertEquals("", woken.getDictionaryInstanceForTesting()?.getLastSubmittedQueryForTesting())
    }

    @Test
    fun testTranslationMiniAppAndTranslatePopupShareServiceAndRespectMissingModels() = runBlocking {
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

        // Shared config updated in TranslationService so Translate popup inherits it
        val cfg = translationService.getConfig()
        assertEquals("ja", cfg.sourceLanguageCode)
        assertEquals("es", cfg.targetLanguageCode)

        // Without downloaded models, Translate popup reports MissingModels and mini-app refuses fake output/copy
        val popupMissing = TranslateSelectionActivity.performSharedTranslation(translationService, "Good morning")
        assertTrue(popupMissing is TranslationOutcome.MissingModels)

        instance.submitTranslation("Good morning")
        assertNull("Must never fabricate fake translation output", instance.getTranslatedOutputForTesting())
        assertFalse("Copy must return false when no real translation exists", instance.copyOutputToClipboard(context))

        // Change defaults back via Settings (auto -> en), download Spanish + English models -> popup produces real output
        translationService.updateConfig(sourceLanguageCode = "auto", targetLanguageCode = "en")
        translationBackend.downloaded.addAll(listOf("es", "en"))
        val popupOk = TranslateSelectionActivity.performSharedTranslation(translationService, "Hola mundo")
        assertTrue(popupOk is TranslationOutcome.Success)
        assertEquals("Hello world", (popupOk as TranslationOutcome.Success).translatedText)

        // Verify sleep/wake preserves session language & input state
        instance.selectSourceLanguage("Japanese")
        instance.selectTargetLanguage("Spanish")
        instance.submitTranslation("Good morning")

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
        val dictWin = bridge.openMiniApp("miniapp_dictionary", "dictionary")
        val transWin = bridge.openMiniApp("miniapp_translation", "translation")
        assertNotNull("Dictionary top bar must expose Edit button", dictWin.getTopBarNoteEditButtonForTesting())
        assertNotNull("Translation top bar must expose Edit button", transWin.getTopBarNoteEditButtonForTesting())

        val bridgeSrc = listOf(
            File("src/main/java/com/example/feature/floating/FloatingMiniAppBridge.kt"),
            File("app/src/main/java/com/example/feature/floating/FloatingMiniAppBridge.kt")
        ).first { it.exists() }.readText()
        assertTrue(bridgeSrc.contains("ROUTE_DICTIONARY_TRANSLATION_SETTINGS"))
        assertTrue(bridgeSrc.contains("launchDictionaryTranslationSettings"))
    }

    @Test
    fun testSharedSettingsScreenIsFunctionalNotPlaceholderAndAddElementWired() {
        val settingsScreen = listOf(
            File("src/main/java/com/example/feature/settings/DictionaryTranslationSettingsScreen.kt"),
            File("app/src/main/java/com/example/feature/settings/DictionaryTranslationSettingsScreen.kt")
        ).first { it.exists() }.readText()
        assertTrue(settingsScreen.contains("settings_section_dictionary"))
        assertTrue(settingsScreen.contains("settings_section_translation"))
        assertTrue(settingsScreen.contains("DictionaryService.getInstance"))
        assertTrue(settingsScreen.contains("TranslationService.getInstance"))
        assertTrue(settingsScreen.contains("ActivityResultContracts.OpenDocument"))
        assertTrue(settingsScreen.contains("translation_wifi_only_switch"))
        assertTrue(settingsScreen.contains("translation_browse_models_button"))
        assertFalse("Settings screen must not keep old placeholder rows", settingsScreen.contains("PlaceholderSettingRow"))
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
