package com.example.feature.language

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.feature.floating.EmbeddedInputEditText
import com.example.feature.floating.MiniAppInstance
import com.example.feature.floating.MiniAppLifecycleState
import com.example.feature.floating.MiniAppRecoverySnapshot
import com.example.feature.language.dictionary.DictionaryDefinitionEntry
import com.example.feature.language.dictionary.DictionaryLookupOutcome
import com.example.feature.language.dictionary.DictionaryService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Standalone floating Dictionary mini-app (hosted in Main process).
 *
 * Connected to the shared offline [DictionaryService] (StarDict disk-backed engine):
 * - Preserves search input, Search/Clear controls, scrollable results area, Settings shortcut,
 *   and sleep/restore lifecycle.
 * - Executes all dictionary lookups off the main thread.
 * - Displays actual definitions with dictionary attribution, plus honest loading, empty,
 *   no-result, unconfigured, and error states.
 */
class DictionaryMiniAppInstance(
    override val instanceId: String,
    override val appType: String = APP_TYPE,
    private val onRequestWindowFocusable: (Boolean) -> Unit = {},
    private val onOpenSettings: () -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val postToUi: ((() -> Unit) -> Unit)? = null
) : MiniAppInstance {

    override var lifecycleState: MiniAppLifecycleState = MiniAppLifecycleState.UNINITIALIZED
        private set

    private var currentQuery: String = ""
    private var lastSubmittedQuery: String = ""
    private var isLoading: Boolean = false
    private var lastOutcome: DictionaryLookupOutcome = DictionaryLookupOutcome.EmptyQuery

    private var rootView: View? = null
    private var searchInput: EditText? = null
    private var resultContainer: LinearLayout? = null
    private var hostContext: Context? = null

    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var lookupJob: Job? = null

    override fun onCreate(savedSnapshot: MiniAppRecoverySnapshot?) {
        lifecycleState = MiniAppLifecycleState.CREATED
        currentQuery = savedSnapshot?.payload?.get(KEY_QUERY) ?: ""
        lastSubmittedQuery = savedSnapshot?.payload?.get(KEY_SUBMITTED_QUERY) ?: ""
    }

    override fun restoreFromSnapshot(snapshot: MiniAppRecoverySnapshot) {
        currentQuery = snapshot.payload[KEY_QUERY] ?: ""
        lastSubmittedQuery = snapshot.payload[KEY_SUBMITTED_QUERY] ?: ""
        searchInput?.setText(currentQuery)
        val ctx = hostContext ?: rootView?.context
        if (ctx != null) {
            if (lastSubmittedQuery.isNotBlank()) {
                executeLookupAsync(ctx, lastSubmittedQuery)
            } else {
                lastOutcome = DictionaryLookupOutcome.EmptyQuery
                renderResultArea(ctx)
            }
        }
    }

    override fun createContentView(context: Context): View {
        hostContext = context
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

        // Search row: Input + Search button + Clear button
        val searchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val input = EmbeddedInputEditText(context) { instanceId }.apply {
            tag = TAG_SEARCH_INPUT
            hint = "Enter word to look up"
            setHintTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_MUTED)
            setTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_PRIMARY)
            textSize = 13.5f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = LanguageMiniAppUiHelper.roundedBg(
                density,
                LanguageMiniAppUiHelper.COLOR_INPUT_BG,
                LanguageMiniAppUiHelper.COLOR_BORDER,
                8f
            )
            setPadding((10 * density).toInt(), (9 * density).toInt(), (10 * density).toInt(), (9 * density).toInt())
            setText(currentQuery)
            setSelection(currentQuery.length)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (6 * density).toInt()
            }
            setOnFocusChangeListener { _, hasFocus ->
                onRequestWindowFocusable(hasFocus)
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    submitSearch(text?.toString().orEmpty())
                    true
                } else {
                    false
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    currentQuery = s?.toString().orEmpty()
                }
            })
        }
        searchInput = input
        searchRow.addView(input)

        val searchBtn = LanguageMiniAppUiHelper.createPillButton(
            context = context,
            text = "Search",
            accentColor = LanguageMiniAppUiHelper.COLOR_ACCENT_DICTIONARY,
            filled = true,
            contentDesc = "Search dictionary",
            tag = TAG_SEARCH_BUTTON
        ) {
            submitSearch(searchInput?.text?.toString() ?: currentQuery)
        }
        searchRow.addView(searchBtn)

        val clearBtn = LanguageMiniAppUiHelper.createPillButton(
            context = context,
            text = "×",
            accentColor = LanguageMiniAppUiHelper.COLOR_TEXT_SECONDARY,
            filled = false,
            contentDesc = "Clear dictionary query",
            tag = TAG_CLEAR_BUTTON
        ) {
            clearSearch()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (6 * density).toInt()
            }
        }
        searchRow.addView(clearBtn)
        content.addView(searchRow)

        // Spacer
        content.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, (12 * density).toInt())
        })

        // Definition / Result container
        val results = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG_RESULT_CONTAINER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        resultContainer = results
        content.addView(results)

        scrollView.addView(content)
        rootView = scrollView

        if (lastSubmittedQuery.isNotBlank()) {
            executeLookupAsync(context, lastSubmittedQuery)
        } else {
            renderResultArea(context)
        }
        return scrollView
    }

    fun submitSearch(rawQuery: String) {
        val trimmed = rawQuery.trim()
        currentQuery = rawQuery
        lastSubmittedQuery = trimmed
        searchInput?.let { input ->
            if (input.text?.toString() != rawQuery) {
                input.setText(rawQuery)
                input.setSelection(rawQuery.length)
            }
        }
        val ctx = hostContext ?: rootView?.context ?: return
        if (trimmed.isEmpty()) {
            lookupJob?.cancel()
            isLoading = false
            lastOutcome = DictionaryLookupOutcome.EmptyQuery
            renderResultArea(ctx)
        } else {
            executeLookupAsync(ctx, trimmed)
        }
    }

    fun clearSearch() {
        lookupJob?.cancel()
        isLoading = false
        currentQuery = ""
        lastSubmittedQuery = ""
        lastOutcome = DictionaryLookupOutcome.EmptyQuery
        searchInput?.setText("")
        (hostContext ?: rootView?.context)?.let { renderResultArea(it) }
    }

    /**
     * Re-reads shared [DictionaryService] configuration and refreshes active lookup or status.
     */
    fun refreshFromService() {
        val ctx = hostContext ?: rootView?.context ?: return
        if (lastSubmittedQuery.isNotBlank()) {
            executeLookupAsync(ctx, lastSubmittedQuery)
        } else {
            renderResultArea(ctx)
        }
    }

    private fun executeLookupAsync(context: Context, query: String) {
        lookupJob?.cancel()
        isLoading = true
        renderResultArea(context)

        val service = DictionaryService.getInstance(context)
        lookupJob = scope.launch {
            val outcome = service.lookup(query)
            dispatchUi {
                if (lifecycleState == MiniAppLifecycleState.DESTROYED) return@dispatchUi
                if (lastSubmittedQuery != query) return@dispatchUi
                isLoading = false
                lastOutcome = outcome
                (hostContext ?: rootView?.context)?.let { renderResultArea(it) }
            }
        }
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

    private fun renderResultArea(context: Context) {
        val container = resultContainer ?: return
        val density = context.resources.displayMetrics.density
        container.removeAllViews()

        if (isLoading) {
            container.addView(
                LanguageMiniAppUiHelper.createStatusNoticeCard(
                    context = context,
                    badgeText = "SEARCHING OFFLINE DICTIONARIES",
                    titleText = "Looking up \"$lastSubmittedQuery\"...",
                    bodyText = "Reading disk-backed StarDict indexes..."
                ).apply { tag = TAG_LOADING_STATE }
            )
            return
        }

        if (lastSubmittedQuery.isBlank()) {
            val service = DictionaryService.getInstance(context)
            val installed = service.getInstalledDictionaries()
            val enabledCount = installed.count { it.enabled }

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
                    text = "Ready to look up a word"
                    textSize = 13.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_PRIMARY)
                })
                addView(TextView(context).apply {
                    text = if (enabledCount > 0) {
                        "$enabledCount offline ${if (enabledCount == 1) "dictionary" else "dictionaries"} active. Enter a word above and tap Search."
                    } else if (installed.isNotEmpty()) {
                        "${installed.size} installed ${if (installed.size == 1) "dictionary is" else "dictionaries are"} currently disabled. Enable one in Settings."
                    } else {
                        "Import a StarDict (.zip) dictionary in Settings to look up definitions offline."
                    }
                    textSize = 12f
                    setTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_SECONDARY)
                    setLineSpacing(0f, 1.25f)
                    setPadding(0, (6 * density).toInt(), 0, 0)
                })
            }
            container.addView(emptyCard)

            if (enabledCount == 0) {
                container.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(1, (10 * density).toInt())
                })
                container.addView(
                    LanguageMiniAppUiHelper.createStatusNoticeCard(
                        context = context,
                        badgeText = "DICTIONARY STATUS",
                        titleText = if (installed.isEmpty()) "No offline dictionary installed" else "All dictionaries disabled",
                        bodyText = if (installed.isEmpty()) {
                            "Import a StarDict ZIP archive (.ifo, .idx/.idx.gz, .dict/.dict.dz) in Dictionary Settings."
                        } else {
                            "Enable at least one installed dictionary in Dictionary Settings to look up words."
                        },
                        actionLabel = "Open Settings",
                        actionTag = TAG_OPEN_SETTINGS_BUTTON,
                        onActionClick = onOpenSettings
                    )
                )
            }
            return
        }

        // Submitted word header card
        val wordCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG_SUBMITTED_WORD_CARD
            background = LanguageMiniAppUiHelper.roundedBg(
                density,
                LanguageMiniAppUiHelper.COLOR_CARD_BG,
                LanguageMiniAppUiHelper.COLOR_ACCENT_DICTIONARY,
                10f
            )
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())

            addView(TextView(context).apply {
                text = "SEARCHED TERM"
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(LanguageMiniAppUiHelper.COLOR_ACCENT_DICTIONARY)
                letterSpacing = 0.06f
            })
            addView(TextView(context).apply {
                tag = TAG_SUBMITTED_WORD_TEXT
                text = lastSubmittedQuery
                textSize = 17.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_PRIMARY)
                setPadding(0, (3 * density).toInt(), 0, 0)
            })
        }
        container.addView(wordCard)
        container.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, (10 * density).toInt())
        })

        when (val outcome = lastOutcome) {
            is DictionaryLookupOutcome.Found -> {
                outcome.entries.forEachIndexed { idx, entry ->
                    if (idx > 0) {
                        container.addView(View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(1, (8 * density).toInt())
                        })
                    }
                    container.addView(buildDefinitionCard(context, density, entry))
                }
            }
            is DictionaryLookupOutcome.NotFound -> {
                container.addView(
                    LanguageMiniAppUiHelper.createStatusNoticeCard(
                        context = context,
                        badgeText = "NO RESULT",
                        titleText = "No definition found for \"${outcome.query}\"",
                        bodyText = "Searched ${outcome.searchedDictionaries} active offline ${if (outcome.searchedDictionaries == 1) "dictionary" else "dictionaries"}. Check spelling or import additional StarDict dictionaries in Settings.",
                        actionLabel = "Open Settings",
                        actionTag = TAG_OPEN_SETTINGS_BUTTON,
                        onActionClick = onOpenSettings
                    ).apply { tag = TAG_NO_RESULT_STATE }
                )
            }
            is DictionaryLookupOutcome.NoDictionariesInstalled -> {
                container.addView(
                    LanguageMiniAppUiHelper.createStatusNoticeCard(
                        context = context,
                        badgeText = "NO DICTIONARY INSTALLED",
                        titleText = "Import an offline dictionary",
                        bodyText = "No StarDict dictionary is installed yet, so no definition can be shown for \"$lastSubmittedQuery\". Import a StarDict ZIP package in Dictionary & Translation Settings.",
                        actionLabel = "Open Settings",
                        actionTag = TAG_OPEN_SETTINGS_BUTTON,
                        onActionClick = onOpenSettings
                    )
                )
            }
            is DictionaryLookupOutcome.AllDictionariesDisabled -> {
                container.addView(
                    LanguageMiniAppUiHelper.createStatusNoticeCard(
                        context = context,
                        badgeText = "DICTIONARIES DISABLED",
                        titleText = "All installed dictionaries are turned off",
                        bodyText = "Enable one or more installed dictionaries in Dictionary & Translation Settings to search for \"$lastSubmittedQuery\".",
                        actionLabel = "Open Settings",
                        actionTag = TAG_OPEN_SETTINGS_BUTTON,
                        onActionClick = onOpenSettings
                    )
                )
            }
            is DictionaryLookupOutcome.Error -> {
                container.addView(
                    LanguageMiniAppUiHelper.createStatusNoticeCard(
                        context = context,
                        badgeText = "LOOKUP ERROR",
                        titleText = "Could not read dictionary entry",
                        bodyText = outcome.message,
                        actionLabel = "Open Settings",
                        actionTag = TAG_OPEN_SETTINGS_BUTTON,
                        onActionClick = onOpenSettings
                    )
                )
            }
            DictionaryLookupOutcome.EmptyQuery -> Unit
        }
    }

    private fun buildDefinitionCard(
        context: Context,
        density: Float,
        entry: DictionaryDefinitionEntry
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG_DEFINITION_CARD
            background = LanguageMiniAppUiHelper.roundedBg(
                density,
                LanguageMiniAppUiHelper.COLOR_CARD_BG,
                LanguageMiniAppUiHelper.COLOR_BORDER,
                10f
            )
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())

            // Dictionary attribution badge
            addView(TextView(context).apply {
                text = entry.dictionaryName.uppercase()
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(LanguageMiniAppUiHelper.COLOR_ACCENT_DICTIONARY)
                letterSpacing = 0.05f
            })

            // Headword + phonetic
            val titleLine = buildString {
                append(entry.headword)
                if (!entry.phonetic.isNullOrBlank()) {
                    append("  ")
                    append(entry.phonetic)
                }
            }
            addView(TextView(context).apply {
                text = titleLine
                textSize = 14.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_PRIMARY)
                setPadding(0, (4 * density).toInt(), 0, 0)
            })

            if (!entry.matchedSynonym.isNullOrBlank()) {
                addView(TextView(context).apply {
                    text = "Matched synonym: ${entry.matchedSynonym}"
                    textSize = 11f
                    setTextColor(LanguageMiniAppUiHelper.COLOR_WARNING_ACCENT)
                    setPadding(0, (2 * density).toInt(), 0, 0)
                })
            }

            if (entry.definitionText.isNotBlank()) {
                addView(TextView(context).apply {
                    text = entry.definitionText
                    textSize = 12.5f
                    setTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_SECONDARY)
                    setLineSpacing(0f, 1.3f)
                    setTextIsSelectable(true)
                    setPadding(0, (6 * density).toInt(), 0, 0)
                })
            }
        }
    }

    override fun onStart() {
        lifecycleState = MiniAppLifecycleState.STARTED
    }

    override fun onResume() {
        lifecycleState = MiniAppLifecycleState.RESUMED
        refreshFromService()
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
        val latestInput = searchInput?.text?.toString() ?: currentQuery
        currentQuery = latestInput
        return linkedMapOf(
            KEY_QUERY to currentQuery,
            KEY_SUBMITTED_QUERY to lastSubmittedQuery
        )
    }

    override fun onDestroy() {
        lifecycleState = MiniAppLifecycleState.DESTROYED
        lookupJob?.cancel()
        scope.cancel()
        onRequestWindowFocusable(false)
        searchInput = null
        resultContainer = null
        rootView = null
        hostContext = null
    }

    internal fun getCurrentQueryForTesting(): String = searchInput?.text?.toString() ?: currentQuery
    internal fun getLastSubmittedQueryForTesting(): String = lastSubmittedQuery
    internal fun getLastOutcomeForTesting(): DictionaryLookupOutcome = lastOutcome

    companion object {
        const val APP_TYPE = "dictionary"
        const val KEY_QUERY = "dictionary_query"
        const val KEY_SUBMITTED_QUERY = "dictionary_submitted_query"

        const val TAG_ROOT = "dictionary_miniapp_root"
        const val TAG_SEARCH_INPUT = "dictionary_search_input"
        const val TAG_SEARCH_BUTTON = "dictionary_search_button"
        const val TAG_CLEAR_BUTTON = "dictionary_clear_button"
        const val TAG_RESULT_CONTAINER = "dictionary_result_container"
        const val TAG_EMPTY_STATE = "dictionary_empty_state"
        const val TAG_LOADING_STATE = "dictionary_loading_state"
        const val TAG_NO_RESULT_STATE = "dictionary_no_result_state"
        const val TAG_DEFINITION_CARD = "dictionary_definition_card"
        const val TAG_SUBMITTED_WORD_CARD = "dictionary_submitted_word_card"
        const val TAG_SUBMITTED_WORD_TEXT = "dictionary_submitted_word_text"
        const val TAG_OPEN_SETTINGS_BUTTON = "dictionary_open_settings_button"
    }
}
