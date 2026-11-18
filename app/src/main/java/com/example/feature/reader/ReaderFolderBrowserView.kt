package com.example.feature.reader

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import com.example.R
import com.example.core.LogKeeper
import com.example.data.EpubBook
import com.example.data.EpubParser
import com.example.data.LibraryRepository
import com.example.feature.floating.EmbeddedImeBridge
import com.example.feature.floating.EmbeddedInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * In-app SAF folder explorer for Reader in Main process.
 * Navigates folders granted via ACTION_OPEN_DOCUMENT_TREE, filters .epub/.txt books,
 * searches filenames, and imports tapped files cleanly into Room via LibraryRepository.
 */
class ReaderFolderBrowserView(
    context: Context,
    private val instanceId: String? = null,
    private val onBackToLibrary: () -> Unit,
    private val onBookImported: (EpubBook) -> Unit,
    private val onRequestWindowFocusable: ((Boolean) -> Unit)? = null,
    private val embeddedInLibrary: Boolean = false
) : FrameLayout(context) {

    private val appContext: Context = context

    data class FolderEntry(
        val name: String,
        val isDirectory: Boolean,
        val sizeBytes: Long = 0L,
        val document: DocumentFile? = null
    )

    companion object {
        fun isSupportedBookFile(name: String?): Boolean {
            val lower = name?.lowercase()?.trim() ?: return false
            return lower.endsWith(".epub") || lower.endsWith(".txt")
        }

        fun filterAndSortEntries(entries: List<FolderEntry>, query: String): List<FolderEntry> {
            val cleanQuery = query.trim().lowercase()
            val visible = entries.filter { entry ->
                val allowedType = entry.isDirectory || isSupportedBookFile(entry.name)
                val matchesQuery = cleanQuery.isEmpty() || entry.name.lowercase().contains(cleanQuery)
                allowedType && matchesQuery
            }
            return visible.sortedWith { a, b ->
                when {
                    a.isDirectory != b.isDirectory -> if (a.isDirectory) -1 else 1
                    else -> EpubParser.naturalCompare(a.name, b.name)
                }
            }
        }
    }

    private val density: Float = (context.resources.displayMetrics.density).takeIf { it > 0f } ?: 2f
    private fun dp(v: Int): Int = (v * density).toInt()
    private fun dpF(v: Float): Float = v * density

    private val viewScope = CoroutineScope(SupervisorJob() + ReaderDispatchers.main)
    private val repository = LibraryRepository(context)

    private val breadcrumbView: TextView
    private val searchToggleBtn: ImageButton
    private val searchRowLayout: LinearLayout
    private val searchBox: EmbeddedInputEditText
    private val clearSearchBtn: ImageButton
    private val listScrollView: ScrollView
    private val itemsContainer: LinearLayout
    private val emptyStateContainer: LinearLayout
    private val emptyTitleView: TextView
    private val emptySubView: TextView
    private val upButton: Button

    private val folderStack = mutableListOf<DocumentFile>()
    private var currentEntries = listOf<FolderEntry>()
    private var currentQuery: String = ""
    private var isSearchExpanded: Boolean = false
    private var isImporting: Boolean = false

    private var topBarBottomPaddingPx: Int = 0
    private var entryCardVerticalPaddingPx: Int = 0
    private var listScrollBottomPaddingPx: Int = 0
    private var renderedEntriesForTesting: List<FolderEntry> = emptyList()

    private val folderListener: (String?) -> Unit = { newUri ->
        loadRootFolder(newUri)
    }

    init {
        setBackgroundColor(Color.parseColor(if (embeddedInLibrary) "#0B0B10" else "#181825"))

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            if (embeddedInLibrary) {
                setPadding(0, dp(1), 0, 0)
            } else {
                setPadding(dp(6), dp(4), dp(6), dp(38))
            }
        }

        // Top Bar
        topBarBottomPaddingPx = dp(1)
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), topBarBottomPaddingPx)
        }

        if (!embeddedInLibrary) {
            val backBtn = Button(context).apply {
                text = "← Library"
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#89B4FA"))
                minHeight = dp(34)
                minimumHeight = dp(34)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#252538"))
                    cornerRadius = dpF(8f)
                }
                setPadding(dp(8), dp(2), dp(8), dp(2))
                setOnClickListener {
                    clearSearchFocus()
                    onBackToLibrary()
                }
            }
            topBar.addView(backBtn)

            val titleView = TextView(context).apply {
                text = "Folders"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#CDD6F4"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                    marginEnd = dp(4)
                }
            }
            topBar.addView(titleView)
        } else {
            val spacer = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            }
            topBar.addView(spacer)
        }

        upButton = Button(context).apply {
            text = "↑ Up"
            textSize = 11f
            setTextColor(Color.parseColor("#CDD6F4"))
            visibility = View.GONE
            minHeight = dp(32)
            minimumHeight = dp(32)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1D1E2A"))
                setStroke(1, Color.parseColor("#2B2C3C"))
                cornerRadius = dpF(7f)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(4)
            }
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener { navigateUp() }
        }
        topBar.addView(upButton)

        val pickFolderBtn = Button(context).apply {
            text = "📁 Folder"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0B0B10"))
            minHeight = dp(32)
            minimumHeight = dp(32)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#89B4FA"))
                cornerRadius = dpF(7f)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(4)
            }
            setPadding(dp(9), dp(2), dp(9), dp(2))
            setOnClickListener { launchFolderTreePicker() }
        }
        topBar.addView(pickFolderBtn)

        searchToggleBtn = ImageButton(context).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(Color.parseColor("#9BA3B8"))
            contentDescription = "Toggle Explorer Search"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#14141D"))
                cornerRadius = dpF(7f)
                setStroke(1, Color.parseColor("#232432"))
            }
            setPadding(dp(7), dp(7), dp(7), dp(7))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            setOnClickListener { toggleSearchBar() }
        }
        topBar.addView(searchToggleBtn)

        // Breadcrumb bar
        breadcrumbView = TextView(context).apply {
            text = "No folder selected"
            textSize = 11f
            setTextColor(Color.parseColor("#9BA3B8"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
            setPadding(dp(2), dp(1), dp(2), if (embeddedInLibrary) dp(1) else dp(3))
        }
        if (embeddedInLibrary) {
            topBar.removeViewAt(0)
            breadcrumbView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            topBar.addView(breadcrumbView, 0)
            rootLayout.addView(topBar)
        } else {
            rootLayout.addView(topBar)
            rootLayout.addView(breadcrumbView)
        }

        // Expandable Search Row (hidden by default; revealed via compact top-bar search icon)
        searchRowLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(2)
            }
        }

        searchBox = EmbeddedInputEditText(context) { instanceId }.apply {
            hint = "Filter .epub / .txt..."
            setHintTextColor(Color.parseColor("#5B6078"))
            setTextColor(Color.parseColor("#F5F5F7"))
            textSize = 12f
            isSingleLine = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#161620"))
                cornerRadius = dpF(8f)
                setStroke(1, Color.parseColor("#2B2C3C"))
            }
            setPadding(dp(10), dp(5), dp(10), dp(5))
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
            setOnFocusChangeListener { _, hasFocus ->
                onRequestWindowFocusable?.invoke(hasFocus)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    currentQuery = s?.toString() ?: ""
                    updateSearchToggleTint()
                    renderEntries()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        searchRowLayout.addView(searchBox)

        clearSearchBtn = ImageButton(context).apply {
            setImageResource(R.drawable.ic_close_small)
            setColorFilter(Color.parseColor("#9BA3B8"))
            contentDescription = "Close folder search"
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
            }
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            setOnClickListener { collapseSearchBar() }
        }
        searchRowLayout.addView(clearSearchBtn)
        rootLayout.addView(searchRowLayout)

        // Scrollable list (bottom padding prevents Continue FAB and floating pill from obscuring final row)
        listScrollBottomPaddingPx = if (embeddedInLibrary) dp(86) else 0
        listScrollView = ScrollView(context).apply {
            clipToPadding = false
            if (listScrollBottomPaddingPx > 0) {
                setPadding(0, 0, 0, listScrollBottomPaddingPx)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        itemsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        listScrollView.addView(itemsContainer)
        rootLayout.addView(listScrollView)

        // Empty / Unlinked / Permission-revoked state
        emptyStateContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        emptyTitleView = TextView(context).apply {
            text = "No Books Folder Linked"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#CDD6F4"))
            gravity = Gravity.CENTER
        }
        emptyStateContainer.addView(emptyTitleView)

        emptySubView = TextView(context).apply {
            text = "Grant access to a folder containing EPUB or TXT books to browse and import inside this window."
            textSize = 12f
            setTextColor(Color.parseColor("#A6ADC8"))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(16))
        }
        emptyStateContainer.addView(emptySubView)

        val selectFolderCta = Button(context).apply {
            text = "📁 Select Folder"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            minHeight = dp(44)
            minimumHeight = dp(44)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#89B4FA"))
                cornerRadius = dpF(10f)
            }
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener { launchFolderTreePicker() }
        }
        emptyStateContainer.addView(selectFolderCta)

        val singleFileCta = Button(context).apply {
            text = "+ Single File Fallback"
            textSize = 12f
            setTextColor(Color.parseColor("#89B4FA"))
            minHeight = dp(44)
            minimumHeight = dp(44)
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1).coerceAtLeast(1), Color.parseColor("#45475A"))
                cornerRadius = dpF(10f)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { launchSingleFileFallback() }
        }
        emptyStateContainer.addView(singleFileCta)

        rootLayout.addView(emptyStateContainer)
        addView(rootLayout)

        ReaderEventManager.registerFolderListener(folderListener)
        loadRootFolder(ReaderFolderPrefs.getFolderUri(context))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ReaderEventManager.registerFolderListener(folderListener)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ReaderEventManager.unregisterFolderListener(folderListener)
    }

    fun refreshCurrentFolder() {
        val top = folderStack.lastOrNull()
        if (top != null) {
            loadDirectory(top)
        } else {
            loadRootFolder(ReaderFolderPrefs.getFolderUri(appContext))
        }
    }

    fun loadRootFolder(uriStr: String? = ReaderFolderPrefs.getFolderUri(appContext)) {
        folderStack.clear()
        if (uriStr.isNullOrBlank()) {
            showNoFolderState(
                title = "No Books Folder Linked",
                message = "Choose a folder to browse EPUB and TXT files directly inside the floating Reader."
            )
            return
        }

        val uri = try { Uri.parse(uriStr) } catch (_: Throwable) { null }
        val rootDoc = uri?.let {
            try { DocumentFile.fromTreeUri(appContext, it) } catch (_: Throwable) { null }
        }

        if (rootDoc == null || !rootDoc.exists() || !rootDoc.canRead()) {
            showNoFolderState(
                title = "Folder Permission Unavailable",
                message = "The previously selected folder could not be accessed or its permission expired. Please select it again."
            )
            return
        }

        folderStack.add(rootDoc)
        loadDirectory(rootDoc)
    }

    private fun navigateUp() {
        if (folderStack.size > 1) {
            folderStack.removeAt(folderStack.size - 1)
            loadDirectory(folderStack.last())
        }
    }

    private fun toggleSearchBar() {
        if (isSearchExpanded) {
            collapseSearchBar()
        } else {
            isSearchExpanded = true
            searchRowLayout.visibility = View.VISIBLE
            updateSearchToggleTint()
            onRequestWindowFocusable?.invoke(true)
            searchBox.post {
                searchBox.requestFocus()
                try {
                    val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT)
                } catch (_: Throwable) {}
            }
        }
    }

    private fun collapseSearchBar() {
        isSearchExpanded = false
        currentQuery = ""
        searchBox.setText("")
        searchRowLayout.visibility = View.GONE
        updateSearchToggleTint()
        clearSearchFocus()
        renderEntries()
    }

    internal fun clearSearchFocus() {
        searchBox.clearFocus()
        EmbeddedImeBridge.clearFocusRecursive(searchRowLayout)
        onRequestWindowFocusable?.invoke(false)
    }

    private fun updateSearchToggleTint() {
        searchToggleBtn.setColorFilter(
            Color.parseColor(if (isSearchExpanded || currentQuery.isNotEmpty()) "#89B4FA" else "#9BA3B8")
        )
    }

    private fun loadDirectory(dir: DocumentFile) {
        upButton.visibility = if (folderStack.size > 1) View.VISIBLE else View.GONE
        breadcrumbView.text = folderStack.joinToString(" / ") { it.name ?: "Folder" }
        searchToggleBtn.visibility = View.VISIBLE
        searchRowLayout.visibility = if (isSearchExpanded) View.VISIBLE else View.GONE
        emptyStateContainer.visibility = View.GONE
        listScrollView.visibility = View.VISIBLE

        itemsContainer.removeAllViews()
        itemsContainer.addView(TextView(appContext).apply {
            text = "Scanning folder..."
            textSize = 12f
            setTextColor(Color.parseColor("#A6ADC8"))
            setPadding(dp(8), dp(16), dp(8), dp(16))
        })

        viewScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    if (!dir.exists() || !dir.canRead()) {
                        return@withContext null
                    }
                    dir.listFiles().mapNotNull { doc ->
                        val docName = doc.name ?: return@mapNotNull null
                        val isDir = doc.isDirectory
                        if (!isDir && !isSupportedBookFile(docName)) return@mapNotNull null
                        FolderEntry(
                            name = docName,
                            isDirectory = isDir,
                            sizeBytes = doc.length(),
                            document = doc
                        )
                    }
                } catch (t: Throwable) {
                    LogKeeper.log(appContext, "ReaderFolderBrowser", "listFiles error: ${t.message}")
                    null
                }
            }

            if (loaded == null) {
                showNoFolderState(
                    title = "Cannot Read Folder",
                    message = "Permission to this folder may have been revoked or the folder was moved."
                )
                return@launch
            }

            currentEntries = loaded
            renderEntries()
        }
    }

    private fun renderEntries() {
        itemsContainer.removeAllViews()
        val sorted = filterAndSortEntries(currentEntries, currentQuery)
        renderedEntriesForTesting = sorted

        if (folderStack.size > 1) {
            val parentRow = createEntryRow(
                icon = "📁",
                title = ".. (Parent Folder)",
                subtitle = "Go up one level",
                accent = false
            ) {
                navigateUp()
            }
            itemsContainer.addView(parentRow)
        }

        if (sorted.isEmpty()) {
            val msg = if (currentQuery.isBlank()) {
                "No subfolders or .epub / .txt books in this folder."
            } else {
                "No matches for '$currentQuery'."
            }
            itemsContainer.addView(TextView(appContext).apply {
                text = msg
                textSize = 12f
                setTextColor(Color.parseColor("#A6ADC8"))
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(24), dp(12), dp(12))
            })
            return
        }

        for (entry in sorted) {
            val doc = entry.document ?: continue
            if (entry.isDirectory) {
                itemsContainer.addView(
                    createEntryRow(
                        icon = "📁",
                        title = entry.name,
                        subtitle = "Folder",
                        accent = false
                    ) {
                        folderStack.add(doc)
                        collapseSearchBar()
                        loadDirectory(doc)
                    }
                )
            } else {
                val kb = (entry.sizeBytes / 1024L).coerceAtLeast(1L)
                val ext = entry.name.substringAfterLast('.', "").uppercase()
                itemsContainer.addView(
                    createEntryRow(
                        icon = "📖",
                        title = entry.name,
                        subtitle = "$ext • ${kb} KB",
                        accent = true
                    ) {
                        importDocumentFile(entry.name, doc)
                    }
                )
            }
        }
    }

    private fun createEntryRow(
        icon: String,
        title: String,
        subtitle: String,
        accent: Boolean,
        onClick: () -> Unit
    ): LinearLayout {
        entryCardVerticalPaddingPx = dp(3)
        return LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(38)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#14141D"))
                cornerRadius = dpF(8f)
                setStroke(1, Color.parseColor("#232432"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(1), dp(1), dp(1), dp(1))
            }
            setPadding(dp(7), entryCardVerticalPaddingPx, dp(7), entryCardVerticalPaddingPx)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            addView(TextView(appContext).apply {
                text = icon
                textSize = 14f
                setPadding(0, 0, dp(7), 0)
            })

            val col = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(appContext).apply {
                text = title
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(if (accent) "#F5F5F7" else "#89B4FA"))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            col.addView(TextView(appContext).apply {
                text = subtitle
                textSize = 10.5f
                setTextColor(Color.parseColor("#9BA3B8"))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(col)
        }
    }

    private fun importDocumentFile(name: String, doc: DocumentFile) {
        if (isImporting) {
            try { Toast.makeText(appContext, "Import already in progress...", Toast.LENGTH_SHORT)?.show() } catch (_: Throwable) {}
            return
        }
        isImporting = true
        try { Toast.makeText(appContext, "Importing $name...", Toast.LENGTH_SHORT)?.show() } catch (_: Throwable) {}

        viewScope.launch {
            try {
                val imported = repository.importBook(doc.uri, markOpenedOnImport = true)
                if (imported != null) {
                    try { Toast.makeText(appContext, "Imported: ${imported.title}", Toast.LENGTH_SHORT)?.show() } catch (_: Throwable) {}
                    ReaderEventManager.notifyLibraryChanged(appContext)
                    EmbeddedImeBridge.clearFocusRecursive(this@ReaderFolderBrowserView)
                    onRequestWindowFocusable?.invoke(false)
                    onBookImported(imported)
                } else {
                    try { Toast.makeText(appContext, "Failed to import '$name'. File may be corrupted or empty.", Toast.LENGTH_SHORT)?.show() } catch (_: Throwable) {}
                }
            } finally {
                isImporting = false
            }
        }
    }

    private fun showNoFolderState(title: String, message: String) {
        upButton.visibility = View.GONE
        breadcrumbView.text = "No folder selected"
        isSearchExpanded = false
        currentQuery = ""
        renderedEntriesForTesting = emptyList()
        searchBox.setText("")
        searchToggleBtn.visibility = View.GONE
        searchRowLayout.visibility = View.GONE
        updateSearchToggleTint()
        clearSearchFocus()
        listScrollView.visibility = View.GONE
        emptyStateContainer.visibility = View.VISIBLE
        emptyTitleView.text = title
        emptySubView.text = message
    }

    private fun launchFolderTreePicker() {
        try {
            val intent = Intent(appContext, ReaderFolderTreeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
        } catch (e: Exception) {
            LogKeeper.log(appContext, "ReaderFolderBrowser", "Failed to start ReaderFolderTreeActivity: ${e.message}")
            Toast.makeText(appContext, "Cannot open folder picker.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchSingleFileFallback() {
        try {
            val intent = Intent(appContext, ReaderImportActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
        } catch (e: Exception) {
            LogKeeper.log(appContext, "ReaderFolderBrowser", "Failed to start ReaderImportActivity: ${e.message}")
            Toast.makeText(appContext, "Cannot open file picker.", Toast.LENGTH_SHORT).show()
        }
    }

    fun releaseResources() {
        ReaderEventManager.unregisterFolderListener(folderListener)
        viewScope.cancel()
    }

    // Testing helpers
    fun getTopBarBottomPaddingPxForTesting(): Int = topBarBottomPaddingPx
    fun getSearchBottomMarginPxForTesting(): Int =
        (searchRowLayout.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin ?: 0
    fun isEmbeddedInLibraryForTesting(): Boolean = embeddedInLibrary
    fun isSearchExpandedForTesting(): Boolean = isSearchExpanded
    fun isSearchRowVisibleForTesting(): Boolean = isSearchExpanded
    fun getSearchToggleButtonForTesting(): ImageButton = searchToggleBtn
    fun getSearchCloseButtonForTesting(): ImageButton = clearSearchBtn
    fun getSearchBoxForTesting(): EmbeddedInputEditText = searchBox
    fun getCurrentQueryForTesting(): String = currentQuery
    fun toggleSearchForTesting() = toggleSearchBar()
    fun closeSearchForTesting() = collapseSearchBar()
    fun setSearchQueryForTesting(query: String) {
        isSearchExpanded = true
        searchRowLayout.visibility = View.VISIBLE
        currentQuery = query
        searchBox.setText(query)
        updateSearchToggleTint()
        renderEntries()
    }
    fun getListScrollViewBottomPaddingPxForTesting(): Int = listScrollBottomPaddingPx
    fun getEntryCardVerticalPaddingPxForTesting(): Int = entryCardVerticalPaddingPx
    fun setEntriesForTesting(entries: List<FolderEntry>) {
        searchToggleBtn.visibility = View.VISIBLE
        searchRowLayout.visibility = if (isSearchExpanded) View.VISIBLE else View.GONE
        emptyStateContainer.visibility = View.GONE
        listScrollView.visibility = View.VISIBLE
        currentEntries = entries
        renderEntries()
    }
    fun getRenderedEntryCountForTesting(): Int = renderedEntriesForTesting.size
}
