package com.example.feature.reader

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.core.LogKeeper
import com.example.data.EpubBook
import com.example.data.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * ReaderLibraryView: The library shelf interface hosted inside the floating Reader mini-app.
 * Displays recent and imported books, reading progress, search filtering, and import affordances.
 */
class ReaderLibraryView(
    context: Context,
    private val onBookSelected: (EpubBook) -> Unit
) : FrameLayout(context) {

    private val viewScope = CoroutineScope(SupervisorJob() + ReaderDispatchers.main)
    private val repository = LibraryRepository(context)

    private val booksContainer: LinearLayout
    private val emptyStateContainer: LinearLayout
    private val searchBox: EditText
    private val bookListScrollView: ScrollView

    private var allBooks = listOf<EpubBook>()
    private var currentFilterQuery = ""

    private val eventListener = {
        loadBooks()
    }

    init {
        setBackgroundColor(Color.parseColor("#181825")) // Catppuccin Base

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setPadding(16, 16, 16, 16)
        }

        // --- Header Bar ---
        val headerBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 12)
        }

        val titleView = TextView(context).apply {
            text = "eBook Reader"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#CDD6F4")) // Text color
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerBar.addView(titleView)

        val importButton = Button(context).apply {
            text = "+ Import"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#89B4FA")) // Blue accent
                cornerRadius = 16f
            }
            setPadding(24, 8, 24, 8)
            setOnClickListener {
                launchImportActivity()
            }
        }
        headerBar.addView(importButton)
        rootLayout.addView(headerBar)

        // --- Search Bar ---
        searchBox = EditText(context).apply {
            hint = "Search library..."
            setHintTextColor(Color.parseColor("#6C7086"))
            setTextColor(Color.WHITE)
            textSize = 13f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#313244"))
                cornerRadius = 12f
            }
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(8, 0, 8, 12)
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
        rootLayout.addView(searchBox)

        // --- Scrollable Books List ---
        bookListScrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        booksContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        bookListScrollView.addView(booksContainer)
        rootLayout.addView(bookListScrollView)

        // --- Empty State Container ---
        emptyStateContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setPadding(32, 32, 32, 32)
        }

        val emptyIcon = ImageView(context).apply {
            setImageResource(com.example.R.drawable.ic_library_books)
            setColorFilter(Color.parseColor("#89B4FA"))
            layoutParams = LinearLayout.LayoutParams(72, 72).apply {
                bottomMargin = 16
            }
        }
        emptyStateContainer.addView(emptyIcon)

        val emptyText = TextView(context).apply {
            text = "Your library is empty"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#CDD6F4"))
            gravity = Gravity.CENTER
        }
        emptyStateContainer.addView(emptyText)

        val emptySubText = TextView(context).apply {
            text = "Import EPUB or TXT books to start reading in this floating window"
            textSize = 12f
            setTextColor(Color.parseColor("#A6ADC8"))
            gravity = Gravity.CENTER
            setPadding(16, 8, 16, 24)
        }
        emptyStateContainer.addView(emptySubText)

        val emptyImportButton = Button(context).apply {
            text = "+ Import Book"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#89B4FA"))
                cornerRadius = 16f
            }
            setPadding(32, 12, 32, 12)
            setOnClickListener {
                launchImportActivity()
            }
        }
        emptyStateContainer.addView(emptyImportButton)

        // Sample Classic loader button for instant out-of-the-box demonstration
        val sampleButton = Button(context).apply {
            text = "Load Sample Classic"
            textSize = 11f
            setTextColor(Color.parseColor("#89B4FA"))
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(2, Color.parseColor("#45475A"))
                cornerRadius = 16f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
            }
            setPadding(24, 8, 24, 8)
            setOnClickListener {
                loadBundledSampleBook()
            }
        }
        emptyStateContainer.addView(sampleButton)

        rootLayout.addView(emptyStateContainer)
        addView(rootLayout)

        ReaderEventManager.registerListener(eventListener)
        loadBooks()
    }

    fun loadBooks() {
        viewScope.launch {
            allBooks = repository.getBooks()
            renderBooksList()
        }
    }

    private fun renderBooksList() {
        booksContainer.removeAllViews()

        val filtered = if (currentFilterQuery.isBlank()) {
            allBooks
        } else {
            allBooks.filter { it.title.contains(currentFilterQuery, ignoreCase = true) }
        }

        if (filtered.isEmpty()) {
            if (allBooks.isEmpty()) {
                emptyStateContainer.visibility = View.VISIBLE
                bookListScrollView.visibility = View.GONE
            } else {
                emptyStateContainer.visibility = View.GONE
                bookListScrollView.visibility = View.VISIBLE
                val noMatchView = TextView(context).apply {
                    text = "No books matching '$currentFilterQuery'"
                    textSize = 13f
                    setTextColor(Color.parseColor("#A6ADC8"))
                    gravity = Gravity.CENTER
                    setPadding(16, 48, 16, 16)
                }
                booksContainer.addView(noMatchView)
            }
            return
        }

        emptyStateContainer.visibility = View.GONE
        bookListScrollView.visibility = View.VISIBLE

        val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

        for (book in filtered) {
            val bookCard = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E1E2E")) // Surface
                    cornerRadius = 14f
                    setStroke(1, Color.parseColor("#313244"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(8, 6, 8, 6)
                }
                setPadding(16, 16, 16, 16)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    onBookSelected(book)
                }
            }

            // Left book badge
            val bookBadge = TextView(context).apply {
                text = book.title.take(1).uppercase()
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
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
                    cornerRadius = 10f
                }
                layoutParams = LinearLayout.LayoutParams(44, 52).apply {
                    marginEnd = 16
                }
            }
            bookCard.addView(bookBadge)

            // Center details
            val detailsLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleView = TextView(context).apply {
                text = book.title
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#CDD6F4"))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            detailsLayout.addView(titleView)

            val totalCh = book.totalChapters.coerceAtLeast(1)
            val currentCh = (book.lastReadChapter + 1).coerceAtMost(totalCh)
            val progressPercent = ((currentCh.toFloat() / totalCh.toFloat()) * 100).toInt()

            val progressText = TextView(context).apply {
                text = if (book.isParsed) {
                    "Ch. $currentCh of $totalCh • $progressPercent%"
                } else {
                    "Processing chapters..."
                }
                textSize = 11f
                setTextColor(Color.parseColor("#A6ADC8"))
                setPadding(0, 4, 0, 4)
            }
            detailsLayout.addView(progressText)

            // Progress bar
            val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = progressPercent
                progressDrawable?.setColorFilter(Color.parseColor("#89B4FA"), android.graphics.PorterDuff.Mode.SRC_IN)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    6
                ).apply {
                    topMargin = 2
                    bottomMargin = 4
                }
            }
            detailsLayout.addView(progressBar)

            val dateText = TextView(context).apply {
                text = "Opened ${dateFormat.format(Date(book.lastOpenedTimestamp))}"
                textSize = 10f
                setTextColor(Color.parseColor("#6C7086"))
            }
            detailsLayout.addView(dateText)

            bookCard.addView(detailsLayout)

            // Right delete button
            val deleteButton = Button(context).apply {
                text = "✕"
                textSize = 12f
                setTextColor(Color.parseColor("#F38BA8"))
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = 8f
                }
                layoutParams = LinearLayout.LayoutParams(38, 38).apply {
                    marginStart = 8
                }
                setOnClickListener {
                    showDeleteConfirmation(book)
                }
            }
            bookCard.addView(deleteButton)

            booksContainer.addView(bookCard)
        }
    }

    private fun showDeleteConfirmation(book: EpubBook) {
        try {
            AlertDialog.Builder(context)
                .setTitle("Delete Book")
                .setMessage("Remove '${book.title}' and its saved reading progress from your library?")
                .setPositiveButton("Delete") { _, _ ->
                    viewScope.launch {
                        repository.deleteBook(book)
                        loadBooks()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (_: Throwable) {
            // Direct delete if Dialog cannot attach to window token
            viewScope.launch {
                repository.deleteBook(book)
                loadBooks()
            }
        }
    }

    private fun launchImportActivity() {
        try {
            val intent = Intent(context, ReaderImportActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            LogKeeper.log(context, "ReaderLibraryView", "Failed to start ReaderImportActivity: ${e.message}")
            Toast.makeText(context, "Cannot open file picker.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadBundledSampleBook() {
        viewScope.launch {
            Toast.makeText(context, "Creating sample classic...", Toast.LENGTH_SHORT).show()
            val sampleEpub = withContext(Dispatchers.IO) {
                createSampleClassicEpub()
            }
            if (sampleEpub != null) {
                repository.importBookFile(sampleEpub, "The Time Machine")
                loadBooks()
                Toast.makeText(context, "Sample loaded!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Generates a valid sample EPUB on the fly with proper OPF and chapters to test immediately.
     */
    private fun createSampleClassicEpub(): File? {
        return try {
            val sampleFile = File(context.cacheDir, "sample_the_time_machine.epub")
            if (sampleFile.exists()) sampleFile.delete()

            ZipOutputStream(FileOutputStream(sampleFile)).use { zos ->
                // mimetype
                zos.putNextEntry(ZipEntry("mimetype"))
                zos.write("application/epub+zip".toByteArray())
                zos.closeEntry()

                // META-INF/container.xml
                zos.putNextEntry(ZipEntry("META-INF/container.xml"))
                val containerXml = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""
                zos.write(containerXml.toByteArray())
                zos.closeEntry()

                // OEBPS/content.opf
                zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
                val opfContent = """<?xml version="1.0" encoding="UTF-8"?>
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
</package>"""
                zos.write(opfContent.toByteArray())
                zos.closeEntry()

                // Chapters
                val ch1 = """<!DOCTYPE html><html><head><title>Chapter 1: The Machine</title></head><body>
<h1>Chapter 1: The Machine</h1>
<p>The Time Traveller was expounding a recondite matter to us. His grey eyes shone and twinkled, and his usually pale face was flushed and animated.</p>
<p>The fire burned brightly, and the soft radiance of the incandescent lights in the lilies of silver caught the bubbles that flashed and passed in our glasses.</p>
<p>"You must follow me carefully. I shall have to controvert one or two ideas that are almost universally accepted. The geometry, for instance, they taught you at school is founded on a misconception."</p>
</body></html>"""
                zos.putNextEntry(ZipEntry("OEBPS/text/chapter1.xhtml"))
                zos.write(ch1.toByteArray())
                zos.closeEntry()

                val ch2 = """<!DOCTYPE html><html><head><title>Chapter 2: The Voyage</title></head><body>
<h1>Chapter 2: The Voyage</h1>
<p>I told some of you that I had finished the machine. It stood in the laboratory, its nickel bars gleaming under the skylight.</p>
<p>I took the starting lever in one hand and the stopping one in the other, pressed the first, and almost immediately the second. I seemed to reel; I felt a nightmare sensation of falling.</p>
<p>The laboratory grew faint and hazy, and then disappeared into an incredible grey mist of whirling shadows.</p>
</body></html>"""
                zos.putNextEntry(ZipEntry("OEBPS/text/chapter2.xhtml"))
                zos.write(ch2.toByteArray())
                zos.closeEntry()

                val ch3 = """<!DOCTYPE html><html><head><title>Chapter 3: The Golden Age</title></head><body>
<h1>Chapter 3: The Golden Age</h1>
<p>In another moment I was in a vast green world under an unfamiliar sun. Gigantic silver palaces rose through lush tropical foliage.</p>
<p>Creatures clad in soft garments of purple and white gathered around the Time Machine, gazing with curious laughter and offering garlands of blossoms.</p>
<p>I had arrived in the year Eight Hundred and Two Thousand, Seven Hundred and One.</p>
</body></html>"""
                zos.putNextEntry(ZipEntry("OEBPS/text/chapter3.xhtml"))
                zos.write(ch3.toByteArray())
                zos.closeEntry()
            }
            sampleFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ReaderEventManager.unregisterListener(eventListener)
        viewScope.cancel()
    }
}
