package com.example.feature.selection

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.feature.language.LanguageMiniAppUiHelper

/**
 * Android text-selection (`ACTION_PROCESS_TEXT`) popup activity for "Translate".
 *
 * Compact translation popup distinct from the full Translation floating mini-app:
 * - Shows the selected text.
 * - Shows a translation output area with an honest unconfigured placeholder (no fake translations).
 * - Provides a Copy button (copies real output when configured; informs honestly when unconfigured).
 */
class TranslateSelectionActivity : Activity() {

    private var selectedText: String? = null
    // Always null while no translation backend is configured; never populated with fake output.
    private var translatedResult: String? = null

    private var sourcePreviewView: TextView? = null
    private var resultContainer: LinearLayout? = null
    private var copyFeedbackView: TextView? = null
    private var copyButton: TextView? = null

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
        translatedResult = null
        renderContent()
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        val shell = SelectionPopupShell.create(
            activity = this,
            title = "Translate",
            accentColor = LanguageMiniAppUiHelper.COLOR_ACCENT_TRANSLATION,
            onDismiss = { finish() }
        )

        val body = shell.cardContent

        // Selected text preview card
        val sourceScroll = ScrollView(this).apply {
            background = LanguageMiniAppUiHelper.roundedBg(
                density,
                LanguageMiniAppUiHelper.COLOR_CARD_BG,
                LanguageMiniAppUiHelper.COLOR_BORDER,
                10f
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (96 * density).toInt()
            )
        }
        val preview = TextView(this).apply {
            tag = TAG_SELECTED_TEXT
            textSize = 13.5f
            setTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_PRIMARY)
            setLineSpacing(0f, 1.3f)
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
        }
        sourcePreviewView = preview
        sourceScroll.addView(preview)
        body.addView(sourceScroll)

        body.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, (10 * density).toInt())
        })

        // Translation result container
        val results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG_RESULT_CONTAINER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        resultContainer = results
        body.addView(results)

        val feedback = TextView(this).apply {
            tag = TAG_COPY_FEEDBACK
            textSize = 11.5f
            setTextColor(LanguageMiniAppUiHelper.COLOR_WARNING_ACCENT)
            visibility = View.GONE
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        copyFeedbackView = feedback
        body.addView(feedback)

        body.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, (10 * density).toInt())
        })

        // Bottom actions row: Copy + Settings
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val copyBtn = LanguageMiniAppUiHelper.createPillButton(
            context = this,
            text = "Copy",
            accentColor = LanguageMiniAppUiHelper.COLOR_ACCENT_TRANSLATION,
            filled = true,
            contentDesc = "Copy translated text",
            tag = TAG_COPY_BUTTON
        ) {
            copyTranslation()
        }.apply {
            minimumHeight = (48 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        copyButton = copyBtn
        actionRow.addView(copyBtn)

        val settingsBtn = LanguageMiniAppUiHelper.createPillButton(
            context = this,
            text = "Settings",
            accentColor = LanguageMiniAppUiHelper.COLOR_TEXT_PRIMARY,
            filled = false,
            contentDesc = "Open Dictionary and Translation settings",
            tag = TAG_SETTINGS_BUTTON
        ) {
            SelectionPopupShell.openDictionaryTranslationSettings(this)
            finish()
        }.apply {
            minimumHeight = (48 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (8 * density).toInt()
            }
        }
        actionRow.addView(settingsBtn)

        body.addView(actionRow)
        setContentView(shell.root)
    }

    private fun renderContent() {
        val container = resultContainer ?: return
        container.removeAllViews()
        copyFeedbackView?.visibility = View.GONE

        val text = selectedText
        if (text.isNullOrBlank()) {
            sourcePreviewView?.text = "No text was received from the selection menu."
            container.addView(
                LanguageMiniAppUiHelper.createStatusNoticeCard(
                    context = this,
                    badgeText = "EMPTY SELECTION",
                    titleText = "No text selected",
                    bodyText = "Select text in an app and choose Translate from the text selection menu."
                )
            )
        } else {
            sourcePreviewView?.text = text
            container.addView(
                LanguageMiniAppUiHelper.createStatusNoticeCard(
                    context = this,
                    badgeText = "TRANSLATION NOT CONFIGURED",
                    titleText = "Translation unavailable yet",
                    bodyText = "No translation engine or offline language pack is installed yet. Configure translation in Dictionary & Translation Settings when available."
                )
            )
        }
    }

    internal fun copyTranslation(): Boolean {
        val output = translatedResult?.takeIf { it.isNotBlank() }
        if (output == null) {
            val msg = "No translation output available to copy yet."
            copyFeedbackView?.apply {
                text = msg
                visibility = View.VISIBLE
            }
            try {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
            return false
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Translation", output))
        copyFeedbackView?.apply {
            text = "Copied translation to clipboard."
            visibility = View.VISIBLE
        }
        return true
    }

    companion object {
        const val TAG_SELECTED_TEXT = "translate_popup_selected_text"
        const val TAG_RESULT_CONTAINER = "translate_popup_result_container"
        const val TAG_COPY_BUTTON = "translate_popup_copy_button"
        const val TAG_SETTINGS_BUTTON = "translate_popup_settings_button"
        const val TAG_COPY_FEEDBACK = "translate_popup_copy_feedback"
    }
}
