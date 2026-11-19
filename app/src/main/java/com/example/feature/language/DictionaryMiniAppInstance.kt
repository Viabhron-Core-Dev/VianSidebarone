package com.example.feature.language

import android.content.Context
import android.graphics.Typeface
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

/**
 * Standalone floating Dictionary mini-app UI shell (hosted in Main process).
 *
 * Provides search input, submit/clear actions, scrollable definition/result area, empty state,
 * honest unconfigured placeholder state, and Edit/Settings navigation to shared Dictionary &
 * Translation Settings. Does NOT perform fake lookups or bundle a dictionary database.
 */
class DictionaryMiniAppInstance(
    override val instanceId: String,
    override val appType: String = APP_TYPE,
    private val onRequestWindowFocusable: (Boolean) -> Unit = {},
    private val onOpenSettings: () -> Unit = {}
) : MiniAppInstance {

    override var lifecycleState: MiniAppLifecycleState = MiniAppLifecycleState.UNINITIALIZED
        private set

    private var currentQuery: String = ""
    private var lastSubmittedQuery: String = ""

    private var rootView: View? = null
    private var searchInput: EditText? = null
    private var resultContainer: LinearLayout? = null

    override fun onCreate(savedSnapshot: MiniAppRecoverySnapshot?) {
        lifecycleState = MiniAppLifecycleState.CREATED
        currentQuery = savedSnapshot?.payload?.get(KEY_QUERY) ?: ""
        lastSubmittedQuery = savedSnapshot?.payload?.get(KEY_SUBMITTED_QUERY) ?: ""
    }

    override fun restoreFromSnapshot(snapshot: MiniAppRecoverySnapshot) {
        currentQuery = snapshot.payload[KEY_QUERY] ?: ""
        lastSubmittedQuery = snapshot.payload[KEY_SUBMITTED_QUERY] ?: ""
        searchInput?.setText(currentQuery)
        rootView?.context?.let { renderResultArea(it) }
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

        renderResultArea(context)
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
        rootView?.context?.let { renderResultArea(it) }
    }

    fun clearSearch() {
        currentQuery = ""
        lastSubmittedQuery = ""
        searchInput?.setText("")
        rootView?.context?.let { renderResultArea(it) }
    }

    private fun renderResultArea(context: Context) {
        val container = resultContainer ?: return
        val density = context.resources.displayMetrics.density
        container.removeAllViews()

        if (lastSubmittedQuery.isBlank()) {
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
                    text = "Enter a word above and tap Search. Dictionary definitions will appear here once an offline dictionary is configured."
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
                    badgeText = "DICTIONARY STATUS",
                    titleText = "No dictionary configured yet",
                    bodyText = "Offline dictionary support (such as StarDict files) is not configured yet. Open Settings to inspect Dictionary & Translation configuration.",
                    actionLabel = "Open Settings",
                    actionTag = TAG_OPEN_SETTINGS_BUTTON,
                    onActionClick = onOpenSettings
                )
            )
        } else {
            val wordCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                tag = TAG_SUBMITTED_WORD_CARD
                background = LanguageMiniAppUiHelper.roundedBg(
                    density,
                    LanguageMiniAppUiHelper.COLOR_CARD_BG,
                    LanguageMiniAppUiHelper.COLOR_ACCENT_DICTIONARY,
                    10f
                )
                setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())

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
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(LanguageMiniAppUiHelper.COLOR_TEXT_PRIMARY)
                    setPadding(0, (4 * density).toInt(), 0, 0)
                })
            }
            container.addView(wordCard)
            container.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(1, (10 * density).toInt())
            })
            container.addView(
                LanguageMiniAppUiHelper.createStatusNoticeCard(
                    context = context,
                    badgeText = "NOT CONFIGURED",
                    titleText = "Dictionary lookup unavailable",
                    bodyText = "No offline dictionary source is installed yet, so no definition can be shown for \"$lastSubmittedQuery\". Configure dictionaries in Dictionary & Translation Settings when available.",
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
        val latestInput = searchInput?.text?.toString() ?: currentQuery
        currentQuery = latestInput
        return linkedMapOf(
            KEY_QUERY to currentQuery,
            KEY_SUBMITTED_QUERY to lastSubmittedQuery
        )
    }

    override fun onDestroy() {
        lifecycleState = MiniAppLifecycleState.DESTROYED
        onRequestWindowFocusable(false)
        searchInput = null
        resultContainer = null
        rootView = null
    }

    internal fun getCurrentQueryForTesting(): String = searchInput?.text?.toString() ?: currentQuery
    internal fun getLastSubmittedQueryForTesting(): String = lastSubmittedQuery

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
        const val TAG_SUBMITTED_WORD_CARD = "dictionary_submitted_word_card"
        const val TAG_SUBMITTED_WORD_TEXT = "dictionary_submitted_word_text"
        const val TAG_OPEN_SETTINGS_BUTTON = "dictionary_open_settings_button"
    }
}
