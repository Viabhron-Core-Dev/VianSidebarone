package com.example.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.feature.language.dictionary.DictionaryImportProgress
import com.example.feature.language.dictionary.DictionaryImportResult
import com.example.feature.language.dictionary.DictionaryService
import com.example.feature.language.dictionary.InstalledDictionary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val ROUTE_DICTIONARY_SETTINGS = "dictionary_settings"
const val ROUTE_DICTIONARY_TRANSLATION_SETTINGS = "dictionary_translation_settings"
const val ROUTE_LEGACY_TRANSLATION_SETTINGS = "translation_settings"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionarySettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val dictionaryService = remember(context) { DictionaryService.getInstance(context) }

    var dictionaries by remember { mutableStateOf<List<InstalledDictionary>>(emptyList()) }
    var isImporting by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf<DictionaryImportProgress?>(null) }
    var importFeedback by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var dictionaryPendingDelete by remember { mutableStateOf<InstalledDictionary?>(null) }

    fun reloadDictionaries() {
        coroutineScope.launch {
            dictionaries = withContext(Dispatchers.IO) {
                dictionaryService.getInstalledDictionaries()
            }
        }
    }

    LaunchedEffect(Unit) {
        dictionaries = withContext(Dispatchers.IO) {
            dictionaryService.getInstalledDictionaries()
        }
    }

    // Zero-permission SAF document picker for StarDict ZIP archives
    val zipImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null || isImporting) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            isImporting = true
            importFeedback = null
            importProgress = DictionaryImportProgress("Starting", "Opening selected ZIP archive...")
            val result = withContext(Dispatchers.IO) {
                dictionaryService.importFromUri(uri) { progress ->
                    coroutineScope.launch(Dispatchers.Main) {
                        importProgress = progress
                    }
                }
            }
            isImporting = false
            importProgress = null
            when (result) {
                is DictionaryImportResult.Success -> {
                    val count = result.installed.size
                    val names = result.installed.joinToString(", ") { it.name }
                    val dupNote = if (result.skippedDuplicates.isNotEmpty()) {
                        " (Skipped duplicate: ${result.skippedDuplicates.joinToString(", ")})"
                    } else ""
                    importFeedback = true to "Imported $count ${if (count == 1) "dictionary" else "dictionaries"}: $names$dupNote"
                    reloadDictionaries()
                }
                is DictionaryImportResult.Failure -> {
                    importFeedback = false to result.message
                    reloadDictionaries()
                }
            }
        }
    }

    val enabledDictCount = dictionaries.count { it.enabled }
    val totalDictBytes = dictionaries.sumOf { it.storageBytes }
    val dictStatusLabel = when {
        dictionaries.isEmpty() -> "No dictionaries"
        enabledDictCount == 0 -> "${dictionaries.size} disabled"
        else -> "$enabledDictCount active"
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Dictionary Settings") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("dictionary_settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("dictionary_settings_screen"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Shared offline StarDict dictionaries used by both the floating Dictionary window and the Meaning text-selection popup.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_section_dictionary"),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DictionarySectionHeaderRow(
                        title = "Offline Dictionaries (StarDict)",
                        statusLabel = dictStatusLabel,
                        statusTag = "dictionary_status_badge"
                    )

                    Text(
                        text = "Import StarDict ZIP packages (.ifo, .idx / .idx.gz, .dict / .dict.dz, optional .syn). Lookups run completely offline across enabled dictionaries in priority order.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                zipImportLauncher.launch(
                                    arrayOf(
                                        "application/zip",
                                        "application/x-zip-compressed",
                                        "application/octet-stream",
                                        "*/*"
                                    )
                                )
                            },
                            enabled = !isImporting,
                            modifier = Modifier.testTag("dictionary_import_zip_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isImporting) "Importing..." else "Import StarDict (.zip)")
                        }

                        if (dictionaries.isNotEmpty()) {
                            Text(
                                text = DictionaryService.formatStorageBytes(totalDictBytes),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("dictionary_total_storage_text")
                            )
                        }
                    }

                    if (isImporting) {
                        val progress = importProgress
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("dictionary_import_progress"),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (progress?.fraction != null) {
                                LinearProgressIndicator(
                                    progress = { progress.fraction.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            Text(
                                text = "${progress?.stage ?: "Importing"}: ${progress?.detail ?: "Processing archive..."}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    importFeedback?.let { (success, message) ->
                        DictionaryFeedbackBanner(
                            isSuccess = success,
                            message = message,
                            tag = "dictionary_import_feedback"
                        )
                    }

                    HorizontalDivider()

                    if (dictionaries.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("dictionary_empty_state"),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "No offline dictionaries installed",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Tap \"Import StarDict (.zip)\" to select a ZIP archive containing one or more StarDict dictionaries.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("dictionary_installed_list"),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            dictionaries.forEachIndexed { index, dict ->
                                InstalledDictionaryItemRow(
                                    dictionary = dict,
                                    rank = index + 1,
                                    canMoveUp = index > 0,
                                    canMoveDown = index < dictionaries.lastIndex,
                                    onToggleEnabled = { enabled ->
                                        coroutineScope.launch {
                                            withContext(Dispatchers.IO) {
                                                dictionaryService.setDictionaryEnabled(dict.id, enabled)
                                            }
                                            reloadDictionaries()
                                        }
                                    },
                                    onMoveUp = {
                                        coroutineScope.launch {
                                            withContext(Dispatchers.IO) {
                                                dictionaryService.moveDictionaryPriority(dict.id, moveUp = true)
                                            }
                                            reloadDictionaries()
                                        }
                                    },
                                    onMoveDown = {
                                        coroutineScope.launch {
                                            withContext(Dispatchers.IO) {
                                                dictionaryService.moveDictionaryPriority(dict.id, moveUp = false)
                                            }
                                            reloadDictionaries()
                                        }
                                    },
                                    onRequestDelete = { dictionaryPendingDelete = dict }
                                )
                                if (index < dictionaries.lastIndex) {
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    dictionaryPendingDelete?.let { dict ->
        ConfirmDeleteDictionaryDialog(
            title = "Delete Dictionary?",
            message = "Remove \"${dict.name}\" (${DictionaryService.formatStorageBytes(dict.storageBytes)}) and delete all local StarDict index files?",
            onConfirm = {
                dictionaryPendingDelete = null
                coroutineScope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        dictionaryService.deleteDictionary(dict.id)
                    }
                    importFeedback = if (deleted) {
                        true to "Deleted dictionary \"${dict.name}\"."
                    } else {
                        false to "Could not completely delete \"${dict.name}\"."
                    }
                    reloadDictionaries()
                }
            },
            onDismiss = { dictionaryPendingDelete = null }
        )
    }
}

/**
 * Safe, ML Kit-free fallback screen if a legacy shortcut opens `translation_settings`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeferredTranslationNoticeScreen(
    onOpenDictionarySettings: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Dictionary Settings") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("deferred_translation_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .testTag("deferred_translation_notice_screen"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Translation Deferred to Vian World",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "On-device translation has been deferred from Sidebar for future Vian World integration. Offline StarDict dictionaries and Meaning lookups remain active in Sidebar.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = onOpenDictionarySettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("deferred_notice_open_dictionary_settings")
                    ) {
                        Text("Open Dictionary Settings")
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledDictionaryItemRow(
    dictionary: InstalledDictionary,
    rank: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRequestDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("installed_dictionary_row_${dictionary.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "#$rank  ${dictionary.name}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                val statsLine = buildString {
                    append("${dictionary.wordCount} words")
                    if (dictionary.synonymCount > 0) {
                        append(" • ${dictionary.synonymCount} synonyms")
                    }
                    append(" • ${DictionaryService.formatStorageBytes(dictionary.storageBytes)}")
                    if (dictionary.idxOffsetBits == 64) {
                        append(" • 64-bit")
                    }
                }
                Text(
                    text = statsLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val meta = dictionary.description.ifBlank { dictionary.author }
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }
            Switch(
                checked = dictionary.enabled,
                onCheckedChange = onToggleEnabled,
                modifier = Modifier.testTag("dictionary_enable_switch_${dictionary.id}")
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onMoveUp,
                enabled = canMoveUp,
                modifier = Modifier.testTag("dictionary_priority_up_${dictionary.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Increase priority of ${dictionary.name}"
                )
            }
            IconButton(
                onClick = onMoveDown,
                enabled = canMoveDown,
                modifier = Modifier.testTag("dictionary_priority_down_${dictionary.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Decrease priority of ${dictionary.name}"
                )
            }
            IconButton(
                onClick = onRequestDelete,
                modifier = Modifier.testTag("dictionary_delete_${dictionary.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete ${dictionary.name}",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun DictionaryFeedbackBanner(
    isSuccess: Boolean,
    message: String,
    tag: String
) {
    Surface(
        color = if (isSuccess) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (isSuccess) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(10.dp)
        )
    }
}

@Composable
private fun DictionarySectionHeaderRow(
    title: String,
    statusLabel: String,
    statusTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.testTag(statusTag)
        ) {
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun ConfirmDeleteDictionaryDialog(
    title: String,
    message: String,
    confirmLabel: String = "Delete",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("confirm_delete_dialog_button")
            ) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
