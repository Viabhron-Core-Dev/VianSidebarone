package com.example.feature.language

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.view.WindowManager
import com.example.core.FakeSharedPreferences
import com.example.core.FloatingWindowManager
import com.example.core.WindowBounds
import com.example.feature.element.ElementActionDispatcher
import com.example.feature.floating.DurableMiniAppStore
import com.example.feature.floating.FloatingMiniAppBridge
import com.example.feature.floating.FloatingMiniAppWindow
import com.example.feature.floating.MiniAppRecoverySnapshot
import com.example.feature.floating.MiniAppState
import com.example.feature.floating.trigger.FloatingTriggerCompat
import com.example.feature.floating.trigger.FloatingTriggerTargetValidation
import com.example.feature.language.dictionary.DictionaryImportResult
import com.example.feature.language.dictionary.DictionaryLookupOutcome
import com.example.feature.language.dictionary.DictionaryService
import com.example.feature.selection.MeaningSelectionActivity
import com.example.feature.settings.ROUTE_DICTIONARY_SETTINGS
import com.example.feature.settings.ROUTE_DICTIONARY_TRANSLATION_SETTINGS
import com.example.feature.settings.ROUTE_LEGACY_TRANSLATION_SETTINGS
import com.example.feature.sidebar.SidebarItem
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
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DictionaryMiniAppTest {

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

    @Before
    fun setUp() {
        val prefs = FakeSharedPreferences()
        val filesDir = tempFolder.newFolder("dict_test")
        context = MockTestContext(prefs, filesDir)
        DurableMiniAppStore.resetForTesting()
        FloatingMiniAppBridge.resetForTesting()
        DictionaryService.resetForTesting()

        dictionaryService = DictionaryService.createForTesting(context, File(filesDir, "stardict_dictionaries"))

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
    }

    private fun resolveProjectPath(relativeFromRoot: String): File {
        val fromApp = relativeFromRoot.removePrefix("app/")
        val candidates = listOf(
            File(relativeFromRoot),
            File(fromApp),
            File("../$relativeFromRoot")
        )
        return candidates.first { it.exists() }
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
    fun testDictionaryLaunchesWithCompactBoundsAndSharedChrome() {
        assertTrue(bridge.isMainOnlyMiniApp("dictionary"))
        assertFalse("Translation must no longer be registered as an active mini-app", bridge.isMainOnlyMiniApp("translation"))

        val dictWin = bridge.openMiniApp("miniapp_dictionary", "dictionary")
        assertTrue(dictWin.isDictionaryApp())

        assertNotNull(dictWin.getBottomRightStickerViewForTesting())
        assertTrue(dictWin.isStickerResizeVisibleForTesting())
        assertTrue("Dictionary initial width must fit screen compactly", dictWin.bounds.width in 200..960)
        assertTrue("Dictionary initial height must be compact", dictWin.bounds.height < 850)
        assertNotNull(dictWin.getDictionaryInstanceForTesting())
    }

    @Test
    fun testDictionaryMiniAppAndMeaningPopupShareEngineAndReflectSettingsChanges() {
        assertTrue(MeaningSelectionActivity.performSharedLookup(dictionaryService, "ephemeral") is DictionaryLookupOutcome.NoDictionariesInstalled)

        val zip = createSimpleStarDictZip("Oxford Sample", "ephemeral", "Lasting for a very short time.")
        val importRes = dictionaryService.importFromFile(zip)
        assertTrue(importRes is DictionaryImportResult.Success)
        val dictId = (importRes as DictionaryImportResult.Success).installed.single().id

        val popupOutcome = MeaningSelectionActivity.performSharedLookup(dictionaryService, "  ephemeral ")
        assertTrue(popupOutcome is DictionaryLookupOutcome.Found)
        val popupEntry = (popupOutcome as DictionaryLookupOutcome.Found).entries.single()
        assertEquals("Oxford Sample", popupEntry.dictionaryName)
        assertTrue(popupEntry.definitionText.contains("short time"))

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

        dictionaryService.setDictionaryEnabled(dictId, false)
        assertTrue(MeaningSelectionActivity.performSharedLookup(dictionaryService, "ephemeral") is DictionaryLookupOutcome.AllDictionariesDisabled)

        woken.getDictionaryInstanceForTesting()?.clearSearch()
        assertEquals("", woken.getDictionaryInstanceForTesting()?.getLastSubmittedQueryForTesting())
    }

    @Test
    fun testDictionaryMiniAppOpensDictionarySettingsRoute() {
        bridge.launchDictionarySettings("miniapp_dictionary")

        assertEquals(1, context.startedIntents.size)
        val bridgeSrc = resolveProjectPath("app/src/main/java/com/example/feature/floating/FloatingMiniAppBridge.kt").readText()
        assertTrue(bridgeSrc.contains("putExtra(\"start_route\", com.example.feature.settings.ROUTE_DICTIONARY_SETTINGS)"))

        val dictWin = bridge.openMiniApp("miniapp_dictionary", "dictionary")
        val editBtn = dictWin.getTopBarNoteEditButtonForTesting()
        assertNotNull("Dictionary top bar must expose Edit button", editBtn)
        val windowSrc = resolveProjectPath("app/src/main/java/com/example/feature/floating/FloatingMiniAppWindow.kt").readText()
        assertTrue(windowSrc.contains("tag = \"dictionary_topbar_edit_button\""))
        assertTrue(windowSrc.contains("launchDictionarySettings(instanceId)"))
        assertFalse("Translation topbar Edit button must be removed from active app", windowSrc.contains("translation_topbar_edit_button"))
    }

    @Test
    fun testDictionarySettingsAndDeferralCleanupAreConsistentWithoutMlKit() {
        val dictSettingsSrc = resolveProjectPath("app/src/main/java/com/example/feature/settings/DictionarySettingsScreen.kt").readText()
        assertTrue(dictSettingsSrc.contains("settings_section_dictionary"))
        assertTrue(dictSettingsSrc.contains("DictionaryService.getInstance"))
        assertTrue(dictSettingsSrc.contains("ActivityResultContracts.OpenDocument"))
        assertTrue(dictSettingsSrc.contains("DeferredTranslationNoticeScreen"))
        assertFalse(dictSettingsSrc.contains("com.google.mlkit"))
        assertFalse(dictSettingsSrc.contains("settings_section_translation"))
        assertFalse(dictSettingsSrc.contains("translation_wifi_only_switch"))
        assertFalse(dictSettingsSrc.contains("translation_browse_models_button"))
        assertEquals("dictionary_settings", ROUTE_DICTIONARY_SETTINGS)
        assertEquals("dictionary_translation_settings", ROUTE_DICTIONARY_TRANSLATION_SETTINGS)
        assertEquals("translation_settings", ROUTE_LEGACY_TRANSLATION_SETTINGS)

        val settingsActivitySrc = resolveProjectPath("app/src/main/java/com/example/SettingsActivity.kt").readText()
        assertTrue(settingsActivitySrc.contains("ROUTE_DICTIONARY_SETTINGS"))
        assertTrue(settingsActivitySrc.contains("ROUTE_DICTIONARY_TRANSLATION_SETTINGS"))
        assertTrue(settingsActivitySrc.contains("ROUTE_LEGACY_TRANSLATION_SETTINGS"))
        assertTrue(settingsActivitySrc.contains("DictionarySettingsScreen"))
        assertTrue(settingsActivitySrc.contains("DeferredTranslationNoticeScreen"))
        assertTrue(settingsActivitySrc.contains("Text(\"Dictionary Settings\")"))

        val addElementSrc = resolveProjectPath("app/src/main/java/com/example/feature/settings/AddElementActivity.kt").readText()
        assertTrue(addElementSrc.contains("\"Floating Trigger\""))
        assertTrue(addElementSrc.contains("system:dictionary_floating"))
        assertFalse("AddElementActivity must not expose Translation entry", addElementSrc.contains("system:translation_floating"))

        val sidebarItemSrc = resolveProjectPath("app/src/main/java/com/example/feature/sidebar/SidebarItem.kt").readText()
        assertTrue(sidebarItemSrc.contains("dictionary_floating"))
        assertFalse("SidebarItem must not expose translation_floating", sidebarItemSrc.contains("translation_floating"))

        // Action dispatcher rejects legacy translation_floating and FloatingTriggerCompat blocks translation targets
        assertFalse(
            ElementActionDispatcher.execute(
                context,
                SidebarItem.SystemAction("translation_floating", "Translation (Floating)", 0)
            )
        )
        assertTrue(FloatingTriggerCompat.validateTarget(context, "system:dictionary_floating") is FloatingTriggerTargetValidation.ValidStandalone)
        assertTrue(FloatingTriggerCompat.validateTarget(context, "page_window:dictionary") is FloatingTriggerTargetValidation.ValidStandalone)
        assertTrue(FloatingTriggerCompat.validateTarget(context, "system:translation_floating") is FloatingTriggerTargetValidation.Unsupported)
        assertTrue(FloatingTriggerCompat.validateTarget(context, "page_window:translation") is FloatingTriggerTargetValidation.Unsupported)

        // Legacy persisted "translation" snapshots on disk must be safely skipped without opening Heavy
        store.saveSnapshot(
            MiniAppRecoverySnapshot(
                instanceId = "miniapp_translation",
                appType = "translation",
                state = MiniAppState.ACTIVE,
                windowBounds = WindowBounds(100, 100, 500, 600)
            )
        )
        val restored = bridge.restoreActiveMiniApps()
        assertTrue("Stale deferred translation snapshot must not be restored", restored.none { it.appType == "translation" })

        // Verify zero ML Kit dependencies or active imports and confirm deferred handoff exists
        val gradleSrc = resolveProjectPath("app/build.gradle.kts").readText()
        assertFalse("app/build.gradle.kts must not include ML Kit", gradleSrc.contains("com.google.mlkit"))

        val mainSrcDir = resolveProjectPath("app/src/main/java")
        val mlkitFiles = mainSrcDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter {
            val txt = it.readText()
            txt.contains("com.google.mlkit") || txt.contains("TranslationService") || txt.contains("TranslationMiniAppInstance")
        }.toList()
        assertTrue("Expected zero active ML Kit or Translation service references in app/src/main/java, found: $mlkitFiles", mlkitFiles.isEmpty())

        assertTrue(resolveProjectPath("deferred/vian-world/translation/README.md").isFile)
    }
}
