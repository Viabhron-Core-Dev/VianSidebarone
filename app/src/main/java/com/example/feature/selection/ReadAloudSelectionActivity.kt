package com.example.feature.selection

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.feature.language.LanguageMiniAppUiHelper

/**
 * Android text-selection (`ACTION_PROCESS_TEXT`) popup activity for "Read aloud".
 *
 * Uses built-in Android [android.speech.tts.TextToSpeech] via [ReadAloudController]:
 * - Speaks selected text automatically when ready.
 * - Provides Play/Stop plus Slower and Faster speech-rate controls.
 * - Handles empty/missing text, missing TTS engines, and unsupported languages safely.
 * - Releases and shuts down TTS cleanly with popup lifecycle.
 */
class ReadAloudSelectionActivity : Activity() {

    internal var readAloudController: ReadAloudController? = null
    private var selectedText: String? = null

    private var excerptView: TextView? = null
    private var statusView: TextView? = null
    private var speedLabelView: TextView? = null
    private var playStopButton: TextView? = null
    private var slowerButton: TextView? = null
    private var fasterButton: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedText = SelectionTextHelper.extractSelectedText(intent)
        buildUi()
        initializeControllerAndSpeak()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectedText = SelectionTextHelper.extractSelectedText(intent)
        renderSelectedText()
        val text = selectedText
        if (text != null) {
            if (readAloudController == null || readAloudController?.isReleased() == true) {
                initializeControllerAndSpeak()
            } else {
                readAloudController?.speak(text)
            }
        } else {
            readAloudController?.stop()
            statusView?.text = "No text selected to read aloud."
        }
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        val shell = SelectionPopupShell.create(
            activity = this,
            title = "Read aloud",
            accentColor = LanguageMiniAppUiHelper.COLOR_ACCENT_DICTIONARY,
            onDismiss = { finish() }
        )

        val body = shell.cardContent

        // Scrollable selected-text excerpt card
        val excerptScroll = ScrollView(this).apply {
            background = LanguageMiniAppUiHelper.roundedBg(
                density,
                LanguageMiniAppUiHelper.COLOR_CARD_BG,
                LanguageMiniAppUiHelper.COLOR_BORDER,
                10f
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (110 * density).toInt()
            )
        }
        val excerpt = TextView(this).apply {
            tag = TAG_SELECTED_TEXT
            textSize = 13.5f
            setTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_PRIMARY)
            setLineSpacing(0f, 1.3f)
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
        }
        excerptView = excerpt
        excerptScroll.addView(excerpt)
        body.addView(excerptScroll)

        // Status line
        val status = TextView(this).apply {
            tag = TAG_STATUS_TEXT
            textSize = 12f
            setTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_SECONDARY)
            setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
        }
        statusView = status
        body.addView(status)

        // Controls row: Slower | Speed | Faster | Play/Stop
        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val slower = LanguageMiniAppUiHelper.createPillButton(
            context = this,
            text = "Slower",
            accentColor = LanguageMiniAppUiHelper.COLOR_TEXT_PRIMARY,
            filled = false,
            contentDesc = "Decrease speech speed",
            tag = TAG_SLOWER_BUTTON
        ) {
            readAloudController?.slower()
        }.apply {
            minimumHeight = (48 * density).toInt()
        }
        slowerButton = slower
        controlsRow.addView(slower)

        val speedLbl = TextView(this).apply {
            tag = TAG_SPEED_LABEL
            text = ReadAloudController.formatSpeed(ReadAloudController.DEFAULT_SPEECH_RATE)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(LanguageMiniAppUiHelper.COLOR_ACCENT_DICTIONARY)
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        speedLabelView = speedLbl
        controlsRow.addView(speedLbl)

        val faster = LanguageMiniAppUiHelper.createPillButton(
            context = this,
            text = "Faster",
            accentColor = LanguageMiniAppUiHelper.COLOR_TEXT_PRIMARY,
            filled = false,
            contentDesc = "Increase speech speed",
            tag = TAG_FASTER_BUTTON
        ) {
            readAloudController?.faster()
        }.apply {
            minimumHeight = (48 * density).toInt()
        }
        fasterButton = faster
        controlsRow.addView(faster)

        controlsRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })

        val playStop = LanguageMiniAppUiHelper.createPillButton(
            context = this,
            text = "Play",
            accentColor = LanguageMiniAppUiHelper.COLOR_ACCENT_DICTIONARY,
            filled = true,
            contentDesc = "Play or stop reading aloud",
            tag = TAG_PLAY_STOP_BUTTON
        ) {
            readAloudController?.togglePlayStop(selectedText)
        }.apply {
            minimumHeight = (48 * density).toInt()
        }
        playStopButton = playStop
        controlsRow.addView(playStop)

        body.addView(controlsRow)

        setContentView(shell.root)
        renderSelectedText()
    }

    private fun renderSelectedText() {
        val text = selectedText
        if (text.isNullOrBlank()) {
            excerptView?.text = "No text was received from the selection menu."
            statusView?.text = "Select text in an app and choose Read aloud."
            playStopButton?.isEnabled = false
            playStopButton?.alpha = 0.5f
        } else {
            excerptView?.text = text
            playStopButton?.isEnabled = true
            playStopButton?.alpha = 1f
        }
    }

    private fun initializeControllerAndSpeak() {
        val text = selectedText ?: return
        val controller = ReadAloudController(
            context = this,
            onStatusChanged = { status -> updateUiForStatus(status) }
        )
        readAloudController = controller
        speedLabelView?.text = ReadAloudController.formatSpeed(controller.getSpeechRate())
        controller.speak(text)
    }

    private fun updateUiForStatus(status: ReadAloudStatus) {
        statusView?.text = status.message
        speedLabelView?.text = ReadAloudController.formatSpeed(status.speechRate)
        playStopButton?.text = if (status.state == ReadAloudState.SPEAKING) "Stop" else "Play"
        val canSpeak = !selectedText.isNullOrBlank() &&
            status.state != ReadAloudState.ERROR &&
            status.state != ReadAloudState.UNSUPPORTED_LANGUAGE
        playStopButton?.isEnabled = canSpeak
        playStopButton?.alpha = if (canSpeak) 1f else 0.5f
        slowerButton?.isEnabled = status.speechRate > ReadAloudController.MIN_SPEECH_RATE
        slowerButton?.alpha = if (status.speechRate > ReadAloudController.MIN_SPEECH_RATE) 1f else 0.45f
        fasterButton?.isEnabled = status.speechRate < ReadAloudController.MAX_SPEECH_RATE
        fasterButton?.alpha = if (status.speechRate < ReadAloudController.MAX_SPEECH_RATE) 1f else 0.45f
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            readAloudController?.stop()
        }
    }

    override fun onDestroy() {
        readAloudController?.release()
        readAloudController = null
        super.onDestroy()
    }

    companion object {
        const val TAG_SELECTED_TEXT = "read_aloud_selected_text"
        const val TAG_STATUS_TEXT = "read_aloud_status_text"
        const val TAG_SPEED_LABEL = "read_aloud_speed_label"
        const val TAG_SLOWER_BUTTON = "read_aloud_slower_button"
        const val TAG_FASTER_BUTTON = "read_aloud_faster_button"
        const val TAG_PLAY_STOP_BUTTON = "read_aloud_play_stop_button"
    }
}
