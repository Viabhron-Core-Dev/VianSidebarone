package com.example.feature.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.core.LogKeeper
import com.example.data.ChapterMeta
import com.example.data.EpubBook
import com.example.data.EpubParser
import com.example.data.HighWaterProgressUpdate
import com.example.data.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * ReaderReadingView: The Main-process reading screen inside the floating Reader mini-app.
 *
 * Implements:
 * 1. Virtualized TOC chapter list (`RecyclerView`, 1 line per chapter, `"${index + 1}. ${title}"`,
 *    `maxLines = 1`, `ellipsize = END`, current chapter highlighted).
 * 2. Strict separation between Reader resume position (`EpubBook.lastReadChapter`/`lastReadScrollY`)
 *    and Tracker high-water progress (`TrackerBook.readChapters`).
 * 3. Accidental jump protection: TOC jumps, search result jumps, and initial/restored chapter loads
 *    update ONLY the resume position and never advance Tracker high-water progress.
 * 4. Genuine reading activity detection + Undo banner when high-water progress increases.
 * 5. Rolling 21-chapter cache window maintenance across chapter transitions, auto-scroll, and TTS.
 */
class ReaderReadingView(
    context: Context,
    private val instanceId: String? = null,
    private val onRequestWindowFocusable: ((Boolean) -> Unit)? = null,
    private val onActiveBookChanged: ((EpubBook?) -> Unit)? = null,
    private val onBackToLibrary: () -> Unit
) : FrameLayout(context), TextToSpeech.OnInitListener {

    private val appContext: Context = context

    private val density: Float = (context.resources.displayMetrics.density).takeIf { it > 0f } ?: 2f
    private fun dp(v: Int): Int = (v * density).toInt()
    private fun dpF(v: Float): Float = v * density

    private val viewScope = CoroutineScope(SupervisorJob() + ReaderDispatchers.main)
    private val repository = LibraryRepository(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    var currentBook: EpubBook? = null
        private set
    var currentChapterIndex: Int = 0
        private set
    var currentScrollY: Int = 0
        private set
    var currentFontSizeSp: Float = ReaderPreferences.getDefaultFontSizeSp(context)
        private set
    private var currentLineSpacingMult: Float = ReaderPreferences.getLineSpacingMultiplier(context)
    private var currentThemePreset: String = ReaderPreferences.getThemePreset(context)

    private var chaptersList: List<ChapterMeta> = emptyList()
    private var rawChapterText: String = ""

    // Genuine reading activity tracking for the currently displayed chapter
    private var isProgrammaticScrollOrLoad = false
    private var hasGenuineReadingInCurrentChapter = false
    private var userTouchDownInReadingView = false
    private var initialChapterLoadScrollY = 0
    private var chapterOpenedAtMs: Long = 0L
    internal var clockMsForTesting: (() -> Long)? = null
    private var lastHighWaterUpdate: HighWaterProgressUpdate? = null
    private var undoBannerHideJob: Job? = null

    private fun nowMs(): Long = clockMsForTesting?.invoke() ?: System.currentTimeMillis()

    private fun getScrollableRangePx(): Int {
        val child = readingScrollView.getChildAt(0) ?: return 0
        val viewH = readingScrollView.height
        if (viewH <= 0 || child.height <= 0) return 0
        return (child.height - viewH).coerceAtLeast(0)
    }

    // UI elements
    private val mainLayout: LinearLayout
    private val topBarLayout: LinearLayout
    private val chapterTitleTopView: TextView
    private val chapterHeadingView: TextView
    private val readingContentTextView: TextView
    private val readingScrollView: ScrollView
    private val prevChapterButton: Button
    private val nextChapterButton: Button
    private val bottomBarLayout: LinearLayout
    private val bottomProgressTextView: TextView
    private val bottomProgressBar: ProgressBar

    // High-Water Progress Undo Banner
    private val undoBannerLayout: LinearLayout
    private val undoBannerTextView: TextView
    private val undoBannerActionButton: Button

    // Tool panels
    private val searchBarLayout: LinearLayout
    private val searchEditText: com.example.feature.floating.EmbeddedInputEditText
    private val searchCountView: TextView
    private val fontSizeBarLayout: LinearLayout
    private val fontSizeTextView: TextView
    private val autoScrollBarLayout: LinearLayout
    private val autoScrollPlayPauseButton: Button
    private val autoScrollSpeedButton: Button
    private val ttsBarLayout: LinearLayout
    private val ttsPlayPauseButton: Button
    private val ttsStatusView: TextView

    // Virtualized TOC overlay
    private val tocOverlayLayout: FrameLayout
    private val tocRecyclerView: RecyclerView?
    private val tocAdapter: TocChapterAdapter

    // Search in book overlay
    private val searchBookOverlayLayout: FrameLayout
    private val searchBookItemsContainer: LinearLayout

    // Auto-scroll state
    private var isAutoScrollRunning = false
    private var autoScrollSpeedMultiplier = ReaderPreferences.getAutoScrollSpeed(context)
    private var autoScrollPixelsAccumulated = 0
    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (isAutoScrollRunning) {
                val step = (2 * autoScrollSpeedMultiplier).toInt().coerceAtLeast(1)
                readingScrollView.smoothScrollBy(0, step)
                autoScrollPixelsAccumulated += step
                val scrollableRange = getScrollableRangePx()
                val autoScrollCompletedShort = scrollableRange in 1 until dp(90) &&
                    readingScrollView.scrollY >= (scrollableRange * 0.70f).toInt().coerceAtLeast(1)
                if ((autoScrollPixelsAccumulated >= dp(90) || autoScrollCompletedShort) && !hasGenuineReadingInCurrentChapter) {
                    recordGenuineReadingActivityOnCurrentChapter(currentChapterIndex)
                }
                mainHandler.postDelayed(this, 40L)
            }
        }
    }

    // TTS state
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false
    private var isTtsSpeaking = false
    private var ttsParagraphs = listOf<String>()
    private var ttsCurrentParagraphIndex = 0

    // Search match state
    private val searchMatches = mutableListOf<Int>()
    private var currentSearchMatchIndex = -1

    // Debounced scroll persistence & lifecycle job tracking
    private var scrollSaveJob: Job? = null
    private var openChapterJob: Job? = null
    private var highWaterJob: Job? = null
    private var undoJob: Job? = null

    private val settingsListener = {
        applyReaderPreferences()
    }

    fun refreshPreferences() {
        applyReaderPreferences()
    }

    private val libraryChangedListener = {
        val bookId = currentBook?.id
        if (bookId != null && bookId > 0) {
            viewScope.launch {
                val refreshed = repository.getBook(bookId)
                if (refreshed != null) {
                    currentBook = refreshed
                    onActiveBookChanged?.invoke(refreshed)
                }
            }
        }
    }

    init {
        setBackgroundColor(Color.parseColor("#181825"))

        mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        // --- Top Navigation & Action Bar ---
        topBarLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E2E"))
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

        val backButton = Button(context).apply {
            text = "← Library"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#89B4FA"))
            minHeight = dp(38)
            minimumHeight = dp(38)
            minWidth = dp(48)
            minimumWidth = dp(48)
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dpF(8f)
            }
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setOnClickListener {
                clearSearchFocus()
                saveCurrentProgressImmediately()
                stopAutoScroll()
                stopTts()
                onActiveBookChanged?.invoke(null)
                onBackToLibrary()
            }
        }
        topBarLayout.addView(backButton)

        chapterTitleTopView = TextView(context).apply {
            text = "Chapter"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#CDD6F4"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        }
        topBarLayout.addView(chapterTitleTopView)

        fun createActionButton(label: String, onClick: () -> Unit): Button {
            return Button(context).apply {
                text = label
                textSize = 12f
                setTextColor(Color.parseColor("#CDD6F4"))
                minWidth = dp(38)
                minimumWidth = dp(38)
                minHeight = dp(38)
                minimumHeight = dp(38)
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dpF(8f)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(38)
                ).apply {
                    marginStart = dp(2)
                }
                setPadding(dp(5), dp(2), dp(5), dp(2))
                setOnClickListener { onClick() }
            }
        }

        topBarLayout.addView(createActionButton("🔍") { toggleSearchBar() })
        topBarLayout.addView(createActionButton("A") { toggleFontSizeBar() })
        topBarLayout.addView(createActionButton("▼") { toggleAutoScrollBar() })
        topBarLayout.addView(createActionButton("🔊") { toggleTtsBar() })
        topBarLayout.addView(createActionButton("☰") { toggleTocOverlay() })

        mainLayout.addView(topBarLayout)

        // --- High-Water Progress Undo Banner ---
        undoBannerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#313244"))
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }

        undoBannerTextView = TextView(context).apply {
            text = "Tracker progress updated"
            textSize = 11f
            setTextColor(Color.parseColor("#A6E3A1"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        undoBannerLayout.addView(undoBannerTextView)

        undoBannerActionButton = Button(context).apply {
            text = "Undo"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#11111B"))
            minHeight = dp(28)
            minimumHeight = dp(28)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F9E2AF"))
                cornerRadius = dpF(6f)
            }
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener {
                performHighWaterProgressUndo()
            }
        }
        undoBannerLayout.addView(undoBannerActionButton)
        mainLayout.addView(undoBannerLayout)

        // --- Search Bar Panel ---
        searchBarLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#252538"))
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        searchEditText = com.example.feature.floating.EmbeddedInputEditText(context) { instanceId }.apply {
            hint = "Search chapter..."
            setHintTextColor(Color.parseColor("#6C7086"))
            setTextColor(Color.WHITE)
            textSize = 13f
            isSingleLine = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#313244"))
                cornerRadius = dpF(8f)
            }
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onRequestWindowFocusable?.invoke(true)
                    v.post {
                        v.requestFocus()
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
                false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    performChapterSearch(s?.toString() ?: "")
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        searchBarLayout.addView(searchEditText)

        searchCountView = TextView(context).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.parseColor("#A6ADC8"))
            setPadding(8, 0, 8, 0)
        }
        searchBarLayout.addView(searchCountView)

        val prevMatchButton = createActionButton("▲") { navigateSearchMatch(-1) }
        val nextMatchButton = createActionButton("▼") { navigateSearchMatch(1) }
        val searchBookButton = createActionButton("In Book") { openSearchInBookDialog() }
        val closeSearchButton = createActionButton("✕") { toggleSearchBar() }

        searchBarLayout.addView(prevMatchButton)
        searchBarLayout.addView(nextMatchButton)
        searchBarLayout.addView(searchBookButton)
        searchBarLayout.addView(closeSearchButton)
        mainLayout.addView(searchBarLayout)

        // --- Font Size Bar Panel ---
        fontSizeBarLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#252538"))
            setPadding(16, 8, 16, 8)
        }

        val decFontButton = Button(context).apply {
            text = "A -"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#313244"))
                cornerRadius = 8f
            }
            setOnClickListener {
                if (currentFontSizeSp > 12f) {
                    currentFontSizeSp -= 2f
                    ReaderPreferences.setDefaultFontSizeSp(context, currentFontSizeSp)
                    applyFontSize()
                }
            }
        }
        fontSizeBarLayout.addView(decFontButton)

        fontSizeTextView = TextView(context).apply {
            text = "${currentFontSizeSp.toInt()}sp"
            textSize = 13f
            setTextColor(Color.parseColor("#CDD6F4"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fontSizeBarLayout.addView(fontSizeTextView)

        val incFontButton = Button(context).apply {
            text = "A +"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#313244"))
                cornerRadius = 8f
            }
            setOnClickListener {
                if (currentFontSizeSp < 28f) {
                    currentFontSizeSp += 2f
                    ReaderPreferences.setDefaultFontSizeSp(context, currentFontSizeSp)
                    applyFontSize()
                }
            }
        }
        fontSizeBarLayout.addView(incFontButton)

        fontSizeBarLayout.addView(createActionButton("✕") { toggleFontSizeBar() })
        mainLayout.addView(fontSizeBarLayout)

        // --- Auto-Scroll Bar Panel ---
        autoScrollBarLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#252538"))
            setPadding(16, 8, 16, 8)
        }

        autoScrollPlayPauseButton = Button(context).apply {
            text = "▶ Play"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#89B4FA"))
                cornerRadius = 8f
            }
            setOnClickListener {
                toggleAutoScrollPlayPause()
            }
        }
        autoScrollBarLayout.addView(autoScrollPlayPauseButton)

        autoScrollSpeedButton = Button(context).apply {
            text = "Speed: ${autoScrollSpeedMultiplier}x"
            textSize = 12f
            setTextColor(Color.parseColor("#CDD6F4"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#313244"))
                cornerRadius = 8f
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = 12
            }
            setOnClickListener {
                cycleAutoScrollSpeed()
            }
        }
        autoScrollBarLayout.addView(autoScrollSpeedButton)

        val autoScrollSpacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
        autoScrollBarLayout.addView(autoScrollSpacer)
        autoScrollBarLayout.addView(createActionButton("✕") { toggleAutoScrollBar() })
        mainLayout.addView(autoScrollBarLayout)

        // --- Text-to-Speech Bar Panel ---
        ttsBarLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#252538"))
            setPadding(16, 8, 16, 8)
        }

        ttsPlayPauseButton = Button(context).apply {
            text = "▶ Speak"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#A6E3A1"))
                cornerRadius = 8f
            }
            setOnClickListener {
                toggleTtsPlayPause()
            }
        }
        ttsBarLayout.addView(ttsPlayPauseButton)

        val ttsStopButton = Button(context).apply {
            text = "⏹ Stop"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F38BA8"))
                cornerRadius = 8f
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = 8
            }
            setOnClickListener {
                stopTts()
            }
        }
        ttsBarLayout.addView(ttsStopButton)

        ttsStatusView = TextView(context).apply {
            text = "TTS Ready"
            textSize = 11f
            setTextColor(Color.parseColor("#A6ADC8"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 8
            }
        }
        ttsBarLayout.addView(ttsStatusView)
        ttsBarLayout.addView(createActionButton("✕") { toggleTtsBar() })
        mainLayout.addView(ttsBarLayout)

        // --- Scrollable Reading Content ---
        readingScrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_MOVE) {
                    userTouchDownInReadingView = true
                }
                false
            }
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                currentScrollY = scrollY
                if (!hasGenuineReadingInCurrentChapter &&
                    ReaderProgressPolicy.isScrollActivityMeaningful(
                        scrollY = scrollY,
                        initialScrollY = initialChapterLoadScrollY,
                        scrollableRangePx = getScrollableRangePx(),
                        scrollThresholdPx = dp(80),
                        userTouched = userTouchDownInReadingView,
                        isProgrammatic = isProgrammaticScrollOrLoad
                    )
                ) {
                    recordGenuineReadingActivityOnCurrentChapter(currentChapterIndex)
                }
                scheduleDebouncedProgressSave()
            }
        }

        val contentInnerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(28))
        }

        chapterHeadingView = TextView(context).apply {
            text = "Chapter"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#89B4FA"))
            setPadding(0, dp(4), 0, dp(10))
        }
        contentInnerLayout.addView(chapterHeadingView)

        readingContentTextView = TextView(context).apply {
            setTextIsSelectable(true)
            textSize = currentFontSizeSp
            setTextColor(Color.parseColor("#CDD6F4"))
            setLineSpacing(0f, currentLineSpacingMult)
        }
        contentInnerLayout.addView(readingContentTextView)

        readingScrollView.addView(contentInnerLayout)
        mainLayout.addView(readingScrollView)

        // --- Bottom Chapter Navigation Bar (with clearance for bottom-right [ ×  —  ↘ ] sticker) ---
        bottomBarLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E2E"))
            setPadding(dp(8), dp(4), dp(8), dp(42))
        }

        bottomProgressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressDrawable?.setColorFilter(Color.parseColor("#89B4FA"), android.graphics.PorterDuff.Mode.SRC_IN)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(3).coerceAtLeast(3)
            ).apply {
                bottomMargin = dp(4)
            }
        }
        bottomBarLayout.addView(bottomProgressBar)

        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        prevChapterButton = Button(context).apply {
            text = "◀ Prev"
            textSize = 12f
            setTextColor(Color.WHITE)
            minHeight = dp(38)
            minimumHeight = dp(38)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#313244"))
                cornerRadius = dpF(8f)
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                if (currentChapterIndex > 0) {
                    openChapter(currentChapterIndex - 1, initialScrollY = 0, isDirectJump = false)
                }
            }
        }
        bottomRow.addView(prevChapterButton)

        bottomProgressTextView = TextView(context).apply {
            text = "Chapter 1 of 1 • 0%"
            textSize = 11f
            setTextColor(Color.parseColor("#A6ADC8"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bottomRow.addView(bottomProgressTextView)

        nextChapterButton = Button(context).apply {
            text = "Next ▶"
            textSize = 12f
            setTextColor(Color.WHITE)
            minHeight = dp(38)
            minimumHeight = dp(38)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#313244"))
                cornerRadius = dpF(8f)
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                advanceToNextChapterByUser()
            }
        }
        bottomRow.addView(nextChapterButton)

        bottomBarLayout.addView(bottomRow)
        mainLayout.addView(bottomBarLayout)

        addView(mainLayout)

        // --- Virtualized TOC Overlay Drawer ---
        tocOverlayLayout = FrameLayout(context).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#E6181825"))
            isClickable = true
            isFocusable = true
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setOnClickListener {
                visibility = View.GONE
            }
        }

        val tocCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E2E"))
                cornerRadius = dpF(12f)
                setStroke(1, Color.parseColor("#313244"))
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                setMargins(dp(12), dp(12), dp(12), dp(46))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val tocHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }

        val tocTitle = TextView(context).apply {
            text = "Table of Contents"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#CDD6F4"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tocHeader.addView(tocTitle)

        val tocClose = Button(context).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.parseColor("#A6ADC8"))
            minWidth = dp(40)
            minimumWidth = dp(40)
            minHeight = dp(40)
            minimumHeight = dp(40)
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dpF(8f)
            }
            setOnClickListener {
                tocOverlayLayout.visibility = View.GONE
            }
        }
        tocHeader.addView(tocClose)
        tocCard.addView(tocHeader)

        tocAdapter = TocChapterAdapter { selectedChapter ->
            tocOverlayLayout.visibility = View.GONE
            // Jumping via TOC updates ONLY Reader resume position — never advances Tracker high-water progress!
            openChapter(selectedChapter.index, initialScrollY = 0, isDirectJump = true)
        }

        tocRecyclerView = try {
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                adapter = tocAdapter
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }.also { tocCard.addView(it) }
        } catch (_: Throwable) {
            null
        }

        tocOverlayLayout.addView(tocCard)
        addView(tocOverlayLayout)

        // --- Search In Book Overlay ---
        searchBookOverlayLayout = FrameLayout(context).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#E6181825"))
            isClickable = true
            isFocusable = true
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setOnClickListener {
                visibility = View.GONE
            }
        }

        val searchBookCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E2E"))
                cornerRadius = dpF(12f)
                setStroke(1, Color.parseColor("#313244"))
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                setMargins(dp(12), dp(12), dp(12), dp(46))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val searchBookHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 6, 8, 10)
        }

        val searchBookTitle = TextView(context).apply {
            text = "Search In Book"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#CDD6F4"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        searchBookHeader.addView(searchBookTitle)

        val searchBookClose = Button(context).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.parseColor("#A6ADC8"))
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = 8f
            }
            setOnClickListener {
                searchBookOverlayLayout.visibility = View.GONE
            }
        }
        searchBookHeader.addView(searchBookClose)
        searchBookCard.addView(searchBookHeader)

        val searchBookScrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        searchBookItemsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        searchBookScrollView.addView(searchBookItemsContainer)
        searchBookCard.addView(searchBookScrollView)

        searchBookOverlayLayout.addView(searchBookCard)
        addView(searchBookOverlayLayout)

        ReaderEventManager.registerSettingsListener(settingsListener)
        ReaderEventManager.registerListener(libraryChangedListener)
        applyReaderPreferences()

        // Initialize TTS
        try {
            textToSpeech = TextToSpeech(context, this)
        } catch (e: Exception) {
            LogKeeper.log(context, "ReaderReadingView", "TTS init exception: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsInitialized = true
            textToSpeech?.language = Locale.getDefault()
            textToSpeech?.setSpeechRate(ReaderPreferences.getTtsSpeechRate(appContext))
            ttsStatusView.text = "TTS Ready"
        } else {
            ttsStatusView.text = "TTS Unavailable"
        }
    }

    private fun applyReaderPreferences() {
        currentLineSpacingMult = ReaderPreferences.getLineSpacingMultiplier(appContext)
        currentThemePreset = ReaderPreferences.getThemePreset(appContext)
        autoScrollSpeedMultiplier = ReaderPreferences.getAutoScrollSpeed(appContext)
        autoScrollSpeedButton.text = "Speed: ${autoScrollSpeedMultiplier}x"
        textToSpeech?.setSpeechRate(ReaderPreferences.getTtsSpeechRate(appContext))

        val palette = ReaderPreferences.getPalette(currentThemePreset)
        setBackgroundColor(palette.backgroundColor)
        readingScrollView.setBackgroundColor(palette.backgroundColor)
        topBarLayout.setBackgroundColor(palette.surfaceColor)
        bottomBarLayout.setBackgroundColor(palette.surfaceColor)
        readingContentTextView.setTextColor(palette.textColor)
        readingContentTextView.setLineSpacing(0f, currentLineSpacingMult)
        chapterHeadingView.setTextColor(palette.accentColor)
        chapterTitleTopView.setTextColor(palette.textColor)
        bottomProgressTextView.setTextColor(palette.secondaryTextColor)
    }

    private fun clearSearchFocus() {
        searchEditText.clearFocus()
        onRequestWindowFocusable?.invoke(false)
    }

    /**
     * Opens a book at its saved resume position (or explicit [initialChapter] / [initialScrollY]).
     * Restoration or initial book load NEVER counts as new high-water Tracker progress.
     */
    fun loadBook(
        book: EpubBook,
        initialChapter: Int? = null,
        initialScrollY: Int? = null,
        fontSizeSp: Float? = null
    ) {
        currentBook = book
        onActiveBookChanged?.invoke(book)
        if (fontSizeSp != null && fontSizeSp in 12f..28f) {
            currentFontSizeSp = fontSizeSp
        } else {
            currentFontSizeSp = ReaderPreferences.getDefaultFontSizeSp(appContext)
        }
        applyFontSize()
        applyReaderPreferences()

        viewScope.launch {
            chaptersList = repository.getChapters(book.id)
            val chapterIdx = initialChapter ?: book.lastReadChapter
            val scrollY = initialScrollY ?: book.lastReadScrollY
            val clampedIdx = chapterIdx.coerceIn(0, (chaptersList.size - 1).coerceAtLeast(0))
            openChapter(clampedIdx, scrollY, isDirectJump = false, isInitialRestore = true)
        }
    }

    /**
     * Loads and displays [chapterIndex].
     *
     * - Updates the 21-chapter rolling cache window around [chapterIndex].
     * - Saves ONLY the Reader resume position (`EpubBook.lastReadChapter`, `lastReadScrollY`).
     * - Does NOT advance `TrackerBook.readChapters` merely because the chapter was displayed!
     */
    fun openChapter(
        chapterIndex: Int,
        initialScrollY: Int = 0,
        isDirectJump: Boolean = false,
        isInitialRestore: Boolean = false
    ) {
        val wasTtsSpeaking = isTtsSpeaking
        val wasAutoScrolling = isAutoScrollRunning

        currentChapterIndex = chapterIndex
        currentScrollY = initialScrollY
        isProgrammaticScrollOrLoad = true
        hasGenuineReadingInCurrentChapter = false
        userTouchDownInReadingView = false
        initialChapterLoadScrollY = initialScrollY
        chapterOpenedAtMs = nowMs()
        autoScrollPixelsAccumulated = 0

        if (!wasTtsSpeaking) {
            stopTts()
        } else {
            textToSpeech?.stop()
            ttsParagraphs = emptyList()
            ttsCurrentParagraphIndex = 0
        }
        if (!wasAutoScrolling) {
            stopAutoScroll()
        }
        clearSearchHighlights()

        val book = currentBook ?: return
        val totalCh = chaptersList.size.coerceAtLeast(1)
        val chapterMeta = chaptersList.getOrNull(chapterIndex)
        val title = chapterMeta?.title?.takeIf { it.isNotBlank() } ?: "Chapter ${chapterIndex + 1}"

        chapterTitleTopView.text = title
        chapterHeadingView.text = title

        val progressPercent = (((chapterIndex + 1).toFloat() / totalCh.toFloat()) * 100).toInt()
        bottomProgressBar.progress = progressPercent
        bottomProgressTextView.text = "Chapter ${chapterIndex + 1} of $totalCh • $progressPercent%"

        prevChapterButton.isEnabled = chapterIndex > 0
        nextChapterButton.isEnabled = chapterIndex < chaptersList.size - 1
        tocAdapter.submitChapters(chaptersList, currentChapterIndex)

        openChapterJob?.cancel()
        openChapterJob = viewScope.launch {
            // Maintain 21-chapter rolling cache window and load chapter text
            rawChapterText = repository.getChapterText(book.id, chapterIndex)
            readingContentTextView.text = rawChapterText
            chapterOpenedAtMs = nowMs()

            val posted = readingScrollView.post {
                readingScrollView.scrollTo(0, initialScrollY)
                isProgrammaticScrollOrLoad = false
            }
            if (!posted) {
                readingScrollView.scrollTo(0, initialScrollY)
                isProgrammaticScrollOrLoad = false
            }

            // Save ONLY Reader resume position (never advances Tracker high-water progress on open/jump/restore)
            repository.saveResumePosition(
                bookId = book.id,
                chapterIndex = chapterIndex,
                scrollY = initialScrollY,
                updateOpenRecency = true
            )

            // Seamless TTS continuity across chapter transitions / rolling window shifts
            if (wasTtsSpeaking && rawChapterText.isNotBlank()) {
                startTts()
            }
        }
    }

    fun advanceToNextChapterByUser(scrollableRangeOverridePx: Int? = null) {
        if (currentChapterIndex >= chaptersList.size - 1) return
        val metaWords = chaptersList.getOrNull(currentChapterIndex)?.wordCount ?: 0
        val wordCount = if (metaWords > 0) metaWords else ReaderProgressPolicy.countWords(rawChapterText)
        val scrollableRange = scrollableRangeOverridePx ?: getScrollableRangePx()

        if (ReaderProgressPolicy.shouldAdvanceProgressOnNextClick(
                hasGenuineReadingInChapter = hasGenuineReadingInCurrentChapter,
                chapterEnteredAtMs = chapterOpenedAtMs,
                nowMs = nowMs(),
                wordCount = wordCount,
                charCount = rawChapterText.length,
                scrollableRangePx = scrollableRange,
                scrollThresholdPx = dp(80)
            )
        ) {
            recordGenuineReadingActivityOnCurrentChapter(currentChapterIndex)
        }
        openChapter(currentChapterIndex + 1, initialScrollY = 0, isDirectJump = false)
    }

    fun simulateUserScrollForTesting(scrollY: Int, scrollableRangePx: Int = dp(800)) {
        userTouchDownInReadingView = true
        isProgrammaticScrollOrLoad = false
        currentScrollY = scrollY
        if (!hasGenuineReadingInCurrentChapter &&
            ReaderProgressPolicy.isScrollActivityMeaningful(
                scrollY = scrollY,
                initialScrollY = initialChapterLoadScrollY,
                scrollableRangePx = scrollableRangePx,
                scrollThresholdPx = dp(80),
                userTouched = true,
                isProgrammatic = false
            )
        ) {
            recordGenuineReadingActivityOnCurrentChapter(currentChapterIndex)
        }
        repository.saveReadingProgressAsync(
            bookId = currentBook?.id ?: return,
            chapterIndex = currentChapterIndex,
            scrollY = currentScrollY,
            updateOpenRecency = true
        )
    }

    /**
     * Records genuine reading activity in [chapterIndex] and shows the Undo banner if
     * Tracker high-water progress (`TrackerBook.readChapters`) increased.
     */
    fun recordGenuineReadingActivityOnCurrentChapter(chapterIndex: Int = currentChapterIndex) {
        val book = currentBook ?: return
        if (hasGenuineReadingInCurrentChapter) return
        hasGenuineReadingInCurrentChapter = true
        val targetChapter = chapterIndex

        highWaterJob = viewScope.launch {
            val update = repository.recordGenuineReadingProgress(book.id, targetChapter)
            if (update != null) {
                showHighWaterUndoBanner(update)
            }
        }
    }

    private fun showHighWaterUndoBanner(update: HighWaterProgressUpdate) {
        lastHighWaterUpdate = update
        undoBannerTextView.text = "Tracker progress: Ch. ${update.previousReadChapters} → Ch. ${update.newReadChapters}"
        undoBannerLayout.visibility = View.VISIBLE

        undoBannerHideJob?.cancel()
        undoBannerHideJob = viewScope.launch {
            delay(6000L)
            undoBannerLayout.visibility = View.GONE
        }
    }

    fun performHighWaterProgressUndo() {
        val update = lastHighWaterUpdate ?: return
        undoBannerHideJob?.cancel()
        undoBannerLayout.visibility = View.GONE
        lastHighWaterUpdate = null

        undoJob = viewScope.launch {
            val ok = repository.undoHighWaterProgress(update)
            if (ok) {
                try {
                    Toast.makeText(
                        appContext,
                        "Reverted Tracker progress to Ch. ${update.previousReadChapters}",
                        Toast.LENGTH_SHORT
                    )?.show()
                } catch (_: Throwable) {}
            }
        }
    }

    private fun scheduleDebouncedProgressSave() {
        val book = currentBook ?: return
        scrollSaveJob?.cancel()
        scrollSaveJob = viewScope.launch {
            delay(500L)
            repository.saveResumePosition(book.id, currentChapterIndex, currentScrollY, updateOpenRecency = true)
        }
    }

    fun saveCurrentProgressImmediately() {
        val book = currentBook ?: return
        scrollSaveJob?.cancel()
        repository.saveReadingProgressAsync(book.id, currentChapterIndex, currentScrollY, updateOpenRecency = true)
    }

    // --- Search Feature ---
    private fun toggleSearchBar() {
        if (searchBarLayout.visibility == View.VISIBLE) {
            searchBarLayout.visibility = View.GONE
            clearSearchFocus()
            clearSearchHighlights()
        } else {
            searchBarLayout.visibility = View.VISIBLE
            fontSizeBarLayout.visibility = View.GONE
            autoScrollBarLayout.visibility = View.GONE
            ttsBarLayout.visibility = View.GONE
            onRequestWindowFocusable?.invoke(true)
            searchEditText.requestFocus()
        }
    }

    private fun performChapterSearch(query: String) {
        searchMatches.clear()
        currentSearchMatchIndex = -1
        if (query.isBlank() || rawChapterText.isBlank()) {
            searchCountView.text = ""
            readingContentTextView.text = rawChapterText
            return
        }

        val cleanQuery = query.lowercase()
        val lowerText = rawChapterText.lowercase()
        var idx = 0
        while (idx < lowerText.length) {
            val match = lowerText.indexOf(cleanQuery, idx)
            if (match == -1) break
            searchMatches.add(match)
            idx = match + cleanQuery.length
        }

        if (searchMatches.isEmpty()) {
            searchCountView.text = "0 matches"
            readingContentTextView.text = rawChapterText
        } else {
            currentSearchMatchIndex = 0
            highlightSearchMatches(cleanQuery.length)
        }
    }

    private fun navigateSearchMatch(direction: Int) {
        if (searchMatches.isEmpty()) return
        currentSearchMatchIndex = (currentSearchMatchIndex + direction + searchMatches.size) % searchMatches.size
        val query = searchEditText.text.toString().trim()
        highlightSearchMatches(query.length)
    }

    private fun highlightSearchMatches(queryLength: Int) {
        if (searchMatches.isEmpty()) return
        val spannable = SpannableString(rawChapterText)
        val matchOffset = searchMatches[currentSearchMatchIndex]

        for ((idx, offset) in searchMatches.withIndex()) {
            val color = if (idx == currentSearchMatchIndex) Color.parseColor("#FAB387") else Color.parseColor("#EBA0AC")
            val end = (offset + queryLength).coerceAtMost(rawChapterText.length)
            spannable.setSpan(
                BackgroundColorSpan(color),
                offset,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        readingContentTextView.text = spannable
        searchCountView.text = "${currentSearchMatchIndex + 1}/${searchMatches.size}"

        val layout = readingContentTextView.layout
        if (layout != null) {
            val line = layout.getLineForOffset(matchOffset)
            val y = layout.getLineTop(line)
            isProgrammaticScrollOrLoad = true
            readingScrollView.smoothScrollTo(0, y)
            readingScrollView.post { isProgrammaticScrollOrLoad = false }
        }
    }

    private fun clearSearchHighlights() {
        searchMatches.clear()
        currentSearchMatchIndex = -1
        searchCountView.text = ""
        readingContentTextView.text = rawChapterText
    }

    private fun openSearchInBookDialog() {
        val query = searchEditText.text.toString().trim()
        if (query.isBlank()) {
            Toast.makeText(appContext, "Enter a search term first.", Toast.LENGTH_SHORT).show()
            return
        }
        val book = currentBook ?: return
        clearSearchFocus()

        searchBookItemsContainer.removeAllViews()
        val loadingText = TextView(appContext).apply {
            text = "Searching across all chapters..."
            textSize = 13f
            setTextColor(Color.parseColor("#A6ADC8"))
            setPadding(16, 24, 16, 24)
        }
        searchBookItemsContainer.addView(loadingText)
        searchBookOverlayLayout.visibility = View.VISIBLE

        viewScope.launch {
            val results = repository.searchInBook(book.id, query)
            searchBookItemsContainer.removeAllViews()

            if (results.isEmpty()) {
                val emptyView = TextView(appContext).apply {
                    text = "No matches found for '$query'"
                    textSize = 13f
                    setTextColor(Color.parseColor("#A6ADC8"))
                    setPadding(16, 24, 16, 24)
                }
                searchBookItemsContainer.addView(emptyView)
                return@launch
            }

            for (res in results) {
                val itemCard = LinearLayout(appContext).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#252538"))
                        cornerRadius = 10f
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(4, 4, 4, 4)
                    }
                    setPadding(14, 12, 14, 12)
                    isClickable = true
                    setOnClickListener {
                        searchBookOverlayLayout.visibility = View.GONE
                        // Jumping via Search Result updates ONLY Reader resume position — never advances Tracker high-water!
                        openChapter(res.chapterIndex, initialScrollY = 0, isDirectJump = true)
                    }
                }

                val title = TextView(appContext).apply {
                    text = "${res.chapterIndex + 1}. ${res.chapterTitle}"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#89B4FA"))
                }
                itemCard.addView(title)

                val snippet = TextView(appContext).apply {
                    text = res.snippet
                    textSize = 12f
                    setTextColor(Color.parseColor("#CDD6F4"))
                    setPadding(0, 4, 0, 0)
                }
                itemCard.addView(snippet)

                searchBookItemsContainer.addView(itemCard)
            }
        }
    }

    // --- Font Size Bar ---
    private fun toggleFontSizeBar() {
        if (fontSizeBarLayout.visibility == View.VISIBLE) {
            fontSizeBarLayout.visibility = View.GONE
        } else {
            fontSizeBarLayout.visibility = View.VISIBLE
            searchBarLayout.visibility = View.GONE
            autoScrollBarLayout.visibility = View.GONE
            ttsBarLayout.visibility = View.GONE
        }
    }

    private fun applyFontSize() {
        readingContentTextView.textSize = currentFontSizeSp
        fontSizeTextView.text = "${currentFontSizeSp.toInt()}sp"
    }

    // --- Auto-Scroll Feature ---
    private fun toggleAutoScrollBar() {
        if (autoScrollBarLayout.visibility == View.VISIBLE) {
            autoScrollBarLayout.visibility = View.GONE
            stopAutoScroll()
        } else {
            autoScrollBarLayout.visibility = View.VISIBLE
            searchBarLayout.visibility = View.GONE
            fontSizeBarLayout.visibility = View.GONE
            ttsBarLayout.visibility = View.GONE
        }
    }

    private fun toggleAutoScrollPlayPause() {
        if (isAutoScrollRunning) {
            stopAutoScroll()
        } else {
            startAutoScroll()
        }
    }

    private fun startAutoScroll() {
        isAutoScrollRunning = true
        autoScrollPlayPauseButton.text = "⏸ Pause"
        autoScrollPlayPauseButton.background = GradientDrawable().apply {
            setColor(Color.parseColor("#FAB387"))
            cornerRadius = 8f
        }
        mainHandler.post(autoScrollRunnable)
    }

    fun stopAutoScroll() {
        isAutoScrollRunning = false
        autoScrollPlayPauseButton.text = "▶ Play"
        autoScrollPlayPauseButton.background = GradientDrawable().apply {
            setColor(Color.parseColor("#89B4FA"))
            cornerRadius = 8f
        }
        mainHandler.removeCallbacks(autoScrollRunnable)
    }

    private fun cycleAutoScrollSpeed() {
        autoScrollSpeedMultiplier = when (autoScrollSpeedMultiplier) {
            0.5f -> 1.0f
            1.0f -> 2.0f
            2.0f -> 3.0f
            else -> 0.5f
        }
        ReaderPreferences.setAutoScrollSpeed(appContext, autoScrollSpeedMultiplier)
        autoScrollSpeedButton.text = "Speed: ${autoScrollSpeedMultiplier}x"
    }

    // --- Text-to-Speech Feature ---
    private fun toggleTtsBar() {
        if (ttsBarLayout.visibility == View.VISIBLE) {
            ttsBarLayout.visibility = View.GONE
            stopTts()
        } else {
            ttsBarLayout.visibility = View.VISIBLE
            searchBarLayout.visibility = View.GONE
            fontSizeBarLayout.visibility = View.GONE
            autoScrollBarLayout.visibility = View.GONE
        }
    }

    private fun toggleTtsPlayPause() {
        if (isTtsSpeaking) {
            pauseTts()
        } else {
            startTts()
        }
    }

    private fun startTts() {
        if (!isTtsInitialized || textToSpeech == null) {
            Toast.makeText(appContext, "TTS not initialized.", Toast.LENGTH_SHORT).show()
            return
        }

        if (ttsParagraphs.isEmpty()) {
            ttsParagraphs = rawChapterText.split("\n\n").map { it.trim() }.filter { it.isNotBlank() }
            ttsCurrentParagraphIndex = 0
        }

        if (ttsCurrentParagraphIndex >= ttsParagraphs.size) {
            ttsCurrentParagraphIndex = 0
        }

        isTtsSpeaking = true
        ttsPlayPauseButton.text = "⏸ Pause"
        speakCurrentTtsParagraph()
    }

    private fun speakCurrentTtsParagraph() {
        if (!isTtsSpeaking) return
        if (ttsCurrentParagraphIndex >= ttsParagraphs.size) {
            // Completed chapter via TTS -> counts as genuine reading activity
            recordGenuineReadingActivityOnCurrentChapter()
            if (currentChapterIndex < chaptersList.size - 1) {
                openChapter(currentChapterIndex + 1, initialScrollY = 0, isDirectJump = false)
            } else {
                stopTts()
            }
            return
        }

        if (ttsCurrentParagraphIndex >= 1 && !hasGenuineReadingInCurrentChapter) {
            recordGenuineReadingActivityOnCurrentChapter()
        }

        val text = ttsParagraphs[ttsCurrentParagraphIndex]
        ttsStatusView.text = "Speaking ${ttsCurrentParagraphIndex + 1}/${ttsParagraphs.size}"

        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reader_tts_$ttsCurrentParagraphIndex")

        val words = text.split(Regex("\\s+")).size
        val rate = ReaderPreferences.getTtsSpeechRate(appContext).coerceAtLeast(0.5f)
        val durationMs = ((words.toFloat() / (2.5f * rate)) * 1000L).toLong().coerceIn(1200L, 20000L)
        mainHandler.postDelayed({
            if (isTtsSpeaking) {
                ttsCurrentParagraphIndex++
                speakCurrentTtsParagraph()
            }
        }, durationMs)
    }

    private fun pauseTts() {
        isTtsSpeaking = false
        ttsPlayPauseButton.text = "▶ Speak"
        textToSpeech?.stop()
        ttsStatusView.text = "Paused at ${ttsCurrentParagraphIndex + 1}/${ttsParagraphs.size}"
    }

    fun stopTts() {
        isTtsSpeaking = false
        ttsCurrentParagraphIndex = 0
        ttsParagraphs = emptyList()
        ttsPlayPauseButton.text = "▶ Speak"
        textToSpeech?.stop()
        ttsStatusView.text = "TTS Stopped"
    }

    // --- Virtualized Table of Contents Overlay ---
    private fun toggleTocOverlay() {
        if (tocOverlayLayout.visibility == View.VISIBLE) {
            tocOverlayLayout.visibility = View.GONE
        } else {
            tocAdapter.submitChapters(chaptersList, currentChapterIndex)
            tocOverlayLayout.visibility = View.VISIBLE
            tocRecyclerView?.scrollToPosition(currentChapterIndex.coerceIn(0, (chaptersList.size - 1).coerceAtLeast(0)))
        }
    }

    fun releaseResources() {
        ReaderEventManager.unregisterSettingsListener(settingsListener)
        ReaderEventManager.unregisterListener(libraryChangedListener)
        stopAutoScroll()
        stopTts()
        try {
            textToSpeech?.shutdown()
        } catch (_: Throwable) {}
        textToSpeech = null
        scrollSaveJob?.cancel()
        openChapterJob?.cancel()
        highWaterJob?.cancel()
        undoJob?.cancel()
        undoBannerHideJob?.cancel()
        rawChapterText = ""
        readingContentTextView.text = ""
        EpubParser.releaseInMemoryWindow(currentBook?.id)
        viewScope.cancel()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }

    fun isViewScopeActiveForTesting(): Boolean =
        viewScope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true

    internal suspend fun awaitPendingJobsForTesting() {
        openChapterJob?.join()
        highWaterJob?.join()
        undoJob?.join()
    }

    fun isAutoScrollRunningForTesting(): Boolean = isAutoScrollRunning
    fun isTtsSpeakingForTesting(): Boolean = isTtsSpeaking
    fun isUndoBannerVisibleForTesting(): Boolean = undoBannerLayout.visibility == View.VISIBLE
    fun getLastHighWaterUpdateForTesting(): HighWaterProgressUpdate? = lastHighWaterUpdate
    fun getTocRecyclerViewForTesting(): RecyclerView? = tocRecyclerView

    /**
     * Virtualized RecyclerView Adapter for Table of Contents (TOC).
     * Strictly renders 1 line per chapter: "${index + 1}. ${actualTitle}" with end ellipsis
     * and highlights the currently open chapter.
     */
    class TocChapterAdapter(
        private val onChapterClicked: (ChapterMeta) -> Unit
    ) : RecyclerView.Adapter<TocChapterAdapter.TocViewHolder>() {

        private var items: List<ChapterMeta> = emptyList()
        private var currentIndex: Int = 0

        fun submitChapters(newItems: List<ChapterMeta>, activeChapterIndex: Int) {
            items = newItems
            currentIndex = activeChapterIndex
            try {
                notifyDataSetChanged()
            } catch (_: Throwable) {}
        }

        fun formatTocLine(meta: ChapterMeta): String {
            val numberPrefix = "${meta.index + 1}. "
            val cleanTitle = meta.title.trim().takeIf { it.isNotBlank() } ?: "Chapter ${meta.index + 1}"
            return "$numberPrefix$cleanTitle"
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TocViewHolder {
            val ctx = parent.context
            val density = (ctx.resources.displayMetrics.density).takeIf { it > 0f } ?: 2f
            fun dp(v: Int): Int = (v * density).toInt()

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(44)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, dp(2), 0, dp(2))
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isClickable = true
                isFocusable = true
            }

            val lineText = TextView(ctx).apply {
                textSize = 13f
                maxLines = 1
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(lineText)

            val badgeText = TextView(ctx).apply {
                text = "Current"
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#89B4FA"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#181825"))
                    cornerRadius = 6 * density
                }
                setPadding(dp(8), dp(2), dp(8), dp(2))
                visibility = View.GONE
            }
            row.addView(badgeText)

            return TocViewHolder(row, lineText, badgeText)
        }

        override fun onBindViewHolder(holder: TocViewHolder, position: Int) {
            val item = items[position]
            val isCurrent = item.index == currentIndex
            val density = (holder.itemView.context.resources.displayMetrics.density).takeIf { it > 0f } ?: 2f

            holder.lineText.text = formatTocLine(item)
            holder.lineText.typeface = if (isCurrent) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            holder.lineText.setTextColor(
                if (isCurrent) Color.parseColor("#89B4FA") else Color.parseColor("#CDD6F4")
            )
            holder.badgeText.visibility = if (isCurrent) View.VISIBLE else View.GONE

            holder.row.background = GradientDrawable().apply {
                setColor(if (isCurrent) Color.parseColor("#313244") else Color.parseColor("#252538"))
                cornerRadius = 8 * density
                if (isCurrent) {
                    setStroke((1.5f * density).toInt().coerceAtLeast(1), Color.parseColor("#89B4FA"))
                }
            }

            holder.row.setOnClickListener {
                onChapterClicked(item)
            }
        }

        override fun getItemCount(): Int = items.size

        class TocViewHolder(
            val row: LinearLayout,
            val lineText: TextView,
            val badgeText: TextView
        ) : RecyclerView.ViewHolder(row)
    }
}
