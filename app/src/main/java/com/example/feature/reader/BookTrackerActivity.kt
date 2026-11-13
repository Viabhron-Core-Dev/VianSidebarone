package com.example.feature.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.LogKeeper
import com.example.core.ipc.HeavyProcessHost
import com.example.core.ipc.MainActionType
import com.example.core.ipc.MainCommandRequest
import com.example.data.AppDatabase
import com.example.data.EpubBook
import com.example.data.LibraryRepository
import com.example.data.TrackerBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

enum class TrackerSortOption(val label: String) {
    DATE("Date"),
    MOST_READ("Most Read"),
    NAME("Name")
}

/**
 * BookTrackerActivity: Heavy-process (`:heavy`) Book Tracker, Metadata Editor, and Backup Manager.
 *
 * Features:
 * - Search by title and author.
 * - Genre filter chips.
 * - Sort by Date, Most Read, and Name.
 * - Add, Edit, and Delete Tracker records.
 * - Manual correction of `readChapters` (high-water progress), `totalChapters`, `isFinished`, `isWebNovel`, `genres`, `rating`, `comment`.
 * - Displays whether a `TrackerBook` is linked to an imported `EpubBook` (`linkedEpubBookId`),
 *   shows both High-Water Progress and separate Reader Resume chapter, and provides **"Open in Reader"**
 *   dispatching by exact `linkedEpubBookId` to Main.
 * - Supports launching directly into the Tracker Editor for a specific `EpubBook.id` when invoked
 *   from the floating Reader's top-bar `Edit` button.
 * - Hosts LiteReader Backup Import/Export (Full ZIP, NoBooks ZIP, Tracker TSV, and Moon+ `.mrstd`).
 */
class BookTrackerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_EDIT_EPUB_BOOK_ID = "extra_edit_epub_book_id"
        const val EXTRA_OPEN_BACKUP_PANEL = "extra_open_backup_panel"

        fun launch(context: Context, openBackupPanel: Boolean = false) {
            try {
                val intent = Intent(context, BookTrackerActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(EXTRA_OPEN_BACKUP_PANEL, openBackupPanel)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                LogKeeper.log(context, "BookTrackerActivity", "Failed to launch BookTrackerActivity: ${e.message}")
            }
        }

        fun launchForEpubBook(context: Context, epubBookId: Int) {
            try {
                val intent = Intent(context, BookTrackerActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(EXTRA_EDIT_EPUB_BOOK_ID, epubBookId)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                LogKeeper.log(context, "BookTrackerActivity", "Failed to launch BookTrackerActivity for epubId=$epubBookId: ${e.message}")
            }
        }

        fun filterAndSortTrackerBooks(
            books: List<TrackerBook>,
            searchQuery: String,
            selectedGenre: String,
            sortOption: TrackerSortOption
        ): List<TrackerBook> {
            val q = searchQuery.trim().lowercase()
            val g = selectedGenre.trim().lowercase()
            val filtered = books.filter { b ->
                val matchesQuery = q.isEmpty() ||
                    b.title.lowercase().contains(q) ||
                    b.author.lowercase().contains(q)
                val matchesGenre = g.isEmpty() || g == "all" ||
                    b.genres.split(',').any { it.trim().lowercase() == g }
                matchesQuery && matchesGenre
            }

            return when (sortOption) {
                TrackerSortOption.DATE -> filtered.sortedWith(
                    compareByDescending<TrackerBook> { maxOf(it.lastReadingActivityTimestamp, it.addedTimestamp) }
                        .thenByDescending { it.id }
                )
                TrackerSortOption.MOST_READ -> filtered.sortedWith(
                    compareByDescending<TrackerBook> { it.readChapters }
                        .thenByDescending { maxOf(it.lastReadingActivityTimestamp, it.addedTimestamp) }
                )
                TrackerSortOption.NAME -> filtered.sortedWith(
                    compareBy<TrackerBook, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
                        .thenBy { it.id }
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialEditEpubId = intent?.getIntExtra(EXTRA_EDIT_EPUB_BOOK_ID, -1) ?: -1
        val initialOpenBackup = intent?.getBooleanExtra(EXTRA_OPEN_BACKUP_PANEL, false) ?: false

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF89B4FA),
                    secondary = Color(0xFFCBA6F7),
                    background = Color(0xFF181825),
                    surface = Color(0xFF1E1E2E),
                    onPrimary = Color(0xFF11111B),
                    onBackground = Color(0xFFCDD6F4),
                    onSurface = Color(0xFFCDD6F4)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BookTrackerScreen(
                        initialEditEpubBookId = initialEditEpubId,
                        initialShowBackupDialog = initialOpenBackup,
                        onClose = { finish() },
                        onOpenBookInReader = { epubBookId ->
                            dispatchOpenBookInMainReader(this, epubBookId)
                            finish()
                        }
                    )
                }
            }
        }
    }

    private fun dispatchOpenBookInMainReader(context: Context, epubBookId: Int) {
        if (epubBookId <= 0) return
        try {
            // 1. Broadcast across processes to Main's FloatingMiniAppBridge
            ReaderEventManager.requestOpenBookInReader(context, epubBookId)
            // 2. Also send via HeavyProcessHost IPC if bound
            HeavyProcessHost.getInstance(context).requestMainAction(
                MainCommandRequest(
                    requestId = UUID.randomUUID().toString(),
                    action = MainActionType.CUSTOM,
                    targetId = "READER_ACTION",
                    parameters = mapOf(
                        "subAction" to "OPEN_BOOK",
                        "epubBookId" to epubBookId.toString()
                    )
                )
            )
        } catch (t: Throwable) {
            LogKeeper.log(context, "BookTrackerActivity", "dispatchOpenBookInMainReader warning: ${t.message}")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookTrackerScreen(
    initialEditEpubBookId: Int = -1,
    initialShowBackupDialog: Boolean = false,
    onClose: () -> Unit,
    onOpenBookInReader: (Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { LibraryRepository(context) }
    val db = remember { AppDatabase.getDatabase(context) }

    var trackerBooks by remember { mutableStateOf<List<TrackerBook>>(emptyList()) }
    var epubById by remember { mutableStateOf<Map<Int, EpubBook>>(emptyMap()) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedGenre by remember { mutableStateOf("All") }
    var sortOption by remember { mutableStateOf(TrackerSortOption.DATE) }

    var editingBook by remember { mutableStateOf<TrackerBook?>(null) }
    var isAddingNewBook by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(initialShowBackupDialog) }
    var statusBannerText by remember { mutableStateOf<String?>(null) }

    fun reloadData() {
        scope.launch {
            val loadedTrackers = withContext(Dispatchers.IO) {
                db.trackerDao().getAllBooks()
            }
            val loadedEpubs = withContext(Dispatchers.IO) {
                db.epubDao().getAllBooks().associateBy { it.id }
            }
            trackerBooks = loadedTrackers
            epubById = loadedEpubs
        }
    }

    LaunchedEffect(initialEditEpubBookId) {
        reloadData()
        if (initialEditEpubBookId > 0) {
            val linked = repo.getOrCreateLinkedTrackerBook(initialEditEpubBookId)
            if (linked != null) {
                reloadData()
                editingBook = linked
            }
        }
    }

    // SAF Launchers for Backup Import & Export
    var pendingExportMode by remember { mutableStateOf("FULL_ZIP") }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val tempOut = File(context.cacheDir, "export_tmp_${UUID.randomUUID()}")
                try {
                    val ok = withContext(Dispatchers.IO) {
                        val exported = when (pendingExportMode) {
                            "FULL_ZIP" -> LiteReaderBackupManager.exportFullZipBackup(context, tempOut)
                            "NOBOOKS_ZIP" -> LiteReaderBackupManager.exportNoBooksZipBackup(context, tempOut)
                            "TSV" -> LiteReaderBackupManager.exportTrackerTsv(context, tempOut)
                            else -> false
                        }
                        if (exported && tempOut.exists()) {
                            context.contentResolver.openOutputStream(uri)?.use { os ->
                                FileInputStream(tempOut).use { fis -> fis.copyTo(os) }
                            }
                            true
                        } else false
                    }
                    statusBannerText = if (ok) "Exported $pendingExportMode backup successfully." else "Export failed."
                } catch (t: Throwable) {
                    statusBannerText = "Export error: ${t.message}"
                } finally {
                    tempOut.delete()
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val tempIn = File(context.cacheDir, "import_tmp_${UUID.randomUUID()}")
                try {
                    val report = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { ins ->
                            FileOutputStream(tempIn).use { fos -> ins.copyTo(fos) }
                        }
                        val pathHint = uri.lastPathSegment?.lowercase() ?: ""
                        when {
                            pathHint.endsWith(".tsv") -> LiteReaderBackupManager.importTrackerTsv(context, tempIn)
                            pathHint.endsWith(".mrstd") -> LiteReaderBackupManager.importMoonPlusMrstd(context, tempIn)
                            else -> {
                                val zipRes = LiteReaderBackupManager.importZipBackup(context, tempIn)
                                if (zipRes.success) zipRes else {
                                    val tsvRes = LiteReaderBackupManager.importTrackerTsv(context, tempIn)
                                    if (tsvRes.success) tsvRes else LiteReaderBackupManager.importMoonPlusMrstd(context, tempIn)
                                }
                            }
                        }
                    }
                    if (report.success) {
                        statusBannerText = "Imported (${report.backupType}): +${report.importedEpubs} books, +${report.importedTrackers} tracker records (${report.updatedTrackers} updated)."
                        reloadData()
                    } else {
                        statusBannerText = "Import failed: ${report.errorMessage}"
                    }
                } catch (t: Throwable) {
                    statusBannerText = "Import error: ${t.message}"
                } finally {
                    tempIn.delete()
                }
            }
        }
    }

    val allGenres = remember(trackerBooks) {
        val set = linkedSetOf("All")
        trackerBooks.forEach { b ->
            b.genres.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { set.add(it) }
        }
        set.toList()
    }

    val displayedBooks = remember(trackerBooks, searchQuery, selectedGenre, sortOption) {
        BookTrackerActivity.filterAndSortTrackerBooks(
            books = trackerBooks,
            searchQuery = searchQuery,
            selectedGenre = selectedGenre,
            sortOption = sortOption
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Book Tracker", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { showBackupDialog = true }) {
                        Text("Backups", color = Color(0xFF89B4FA), fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E1E2E),
                    titleContentColor = Color(0xFFCDD6F4)
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingBook = TrackerBook(
                        id = 0,
                        linkedEpubBookId = null,
                        title = "",
                        author = "",
                        readChapters = 0,
                        totalChapters = 1,
                        genres = "",
                        rating = 0f,
                        comment = ""
                    )
                    isAddingNewBook = true
                },
                containerColor = Color(0xFF89B4FA),
                contentColor = Color(0xFF11111B)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Tracker Book")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            statusBannerText?.let { msg ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF313244))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = msg,
                            color = Color(0xFFA6E3A1),
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { statusBannerText = null }) {
                            Text("Dismiss", fontSize = 11.sp)
                        }
                    }
                }
            }

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search by title or author...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Sort chips row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Sort:", fontSize = 12.sp, color = Color(0xFFA6ADC8))
                TrackerSortOption.values().forEach { opt ->
                    FilterChip(
                        selected = sortOption == opt,
                        onClick = { sortOption = opt },
                        label = { Text(opt.label, fontSize = 12.sp) }
                    )
                }
            }

            // Genre filter chips row
            if (allGenres.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Genre:", fontSize = 12.sp, color = Color(0xFFA6ADC8))
                    allGenres.forEach { genre ->
                        FilterChip(
                            selected = selectedGenre.equals(genre, ignoreCase = true),
                            onClick = { selectedGenre = genre },
                            label = { Text(genre, fontSize = 11.sp) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (displayedBooks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (trackerBooks.isEmpty()) {
                            "No tracked books yet. Import an EPUB/TXT in Reader, tap + to add a book, or restore a LiteReader backup."
                        } else {
                            "No tracked books match your search/filter."
                        },
                        color = Color(0xFFA6ADC8),
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(displayedBooks, key = { it.id }) { book ->
                        val linkedEpub = book.linkedEpubBookId?.let { epubById[it] }
                        TrackerBookCard(
                            book = book,
                            linkedEpub = linkedEpub,
                            onEdit = {
                                isAddingNewBook = false
                                editingBook = book
                            },
                            onDelete = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        db.trackerDao().deleteBook(book)
                                    }
                                    ReaderEventManager.notifyLibraryChanged(context)
                                    reloadData()
                                }
                            },
                            onOpenInReader = { epubId ->
                                onOpenBookInReader(epubId)
                            }
                        )
                    }
                }
            }
        }
    }

    // Edit / Add Modal Dialog
    editingBook?.let { current ->
        val linkedEpub = current.linkedEpubBookId?.let { epubById[it] }
        TrackerBookEditDialog(
            initialBook = current,
            linkedEpub = linkedEpub,
            isNew = isAddingNewBook,
            onDismiss = {
                editingBook = null
                isAddingNewBook = false
            },
            onSave = { updated ->
                scope.launch {
                    if (isAddingNewBook || updated.id == 0) {
                        val now = System.currentTimeMillis()
                        withContext(Dispatchers.IO) {
                            db.trackerDao().insertBook(
                                updated.copy(
                                    addedTimestamp = now,
                                    lastUpdatedTimestamp = now,
                                    lastMetadataEditTimestamp = now
                                )
                            )
                        }
                    } else {
                        repo.saveTrackerMetadataEdit(updated)
                    }
                    ReaderEventManager.notifyLibraryChanged(context)
                    editingBook = null
                    isAddingNewBook = false
                    reloadData()
                }
            },
            onOpenInReader = { epubId ->
                editingBook = null
                onOpenBookInReader(epubId)
            }
        )
    }

    if (showBackupDialog) {
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text("LiteReader Backups & Migration", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Safely import or export reading progress and books without overwriting active database tables.",
                        fontSize = 12.sp,
                        color = Color(0xFFA6ADC8)
                    )
                    Button(
                        onClick = {
                            showBackupDialog = false
                            importLauncher.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📥 Import Backup (.zip / .tsv / .mrstd)")
                    }
                    OutlinedButton(
                        onClick = {
                            showBackupDialog = false
                            pendingExportMode = "FULL_ZIP"
                            exportLauncher.launch("LiteReader_Full_Backup.zip")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📤 Export Full ZIP (DB + Books)")
                    }
                    OutlinedButton(
                        onClick = {
                            showBackupDialog = false
                            pendingExportMode = "NOBOOKS_ZIP"
                            exportLauncher.launch("LiteReader_NoBooks_Backup.zip")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📤 Export NoBooks ZIP (DB Only)")
                    }
                    OutlinedButton(
                        onClick = {
                            showBackupDialog = false
                            pendingExportMode = "TSV"
                            exportLauncher.launch("LiteReader_Tracker.tsv")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📤 Export Tracker TSV (.tsv)")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBackupDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun TrackerBookCard(
    book: TrackerBook,
    linkedEpub: EpubBook?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenInReader: (Int) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = book.title.ifBlank { "Untitled" },
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color(0xFFCDD6F4),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (book.author.isNotBlank()) {
                        Text(
                            text = book.author,
                            fontSize = 12.sp,
                            color = Color(0xFFA6ADC8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = Color(0xFF89B4FA))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFF38BA8))
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // High-Water Progress vs Reader Resume position
            val totalStr = if (book.totalChapters > 0) "${book.totalChapters}" else "?"
            val statusBadges = buildList {
                add("High-Water Read: ${book.readChapters} / $totalStr ch")
                if (linkedEpub != null) {
                    val resumeOneBased = (linkedEpub.lastReadChapter + 1).coerceAtLeast(1)
                    add("Reader Resume: Ch. $resumeOneBased")
                } else {
                    add("Tracker-Only (Unlinked)")
                }
                if (book.isFinished) add("✓ Finished")
                if (book.isWebNovel) add("Web Novel")
            }.joinToString(" • ")

            Text(
                text = statusBadges,
                fontSize = 12.sp,
                color = Color(0xFFA6E3A1)
            )

            if (book.genres.isNotBlank() || book.rating > 0f) {
                Spacer(modifier = Modifier.height(4.dp))
                val metaText = buildList {
                    if (book.genres.isNotBlank()) add("Genres: ${book.genres}")
                    if (book.rating > 0f) add("★ ${book.rating}")
                }.joinToString(" • ")
                Text(text = metaText, fontSize = 11.sp, color = Color(0xFFF9E2AF))
            }

            if (book.comment.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = book.comment,
                    fontSize = 11.sp,
                    color = Color(0xFFA6ADC8),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (linkedEpub != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { onOpenInReader(linkedEpub.id) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF89B4FA),
                            contentColor = Color(0xFF11111B)
                        )
                    ) {
                        Text("📖 Open in Reader", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackerBookEditDialog(
    initialBook: TrackerBook,
    linkedEpub: EpubBook?,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (TrackerBook) -> Unit,
    onOpenInReader: (Int) -> Unit
) {
    var title by remember(initialBook) { mutableStateOf(initialBook.title) }
    var author by remember(initialBook) { mutableStateOf(initialBook.author) }
    var readChaptersText by remember(initialBook) { mutableStateOf(initialBook.readChapters.toString()) }
    var totalChaptersText by remember(initialBook) { mutableStateOf(initialBook.totalChapters.toString()) }
    var genres by remember(initialBook) { mutableStateOf(initialBook.genres) }
    var ratingText by remember(initialBook) { mutableStateOf(initialBook.rating.toString()) }
    var comment by remember(initialBook) { mutableStateOf(initialBook.comment) }
    var isFinished by remember(initialBook) { mutableStateOf(initialBook.isFinished) }
    var isWebNovel by remember(initialBook) { mutableStateOf(initialBook.isWebNovel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isNew) "Add Tracker Book" else "Edit Tracker Book",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (linkedEpub != null) {
                    val resumeCh = (linkedEpub.lastReadChapter + 1).coerceAtLeast(1)
                    Text(
                        text = "Linked Reader Book (#${linkedEpub.id}) • Reader Resume: Ch. $resumeCh / ${linkedEpub.totalChapters}",
                        fontSize = 12.sp,
                        color = Color(0xFF89B4FA)
                    )
                    OutlinedButton(
                        onClick = { onOpenInReader(linkedEpub.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📖 Open in Floating Reader")
                    }
                } else {
                    Text(
                        text = "Unlinked Tracker Record (no local EPUB/TXT binary attached)",
                        fontSize = 11.sp,
                        color = Color(0xFFA6ADC8)
                    )
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Author") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = readChaptersText,
                        onValueChange = { readChaptersText = it },
                        label = { Text("Read Chapters (High-Water)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = totalChaptersText,
                        onValueChange = { totalChaptersText = it },
                        label = { Text("Total Chapters") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = genres,
                    onValueChange = { genres = it },
                    label = { Text("Genres (comma-separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ratingText,
                    onValueChange = { ratingText = it },
                    label = { Text("Rating (0.0 - 5.0)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Notes / Comment") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isFinished, onCheckedChange = { isFinished = it })
                    Text("Finished", fontSize = 13.sp)
                    Spacer(modifier = Modifier.width(16.dp))
                    Checkbox(checked = isWebNovel, onCheckedChange = { isWebNovel = it })
                    Text("Web Novel", fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanTitle = title.trim().ifEmpty { "Untitled" }
                    val totalCh = totalChaptersText.trim().toIntOrNull()?.coerceAtLeast(0) ?: initialBook.totalChapters
                    val rawReadCh = readChaptersText.trim().toIntOrNull()?.coerceAtLeast(0) ?: initialBook.readChapters
                    val cappedReadCh = if (totalCh > 0) rawReadCh.coerceAtMost(totalCh) else rawReadCh
                    val rating = ratingText.trim().toFloatOrNull()?.coerceIn(0f, 5f) ?: initialBook.rating
                    onSave(
                        initialBook.copy(
                            title = cleanTitle,
                            author = author.trim(),
                            readChapters = cappedReadCh,
                            totalChapters = totalCh,
                            genres = genres.trim(),
                            rating = rating,
                            comment = comment.trim(),
                            isFinished = isFinished,
                            isWebNovel = isWebNovel
                        )
                    )
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
