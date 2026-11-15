package com.example.feature.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.R
import com.example.data.EpubBook
import com.example.data.LibraryRepository
import com.example.data.TrackerBook
import com.example.feature.floating.EmbeddedImeBridge
import com.example.feature.floating.EmbeddedInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * ReaderBookDetailsView: Current-book details & metadata editor panel inside the floating Reader Library.
 *
 * Replaces LiteReader's Quick Notes action with single-source-of-truth book metadata backed by
 * [LibraryRepository.getOrCreateLinkedTrackerBook] and [LibraryRepository.saveTrackerMetadataEdit],
 * keeping Reader resume position strictly separate from Tracker high-water progress and showing
 * active-book binary availability clearly.
 */
class ReaderBookDetailsView(
    context: Context,
    private val instanceId: String? = null,
    private val onRequestWindowFocusable: ((Boolean) -> Unit)? = null,
    private val onClose: () -> Unit,
    private val onReadBook: (EpubBook) -> Unit,
    private val onRequestReimport: (EpubBook) -> Unit,
    private val onOpenTracker: (Int) -> Unit,
    private val onMetadataSaved: ((EpubBook?, TrackerBook) -> Unit)? = null
) : FrameLayout(context) {

    private val appContext: Context = context
    private val density: Float = (context.resources.displayMetrics.density).takeIf { it > 0f } ?: 2f
    private fun dp(v: Int): Int = (v * density).toInt()
    private fun dpF(v: Float): Float = v * density

    private val viewScope = CoroutineScope(SupervisorJob() + ReaderDispatchers.main)
    private val repository = LibraryRepository(context)

    private var currentEpub: EpubBook? = null
    private var currentTracker: TrackerBook? = null
    private var loadJob: Job? = null
    private var saveJob: Job? = null

    private var formTitle: String = ""
    private var formAuthor: String = ""
    private var formReadChaptersText: String = "0"
    private var formTotalChaptersText: String = "0"
    private var formGenres: String = ""
    private var formRatingText: String = "0.0"
    private var formComment: String = ""
    private var formIsFinished: Boolean = false
    private var formIsWebNovel: Boolean = false

    private val headerTitleView: TextView
    private val statusResumeView: TextView
    private val statusBinaryView: TextView
    private val saveStatusView: TextView

    private val titleInput: EmbeddedInputEditText
    private val authorInput: EmbeddedInputEditText
    private val readChaptersInput: EmbeddedInputEditText
    private val totalChaptersInput: EmbeddedInputEditText
    private val genresInput: EmbeddedInputEditText
    private val ratingInput: EmbeddedInputEditText
    private val commentInput: EmbeddedInputEditText
    private val finishedCheckBox: CheckBox
    private val webNovelCheckBox: CheckBox

    private val primaryActionButton: Button
    private val saveButton: Button
    private val openTrackerButton: Button

    init {
        setBackgroundColor(Color.parseColor("#0B0B10"))
        isClickable = true

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setPadding(dp(12), dp(8), dp(12), dp(46)) // 46dp clearance for bottom-right [ × — ↘ ] sticker
        }

        // Compact header bar
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }

        val backBtn = ImageButton(context).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(Color.parseColor("#F5F5F7"))
            contentDescription = "Back to Library"
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#16161E"))
                cornerRadius = dpF(8f)
            }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                clearInputFocus()
                onClose()
            }
        }
        header.addView(backBtn)

        headerTitleView = TextView(context).apply {
            text = "Book Details"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F5F5F7"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(headerTitleView)

        openTrackerButton = Button(context).apply {
            text = "Full Tracker"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#89B4FA"))
            minHeight = dp(40)
            minimumHeight = dp(40)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#181822"))
                cornerRadius = dpF(8f)
                setStroke(1, Color.parseColor("#2B2C3B"))
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                clearInputFocus()
                onOpenTracker(currentEpub?.id ?: -1)
            }
        }
        header.addView(openTrackerButton)
        root.addView(header)

        // Scrollable form body
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        // Status summary card (Reader Resume vs Tracker High-Water + Binary Status)
        val statusCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#13131A"))
                cornerRadius = dpF(10f)
                setStroke(1, Color.parseColor("#252635"))
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }

        statusResumeView = TextView(context).apply {
            text = "Reader Resume: — • Tracker High-Water: —"
            textSize = 11f
            setTextColor(Color.parseColor("#9BA3B8"))
        }
        statusCard.addView(statusResumeView)

        statusBinaryView = TextView(context).apply {
            text = "Binary: Checking..."
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#A6E3A1"))
            setPadding(0, dp(4), 0, 0)
        }
        statusCard.addView(statusBinaryView)
        body.addView(statusCard)

        fun addFieldLabel(label: String) {
            body.addView(TextView(context).apply {
                text = label
                textSize = 11f
                setTextColor(Color.parseColor("#9BA3B8"))
                setPadding(dp(2), dp(2), dp(2), dp(3))
            })
        }

        fun createEditor(hintText: String, inputTypeFlags: Int = InputType.TYPE_CLASS_TEXT, singleLine: Boolean = true): EmbeddedInputEditText {
            return EmbeddedInputEditText(context) { instanceId }.apply {
                hint = hintText
                setHintTextColor(Color.parseColor("#5B6078"))
                setTextColor(Color.parseColor("#F5F5F7"))
                textSize = 13f
                isSingleLine = singleLine
                inputType = inputTypeFlags
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#171720"))
                    cornerRadius = dpF(8f)
                    setStroke(1, Color.parseColor("#2A2B3A"))
                }
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
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
                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        onRequestWindowFocusable?.invoke(true)
                    }
                }
            }
        }

        addFieldLabel("Title")
        titleInput = createEditor("Book title")
        body.addView(titleInput)

        addFieldLabel("Author")
        authorInput = createEditor("Author")
        body.addView(authorInput)

        // Side-by-side Read Chapters / Total Chapters
        val chaptersRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val readCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        }
        readCol.addView(TextView(context).apply {
            text = "Read Ch. (Tracker)"
            textSize = 11f
            setTextColor(Color.parseColor("#9BA3B8"))
            setPadding(dp(2), dp(2), dp(2), dp(3))
        })
        readChaptersInput = createEditor("0", InputType.TYPE_CLASS_NUMBER)
        readCol.addView(readChaptersInput)
        chaptersRow.addView(readCol)

        val totalCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        totalCol.addView(TextView(context).apply {
            text = "Total Ch."
            textSize = 11f
            setTextColor(Color.parseColor("#9BA3B8"))
            setPadding(dp(2), dp(2), dp(2), dp(3))
        })
        totalChaptersInput = createEditor("0", InputType.TYPE_CLASS_NUMBER)
        totalCol.addView(totalChaptersInput)
        chaptersRow.addView(totalCol)
        body.addView(chaptersRow)

        // Genres + Rating row
        val metaRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val genresCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f).apply {
                marginEnd = dp(6)
            }
        }
        genresCol.addView(TextView(context).apply {
            text = "Genres (comma-separated)"
            textSize = 11f
            setTextColor(Color.parseColor("#9BA3B8"))
            setPadding(dp(2), dp(2), dp(2), dp(3))
        })
        genresInput = createEditor("Fantasy, Mystery")
        genresCol.addView(genresInput)
        metaRow.addView(genresCol)

        val ratingCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        ratingCol.addView(TextView(context).apply {
            text = "Rating (0-5)"
            textSize = 11f
            setTextColor(Color.parseColor("#9BA3B8"))
            setPadding(dp(2), dp(2), dp(2), dp(3))
        })
        ratingInput = createEditor("0.0", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        ratingCol.addView(ratingInput)
        metaRow.addView(ratingCol)
        body.addView(metaRow)

        addFieldLabel("Notes / Comment")
        commentInput = createEditor(
            "Reading notes or review...",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            singleLine = false
        ).apply {
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
        }
        body.addView(commentInput)

        // Flags row
        val flagsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(6))
        }
        finishedCheckBox = CheckBox(context).apply {
            text = "Finished"
            textSize = 12f
            setTextColor(Color.parseColor("#F5F5F7"))
            setOnCheckedChangeListener { _, isChecked -> formIsFinished = isChecked }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(16)
            }
        }
        flagsRow.addView(finishedCheckBox)

        webNovelCheckBox = CheckBox(context).apply {
            text = "Web Novel"
            textSize = 12f
            setTextColor(Color.parseColor("#F5F5F7"))
            setOnCheckedChangeListener { _, isChecked -> formIsWebNovel = isChecked }
        }
        flagsRow.addView(webNovelCheckBox)
        body.addView(flagsRow)

        saveStatusView = TextView(context).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.parseColor("#A6E3A1"))
            visibility = View.GONE
            setPadding(dp(2), 0, dp(2), dp(6))
        }
        body.addView(saveStatusView)

        scroll.addView(body)
        root.addView(scroll)

        // Bottom action bar: Read/Re-import + Save
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        primaryActionButton = Button(context).apply {
            text = "Read Book"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F5F5F7"))
            minHeight = dp(44)
            minimumHeight = dp(44)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#222331"))
                cornerRadius = dpF(8f)
                setStroke(1, Color.parseColor("#35374B"))
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                clearInputFocus()
                val book = currentEpub ?: return@setOnClickListener
                if (repository.hasBookBinary(book)) {
                    onReadBook(book)
                } else {
                    onRequestReimport(book)
                }
            }
        }
        actionsRow.addView(primaryActionButton)

        saveButton = Button(context).apply {
            text = "Save Metadata"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0B0B10"))
            minHeight = dp(44)
            minimumHeight = dp(44)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#89B4FA"))
                cornerRadius = dpF(8f)
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                clearInputFocus()
                saveChanges()
            }
        }
        actionsRow.addView(saveButton)

        root.addView(actionsRow)
        addView(root)
    }

    fun bindBook(book: EpubBook) {
        currentEpub = book
        currentTracker = null
        saveStatusView.visibility = View.GONE
        headerTitleView.text = book.title.ifBlank { "Book Details" }
        applyBookToFields(book, null)

        loadJob?.cancel()
        loadJob = viewScope.launch {
            val freshEpub = repository.getBook(book.id) ?: book
            val tracker = repository.getOrCreateLinkedTrackerBook(freshEpub.id)
            currentEpub = freshEpub
            currentTracker = tracker
            applyBookToFields(freshEpub, tracker)
        }
    }

    private fun applyBookToFields(book: EpubBook, tracker: TrackerBook?) {
        val totalCh = maxOf(book.totalChapters, tracker?.totalChapters ?: 0)
        val resumeCh = (book.lastReadChapter + 1).coerceIn(1, totalCh.coerceAtLeast(1))
        val highWater = tracker?.readChapters ?: 0
        val hasBinary = repository.hasBookBinary(book)

        statusResumeView.text = "Reader Resume: Ch. $resumeCh / ${totalCh.coerceAtLeast(1)} (scroll ${book.lastReadScrollY}px) • Tracker High-Water: $highWater / ${totalCh.coerceAtLeast(1)}"
        if (hasBinary) {
            statusBinaryView.text = "Binary: On device (Active reading copy)"
            statusBinaryView.setTextColor(Color.parseColor("#A6E3A1"))
            primaryActionButton.text = "Read Book"
        } else {
            statusBinaryView.text = "Binary: Metadata only (Re-import file to read)"
            statusBinaryView.setTextColor(Color.parseColor("#F9E2AF"))
            primaryActionButton.text = "Re-import File"
        }

        formTitle = tracker?.title ?: book.title
        formAuthor = tracker?.author ?: book.author
        formReadChaptersText = (tracker?.readChapters ?: 0).toString()
        formTotalChaptersText = totalCh.toString()
        formGenres = tracker?.genres ?: ""
        formRatingText = (tracker?.rating ?: 0f).toString()
        formComment = tracker?.comment ?: ""
        formIsFinished = tracker?.isFinished ?: false
        formIsWebNovel = tracker?.isWebNovel ?: false

        titleInput.setText(formTitle)
        authorInput.setText(formAuthor)
        readChaptersInput.setText(formReadChaptersText)
        totalChaptersInput.setText(formTotalChaptersText)
        genresInput.setText(formGenres)
        ratingInput.setText(formRatingText)
        commentInput.setText(formComment)
        finishedCheckBox.isChecked = formIsFinished
        webNovelCheckBox.isChecked = formIsWebNovel
    }

    fun saveChanges() {
        saveJob?.cancel()
        saveJob = viewScope.launch {
            val saved = saveChangesInternal()
            if (saved != null) {
                saveStatusView.text = "Saved metadata to Book Tracker & Library."
                saveStatusView.setTextColor(Color.parseColor("#A6E3A1"))
                saveStatusView.visibility = View.VISIBLE
                try {
                    Toast.makeText(appContext, "Book details saved", Toast.LENGTH_SHORT)?.show()
                } catch (_: Throwable) {}
            }
        }
    }

    suspend fun saveChangesInternal(): TrackerBook? {
        val epub = currentEpub ?: return null
        val baseTracker = currentTracker
            ?: repository.getOrCreateLinkedTrackerBook(epub.id)
            ?: return null

        val rawTitle = titleInput.text?.toString()?.takeIf { it.isNotBlank() } ?: formTitle
        val rawAuthor = authorInput.text?.toString() ?: formAuthor
        val rawTotalText = totalChaptersInput.text?.toString()?.takeIf { it.isNotBlank() } ?: formTotalChaptersText
        val rawReadText = readChaptersInput.text?.toString()?.takeIf { it.isNotBlank() } ?: formReadChaptersText
        val rawGenres = genresInput.text?.toString() ?: formGenres
        val rawRatingText = ratingInput.text?.toString()?.takeIf { it.isNotBlank() } ?: formRatingText
        val rawComment = commentInput.text?.toString() ?: formComment
        val rawFinished = finishedCheckBox.isChecked || formIsFinished
        val rawWebNovel = webNovelCheckBox.isChecked || formIsWebNovel

        val cleanTitle = rawTitle.trim().ifEmpty { epub.title.ifBlank { "Untitled" } }
        val cleanAuthor = rawAuthor.trim()
        val parsedTotal = rawTotalText.trim().toIntOrNull()?.coerceAtLeast(0)
            ?: maxOf(baseTracker.totalChapters, epub.totalChapters)
        val parsedRead = rawReadText.trim().toIntOrNull()?.coerceAtLeast(0)
            ?: baseTracker.readChapters
        val cappedRead = if (parsedTotal > 0) parsedRead.coerceAtMost(parsedTotal) else parsedRead
        val parsedRating = rawRatingText.trim().toFloatOrNull()?.coerceIn(0f, 5f)
            ?: baseTracker.rating

        val updatedTracker = baseTracker.copy(
            title = cleanTitle,
            author = cleanAuthor,
            readChapters = cappedRead,
            totalChapters = parsedTotal,
            genres = rawGenres.trim(),
            rating = parsedRating,
            comment = rawComment.trim(),
            isFinished = rawFinished,
            isWebNovel = rawWebNovel
        )
        val persisted = repository.saveTrackerMetadataEdit(updatedTracker)
        val updatedEpub = repository.getBook(epub.id) ?: epub
        currentEpub = updatedEpub
        currentTracker = persisted
        headerTitleView.text = updatedEpub.title.ifBlank { "Book Details" }
        applyBookToFields(updatedEpub, persisted)
        onMetadataSaved?.invoke(updatedEpub, persisted)
        return persisted
    }

    fun clearInputFocus() {
        EmbeddedImeBridge.clearFocusRecursive(this)
        onRequestWindowFocusable?.invoke(false)
    }

    override fun onDetachedFromWindow() {
        clearInputFocus()
        super.onDetachedFromWindow()
    }

    fun releaseResources() {
        clearInputFocus()
        loadJob?.cancel()
        saveJob?.cancel()
        viewScope.cancel()
    }

    // Testing helpers
    suspend fun awaitPendingJobsForTesting() {
        loadJob?.join()
        saveJob?.join()
    }

    fun setFormFieldsForTesting(
        title: String? = null,
        author: String? = null,
        readChapters: Int? = null,
        totalChapters: Int? = null,
        genres: String? = null,
        rating: Float? = null,
        comment: String? = null,
        isFinished: Boolean? = null,
        isWebNovel: Boolean? = null
    ) {
        title?.let { formTitle = it; titleInput.setText(it) }
        author?.let { formAuthor = it; authorInput.setText(it) }
        readChapters?.let { formReadChaptersText = it.toString(); readChaptersInput.setText(it.toString()) }
        totalChapters?.let { formTotalChaptersText = it.toString(); totalChaptersInput.setText(it.toString()) }
        genres?.let { formGenres = it; genresInput.setText(it) }
        rating?.let { formRatingText = it.toString(); ratingInput.setText(it.toString()) }
        comment?.let { formComment = it; commentInput.setText(it) }
        isFinished?.let { formIsFinished = it; finishedCheckBox.isChecked = it }
        isWebNovel?.let { formIsWebNovel = it; webNovelCheckBox.isChecked = it }
    }

    fun getCurrentTrackerForTesting(): TrackerBook? = currentTracker
    fun getCurrentEpubForTesting(): EpubBook? = currentEpub
    fun getStatusBinaryTextForTesting(): String = statusBinaryView.text?.toString() ?: ""
}
