package com.example.feature.selection

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.core.LogKeeper
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

enum class ReadAloudState {
    INITIALIZING,
    READY,
    SPEAKING,
    STOPPED,
    UNSUPPORTED_LANGUAGE,
    ERROR,
    RELEASED
}

data class ReadAloudStatus(
    val state: ReadAloudState,
    val speechRate: Float,
    val message: String
)

/**
 * Thin adapter over Android's built-in [TextToSpeech] engine so [ReadAloudController]
 * uses real Android TTS on device and can be verified deterministically in JVM unit tests.
 */
interface TtsEngineAdapter {
    fun initialize(onInit: (status: Int) -> Unit)
    fun setLanguage(locale: Locale): Int
    fun setSpeechRate(rate: Float): Int
    fun setUtteranceListener(onStart: (String) -> Unit, onDone: (String) -> Unit, onError: (String) -> Unit)
    fun speak(text: String, utteranceId: String): Int
    fun stop(): Int
    fun shutdown()
}

class AndroidTtsEngineAdapter(private val context: Context) : TtsEngineAdapter {
    private var tts: TextToSpeech? = null

    override fun initialize(onInit: (status: Int) -> Unit) {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                onInit(status)
            }
        } catch (_: Throwable) {
            tts = null
            onInit(TextToSpeech.ERROR)
        }
    }

    override fun setLanguage(locale: Locale): Int {
        return try {
            tts?.setLanguage(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
        } catch (_: Throwable) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun setSpeechRate(rate: Float): Int {
        return try {
            tts?.setSpeechRate(rate) ?: TextToSpeech.ERROR
        } catch (_: Throwable) {
            TextToSpeech.ERROR
        }
    }

    override fun setUtteranceListener(
        onStart: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    onStart(utteranceId.orEmpty())
                }

                override fun onDone(utteranceId: String?) {
                    onDone(utteranceId.orEmpty())
                }

                @Deprecated("Deprecated in Android API")
                override fun onError(utteranceId: String?) {
                    onError(utteranceId.orEmpty())
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    onError(utteranceId.orEmpty())
                }
            })
        } catch (_: Throwable) {}
    }

    override fun speak(text: String, utteranceId: String): Int {
        return try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) ?: TextToSpeech.ERROR
        } catch (_: Throwable) {
            TextToSpeech.ERROR
        }
    }

    override fun stop(): Int {
        return try {
            tts?.stop() ?: TextToSpeech.SUCCESS
        } catch (_: Throwable) {
            TextToSpeech.ERROR
        }
    }

    override fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Throwable) {}
        tts = null
    }
}

/**
 * Coordinates active speech sessions across selection popups so a new Read Aloud session
 * immediately stops any previously active popup session instead of speaking simultaneously.
 */
object ReadAloudSessionCoordinator {
    private val lock = Any()
    private var activeController: ReadAloudController? = null

    fun claimActive(controller: ReadAloudController) {
        val previous: ReadAloudController?
        synchronized(lock) {
            previous = if (activeController != null && activeController !== controller) {
                activeController
            } else {
                null
            }
            activeController = controller
        }
        previous?.stopFromCoordinator()
    }

    fun unregister(controller: ReadAloudController) {
        synchronized(lock) {
            if (activeController === controller) {
                activeController = null
            }
        }
    }

    internal fun getActiveForTesting(): ReadAloudController? = synchronized(lock) { activeController }

    internal fun resetForTesting() {
        synchronized(lock) {
            activeController = null
        }
    }
}

/**
 * Reusable built-in Android [TextToSpeech] controller for text-selection Read Aloud popups.
 *
 * Handles:
 * - Engine initialization & pending-utterance queueing while initializing.
 * - Unsupported / missing language detection with safe Locale.US fallback.
 * - Slower / faster speech rate controls clamped to [MIN_SPEECH_RATE, MAX_SPEECH_RATE].
 * - Preemption of conflicting simultaneous speech sessions across popups.
 * - Clean lifecycle shutdown (`release()`) preventing leaked TTS connections.
 */
class ReadAloudController(
    private val context: Context,
    private val engine: TtsEngineAdapter = AndroidTtsEngineAdapter(context),
    private val preferredLocaleProvider: () -> Locale = { Locale.getDefault() },
    private val dispatchToMain: Boolean = true,
    private val onStatusChanged: (ReadAloudStatus) -> Unit = {}
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler: Handler? = try {
        Looper.getMainLooper()?.let { Handler(it) }
    } catch (_: Throwable) {
        null
    }

    private var state: ReadAloudState = ReadAloudState.INITIALIZING
    private var speechRate: Float = loadSavedSpeechRate()
    private var lastRequestedText: String? = null
    private var pendingSpeakText: String? = null
    private var activeUtteranceId: String? = null
    private var lastMessage: String = "Preparing speech engine..."
    private var isReleased = false

    init {
        emitStatus(ReadAloudState.INITIALIZING, "Preparing speech engine...")
        engine.initialize { initStatus ->
            handleInitComplete(initStatus)
        }
    }

    private fun handleInitComplete(initStatus: Int) {
        if (isReleased) {
            engine.shutdown()
            return
        }
        if (initStatus != TextToSpeech.SUCCESS) {
            pendingSpeakText = null
            LogKeeper.log(context, TAG, "TTS_INIT_FAILED status=$initStatus")
            emitStatus(
                ReadAloudState.ERROR,
                "Text-to-speech engine is unavailable on this device."
            )
            return
        }

        val primaryLocale = preferredLocaleProvider()
        var langResult = engine.setLanguage(primaryLocale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (primaryLocale != Locale.US) {
                langResult = engine.setLanguage(Locale.US)
            }
        }

        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            pendingSpeakText = null
            LogKeeper.log(context, TAG, "TTS_LANGUAGE_UNSUPPORTED locale=$primaryLocale result=$langResult")
            emitStatus(
                ReadAloudState.UNSUPPORTED_LANGUAGE,
                "Voice data for this language is missing or unsupported."
            )
            return
        }

        engine.setSpeechRate(speechRate)
        engine.setUtteranceListener(
            onStart = { id ->
                if (!isReleased && id == activeUtteranceId) {
                    emitStatus(ReadAloudState.SPEAKING, "Reading aloud (${formatSpeed(speechRate)})")
                }
            },
            onDone = { id ->
                if (!isReleased && id == activeUtteranceId) {
                    ReadAloudSessionCoordinator.unregister(this)
                    emitStatus(ReadAloudState.STOPPED, "Finished reading")
                }
            },
            onError = { id ->
                if (!isReleased && id == activeUtteranceId) {
                    ReadAloudSessionCoordinator.unregister(this)
                    emitStatus(ReadAloudState.ERROR, "Unable to read text aloud.")
                }
            }
        )

        emitStatus(ReadAloudState.READY, "Ready (${formatSpeed(speechRate)})")
        val queued = pendingSpeakText
        pendingSpeakText = null
        if (!queued.isNullOrBlank()) {
            speakInternal(queued)
        }
    }

    fun speak(rawText: String?): Boolean {
        if (isReleased) return false
        val normalized = SelectionTextHelper.normalize(rawText)
        if (normalized == null) {
            emitStatus(
                if (state == ReadAloudState.INITIALIZING) ReadAloudState.INITIALIZING else ReadAloudState.STOPPED,
                "No text available to read aloud."
            )
            return false
        }
        lastRequestedText = normalized

        return when (state) {
            ReadAloudState.INITIALIZING -> {
                pendingSpeakText = normalized
                emitStatus(ReadAloudState.INITIALIZING, "Starting speech...")
                true
            }
            ReadAloudState.UNSUPPORTED_LANGUAGE, ReadAloudState.ERROR, ReadAloudState.RELEASED -> {
                false
            }
            ReadAloudState.READY, ReadAloudState.SPEAKING, ReadAloudState.STOPPED -> {
                speakInternal(normalized)
            }
        }
    }

    private fun speakInternal(text: String): Boolean {
        ReadAloudSessionCoordinator.claimActive(this)
        engine.setSpeechRate(speechRate)
        val utteranceId = "ra_${UUID.randomUUID()}"
        activeUtteranceId = utteranceId
        val result = engine.speak(text, utteranceId)
        return if (result == TextToSpeech.SUCCESS) {
            emitStatus(ReadAloudState.SPEAKING, "Reading aloud (${formatSpeed(speechRate)})")
            true
        } else {
            ReadAloudSessionCoordinator.unregister(this)
            LogKeeper.log(context, TAG, "TTS_SPEAK_ERROR result=$result")
            emitStatus(ReadAloudState.ERROR, "Speech playback failed.")
            false
        }
    }

    fun stop() {
        if (isReleased) return
        pendingSpeakText = null
        activeUtteranceId = null
        ReadAloudSessionCoordinator.unregister(this)
        engine.stop()
        if (state == ReadAloudState.SPEAKING || state == ReadAloudState.READY) {
            emitStatus(ReadAloudState.STOPPED, "Stopped")
        }
    }

    internal fun stopFromCoordinator() {
        if (isReleased) return
        pendingSpeakText = null
        activeUtteranceId = null
        engine.stop()
        if (state == ReadAloudState.SPEAKING) {
            emitStatus(ReadAloudState.STOPPED, "Stopped for another Read Aloud session")
        }
    }

    fun togglePlayStop(fallbackText: String? = null) {
        if (isReleased) return
        if (state == ReadAloudState.SPEAKING) {
            stop()
        } else {
            speak(fallbackText ?: lastRequestedText)
        }
    }

    fun slower(): Float = setSpeechRate(speechRate - SPEECH_RATE_STEP)

    fun faster(): Float = setSpeechRate(speechRate + SPEECH_RATE_STEP)

    fun setSpeechRate(requestedRate: Float): Float {
        val clamped = clampRate(requestedRate)
        speechRate = clamped
        prefs.edit().putFloat(KEY_SPEECH_RATE, clamped).apply()

        if (!isReleased && state != ReadAloudState.INITIALIZING &&
            state != ReadAloudState.ERROR && state != ReadAloudState.UNSUPPORTED_LANGUAGE
        ) {
            engine.setSpeechRate(clamped)
            if (state == ReadAloudState.SPEAKING) {
                val activeText = lastRequestedText
                if (!activeText.isNullOrBlank()) {
                    // Restart current utterance at updated speech rate so change is immediately audible
                    speakInternal(activeText)
                }
            } else {
                emitStatus(state, "Speed: ${formatSpeed(clamped)}")
            }
        } else if (!isReleased) {
            emitStatus(state, lastMessage)
        }
        return clamped
    }

    fun getSpeechRate(): Float = speechRate

    fun getState(): ReadAloudState = state

    fun getStatus(): ReadAloudStatus = ReadAloudStatus(state, speechRate, lastMessage)

    fun isReleased(): Boolean = isReleased

    fun release() {
        if (isReleased) return
        isReleased = true
        pendingSpeakText = null
        activeUtteranceId = null
        ReadAloudSessionCoordinator.unregister(this)
        engine.stop()
        engine.shutdown()
        state = ReadAloudState.RELEASED
        lastMessage = "Released"
    }

    private fun loadSavedSpeechRate(): Float {
        val saved = prefs.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE)
        return clampRate(saved)
    }

    private fun emitStatus(newState: ReadAloudState, message: String) {
        state = newState
        lastMessage = message
        val snapshot = ReadAloudStatus(newState, speechRate, message)
        val handler = mainHandler
        if (dispatchToMain && handler != null && Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { onStatusChanged(snapshot) }
        } else {
            onStatusChanged(snapshot)
        }
    }

    companion object {
        private const val TAG = "ReadAloudController"
        const val PREFS_NAME = "SelectionReadAloudPrefs"
        const val KEY_SPEECH_RATE = "speech_rate"

        const val MIN_SPEECH_RATE = 0.5f
        const val MAX_SPEECH_RATE = 2.0f
        const val DEFAULT_SPEECH_RATE = 1.0f
        const val SPEECH_RATE_STEP = 0.25f

        fun clampRate(rate: Float): Float {
            val coerced = rate.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
            return ((coerced * 100f).roundToInt()) / 100f
        }

        fun formatSpeed(rate: Float): String {
            return String.format(Locale.US, "%.2fx", rate)
                .replace("0x", "x")
                .replace(".x", ".0x")
        }
    }
}
