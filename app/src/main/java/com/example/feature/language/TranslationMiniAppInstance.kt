package com.example.feature.language

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.feature.floating.EmbeddedInputEditText
import com.example.feature.floating.MiniAppInstance
import com.example.feature.floating.MiniAppLifecycleState
import com.example.feature.floating.MiniAppRecoverySnapshot

/**
 * Standalone floating Translation mini-app UI shell (hosted in Main process).
 *
 * Provides source/target language selectors, swap action, input text box, translation output area,
 * Copy action, empty & honest unconfigured placeholder states, and Edit/Settings navigation to the
 * shared Dictionary & Translation Settings screen. Does NOT fake translations or call external APIs.
 */
class TranslationMiniAppInstance(
    override val instanceId: String,
    override val appType: String = APP_TYPE,
    private val onRequestWindowFocusable: (Boolean) -> Unit = {},
    private val onOpenSettings: () -> Unit = {}
) : MiniAppInstance {

    override var lifecycleState: MiniAppLifecycleState = MiniAppLifecycleState.UNINITIALIZED
        private set

    private var sourceLanguage: String = DEFAULT_SOURCE_LANGUAGE
    private var targetLanguage: String = DEFAULT_TARGET_LANGUAGE
    private var inputText: String = ""
    private var submittedText: String = ""
    // Remains null until a real translation backend exists; never populated with fake output.
    private var translatedOutput: String? = null

    private var rootView: View? = null
    private var sourceLangBtn: TextView? = null
    private var targetLangBtn: TextView? = null
    private var swapBtn: TextView? = null
    private var inputEditText: EditText? = null
    private var outputContainer: LinearLayout? = null
    private var copyFeedbackView: TextView? = null

    override fun onCreate(savedSnapshot: MiniAppRecoverySnapshot?) {
        lifecycleState = MiniAppLifecycleState.CREATED
        sourceLanguage = savedSnapshot?.payload?.get(KEY_SOURCE_LANG)
            ?.takeIf { it in SOURCE_LANGUAGES } ?: DEFAULT_SOURCE_LANGUAGE
        targetLanguage = savedSnapshot?.payload?.get(KEY_TARGET_LANG)
            ?.takeIf { it in TARGET_LANGUAGES } ?: DEFAULT_TARGET_LANGUAGE
        inputText = savedSnapshot?.payload?.get(KEY_INPUT_TEXT) ?: ""
        submittedText = savedSnapshot?.payload?.get(KEY_SUBMITTED_TEXT) ?: ""
        translatedOutput = null
    }

    override fun restoreFromSnapshot(snapshot: MiniAppRecoverySnapshot) {
        onCreate(snapshot)
        inputEditText?.setText(inputText)
        updateLanguageButtonLabels()
        rootView?.context?.let { renderOutputArea(it) }
    }

    override fun createContentView(context: Context): View {
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
            text = sourceLanguage,
            accentColor = LanguageMiniAppUiHelper.COLOR_ACCENT_TRANSLATION,
            filled = false,
            contentDesc = "Select source language",
            tag = TAG_SOURCE_LANG_BUTTON
        ) {
            showLanguagePicker(context, sourceLangBtn!!, SOURCE_LANGUAGES) { selected ->
                selectSourceLanguage(selected)
            }
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
            text = targetLanguage,
            accentColor = LanguageMiniAppUiHelper.COLOR_ACCENT_TRANSLATION,
            filled = false,
            contentDesc = "Select target language",
            tag = TAG_TARGET_LANG_BUTTON
        ) {
            showLanguagePicker(context, targetLangBtn!!, TARGET_LANGUAGES) { selected ->
                selectTargetLanguage(selected)
            }
        }.apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        targetLangBtn = tgtBtn
        langRow.addView(tgtBtn)
        content.addView(langRow)

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
        renderOutputArea(context)
        return scrollView
    }

    fun selectSourceLanguage(language: String) {
        if (language !in SOURCE_LANGUAGES) return
        sourceLanguage = language
        updateLanguageButtonLabels()
        rootView?.context?.let { renderOutputArea(it) }
    }

    fun selectTargetLanguage(language: String) {
        if (language !in TARGET_LANGUAGES) return
        targetLanguage = language
        updateLanguageButtonLabels()
        rootView?.context?.let { renderOutputArea(it) }
    }

    fun swapLanguages(): Boolean {
        if (sourceLanguage == DEFAULT_SOURCE_LANGUAGE) return false
        val oldSource = sourceLanguage
        sourceLanguage = targetLanguage
        targetLanguage = oldSource
        updateLanguageButtonLabels()
        rootView?.context?.let { renderOutputArea(it) }
        return true
    }

    fun submitTranslation(rawText: String) {
        inputText = rawText
        submittedText = rawText.trim()
        translatedOutput = null
        copyFeedbackView?.visibility = View.GONE
        inputEditText?.let { input ->
            if (input.text?.toString() != rawText) {
                input.setText(rawText)
                input.setSelection(rawText.length)
            }
        }
        rootView?.context?.let { renderOutputArea(it) }
    }

    fun clearInput() {
        inputText = ""
        submittedText = ""
        translatedOutput = null
        copyFeedbackView?.visibility = View.GONE
        inputEditText?.setText("")
        rootView?.context?.let { renderOutputArea(it) }
    }

    fun copyOutputToClipboard(context: Context): Boolean {
        val output = translatedOutput?.takeIf { it.isNotBlank() }
        if (output == null) {
            val msg = "No translation output to copy yet — translation engine not configured."
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

    private fun showLanguagePicker(
        context: Context,
        anchor: View,
        languages: List<String>,
        onSelected: (String) -> Unit
    ) {
        try {
            val popup = PopupMenu(context, anchor)
            languages.forEachIndexed { idx, lang ->
                popup.menu.add(0, idx, idx, lang)
            }
            popup.setOnMenuItemClickListener { item ->
                languages.getOrNull(item.itemId)?.let(onSelected)
                true
            }
            popup.show()
        } catch (_: Throwable) {
            // Safe fallback if anchor cannot host PopupMenu in a headless/overlay context: cycle cleanly
            val currentText = (anchor as? TextView)?.text?.toString()
            val idx = languages.indexOf(currentText)
            val next = languages[(idx + 1) % languages.size]
            onSelected(next)
        }
    }

    private fun updateLanguageButtonLabels() {
        sourceLangBtn?.text = sourceLanguage
        targetLangBtn?.text = targetLanguage
        val canSwap = sourceLanguage != DEFAULT_SOURCE_LANGUAGE
        swapBtn?.alpha = if (canSwap) 1f else 0.45f
    }

    private fun renderOutputArea(context: Context) {
        val container = outputContainer ?: return
        val density = context.resources.displayMetrics.density
        container.removeAllViews()

        if (submittedText.isBlank()) {
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
                    text = "Translation output ($sourceLanguage → $targetLanguage)"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_PRIMARY)
                })
                addView(TextView(context).apply {
                    text = "Enter text above and tap Translate. Translated output will appear here once a translation engine or language pack is configured."
                    textSize = 12f
                    setTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_SECONDARY)
                    setLineSpacing(0f, 1.25f)
                    setPadding(0, (6 * density).toInt(), 0, 0)
                })
            }
            container.addView(emptyCard)
            container.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(1, (10 * density).toInt())
            })
            container.addView(
                LanguageMiniAppUiHelper.createStatusNoticeCard(
                    context = context,
                    badgeText = "TRANSLATION STATUS",
                    titleText = "Translation engine not configured yet",
                    bodyText = "No translation backend or offline language pack is installed yet. Open Settings to inspect Dictionary & Translation configuration.",
                    actionLabel = "Open Settings",
                    actionTag = TAG_OPEN_SETTINGS_BUTTON,
                    onActionClick = onOpenSettings
                )
            )
        } else {
            container.addView(
                LanguageMiniAppUiHelper.createStatusNoticeCard(
                    context = context,
                    badgeText = "$sourceLanguage → $targetLanguage • NOT CONFIGURED",
                    titleText = "Translation unavailable",
                    bodyText = "No translation engine or language pack is configured yet for translating \"${submittedText.take(80)}\". Configure translation in Dictionary & Translation Settings when available.",
                    actionLabel = "Open Settings",
                    actionTag = TAG_OPEN_SETTINGS_BUTTON,
                    onActionClick = onOpenSettings
                )
            )
        }
    }

    override fun onStart() {
        lifecycleState = MiniAppLifecycleState.STARTED
    }

    override fun onResume() {
        lifecycleState = MiniAppLifecycleState.RESUMED
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
            KEY_SOURCE_LANG to sourceLanguage,
            KEY_TARGET_LANG to targetLanguage,
            KEY_INPUT_TEXT to inputText,
            KEY_SUBMITTED_TEXT to submittedText
        )
    }

    override fun onDestroy() {
        lifecycleState = MiniAppLifecycleState.DESTROYED
        onRequestWindowFocusable(false)
        sourceLangBtn = null
        targetLangBtn = null
        swapBtn = null
        inputEditText = null
        outputContainer = null
        copyFeedbackView = null
        rootView = null
    }

    internal fun getSourceLanguageForTesting(): String = sourceLanguage
    internal fun getTargetLanguageForTesting(): String = targetLanguage
    internal fun getInputTextForTesting(): String = inputEditText?.text?.toString() ?: inputText
    internal fun getTranslatedOutputForTesting(): String? = translatedOutput

    companion object {
        const val APP_TYPE = "translation"
        const val KEY_SOURCE_LANG = "translation_source_lang"
        const val KEY_TARGET_LANG = "translation_target_lang"
        const val KEY_INPUT_TEXT = "translation_input_text"
        const val KEY_SUBMITTED_TEXT = "translation_submitted_text"

        const val DEFAULT_SOURCE_LANGUAGE = "Auto-detect"
        const val DEFAULT_TARGET_LANGUAGE = "English"

        val SOURCE_LANGUAGES = listOf(
            "Auto-detect", "English", "Spanish", "French", "German", "Hindi", "Japanese", "Arabic", "Chinese"
        )
        val TARGET_LANGUAGES = listOf(
            "English", "Spanish", "French", "German", "Hindi", "Japanese", "Arabic", "Chinese"
        )

        const val TAG_ROOT = "translation_miniapp_root"
        const val TAG_SOURCE_LANG_BUTTON = "translation_source_lang_button"
        const val TAG_TARGET_LANG_BUTTON = "translation_target_lang_button"
        const val TAG_SWAP_LANG_BUTTON = "translation_swap_lang_button"
        const val TAG_INPUT_TEXT = "translation_input_text"
        const val TAG_TRANSLATE_BUTTON = "translation_translate_button"
        const val TAG_COPY_BUTTON = "translation_copy_button"
        const val TAG_CLEAR_BUTTON = "translation_clear_button"
        const val TAG_COPY_FEEDBACK = "translation_copy_feedback"
        const val TAG_OUTPUT_CONTAINER = "translation_output_container"
        const val TAG_EMPTY_STATE = "translation_empty_state"
        const val TAG_OPEN_SETTINGS_BUTTON = "translation_open_settings_button"
    }
}
