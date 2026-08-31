package com.example.feature.system_hub

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.res.painterResource
import com.example.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import android.content.Intent
import androidx.core.content.FileProvider

data class RecordingItem(
    val id: String,
    val name: String,
    val uri: Uri,
    val date: Long,
    val size: Long,
    val durationMs: Long,
    val isSaf: Boolean
)

class RecordingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                RecordingsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RecordingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE) }
    
    val pinLockEnabled = remember { prefs.getBoolean("call_recorder_pin_enabled", false) }
    val currentPinHash = remember { prefs.getString("call_recorder_pin_hash", "") ?: "" }
    var isAuthenticated by remember { mutableStateOf(!pinLockEnabled) }
    
    if (!isAuthenticated) {
        PinEntryScreen(
            correctPinHash = currentPinHash,
            onAuthenticated = { isAuthenticated = true },
            onCancel = onBack
        )
        return
    }

    val coroutineScope = rememberCoroutineScope()
    var recordings by remember { mutableStateOf<List<RecordingItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    val selectedIds = remember { mutableStateListOf<String>() }
    
    var currentlyPlaying by remember { mutableStateOf<String?>(null) }
    val mediaPlayer = remember { MediaPlayer() }

    LaunchedEffect(Unit) {
        isLoading = true
        recordings = loadRecordings(context)
        isLoading = false
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                mediaPlayer.release()
            } catch (e: Exception) {}
        }
    }

    val filteredRecordings = recordings.filter {
        it.name.contains(searchQuery, ignoreCase = true)
    }

    val isMultiSelect = selectedIds.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    if (isMultiSelect) {
                        Text("${selectedIds.size} Selected")
                    } else {
                        Text("Recordings") 
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { 
                        if (isMultiSelect) {
                            selectedIds.clear()
                        } else {
                            onBack() 
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isMultiSelect) {
                        IconButton(onClick = {
                            coroutineScope.launch {
                                deleteSelected(context, recordings, selectedIds)
                                recordings = recordings.filterNot { selectedIds.contains(it.id) }
                                selectedIds.clear()
                            }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Selected")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!isMultiSelect) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search") },
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                )
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (filteredRecordings.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No recordings found.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredRecordings, key = { it.id }) { item ->
                        val isSelected = selectedIds.contains(item.id)
                        val isPlaying = currentlyPlaying == item.id
                        
                        ListItem(
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    if (isMultiSelect) {
                                        if (isSelected) selectedIds.remove(item.id) else selectedIds.add(item.id)
                                    } else {
                                        // Play / pause
                                        try {
                                            if (isPlaying) {
                                                mediaPlayer.stop()
                                                mediaPlayer.reset()
                                                currentlyPlaying = null
                                            } else {
                                                mediaPlayer.reset()
                                                mediaPlayer.setDataSource(context, item.uri)
                                                mediaPlayer.prepare()
                                                mediaPlayer.start()
                                                currentlyPlaying = item.id
                                                mediaPlayer.setOnCompletionListener {
                                                    currentlyPlaying = null
                                                }
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                },
                                onLongClick = {
                                    if (!isMultiSelect) {
                                        selectedIds.add(item.id)
                                    }
                                }
                            ),
                            headlineContent = { Text(item.name) },
                            supportingContent = { 
                                val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(item.date))
                                val sizeStr = Formatter.formatShortFileSize(context, item.size)
                                val durationSec = item.durationMs / 1000
                                val durationStr = String.format("%02d:%02d", durationSec / 60, durationSec % 60)
                                Text("$dateStr • $durationStr • $sizeStr")
                            },
                            leadingContent = {
                                if (isMultiSelect) {
                                    Checkbox(checked = isSelected, onCheckedChange = {
                                        if (it) selectedIds.add(item.id) else selectedIds.remove(item.id)
                                    })
                                } else {
                                if (isPlaying) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_stop),
                                        contentDescription = "Stop"
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = "Play"
                                    )
                                }
                                }
                            },
                            trailingContent = {
                                if (!isMultiSelect) {
                                    Row {
                                        IconButton(onClick = {
                                            shareRecording(context, item.uri)
                                        }) {
                                            Icon(Icons.Default.Share, contentDescription = "Share")
                                        }
                                        IconButton(onClick = {
                                            coroutineScope.launch {
                                                deleteSelected(context, recordings, listOf(item.id))
                                                recordings = recordings.filterNot { it.id == item.id }
                                            }
                                        }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                                        }
                                    }
                                }
                            },
                            colors = if (isSelected) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant) else ListItemDefaults.colors()
                        )
                        Divider()
                    }
                }
            }
        }
    }
}

private fun shareRecording(context: Context, uri: Uri) {
    try {
        val shareUri = if (uri.scheme == "file") {
            val file = File(uri.path ?: "")
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        } else {
            uri
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            clipData = android.content.ClipData.newRawUri("", shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share Recording")
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
    } catch (e: Exception) {
        e.printStackTrace()
        android.widget.Toast.makeText(context, "Error sharing recording", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private suspend fun deleteSelected(context: Context, recordings: List<RecordingItem>, ids: List<String>) {
    withContext(Dispatchers.IO) {
        val toDelete = recordings.filter { ids.contains(it.id) }
        for (item in toDelete) {
            try {
                if (item.isSaf) {
                    DocumentFile.fromSingleUri(context, item.uri)?.delete()
                } else {
                    File(item.uri.path ?: "").delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

private suspend fun loadRecordings(context: Context): List<RecordingItem> = withContext(Dispatchers.IO) {
    val items = mutableListOf<RecordingItem>()
    val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
    val saveFolderStr = prefs.getString("call_recorder_save_folder", "") ?: ""

    val retriever = MediaMetadataRetriever()

    if (saveFolderStr.isNotEmpty()) {
        try {
            val uri = Uri.parse(saveFolderStr)
            val documentFile = DocumentFile.fromTreeUri(context, uri)
            if (documentFile != null && documentFile.exists()) {
                documentFile.listFiles().forEach { file ->
                    val fName = file.name ?: ""
                    if (fName.startsWith("CALL_") || fName.startsWith("AUDIO_") || fName.endsWith(".m4a") || fName.endsWith(".3gp")) {
                        var duration = 0L
                        try {
                            retriever.setDataSource(context, file.uri)
                            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        } catch (e: Exception) {}
                        
                        items.add(
                            RecordingItem(
                                id = file.uri.toString(),
                                name = file.name ?: "Unknown",
                                uri = file.uri,
                                date = file.lastModified(),
                                size = file.length(),
                                durationMs = duration,
                                isSaf = true
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    } else {
        val recordsDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), ".Records")
        if (recordsDir.exists()) {
            recordsDir.listFiles()?.forEach { file ->
                val fName = file.name
                if ((fName.startsWith("CALL_") || fName.startsWith("AUDIO_") || fName.endsWith(".m4a") || fName.endsWith(".3gp")) && file.isFile) {
                    var duration = 0L
                    try {
                        retriever.setDataSource(file.absolutePath)
                        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } catch (e: Exception) {}
                    
                    items.add(
                        RecordingItem(
                            id = file.absolutePath,
                            name = file.name,
                            uri = Uri.fromFile(file),
                            date = file.lastModified(),
                            size = file.length(),
                            durationMs = duration,
                            isSaf = false
                        )
                    )
                }
            }
        }
    }
    
    try {
        retriever.release()
    } catch (e: Exception) {}

    items.sortedByDescending { it.date }
}

@Composable
fun PinEntryScreen(correctPinHash: String, onAuthenticated: () -> Unit, onCancel: () -> Unit) {
    var pinInput by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val offsetX = remember { androidx.compose.animation.core.Animatable(0f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Enter PIN", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = pinInput,
            onValueChange = {
                if (it.length <= 4 && it.all { char -> char.isDigit() }) {
                    pinInput = it
                    isError = false
                    if (it.length == 4) {
                        if (com.example.core.PinUtils.hashPin(it) == correctPinHash) {
                            onAuthenticated()
                        } else {
                            isError = true
                            pinInput = ""
                            coroutineScope.launch {
                                offsetX.animateTo(targetValue = 20f, animationSpec = androidx.compose.animation.core.tween(50))
                                offsetX.animateTo(targetValue = -20f, animationSpec = androidx.compose.animation.core.tween(50))
                                offsetX.animateTo(targetValue = 20f, animationSpec = androidx.compose.animation.core.tween(50))
                                offsetX.animateTo(targetValue = -20f, animationSpec = androidx.compose.animation.core.tween(50))
                                offsetX.animateTo(targetValue = 0f, animationSpec = androidx.compose.animation.core.tween(50))
                            }
                        }
                    }
                }
            },
            label = { Text("4-Digit PIN") },
            isError = isError,
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
            modifier = Modifier.offset(x = offsetX.value.dp)
        )
        if (isError) {
            Text("Incorrect PIN", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onCancel) {
            Text("Cancel")
        }
    }
}
