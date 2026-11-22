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
        @Volatile
        var failWindowAddView: Boolean = false

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
            ) { _, method, _ ->
                if (method.name == "addView" && failWindowAddView) {
                    throw RuntimeException("Simulated WindowManager.addView overlay failure")
                }
                null
            } as WindowManager
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
        FloatingWindowManager.resetForTesting()
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
        FloatingWindowManager.resetForTesting()
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

    @Test
    fun testDictionaryOpenWithQueryReusesSingleInstanceAndSleepReleasesRuntime() {
        val zip = createSimpleStarDictZip("Oxford Sample", "ephemeral", "Lasting for a very short time.")
        assertTrue(dictionaryService.importFromFile(zip) is DictionaryImportResult.Success)

        val firstWin = bridge.openDictionaryWithQuery("ephemeral")
        val firstInstance = firstWin.getDictionaryInstanceForTesting()!!
        assertEquals("miniapp_dictionary", firstWin.instanceId)
        assertEquals("ephemeral", firstInstance.getLastSubmittedQueryForTesting())
        assertTrue(firstInstance.hasAttachedViewsForTesting())

        // Opening Full Dictionary again with another word MUST reuse the existing window (no duplicates)
        val secondWin = bridge.openDictionaryWithQuery("serendipity")
        assertTrue("Expected exact same FloatingMiniAppWindow instance to be reused", firstWin === secondWin)
        assertEquals(
            1,
            FloatingWindowManager.getInstance(context).getAllWindows()
                .count { (it as? FloatingMiniAppWindow)?.isDictionaryApp() == true }
        )
        assertEquals("serendipity", firstInstance.getLastSubmittedQueryForTesting())

        // Direct prepareForSleep() must enter SLEEPING and release runtime views/jobs/outcome buffer
        val directSaved = firstInstance.prepareForSleep()
        assertEquals(com.example.feature.floating.MiniAppLifecycleState.SLEEPING, firstInstance.lifecycleState)
        assertEquals("serendipity", directSaved[DictionaryMiniAppInstance.KEY_SUBMITTED_QUERY])
        assertFalse("Lookup job must not stay active after prepareForSleep", firstInstance.hasActiveLookupJobForTesting())
        assertFalse("Dictionary views must be released on prepareForSleep", firstInstance.hasAttachedViewsForTesting())
        assertEquals(DictionaryLookupOutcome.EmptyQuery, firstInstance.getLastOutcomeForTesting())

        // Folding/sleeping via bridge saves durable SLEEPING snapshot and tears down the live window instance cleanly
        var sleepOk = false
        bridge.sleepMiniApp("miniapp_dictionary") { sleepOk = it }
        assertTrue(sleepOk)
        assertEquals(com.example.feature.floating.MiniAppLifecycleState.DESTROYED, firstInstance.lifecycleState)
        assertFalse("Lookup job must not stay active after sleep", firstInstance.hasActiveLookupJobForTesting())
        assertFalse("Dictionary views must be released on sleep", firstInstance.hasAttachedViewsForTesting())

        val snap = store.loadSnapshot("miniapp_dictionary")
        assertNotNull(snap)
        assertEquals(MiniAppState.SLEEPING, snap!!.state)
        assertEquals("serendipity", snap.payload[DictionaryMiniAppInstance.KEY_SUBMITTED_QUERY])

        // Waking via Open Full Dictionary handoff with a new word restores a single window cleanly
        val wokenWin = bridge.openDictionaryWithQuery("ephemeral")
        assertEquals(
            1,
            FloatingWindowManager.getInstance(context).getAllWindows()
                .count { (it as? FloatingMiniAppWindow)?.isDictionaryApp() == true }
        )
        assertEquals("ephemeral", wokenWin.getDictionaryInstanceForTesting()?.getLastSubmittedQueryForTesting())
    }

    @Test
    fun testSharedLightDarkThemePaletteAndQuickMeaningPopupContract() {
        val light = LanguageMiniAppUiHelper.resolvePalette(isDark = false)
        val dark = LanguageMiniAppUiHelper.resolvePalette(isDark = true)

        assertFalse(light.isDark)
        assertTrue(dark.isDark)
        assertTrue("Light and dark surface colors must differ", light.surfaceBg != dark.surfaceBg)
        assertTrue("Light and dark text colors must differ", light.textPrimary != dark.textPrimary)
        assertTrue("Light and dark top bar colors must differ", light.topBarBg != dark.topBarBg)
        assertTrue("Light and dark Dictionary accents must differ", light.accentDictionary != dark.accentDictionary)

        // Light mode contrast adaptation must darken bright pastel Note accents while dark mode preserves them
        val pastel = 0xFFF9E2AF.toInt()
        assertEquals(pastel, LanguageMiniAppUiHelper.adaptAccentForSurface(pastel, isDark = true))
        val adaptedLight = LanguageMiniAppUiHelper.adaptAccentForSurface(pastel, isDark = false)
        assertTrue("Pastel accent must be darkened for legibility on light surfaces", adaptedLight != pastel)

        // Verify Quick Meaning popup wires Open Full Dictionary reuse and never invents ranking labels
        val popupSrc = resolveProjectPath("app/src/main/java/com/example/feature/selection/MeaningSelectionActivity.kt").readText()
        val popupCodeOnly = popupSrc.lineSequence()
            .filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")
            }
            .joinToString("\n")
        assertTrue(popupCodeOnly.contains("TAG_OPEN_FULL_DICTIONARY_BUTTON"))
        assertTrue(popupCodeOnly.contains("openDictionaryWithQuery"))
        assertTrue(popupCodeOnly.contains("buildConcisePopupMeaning"))
        assertFalse("Quick Meaning popup must never invent 'Most Common' UI labels", popupCodeOnly.contains("Most Common", ignoreCase = true))

        val dictSrc = resolveProjectPath("app/src/main/java/com/example/feature/language/DictionaryMiniAppInstance.kt").readText()
        assertFalse("Dictionary copy must not say Dictionary & Translation Settings", dictSrc.contains("Dictionary & Translation Settings"))
        assertTrue(dictSrc.contains("override fun releaseRuntime()"))
        assertTrue(dictSrc.contains("resolvePalette"))
    }

    @Test
    fun testQuickMeaningHandoffSuccessOpensDictionaryAndDismissesPopup() {
        val zip = createSimpleStarDictZip("Oxford Sample", "ephemeral", "Lasting for a very short time.")
        assertTrue(dictionaryService.importFromFile(zip) is DictionaryImportResult.Success)
        val lookupOutcome = MeaningSelectionActivity.performSharedLookup(dictionaryService, "ephemeral")
        assertTrue(lookupOutcome is DictionaryLookupOutcome.Found)

        val popup = MeaningSelectionActivity()
        popup.setStateForTesting(selected = "ephemeral", activeTerm = "ephemeral", outcome = lookupOutcome)

        var dismissed = false
        val ok = popup.attemptOpenFullDictionary(
            handoffExecutor = { term -> bridge.openDictionaryWithQueryResult(term) },
            onDismiss = { dismissed = true }
        )

        assertTrue("Successful handoff must return true", ok)
        assertTrue("Quick Meaning popup must dismiss on successful handoff", dismissed)
        assertEquals(null, popup.getLastHandoffErrorForTesting())

        val wm = FloatingWindowManager.getInstance(context)
        val dictWindow = wm.getWindow(FloatingMiniAppBridge.DEFAULT_DICTIONARY_INSTANCE_ID) as? FloatingMiniAppWindow
        assertNotNull("Full Dictionary window must be open", dictWindow)
        assertTrue(dictWindow!!.isAttached)
        assertTrue(dictWindow.isDictionaryReady())
        assertEquals("ephemeral", dictWindow.getDictionaryInstanceForTesting()?.getLastSubmittedQueryForTesting())
    }

    @Test
    fun testQuickMeaningHandoffFailureKeepsPopupOpenAndPreservesResult() {
        val zip = createSimpleStarDictZip("Oxford Sample", "ephemeral", "Lasting for a very short time.")
        assertTrue(dictionaryService.importFromFile(zip) is DictionaryImportResult.Success)
        val lookupOutcome = MeaningSelectionActivity.performSharedLookup(dictionaryService, "ephemeral")
        assertTrue(lookupOutcome is DictionaryLookupOutcome.Found)

        val popup = MeaningSelectionActivity()
        popup.setStateForTesting(selected = "ephemeral", activeTerm = "ephemeral", outcome = lookupOutcome)

        // Simulate real WindowManager.addView failure during overlay attachment
        context.failWindowAddView = true
        var dismissed = false
        val ok = popup.attemptOpenFullDictionary(
            handoffExecutor = { term -> bridge.openDictionaryWithQueryResult(term) },
            onDismiss = { dismissed = true }
        )

        assertFalse("Failed handoff must return false", ok)
        assertFalse("Quick Meaning popup MUST remain open on handoff failure", dismissed)
        assertNotNull("Clear non-destructive error message must be recorded", popup.getLastHandoffErrorForTesting())
        assertTrue(popup.getLastHandoffErrorForTesting()!!.contains("Full Dictionary"))
        assertEquals("Selected term must remain preserved", "ephemeral", popup.getActiveQueryTermForTesting())
        assertEquals("Displayed lookup result must remain preserved", lookupOutcome, popup.getLastOutcomeForTesting())

        // No broken unattached Dictionary window may linger in FloatingWindowManager
        val wm = FloatingWindowManager.getInstance(context)
        assertEquals(
            "Failed brand-new handoff must not leave a broken zombie Dictionary window",
            0,
            wm.getAllWindows().count { (it as? FloatingMiniAppWindow)?.isDictionaryApp() == true }
        )
    }

    @Test
    fun testQuickMeaningHandoffRetryAfterFailureSucceedsCleanly() {
        val zip = createSimpleStarDictZip("Oxford Sample", "ephemeral", "Lasting for a very short time.")
        assertTrue(dictionaryService.importFromFile(zip) is DictionaryImportResult.Success)
        val lookupOutcome = MeaningSelectionActivity.performSharedLookup(dictionaryService, "ephemeral")
        assertTrue(lookupOutcome is DictionaryLookupOutcome.Found)

        val popup = MeaningSelectionActivity()
        popup.setStateForTesting(selected = "ephemeral", activeTerm = "ephemeral", outcome = lookupOutcome)

        // First attempt fails (WindowManager overlay error)
        context.failWindowAddView = true
        var dismissCount = 0
        val firstOk = popup.attemptOpenFullDictionary(
            handoffExecutor = { term -> bridge.openDictionaryWithQueryResult(term) },
            onDismiss = { dismissCount++ }
        )
        assertFalse(firstOk)
        assertEquals(0, dismissCount)
        assertNotNull(popup.getLastHandoffErrorForTesting())
        assertEquals(lookupOutcome, popup.getLastOutcomeForTesting())

        // Retry after condition resolves succeeds with preserved selected term and dismisses popup
        context.failWindowAddView = false
        val retryOk = popup.attemptOpenFullDictionary(
            handoffExecutor = { term -> bridge.openDictionaryWithQueryResult(term) },
            onDismiss = { dismissCount++ }
        )
        assertTrue("Retry handoff must succeed once overlay attachment recovers", retryOk)
        assertEquals("Popup must dismiss on successful retry", 1, dismissCount)
        assertEquals("Previous handoff error must clear on successful retry", null, popup.getLastHandoffErrorForTesting())

        val wm = FloatingWindowManager.getInstance(context)
        val dictWindows = wm.getAllWindows().filterIsInstance<FloatingMiniAppWindow>().filter { it.isDictionaryApp() }
        assertEquals("Retry must result in exactly one Dictionary window", 1, dictWindows.size)
        assertEquals("ephemeral", dictWindows.first().getDictionaryInstanceForTesting()?.getLastSubmittedQueryForTesting())
    }

    @Test
    fun testRepeatedQuickMeaningHandoffsReuseSingleDictionaryWithoutDuplicates() {
        val zip = createSimpleStarDictZip("Oxford Sample", "ephemeral", "Lasting for a very short time.")
        assertTrue(dictionaryService.importFromFile(zip) is DictionaryImportResult.Success)

        val terms = listOf("ephemeral", "serendipity", "ubiquitous")
        var firstWindow: FloatingMiniAppWindow? = null

        for (term in terms) {
            val popup = MeaningSelectionActivity()
            popup.setStateForTesting(
                selected = term,
                activeTerm = term,
                outcome = MeaningSelectionActivity.performSharedLookup(dictionaryService, term)
            )
            var dismissed = false
            var openedWindow: FloatingMiniAppWindow? = null
            val ok = popup.attemptOpenFullDictionary(
                handoffExecutor = { q ->
                    val res = bridge.openDictionaryWithQueryResult(q)
                    if (res is FloatingMiniAppBridge.DictionaryHandoffResult.Success) {
                        openedWindow = res.window
                    }
                    res
                },
                onDismiss = { dismissed = true }
            )
            assertTrue("Handoff for '$term' must succeed", ok)
            assertTrue("Popup must dismiss for '$term'", dismissed)
            assertNotNull(openedWindow)
            if (firstWindow == null) {
                firstWindow = openedWindow
            } else {
                assertTrue("Repeated handoffs must reuse the exact same single Dictionary window", firstWindow === openedWindow)
            }
        }

        val wm = FloatingWindowManager.getInstance(context)
        val dictWindows = wm.getAllWindows().filterIsInstance<FloatingMiniAppWindow>().filter { it.isDictionaryApp() }
        assertEquals("Repeated handoffs must never create duplicate Dictionary windows", 1, dictWindows.size)
        assertEquals("ubiquitous", dictWindows.first().getDictionaryInstanceForTesting()?.getLastSubmittedQueryForTesting())
    }
}
