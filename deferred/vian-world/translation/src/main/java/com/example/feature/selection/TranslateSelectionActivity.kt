package com.example.feature.selection

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.feature.language.LanguageMiniAppUiHelper
import com.example.feature.language.translation.TranslationOutcome
import com.example.feature.language.translation.TranslationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android text-selection (`ACTION_PROCESS_TEXT`) popup activity for "Translate".
 *
 * Preserved outside active Sidebar build for future Vian World integration.
 */
class TranslateSelectionActivity : Activity() {

    private var selectedText: String? = null
    private var translatedResult: String? = null
    private var isDownloadingModel: Boolean = false
    private var downloadErrorMessage: String? = null

    private var languagePairHeaderView: TextView? = null
    private var sourcePreviewView: TextView? = null
    private var resultContainer: LinearLayout? = null
    private var copyFeedbackView: TextView? = null
    private var copyButton: TextView? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var translateJob: Job? = null
    private var downloadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedText = SelectionTextHelper.extractSelectedText(intent)
        buildUi()
        triggerTranslation()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectedText = SelectionTextHelper.extractSelectedText(intent)
        translatedResult = null
        downloadErrorMessage = null
        triggerTranslation()
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

        val pairHeader = TextView(this).apply {
            tag = TAG_LANGUAGE_PAIR_HEADER
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(LanguageMiniAppUiHelper.COLOR_ACCENT_TRANSLATION)
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        languagePairHeaderView = pairHeader
        body.addView(pairHeader)

        val sourceScroll = ScrollView(this).apply {
            background = LanguageMiniAppUiHelper.roundedBg(
                density,
                LanguageMiniAppUiHelper.COLOR_CARD_BG,
                LanguageMiniAppUiHelper.COLOR_BORDER,
                10f
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (88 * density).toInt()
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

        val maxOutputHeightPx = (resources.displayMetrics.heightPixels * 0.34f)
            .toInt()
            .coerceIn((130 * density).toInt(), (260 * density).toInt())

        val resultScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG_RESULT_CONTAINER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        resultContainer = results
        resultScroll.addView(results)

        val resultWrapper = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cappedHeightSpec = MeasureSpec.makeMeasureSpec(maxOutputHeightPx, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, cappedHeightSpec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(resultScroll)
        }
        body.addView(resultWrapper)

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
            contentDesc = "Open Translation settings",
            tag = TAG_SETTINGS_BUTTON
        ) {
            // Wire to Vian World Translation settings route
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

    private fun triggerTranslation() {
        translateJob?.cancel()
        translatedResult = null
        copyFeedbackView?.visibility = View.GONE

        val service = TranslationService.getInstance(this)
        val cfg = service.getConfig()
        val srcLabel = service.getLanguageDisplayName(cfg.sourceLanguageCode)
        val tgtLabel = service.getLanguageDisplayName(cfg.targetLanguageCode)
        languagePairHeaderView?.text = "$srcLabel → $tgtLabel"

        val text = selectedText
        if (text.isNullOrBlank()) {
            sourcePreviewView?.text = "No text was received from the selection menu."
            renderEmptySelection()
            return
        }

        sourcePreviewView?.text = text
        renderLoading("$srcLabel → $tgtLabel")

        translateJob = scope.launch {
            val outcome = service.translate(text)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                translatedResult = (outcome as? TranslationOutcome.Success)?.translatedText
                renderOutcome(service, outcome)
            }
        }
    }

    private fun triggerModelDownload(missingCodes: List<String>) {
        if (isDownloadingModel || missingCodes.isEmpty()) return
        val service = TranslationService.getInstance(this)
        isDownloadingModel = true
        downloadErrorMessage = null
        val names = missingCodes.joinToString(", ") { service.getLanguageDisplayName(it) }
        renderDownloading(names)

        downloadJob?.cancel()
        downloadJob = scope.launch {
            val res = service.downloadMissingModels(missingCodes)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                isDownloadingModel = false
                if (res.isSuccess) {
                    triggerTranslation()
                } else {
                    downloadErrorMessage = res.exceptionOrNull()?.message
                        ?: "Model download failed. Check Wi-Fi or download settings."
                    triggerTranslation()
                }
            }
        }
    }

    private fun renderEmptySelection() {
        val container = resultContainer ?: return
        container.removeAllViews()
        container.addView(
            LanguageMiniAppUiHelper.createStatusNoticeCard(
                context = this,
                badgeText = "EMPTY SELECTION",
                titleText = "No text selected",
                bodyText = "Select text in an app and choose Translate from the text selection menu."
            )
        )
    }

    private fun renderLoading(pairText: String) {
        val container = resultContainer ?: return
        container.removeAllViews()
        container.addView(
            LanguageMiniAppUiHelper.createStatusNoticeCard(
                context = this,
                badgeText = pairText.uppercase(),
                titleText = "Translating on device...",
                bodyText = "Running local ML Kit translation..."
            )
        )
    }

    private fun renderDownloading(names: String) {
        val container = resultContainer ?: return
        container.removeAllViews()
        container.addView(
            LanguageMiniAppUiHelper.createStatusNoticeCard(
                context = this,
                badgeText = "DOWNLOADING MODEL",
                titleText = "Downloading $names...",
                bodyText = "Once installed, translations run locally on your device."
            )
        )
    }

    private fun renderOutcome(service: TranslationService, outcome: TranslationOutcome) {
        val container = resultContainer ?: return
        val density = resources.displayMetrics.density
        container.removeAllViews()

        when (outcome) {
            is TranslationOutcome.Success -> {
                val srcName = service.getLanguageDisplayName(outcome.resolvedSourceCode)
                val tgtName = service.getLanguageDisplayName(outcome.targetCode)
                val badge = if (outcome.wasAutoDetected) {
                    "DETECTED $srcName → $tgtName"
                } else {
                    "$srcName → $tgtName"
                }
                languagePairHeaderView?.text = if (outcome.wasAutoDetected) {
                    "$srcName (detected) → $tgtName"
                } else {
                    "$srcName → $tgtName"
                }

                val outCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    tag = TAG_TRANSLATED_OUTPUT_CARD
                    background = LanguageMiniAppUiHelper.roundedBg(
                        density,
                        LanguageMiniAppUiHelper.COLOR_CARD_BG,
                        LanguageMiniAppUiHelper.COLOR_ACCENT_TRANSLATION,
                        10f
                    )
                    setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())

                    addView(TextView(this@TranslateSelectionActivity).apply {
                        text = badge.uppercase()
                        textSize = 10f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(LanguageMiniAppUiHelper.COLOR_ACCENT_TRANSLATION)
                        letterSpacing = 0.05f
                    })
                    addView(TextView(this@TranslateSelectionActivity).apply {
                        tag = TAG_TRANSLATED_OUTPUT_TEXT
                        text = outcome.translatedText
                        textSize = 14.5f
                        setTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_PRIMARY)
                        setLineSpacing(0f, 1.3f)
                        setTextIsSelectable(true)
                        setPadding(0, (5 * density).toInt(), 0, 0)
                    })
                }
                container.addView(outCard)
            }
            is TranslationOutcome.SameLanguage -> {
                val langName = service.getLanguageDisplayName(outcome.languageCode)
                container.addView(
                    LanguageMiniAppUiHelper.createStatusNoticeCard(
                        context = this,
                        badgeText = "SAME LANGUAGE",
                        titleText = "Already in $langName",
                        bodyText = "Change your target language in Translation Settings to translate into another language."
                    )
                )
            }
            is TranslationOutcome.MissingModels -> {
                val missingNames = outcome.missingLanguageCodes.joinToString(", ") {
                    service.getLanguageDisplayName(it)
                }
                val detectedNote = if (outcome.wasAutoDetected) {
                    "Detected ${service.getLanguageDisplayName(outcome.resolvedSourceCode)}. "
                } else ""
                val errSuffix = downloadErrorMessage?.let { "\n\n$it" }.orEmpty()
                container.addView(
                    LanguageMiniAppUiHelper.createStatusNoticeCard(
                        context = this,
                        badgeText = "MODEL REQUIRED",
                        titleText = "Missing model: $missingNames",
                        bodyText = "${detectedNote}Download the required offline ML Kit model(s) to translate locally.$errSuffix",
                        actionLabel = if (downloadErrorMessage != null) "Retry Download" else "Download Model(s)",
                        actionTag = TAG_DOWNLOAD_MODEL_BUTTON,
                        onActionClick = { triggerModelDownload(outcome.missingLanguageCodes) }
                    )
                )
            }
            TranslationOutcome.UndeterminedLanguage -> {
                container.addView(
                    LanguageMiniAppUiHelper.createStatusNoticeCard(
                        context = this,
                        badgeText = "AUTO-DETECT",
                        titleText = "Could not detect language",
                        bodyText = "Selected text could not be identified automatically. Set an explicit source language in Translation Settings."
                    )
                )
            }
            is TranslationOutcome.UnsupportedLanguage -> {
                container.addView(
                    LanguageMiniAppUiHelper.createStatusNoticeCard(
                        context = this,
                        badgeText = "UNSUPPORTED LANGUAGE",
                        titleText = "Unsupported language (${outcome.detectedLanguageTag})",
                        bodyText = "This language is not supported by on-device ML Kit Translation."
                    )
                )
            }
            is TranslationOutcome.Error -> {
                container.addView(
                    LanguageMiniAppUiHelper.createStatusNoticeCard(
                        context = this,
                        badgeText = "TRANSLATION ERROR",
                        titleText = "Translation failed",
                        bodyText = outcome.message
                    )
                )
            }
            TranslationOutcome.EmptyInput -> renderEmptySelection()
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

    override fun onDestroy() {
        translateJob?.cancel()
        downloadJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val TAG_LANGUAGE_PAIR_HEADER = "translate_popup_language_pair"
        const val TAG_SELECTED_TEXT = "translate_popup_selected_text"
        const val TAG_RESULT_CONTAINER = "translate_popup_result_container"
        const val TAG_TRANSLATED_OUTPUT_CARD = "translate_popup_output_card"
        const val TAG_TRANSLATED_OUTPUT_TEXT = "translate_popup_output_text"
        const val TAG_DOWNLOAD_MODEL_BUTTON = "translate_popup_download_model_button"
        const val TAG_COPY_BUTTON = "translate_popup_copy_button"
        const val TAG_SETTINGS_BUTTON = "translate_popup_settings_button"
        const val TAG_COPY_FEEDBACK = "translate_popup_copy_feedback"

        internal suspend fun performSharedTranslation(
            service: TranslationService,
            rawSelectedText: String?
        ): TranslationOutcome {
            val normalized = SelectionTextHelper.normalize(rawSelectedText)
                ?: return TranslationOutcome.EmptyInput
            return service.translate(normalized)
        }
    }
}
