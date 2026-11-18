package com.example.feature.reader

import android.content.Context
import android.content.Intent
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
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ReaderReadingView: Distraction-free dark reading surface inside the floating Reader mini-app.
 *
 * Visual & Interaction Contract:
 * - Near-black reading background (`#08080B`) with white text (`#F5F5F7`) and comfortable line spacing.
 * - Single progress indicator rule: when toolbar is hidden, a thin top hairline progress bar sits
 *   flush under the floating window top bar; when toolbar is visible, the top hairline hides and
 *   a full-book SeekBar appears inside the bottom toolbar.
 * - Bottom action toolbar (`bottomToolbarLayout`) is hidden by default (`View.GONE`).
 *   Clean tap on the reading area toggles toolbar visibility without breaking scrolling or text selection.
 * - Toolbar provides: Full-book SeekBar, compact Prev/Next chapter buttons, and 7 icon actions
 *   (Library, TOC/Chapters, Bookmark, TTS, Auto-scroll, Search, Reader Settings).
 * - Preserves strict separation between Reader resume position and Tracker high-water progress,
 *   21-chapter rolling cache, and Undo banner.
 */
class ReaderReadingView(
    context: Context,
    private val instanceId: String? = null,
    private val onRequestWindowFocusable: ((Boolean) -> Unit)? = null,
    private val onActiveBookChanged: ((EpubBook?) -> Unit)? = null,
    private val onChapterTitleChanged: ((String?) -> Unit)? = null,
    private val onOpenReaderSettings: (() -> Unit)? = null,
    private val onToolbarVisibilityChanged: ((Boolean) -> Unit)? = null,
    private val onBackToLibrary: () -> Unit
) : FrameLayout(context), TextToSpeech.OnInitListener {

    data class SeekTarget(
        val chapterIndex: Int,
        val intraChapterFraction: Float
    )

    companion object {
        const val PROGRESS_MAX_PERMILLE = 1000

        private fun chapterWeights(chapters: List<ChapterMeta>, totalChapters: Int): FloatArray {
            val count = maxOf(chapters.size, totalChapters).coerceAtLeast(1)
            val hasWordCounts = chapters.any { it.wordCount > 0 }
            return FloatArray(count) { idx ->
                val words = chapters.getOrNull(idx)?.wordCount ?: 0
                if (hasWordCounts) words.coerceAtLeast(1).toFloat() else 1f
            }
        }

        fun computeFullBookProgressPermille(
            chapters: List<ChapterMeta>,
            totalChapters: Int,
            chapterIndex: Int,
            scrollY: Int,
            scrollableRangePx: Int
        ): Int {
            val weights = chapterWeights(chapters, totalChapters)
            val count = weights.size
            if (count <= 0) return 0
            val safeIdx = chapterIndex.coerceIn(0, count - 1)
            val intra = if (scrollableRangePx > 0) {
                (scrollY.coerceAtLeast(0).toFloat() / scrollableRangePx.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            val totalWeight = weights.sum().coerceAtLeast(1f)
            var completedWeight = 0f
            for (i in 0 until safeIdx) {
                completedWeight += weights[i]
            }
            val currentPosWeight = completedWeight + weights[safeIdx] * intra
            return ((currentPosWeight / totalWeight) * PROGRESS_MAX_PERMILLE)
                .roundToInt()
                .coerceIn(0, PROGRESS_MAX_PERMILLE)
        }

        fun resolveSeekTarget(
            chapters: List<ChapterMeta>,
            totalChapters: Int,
            permille: Int
        ): SeekTarget {
            val weights = chapterWeights(chapters, totalChapters)
            val count = weights.size
            if (count <= 0) return SeekTarget(0, 0f)
            val clamped = permille.coerceIn(0, PROGRESS_MAX_PERMILLE)
            if (clamped == 0) return SeekTarget(0, 0f)
            if (clamped >= PROGRESS_MAX_PERMILLE) return SeekTarget(count - 1, 1f)

            val totalWeight = weights.sum().coerceAtLeast(1f)
            val targetWeight = (clamped.toFloat() / PROGRESS_MAX_PERMILLE.toFloat()) * totalWeight
            var running = 0f
            for (i in 0 until count) {
                val w = weights[i]
                if (targetWeight <= running + w || i == count - 1) {
                    val intra = ((targetWeight - running) / w).coerceIn(0f, 1f)
                    return SeekTarget(i, intra)
                }
                running += w
            }
            return SeekTarget(count - 1, 1f)
        }

        fun resolveDisplayBookName(book: EpubBook?): String {
            if (book == null) return ""
            val cleanTitle = book.title.trim()
            if (cleanTitle.isNotEmpty()) return cleanTitle
            val path = book.filePath.trim()
            if (path.isEmpty()) return ""
            val fileName = java.io.File(path).name
            return fileName.substringBeforeLast('.').ifBlank { fileName }
        }
    }

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

    // Genuine reading activity tracking
    private var isProgrammaticScrollOrLoad = false
    private var hasGenuineReadingInCurrentChapter = false
    private var userTouchDownInReadingView = false
    private var initialChapterLoadScrollY = 0
    private var chapterOpenedAtMs: Long = 0L
    internal var clockMsForTesting: (() -> Long)? = null
    private var lastHighWaterUpdate: HighWaterProgressUpdate? = null
    private var undoBannerHideJob: Job? = null

    // Clean-tap tracking (distinguishes single tap from scroll drag or text selection)
    private var tapDownX = 0f
    private var tapDownY = 0f
    private var tapDownTimeMs = 0L
    private var hadSelectionAtTapDown = false
    private val touchSlopPx: Int = try {
        ViewConfiguration.get(context)?.scaledTouchSlop?.takeIf { it > 0 } ?: dp(8)
    } catch (_: Throwable) {
        dp(8)
    }

    private fun nowMs(): Long = clockMsForTesting?.invoke() ?: System.currentTimeMillis()

    private fun getScrollableRangePx(): Int {
        val child = readingScrollView.getChildAt(0) ?: return 0
        val viewH = readingScrollView.height
        if (viewH <= 0 || child.height <= 0) return 0
        return (child.height - viewH).coerceAtLeast(0)
    }

    // Core layout views
    private val mainLayout: LinearLayout
    private val topHeaderLayout: LinearLayout
    private val chapterTitleTopView: TextView
    private val chapterHeadingView: TextView
    private val readingContentTextView: TextView
    private val readingScrollView: ScrollView

    // Thin persistent progress hairline at the very top edge (shown ONLY when toolbar is hidden)
    private val topBookProgressBar: ProgressBar
    private var isTopProgressLineVisible: Boolean = true
    private var currentFullBookProgressPermille: Int = 0

    // Hidden-by-default bottom action toolbar
    private var isBottomToolbarVisible: Boolean = false
    private val bottomToolbarLayout: LinearLayout
    private val toolbarBookTitleView: TextView
    private val navSeekRow: LinearLayout
    private val iconRowLayout: LinearLayout
    private val fullBookSeekBar: SeekBar
    private val fullBookProgressLabelView: TextView
    private var isUserSeekingFullBook: Boolean = false
    private var isProgrammaticSeekUpdate: Boolean = false
    private var pendingSeekPermille: Int = 0
    private var hasFontControlsInToolbar: Boolean = false
    private val prevChapterButton: Button
    private val nextChapterButton: Button
    private val bookmarkToolbarIcon: ImageButton

    // Tracked Kotlin UI state for mockable android.jar unit test compatibility
    private var minimalHeaderTitleState: String = "Chapter"
    private var topHeaderVerticalPaddingPx: Int = 0
    private var toolbarBookTitleTextState: String = ""
    private val toolbarBookTitleMaxLinesState: Int = 3
    private val navSeekRowChildrenForTesting = mutableListOf<View>()
    private val iconRowActionsForTesting = mutableListOf<Pair<ImageButton, String>>()
    private val prevChapterContentDescState: String = "Previous chapter"
    private val nextChapterContentDescState: String = "Next chapter"

    // High-Water Progress Undo Banner
    private val undoBannerLayout: LinearLayout
    private val undoBannerTextView: TextView
    private val undoBannerActionButton: Button

    // Expandable inline sub-toolbars (shown inside bottomToolbarLayout when active)
    private val searchBarLayout: LinearLayout
    private val searchEditText: com.example.feature.floating.EmbeddedInputEditText
    private val searchCountView: TextView
    private val autoScrollBarLayout: LinearLayout
    private val autoScrollPlayPauseButton: Button
    private val autoScrollSpeedButton: Button
    private val ttsBarLayout: LinearLayout
    private val ttsPlayPauseButton: Button
    private val ttsStatusView: TextView

    // Overlays (TOC, Bookmarks, Search In Book)
    private val tocOverlayLayout: FrameLayout
    private val tocRecyclerView: RecyclerView?
    private val tocAdapter: TocChapterAdapter

    private val bookmarkOverlayLayout: FrameLayout
    private val bookmarkToggleBtn: Button
    private val bookmarkItemsContainer: LinearLayout

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

    // Lifecycle jobs
    private var scrollSaveJob: Job? = null
    private var loadBookJob: Job? = null
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
                    updateMinimalHeaderTitle()
                }
            }
        }
    }

    init {
        setBackgroundColor(Color.parseColor("#08080B"))

        mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        // --- Thin Full-Book Progress Line at Very Top Edge (flush under floating window top bar) ---
        topBookProgressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = PROGRESS_MAX_PERMILLE
            progress = 0
            visibility = View.VISIBLE
            setBackgroundColor(Color.parseColor("#181924"))
            progressDrawable?.setColorFilter(Color.parseColor("#89B4FA"), android.graphics.PorterDuff.Mode.SRC_IN)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(2).coerceAtLeast(2)
            )
        }
        mainLayout.addView(topBookProgressBar)

        // Chapter title is hosted directly in the floating window top bar (not a separate row below it)
        topHeaderVerticalPaddingPx = 0
        topHeaderLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        chapterTitleTopView = TextView(context).apply {
            text = "Chapter"
            maxLines = 1
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        topHeaderLayout.addView(chapterTitleTopView)

        // --- High-Water Progress Undo Banner ---
        undoBannerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#181A24"))
            setPadding(dp(12), dp(4), dp(10), dp(4))
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
            setTextColor(Color.parseColor("#0B0B10"))
            minHeight = dp(28)
            minimumHeight = dp(28)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F9E2AF"))
                cornerRadius = dpF(6f)
            }
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener { performHighWaterProgressUndo() }
        }
        undoBannerLayout.addView(undoBannerActionButton)
        mainLayout.addView(undoBannerLayout)

        // --- Scrollable Reading Content ---
        readingScrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setOnTouchListener { _, ev ->
                handleReadingTouchEvent(ev)
                false
            }
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                if (isProgrammaticScrollOrLoad) {
                    return@setOnScrollChangeListener
                }
                currentScrollY = scrollY
                updateFullBookProgressIndicators()
                if (!hasGenuineReadingInCurrentChapter &&
                    ReaderProgressPolicy.isScrollActivityMeaningful(
                        scrollY = scrollY,
                        initialScrollY = initialChapterLoadScrollY,
                        scrollableRangePx = getScrollableRangePx(),
                        scrollThresholdPx = dp(80),
                        userTouched = userTouchDownInReadingView,
                        isProgrammatic = false
                    )
                ) {
                    recordGenuineReadingActivityOnCurrentChapter(currentChapterIndex)
                }
                currentBook?.let { activeBook ->
                    repository.saveSyncResumeCheckpoint(
                        bookId = activeBook.id,
                        chapterIndex = currentChapterIndex,
                        scrollY = scrollY,
                        commitImmediately = false
                    )
                }
                scheduleDebouncedProgressSave()
            }
        }

        val contentInnerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(28))
        }

        chapterHeadingView = TextView(context).apply {
            text = "Chapter"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#89B4FA"))
            setPadding(0, dp(2), 0, dp(10))
        }
        contentInnerLayout.addView(chapterHeadingView)

        readingContentTextView = TextView(context).apply {
            setTextIsSelectable(true)
            textSize = currentFontSizeSp
            setTextColor(Color.parseColor("#F5F5F7"))
            setLineSpacing(0f, currentLineSpacingMult)
            setOnTouchListener { _, ev ->
                handleReadingTouchEvent(ev)
                false
            }
        }
        contentInnerLayout.addView(readingContentTextView)
        readingScrollView.addView(contentInnerLayout)
        mainLayout.addView(readingScrollView)

        // --- Hidden-By-Default Bottom Action Toolbar ---
        // Note: Floating window [ × — ↘ ] sticker hides while Reader bottom toolbar is visible,
        // so no extra 46dp bottom clearance is needed here.
        bottomToolbarLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setBackgroundColor(Color.parseColor("#121219"))
            bottomToolbarBottomPaddingPx = dp(6)
            setPadding(dp(8), dp(4), dp(8), bottomToolbarBottomPaddingPx)
        }

        fun createCompactToolButton(label: String, onClick: () -> Unit): Button {
            return Button(context).apply {
                text = label
                textSize = 11f
                setTextColor(Color.parseColor("#F5F5F7"))
                minWidth = dp(36)
                minimumWidth = dp(36)
                minHeight = dp(34)
                minimumHeight = dp(34)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1D1E2A"))
                    cornerRadius = dpF(8f)
                }
                setPadding(dp(7), dp(1), dp(7), dp(1))
                setOnClickListener { onClick() }
            }
        }

        // Expandable Search Sub-bar
        searchBarLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, 0, 0, dp(6))
        }
        searchEditText = com.example.feature.floating.EmbeddedInputEditText(context) { instanceId }.apply {
            hint = "Search chapter..."
            setHintTextColor(Color.parseColor("#6C7086"))
            setTextColor(Color.WHITE)
            textSize = 13f
            isSingleLine = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1D1E2A"))
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
            setTextColor(Color.parseColor("#9BA3B8"))
            setPadding(dp(6), 0, dp(6), 0)
        }
        searchBarLayout.addView(searchCountView)
        searchBarLayout.addView(createCompactToolButton("▲") { navigateSearchMatch(-1) }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(36)).apply { marginEnd = dp(3) }
        })
        searchBarLayout.addView(createCompactToolButton("▼") { navigateSearchMatch(1) }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(36)).apply { marginEnd = dp(3) }
        })
        searchBarLayout.addView(createCompactToolButton("Book") { openSearchInBookDialog() }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(3) }
        })
        searchBarLayout.addView(createCompactToolButton("✕") { toggleSearchBar() }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(36))
        })
        bottomToolbarLayout.addView(searchBarLayout)

        // Expandable Auto-Scroll Sub-bar
        autoScrollBarLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, 0, 0, dp(6))
        }
        autoScrollPlayPauseButton = createCompactToolButton("▶ Play") { toggleAutoScrollPlayPause() }.apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#89B4FA"))
                cornerRadius = dpF(8f)
            }
            setTextColor(Color.parseColor("#0B0B10"))
        }
        autoScrollBarLayout.addView(autoScrollPlayPauseButton)
        autoScrollSpeedButton = createCompactToolButton("Speed: ${autoScrollSpeedMultiplier}x") { cycleAutoScrollSpeed() }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)).apply {
                marginStart = dp(6)
            }
        }
        autoScrollBarLayout.addView(autoScrollSpeedButton)
        autoScrollBarLayout.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })
        autoScrollBarLayout.addView(createCompactToolButton("✕") { toggleAutoScrollBar() })
        bottomToolbarLayout.addView(autoScrollBarLayout)

        // Expandable TTS Sub-bar
        ttsBarLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, 0, 0, dp(6))
        }
        ttsPlayPauseButton = createCompactToolButton("▶ Speak") { toggleTtsPlayPause() }.apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#A6E3A1"))
                cornerRadius = dpF(8f)
            }
            setTextColor(Color.parseColor("#0B0B10"))
        }
        ttsBarLayout.addView(ttsPlayPauseButton)
        ttsBarLayout.addView(createCompactToolButton("⏹ Stop") { stopTts() }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)).apply {
                marginStart = dp(6)
            }
        })
        ttsStatusView = TextView(context).apply {
            text = "TTS Ready"
            textSize = 11f
            setTextColor(Color.parseColor("#9BA3B8"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        }
        ttsBarLayout.addView(ttsStatusView)
        ttsBarLayout.addView(createCompactToolButton("✕") { toggleTtsBar() })
        bottomToolbarLayout.addView(ttsBarLayout)

        // Book title (wrapped cleanly up to 3 lines) + full-book progress indicator row
        val toolbarHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), dp(2))
        }

        toolbarBookTitleView = TextView(context).apply {
            text = ""
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F5F5F7"))
            maxLines = 3
            isSingleLine = false
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        toolbarHeaderRow.addView(toolbarBookTitleView)

        fullBookProgressLabelView = TextView(context).apply {
            text = "Ch. 1/1 • 0%"
            textSize = 10.5f
            setTextColor(Color.parseColor("#CDD6F4"))
            gravity = Gravity.END
            maxLines = 1
            setPadding(dp(4), 0, dp(2), 0)
        }
        toolbarHeaderRow.addView(fullBookProgressLabelView)
        bottomToolbarLayout.addView(toolbarHeaderRow)

        // Single compact row strictly ordered: Prev chapter -> full-book progress SeekBar -> Next chapter
        navSeekRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(1), 0, dp(3))
        }

        prevChapterButton = createCompactToolButton("◀") {
            if (currentChapterIndex > 0) {
                openChapter(currentChapterIndex - 1, initialScrollY = 0, isDirectJump = false)
            }
        }.apply {
            contentDescription = prevChapterContentDescState
            minWidth = dp(40)
            minimumWidth = dp(40)
            minHeight = dp(32)
            minimumHeight = dp(32)
            setPadding(dp(8), dp(1), dp(8), dp(1))
        }
        navSeekRow.addView(prevChapterButton)
        navSeekRowChildrenForTesting.add(prevChapterButton)

        fullBookSeekBar = SeekBar(context).apply {
            max = PROGRESS_MAX_PERMILLE
            progress = 0
            minimumHeight = dp(28)
            contentDescription = "Book reading progress"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    isUserSeekingFullBook = true
                }

                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !isProgrammaticSeekUpdate) {
                        isUserSeekingFullBook = true
                        pendingSeekPermille = progress
                        updateSeekPreviewLabel(progress)
                    }
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val targetPermille = seekBar?.progress ?: pendingSeekPermille
                    isUserSeekingFullBook = false
                    commitSeekToFullBookPermille(targetPermille)
                }
            })
        }
        navSeekRow.addView(fullBookSeekBar)
        navSeekRowChildrenForTesting.add(fullBookSeekBar)

        nextChapterButton = createCompactToolButton("▶") {
            advanceToNextChapterByUser()
        }.apply {
            contentDescription = nextChapterContentDescState
            minWidth = dp(40)
            minimumWidth = dp(40)
            minHeight = dp(32)
            minimumHeight = dp(32)
            setPadding(dp(8), dp(1), dp(8), dp(1))
        }
        navSeekRow.addView(nextChapterButton)
        navSeekRowChildrenForTesting.add(nextChapterButton)
        bottomToolbarLayout.addView(navSeekRow)

        // 7-Icon Action Row: Library, TOC, Bookmark, TTS, Auto-Scroll, Search, Reader Settings
        iconRowLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun createToolbarIcon(resId: Int, desc: String, onClick: () -> Unit): ImageButton {
            return ImageButton(context).apply {
                setImageResource(resId)
                setColorFilter(Color.parseColor("#E2E5F0"))
                contentDescription = desc
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1B1C27"))
                    cornerRadius = dpF(7f)
                }
                setPadding(dp(7), dp(7), dp(7), dp(7))
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    setMargins(dp(1), 0, dp(1), 0)
                }
                setOnClickListener { onClick() }
            }.also { btn ->
                iconRowActionsForTesting.add(btn to desc)
            }
        }

        iconRowLayout.addView(createToolbarIcon(R.drawable.ic_library_books, "Back to Library") {
            navigateBackToLibrary()
        })
        iconRowLayout.addView(createToolbarIcon(R.drawable.ic_chapters_list, "Chapters / Table of Contents") {
            toggleTocOverlay()
        })
        bookmarkToolbarIcon = createToolbarIcon(R.drawable.ic_bookmark_outline, "Bookmarks") {
            toggleBookmarkOverlay()
        }
        iconRowLayout.addView(bookmarkToolbarIcon)
        iconRowLayout.addView(createToolbarIcon(R.drawable.ic_volume_up, "Text to Speech") {
            toggleTtsBar()
        })
        iconRowLayout.addView(createToolbarIcon(R.drawable.ic_auto_scroll, "Auto-scroll") {
            toggleAutoScrollBar()
        })
        iconRowLayout.addView(createToolbarIcon(R.drawable.ic_search, "Search Chapter or Book") {
            toggleSearchBar()
        })
        iconRowLayout.addView(createToolbarIcon(R.drawable.ic_settings_cog, "Reader Settings") {
            openSettingsFromReadingView()
        })

        bottomToolbarLayout.addView(iconRowLayout)
        mainLayout.addView(bottomToolbarLayout)
        addView(mainLayout)

        // --- Virtualized TOC Overlay ---
        tocOverlayLayout = FrameLayout(context).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#E608080B"))
            isClickable = true
            isFocusable = true
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setOnClickListener { visibility = View.GONE }
        }

        val tocCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#13131B"))
                cornerRadius = dpF(12f)
                setStroke(1, Color.parseColor("#252635"))
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                setMargins(dp(12), dp(12), dp(12), dp(48))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val tocHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }
        tocHeader.addView(TextView(context).apply {
            text = "Chapters"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F5F5F7"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        tocHeader.addView(Button(context).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.parseColor("#9BA3B8"))
            minWidth = dp(40)
            minimumWidth = dp(40)
            minHeight = dp(40)
            minimumHeight = dp(40)
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            setOnClickListener { tocOverlayLayout.visibility = View.GONE }
        })
        tocCard.addView(tocHeader)

        tocAdapter = TocChapterAdapter { selectedChapter ->
            tocOverlayLayout.visibility = View.GONE
            openChapter(selectedChapter.index, initialScrollY = 0, isDirectJump = true)
        }
        tocRecyclerView = try {
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                adapter = tocAdapter
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }.also { tocCard.addView(it) }
        } catch (_: Throwable) {
            null
        }
        tocOverlayLayout.addView(tocCard)
        addView(tocOverlayLayout)

        // --- Bookmarks Overlay ---
        bookmarkOverlayLayout = FrameLayout(context).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#E608080B"))
            isClickable = true
            isFocusable = true
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setOnClickListener { visibility = View.GONE }
        }

        val bmCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#13131B"))
                cornerRadius = dpF(12f)
                setStroke(1, Color.parseColor("#252635"))
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                setMargins(dp(12), dp(12), dp(12), dp(48))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val bmHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }
        bmHeader.addView(TextView(context).apply {
            text = "Bookmarks"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F5F5F7"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        bmHeader.addView(Button(context).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.parseColor("#9BA3B8"))
            minWidth = dp(40)
            minimumWidth = dp(40)
            minHeight = dp(40)
            minimumHeight = dp(40)
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            setOnClickListener { bookmarkOverlayLayout.visibility = View.GONE }
        })
        bmCard.addView(bmHeader)

        bookmarkToggleBtn = Button(context).apply {
            text = "+ Bookmark Current Chapter"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0B0B10"))
            minHeight = dp(42)
            minimumHeight = dp(42)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#89B4FA"))
                cornerRadius = dpF(8f)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
            setOnClickListener {
                toggleCurrentChapterBookmark()
                renderBookmarksList()
            }
        }
        bmCard.addView(bookmarkToggleBtn)

        val bmScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        bookmarkItemsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        bmScroll.addView(bookmarkItemsContainer)
        bmCard.addView(bmScroll)
        bookmarkOverlayLayout.addView(bmCard)
        addView(bookmarkOverlayLayout)

        // --- Search In Book Overlay ---
        searchBookOverlayLayout = FrameLayout(context).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#E608080B"))
            isClickable = true
            isFocusable = true
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setOnClickListener { visibility = View.GONE }
        }

        val searchBookCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#13131B"))
                cornerRadius = dpF(12f)
                setStroke(1, Color.parseColor("#252635"))
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                setMargins(dp(12), dp(12), dp(12), dp(48))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val searchBookHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }
        searchBookHeader.addView(TextView(context).apply {
            text = "Search In Book"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F5F5F7"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        searchBookHeader.addView(Button(context).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.parseColor("#9BA3B8"))
            minWidth = dp(40)
            minimumWidth = dp(40)
            minHeight = dp(40)
            minimumHeight = dp(40)
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            setOnClickListener { searchBookOverlayLayout.visibility = View.GONE }
        })
        searchBookCard.addView(searchBookHeader)

        val searchBookScrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
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

        try {
            textToSpeech = TextToSpeech(context, this)
        } catch (e: Exception) {
            LogKeeper.log(context, "ReaderReadingView", "TTS init exception: ${e.message}")
        }
    }

    private fun handleReadingTouchEvent(ev: MotionEvent) {
        handleReadingTouchRaw(ev.actionMasked, ev.rawX, ev.rawY, ev.eventTime)
    }

    internal fun handleReadingTouchRaw(
        actionMasked: Int,
        rawX: Float,
        rawY: Float,
        eventTimeMs: Long,
        hasSelectionOverride: Boolean? = null
    ) {
        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                userTouchDownInReadingView = true
                tapDownX = rawX
                tapDownY = rawY
                tapDownTimeMs = eventTimeMs
                hadSelectionAtTapDown = hasSelectionOverride ?: readingContentTextView.hasSelection()
            }
            MotionEvent.ACTION_MOVE -> {
                userTouchDownInReadingView = true
            }
            MotionEvent.ACTION_UP -> {
                val dx = abs(rawX - tapDownX)
                val dy = abs(rawY - tapDownY)
                val dt = (eventTimeMs - tapDownTimeMs).coerceAtLeast(0L)
                val hasSelectionNow = hasSelectionOverride ?: readingContentTextView.hasSelection()
                if (!hadSelectionAtTapDown &&
                    !hasSelectionNow &&
                    dx < touchSlopPx &&
                    dy < touchSlopPx &&
                    dt < 350L
                ) {
                    toggleBottomToolbar()
                }
            }
        }
    }

    private fun toggleBottomToolbar() {
        setBottomToolbarVisible(!isBottomToolbarVisible)
    }

    private fun setBottomToolbarVisible(visible: Boolean) {
        isBottomToolbarVisible = visible
        isTopProgressLineVisible = !visible
        if (visible) {
            bottomToolbarLayout.visibility = View.VISIBLE
            topBookProgressBar.visibility = View.GONE
            updateFullBookProgressIndicators()
        } else {
            bottomToolbarLayout.visibility = View.GONE
            topBookProgressBar.visibility = View.VISIBLE
            clearSearchFocus()
        }
        onToolbarVisibilityChanged?.invoke(visible)
    }

    private fun navigateBackToLibrary() {
        clearSearchFocus()
        setBottomToolbarVisible(false)
        saveCurrentProgressImmediately()
        stopAutoScroll()
        stopTts()
        onChapterTitleChanged?.invoke(null)
        onActiveBookChanged?.invoke(null)
        onBackToLibrary()
    }

    private fun openSettingsFromReadingView() {
        clearSearchFocus()
        saveCurrentProgressImmediately()
        if (onOpenReaderSettings != null) {
            onOpenReaderSettings.invoke()
        } else {
            try {
                val intent = Intent(appContext, com.example.SettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("start_route", "reader_settings")
                }
                appContext.startActivity(intent)
            } catch (_: Throwable) {}
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
        val prefs = appContext.getSharedPreferences(ReaderPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(ReaderPreferences.KEY_DEFAULT_FONT_SIZE)) {
            currentFontSizeSp = ReaderPreferences.getDefaultFontSizeSp(appContext)
            applyFontSize()
        }
        currentLineSpacingMult = ReaderPreferences.getLineSpacingMultiplier(appContext)
        currentThemePreset = ReaderPreferences.getThemePreset(appContext)
        autoScrollSpeedMultiplier = ReaderPreferences.getAutoScrollSpeed(appContext)
        autoScrollSpeedButton.text = "Speed: ${autoScrollSpeedMultiplier}x"
        textToSpeech?.setSpeechRate(ReaderPreferences.getTtsSpeechRate(appContext))

        val palette = ReaderPreferences.getPalette(currentThemePreset)
        setBackgroundColor(palette.backgroundColor)
        readingScrollView.setBackgroundColor(palette.backgroundColor)
        topHeaderLayout.setBackgroundColor(palette.backgroundColor)
        topBookProgressBar.setBackgroundColor(palette.surfaceColor)
        topBookProgressBar.progressDrawable?.setColorFilter(palette.accentColor, android.graphics.PorterDuff.Mode.SRC_IN)
        bottomToolbarLayout.setBackgroundColor(palette.surfaceColor)
        readingContentTextView.setTextColor(palette.textColor)
        readingContentTextView.setLineSpacing(0f, currentLineSpacingMult)
        chapterHeadingView.setTextColor(palette.accentColor)
        chapterTitleTopView.setTextColor(palette.secondaryTextColor)
        toolbarBookTitleView.setTextColor(palette.textColor)
        fullBookProgressLabelView.setTextColor(palette.secondaryTextColor)
    }

    private fun applyFontSize() {
        readingContentTextView.textSize = currentFontSizeSp
    }

    private fun updateSeekPreviewLabel(permille: Int) {
        val totalCh = chaptersList.size.coerceAtLeast(1)
        val target = resolveSeekTarget(chaptersList, totalCh, permille)
        val pct = (permille.toFloat() / 10f).roundToInt().coerceIn(0, 100)
        fullBookProgressLabelView.text = "Ch. ${target.chapterIndex + 1}/$totalCh • $pct%"
    }

    private fun updateFullBookProgressIndicators(scrollableRangeOverridePx: Int? = null) {
        val totalCh = chaptersList.size.coerceAtLeast(1)
        val range = scrollableRangeOverridePx ?: getScrollableRangePx()
        val permille = computeFullBookProgressPermille(
            chapters = chaptersList,
            totalChapters = totalCh,
            chapterIndex = currentChapterIndex,
            scrollY = currentScrollY,
            scrollableRangePx = range
        )
        currentFullBookProgressPermille = permille
        topBookProgressBar.progress = permille
        if (!isUserSeekingFullBook) {
            isProgrammaticSeekUpdate = true
            try {
                fullBookSeekBar.progress = permille
            } finally {
                isProgrammaticSeekUpdate = false
            }
            val pct = (permille.toFloat() / 10f).roundToInt().coerceIn(0, 100)
            fullBookProgressLabelView.text = "Ch. ${currentChapterIndex + 1}/$totalCh • $pct%"
        }
    }

    private fun commitSeekToFullBookPermille(permille: Int) {
        val book = currentBook ?: return
        val totalCh = chaptersList.size.coerceAtLeast(1)
        val target = resolveSeekTarget(chaptersList, totalCh, permille)
        if (target.chapterIndex != currentChapterIndex) {
            // Seeking across chapters is a direct jump — never inflates Tracker high-water
            openChapter(
                chapterIndex = target.chapterIndex,
                initialScrollY = 0,
                isDirectJump = true,
                pendingSeekFraction = target.intraChapterFraction
            )
        } else {
            val range = getScrollableRangePx()
            val targetScrollY = if (range > 0) {
                (range * target.intraChapterFraction).roundToInt().coerceIn(0, range)
            } else 0
            isProgrammaticScrollOrLoad = true
            readingScrollView.scrollTo(0, targetScrollY)
            currentScrollY = targetScrollY
            initialChapterLoadScrollY = targetScrollY
            isProgrammaticScrollOrLoad = false
            updateFullBookProgressIndicators()
            repository.saveReadingProgressAsync(book.id, currentChapterIndex, targetScrollY, updateOpenRecency = true)
        }
    }

    private fun clearSearchFocus() {
        searchEditText.clearFocus()
        onRequestWindowFocusable?.invoke(false)
    }

    private fun updateMinimalHeaderTitle(chapterTitleOverride: String? = null) {
        val chTitle = chapterTitleOverride
            ?: chaptersList.getOrNull(currentChapterIndex)?.title?.takeIf { it.isNotBlank() }
            ?: "Chapter ${currentChapterIndex + 1}"
        // Display current chapter title in the shared floating window top bar
        minimalHeaderTitleState = chTitle
        chapterTitleTopView.text = chTitle
        onChapterTitleChanged?.invoke(chTitle)
        val bookName = resolveDisplayBookName(currentBook)
        toolbarBookTitleTextState = bookName
        toolbarBookTitleView.text = bookName
    }

    private fun updateBookmarkIconState() {
        val bookId = currentBook?.id ?: -1
        val bookmarked = ReaderBookmarkStore.isChapterBookmarked(appContext, bookId, currentChapterIndex)
        bookmarkToolbarIcon.setImageResource(
            if (bookmarked) R.drawable.ic_bookmark_ribbon else R.drawable.ic_bookmark_outline
        )
        bookmarkToolbarIcon.setColorFilter(
            Color.parseColor(if (bookmarked) "#89B4FA" else "#E2E5F0")
        )
        bookmarkToggleBtn.text = if (bookmarked) {
            "Remove Bookmark (Ch. ${currentChapterIndex + 1})"
        } else {
            "+ Bookmark Ch. ${currentChapterIndex + 1}"
        }
    }

    fun toggleCurrentChapterBookmark(): Boolean {
        val book = currentBook ?: return false
        val title = chaptersList.getOrNull(currentChapterIndex)?.title?.takeIf { it.isNotBlank() }
            ?: "Chapter ${currentChapterIndex + 1}"
        val added = ReaderBookmarkStore.toggleChapterBookmark(
            context = appContext,
            bookId = book.id,
            chapterIndex = currentChapterIndex,
            chapterTitle = title,
            scrollY = currentScrollY
        )
        updateBookmarkIconState()
        try {
            Toast.makeText(
                appContext,
                if (added) "Bookmarked $title" else "Bookmark removed",
                Toast.LENGTH_SHORT
            )?.show()
        } catch (_: Throwable) {}
        return added
    }

    private fun toggleBookmarkOverlay() {
        if (bookmarkOverlayLayout.visibility == View.VISIBLE) {
            bookmarkOverlayLayout.visibility = View.GONE
        } else {
            updateBookmarkIconState()
            renderBookmarksList()
            bookmarkOverlayLayout.visibility = View.VISIBLE
        }
    }

    private fun renderBookmarksList() {
        bookmarkItemsContainer.removeAllViews()
        val bookId = currentBook?.id ?: return
        val bookmarks = ReaderBookmarkStore.getBookmarks(appContext, bookId)
        if (bookmarks.isEmpty()) {
            bookmarkItemsContainer.addView(TextView(appContext).apply {
                text = "No bookmarks for this book yet."
                textSize = 12f
                setTextColor(Color.parseColor("#9BA3B8"))
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(24), dp(12), dp(24))
            })
            return
        }

        for (bm in bookmarks) {
            val row = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(46)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A1B26"))
                    cornerRadius = dpF(8f)
                    setStroke(1, Color.parseColor("#2A2C3B"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(6)
                }
                setPadding(dp(10), dp(8), dp(6), dp(8))
                isClickable = true
                setOnClickListener {
                    bookmarkOverlayLayout.visibility = View.GONE
                    // Bookmark jumps update ONLY resume position — never inflate Tracker high-water!
                    openChapter(bm.chapterIndex, initialScrollY = bm.scrollY, isDirectJump = true)
                }
            }

            val col = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(appContext).apply {
                text = "${bm.chapterIndex + 1}. ${bm.chapterTitle}"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#F5F5F7"))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            col.addView(TextView(appContext).apply {
                text = "Scroll offset: ${bm.scrollY}px"
                textSize = 11f
                setTextColor(Color.parseColor("#9BA3B8"))
            })
            row.addView(col)

            row.addView(ImageButton(appContext).apply {
                setImageResource(R.drawable.ic_close_small)
                setColorFilter(Color.parseColor("#9BA3B8"))
                contentDescription = "Delete bookmark"
                background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                setOnClickListener {
                    ReaderBookmarkStore.removeBookmark(appContext, bookId, bm.id)
                    updateBookmarkIconState()
                    renderBookmarksList()
                }
            })
            bookmarkItemsContainer.addView(row)
        }
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
        val displayTitle = resolveDisplayBookName(book)
        toolbarBookTitleTextState = displayTitle
        toolbarBookTitleView.text = displayTitle
        onActiveBookChanged?.invoke(book)
        setBottomToolbarVisible(false) // Toolbar always hidden by default on reading start
        tocOverlayLayout.visibility = View.GONE
        bookmarkOverlayLayout.visibility = View.GONE
        searchBookOverlayLayout.visibility = View.GONE

        val prefs = appContext.getSharedPreferences(ReaderPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        currentFontSizeSp = when {
            fontSizeSp != null && fontSizeSp in 12f..28f &&
                !(fontSizeSp == 16f && prefs.contains(ReaderPreferences.KEY_DEFAULT_FONT_SIZE)) -> fontSizeSp
            prefs.contains(ReaderPreferences.KEY_DEFAULT_FONT_SIZE) -> ReaderPreferences.getDefaultFontSizeSp(appContext)
            else -> ReaderPreferences.getDefaultFontSizeSp(appContext)
        }
        applyFontSize()
        applyReaderPreferences()

        loadBookJob?.cancel()
        loadBookJob = viewScope.launch {
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
        isInitialRestore: Boolean = false,
        pendingSeekFraction: Float? = null
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

        updateMinimalHeaderTitle(title)
        chapterHeadingView.text = title
        updateBookmarkIconState()
        updateFullBookProgressIndicators()

        prevChapterButton.isEnabled = chapterIndex > 0
        nextChapterButton.isEnabled = chapterIndex < chaptersList.size - 1
        tocAdapter.submitChapters(chaptersList, currentChapterIndex)

        openChapterJob?.cancel()
        openChapterJob = viewScope.launch {
            rawChapterText = repository.getChapterText(book.id, chapterIndex)
            readingContentTextView.text = rawChapterText
            chapterOpenedAtMs = nowMs()

            fun applyRestoredScrollPosition(): Int {
                val range = getScrollableRangePx()
                val resolvedY = if (pendingSeekFraction != null && range > 0) {
                    (range * pendingSeekFraction).roundToInt().coerceIn(0, range)
                } else {
                    initialScrollY
                }
                readingScrollView.scrollTo(0, resolvedY)
                currentScrollY = resolvedY
                initialChapterLoadScrollY = resolvedY
                updateFullBookProgressIndicators()
                return resolvedY
            }

            var postedHandled = false
            val posted = readingScrollView.post {
                val needsLayoutWait = (initialScrollY > 0 || pendingSeekFraction != null) &&
                    getScrollableRangePx() <= 0 &&
                    readingContentTextView.height == 0
                if (needsLayoutWait) {
                    val layoutListener = object : View.OnLayoutChangeListener {
                        override fun onLayoutChange(
                            v: View?,
                            left: Int,
                            top: Int,
                            right: Int,
                            bottom: Int,
                            oldLeft: Int,
                            oldTop: Int,
                            oldRight: Int,
                            oldBottom: Int
                        ) {
                            if (readingScrollView.height > 0 && readingContentTextView.height > 0) {
                                readingContentTextView.removeOnLayoutChangeListener(this)
                                val resolvedY = applyRestoredScrollPosition()
                                isProgrammaticScrollOrLoad = false
                                if (pendingSeekFraction != null && resolvedY != initialScrollY) {
                                    repository.saveReadingProgressAsync(book.id, chapterIndex, resolvedY, updateOpenRecency = true)
                                }
                            }
                        }
                    }
                    readingContentTextView.addOnLayoutChangeListener(layoutListener)
                }
                val resolvedY = applyRestoredScrollPosition()
                if (!needsLayoutWait) {
                    isProgrammaticScrollOrLoad = false
                }
                if (pendingSeekFraction != null && resolvedY != initialScrollY) {
                    repository.saveReadingProgressAsync(book.id, chapterIndex, resolvedY, updateOpenRecency = true)
                }
                postedHandled = true
            }
            if (!posted && !postedHandled) {
                readingScrollView.scrollTo(0, initialScrollY)
                currentScrollY = initialScrollY
                initialChapterLoadScrollY = initialScrollY
                isProgrammaticScrollOrLoad = false
                updateFullBookProgressIndicators()
            }

            repository.saveResumePosition(
                bookId = book.id,
                chapterIndex = chapterIndex,
                scrollY = currentScrollY,
                updateOpenRecency = true
            )

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
        val chapterSnapshot = currentChapterIndex
        val scrollSnapshot = currentScrollY
        scrollSaveJob?.cancel()
        scrollSaveJob = viewScope.launch {
            delay(200L)
            repository.saveResumePosition(book.id, chapterSnapshot, scrollSnapshot, updateOpenRecency = true)
        }
    }

    fun saveCurrentProgressImmediately() {
        val book = currentBook ?: return
        scrollSaveJob?.cancel()
        repository.saveSyncResumeCheckpoint(
            bookId = book.id,
            chapterIndex = currentChapterIndex,
            scrollY = currentScrollY,
            commitImmediately = true
        )
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
            searchCountView.text = "0"
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
            spannable.setSpan(BackgroundColorSpan(color), offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
            try { Toast.makeText(appContext, "Enter a search term first.", Toast.LENGTH_SHORT)?.show() } catch (_: Throwable) {}
            return
        }
        val book = currentBook ?: return
        clearSearchFocus()

        searchBookItemsContainer.removeAllViews()
        searchBookItemsContainer.addView(TextView(appContext).apply {
            text = "Searching across all chapters..."
            textSize = 13f
            setTextColor(Color.parseColor("#9BA3B8"))
            setPadding(dp(12), dp(18), dp(12), dp(18))
        })
        searchBookOverlayLayout.visibility = View.VISIBLE

        viewScope.launch {
            val results = repository.searchInBook(book.id, query)
            searchBookItemsContainer.removeAllViews()

            if (results.isEmpty()) {
                searchBookItemsContainer.addView(TextView(appContext).apply {
                    text = "No matches found for '$query'"
                    textSize = 13f
                    setTextColor(Color.parseColor("#9BA3B8"))
                    setPadding(dp(12), dp(18), dp(12), dp(18))
                })
                return@launch
            }

            for (res in results) {
                val itemCard = LinearLayout(appContext).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1B1C27"))
                        cornerRadius = dpF(8f)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(dp(2), dp(3), dp(2), dp(3))
                    }
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    isClickable = true
                    setOnClickListener {
                        searchBookOverlayLayout.visibility = View.GONE
                        openChapter(res.chapterIndex, initialScrollY = 0, isDirectJump = true)
                    }
                }
                itemCard.addView(TextView(appContext).apply {
                    text = "${res.chapterIndex + 1}. ${res.chapterTitle}"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#89B4FA"))
                })
                itemCard.addView(TextView(appContext).apply {
                    text = res.snippet
                    textSize = 12f
                    setTextColor(Color.parseColor("#CDD6F4"))
                    setPadding(0, dp(4), 0, 0)
                })
                searchBookItemsContainer.addView(itemCard)
            }
        }
    }

    // --- Auto-Scroll Feature ---
    private fun toggleAutoScrollBar() {
        if (autoScrollBarLayout.visibility == View.VISIBLE) {
            autoScrollBarLayout.visibility = View.GONE
            stopAutoScroll()
        } else {
            autoScrollBarLayout.visibility = View.VISIBLE
            searchBarLayout.visibility = View.GONE
            ttsBarLayout.visibility = View.GONE
        }
    }

    private fun toggleAutoScrollPlayPause() {
        if (isAutoScrollRunning) stopAutoScroll() else startAutoScroll()
    }

    private fun startAutoScroll() {
        isAutoScrollRunning = true
        autoScrollPlayPauseButton.text = "⏸ Pause"
        autoScrollPlayPauseButton.background = GradientDrawable().apply {
            setColor(Color.parseColor("#FAB387"))
            cornerRadius = dpF(8f)
        }
        mainHandler.post(autoScrollRunnable)
    }

    fun stopAutoScroll() {
        isAutoScrollRunning = false
        autoScrollPlayPauseButton.text = "▶ Play"
        autoScrollPlayPauseButton.background = GradientDrawable().apply {
            setColor(Color.parseColor("#89B4FA"))
            cornerRadius = dpF(8f)
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
            autoScrollBarLayout.visibility = View.GONE
        }
    }

    private fun toggleTtsPlayPause() {
        if (isTtsSpeaking) pauseTts() else startTts()
    }

    private fun startTts() {
        if (!isTtsInitialized || textToSpeech == null) {
            try { Toast.makeText(appContext, "TTS not initialized.", Toast.LENGTH_SHORT)?.show() } catch (_: Throwable) {}
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
        try { textToSpeech?.shutdown() } catch (_: Throwable) {}
        textToSpeech = null
        scrollSaveJob?.cancel()
        loadBookJob?.cancel()
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

    // Testing helpers
    fun isViewScopeActiveForTesting(): Boolean =
        viewScope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true

    internal suspend fun awaitPendingJobsForTesting() {
        loadBookJob?.join()
        openChapterJob?.join()
        highWaterJob?.join()
        undoJob?.join()
    }

    fun isAutoScrollRunningForTesting(): Boolean = isAutoScrollRunning
    fun isTtsSpeakingForTesting(): Boolean = isTtsSpeaking
    fun isUndoBannerVisibleForTesting(): Boolean = undoBannerLayout.visibility == View.VISIBLE
    fun getLastHighWaterUpdateForTesting(): HighWaterProgressUpdate? = lastHighWaterUpdate
    fun getTocRecyclerViewForTesting(): RecyclerView? = tocRecyclerView
    fun isBottomToolbarVisibleForTesting(): Boolean = isBottomToolbarVisible
    fun isTopProgressLineVisibleForTesting(): Boolean = isTopProgressLineVisible
    fun hasFontControlsInToolbarForTesting(): Boolean = hasFontControlsInToolbar
    private var bottomToolbarBottomPaddingPx: Int = 0
    fun getBottomToolbarBottomPaddingPxForTesting(): Int = bottomToolbarBottomPaddingPx
    fun getMinimalHeaderTitleForTesting(): String =
        chapterTitleTopView.text?.toString()?.takeIf { it.isNotEmpty() } ?: minimalHeaderTitleState
    fun getTopHeaderVerticalPaddingPxForTesting(): Int = topHeaderVerticalPaddingPx
    fun getToolbarBookTitleViewForTesting(): TextView = toolbarBookTitleView
    fun getToolbarBookTitleTextForTesting(): String =
        toolbarBookTitleView.text?.toString()?.takeIf { it.isNotEmpty() } ?: toolbarBookTitleTextState
    fun getToolbarBookTitleMaxLinesForTesting(): Int =
        toolbarBookTitleView.maxLines.takeIf { it > 0 } ?: toolbarBookTitleMaxLinesState
    fun getNavSeekRowForTesting(): LinearLayout = navSeekRow
    fun getNavSeekRowChildrenForTesting(): List<View> = navSeekRowChildrenForTesting.toList()
    fun getTopHeaderLayoutForTesting(): LinearLayout = topHeaderLayout
    fun getBottomToolbarIconRowForTesting(): LinearLayout = iconRowLayout
    fun getBottomToolbarIconDescriptionsForTesting(): List<String> = iconRowActionsForTesting.map { it.second }
    fun getCurrentFullBookProgressPermilleForTesting(): Int = currentFullBookProgressPermille
    fun getFullBookProgressLabelForTesting(): String = fullBookProgressLabelView.text?.toString() ?: ""
    fun getFullBookSeekBarForTesting(): SeekBar = fullBookSeekBar
    fun getTopBookProgressBarForTesting(): ProgressBar = topBookProgressBar
    fun getPrevChapterButtonForTesting(): Button = prevChapterButton
    fun getNextChapterButtonForTesting(): Button = nextChapterButton
    fun getPrevChapterContentDescriptionForTesting(): String =
        prevChapterButton.contentDescription?.toString()?.takeIf { it.isNotEmpty() } ?: prevChapterContentDescState
    fun getNextChapterContentDescriptionForTesting(): String =
        nextChapterButton.contentDescription?.toString()?.takeIf { it.isNotEmpty() } ?: nextChapterContentDescState
    fun navigateBackToLibraryForTesting() = navigateBackToLibrary()
    fun refreshProgressIndicatorsForTesting(scrollableRangePx: Int = dp(800)) {
        updateFullBookProgressIndicators(scrollableRangeOverridePx = scrollableRangePx)
    }
    fun simulateSeekBarDragTickForTesting(permille: Int) {
        isUserSeekingFullBook = true
        pendingSeekPermille = permille
        updateSeekPreviewLabel(permille)
    }
    fun simulateSeekBarReleaseForTesting(permille: Int) {
        isUserSeekingFullBook = false
        pendingSeekPermille = permille
        commitSeekToFullBookPermille(permille)
    }
    fun handleCleanTapOnReadingAreaForTesting() = toggleBottomToolbar()
    fun dispatchTouchOnReadingAreaForTesting(event: MotionEvent): Boolean {
        handleReadingTouchEvent(event)
        return false
    }
    fun dispatchTouchCoordinatesForTesting(
        actionMasked: Int,
        rawX: Float,
        rawY: Float,
        eventTimeMs: Long,
        hasSelection: Boolean = false
    ) {
        handleReadingTouchRaw(actionMasked, rawX, rawY, eventTimeMs, hasSelectionOverride = hasSelection)
    }
    fun getBookmarksForTesting(): List<ReaderBookmark> {
        val id = currentBook?.id ?: return emptyList()
        return ReaderBookmarkStore.getBookmarks(appContext, id)
    }

    class TocChapterAdapter(
        private val onChapterClicked: (ChapterMeta) -> Unit
    ) : RecyclerView.Adapter<TocChapterAdapter.TocViewHolder>() {

        private var items: List<ChapterMeta> = emptyList()
        private var currentIndex: Int = 0

        fun submitChapters(newItems: List<ChapterMeta>, activeChapterIndex: Int) {
            items = newItems
            currentIndex = activeChapterIndex
            try { notifyDataSetChanged() } catch (_: Throwable) {}
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
                    setColor(Color.parseColor("#0B0B10"))
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
                if (isCurrent) Color.parseColor("#89B4FA") else Color.parseColor("#F5F5F7")
            )
            holder.badgeText.visibility = if (isCurrent) View.VISIBLE else View.GONE

            holder.row.background = GradientDrawable().apply {
                setColor(if (isCurrent) Color.parseColor("#212232") else Color.parseColor("#181922"))
                cornerRadius = 8 * density
                if (isCurrent) {
                    setStroke((1.5f * density).toInt().coerceAtLeast(1), Color.parseColor("#89B4FA"))
                }
            }
            holder.row.setOnClickListener { onChapterClicked(item) }
        }

        override fun getItemCount(): Int = items.size

        class TocViewHolder(
            val row: LinearLayout,
            val lineText: TextView,
            val badgeText: TextView
        ) : RecyclerView.ViewHolder(row)
    }
}
