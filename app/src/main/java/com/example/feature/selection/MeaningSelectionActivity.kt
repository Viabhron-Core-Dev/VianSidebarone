package com.example.feature.selection

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.feature.language.LanguageMiniAppUiHelper

/**
 * Android text-selection (`ACTION_PROCESS_TEXT`) popup activity for "Meaning".
 *
 * Compact lookup popup distinct from the full Dictionary floating mini-app:
 * - Displays the selected word prominently at the top.
 * - Includes an inline Read Aloud control backed by built-in Android [android.speech.tts.TextToSpeech].
 * - Displays a concise meaning/result area with an honest unconfigured placeholder (no fake definitions).
 */
class MeaningSelectionActivity : Activity() {

    internal var readAloudController: ReadAloudController? = null
    private var selectedText: String? = null

    private var wordHeaderView: TextView? = null
    private var speakButton: TextView? = null
    private var ttsStatusView: TextView? = null
    private var meaningBodyContainer: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedText = SelectionTextHelper.extractSelectedText(intent)
        buildUi()
        renderContent()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectedText = SelectionTextHelper.extractSelectedText(intent)
        readAloudController?.stop()
        renderContent()
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        val shell = SelectionPopupShell.create(
            activity = this,
            title = "Meaning",
            accentColor = LanguageMiniAppUiHelper.COLOR_ACCENT_DICTIONARY,
            onDismiss = { finish() }
        )

        val body = shell.cardContent

        // Top selected-word row + inline Read Aloud button
        val wordRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = LanguageMiniAppUiHelper.roundedBg(
                density,
                LanguageMiniAppUiHelper.COLOR_CARD_BG,
                LanguageMiniAppUiHelper.COLOR_ACCENT_DICTIONARY,
                12f
            )
            setPadding((14 * density).toInt(), (12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val wordText = TextView(this).apply {
            tag = TAG_SELECTED_WORD
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_PRIMARY)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (8 * density).toInt()
            }
        }
        wordHeaderView = wordText
        wordRow.addView(wordText)

        val speakBtn = LanguageMiniAppUiHelper.createPillButton(
            context = this,
            text = "Read",
            accentColor = LanguageMiniAppUiHelper.COLOR_ACCENT_DICTIONARY,
            filled = true,
            contentDesc = "Read selected word aloud",
            tag = TAG_READ_ALOUD_BUTTON
        ) {
            triggerReadAloud()
        }.apply {
            minimumHeight = (48 * density).toInt()
        }
        speakButton = speakBtn
        wordRow.addView(speakBtn)
        body.addView(wordRow)

        val ttsStatus = TextView(this).apply {
            tag = TAG_TTS_STATUS
            textSize = 11.5f
            setTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_SECONDARY)
            visibility = View.GONE
            setPadding(0, (6 * density).toInt(), 0, 0)
        }
        ttsStatusView = ttsStatus
        body.addView(ttsStatus)

        body.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, (12 * density).toInt())
        })

        val meaningContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG_MEANING_RESULT_CONTAINER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        meaningBodyContainer = meaningContainer
        body.addView(meaningContainer)

        setContentView(shell.root)
    }

    private fun renderContent() {
        val container = meaningBodyContainer ?: return
        container.removeAllViews()

        val text = selectedText
        if (text.isNullOrBlank()) {
            wordHeaderView?.text = "No word selected"
            speakButton?.isEnabled = false
            speakButton?.alpha = 0.45f
            container.addView(
                LanguageMiniAppUiHelper.createStatusNoticeCard(
                    context = this,
                    badgeText = "EMPTY SELECTION",
                    titleText = "No text received",
                    bodyText = "Select a word in an app and choose Meaning from the text selection menu."
                )
            )
        } else {
            val primaryTerm = SelectionTextHelper.formatPrimaryTerm(text)
            wordHeaderView?.text = primaryTerm
            speakButton?.isEnabled = true
            speakButton?.alpha = 1f
            container.addView(
                LanguageMiniAppUiHelper.createStatusNoticeCard(
                    context = this,
                    badgeText = "DICTIONARY NOT CONFIGURED",
                    titleText = "Meaning unavailable yet",
                    bodyText = "No offline dictionary is configured yet, so a definition for \"$primaryTerm\" cannot be shown. Configure dictionaries in Dictionary & Translation Settings when available.",
                    actionLabel = "Open Settings",
                    actionTag = TAG_OPEN_SETTINGS_BUTTON,
                    onActionClick = {
                        SelectionPopupShell.openDictionaryTranslationSettings(this)
                        finish()
                    }
                )
            )
        }
    }

    private fun triggerReadAloud() {
        val text = selectedText ?: return
        val existing = readAloudController
        if (existing != null && !existing.isReleased()) {
            existing.togglePlayStop(text)
            return
        }
        val controller = ReadAloudController(
            context = this,
            onStatusChanged = { status ->
                ttsStatusView?.apply {
                    this.text = status.message
                    visibility = View.VISIBLE
                }
                speakButton?.text = if (status.state == ReadAloudState.SPEAKING) "Stop" else "Read"
            }
        )
        readAloudController = controller
        controller.speak(text)
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
        const val TAG_SELECTED_WORD = "meaning_popup_selected_word"
        const val TAG_READ_ALOUD_BUTTON = "meaning_popup_read_aloud_button"
        const val TAG_TTS_STATUS = "meaning_popup_tts_status"
        const val TAG_MEANING_RESULT_CONTAINER = "meaning_popup_result_container"
        const val TAG_OPEN_SETTINGS_BUTTON = "meaning_popup_open_settings_button"
    }
}
