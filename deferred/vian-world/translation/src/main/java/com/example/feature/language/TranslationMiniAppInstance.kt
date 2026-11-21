package com.example.feature.language

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.feature.floating.EmbeddedInputEditText
import com.example.feature.floating.MiniAppInstance
import com.example.feature.floating.MiniAppLifecycleState
import com.example.feature.floating.MiniAppRecoverySnapshot
import com.example.feature.language.translation.LanguageOption
import com.example.feature.language.translation.TranslationOutcome
import com.example.feature.language.translation.TranslationService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Standalone floating Translation mini-app (hosted in Main process).
 *
 * Connected to the shared on-device [TranslationService] (Google ML Kit Translate + Language ID):
 * - Preserves source/target selectors, swap control, input box, Translate/Copy/Clear actions,
 *   output area, Settings shortcut, and sleep/restore lifecycle.
 * - Uses ML Kit's full supported language list via an overlay-safe inline searchable picker.
 * - Executes language identification, model availability checks, downloads, and translation off
 *   the main thread. Never fabricates placeholder translations.
 */
class TranslationMiniAppInstance(
    override val instanceId: String,
    override val appType: String = APP_TYPE,
    private val onRequestWindowFocusable: (Boolean) -> Unit = {},
    private val onOpenSettings: () -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val postToUi: ((() -> Unit) -> Unit)? = null
) : MiniAppInstance {

    override var lifecycleState: MiniAppLifecycleState = MiniAppLifecycleState.UNINITIALIZED
        private set

    private var sourceLanguageCode: String = TranslationService.CODE_AUTO_DETECT
    private var targetLanguageCode: String = TranslationService.DEFAULT_TARGET_LANGUAGE_CODE
    private var hasExplicitSessionOverride: Boolean = false
    private var inputText: String = ""
    private var submittedText: String = ""
    private var translatedOutput: String? = null
    private var lastOutcome: TranslationOutcome = TranslationOutcome.EmptyInput
    private var isTranslating: Boolean = false
    private var isDownloadingModel: Boolean = false
    private var downloadStatusMessage: String? = null
    private var pickerTarget: PickerMode = PickerMode.NONE
    private var pickerFilterQuery: String = ""

    private var hostContext: Context? = null
    private var rootView: View? = null
    private var sourceLangBtn: TextView? = null
    private var targetLangBtn: TextView? = null
    private var swapBtn: TextView? = null
    private var inputEditText: EditText? = null
    private var pickerContainer: LinearLayout? = null
    private var outputContainer: LinearLayout? = null
    private var copyFeedbackView: TextView? = null

    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var translationJob: Job? = null
    private var downloadJob: Job? = null

    private enum class PickerMode { NONE, SOURCE, TARGET }

    override fun onCreate(savedSnapshot: MiniAppRecoverySnapshot?) {
        lifecycleState = MiniAppLifecycleState.CREATED
        val savedSrc = savedSnapshot?.payload?.get(KEY_SOURCE_LANG)
        val savedTgt = savedSnapshot?.payload?.get(KEY_TARGET_LANG)
        if (!savedSrc.isNullOrBlank() || !savedTgt.isNullOrBlank()) {
            hasExplicitSessionOverride = true
            sourceLanguageCode = resolveCodeWithoutContext(savedSrc, allowAuto = true)
                ?: TranslationService.CODE_AUTO_DETECT
            targetLanguageCode = resolveCodeWithoutContext(savedTgt, allowAuto = false)
                ?: TranslationService.DEFAULT_TARGET_LANGUAGE_CODE
        } else {
            hasExplicitSessionOverride = false
            sourceLanguageCode = TranslationService.CODE_AUTO_DETECT
            targetLanguageCode = TranslationService.DEFAULT_TARGET_LANGUAGE_CODE
        }
        inputText = savedSnapshot?.payload?.get(KEY_INPUT_TEXT) ?: ""
        submittedText = savedSnapshot?.payload?.get(KEY_SUBMITTED_TEXT) ?: ""
        translatedOutput = null
        lastOutcome = TranslationOutcome.EmptyInput
    }

    override fun restoreFromSnapshot(snapshot: MiniAppRecoverySnapshot) {
        onCreate(snapshot)
        val ctx = hostContext ?: rootView?.context
        if (ctx != null) {
            resolveStoredLanguagesWithService(ctx)
        }
        inputEditText?.setText(inputText)
        updateLanguageButtonLabels()
        if (ctx != null) {
            if (submittedText.isNotBlank()) {
                runTranslationAsync(ctx, submittedText)
            } else {
                renderOutputArea(ctx)
            }
        }
    }

    override fun createContentView(context: Context): View {
        hostContext = context
        val service = TranslationService.getInstance(context)
        if (!hasExplicitSessionOverride) {
            val cfg = service.getConfig()
            sourceLanguageCode = cfg.sourceLanguageCode
            targetLanguageCode = cfg.targetLanguageCode
        } else {
            resolveStoredLanguagesWithService(context)
        }

        val density = context.resources.displayMetrics.density

        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(LanguageMiniAppUiHelper.COLOR_SURFACE_BG)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            tag = TAG_ROOT
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Reserve bottom padding so scrollable content clears the shared bottom-right [ x | - | resize ] pill
            setPadding(
                (12 * density).toInt(),
                (10 * density).toInt(),
                (12 * density).toInt(),
                (52 * density).toInt()
            )
        }

        // 1. Source / Swap / Target Language Selector Row
        val langRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val srcBtn = LanguageMiniAppUiHelper.createPillButton(
            context = context,
            text = service.getLanguageDisplayName(sourceLanguageCode),
            accentColor = LanguageMiniAppUiHelper.COLOR_ACCENT_TRANSLATION,
            filled = false,
            contentDesc = "Select source language",
            tag = TAG_SOURCE_LANG_BUTTON
        ) {
            toggleInlineLanguagePicker(context, PickerMode.SOURCE)
        }.apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        sourceLangBtn = srcBtn
        langRow.addView(srcBtn)

        val swap = LanguageMiniAppUiHelper.createPillButton(
            context = context,
            text = "⇄",
            accentColor = LanguageMiniAppUiHelper.COLOR_TEXT_SECONDARY,
            filled = false,
            contentDesc = "Swap source and target languages",
            tag = TAG_SWAP_LANG_BUTTON
        ) {
            swapLanguages()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (6 * density).toInt()
                marginEnd = (6 * density).toInt()
            }
        }
        swapBtn = swap
        langRow.addView(swap)

        val tgtBtn = LanguageMiniAppUiHelper.createPillButton(
            context = context,
            text = service.getLanguageDisplayName(targetLanguageCode),
            accentColor = LanguageMiniAppUiHelper.COLOR_ACCENT_TRANSLATION,
            filled = false,
            contentDesc = "Select target language",
            tag = TAG_TARGET_LANG_BUTTON
        ) {
            toggleInlineLanguagePicker(context, PickerMode.TARGET)
        }.apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        targetLangBtn = tgtBtn
        langRow.addView(tgtBtn)
        content.addView(langRow)

        // Overlay-safe inline searchable language picker container (collapsed by default)
        val picker = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG_LANGUAGE_PICKER_PANEL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * density).toInt()
            }
        }
        pickerContainer = picker
        content.addView(picker)

        content.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, (10 * density).toInt())
        })

        // 2. Input Text Area
        val input = EmbeddedInputEditText(context) { instanceId }.apply {
            tag = TAG_INPUT_TEXT
            hint = "Enter text to translate..."
            setHintTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_MUTED)
            setTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_PRIMARY)
            textSize = 13.5f
            gravity = Gravity.TOP or Gravity.START
            minLines = 3
            maxLines = 5
            isVerticalScrollBarEnabled = true
            background = LanguageMiniAppUiHelper.roundedBg(
                density,
                LanguageMiniAppUiHelper.COLOR_INPUT_BG,
                LanguageMiniAppUiHelper.COLOR_BORDER,
                10f
            )
            setPadding((10 * density).toInt(), (9 * density).toInt(), (10 * density).toInt(), (9 * density).toInt())
            setText(inputText)
            setSelection(inputText.length)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnFocusChangeListener { _, hasFocus ->
                onRequestWindowFocusable(hasFocus)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    inputText = s?.toString().orEmpty()
                }
            })
        }
        inputEditText = input
        content.addView(input)

        content.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, (8 * density).toInt())
        })

        // 3. Action Buttons Row (Translate + Copy + Clear)
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val translateBtn = LanguageMiniAppUiHelper.createPillButton(
            context = context,
            text = "Translate",
            accentColor = LanguageMiniAppUiHelper.COLOR_ACCENT_TRANSLATION,
            filled = true,
            contentDesc = "Translate text",
            tag = TAG_TRANSLATE_BUTTON
        ) {
            submitTranslation(inputEditText?.text?.toString() ?: inputText)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        actionRow.addView(translateBtn)

        val copyBtn = LanguageMiniAppUiHelper.createPillButton(
            context = context,
            text = "Copy",
            accentColor = LanguageMiniAppUiHelper.COLOR_ACCENT_TRANSLATION,
            filled = false,
            contentDesc = "Copy translated text",
            tag = TAG_COPY_BUTTON
        ) {
            copyOutputToClipboard(context)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (6 * density).toInt()
            }
        }
        actionRow.addView(copyBtn)

        val clearBtn = LanguageMiniAppUiHelper.createPillButton(
            context = context,
            text = "Clear",
            accentColor = LanguageMiniAppUiHelper.COLOR_TEXT_SECONDARY,
            filled = false,
            contentDesc = "Clear translation input",
            tag = TAG_CLEAR_BUTTON
        ) {
            clearInput()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (6 * density).toInt()
            }
        }
        actionRow.addView(clearBtn)
        content.addView(actionRow)

        val feedback = TextView(context).apply {
            tag = TAG_COPY_FEEDBACK
            textSize = 11.5f
            setTextColor(LanguageMiniAppUiHelper.COLOR_WARNING_ACCENT)
            visibility = View.GONE
            setPadding(0, (6 * density).toInt(), 0, 0)
        }
        copyFeedbackView = feedback
        content.addView(feedback)

        content.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, (10 * density).toInt())
        })

        // 4. Translation Output Area
        val output = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG_OUTPUT_CONTAINER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        outputContainer = output
        content.addView(output)

        scrollView.addView(content)
        rootView = scrollView

        updateLanguageButtonLabels()
        // Refresh downloaded model list in background so language picker & status stay up to date
        scope.launch {
            service.refreshDownloadedModels()
            dispatchUi {
                if (lifecycleState != MiniAppLifecycleState.DESTROYED) {
                    (hostContext ?: rootView?.context)?.let { renderOutputArea(it) }
                }
            }
        }

        if (submittedText.isNotBlank()) {
            runTranslationAsync(context, submittedText)
        } else {
            renderOutputArea(context)
        }
        return scrollView
    }

    /**
     * Selects a source language by ML Kit code (e.g. `"es"`, `"auto"`) or display name (e.g. `"Spanish"`).
     */
    fun selectSourceLanguage(languageCodeOrName: String) {
        val ctx = hostContext ?: rootView?.context
        val resolved = if (ctx != null) {
            TranslationService.getInstance(ctx).resolveLanguageCode(languageCodeOrName, allowAuto = true)
        } else {
            resolveCodeWithoutContext(languageCodeOrName, allowAuto = true)
        } ?: return

        sourceLanguageCode = resolved
        hasExplicitSessionOverride = true
        if (ctx != null) {
            TranslationService.getInstance(ctx).updateConfig(sourceLanguageCode = resolved)
        }
        closeInlineLanguagePicker()
        updateLanguageButtonLabels()
        if (ctx != null) {
            if (submittedText.isNotBlank()) {
                runTranslationAsync(ctx, submittedText)
            } else {
                renderOutputArea(ctx)
            }
        }
    }

    /**
     * Selects a target language by ML Kit code (e.g. `"ja"`) or display name (e.g. `"Japanese"`).
     */
    fun selectTargetLanguage(languageCodeOrName: String) {
        val ctx = hostContext ?: rootView?.context
        val resolved = if (ctx != null) {
            TranslationService.getInstance(ctx).resolveLanguageCode(languageCodeOrName, allowAuto = false)
        } else {
            resolveCodeWithoutContext(languageCodeOrName, allowAuto = false)
        } ?: return

        targetLanguageCode = resolved
        hasExplicitSessionOverride = true
        if (ctx != null) {
            TranslationService.getInstance(ctx).updateConfig(targetLanguageCode = resolved)
        }
        closeInlineLanguagePicker()
        updateLanguageButtonLabels()
        if (ctx != null) {
            if (submittedText.isNotBlank()) {
                runTranslationAsync(ctx, submittedText)
            } else {
                renderOutputArea(ctx)
            }
        }
    }

    fun swapLanguages(): Boolean {
        if (sourceLanguageCode == TranslationService.CODE_AUTO_DETECT) return false
        val oldSource = sourceLanguageCode
        sourceLanguageCode = targetLanguageCode
        targetLanguageCode = oldSource
        hasExplicitSessionOverride = true
        val ctx = hostContext ?: rootView?.context
        if (ctx != null) {
            TranslationService.getInstance(ctx).updateConfig(
                sourceLanguageCode = sourceLanguageCode,
                targetLanguageCode = targetLanguageCode
            )
        }
        closeInlineLanguagePicker()
        updateLanguageButtonLabels()
        if (ctx != null) {
            if (submittedText.isNotBlank()) {
                runTranslationAsync(ctx, submittedText)
            } else {
                renderOutputArea(ctx)
            }
        }
        return true
    }

    fun submitTranslation(rawText: String) {
        inputText = rawText
        submittedText = rawText.trim()
        translatedOutput = null
        downloadStatusMessage = null
        copyFeedbackView?.visibility = View.GONE
        inputEditText?.let { input ->
            if (input.text?.toString() != rawText) {
                input.setText(rawText)
                input.setSelection(rawText.length)
            }
        }
        val ctx = hostContext ?: rootView?.context ?: return
        if (submittedText.isEmpty()) {
            translationJob?.cancel()
            isTranslating = false
            lastOutcome = TranslationOutcome.EmptyInput
            renderOutputArea(ctx)
        } else {
            runTranslationAsync(ctx, submittedText)
        }
    }

    fun clearInput() {
        translationJob?.cancel()
        isTranslating = false
        inputText = ""
        submittedText = ""
        translatedOutput = null
        downloadStatusMessage = null
        lastOutcome = TranslationOutcome.EmptyInput
        copyFeedbackView?.visibility = View.GONE
        inputEditText?.setText("")
        (hostContext ?: rootView?.context)?.let { renderOutputArea(it) }
    }

    fun copyOutputToClipboard(context: Context): Boolean {
        val output = translatedOutput?.takeIf { it.isNotBlank() }
        if (output == null) {
            val msg = "No translation output available to copy yet."
            copyFeedbackView?.apply {
                text = msg
                visibility = View.VISIBLE
            }
            try {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
            return false
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Translation", output))
        copyFeedbackView?.apply {
            text = "Copied translation to clipboard."
            visibility = View.VISIBLE
        }
        return true
    }

    /**
     * Syncs shared preferences from [TranslationService] (e.g., after returning from Settings).
     */
    fun syncFromSharedSettings() {
        val ctx = hostContext ?: rootView?.context ?: return
        val cfg = TranslationService.getInstance(ctx).getConfig()
        val changed = (cfg.sourceLanguageCode != sourceLanguageCode || cfg.targetLanguageCode != targetLanguageCode)
        sourceLanguageCode = cfg.sourceLanguageCode
        targetLanguageCode = cfg.targetLanguageCode
        updateLanguageButtonLabels()
        if (changed && submittedText.isNotBlank()) {
            runTranslationAsync(ctx, submittedText)
        } else {
            renderOutputArea(ctx)
        }
    }

    private fun runTranslationAsync(context: Context, textToTranslate: String) {
        translationJob?.cancel()
        isTranslating = true
        translatedOutput = null
        renderOutputArea(context)

        val service = TranslationService.getInstance(context)
        val src = sourceLanguageCode
        val tgt = targetLanguageCode
        translationJob = scope.launch {
            val outcome = service.translate(
                rawText = textToTranslate,
                overrideSourceCode = src,
                overrideTargetCode = tgt
            )
            dispatchUi {
                if (lifecycleState == MiniAppLifecycleState.DESTROYED) return@dispatchUi
                if (submittedText != textToTranslate) return@dispatchUi
                isTranslating = false
                lastOutcome = outcome
                translatedOutput = when (outcome) {
                    is TranslationOutcome.Success -> outcome.translatedText
                    else -> null
                }
                (hostContext ?: rootView?.context)?.let { renderOutputArea(it) }
            }
        }
    }

    private fun triggerMissingModelsDownload(context: Context, missingCodes: List<String>) {
        if (isDownloadingModel || missingCodes.isEmpty()) return
        isDownloadingModel = true
        val service = TranslationService.getInstance(context)
        val names = missingCodes.joinToString(", ") { service.getLanguageDisplayName(it) }
        downloadStatusMessage = "Downloading offline model(s): $names..."
        renderOutputArea(context)

        downloadJob?.cancel()
        downloadJob = scope.launch {
            val res = service.downloadMissingModels(missingCodes)
            dispatchUi {
                if (lifecycleState == MiniAppLifecycleState.DESTROYED) return@dispatchUi
                isDownloadingModel = false
                if (res.isSuccess) {
                    downloadStatusMessage = null
                    val ctx = hostContext ?: rootView?.context ?: return@dispatchUi
                    if (submittedText.isNotBlank()) {
                        runTranslationAsync(ctx, submittedText)
                    } else {
                        renderOutputArea(ctx)
                    }
                } else {
                    downloadStatusMessage = res.exceptionOrNull()?.message
                        ?: "Model download failed. Check your connection or Wi-Fi setting."
                    (hostContext ?: rootView?.context)?.let { renderOutputArea(it) }
                }
            }
        }
    }

    private fun toggleInlineLanguagePicker(context: Context, mode: PickerMode) {
        if (pickerTarget == mode) {
            closeInlineLanguagePicker()
            return
        }
        pickerTarget = mode
        pickerFilterQuery = ""
        renderInlineLanguagePicker(context)
    }

    private fun closeInlineLanguagePicker() {
        pickerTarget = PickerMode.NONE
        pickerFilterQuery = ""
        pickerContainer?.visibility = View.GONE
        pickerContainer?.removeAllViews()
    }

    private fun renderInlineLanguagePicker(context: Context) {
        val container = pickerContainer ?: return
        if (pickerTarget == PickerMode.NONE) {
            container.visibility = View.GONE
            container.removeAllViews()
            return
        }
        val density = context.resources.displayMetrics.density
        val service = TranslationService.getInstance(context)
        val downloaded = service.downloadedLanguages.value
        val options: List<LanguageOption> = if (pickerTarget == PickerMode.SOURCE) {
            service.getSupportedSourceLanguages()
        } else {
            service.getSupportedTargetLanguages()
        }

        container.visibility = View.VISIBLE
        container.removeAllViews()
        container.background = LanguageMiniAppUiHelper.roundedBg(
            density,
            LanguageMiniAppUiHelper.COLOR_CARD_BG,
            LanguageMiniAppUiHelper.COLOR_ACCENT_TRANSLATION,
            10f
        )
        container.setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())

        // Header + close button
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = if (pickerTarget == PickerMode.SOURCE) "Choose source language" else "Choose target language"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(LanguageMiniAppUiHelper.COLOR_ACCENT_TRANSLATION)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(LanguageMiniAppUiHelper.createPillButton(
            context = context,
            text = "Close",
            accentColor = LanguageMiniAppUiHelper.COLOR_TEXT_SECONDARY,
            filled = false,
            contentDesc = "Close language picker"
        ) {
            closeInlineLanguagePicker()
        })
        container.addView(header)

        // Search filter input
        val listHolder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun populateList(filter: String) {
            listHolder.removeAllViews()
            val q = filter.trim().lowercase()
            val filtered = if (q.isEmpty()) options else options.filter {
                it.displayName.lowercase().contains(q) || it.code.lowercase().contains(q)
            }
            for (opt in filtered) {
                val isSelected = if (pickerTarget == PickerMode.SOURCE) {
                    opt.code == sourceLanguageCode
                } else {
                    opt.code == targetLanguageCode
                }
                val isDownloaded = !opt.isAutoDetect && opt.code in downloaded
                val label = buildString {
                    append(opt.displayName)
                    if (!opt.isAutoDetect) append(" (${opt.code})")
                    if (isDownloaded) append("  ✓ Offline")
                }
                listHolder.addView(TextView(context).apply {
                    text = label
                    textSize = 13f
                    typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(
                        if (isSelected) LanguageMiniAppUiHelper.COLOR_ACCENT_TRANSLATION
                        else LanguageMiniAppUiHelper.COLOR_TEXT_PRIMARY
                    )
                    setPadding((8 * density).toInt(), (9 * density).toInt(), (8 * density).toInt(), (9 * density).toInt())
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (pickerTarget == PickerMode.SOURCE) {
                            selectSourceLanguage(opt.code)
                        } else {
                            selectTargetLanguage(opt.code)
                        }
                    }
                })
            }
            if (filtered.isEmpty()) {
                listHolder.addView(TextView(context).apply {
                    text = "No matching ML Kit language"
                    textSize = 12f
                    setTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_MUTED)
                    setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                })
            }
        }

        val searchBox = EmbeddedInputEditText(context) { "${instanceId}_lang_search" }.apply {
            hint = "Filter languages (${options.size})..."
            setHintTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_MUTED)
            setTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_PRIMARY)
            textSize = 12.5f
            isSingleLine = true
            background = LanguageMiniAppUiHelper.roundedBg(
                density,
                LanguageMiniAppUiHelper.COLOR_INPUT_BG,
                LanguageMiniAppUiHelper.COLOR_BORDER,
                8f
            )
            setPadding((8 * density).toInt(), (7 * density).toInt(), (8 * density).toInt(), (7 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (6 * density).toInt()
                bottomMargin = (6 * density).toInt()
            }
            setOnFocusChangeListener { _, hasFocus -> onRequestWindowFocusable(hasFocus) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    pickerFilterQuery = s?.toString().orEmpty()
                    populateList(pickerFilterQuery)
                }
            })
        }
        container.addView(searchBox)

        val boundedScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (170 * density).toInt()
            )
        }
        boundedScroll.addView(listHolder)
        container.addView(boundedScroll)
        populateList(pickerFilterQuery)
    }

    private fun updateLanguageButtonLabels() {
        val ctx = hostContext ?: rootView?.context
        val srcLabel = if (ctx != null) {
            TranslationService.getInstance(ctx).getLanguageDisplayName(sourceLanguageCode)
        } else {
            fallbackDisplayName(sourceLanguageCode)
        }
        val tgtLabel = if (ctx != null) {
            TranslationService.getInstance(ctx).getLanguageDisplayName(targetLanguageCode)
        } else {
            fallbackDisplayName(targetLanguageCode)
        }
        sourceLangBtn?.text = srcLabel
        targetLangBtn?.text = tgtLabel
        val canSwap = sourceLanguageCode != TranslationService.CODE_AUTO_DETECT
        swapBtn?.alpha = if (canSwap) 1f else 0.45f
    }

    private fun renderOutputArea(context: Context) {
        val container = outputContainer ?: return
        val density = context.resources.displayMetrics.density
        val service = TranslationService.getInstance(context)
        val srcName = service.getLanguageDisplayName(sourceLanguageCode)
        val tgtName = service.getLanguageDisplayName(targetLanguageCode)
        container.removeAllViews()

        if (isTranslating) {
            container.addView(
                LanguageMiniAppUiHelper.createStatusNoticeCard(
                    context = context,
                    badgeText = "$srcName → $tgtName",
                    titleText = "Translating on device...",
                    bodyText = "Running local ML Kit language detection and translation..."
                ).apply { tag = TAG_LOADING_STATE }
            )
            return
        }

        if (isDownloadingModel && !downloadStatusMessage.isNullOrBlank()) {
            container.addView(
                LanguageMiniAppUiHelper.createStatusNoticeCard(
                    context = context,
                    badgeText = "DOWNLOADING MODEL",
                    titleText = "Preparing offline translation model",
                    bodyText = downloadStatusMessage!!
                )
            )
            return
        }

        if (submittedText.isBlank()) {
            val downloadedCount = service.downloadedLanguages.value.size
            val emptyCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                tag = TAG_EMPTY_STATE
                background = LanguageMiniAppUiHelper.roundedBg(
                    density,
                    LanguageMiniAppUiHelper.COLOR_CARD_BG,
                    LanguageMiniAppUiHelper.COLOR_BORDER,
                    10f
                )
                setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())

                addView(TextView(context).apply {
                    text = "On-device Translation ($srcName → $tgtName)"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_PRIMARY)
                })
                addView(TextView(context).apply {
                    text = if (downloadedCount > 0) {
                        "$downloadedCount offline ML Kit language ${if (downloadedCount == 1) "model" else "models"} installed. Enter text above and tap Translate."
                    } else {
                        "Enter text above and tap Translate, or download offline language models in Settings."
                    }
                    textSize = 12f
                    setTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_SECONDARY)
                    setLineSpacing(0f, 1.25f)
                    setPadding(0, (6 * density).toInt(), 0, 0)
                })
            }
            container.addView(emptyCard)
            return
        }

        when (val outcome = lastOutcome) {
            is TranslationOutcome.Success -> {
                val resolvedSrcName = service.getLanguageDisplayName(outcome.resolvedSourceCode)
                val resolvedTgtName = service.getLanguageDisplayName(outcome.targetCode)
                val badge = if (outcome.wasAutoDetected) {
                    "DETECTED $resolvedSrcName → $resolvedTgtName • ON-DEVICE"
                } else {
                    "$resolvedSrcName → $resolvedTgtName • ON-DEVICE"
                }
                val resultCard = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    tag = TAG_TRANSLATED_OUTPUT_CARD
                    background = LanguageMiniAppUiHelper.roundedBg(
                        density,
                        LanguageMiniAppUiHelper.COLOR_CARD_BG,
                        LanguageMiniAppUiHelper.COLOR_ACCENT_TRANSLATION,
                        10f
                    )
                    setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())

                    addView(TextView(context).apply {
                        text = badge.uppercase()
                        textSize = 10f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(LanguageMiniAppUiHelper.COLOR_ACCENT_TRANSLATION)
                        letterSpacing = 0.05f
                    })
                    addView(TextView(context).apply {
                        tag = TAG_TRANSLATED_OUTPUT_TEXT
                        text = outcome.translatedText
                        textSize = 14.5f
                        setTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_PRIMARY)
                        setLineSpacing(0f, 1.3f)
                        setTextIsSelectable(true)
                        setPadding(0, (6 * density).toInt(), 0, 0)
                    })
                }
                container.addView(resultCard)
            }
            is TranslationOutcome.SameLanguage -> {
                val langName = service.getLanguageDisplayName(outcome.languageCode)
                container.addView(
                    LanguageMiniAppUiHelper.createStatusNoticeCard(
                        context = context,
                        badgeText = "SAME LANGUAGE",
                        titleText = "Source and target are both $langName",
                        bodyText = "Select a different target language to translate this text."
                    )
                )
            }
            is TranslationOutcome.MissingModels -> {
                val missingNames = outcome.missingLanguageCodes.joinToString(", ") {
                    service.getLanguageDisplayName(it)
                }
                val detectedPrefix = if (outcome.wasAutoDetected) {
                    "Detected ${service.getLanguageDisplayName(outcome.resolvedSourceCode)}. "
                } else ""
                val extraErr = downloadStatusMessage?.let { "\n\n$it" }.orEmpty()
                val noticeCard = LanguageMiniAppUiHelper.createStatusNoticeCard(
                    context = context,
                    badgeText = "MODEL REQUIRED FOR OFFLINE TRANSLATION",
                    titleText = "Missing language model: $missingNames",
                    bodyText = "${detectedPrefix}Download the required ML Kit on-device model(s) before translating locally.$extraErr",
                    actionLabel = if (downloadStatusMessage != null) "Retry Download" else "Download Model(s)",
                    actionTag = TAG_DOWNLOAD_MODEL_BUTTON,
                    onActionClick = {
                        triggerMissingModelsDownload(context, outcome.missingLanguageCodes)
                    }
                )
                noticeCard.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(1, (8 * density).toInt())
                })
                noticeCard.addView(
                    LanguageMiniAppUiHelper.createPillButton(
                        context = context,
                        text = "Open Settings",
                        accentColor = LanguageMiniAppUiHelper.COLOR_TEXT_SECONDARY,
                        filled = false,
                        contentDesc = "Open Translation Settings",
                        tag = TAG_OPEN_SETTINGS_BUTTON,
                        onClick = onOpenSettings
                    )
                )
                container.addView(noticeCard)
            }
            TranslationOutcome.UndeterminedLanguage -> {
                container.addView(
                    LanguageMiniAppUiHelper.createStatusNoticeCard(
                        context = context,
                        badgeText = "AUTO-DETECT",
                        titleText = "Could not detect language",
                        bodyText = "The text was too short or ambiguous for automatic language detection. Choose a specific source language above and try again."
                    )
                )
            }
            is TranslationOutcome.UnsupportedLanguage -> {
                container.addView(
                    LanguageMiniAppUiHelper.createStatusNoticeCard(
                        context = context,
                        badgeText = "UNSUPPORTED LANGUAGE",
                        titleText = "Language '${outcome.detectedLanguageTag}' is not supported",
                        bodyText = "Detected language '${outcome.detectedLanguageTag}' is not supported by on-device ML Kit Translation."
                    )
                )
            }
            is TranslationOutcome.Error -> {
                container.addView(
                    LanguageMiniAppUiHelper.createStatusNoticeCard(
                        context = context,
                        badgeText = "TRANSLATION ERROR",
                        titleText = "Translation failed",
                        bodyText = outcome.message,
                        actionLabel = "Open Settings",
                        actionTag = TAG_OPEN_SETTINGS_BUTTON,
                        onActionClick = onOpenSettings
                    )
                )
            }
            TranslationOutcome.EmptyInput -> Unit
        }
    }

    private fun resolveStoredLanguagesWithService(context: Context) {
        val service = TranslationService.getInstance(context)
        sourceLanguageCode = service.resolveLanguageCode(sourceLanguageCode, allowAuto = true)
            ?: TranslationService.CODE_AUTO_DETECT
        targetLanguageCode = service.resolveLanguageCode(targetLanguageCode, allowAuto = false)
            ?: TranslationService.DEFAULT_TARGET_LANGUAGE_CODE
    }

    private fun resolveCodeWithoutContext(raw: String?, allowAuto: Boolean): String? {
        val clean = raw?.trim().orEmpty()
        if (clean.isEmpty()) return null
        if (allowAuto && (clean.equals("auto", true) || clean.equals("Auto-detect", true))) {
            return TranslationService.CODE_AUTO_DETECT
        }
        val map = mapOf(
            "english" to "en", "spanish" to "es", "french" to "fr", "german" to "de",
            "hindi" to "hi", "japanese" to "ja", "arabic" to "ar", "chinese" to "zh"
        )
        map[clean.lowercase()]?.let { return it }
        return clean.lowercase()
    }

    private fun fallbackDisplayName(code: String): String {
        if (code == TranslationService.CODE_AUTO_DETECT) return TranslationService.DISPLAY_AUTO_DETECT
        val loc = java.util.Locale.forLanguageTag(code)
        val disp = loc.getDisplayLanguage(java.util.Locale.getDefault())
        return if (disp.isNullOrBlank() || disp.equals(code, true)) code.uppercase() else disp.replaceFirstChar { it.titlecase() }
    }

    private fun dispatchUi(block: () -> Unit) {
        val custom = postToUi
        if (custom != null) {
            custom(block)
            return
        }
        try {
            val mainLooper = Looper.getMainLooper()
            if (mainLooper == null || Looper.myLooper() == mainLooper) {
                block()
            } else {
                Handler(mainLooper).post(block)
            }
        } catch (_: Throwable) {
            block()
        }
    }

    override fun onStart() {
        lifecycleState = MiniAppLifecycleState.STARTED
    }

    override fun onResume() {
        lifecycleState = MiniAppLifecycleState.RESUMED
        syncFromSharedSettings()
    }

    override fun onPause() {
        lifecycleState = MiniAppLifecycleState.PAUSED
        onRequestWindowFocusable(false)
    }

    override fun onStop() {
        lifecycleState = MiniAppLifecycleState.STOPPED
    }

    override fun prepareForSleep(): Map<String, String> {
        lifecycleState = MiniAppLifecycleState.SLEEPING
        return onSaveState()
    }

    override fun onSaveState(): Map<String, String> {
        val latest = inputEditText?.text?.toString() ?: inputText
        inputText = latest
        return linkedMapOf(
            KEY_SOURCE_LANG to sourceLanguageCode,
            KEY_TARGET_LANG to targetLanguageCode,
            KEY_INPUT_TEXT to inputText,
            KEY_SUBMITTED_TEXT to submittedText
        )
    }

    override fun onDestroy() {
        lifecycleState = MiniAppLifecycleState.DESTROYED
        translationJob?.cancel()
        downloadJob?.cancel()
        scope.cancel()
        onRequestWindowFocusable(false)
        sourceLangBtn = null
        targetLangBtn = null
        swapBtn = null
        inputEditText = null
        pickerContainer = null
        outputContainer = null
        copyFeedbackView = null
        rootView = null
        hostContext = null
    }

    internal fun getSourceLanguageForTesting(): String {
        val ctx = hostContext ?: rootView?.context
        return if (ctx != null) TranslationService.getInstance(ctx).getLanguageDisplayName(sourceLanguageCode)
        else fallbackDisplayName(sourceLanguageCode)
    }

    internal fun getTargetLanguageForTesting(): String {
        val ctx = hostContext ?: rootView?.context
        return if (ctx != null) TranslationService.getInstance(ctx).getLanguageDisplayName(targetLanguageCode)
        else fallbackDisplayName(targetLanguageCode)
    }

    internal fun getSourceLanguageCodeForTesting(): String = sourceLanguageCode
    internal fun getTargetLanguageCodeForTesting(): String = targetLanguageCode
    internal fun getInputTextForTesting(): String = inputEditText?.text?.toString() ?: inputText
    internal fun getTranslatedOutputForTesting(): String? = translatedOutput
    internal fun getLastOutcomeForTesting(): TranslationOutcome = lastOutcome

    companion object {
        const val APP_TYPE = "translation"
        const val KEY_SOURCE_LANG = "translation_source_lang"
        const val KEY_TARGET_LANG = "translation_target_lang"
        const val KEY_INPUT_TEXT = "translation_input_text"
        const val KEY_SUBMITTED_TEXT = "translation_submitted_text"

        const val DEFAULT_SOURCE_LANGUAGE = TranslationService.DISPLAY_AUTO_DETECT
        const val DEFAULT_TARGET_LANGUAGE = "English"

        const val TAG_ROOT = "translation_miniapp_root"
        const val TAG_SOURCE_LANG_BUTTON = "translation_source_lang_button"
        const val TAG_TARGET_LANG_BUTTON = "translation_target_lang_button"
        const val TAG_SWAP_LANG_BUTTON = "translation_swap_lang_button"
        const val TAG_LANGUAGE_PICKER_PANEL = "translation_language_picker_panel"
        const val TAG_INPUT_TEXT = "translation_input_text"
        const val TAG_TRANSLATE_BUTTON = "translation_translate_button"
        const val TAG_COPY_BUTTON = "translation_copy_button"
        const val TAG_CLEAR_BUTTON = "translation_clear_button"
        const val TAG_COPY_FEEDBACK = "translation_copy_feedback"
        const val TAG_OUTPUT_CONTAINER = "translation_output_container"
        const val TAG_EMPTY_STATE = "translation_empty_state"
        const val TAG_LOADING_STATE = "translation_loading_state"
        const val TAG_TRANSLATED_OUTPUT_CARD = "translation_output_card"
        const val TAG_TRANSLATED_OUTPUT_TEXT = "translation_output_text"
        const val TAG_DOWNLOAD_MODEL_BUTTON = "translation_download_model_button"
        const val TAG_OPEN_SETTINGS_BUTTON = "translation_open_settings_button"
    }
}
