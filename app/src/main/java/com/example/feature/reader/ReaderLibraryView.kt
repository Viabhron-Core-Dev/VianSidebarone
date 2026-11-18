package com.example.feature.reader

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.R
import com.example.core.LogKeeper
import com.example.data.EpubBook
import com.example.data.LibraryRepository
import com.example.feature.floating.EmbeddedImeBridge
import com.example.feature.floating.EmbeddedInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ReaderLibraryView: Compact dark Library interface inside the floating Reader mini-app.
 *
 * Structure:
 * - Two compact tabs: `Recent` (last 10 opened books ordered by `lastOpenedTimestamp DESC` by default)
 *   and `File Explorer` (embedded [ReaderFolderBrowserView]).
 * - Slim sort toggle (`Opened`, `Title`, `Progress`), `+ File` import chip, and expandable search bar.
 * - Root-anchored bottom-right Continue Reading FAB (visible on both tabs whenever a last-read book exists,
 *   positioned cleanly above the floating window `[ × | — | ↘ ]` sticker).
 * - Active-book-only binary awareness: older metadata-only books show a subtle badge and prompt
 *   cleanly for re-import instead of opening an empty reading view.
 */
class ReaderLibraryView(
    context: Context,
    private val instanceId: String? = null,
    private val onRequestWindowFocusable: ((Boolean) -> Unit)? = null,
    private val onOpenFolderBrowser: (() -> Unit)? = null,
    private val onOpenTracker: ((Int) -> Unit)? = null,
    private val onOpenReaderSettings: (() -> Unit)? = null,
    private val onBookSelected: (EpubBook) -> Unit
) : FrameLayout(context) {

    companion object {
        fun filterAndSortBooks(
            books: List<EpubBook>,
            query: String,
            sortOption: ReaderLibrarySortOption
        ): List<EpubBook> {
            val cleanQuery = query.trim()
            val filtered = if (cleanQuery.isEmpty()) {
                books
            } else {
                books.filter {
                    it.title.contains(cleanQuery, ignoreCase = true) ||
                        it.author.contains(cleanQuery, ignoreCase = true)
                }
            }
            return when (sortOption) {
                ReaderLibrarySortOption.RECENTLY_OPENED -> filtered.sortedWith(
                    compareByDescending<EpubBook> { it.lastOpenedTimestamp }
                        .thenByDescending { it.id }
                )
                ReaderLibrarySortOption.TITLE -> filtered.sortedWith(
                    compareBy<EpubBook, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
                        .thenByDescending { it.lastOpenedTimestamp }
                )
                ReaderLibrarySortOption.PROGRESS -> filtered.sortedWith(
                    compareByDescending<EpubBook> {
                        val total = it.totalChapters.coerceAtLeast(1)
                        ((it.lastReadChapter + 1).toFloat() / total.toFloat())
                    }.thenByDescending { it.lastOpenedTimestamp }
                )
            }
        }
    }

    private val appContext: Context = context
    private val density: Float = (context.resources.displayMetrics.density).takeIf { it > 0f } ?: 2f
    private fun dp(v: Int): Int = (v * density).toInt()
    private fun dpF(v: Float): Float = v * density

    private val viewScope = CoroutineScope(SupervisorJob() + ReaderDispatchers.main)
    private val repository = LibraryRepository(context)
    private var loadBooksJob: Job? = null
    private var bookTapJob: Job? = null

    var activeTab: ReaderLibraryTab = ReaderLibraryTab.RECENT
        private set
    var sortOption: ReaderLibrarySortOption = ReaderLibrarySortOption.RECENTLY_OPENED
        private set
    private var isSearchExpanded: Boolean = false
    private var isDetailsPanelVisible: Boolean = false
    private var areRecentControlsHidden: Boolean = false
    private var isContinueFabVisibleState: Boolean = false
    private var continueFabLabelTextState: String = "▶"
    private var continueFabContentDescState: String = "Continue Reading"
    private var isUtilityStickerVisibleState: Boolean = false
    private var bookListScrollBottomPaddingPx: Int = 0
    private val hasLibraryHeaderBarState: Boolean = false
    private var currentFilterQuery: String = ""
    private var selectedBookId: Int = -1

    private var recentBooks = listOf<EpubBook>()
    private var allBooks = listOf<EpubBook>()

    private val rootLayout: LinearLayout
    private val rootLayoutChildrenForTesting = mutableListOf<View>()
    private val tabBarView: LinearLayout
    private val recentTabButton: Button
    private val explorerTabButton: Button
    private val searchRowLayout: LinearLayout
    private val searchBox: EmbeddedInputEditText

    private val recentContentContainer: FrameLayout
    private val bookListScrollView: ScrollView
    private val booksContainer: LinearLayout
    private val emptyStateContainer: LinearLayout
    private val explorerContainer: FrameLayout
    private val embeddedExplorerView: ReaderFolderBrowserView
    private val continueFab: LinearLayout

    private val detailsOverlay: FrameLayout
    private var detailsView: ReaderBookDetailsView? = null

    private val eventListener = {
        loadBooks()
    }

    init {
        setBackgroundColor(Color.parseColor("#0B0B10"))

        rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setPadding(dp(5), dp(2), dp(5), 0)
        }

        // --- Compact Segmented Tab Bar: Recent vs File Explorer ---
        tabBarView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#13131B"))
                cornerRadius = dpF(9f)
                setStroke(1, Color.parseColor("#232432"))
            }
            setPadding(dp(2), dp(2), dp(2), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(2)
            }
        }
        val tabBar = tabBarView

        recentTabButton = Button(context).apply {
            text = "Recent (0)"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(30)
            minimumHeight = dp(30)
            setPadding(dp(6), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { selectTab(ReaderLibraryTab.RECENT) }
        }
        tabBar.addView(recentTabButton)

        explorerTabButton = Button(context).apply {
            text = "File Explorer"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(30)
            minimumHeight = dp(30)
            setPadding(dp(6), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { selectTab(ReaderLibraryTab.FILE_EXPLORER) }
        }
        tabBar.addView(explorerTabButton)
        rootLayout.addView(tabBar)
        rootLayoutChildrenForTesting.add(tabBar)

        // --- Expandable Search Row (hidden by default to maximize vertical space) ---
        searchRowLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(4)
            }
        }

        searchBox = EmbeddedInputEditText(context) { instanceId }.apply {
            hint = "Filter by title or author..."
            setHintTextColor(Color.parseColor("#5B6078"))
            setTextColor(Color.parseColor("#F5F5F7"))
            textSize = 13f
            isSingleLine = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#161620"))
                cornerRadius = dpF(8f)
                setStroke(1, Color.parseColor("#2B2C3C"))
            }
            setPadding(dp(10), dp(7), dp(10), dp(7))
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
                    currentFilterQuery = s?.toString()?.trim() ?: ""
                    renderBooksList()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        searchRowLayout.addView(searchBox)

        val clearSearchBtn = ImageButton(context).apply {
            setImageResource(R.drawable.ic_close_small)
            setColorFilter(Color.parseColor("#9BA3B8"))
            contentDescription = "Clear search filter"
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
            }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener {
                searchBox.setText("")
                currentFilterQuery = ""
                isSearchExpanded = false
                searchRowLayout.visibility = View.GONE
                clearSearchFocus()
                renderBooksList()
            }
        }
        searchRowLayout.addView(clearSearchBtn)
        rootLayout.addView(searchRowLayout)
        rootLayoutChildrenForTesting.add(searchRowLayout)

        // --- Recent Tab Content (ScrollView + Empty State) ---
        recentContentContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        bookListScrollBottomPaddingPx = dp(86)
        bookListScrollView = ScrollView(context).apply {
            clipToPadding = false
            setPadding(0, 0, 0, bookListScrollBottomPaddingPx) // Clears Continue FAB (82dp) and [ × | — | ↘ ] (42dp) cleanly without bottom strip
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        booksContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        bookListScrollView.addView(booksContainer)
        recentContentContainer.addView(bookListScrollView)

        emptyStateContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        emptyStateContainer.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_library_books)
            setColorFilter(Color.parseColor("#89B4FA"))
            contentDescription = "Empty Library"
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                bottomMargin = dp(8)
            }
        })
        emptyStateContainer.addView(TextView(context).apply {
            text = "No recently opened books"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F5F5F7"))
            gravity = Gravity.CENTER
        })
        emptyStateContainer.addView(TextView(context).apply {
            text = "Open a book from File Explorer or import an EPUB / TXT file."
            textSize = 12f
            setTextColor(Color.parseColor("#9BA3B8"))
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(12))
        })

        emptyStateContainer.addView(Button(context).apply {
            text = "Open File Explorer"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0B0B10"))
            minHeight = dp(42)
            minimumHeight = dp(42)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#89B4FA"))
                cornerRadius = dpF(10f)
            }
            setPadding(dp(16), dp(6), dp(16), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
            setOnClickListener { selectTab(ReaderLibraryTab.FILE_EXPLORER) }
        })

        emptyStateContainer.addView(Button(context).apply {
            text = "+ Import Book File"
            textSize = 12f
            setTextColor(Color.parseColor("#F5F5F7"))
            minHeight = dp(40)
            minimumHeight = dp(40)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A25"))
                setStroke(1, Color.parseColor("#2E3042"))
                cornerRadius = dpF(10f)
            }
            setPadding(dp(16), dp(6), dp(16), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
            setOnClickListener { launchImportActivity(openAfterImport = true) }
        })

        emptyStateContainer.addView(Button(context).apply {
            text = "Load Sample Classic"
            textSize = 11f
            setTextColor(Color.parseColor("#89B4FA"))
            minHeight = dp(38)
            minimumHeight = dp(38)
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(1, Color.parseColor("#2E3042"))
                cornerRadius = dpF(10f)
            }
            setPadding(dp(14), dp(4), dp(14), dp(4))
            setOnClickListener { loadBundledSampleBook() }
        })
        recentContentContainer.addView(emptyStateContainer)
        rootLayout.addView(recentContentContainer)
        rootLayoutChildrenForTesting.add(recentContentContainer)

        // --- Embedded File Explorer Tab ---
        explorerContainer = FrameLayout(context).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        embeddedExplorerView = ReaderFolderBrowserView(
            context = context,
            instanceId = instanceId,
            onBackToLibrary = { selectTab(ReaderLibraryTab.RECENT) },
            onBookImported = { importedBook ->
                selectedBookId = importedBook.id
                onBookSelected(importedBook)
            },
            onRequestWindowFocusable = { focusable ->
                onRequestWindowFocusable?.invoke(focusable)
            },
            embeddedInLibrary = true
        )
        explorerContainer.addView(embeddedExplorerView)
        rootLayout.addView(explorerContainer)
        rootLayoutChildrenForTesting.add(explorerContainer)

        addView(rootLayout)

        // --- Root-Anchored Circular Play-Only Continue Button (visible across Recent and File Explorer tabs) ---
        continueFab = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            contentDescription = "Continue Reading"
            isClickable = true
            isFocusable = true
            elevation = dpF(6f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#89B4FA"))
                setStroke(1, Color.parseColor("#B4BEFE"))
            }
            layoutParams = LayoutParams(
                dp(38),
                dp(38),
                Gravity.BOTTOM or Gravity.END
            ).apply {
                marginEnd = dp(10)
                bottomMargin = dp(44) // Positioned cleanly above bottom-right [ × | — | ↘ ] sticker
            }
            addView(TextView(context).apply {
                text = "▶"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#0B0B10"))
                setPadding(dp(2), 0, 0, 0)
            })
            setOnClickListener {
                triggerContinueFabClick()
            }
        }
        addView(continueFab)

        // --- Current-Book Details Panel Overlay ---
        detailsOverlay = FrameLayout(context).apply {
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(detailsOverlay)

        updateTabVisuals()
        ReaderEventManager.registerListener(eventListener)
        loadBooks()
    }

    fun selectTab(tab: ReaderLibraryTab) {
        activeTab = tab
        clearSearchFocus()
        updateTabVisuals()
        if (tab == ReaderLibraryTab.RECENT) {
            renderBooksList()
        } else {
            embeddedExplorerView.refreshCurrentFolder()
        }
    }

    private fun updateTabVisuals() {
        val isRecent = activeTab == ReaderLibraryTab.RECENT
        recentTabButton.text = "Recent (${recentBooks.size})"
        explorerTabButton.text = "File Explorer"

        recentTabButton.background = GradientDrawable().apply {
            setColor(Color.parseColor(if (isRecent) "#89B4FA" else "#13131B"))
            cornerRadius = dpF(8f)
        }
        recentTabButton.setTextColor(if (isRecent) Color.parseColor("#0B0B10") else Color.parseColor("#9BA3B8"))

        explorerTabButton.background = GradientDrawable().apply {
            setColor(Color.parseColor(if (!isRecent) "#89B4FA" else "#13131B"))
            cornerRadius = dpF(8f)
        }
        explorerTabButton.setTextColor(if (!isRecent) Color.parseColor("#0B0B10") else Color.parseColor("#9BA3B8"))

        areRecentControlsHidden = !isRecent
        isUtilityStickerVisibleState = false
        searchRowLayout.visibility = if (isRecent && isSearchExpanded) View.VISIBLE else View.GONE
        recentContentContainer.visibility = if (isRecent) View.VISIBLE else View.GONE
        explorerContainer.visibility = if (isRecent) View.GONE else View.VISIBLE
        updateContinueFabVisibility()
    }

    private fun getLastReadBookForContinue(): EpubBook? {
        val lastActiveId = repository.getLastActiveReaderBookId()
        if (lastActiveId > 0) {
            allBooks.firstOrNull { it.id == lastActiveId }?.let { return it }
            recentBooks.firstOrNull { it.id == lastActiveId }?.let { return it }
            return null
        }
        val activeBinaryId = repository.getActiveBinaryBookId()
        if (activeBinaryId > 0) {
            allBooks.firstOrNull { it.id == activeBinaryId && it.lastOpenedTimestamp > 0L }?.let { return it }
            recentBooks.firstOrNull { it.id == activeBinaryId && it.lastOpenedTimestamp > 0L }?.let { return it }
        }
        return recentBooks.firstOrNull { it.lastOpenedTimestamp > 0L }
    }

    private fun triggerContinueFabClick() {
        val target = getLastReadBookForContinue() ?: return
        clearSearchFocus()
        embeddedExplorerView.clearSearchFocus()
        handleBookTap(target)
    }

    private fun updateContinueFabVisibility() {
        val shouldShow = getLastReadBookForContinue() != null && !isDetailsPanelVisible
        isContinueFabVisibleState = shouldShow
        continueFab.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun cycleSortOption() {
        val values = ReaderLibrarySortOption.entries
        sortOption = values[(sortOption.ordinal + 1) % values.size]
        renderBooksList()
    }

    private fun clearSearchFocus() {
        searchBox.clearFocus()
        EmbeddedImeBridge.clearFocusRecursive(searchRowLayout)
        onRequestWindowFocusable?.invoke(false)
    }

    fun getPreferredDetailsBook(): EpubBook? {
        val explicit = if (selectedBookId > 0) {
            recentBooks.firstOrNull { it.id == selectedBookId }
                ?: allBooks.firstOrNull { it.id == selectedBookId }
        } else null
        return explicit ?: recentBooks.firstOrNull() ?: allBooks.firstOrNull()
    }

    fun setSelectedBookId(bookId: Int) {
        selectedBookId = bookId
    }

    fun openBookDetails(book: EpubBook) {
        selectedBookId = book.id
        clearSearchFocus()
        val panel = detailsView ?: ReaderBookDetailsView(
            context = appContext,
            instanceId = instanceId,
            onRequestWindowFocusable = { focusable -> onRequestWindowFocusable?.invoke(focusable) },
            onClose = { closeBookDetails() },
            onReadBook = { b ->
                closeBookDetails()
                handleBookTap(b)
            },
            onRequestReimport = { b ->
                closeBookDetails()
                launchImportActivity(reimportBookId = b.id, openAfterImport = true)
            },
            onOpenTracker = { epubId ->
                if (onOpenTracker != null) {
                    onOpenTracker.invoke(epubId)
                } else if (epubId > 0) {
                    BookTrackerActivity.launchForEpubBook(appContext, epubId)
                } else {
                    BookTrackerActivity.launch(appContext)
                }
            },
            onMetadataSaved = { _, _ ->
                loadBooks()
            }
        ).also {
            detailsView = it
            detailsOverlay.removeAllViews()
            detailsOverlay.addView(it)
        }
        panel.bindBook(book)
        isDetailsPanelVisible = true
        detailsOverlay.visibility = View.VISIBLE
        isUtilityStickerVisibleState = false
        updateContinueFabVisibility()
    }

    fun closeBookDetails() {
        detailsView?.clearInputFocus()
        isDetailsPanelVisible = false
        detailsOverlay.visibility = View.GONE
        updateTabVisuals()
    }

    fun loadBooks() {
        loadBooksJob?.cancel()
        loadBooksJob = viewScope.launch {
            try {
                recentBooks = repository.getRecentBooks()
                allBooks = repository.getAllBooks()
                if (selectedBookId <= 0) {
                    selectedBookId = recentBooks.firstOrNull()?.id ?: allBooks.firstOrNull()?.id ?: -1
                }
                updateTabVisuals()
                renderBooksList()
            } catch (_: Throwable) {}
        }
    }

    private fun renderBooksList() {
        booksContainer.removeAllViews()
        if (activeTab != ReaderLibraryTab.RECENT) return

        val filtered = filterAndSortBooks(recentBooks, currentFilterQuery, sortOption)

        if (filtered.isEmpty()) {
            if (recentBooks.isEmpty()) {
                emptyStateContainer.visibility = View.VISIBLE
                bookListScrollView.visibility = View.GONE
            } else {
                emptyStateContainer.visibility = View.GONE
                bookListScrollView.visibility = View.VISIBLE
                booksContainer.addView(TextView(appContext).apply {
                    text = "No recent books matching '$currentFilterQuery'"
                    textSize = 12f
                    setTextColor(Color.parseColor("#9BA3B8"))
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(28), dp(16), dp(16))
                })
            }
            return
        }

        emptyStateContainer.visibility = View.GONE
        bookListScrollView.visibility = View.VISIBLE
        val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())

        for (book in filtered) {
            val hasBinary = repository.hasBookBinary(book)
            val isSelected = book.id == selectedBookId
            val bookCard = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(38)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (isSelected) "#171926" else "#12121A"))
                    cornerRadius = dpF(8f)
                    setStroke(1, Color.parseColor(if (isSelected) "#3B4A6E" else "#212230"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dp(1), dp(1), dp(1), dp(1))
                }
                setPadding(dp(6), dp(3), dp(2), dp(3))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    clearSearchFocus()
                    handleBookTap(book)
                }
            }

            // Compact left accent initial
            val bookBadge = TextView(appContext).apply {
                text = book.title.take(1).uppercase()
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#0B0B10"))
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    val colors = intArrayOf(
                        Color.parseColor("#F38BA8"),
                        Color.parseColor("#FAB387"),
                        Color.parseColor("#A6E3A1"),
                        Color.parseColor("#89B4FA"),
                        Color.parseColor("#CBA6F7")
                    )
                    val colorIndex = Math.abs(book.title.hashCode()) % colors.size
                    setColor(colors[colorIndex])
                    cornerRadius = dpF(5f)
                }
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(26)).apply {
                    marginEnd = dp(6)
                }
            }
            bookCard.addView(bookBadge)

            // Center compact metadata
            val detailsLayout = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            detailsLayout.addView(TextView(appContext).apply {
                text = book.title
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#F5F5F7"))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })

            val totalCh = book.totalChapters.coerceAtLeast(1)
            val currentCh = (book.lastReadChapter + 1).coerceIn(1, totalCh)
            val progressPercent = ((currentCh.toFloat() / totalCh.toFloat()) * 100).toInt()
            val authorPart = if (book.author.isNotBlank()) "${book.author} • " else ""
            val datePart = if (book.lastOpenedTimestamp > 0L) dateFormat.format(Date(book.lastOpenedTimestamp)) else ""
            val statusPart = if (!hasBinary) "Re-import required" else "Ch. $currentCh/$totalCh ($progressPercent%)"

            detailsLayout.addView(TextView(appContext).apply {
                text = "$authorPart$statusPart${if (datePart.isNotEmpty()) " • $datePart" else ""}"
                textSize = 10f
                setTextColor(Color.parseColor(if (hasBinary) "#9BA3B8" else "#F9E2AF"))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, 0, 0, dp(1))
            })

            detailsLayout.addView(ProgressBar(appContext, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = progressPercent
                progressDrawable?.setColorFilter(
                    Color.parseColor(if (hasBinary) "#89B4FA" else "#6C7086"),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(3).coerceAtLeast(3)
                )
            })
            bookCard.addView(detailsLayout)

            // Compact Book Details icon button
            bookCard.addView(ImageButton(appContext).apply {
                setImageResource(R.drawable.ic_book)
                setColorFilter(Color.parseColor("#89B4FA"))
                contentDescription = "Details for ${book.title}"
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(38))
                setOnClickListener { openBookDetails(book) }
            })

            // Compact Delete icon button
            bookCard.addView(ImageButton(appContext).apply {
                setImageResource(R.drawable.ic_close_small)
                setColorFilter(Color.parseColor("#7F849C"))
                contentDescription = "Remove ${book.title}"
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(38))
                setOnClickListener { showDeleteConfirmation(book) }
            })

            booksContainer.addView(bookCard)
        }
    }

    private fun handleBookTap(book: EpubBook) {
        selectedBookId = book.id
        if (repository.hasBookBinary(book)) {
            bookTapJob?.cancel()
            bookTapJob = viewScope.launch {
                try {
                    repository.markBookBinaryActive(book.id)
                } catch (_: Throwable) {}
                onBookSelected(book)
            }
        } else {
            promptReimportForBook(book)
        }
    }

    fun promptReimportForBook(book: EpubBook) {
        val totalCh = book.totalChapters.coerceAtLeast(1)
        val resumeCh = (book.lastReadChapter + 1).coerceIn(1, totalCh)
        val message = "'${book.title}' keeps your progress (Ch. $resumeCh/$totalCh) and Tracker history, " +
            "but its source file was cleaned up when switching active books. Re-import the EPUB/TXT file to continue reading."
        try {
            AlertDialog.Builder(appContext)
                .setTitle("Re-import Book File")
                .setMessage(message)
                .setPositiveButton("Re-import File") { _, _ ->
                    launchImportActivity(reimportBookId = book.id, openAfterImport = true)
                }
                .setNeutralButton("File Explorer") { _, _ ->
                    selectTab(ReaderLibraryTab.FILE_EXPLORER)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (_: Throwable) {
            try { Toast.makeText(appContext, message, Toast.LENGTH_LONG)?.show() } catch (_: Throwable) {}
        }
    }

    private fun showDeleteConfirmation(book: EpubBook) {
        try {
            AlertDialog.Builder(appContext)
                .setTitle("Delete Book")
                .setMessage("Remove '${book.title}' from Reader? (Linked Book Tracker history will be kept.)")
                .setPositiveButton("Delete") { _, _ ->
                    viewScope.launch {
                        repository.deleteBook(book)
                        loadBooks()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (_: Throwable) {
            viewScope.launch {
                repository.deleteBook(book)
                loadBooks()
            }
        }
    }

    private fun launchImportActivity(reimportBookId: Int = -1, openAfterImport: Boolean = true) {
        try {
            val intent = Intent(appContext, ReaderImportActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (reimportBookId > 0) {
                    putExtra(ReaderImportActivity.EXTRA_REIMPORT_BOOK_ID, reimportBookId)
                }
                putExtra(ReaderImportActivity.EXTRA_OPEN_AFTER_IMPORT, openAfterImport)
            }
            appContext.startActivity(intent)
        } catch (e: Exception) {
            LogKeeper.log(appContext, "ReaderLibraryView", "Failed to start ReaderImportActivity: ${e.message}")
            try { Toast.makeText(appContext, "Cannot open file picker.", Toast.LENGTH_SHORT)?.show() } catch (_: Throwable) {}
        }
    }

    private fun loadBundledSampleBook() {
        viewScope.launch {
            try { Toast.makeText(appContext, "Creating sample classic...", Toast.LENGTH_SHORT)?.show() } catch (_: Throwable) {}
            val sampleEpub = withContext(Dispatchers.IO) { createSampleClassicEpub() }
            if (sampleEpub != null) {
                repository.importBookFile(sampleEpub, "The Time Machine", markOpenedOnImport = true)
                loadBooks()
                try { Toast.makeText(appContext, "Sample loaded!", Toast.LENGTH_SHORT)?.show() } catch (_: Throwable) {}
            }
        }
    }

    private fun createSampleClassicEpub(): File? {
        return try {
            val sampleFile = File(appContext.cacheDir, "sample_the_time_machine.epub")
            if (sampleFile.exists()) sampleFile.delete()

            ZipOutputStream(FileOutputStream(sampleFile)).use { zos ->
                zos.putNextEntry(ZipEntry("mimetype"))
                zos.write("application/epub+zip".toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("META-INF/container.xml"))
                zos.write(
                    """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>""".toByteArray()
                )
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
                zos.write(
                    """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="pub-id" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>The Time Machine</dc:title>
    <dc:creator>H. G. Wells</dc:creator>
  </metadata>
  <manifest>
    <item id="ch1" href="text/chapter1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="text/chapter2.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch3" href="text/chapter3.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
    <itemref idref="ch3"/>
  </spine>
</package>""".toByteArray()
                )
                zos.closeEntry()

                val chapters = listOf(
                    "OEBPS/text/chapter1.xhtml" to "Chapter 1: The Machine" to
                        "The Time Traveller was expounding a recondite matter to us. His grey eyes shone and twinkled.",
                    "OEBPS/text/chapter2.xhtml" to "Chapter 2: The Voyage" to
                        "I took the starting lever in one hand and the stopping one in the other.",
                    "OEBPS/text/chapter3.xhtml" to "Chapter 3: The Golden Age" to
                        "In another moment I was in a vast green world under an unfamiliar sun."
                )
                for ((pathAndTitle, body) in chapters) {
                    val (path, chTitle) = pathAndTitle
                    zos.putNextEntry(ZipEntry(path))
                    zos.write("<!DOCTYPE html><html><head><title>$chTitle</title></head><body><h1>$chTitle</h1><p>$body</p></body></html>".toByteArray())
                    zos.closeEntry()
                }
            }
            sampleFile
        } catch (_: Exception) {
            null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ReaderEventManager.registerListener(eventListener)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ReaderEventManager.unregisterListener(eventListener)
    }

    fun releaseResources() {
        ReaderEventManager.unregisterListener(eventListener)
        loadBooksJob?.cancel()
        bookTapJob?.cancel()
        detailsView?.releaseResources()
        embeddedExplorerView.releaseResources()
        viewScope.cancel()
    }

    // Testing helpers
    fun isViewScopeActiveForTesting(): Boolean =
        viewScope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true

    suspend fun awaitPendingJobsForTesting() {
        loadBooksJob?.join()
        bookTapJob?.join()
        detailsView?.awaitPendingJobsForTesting()
    }

    fun getRecentBooksForTesting(): List<EpubBook> = recentBooks
    fun getAllBooksForTesting(): List<EpubBook> = allBooks
    fun getActiveTabForTesting(): ReaderLibraryTab = activeTab
    fun getSortOptionForTesting(): ReaderLibrarySortOption = sortOption
    fun isDetailsOpenForTesting(): Boolean = isDetailsPanelVisible
    fun isSearchExpandedForTesting(): Boolean = isSearchExpanded
    fun isRecentControlsHiddenForTesting(): Boolean = areRecentControlsHidden

    fun getEmbeddedExplorerForTesting(): ReaderFolderBrowserView = embeddedExplorerView
    fun getRootContentColumnForTesting(): LinearLayout = rootLayout
    fun getRootContentColumnChildrenForTesting(): List<View> = rootLayoutChildrenForTesting.toList()
    fun getRootLayoutBottomPaddingPxForTesting(): Int = rootLayout.paddingBottom
    fun getTabBarViewForTesting(): LinearLayout = tabBarView
    fun hasBottomUtilityBarForTesting(): Boolean = false
    fun isUtilityStickerVisibleForTesting(): Boolean = isUtilityStickerVisibleState
    fun hasHeaderBarForTesting(): Boolean = hasLibraryHeaderBarState
    fun getContinueFabForTesting(): View = continueFab
    fun getContinueFabLabelTextForTesting(): String = continueFabLabelTextState
    fun getContinueFabContentDescForTesting(): String = continueFabContentDescState
    fun isContinueFabVisibleForTesting(): Boolean = isContinueFabVisibleState
    fun clickContinueFabForTesting() {
        if (!isContinueFabVisibleState) return
        triggerContinueFabClick()
    }
    fun getBookListScrollViewBottomPaddingPxForTesting(): Int = bookListScrollBottomPaddingPx
    fun setRecentBooksForTesting(books: List<EpubBook>) {
        recentBooks = books
        allBooks = books
        updateTabVisuals()
        renderBooksList()
    }

    fun setSearchQueryForTesting(query: String) {
        isSearchExpanded = true
        searchRowLayout.visibility = View.VISIBLE
        searchBox.setText(query)
        currentFilterQuery = query.trim()
        renderBooksList()
    }
    fun cycleSortOptionForTesting() = cycleSortOption()
    fun openBookDetailsForTesting(book: EpubBook) = openBookDetails(book)
    fun closeBookDetailsForTesting() = closeBookDetails()
}
