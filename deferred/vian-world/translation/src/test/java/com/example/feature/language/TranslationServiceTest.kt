package com.example.feature.language

import android.content.Context
import android.content.ContextWrapper
import com.example.feature.language.translation.ModelDownloadState
import com.example.feature.language.translation.TranslationEngineBackend
import com.example.feature.language.translation.TranslationOutcome
import com.example.feature.language.translation.TranslationService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

class TranslationServiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class TestContext(private val baseDir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example"
        override fun getFilesDir(): File = baseDir
    }

    internal class FakeTranslationEngineBackend(
        private val supportedCodes: List<String> = FULL_MLKIT_SAMPLE_CODES
    ) : TranslationEngineBackend {

        val downloaded = linkedSetOf<String>()
        var networkConnected: Boolean = true
        var wifiConnected: Boolean = true
        var failDownloadFor: MutableMap<String, String> = mutableMapOf()
        var failTranslateMessage: String? = null
        var detectedLanguageTagProvider: (String) -> Result<String?> = { text ->
            when {
                text.contains("Hola", ignoreCase = true) -> Result.success("es")
                text.contains("Bonjour", ignoreCase = true) -> Result.success("fr")
                text.contains("Hello", ignoreCase = true) -> Result.success("en")
                text.contains("???") -> Result.success(null)
                text.contains("Latin") -> Result.success("la") // Unsupported by ML Kit Translate
                else -> Result.success("es")
            }
        }
        var downloadGate: CompletableDeferred<Unit>? = null
        var downloadCalls: Int = 0
        var translateCalls: Int = 0

        override fun getSupportedLanguageCodes(): List<String> = supportedCodes

        override fun getDisplayName(languageCode: String): String {
            val loc = Locale.forLanguageTag(languageCode)
            val disp = loc.getDisplayLanguage(Locale.ENGLISH)
            return if (disp.isNullOrBlank() || disp.equals(languageCode, true)) {
                languageCode.uppercase()
            } else {
                disp.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
            }
        }

        override fun normalizeLanguageTag(bcp47Tag: String): String? {
            val base = bcp47Tag.trim().lowercase().substringBefore('-').substringBefore('_')
            return supportedCodes.firstOrNull { it.equals(base, ignoreCase = true) }
        }

        override fun isNetworkConnected(): Boolean = networkConnected
        override fun isWifiConnected(): Boolean = wifiConnected

        override suspend fun getDownloadedLanguageCodes(): Result<Set<String>> {
            return Result.success(downloaded.toSet())
        }

        override suspend fun downloadModel(languageCode: String, requireWifi: Boolean): Result<Unit> {
            downloadCalls++
            downloadGate?.await()
            val err = failDownloadFor[languageCode]
            if (err != null) return Result.failure(IllegalStateException(err))
            downloaded.add(languageCode)
            return Result.success(Unit)
        }

        override suspend fun deleteModel(languageCode: String): Result<Unit> {
            downloaded.remove(languageCode)
            return Result.success(Unit)
        }

        override suspend fun identifyLanguageTag(text: String): Result<String?> {
            return detectedLanguageTagProvider(text)
        }

        override suspend fun translate(
            text: String,
            sourceLanguageCode: String,
            targetLanguageCode: String
        ): Result<String> {
            translateCalls++
            failTranslateMessage?.let { return Result.failure(IllegalStateException(it)) }
            if (sourceLanguageCode !in downloaded || targetLanguageCode !in downloaded) {
                return Result.failure(IllegalStateException("Missing model on device"))
            }
            val map = mapOf(
                Triple("Hola mundo", "es", "en") to "Hello world",
                Triple("Bonjour", "fr", "en") to "Good morning",
                Triple("Good morning", "en", "es") to "Buenos días"
            )
            val output = map[Triple(text, sourceLanguageCode, targetLanguageCode)]
                ?: "[$sourceLanguageCode->$targetLanguageCode] $text"
            return Result.success(output)
        }

        companion object {
            // Mirrors Google ML Kit TranslateLanguage.getAllLanguages() (59 languages)
            val FULL_MLKIT_SAMPLE_CODES = listOf(
                "af", "ar", "be", "bg", "bn", "ca", "cs", "cy", "da", "de",
                "el", "en", "eo", "es", "et", "fa", "fi", "fr", "ga", "gl",
                "gu", "he", "hi", "hr", "ht", "hu", "id", "is", "it", "ja",
                "ka", "kn", "ko", "lt", "lv", "mk", "mr", "ms", "mt", "nl",
                "no", "pl", "pt", "ro", "ru", "sk", "sl", "sq", "sv", "sw",
                "ta", "te", "th", "tl", "tr", "uk", "ur", "vi", "zh"
            )
        }
    }

    private lateinit var prefsFile: File
    private lateinit var context: TestContext
    private lateinit var backend: FakeTranslationEngineBackend
    private lateinit var service: TranslationService

    @Before
    fun setUp() {
        TranslationService.resetForTesting()
        val dir = tempFolder.newFolder("trans_test")
        prefsFile = File(dir, "prefs.json")
        context = TestContext(dir)
        backend = FakeTranslationEngineBackend()
        service = TranslationService.createForTesting(context, prefsFile, backend)
    }

    @After
    fun tearDown() {
        TranslationService.resetForTesting()
    }

    @Test
    fun testFullLanguageEnumerationAndSharedConfigPersistenceAcrossRestart() {
        val targets = service.getSupportedTargetLanguages()
        val sources = service.getSupportedSourceLanguages()
        assertEquals(59, targets.size)
        assertEquals(60, sources.size)
        assertTrue(sources.first().isAutoDetect)
        assertEquals(TranslationService.CODE_AUTO_DETECT, sources.first().code)

        // Default config: Auto-detect -> English, Wi-Fi-only = true
        val initial = service.getConfig()
        assertEquals(TranslationService.CODE_AUTO_DETECT, initial.sourceLanguageCode)
        assertEquals("en", initial.targetLanguageCode)
        assertTrue(initial.wifiOnlyDownloads)

        // Update and verify persistence across simulated restart
        service.updateConfig(sourceLanguageCode = "es", targetLanguageCode = "ja", wifiOnlyDownloads = false)
        TranslationService.resetForTesting()
        val restarted = TranslationService.createForTesting(context, prefsFile, backend)
        val loaded = restarted.getConfig()
        assertEquals("es", loaded.sourceLanguageCode)
        assertEquals("ja", loaded.targetLanguageCode)
        assertFalse(loaded.wifiOnlyDownloads)
    }

    @Test
    fun testAutoDetectMissingModelsDownloadAndRealOnDeviceTranslation() = runBlocking {
        // With no models downloaded, translating Spanish -> English reports MissingModels without calling translate
        val missingOutcome = service.translate("Hola mundo")
        assertTrue(missingOutcome is TranslationOutcome.MissingModels)
        val missing = missingOutcome as TranslationOutcome.MissingModels
        assertTrue(missing.wasAutoDetected)
        assertEquals("es", missing.resolvedSourceCode)
        assertEquals("en", missing.targetCode)
        assertEquals(listOf("es", "en"), missing.missingLanguageCodes)
        assertEquals("Must not call backend.translate when models are missing", 0, backend.translateCalls)

        // Download both required models
        val dlRes = service.downloadMissingModels(missing.missingLanguageCodes)
        assertTrue(dlRes.isSuccess)
        assertTrue("es" in service.downloadedLanguages.value)
        assertTrue("en" in service.downloadedLanguages.value)

        // Now real translation succeeds
        val okOutcome = service.translate("Hola mundo")
        assertTrue(okOutcome is TranslationOutcome.Success)
        val ok = okOutcome as TranslationOutcome.Success
        assertEquals("Hello world", ok.translatedText)
        assertEquals("es", ok.resolvedSourceCode)
        assertEquals("en", ok.targetCode)
        assertTrue(ok.wasAutoDetected)
    }

    @Test
    fun testAutoDetectUndeterminedUnsupportedAndSameLanguageStates() = runBlocking {
        backend.downloaded.addAll(listOf("en", "es"))

        val undetermined = service.translate("???")
        assertTrue(undetermined is TranslationOutcome.UndeterminedLanguage)

        val unsupported = service.translate("Latin phrase")
        assertTrue(unsupported is TranslationOutcome.UnsupportedLanguage)
        assertEquals("la", (unsupported as TranslationOutcome.UnsupportedLanguage).detectedLanguageTag)

        val same = service.translate("Hello there")
        assertTrue(same is TranslationOutcome.SameLanguage)
        assertEquals("en", (same as TranslationOutcome.SameLanguage).languageCode)
    }

    @Test
    fun testWifiOnlyEnforcementMobileOptInOfflineErrorAndDuplicateDownloadPrevention() = runBlocking {
        // 1. Offline rejection
        backend.networkConnected = false
        backend.wifiConnected = false
        val offlineRes = service.downloadModel("fr")
        assertTrue(offlineRes.isFailure)
        assertTrue(service.modelStates.value["fr"] is ModelDownloadState.Failed)

        // 2. Mobile data blocked while wifiOnlyDownloads is ON (default)
        backend.networkConnected = true
        backend.wifiConnected = false
        val wifiBlocked = service.downloadModel("fr")
        assertTrue(wifiBlocked.isFailure)
        assertTrue(wifiBlocked.exceptionOrNull()?.message?.contains("Wi-Fi", ignoreCase = true) == true)

        // 3. Opt into mobile data downloads -> succeeds
        service.updateConfig(wifiOnlyDownloads = false)
        val mobileAllowed = service.downloadModel("fr")
        assertTrue(mobileAllowed.isSuccess)
        assertTrue("fr" in service.downloadedLanguages.value)

        // 4. Duplicate concurrent download prevention
        val gate = CompletableDeferred<Unit>()
        backend.downloadGate = gate
        backend.wifiConnected = true
        val first = async { service.downloadModel("de") }
        // Yield until first enters downloadModel and sets activeDownloads
        while (service.modelStates.value["de"] !is ModelDownloadState.Downloading) {
            kotlinx.coroutines.yield()
        }
        val duplicate = service.downloadModel("de")
        assertTrue("Duplicate concurrent download must be rejected", duplicate.isFailure)
        gate.complete(Unit)
        assertTrue(first.await().isSuccess)
        backend.downloadGate = null
    }

    @Test
    fun testModelDeletionAndTranslationFailureNeverFabricateOutput() = runBlocking {
        backend.downloaded.addAll(listOf("en", "fr"))
        service.refreshDownloadedModels()
        assertTrue("fr" in service.downloadedLanguages.value)

        // Delete French model
        assertTrue(service.deleteModel("fr").isSuccess)
        assertFalse("fr" in service.downloadedLanguages.value)
        assertEquals(ModelDownloadState.NotDownloaded, service.modelStates.value["fr"])

        // Re-add French and simulate engine failure -> returns Error, never fake success
        backend.downloaded.add("fr")
        backend.failTranslateMessage = "Corrupt model weights"
        val failed = service.translate("Bonjour", overrideSourceCode = "fr", overrideTargetCode = "en")
        assertTrue(failed is TranslationOutcome.Error)
        assertEquals("Corrupt model weights", (failed as TranslationOutcome.Error).message)
    }
}
