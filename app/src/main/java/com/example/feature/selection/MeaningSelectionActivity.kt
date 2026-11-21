package com.example.feature.selection

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.feature.language.LanguageMiniAppUiHelper
import com.example.feature.language.dictionary.DictionaryDefinitionEntry
import com.example.feature.language.dictionary.DictionaryLookupOutcome
import com.example.feature.language.dictionary.DictionaryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android text-selection (`ACTION_PROCESS_TEXT`) popup activity for "Meaning".
 *
 * Connected to the shared offline [DictionaryService] (StarDict engine):
 * - Displays the selected term cleanly at top (without custom Read Aloud button/TTS).
 * - Looks up the term off the main thread via [DictionaryService].
 * - Displays actual definitions with dictionary attribution, plus honest loading, no-result,
 *   unconfigured, and error states.
 * - Provides a direct route to Dictionary Settings.
 */
class MeaningSelectionActivity : Activity() {

    private var selectedText: String? = null
    private var lastOutcome: DictionaryLookupOutcome = DictionaryLookupOutcome.EmptyQuery

    private var wordHeaderView: TextView? = null
    private var meaningBodyContainer: LinearLayout? = null

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
        val shell = SelectionPopupShell.create(
            activity = this,
            title = "Meaning",
            accentColor = LanguageMiniAppUiHelper.COLOR_ACCENT_DICTIONARY,
            onDismiss = { finish() }
        )

        val body = shell.cardContent

        // Top selected-word card
        val wordCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = LanguageMiniAppUiHelper.roundedBg(
                density,
                LanguageMiniAppUiHelper.COLOR_CARD_BG,
                LanguageMiniAppUiHelper.COLOR_ACCENT_DICTIONARY,
                12f
            )
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
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
        }
        wordHeaderView = wordText
        wordCard.addView(wordText)
        body.addView(wordCard)

        body.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, (12 * density).toInt())
        })

        // Scrollable definitions container bounded to fit comfortably inside the selection popup
        val maxScrollHeightPx = (resources.displayMetrics.heightPixels * 0.48f)
            .toInt()
            .coerceIn((180 * density).toInt(), (380 * density).toInt())

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

        // Wrap in a height-capped container view
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

        // Footer button to open shared Dictionary Settings
        val settingsBtn = LanguageMiniAppUiHelper.createPillButton(
            context = this,
            text = "Dictionary Settings",
            accentColor = LanguageMiniAppUiHelper.COLOR_TEXT_PRIMARY,
            filled = false,
            contentDesc = "Open Dictionary settings",
            tag = TAG_OPEN_SETTINGS_BUTTON
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

    private fun triggerLookup() {
        lookupJob?.cancel()
        val text = selectedText
        if (text.isNullOrBlank()) {
            wordHeaderView?.text = "No word selected"
            lastOutcome = DictionaryLookupOutcome.EmptyQuery
            renderEmptySelection()
            return
        }

        val primaryTerm = SelectionTextHelper.formatPrimaryTerm(text)
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

    private fun renderEmptySelection() {
        val container = meaningBodyContainer ?: return
        container.removeAllViews()
        container.addView(
            LanguageMiniAppUiHelper.createStatusNoticeCard(
                context = this,
                badgeText = "EMPTY SELECTION",
                titleText = "No text received",
                bodyText = "Select a word in an app and choose Meaning from the text selection menu."
            )
        )
    }

    private fun renderLoading(term: String) {
        val container = meaningBodyContainer ?: return
        container.removeAllViews()
        container.addView(
            LanguageMiniAppUiHelper.createStatusNoticeCard(
                context = this,
                badgeText = "LOOKING UP",
                titleText = "Searching offline dictionaries...",
                bodyText = "Looking up \"$term\" in your installed StarDict dictionaries."
            )
        )
    }

    private fun renderOutcome(term: String, outcome: DictionaryLookupOutcome) {
        val container = meaningBodyContainer ?: return
        val density = resources.displayMetrics.density
        container.removeAllViews()

        when (outcome) {
            is DictionaryLookupOutcome.Found -> {
                outcome.entries.forEachIndexed { idx, entry ->
                    if (idx > 0) {
                        container.addView(View(this).apply {
                            layoutParams = LinearLayout.LayoutParams(1, (8 * density).toInt())
                        })
                    }
                    container.addView(buildDefinitionCard(density, entry))
                }
            }
            is DictionaryLookupOutcome.NotFound -> {
                container.addView(
                    LanguageMiniAppUiHelper.createStatusNoticeCard(
                        context = this,
                        badgeText = "NO RESULT",
                        titleText = "No definition found for \"$term\"",
                        bodyText = "Searched ${outcome.searchedDictionaries} active offline ${if (outcome.searchedDictionaries == 1) "dictionary" else "dictionaries"}. Try selecting a base word or import more StarDict dictionaries in Settings."
                    )
                )
            }
            DictionaryLookupOutcome.NoDictionariesInstalled -> {
                container.addView(
                    LanguageMiniAppUiHelper.createStatusNoticeCard(
                        context = this,
                        badgeText = "NO DICTIONARY INSTALLED",
                        titleText = "Import an offline dictionary",
                        bodyText = "No StarDict dictionary is installed yet. Open Dictionary Settings to import a StarDict ZIP archive."
                    )
                )
            }
            DictionaryLookupOutcome.AllDictionariesDisabled -> {
                container.addView(
                    LanguageMiniAppUiHelper.createStatusNoticeCard(
                        context = this,
                        badgeText = "DICTIONARIES DISABLED",
                        titleText = "All installed dictionaries are turned off",
                        bodyText = "Enable at least one dictionary in Dictionary Settings to view definitions."
                    )
                )
            }
            is DictionaryLookupOutcome.Error -> {
                container.addView(
                    LanguageMiniAppUiHelper.createStatusNoticeCard(
                        context = this,
                        badgeText = "LOOKUP ERROR",
                        titleText = "Dictionary read failed",
                        bodyText = outcome.message
                    )
                )
            }
            DictionaryLookupOutcome.EmptyQuery -> renderEmptySelection()
        }
    }

    private fun buildDefinitionCard(density: Float, entry: DictionaryDefinitionEntry): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG_DEFINITION_CARD
            background = LanguageMiniAppUiHelper.roundedBg(
                density,
                LanguageMiniAppUiHelper.COLOR_CARD_BG,
                LanguageMiniAppUiHelper.COLOR_BORDER,
                10f
            )
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())

            addView(TextView(this@MeaningSelectionActivity).apply {
                text = entry.dictionaryName.uppercase()
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(LanguageMiniAppUiHelper.COLOR_ACCENT_DICTIONARY)
                letterSpacing = 0.05f
            })

            val headerLine = buildString {
                append(entry.headword)
                if (!entry.phonetic.isNullOrBlank()) {
                    append("  ")
                    append(entry.phonetic)
                }
            }
            addView(TextView(this@MeaningSelectionActivity).apply {
                text = headerLine
                textSize = 14.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_PRIMARY)
                setPadding(0, (3 * density).toInt(), 0, 0)
            })

            if (!entry.matchedSynonym.isNullOrBlank()) {
                addView(TextView(this@MeaningSelectionActivity).apply {
                    text = "Matched synonym: ${entry.matchedSynonym}"
                    textSize = 11f
                    setTextColor(LanguageMiniAppUiHelper.COLOR_WARNING_ACCENT)
                    setPadding(0, (2 * density).toInt(), 0, 0)
                })
            }

            if (entry.definitionText.isNotBlank()) {
                addView(TextView(this@MeaningSelectionActivity).apply {
                    text = entry.definitionText
                    textSize = 13f
                    setTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_SECONDARY)
                    setLineSpacing(0f, 1.3f)
                    setTextIsSelectable(true)
                    setPadding(0, (6 * density).toInt(), 0, 0)
                })
            }
        }
    }

    override fun onDestroy() {
        lookupJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val TAG_SELECTED_WORD = "meaning_popup_selected_word"
        const val TAG_MEANING_RESULT_CONTAINER = "meaning_popup_result_container"
        const val TAG_DEFINITION_CARD = "meaning_popup_definition_card"
        const val TAG_OPEN_SETTINGS_BUTTON = "meaning_popup_open_settings_button"

        /**
         * Shared lookup helper invoked by UI and testable in JVM integration tests.
         */
        internal fun performSharedLookup(service: DictionaryService, rawSelectedText: String?): DictionaryLookupOutcome {
            val normalized = SelectionTextHelper.normalize(rawSelectedText)
                ?: return DictionaryLookupOutcome.EmptyQuery
            return service.lookup(normalized)
        }
    }
}
