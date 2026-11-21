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
import com.example.feature.language.translation.TranslationOutcome
import com.example.feature.language.translation.TranslationService
import com.example.feature.selection.TranslateSelectionActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import java.lang.reflect.Proxy

/**
 * Preserved integration tests for floating Translation mini-app and Translate text-selection popup,
 * moved alongside Translation sources for future Vian World integration.
 */
class TranslationUiAndSelectionTest {

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
    private lateinit var translationBackend: TranslationServiceTest.FakeTranslationEngineBackend
    private lateinit var translationService: TranslationService

    @Before
    fun setUp() {
        val prefs = FakeSharedPreferences()
        val filesDir = tempFolder.newFolder("trans_ui_test")
        context = MockTestContext(prefs, filesDir)
        DurableMiniAppStore.resetForTesting()
        FloatingMiniAppBridge.resetForTesting()
        TranslationService.resetForTesting()

        translationBackend = TranslationServiceTest.FakeTranslationEngineBackend()
        translationService = TranslationService.createForTesting(
            context,
            File(filesDir, "mlkit_translation/prefs.json"),
            translationBackend
        )
        val wm = FloatingWindowManager.getInstance(context)
        wm.getAllWindows().map { it.windowId }.forEach { wm.unregisterWindow(it) }
        bridge = FloatingMiniAppBridge.getInstance(context)
    }

    @After
    fun tearDown() {
        FloatingMiniAppBridge.resetForTesting()
        val wm = FloatingWindowManager.getInstance(context)
        wm.getAllWindows().map { it.windowId }.forEach { wm.unregisterWindow(it) }
        DurableMiniAppStore.resetForTesting()
        TranslationService.resetForTesting()
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

        val cfg = translationService.getConfig()
        assertEquals("ja", cfg.sourceLanguageCode)
        assertEquals("es", cfg.targetLanguageCode)

        val popupMissing = TranslateSelectionActivity.performSharedTranslation(translationService, "Good morning")
        assertTrue(popupMissing is TranslationOutcome.MissingModels)

        instance.submitTranslation("Good morning")
        assertNull("Must never fabricate fake translation output", instance.getTranslatedOutputForTesting())
        assertFalse("Copy must return false when no real translation exists", instance.copyOutputToClipboard(context))

        translationService.updateConfig(sourceLanguageCode = "auto", targetLanguageCode = "en")
        translationBackend.downloaded.addAll(listOf("es", "en"))
        val popupOk = TranslateSelectionActivity.performSharedTranslation(translationService, "Hola mundo")
        assertTrue(popupOk is TranslationOutcome.Success)
        assertEquals("Hello world", (popupOk as TranslationOutcome.Success).translatedText)

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
}
