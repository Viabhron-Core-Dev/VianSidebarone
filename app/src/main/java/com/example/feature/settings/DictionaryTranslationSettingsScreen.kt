package com.example.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.example.feature.language.translation.LanguageOption
import com.example.feature.language.translation.ModelDownloadState
import com.example.feature.language.translation.TranslationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val ROUTE_DICTIONARY_TRANSLATION_SETTINGS = "dictionary_translation_settings"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryTranslationSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val dictionaryService = remember(context) { DictionaryService.getInstance(context) }
    val translationService = remember(context) { TranslationService.getInstance(context) }

    // Dictionary state
    var dictionaries by remember { mutableStateOf<List<InstalledDictionary>>(emptyList()) }
    var isImporting by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf<DictionaryImportProgress?>(null) }
    var importFeedback by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var dictionaryPendingDelete by remember { mutableStateOf<InstalledDictionary?>(null) }

    // Translation state
    var translationConfig by remember { mutableStateOf(translationService.getConfig()) }
    val downloadedLanguages by translationService.downloadedLanguages.collectAsState()
    val modelStates by translationService.modelStates.collectAsState()
    var translationFeedback by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var isRefreshingModels by remember { mutableStateOf(false) }
    var showSourcePicker by remember { mutableStateOf(false) }
    var showTargetPicker by remember { mutableStateOf(false) }
    var showModelsBrowser by remember { mutableStateOf(false) }
    var modelPendingDelete by remember { mutableStateOf<LanguageOption?>(null) }

    val sourceLanguageOptions = remember(translationService) {
        translationService.getSupportedSourceLanguages()
    }
    val targetLanguageOptions = remember(translationService) {
        translationService.getSupportedTargetLanguages()
    }

    fun reloadDictionaries() {
        coroutineScope.launch {
            dictionaries = withContext(Dispatchers.IO) {
                dictionaryService.getInstalledDictionaries()
            }
        }
    }

    fun refreshTranslationModels() {
        if (isRefreshingModels) return
        coroutineScope.launch {
            isRefreshingModels = true
            val res = withContext(Dispatchers.IO) {
                translationService.refreshDownloadedModels()
            }
            if (res.isFailure) {
                translationFeedback = false to (
                    res.exceptionOrNull()?.message
                        ?: "Could not query downloaded ML Kit language models."
                    )
            }
            isRefreshingModels = false
        }
    }

    LaunchedEffect(Unit) {
        dictionaries = withContext(Dispatchers.IO) {
            dictionaryService.getInstalledDictionaries()
        }
        translationConfig = withContext(Dispatchers.IO) {
            translationService.getConfig()
        }
        refreshTranslationModels()
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

    val downloadedSorted = remember(downloadedLanguages, translationService) {
        downloadedLanguages
            .map { code -> LanguageOption(code, translationService.getLanguageDisplayName(code)) }
            .sortedBy { it.displayName.lowercase() }
    }
    val translationStatusLabel = when {
        downloadedSorted.isEmpty() -> "No models installed"
        downloadedSorted.size == 1 -> "1 model ready"
        else -> "${downloadedSorted.size} models ready"
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Dictionary & Translation") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("dict_trans_settings_back_button")
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
                .testTag("dictionary_translation_settings_screen"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Shared offline StarDict dictionaries and Google ML Kit on-device translation models used by both floating windows and text-selection popups.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 1. Offline Dictionary Section (StarDict)
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
                    SectionHeaderRow(
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
                        FeedbackBanner(
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

            // 2. On-Device Translation Section (Google ML Kit)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_section_translation"),
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            SectionHeaderRow(
                                title = "On-Device Translation (ML Kit)",
                                statusLabel = translationStatusLabel,
                                statusTag = "translation_status_badge"
                            )
                        }
                        IconButton(
                            onClick = { refreshTranslationModels() },
                            enabled = !isRefreshingModels,
                            modifier = Modifier.testTag("translation_refresh_models_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh downloaded models"
                            )
                        }
                    }

                    Text(
                        text = "Uses Google ML Kit on-device translation models and language identification. Downloaded models run locally without API keys.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider()

                    // Default Source Language
                    LanguagePreferenceRow(
                        title = "Default Source Language",
                        subtitle = "Used by the Translation window and Translate text-selection popup.",
                        value = translationService.getLanguageDisplayName(translationConfig.sourceLanguageCode),
                        tag = "translation_source_language_row",
                        onClick = { showSourcePicker = true }
                    )

                    HorizontalDivider()

                    // Default Target Language
                    LanguagePreferenceRow(
                        title = "Default Target Language",
                        subtitle = "Default output language for on-device translations.",
                        value = translationService.getLanguageDisplayName(translationConfig.targetLanguageCode),
                        tag = "translation_target_language_row",
                        onClick = { showTargetPicker = true }
                    )

                    HorizontalDivider()

                    // Wi-Fi-only Download Toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("translation_wifi_only_row"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Wi-Fi-only model downloads",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (translationConfig.wifiOnlyDownloads) {
                                    "On (recommended). Language models download only over Wi-Fi."
                                } else {
                                    "Off. Mobile data downloads are allowed when downloading models."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = translationConfig.wifiOnlyDownloads,
                            onCheckedChange = { wifiOnly ->
                                coroutineScope.launch {
                                    translationConfig = withContext(Dispatchers.IO) {
                                        translationService.updateConfig(wifiOnlyDownloads = wifiOnly)
                                    }
                                }
                            },
                            modifier = Modifier.testTag("translation_wifi_only_switch")
                        )
                    }

                    HorizontalDivider()

                    // Downloaded Models Summary & Action
                    Text(
                        text = "Downloaded Offline Models",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    translationFeedback?.let { (success, message) ->
                        FeedbackBanner(
                            isSuccess = success,
                            message = message,
                            tag = "translation_action_feedback"
                        )
                    }

                    val activeDownloadingCodes = modelStates.filterValues { it is ModelDownloadState.Downloading }.keys
                    if (activeDownloadingCodes.isNotEmpty()) {
                        val names = activeDownloadingCodes.joinToString(", ") {
                            translationService.getLanguageDisplayName(it)
                        }
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                text = "Downloading model(s): $names...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (downloadedSorted.isEmpty()) {
                        Text(
                            text = "No ML Kit translation models are downloaded yet. Browse available languages below to download models for offline translation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("translation_models_empty_state")
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("translation_downloaded_models_list"),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            downloadedSorted.forEachIndexed { idx, lang ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = lang.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "${lang.code.uppercase()} • On-device model installed",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = { modelPendingDelete = lang },
                                        modifier = Modifier.testTag("delete_downloaded_model_${lang.code}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete ${lang.displayName} model",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                if (idx < downloadedSorted.lastIndex) {
                                    HorizontalDivider()
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { showModelsBrowser = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("translation_browse_models_button")
                    ) {
                        Text("Browse & Download Language Models (${targetLanguageOptions.size})")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    // Dialogs
    if (showSourcePicker) {
        LanguageSelectionDialog(
            title = "Default Source Language",
            options = sourceLanguageOptions,
            selectedCode = translationConfig.sourceLanguageCode,
            downloadedCodes = downloadedLanguages,
            onSelect = { code ->
                showSourcePicker = false
                coroutineScope.launch {
                    translationConfig = withContext(Dispatchers.IO) {
                        translationService.updateConfig(sourceLanguageCode = code)
                    }
                }
            },
            onDismiss = { showSourcePicker = false }
        )
    }

    if (showTargetPicker) {
        LanguageSelectionDialog(
            title = "Default Target Language",
            options = targetLanguageOptions,
            selectedCode = translationConfig.targetLanguageCode,
            downloadedCodes = downloadedLanguages,
            onSelect = { code ->
                showTargetPicker = false
                coroutineScope.launch {
                    translationConfig = withContext(Dispatchers.IO) {
                        translationService.updateConfig(targetLanguageCode = code)
                    }
                }
            },
            onDismiss = { showTargetPicker = false }
        )
    }

    if (showModelsBrowser) {
        TranslationModelsBrowserDialog(
            languages = targetLanguageOptions,
            downloadedCodes = downloadedLanguages,
            modelStates = modelStates,
            wifiOnlyDownloads = translationConfig.wifiOnlyDownloads,
            onDownload = { code ->
                translationFeedback = null
                coroutineScope.launch {
                    val res = withContext(Dispatchers.IO) {
                        translationService.downloadModel(code)
                    }
                    val langName = translationService.getLanguageDisplayName(code)
                    translationFeedback = if (res.isSuccess) {
                        true to "Downloaded $langName offline model."
                    } else {
                        false to (res.exceptionOrNull()?.message ?: "Failed to download $langName model.")
                    }
                }
            },
            onRequestDelete = { lang -> modelPendingDelete = lang },
            onDismiss = { showModelsBrowser = false }
        )
    }

    dictionaryPendingDelete?.let { dict ->
        ConfirmDeleteDialog(
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

    modelPendingDelete?.let { lang ->
        ConfirmDeleteDialog(
            title = "Delete Language Model?",
            message = "Remove the offline ${lang.displayName} (${lang.code.uppercase()}) ML Kit model from this device? You can re-download it later.",
            onConfirm = {
                modelPendingDelete = null
                coroutineScope.launch {
                    val res = withContext(Dispatchers.IO) {
                        translationService.deleteModel(lang.code)
                    }
                    translationFeedback = if (res.isSuccess) {
                        true to "Deleted ${lang.displayName} offline model."
                    } else {
                        false to (res.exceptionOrNull()?.message ?: "Failed to delete ${lang.displayName} model.")
                    }
                }
            },
            onDismiss = { modelPendingDelete = null }
        )
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
private fun LanguagePreferenceRow(
    title: String,
    subtitle: String,
    value: String,
    tag: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FeedbackBanner(
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
private fun SectionHeaderRow(
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
