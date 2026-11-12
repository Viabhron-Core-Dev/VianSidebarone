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
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
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
 * In-app SAF folder explorer for Reader in :heavy.
 * Navigates folders granted via ACTION_OPEN_DOCUMENT_TREE, filters .epub/.txt books,
 * searches filenames, and imports tapped files cleanly into Room via LibraryRepository.
 */
class ReaderFolderBrowserView(
    context: Context,
    private val instanceId: String? = null,
    private val onBackToLibrary: () -> Unit,
    private val onBookImported: (EpubBook) -> Unit
) : FrameLayout(context) {

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
    private val searchBox: EmbeddedInputEditText
    private val listScrollView: ScrollView
    private val itemsContainer: LinearLayout
    private val emptyStateContainer: LinearLayout
    private val emptyTitleView: TextView
    private val emptySubView: TextView
    private val upButton: Button

    private val folderStack = mutableListOf<DocumentFile>()
    private var currentEntries = listOf<FolderEntry>()
    private var currentQuery: String = ""
    private var isImporting: Boolean = false

    private val folderListener: (String?) -> Unit = { newUri ->
        loadRootFolder(newUri)
    }

    init {
        setBackgroundColor(Color.parseColor("#181825"))

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }

        // Top Bar
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(6))
        }

        val backBtn = Button(context).apply {
            text = "← Library"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#89B4FA"))
            minHeight = dp(40)
            minimumHeight = dp(40)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#252538"))
                cornerRadius = dpF(8f)
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                searchBox.clearFocus()
                onBackToLibrary()
            }
        }
        topBar.addView(backBtn)

        val titleView = TextView(context).apply {
            text = "Folders"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#CDD6F4"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(4)
            }
        }
        topBar.addView(titleView)

        upButton = Button(context).apply {
            text = "↑ Up"
            textSize = 11f
            setTextColor(Color.parseColor("#CDD6F4"))
            visibility = View.GONE
            minHeight = dp(40)
            minimumHeight = dp(40)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#313244"))
                cornerRadius = dpF(8f)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(6)
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { navigateUp() }
        }
        topBar.addView(upButton)

        val pickFolderBtn = Button(context).apply {
            text = "📁 Folder"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            minHeight = dp(40)
            minimumHeight = dp(40)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#89B4FA"))
                cornerRadius = dpF(8f)
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { launchFolderTreePicker() }
        }
        topBar.addView(pickFolderBtn)
        rootLayout.addView(topBar)

        // Breadcrumb bar
        breadcrumbView = TextView(context).apply {
            text = "No folder selected"
            textSize = 11f
            setTextColor(Color.parseColor("#A6ADC8"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
            setPadding(dp(4), dp(2), dp(4), dp(6))
        }
        rootLayout.addView(breadcrumbView)

        // Search input (uses generic EmbeddedImeBridge)
        searchBox = EmbeddedInputEditText(context) { instanceId }.apply {
            hint = "Filter .epub / .txt in folder..."
            setHintTextColor(Color.parseColor("#6C7086"))
            setTextColor(Color.WHITE)
            textSize = 13f
            isSingleLine = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#313244"))
                cornerRadius = dpF(8f)
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    currentQuery = s?.toString() ?: ""
                    renderEntries()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        rootLayout.addView(searchBox)

        // Scrollable list
        listScrollView = ScrollView(context).apply {
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
            loadRootFolder(ReaderFolderPrefs.getFolderUri(context))
        }
    }

    fun loadRootFolder(uriStr: String? = ReaderFolderPrefs.getFolderUri(context)) {
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
            try { DocumentFile.fromTreeUri(context, it) } catch (_: Throwable) { null }
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

    private fun loadDirectory(dir: DocumentFile) {
        upButton.visibility = if (folderStack.size > 1) View.VISIBLE else View.GONE
        breadcrumbView.text = folderStack.joinToString(" / ") { it.name ?: "Folder" }
        searchBox.visibility = View.VISIBLE
        emptyStateContainer.visibility = View.GONE
        listScrollView.visibility = View.VISIBLE

        itemsContainer.removeAllViews()
        itemsContainer.addView(TextView(context).apply {
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
                    LogKeeper.log(context, "ReaderFolderBrowser", "listFiles error: ${t.message}")
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
            itemsContainer.addView(TextView(context).apply {
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
                        currentQuery = ""
                        searchBox.setText("")
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
                        subtitle = "$ext • ${kb} KB • Tap to import & open",
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
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E2E"))
                cornerRadius = dpF(10f)
                setStroke(1, Color.parseColor("#313244"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(2), dp(3), dp(2), dp(3))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            addView(TextView(context).apply {
                text = icon
                textSize = 18f
                setPadding(0, 0, dp(10), 0)
            })

            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(context).apply {
                text = title
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(if (accent) "#CDD6F4" else "#89B4FA"))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            col.addView(TextView(context).apply {
                text = subtitle
                textSize = 11f
                setTextColor(Color.parseColor("#A6ADC8"))
            })
            addView(col)
        }
    }

    private fun importDocumentFile(name: String, doc: DocumentFile) {
        if (isImporting) {
            Toast.makeText(context, "Import already in progress...", Toast.LENGTH_SHORT).show()
            return
        }
        isImporting = true
        Toast.makeText(context, "Importing $name...", Toast.LENGTH_SHORT).show()

        viewScope.launch {
            try {
                val imported = repository.importBook(doc.uri)
                if (imported != null) {
                    Toast.makeText(context, "Imported: ${imported.title}", Toast.LENGTH_SHORT).show()
                    ReaderEventManager.notifyLibraryChanged()
                    EmbeddedImeBridge.clearFocusRecursive(this@ReaderFolderBrowserView)
                    onBookImported(imported)
                } else {
                    Toast.makeText(context, "Failed to import '$name'. File may be corrupted or empty.", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isImporting = false
            }
        }
    }

    private fun showNoFolderState(title: String, message: String) {
        upButton.visibility = View.GONE
        breadcrumbView.text = "No folder selected"
        searchBox.visibility = View.GONE
        listScrollView.visibility = View.GONE
        emptyStateContainer.visibility = View.VISIBLE
        emptyTitleView.text = title
        emptySubView.text = message
    }

    private fun launchFolderTreePicker() {
        try {
            val intent = Intent(context, ReaderFolderTreeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            LogKeeper.log(context, "ReaderFolderBrowser", "Failed to start ReaderFolderTreeActivity: ${e.message}")
            Toast.makeText(context, "Cannot open folder picker.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchSingleFileFallback() {
        try {
            val intent = Intent(context, ReaderImportActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            LogKeeper.log(context, "ReaderFolderBrowser", "Failed to start ReaderImportActivity: ${e.message}")
            Toast.makeText(context, "Cannot open file picker.", Toast.LENGTH_SHORT).show()
        }
    }

    fun releaseResources() {
        ReaderEventManager.unregisterFolderListener(folderListener)
        viewScope.cancel()
    }
}
