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
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.core.LogKeeper
import com.example.data.ChapterMeta
import com.example.data.EpubBook
import com.example.data.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * ReaderReadingView: The actual reading screen inside the floating Reader mini-app.
 * Includes text display, chapter navigation, table of contents, in-chapter and in-book search,
 * font scaling, auto-scroll, text selection, and Text-to-Speech.
 */
class ReaderReadingView(
    context: Context,
    private val onBackToLibrary: () -> Unit
) : FrameLayout(context), TextToSpeech.OnInitListener {

    private val viewScope = CoroutineScope(SupervisorJob() + ReaderDispatchers.main)
    private val repository = LibraryRepository(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentBook: EpubBook? = null
    var currentChapterIndex: Int = 0
        private set
    var currentScrollY: Int = 0
        private set
    var currentFontSizeSp: Float = 16f
        private set

    private var chaptersList: List<ChapterMeta> = emptyList()
    private var rawChapterText: String = ""

    // UI elements
    private val chapterTitleTopView: TextView
    private val chapterHeadingView: TextView
    private val readingContentTextView: TextView
    private val readingScrollView: ScrollView
    private val prevChapterButton: Button
    private val nextChapterButton: Button
    private val bottomProgressTextView: TextView
    private val bottomProgressBar: ProgressBar

    // Tool panels
    private val searchBarLayout: LinearLayout
    private val searchEditText: EditText
    private val searchCountView: TextView
    private val fontSizeBarLayout: LinearLayout
    private val fontSizeTextView: TextView
    private val autoScrollBarLayout: LinearLayout
    private val autoScrollPlayPauseButton: Button
    private val autoScrollSpeedButton: Button
    private val ttsBarLayout: LinearLayout
    private val ttsPlayPauseButton: Button
    private val ttsStatusView: TextView

    // TOC overlay
    private val tocOverlayLayout: FrameLayout
    private val tocItemsContainer: LinearLayout

    // Search in book overlay
    private val searchBookOverlayLayout: FrameLayout
    private val searchBookItemsContainer: LinearLayout

    // Auto-scroll state
    private var isAutoScrollRunning = false
    private var autoScrollSpeedMultiplier = 1.0f
    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (isAutoScrollRunning) {
                val step = (2 * autoScrollSpeedMultiplier).toInt().coerceAtLeast(1)
                readingScrollView.smoothScrollBy(0, step)
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

    // Debounced scroll persistence
    private var scrollSaveJob: Job? = null

    init {
        setBackgroundColor(Color.parseColor("#181825")) // Catppuccin Base

        val mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        // --- Top Navigation & Action Bar ---
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E2E")) // Surface
            setPadding(8, 6, 8, 6)
        }

        val backButton = Button(context).apply {
            text = "← Library"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#89B4FA"))
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = 8f
            }
            setPadding(12, 6, 12, 6)
            setOnClickListener {
                saveCurrentProgressImmediately()
                stopAutoScroll()
                stopTts()
                onBackToLibrary()
            }
        }
        topBar.addView(backButton)

        chapterTitleTopView = TextView(context).apply {
            text = "Chapter"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#CDD6F4"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 8
                marginEnd = 8
            }
        }
        topBar.addView(chapterTitleTopView)

        // Action icon buttons
        fun createActionButton(label: String, onClick: () -> Unit): Button {
            return Button(context).apply {
                text = label
                textSize = 12f
                setTextColor(Color.parseColor("#CDD6F4"))
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = 8f
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 40).apply {
                    marginStart = 2
                }
                setPadding(8, 4, 8, 4)
                setOnClickListener { onClick() }
            }
        }

        topBar.addView(createActionButton("🔍") { toggleSearchBar() })
        topBar.addView(createActionButton("A") { toggleFontSizeBar() })
        topBar.addView(createActionButton("▼") { toggleAutoScrollBar() })
        topBar.addView(createActionButton("🔊") { toggleTtsBar() })
        topBar.addView(createActionButton("☰") { toggleTocOverlay() })

        mainLayout.addView(topBar)

        // --- Search Bar Panel ---
        searchBarLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#252538"))
            setPadding(12, 8, 12, 8)
        }

        searchEditText = EditText(context).apply {
            hint = "Search chapter..."
            setHintTextColor(Color.parseColor("#6C7086"))
            setTextColor(Color.WHITE)
            textSize = 12f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#313244"))
                cornerRadius = 8f
            }
            setPadding(16, 10, 16, 10)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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
                    applyFontSize()
                }
            }
        }
        fontSizeBarLayout.addView(decFontButton)

        fontSizeTextView = TextView(context).apply {
            text = "16sp"
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
            text = "Speed: 1.0x"
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
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                currentScrollY = scrollY
                scheduleDebouncedProgressSave()
            }
        }

        val contentInnerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 40)
        }

        chapterHeadingView = TextView(context).apply {
            text = "Chapter"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#89B4FA")) // Accent color
            setPadding(0, 8, 0, 16)
        }
        contentInnerLayout.addView(chapterHeadingView)

        readingContentTextView = TextView(context).apply {
            setTextIsSelectable(true)
            textSize = currentFontSizeSp
            setTextColor(Color.parseColor("#CDD6F4"))
            setLineSpacing(0f, 1.35f)
        }
        contentInnerLayout.addView(readingContentTextView)

        readingScrollView.addView(contentInnerLayout)
        mainLayout.addView(readingScrollView)

        // --- Bottom Chapter Navigation Bar ---
        val bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E2E")) // Surface
            setPadding(12, 6, 12, 8)
        }

        bottomProgressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressDrawable?.setColorFilter(Color.parseColor("#89B4FA"), android.graphics.PorterDuff.Mode.SRC_IN)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                4
            ).apply {
                bottomMargin = 6
            }
        }
        bottomBar.addView(bottomProgressBar)

        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        prevChapterButton = Button(context).apply {
            text = "◀ Prev"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#313244"))
                cornerRadius = 10f
            }
            setOnClickListener {
                if (currentChapterIndex > 0) {
                    openChapter(currentChapterIndex - 1)
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
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#313244"))
                cornerRadius = 10f
            }
            setOnClickListener {
                if (currentChapterIndex < chaptersList.size - 1) {
                    openChapter(currentChapterIndex + 1)
                }
            }
        }
        bottomRow.addView(nextChapterButton)

        bottomBar.addView(bottomRow)
        mainLayout.addView(bottomBar)

        addView(mainLayout)

        // --- TOC Overlay Drawer (Fullscreen inside floating window) ---
        tocOverlayLayout = FrameLayout(context).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#E6181825")) // Semi-translucent dark overlay
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        val tocCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E2E"))
                cornerRadius = 16f
                setStroke(1, Color.parseColor("#313244"))
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                setMargins(24, 24, 24, 24)
            }
            setPadding(16, 16, 16, 16)
        }

        val tocHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 12)
        }

        val tocTitle = TextView(context).apply {
            text = "Table of Contents"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#CDD6F4"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tocHeader.addView(tocTitle)

        val tocClose = Button(context).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.parseColor("#A6ADC8"))
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = 8f
            }
            setOnClickListener {
                tocOverlayLayout.visibility = View.GONE
            }
        }
        tocHeader.addView(tocClose)
        tocCard.addView(tocHeader)

        val tocScrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        tocItemsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        tocScrollView.addView(tocItemsContainer)
        tocCard.addView(tocScrollView)

        tocOverlayLayout.addView(tocCard)
        addView(tocOverlayLayout)

        // --- Search In Book Overlay ---
        searchBookOverlayLayout = FrameLayout(context).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#E6181825"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        val searchBookCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E2E"))
                cornerRadius = 16f
                setStroke(1, Color.parseColor("#313244"))
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                setMargins(24, 24, 24, 24)
            }
            setPadding(16, 16, 16, 16)
        }

        val searchBookHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 12)
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
            ttsStatusView.text = "TTS Ready"
        } else {
            ttsStatusView.text = "TTS Unavailable"
        }
    }

    fun loadBook(book: EpubBook, initialChapter: Int? = null, initialScrollY: Int? = null, fontSizeSp: Float? = null) {
        currentBook = book
        if (fontSizeSp != null && fontSizeSp in 12f..28f) {
            currentFontSizeSp = fontSizeSp
        }
        applyFontSize()

        viewScope.launch {
            chaptersList = repository.getChapters(book.id)
            val chapterIdx = initialChapter ?: book.lastReadChapter
            val scrollY = initialScrollY ?: book.lastReadScrollY
            openChapter(chapterIdx.coerceIn(0, (chaptersList.size - 1).coerceAtLeast(0)), scrollY)
        }
    }

    fun openChapter(chapterIndex: Int, initialScrollY: Int = 0) {
        currentChapterIndex = chapterIndex
        currentScrollY = initialScrollY
        stopTts()
        stopAutoScroll()
        clearSearchHighlights()

        val book = currentBook ?: return
        val totalCh = chaptersList.size.coerceAtLeast(1)
        val chapterMeta = chaptersList.getOrNull(chapterIndex)
        val title = chapterMeta?.title ?: "Chapter ${chapterIndex + 1}"

        chapterTitleTopView.text = title
        chapterHeadingView.text = title

        // Update bottom progress bar
        val progressPercent = (((chapterIndex + 1).toFloat() / totalCh.toFloat()) * 100).toInt()
        bottomProgressBar.progress = progressPercent
        bottomProgressTextView.text = "Chapter ${chapterIndex + 1} of $totalCh • $progressPercent%"

        prevChapterButton.isEnabled = chapterIndex > 0
        nextChapterButton.isEnabled = chapterIndex < chaptersList.size - 1

        viewScope.launch {
            rawChapterText = repository.getChapterText(book.id, chapterIndex)
            readingContentTextView.text = rawChapterText

            // Restore scroll position
            readingScrollView.post {
                readingScrollView.scrollTo(0, initialScrollY)
            }

            // Immediately save progress for chapter transition
            repository.saveReadingProgress(book.id, chapterIndex, initialScrollY)
        }
    }

    private fun scheduleDebouncedProgressSave() {
        val book = currentBook ?: return
        scrollSaveJob?.cancel()
        scrollSaveJob = viewScope.launch {
            delay(500L) // Debounce rapid scroll events
            repository.saveReadingProgress(book.id, currentChapterIndex, currentScrollY)
        }
    }

    fun saveCurrentProgressImmediately() {
        val book = currentBook ?: return
        scrollSaveJob?.cancel()
        viewScope.launch {
            repository.saveReadingProgress(book.id, currentChapterIndex, currentScrollY)
        }
    }

    // --- Search Feature ---
    private fun toggleSearchBar() {
        if (searchBarLayout.visibility == View.VISIBLE) {
            searchBarLayout.visibility = View.GONE
            clearSearchHighlights()
        } else {
            searchBarLayout.visibility = View.VISIBLE
            fontSizeBarLayout.visibility = View.GONE
            autoScrollBarLayout.visibility = View.GONE
            ttsBarLayout.visibility = View.GONE
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

        // Scroll to match
        val layout = readingContentTextView.layout
        if (layout != null) {
            val line = layout.getLineForOffset(matchOffset)
            val y = layout.getLineTop(line)
            readingScrollView.smoothScrollTo(0, y)
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
            Toast.makeText(context, "Enter a search term first.", Toast.LENGTH_SHORT).show()
            return
        }
        val book = currentBook ?: return

        searchBookItemsContainer.removeAllViews()
        val loadingText = TextView(context).apply {
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
                val emptyView = TextView(context).apply {
                    text = "No matches found for '$query'"
                    textSize = 13f
                    setTextColor(Color.parseColor("#A6ADC8"))
                    setPadding(16, 24, 16, 24)
                }
                searchBookItemsContainer.addView(emptyView)
                return@launch
            }

            for (res in results) {
                val itemCard = LinearLayout(context).apply {
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
                        openChapter(res.chapterIndex)
                    }
                }

                val title = TextView(context).apply {
                    text = res.chapterTitle
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#89B4FA"))
                }
                itemCard.addView(title)

                val snippet = TextView(context).apply {
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

    private fun stopAutoScroll() {
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
            Toast.makeText(context, "TTS not initialized.", Toast.LENGTH_SHORT).show()
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
        if (!isTtsSpeaking || ttsCurrentParagraphIndex >= ttsParagraphs.size) {
            stopTts()
            return
        }

        val text = ttsParagraphs[ttsCurrentParagraphIndex]
        ttsStatusView.text = "Speaking ${ttsCurrentParagraphIndex + 1}/${ttsParagraphs.size}"

        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reader_tts_$ttsCurrentParagraphIndex")

        // Schedule next paragraph when finished (best-effort timer based on word length)
        val words = text.split(Regex("\\s+")).size
        val durationMs = ((words.toFloat() / 2.5f) * 1000L).toLong().coerceIn(1200L, 20000L)
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

    private fun stopTts() {
        isTtsSpeaking = false
        ttsCurrentParagraphIndex = 0
        ttsParagraphs = emptyList()
        ttsPlayPauseButton.text = "▶ Speak"
        textToSpeech?.stop()
        ttsStatusView.text = "TTS Stopped"
    }

    // --- Table of Contents Overlay ---
    private fun toggleTocOverlay() {
        if (tocOverlayLayout.visibility == View.VISIBLE) {
            tocOverlayLayout.visibility = View.GONE
        } else {
            renderTocItems()
            tocOverlayLayout.visibility = View.VISIBLE
        }
    }

    private fun renderTocItems() {
        tocItemsContainer.removeAllViews()
        for (ch in chaptersList) {
            val isCurrent = ch.index == currentChapterIndex
            val itemRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(if (isCurrent) Color.parseColor("#313244") else Color.parseColor("#252538"))
                    cornerRadius = 10f
                    if (isCurrent) setStroke(2, Color.parseColor("#89B4FA"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 4, 0, 4)
                }
                setPadding(16, 14, 16, 14)
                isClickable = true
                setOnClickListener {
                    tocOverlayLayout.visibility = View.GONE
                    openChapter(ch.index)
                }
            }

            val chNumber = TextView(context).apply {
                text = "${ch.index + 1}."
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isCurrent) Color.parseColor("#89B4FA") else Color.parseColor("#A6ADC8"))
                setPadding(0, 0, 12, 0)
            }
            itemRow.addView(chNumber)

            val chTitle = TextView(context).apply {
                text = ch.title
                textSize = 13f
                typeface = if (isCurrent) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (isCurrent) Color.parseColor("#89B4FA") else Color.parseColor("#CDD6F4"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            itemRow.addView(chTitle)

            if (isCurrent) {
                val currentBadge = TextView(context).apply {
                    text = "Reading"
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#89B4FA"))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#181825"))
                        cornerRadius = 6f
                    }
                    setPadding(10, 4, 10, 4)
                }
                itemRow.addView(currentBadge)
            }

            tocItemsContainer.addView(itemRow)
        }
    }

    fun releaseResources() {
        stopAutoScroll()
        stopTts()
        try {
            textToSpeech?.shutdown()
        } catch (_: Throwable) {}
        textToSpeech = null
        scrollSaveJob?.cancel()
        viewScope.cancel()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        releaseResources()
    }
}
