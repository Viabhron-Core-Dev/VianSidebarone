package com.example.feature.pwa

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.data.AppDatabase
import com.example.data.PwaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PwaListActivity: Central management and selection library for Offline PWAs.
 *
 * Replaces the previous "Empty Item" entry in the Add Element flow.
 * Supports:
 * - Imported PWA list with icons and metadata
 * - Real-time name search
 * - Sorting by Name, Date, Size
 * - Long-press multi-selection and batch deletion
 * - Single item edit screen
 * - FAB (+) to import a PWA dist.zip
 * - Integration with element-placement architecture when in picking mode
 */
class PwaListActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pageId = intent.getStringExtra("PAGE_ID")
        val containerId = intent.getStringExtra("CONTAINER_ID") ?: "sidebar"
        val isHybridGrid = intent.getBooleanExtra("IS_HYBRID_GRID", false)
        val isPicking = intent.getBooleanExtra("IS_PICKING", false)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PwaListScreen(
                    isPicking = isPicking,
                    onBack = { finish() },
                    onSelectForGrid = { pwa ->
                        com.example.feature.sidebar.ElementMetadataStore.savePwaElement(
                            this@PwaListActivity,
                            pwa.id,
                            pwa.name,
                            pwa.iconPath
                        )
                        val resultIntent = Intent().apply {
                            putExtra("ELEMENT_ID", "pwa:${pwa.id}")
                            putExtra("DISPLAY_NAME", pwa.name)
                            putExtra("PWA_NAME", pwa.name)
                            putExtra("ICON_PATH", pwa.iconPath)
                            putExtra("CONTAINER_ID", containerId)
                            putExtra("PAGE_ID", pageId)
                        }
                        setResult(Activity.RESULT_OK, resultIntent)
                        finish()
                    },
                    onOpenEdit = { pwa ->
                        val editIntent = Intent(this, PwaEditActivity::class.java).apply {
                            putExtra("PWA_ID", pwa.id)
                        }
                        startActivity(editIntent)
                    },
                    onLaunchPlayer = { pwa ->
                        val playerIntent = Intent(this, PwaPlayerActivity::class.java).apply {
                            putExtra("PWA_ID", pwa.id)
                        }
                        startActivity(playerIntent)
                    }
                )
            }
        }
    }
}

enum class PwaSortOption {
    NAME_ASC,
    NAME_DESC,
    DATE_NEWEST,
    DATE_OLDEST,
    SIZE_DESC
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PwaListScreen(
    isPicking: Boolean,
    onBack: () -> Unit,
    onSelectForGrid: (PwaEntity) -> Unit,
    onOpenEdit: (PwaEntity) -> Unit,
    onLaunchPlayer: (PwaEntity) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.getDatabase(context).pwaDao() }

    val allPwas by dao.getAllFlow().collectAsState(initial = emptyList())

    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(PwaSortOption.NAME_ASC) }
    var showSortMenu by remember { mutableStateOf(false) }

    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }

    // Zip file picker launcher
    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isImporting = true
            scope.launch {
                try {
                    val originalName = withContext(Dispatchers.IO) {
                        var name = "pwa_bundle.zip"
                        try {
                            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (nameIndex >= 0 && cursor.moveToFirst()) {
                                    name = cursor.getString(nameIndex)
                                }
                            }
                        } catch (_: Throwable) {}
                        name
                    }

                    val entity = withContext(Dispatchers.IO) {
                        PwaZipImporter.importZipUri(context, uri, originalName)
                    }
                    Toast.makeText(context, "Imported: ${entity.name}", Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    isImporting = false
                }
            }
        }
    }

    // Process search & sort
    val displayedPwas = remember(allPwas, searchQuery, sortOption) {
        var list = if (searchQuery.isBlank()) {
            allPwas
        } else {
            allPwas.filter { it.name.contains(searchQuery, ignoreCase = true) || it.shortName.contains(searchQuery, ignoreCase = true) }
        }

        when (sortOption) {
            PwaSortOption.NAME_ASC -> list.sortedBy { it.name.lowercase() }
            PwaSortOption.NAME_DESC -> list.sortedByDescending { it.name.lowercase() }
            PwaSortOption.DATE_NEWEST -> list.sortedByDescending { it.createdAt }
            PwaSortOption.DATE_OLDEST -> list.sortedBy { it.createdAt }
            PwaSortOption.SIZE_DESC -> list.sortedByDescending { it.packageSize }
        }
    }

    Scaffold(
        topBar = {
            if (isMultiSelectMode) {
                TopAppBar(
                    title = { Text("${selectedIds.size} Selected") },
                    navigationIcon = {
                        IconButton(onClick = {
                            isMultiSelectMode = false
                            selectedIds.clear()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                    },
                    actions = {
                        if (selectedIds.isNotEmpty()) {
                            IconButton(onClick = { showDeleteConfirmDialog = true }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete selected", tint = Color(0xFFFF5252))
                            }
                        }
                    }
                )
            } else if (isSearching) {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search PWAs...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            isSearching = false
                            searchQuery = ""
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(if (isPicking) "Select Offline PWA" else "Offline PWA Library") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { isSearching = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Sort options")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Sort: Name (A-Z)") },
                                    onClick = { sortOption = PwaSortOption.NAME_ASC; showSortMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Sort: Name (Z-A)") },
                                    onClick = { sortOption = PwaSortOption.NAME_DESC; showSortMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Sort: Date (Newest)") },
                                    onClick = { sortOption = PwaSortOption.DATE_NEWEST; showSortMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Sort: Date (Oldest)") },
                                    onClick = { sortOption = PwaSortOption.DATE_OLDEST; showSortMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Sort: Size") },
                                    onClick = { sortOption = PwaSortOption.SIZE_DESC; showSortMenu = false }
                                )
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!isMultiSelectMode) {
                FloatingActionButton(
                    onClick = { zipPickerLauncher.launch("application/zip") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Import dist.zip")
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color(0xFF121212))
        ) {
            if (isImporting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x88000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text("Extracting PWA package...", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else if (displayedPwas.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (searchQuery.isNotBlank()) "No PWAs match '$searchQuery'" else "No imported PWAs yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.Gray
                        )
                        if (searchQuery.isBlank()) {
                            Text(
                                text = "Tap + to import a PWA dist.zip",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.DarkGray
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(displayedPwas, key = { it.id }) { pwa ->
                        val isSelected = selectedIds.contains(pwa.id)

                        PwaListItem(
                            pwa = pwa,
                            isPicking = isPicking,
                            isMultiSelectMode = isMultiSelectMode,
                            isSelected = isSelected,
                            onItemClick = {
                                if (isMultiSelectMode) {
                                    if (isSelected) selectedIds.remove(pwa.id) else selectedIds.add(pwa.id)
                                    if (selectedIds.isEmpty()) isMultiSelectMode = false
                                } else if (isPicking) {
                                    onSelectForGrid(pwa)
                                } else {
                                    onLaunchPlayer(pwa)
                                }
                            },
                            onItemLongClick = {
                                if (!isMultiSelectMode) {
                                    isMultiSelectMode = true
                                    selectedIds.add(pwa.id)
                                }
                            },
                            onEditClick = { onOpenEdit(pwa) },
                            onAddClick = { onSelectForGrid(pwa) }
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete ${selectedIds.size} PWA(s)?") },
            text = { Text("This will permanently remove the PWA packages, extracted assets, and isolated offline storage.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val idsToDelete = selectedIds.toList()
                        showDeleteConfirmDialog = false
                        isMultiSelectMode = false
                        selectedIds.clear()

                        scope.launch(Dispatchers.IO) {
                            for (id in idsToDelete) {
                                PwaStorageManager.deletePwa(context, id)
                            }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Deleted ${idsToDelete.size} PWA(s)", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text("Delete", color = Color(0xFFFF5252))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PwaListItem(
    pwa: PwaEntity,
    isPicking: Boolean,
    isMultiSelectMode: Boolean,
    isSelected: Boolean,
    onItemClick: () -> Unit,
    onItemLongClick: () -> Unit,
    onEditClick: () -> Unit,
    onAddClick: () -> Unit
) {
    val cardBg = if (isSelected) Color(0xFF2E3856) else Color(0xFF1E1E2E)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onItemClick,
                onLongClick = onItemLongClick
            ),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isMultiSelectMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onItemClick() }
                )
            }

            // PWA Icon or Letter Avatar
            val iconBitmap = remember(pwa.iconPath) {
                if (!pwa.iconPath.isNullOrEmpty()) {
                    val file = File(pwa.iconPath)
                    if (file.exists()) {
                        android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    } else null
                } else null
            }

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Color(0xFF2A2A3C), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap.asImageBitmap(),
                        contentDescription = pwa.name,
                        modifier = Modifier.fillMaxSize().padding(6.dp)
                    )
                } else {
                    Text(
                        text = pwa.name.take(1).uppercase(),
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Text Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pwa.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${PwaStorageManager.formatPackageSize(pwa.packageSize)} • v${pwa.version}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }

            // Actions
            if (!isMultiSelectMode) {
                IconButton(onClick = onEditClick) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit PWA", tint = Color.LightGray)
                }
                if (isPicking) {
                    FilledTonalButton(
                        onClick = onAddClick,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Add", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
