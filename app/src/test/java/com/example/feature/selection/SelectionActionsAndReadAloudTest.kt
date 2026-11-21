package com.example.feature.selection

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.speech.tts.TextToSpeech
import com.example.core.FakeSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

class SelectionActionsAndReadAloudTest {

    private class MockTestContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example"
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    private class FakeTtsEngine(
        var initResult: Int = TextToSpeech.SUCCESS,
        var langResultProvider: (Locale) -> Int = { TextToSpeech.LANG_AVAILABLE },
        var speakResult: Int = TextToSpeech.SUCCESS,
        var autoInit: Boolean = false
    ) : TtsEngineAdapter {
        private var pendingInitCallback: ((Int) -> Unit)? = null
        var onStartCb: ((String) -> Unit)? = null
        var onDoneCb: ((String) -> Unit)? = null
        var onErrorCb: ((String) -> Unit)? = null

        val spokenTexts = mutableListOf<String>()
        val appliedRates = mutableListOf<Float>()
        val requestedLocales = mutableListOf<Locale>()
        var stopCalls = 0
        var shutdownCalls = 0

        override fun initialize(onInit: (status: Int) -> Unit) {
            if (autoInit) {
                onInit(initResult)
            } else {
                pendingInitCallback = onInit
            }
        }

        fun completeInit(status: Int = initResult) {
            pendingInitCallback?.invoke(status)
            pendingInitCallback = null
        }

        override fun setLanguage(locale: Locale): Int {
            requestedLocales.add(locale)
            return langResultProvider(locale)
        }

        override fun setSpeechRate(rate: Float): Int {
            appliedRates.add(rate)
            return TextToSpeech.SUCCESS
        }

        override fun setUtteranceListener(
            onStart: (String) -> Unit,
            onDone: (String) -> Unit,
            onError: (String) -> Unit
        ) {
            onStartCb = onStart
            onDoneCb = onDone
            onErrorCb = onError
        }

        override fun speak(text: String, utteranceId: String): Int {
            spokenTexts.add(text)
            return speakResult
        }

        override fun stop(): Int {
            stopCalls++
            return TextToSpeech.SUCCESS
        }

        override fun shutdown() {
            shutdownCalls++
        }
    }

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var context: MockTestContext

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        context = MockTestContext(fakePrefs)
        ReadAloudSessionCoordinator.resetForTesting()
    }

    @After
    fun tearDown() {
        ReadAloudSessionCoordinator.resetForTesting()
    }

    @Test
    fun testManifestRegistersOnlyMeaningProcessTextActivity() {
        val manifestFile = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        ).first { it.exists() }
        val xml = manifestFile.readText()

        assertFalse(
            "ReadAloudSelectionActivity must NOT be registered for ACTION_PROCESS_TEXT",
            xml.contains(".feature.selection.ReadAloudSelectionActivity")
        )
        assertFalse(
            "Custom 'Read aloud' selection label must NOT be registered in manifest",
            xml.contains("android:label=\"Read aloud\"")
        )
        assertFalse(
            "TranslateSelectionActivity must NOT be registered after deferral to Vian World",
            xml.contains(".feature.selection.TranslateSelectionActivity")
        )
        assertFalse(
            "Translate selection label must NOT be registered in manifest after deferral",
            xml.contains("android:label=\"Translate\"")
        )
        assertTrue(xml.contains(".feature.selection.MeaningSelectionActivity"))
        assertTrue(xml.contains("android:label=\"Meaning\""))

        val processTextFilterCount = Regex("android\\.intent\\.action\\.PROCESS_TEXT").findAll(xml).count()
        assertEquals("Only Meaning ACTION_PROCESS_TEXT activity must be registered", 1, processTextFilterCount)
    }

    @Test
    fun testSelectionTextNormalizationHandlesEmptyWhitespaceAndLongInputsSafely() {
        assertNull(SelectionTextHelper.extractSelectedText(null))
        assertNull(SelectionTextHelper.normalize(null))
        assertNull(SelectionTextHelper.normalize(""))
        assertNull(SelectionTextHelper.normalize("   \n\t   "))
        assertEquals("serendipity", SelectionTextHelper.normalize("  serendipity  "))

        val huge = "a".repeat(SelectionTextHelper.MAX_SELECTION_LENGTH + 500)
        assertEquals(SelectionTextHelper.MAX_SELECTION_LENGTH, SelectionTextHelper.normalize(huge)?.length)

        val longTerm = "word ".repeat(30)
        assertTrue(SelectionTextHelper.formatPrimaryTerm(longTerm).endsWith("..."))
    }

    @Test
    fun testReadAloudControllerQueuesSpeakUntilInitAndSupportsSlowerFasterControls() {
        val engine = FakeTtsEngine(autoInit = false)
        val statuses = mutableListOf<ReadAloudStatus>()
        val controller = ReadAloudController(
            context = context,
            engine = engine,
            preferredLocaleProvider = { Locale.US },
            dispatchToMain = false,
            onStatusChanged = { statuses.add(it) }
        )

        assertEquals(ReadAloudState.INITIALIZING, controller.getState())
        assertTrue(controller.speak("Hello world"))
        assertTrue("Speak must wait until TTS init completes", engine.spokenTexts.isEmpty())

        engine.completeInit(TextToSpeech.SUCCESS)
        assertEquals(ReadAloudState.SPEAKING, controller.getState())
        assertEquals(listOf("Hello world"), engine.spokenTexts)

        assertEquals(1.25f, controller.faster(), 0.001f)
        assertEquals(1.0f, controller.slower(), 0.001f)
        repeat(10) { controller.slower() }
        assertEquals(ReadAloudController.MIN_SPEECH_RATE, controller.getSpeechRate(), 0.001f)
        repeat(20) { controller.faster() }
        assertEquals(ReadAloudController.MAX_SPEECH_RATE, controller.getSpeechRate(), 0.001f)

        assertFalse(controller.speak("   "))

        controller.release()
        assertTrue(controller.isReleased())
        assertEquals(ReadAloudState.RELEASED, controller.getState())
        assertTrue(engine.stopCalls >= 1)
        assertEquals(1, engine.shutdownCalls)
        assertNull(ReadAloudSessionCoordinator.getActiveForTesting())
    }

    @Test
    fun testReadAloudControllerHandlesInitFailureUnsupportedLanguageAndSpeakErrorSafely() {
        val failingEngine = FakeTtsEngine(initResult = TextToSpeech.ERROR, autoInit = true)
        val failingController = ReadAloudController(
            context = context,
            engine = failingEngine,
            dispatchToMain = false
        )
        assertEquals(ReadAloudState.ERROR, failingController.getState())
        assertFalse(failingController.speak("Test"))
        failingController.release()

        val unsupportedEngine = FakeTtsEngine(
            initResult = TextToSpeech.SUCCESS,
            langResultProvider = { TextToSpeech.LANG_MISSING_DATA },
            autoInit = true
        )
        val unsupportedController = ReadAloudController(
            context = context,
            engine = unsupportedEngine,
            preferredLocaleProvider = { Locale.JAPAN },
            dispatchToMain = false
        )
        assertEquals(ReadAloudState.UNSUPPORTED_LANGUAGE, unsupportedController.getState())
        assertEquals(listOf(Locale.JAPAN, Locale.US), unsupportedEngine.requestedLocales)
        assertFalse(unsupportedController.speak("Test"))
        unsupportedController.release()

        val speakErrorEngine = FakeTtsEngine(
            initResult = TextToSpeech.SUCCESS,
            speakResult = TextToSpeech.ERROR,
            autoInit = true
        )
        val speakErrorController = ReadAloudController(
            context = context,
            engine = speakErrorEngine,
            preferredLocaleProvider = { Locale.US },
            dispatchToMain = false
        )
        assertFalse(speakErrorController.speak("Fail me"))
        assertEquals(ReadAloudState.ERROR, speakErrorController.getState())
        speakErrorController.release()
    }

    @Test
    fun testSimultaneousReadAloudSessionsPreemptPreviousSessionCleanly() {
        val engineA = FakeTtsEngine(autoInit = true)
        val engineB = FakeTtsEngine(autoInit = true)

        val controllerA = ReadAloudController(context, engine = engineA, dispatchToMain = false)
        val controllerB = ReadAloudController(context, engine = engineB, dispatchToMain = false)

        assertTrue(controllerA.speak("First popup utterance"))
        assertEquals(ReadAloudState.SPEAKING, controllerA.getState())

        assertTrue(controllerB.speak("Second popup utterance"))
        assertEquals(ReadAloudState.STOPPED, controllerA.getState())
        assertTrue("First engine must be stopped when second starts", engineA.stopCalls >= 1)
        assertEquals(ReadAloudState.SPEAKING, controllerB.getState())

        controllerA.release()
        controllerB.release()
        assertEquals(1, engineA.shutdownCalls)
        assertEquals(1, engineB.shutdownCalls)
    }

    @Test
    fun testMeaningPopupWiresSharedDictionaryServiceWithoutCustomReadAloudAndTranslateDeferred() {
        val meaningSrc = listOf(
            File("src/main/java/com/example/feature/selection/MeaningSelectionActivity.kt"),
            File("app/src/main/java/com/example/feature/selection/MeaningSelectionActivity.kt")
        ).first { it.exists() }.readText()
        assertFalse("MeaningSelectionActivity must NOT use ReadAloudController", meaningSrc.contains("ReadAloudController"))
        assertFalse("MeaningSelectionActivity must NOT contain custom Read Aloud button", meaningSrc.contains("\"Read Aloud\"") || meaningSrc.contains("TAG_READ_ALOUD"))
        assertTrue("MeaningSelectionActivity must use shared DictionaryService", meaningSrc.contains("DictionaryService.getInstance"))
        assertTrue("MeaningSelectionActivity must route to Dictionary Settings", meaningSrc.contains("openDictionarySettings"))
        assertFalse("MeaningSelectionActivity must not keep old placeholder string", meaningSrc.contains("DICTIONARY NOT CONFIGURED"))

        val activeTranslateFiles = listOf(
            File("src/main/java/com/example/feature/selection/TranslateSelectionActivity.kt"),
            File("app/src/main/java/com/example/feature/selection/TranslateSelectionActivity.kt")
        ).filter { it.exists() }
        assertTrue("TranslateSelectionActivity must be removed from active src/main", activeTranslateFiles.isEmpty())
    }
}
