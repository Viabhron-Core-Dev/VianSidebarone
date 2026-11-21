package com.example.feature.selection

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.feature.floating.FloatingMiniAppBridge
import com.example.feature.language.LanguageMiniAppUiHelper
import com.example.feature.language.dictionary.DictionaryDefinitionEntry
import com.example.feature.language.dictionary.DictionaryLookupOutcome
import com.example.feature.language.dictionary.DictionaryService
import com.example.feature.language.dictionary.StarDictFormatParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android text-selection (`ACTION_PROCESS_TEXT`) compact Quick Meaning popup for "Meaning".
 *
 * Connected to the shared offline [DictionaryService] (StarDict engine):
 * - Displays the selected term prominently with genuine pronunciation and part of speech when available.
 * - Shows a concise primary meaning preview rather than a full dictionary dump, and never invents
 *   frequency or "Most Common" ranking labels.
 * - Provides an "Open Full Dictionary" action that opens/reuses/focuses the single floating
 *   Dictionary window with the selected word and dismisses this popup cleanly.
 * - Follows Android system light/dark mode automatically.
 */
class MeaningSelectionActivity : Activity() {

    private var selectedText: String? = null
    private var activeQueryTerm: String = ""
    private var lastOutcome: DictionaryLookupOutcome = DictionaryLookupOutcome.EmptyQuery

    private var wordHeaderView: TextView? = null
    private var wordMetaRow: LinearLayout? = null
    private var meaningBodyContainer: LinearLayout? = null
    private var openFullDictionaryBtn: TextView? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lookupJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedText = SelectionTextHelper.extractSelectedText(intent)
        buildUi()
        triggerLookup()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectedText = SelectionTextHelper.extractSelectedText(intent)
        triggerLookup()
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        val palette = LanguageMiniAppUiHelper.resolvePalette(this)
        val shell = SelectionPopupShell.create(
            activity = this,
            title = "Quick Meaning",
            accentColor = palette.accentDictionary,
            onDismiss = { finish() }
        )

        val body = shell.cardContent

        // Top selected-word card (word + optional genuine pronunciation & POS row)
        val wordCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = LanguageMiniAppUiHelper.roundedBg(
                density,
                palette.cardBg,
                palette.accentDictionary,
                12f
            )
            setPadding((14 * density).toInt(), (11 * density).toInt(), (14 * density).toInt(), (11 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val wordText = TextView(this).apply {
            tag = TAG_SELECTED_WORD
            textSize = 18.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.textPrimary)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        wordHeaderView = wordText
        wordCard.addView(wordText)

        val metaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(0, (4 * density).toInt(), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        wordMetaRow = metaRow
        wordCard.addView(metaRow)
        body.addView(wordCard)

        body.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, (10 * density).toInt())
        })

        // Compact bounded quick-meaning container (not a full dictionary dump)
        val maxScrollHeightPx = (resources.displayMetrics.heightPixels * 0.34f)
            .toInt()
            .coerceIn((130 * density).toInt(), (240 * density).toInt())

        val resultScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isVerticalScrollBarEnabled = true
        }
        val meaningContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG_MEANING_RESULT_CONTAINER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        meaningBodyContainer = meaningContainer
        resultScroll.addView(meaningContainer)

        val scrollWrapper = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cappedHeightSpec = MeasureSpec.makeMeasureSpec(maxScrollHeightPx, MeasureSpec.AT_MOST)
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
        body.addView(scrollWrapper)

        body.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, (10 * density).toInt())
        })

        // Primary action: Open Full Dictionary (reuses/focuses existing floating Dictionary window)
        val openFullBtn = LanguageMiniAppUiHelper.createPillButton(
            context = this,
            text = "Open Full Dictionary",
            accentColor = palette.accentDictionary,
            filled = true,
            contentDesc = "Open Full Dictionary for selected word",
            tag = TAG_OPEN_FULL_DICTIONARY_BUTTON,
            palette = palette
        ) {
            openFullDictionaryAndDismiss()
        }.apply {
            minimumHeight = (48 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        openFullDictionaryBtn = openFullBtn
        body.addView(openFullBtn)

        body.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, (8 * density).toInt())
        })

        // Secondary action: Dictionary Settings
        val settingsBtn = LanguageMiniAppUiHelper.createPillButton(
            context = this,
            text = "Dictionary Settings",
            accentColor = palette.textSecondary,
            filled = false,
            contentDesc = "Open Dictionary settings",
            tag = TAG_OPEN_SETTINGS_BUTTON,
            palette = palette
        ) {
            SelectionPopupShell.openDictionarySettings(this)
            finish()
        }.apply {
            minimumHeight = (48 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        body.addView(settingsBtn)

        setContentView(shell.root)
    }

    private fun openFullDictionaryAndDismiss() {
        val term = activeQueryTerm.ifBlank {
            SelectionTextHelper.normalize(selectedText).orEmpty()
        }
        try {
            FloatingMiniAppBridge.getInstance(applicationContext).openDictionaryWithQuery(term)
        } catch (_: Throwable) {}
        finish()
    }

    private fun triggerLookup() {
        lookupJob?.cancel()
        val text = selectedText
        updateWordMetaRow(phonetic = null, partOfSpeech = null)
        if (text.isNullOrBlank()) {
            activeQueryTerm = ""
            wordHeaderView?.text = "No word selected"
            lastOutcome = DictionaryLookupOutcome.EmptyQuery
            renderEmptySelection()
            return
        }

        val primaryTerm = SelectionTextHelper.formatPrimaryTerm(text)
        activeQueryTerm = primaryTerm
        wordHeaderView?.text = primaryTerm
        renderLoading(primaryTerm)

        val service = DictionaryService.getInstance(this)
        lookupJob = scope.launch {
            val outcome = service.lookup(text)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                lastOutcome = outcome
                renderOutcome(primaryTerm, outcome)
            }
        }
    }

    private fun updateWordMetaRow(phonetic: String?, partOfSpeech: String?) {
        val row = wordMetaRow ?: return
        val density = resources.displayMetrics.density
        val palette = LanguageMiniAppUiHelper.resolvePalette(this)
        row.removeAllViews()

        val hasPhonetic = !phonetic.isNullOrBlank()
        val hasPos = !partOfSpeech.isNullOrBlank()
        if (!hasPhonetic && !hasPos) {
            row.visibility = View.GONE
            return
        }

        row.visibility = View.VISIBLE
        if (hasPos) {
            row.addView(TextView(this).apply {
                tag = TAG_PART_OF_SPEECH
                text = partOfSpeech
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(palette.accentDictionary)
                background = LanguageMiniAppUiHelper.roundedBg(density, palette.chipBg, null, 6f)
                setPadding((7 * density).toInt(), (2 * density).toInt(), (7 * density).toInt(), (2 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (hasPhonetic) marginEnd = (8 * density).toInt()
                }
            })
        }
        if (hasPhonetic) {
            row.addView(TextView(this).apply {
                tag = TAG_PHONETIC
                text = phonetic
                textSize = 12.5f
                setTextColor(palette.textSecondary)
            })
        }
    }

    private fun renderEmptySelection() {
        val container = meaningBodyContainer ?: return
        val palette = LanguageMiniAppUiHelper.resolvePalette(this)
        container.removeAllViews()
        container.addView(
            LanguageMiniAppUiHelper.createStatusNoticeCard(
                context = this,
                badgeText = "EMPTY SELECTION",
                titleText = "No text received",
                bodyText = "Select a word in an app and choose Meaning from the text selection menu.",
                palette = palette
            )
        )
    }

    private fun renderLoading(term: String) {
        val container = meaningBodyContainer ?: return
        val palette = LanguageMiniAppUiHelper.resolvePalette(this)
        container.removeAllViews()
        container.addView(
            LanguageMiniAppUiHelper.createStatusNoticeCard(
                context = this,
                badgeText = "LOOKING UP",
                titleText = "Searching offline dictionaries...",
                bodyText = "Looking up \"$term\" in your installed StarDict dictionaries.",
                palette = palette
            )
        )
    }

    private fun renderOutcome(term: String, outcome: DictionaryLookupOutcome) {
        val container = meaningBodyContainer ?: return
        val density = resources.displayMetrics.density
        val palette = LanguageMiniAppUiHelper.resolvePalette(this)
        container.removeAllViews()

        when (outcome) {
            is DictionaryLookupOutcome.Found -> {
                val primaryEntry = outcome.entries.firstOrNull()
                if (primaryEntry == null) {
                    renderEmptySelection()
                    return
                }
                updateWordMetaRow(
                    phonetic = primaryEntry.phonetic,
                    partOfSpeech = primaryEntry.partOfSpeech
                )
                container.addView(
                    buildQuickMeaningCard(
                        density = density,
                        palette = palette,
                        entry = primaryEntry,
                        additionalEntriesCount = (outcome.entries.size - 1).coerceAtLeast(0)
                    )
                )
            }
            is DictionaryLookupOutcome.NotFound -> {
                updateWordMetaRow(null, null)
                container.addView(
                    LanguageMiniAppUiHelper.createStatusNoticeCard(
                        context = this,
                        badgeText = "NO RESULT",
                        titleText = "No definition found for \"$term\"",
                        bodyText = "Searched ${outcome.searchedDictionaries} active offline ${if (outcome.searchedDictionaries == 1) "dictionary" else "dictionaries"}. Open Full Dictionary to edit the query or import dictionaries in Settings.",
                        palette = palette
                    )
                )
            }
            DictionaryLookupOutcome.NoDictionariesInstalled -> {
                updateWordMetaRow(null, null)
                container.addView(
                    LanguageMiniAppUiHelper.createStatusNoticeCard(
                        context = this,
                        badgeText = "NO DICTIONARY INSTALLED",
                        titleText = "Import an offline dictionary",
                        bodyText = "No StarDict dictionary is installed yet. Open Dictionary Settings to import a StarDict ZIP archive.",
                        palette = palette
                    )
                )
            }
            DictionaryLookupOutcome.AllDictionariesDisabled -> {
                updateWordMetaRow(null, null)
                container.addView(
                    LanguageMiniAppUiHelper.createStatusNoticeCard(
                        context = this,
                        badgeText = "DICTIONARIES DISABLED",
                        titleText = "All installed dictionaries are turned off",
                        bodyText = "Enable at least one dictionary in Dictionary Settings to view definitions.",
                        palette = palette
                    )
                )
            }
            is DictionaryLookupOutcome.Error -> {
                updateWordMetaRow(null, null)
                container.addView(
                    LanguageMiniAppUiHelper.createStatusNoticeCard(
                        context = this,
                        badgeText = "LOOKUP ERROR",
                        titleText = "Dictionary read failed",
                        bodyText = outcome.message,
                        palette = palette
                    )
                )
            }
            DictionaryLookupOutcome.EmptyQuery -> {
                updateWordMetaRow(null, null)
                renderEmptySelection()
            }
        }
    }

    private fun buildQuickMeaningCard(
        density: Float,
        palette: LanguageMiniAppUiHelper.FloatingUiPalette,
        entry: DictionaryDefinitionEntry,
        additionalEntriesCount: Int
    ): LinearLayout {
        val conciseText = buildConcisePopupMeaning(entry)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG_DEFINITION_CARD
            background = LanguageMiniAppUiHelper.roundedBg(
                density,
                palette.cardBg,
                palette.border,
                10f
            )
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())

            addView(TextView(this@MeaningSelectionActivity).apply {
                text = entry.dictionaryName.uppercase()
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(palette.accentDictionary)
                letterSpacing = 0.05f
            })

            if (!entry.matchedSynonym.isNullOrBlank()) {
                addView(TextView(this@MeaningSelectionActivity).apply {
                    text = "Matched synonym: ${entry.matchedSynonym} → ${entry.headword}"
                    textSize = 11f
                    setTextColor(palette.warningAccent)
                    setPadding(0, (3 * density).toInt(), 0, 0)
                })
            }

            if (conciseText.isNotBlank()) {
                addView(TextView(this@MeaningSelectionActivity).apply {
                    tag = TAG_CONCISE_MEANING
                    text = conciseText
                    textSize = 13.5f
                    setTextColor(palette.textPrimary)
                    setLineSpacing(0f, 1.3f)
                    setTextIsSelectable(true)
                    setPadding(0, (5 * density).toInt(), 0, 0)
                })
            }

            if (additionalEntriesCount > 0) {
                addView(TextView(this@MeaningSelectionActivity).apply {
                    text = "+$additionalEntriesCount more ${if (additionalEntriesCount == 1) "entry" else "entries"} in Full Dictionary"
                    textSize = 11f
                    setTextColor(palette.textMuted)
                    setPadding(0, (6 * density).toInt(), 0, 0)
                })
            }
        }
    }

    override fun onDestroy() {
        lookupJob?.cancel()
        lookupJob = null
        scope.cancel()
        wordHeaderView = null
        wordMetaRow = null
        meaningBodyContainer = null
        openFullDictionaryBtn = null
        super.onDestroy()
    }

    companion object {
        const val TAG_SELECTED_WORD = "meaning_popup_selected_word"
        const val TAG_PHONETIC = "meaning_popup_phonetic"
        const val TAG_PART_OF_SPEECH = "meaning_popup_part_of_speech"
        const val TAG_MEANING_RESULT_CONTAINER = "meaning_popup_result_container"
        const val TAG_DEFINITION_CARD = "meaning_popup_definition_card"
        const val TAG_CONCISE_MEANING = "meaning_popup_concise_meaning"
        const val TAG_OPEN_FULL_DICTIONARY_BUTTON = "meaning_popup_open_full_dictionary_button"
        const val TAG_OPEN_SETTINGS_BUTTON = "meaning_popup_open_settings_button"

        /**
         * Shared lookup helper invoked by UI and testable in JVM integration tests.
         */
        internal fun performSharedLookup(service: DictionaryService, rawSelectedText: String?): DictionaryLookupOutcome {
            val normalized = SelectionTextHelper.normalize(rawSelectedText)
                ?: return DictionaryLookupOutcome.EmptyQuery
            return service.lookup(normalized)
        }

        /**
         * Returns a concise primary-meaning excerpt for the compact popup without fabricating
         * frequency, ranking, or synthetic labels.
         */
        internal fun buildConcisePopupMeaning(entry: DictionaryDefinitionEntry): String {
            return StarDictFormatParser.extractConciseMeaning(entry.definitionText)
        }
    }
}
