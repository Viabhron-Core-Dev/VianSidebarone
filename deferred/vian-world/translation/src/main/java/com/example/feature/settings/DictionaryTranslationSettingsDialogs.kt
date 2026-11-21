package com.example.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.feature.language.translation.LanguageOption
import com.example.feature.language.translation.ModelDownloadState

@Composable
internal fun LanguageSelectionDialog(
    title: String,
    options: List<LanguageOption>,
    selectedCode: String,
    downloadedCodes: Set<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(options, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) options
        else options.filter {
            it.displayName.lowercase().contains(q) || it.code.lowercase().contains(q)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("translation_language_picker_dialog"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("translation_language_picker_search"),
                    singleLine = true,
                    placeholder = { Text("Search ${options.size} languages...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search languages"
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear filter"
                                )
                            }
                        }
                    }
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 340.dp)
                ) {
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                text = "No matching language found.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    } else {
                        items(filtered, key = { it.code }) { option ->
                            val isSelected = option.code == selectedCode
                            val isInstalled = !option.isAutoDetect && option.code in downloadedCodes
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(option.code) }
                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = option.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                    val sub = buildString {
                                        if (option.isAutoDetect) {
                                            append("On-device ML Kit language identification")
                                        } else {
                                            append(option.code.uppercase())
                                            if (isInstalled) append(" • Downloaded")
                                        }
                                    }
                                    Text(
                                        text = sub,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
internal fun TranslationModelsBrowserDialog(
    languages: List<LanguageOption>,
    downloadedCodes: Set<String>,
    modelStates: Map<String, ModelDownloadState>,
    wifiOnlyDownloads: Boolean,
    onDownload: (String) -> Unit,
    onRequestDelete: (LanguageOption) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(languages, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) languages
        else languages.filter {
            it.displayName.lowercase().contains(q) || it.code.lowercase().contains(q)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Offline Language Models", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (wifiOnlyDownloads) {
                        "Wi-Fi-only downloads enabled (${languages.size} ML Kit languages)"
                    } else {
                        "Wi-Fi or mobile data allowed (${languages.size} ML Kit languages)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("translation_models_browser_dialog"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("translation_models_browser_search"),
                    singleLine = true,
                    placeholder = { Text("Filter ML Kit languages...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Filter models"
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear filter"
                                )
                            }
                        }
                    }
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 360.dp)
                ) {
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                text = "No matching language models.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    } else {
                        items(filtered, key = { it.code }) { option ->
                            val state = modelStates[option.code]
                                ?: if (option.code in downloadedCodes) ModelDownloadState.Downloaded
                                else ModelDownloadState.NotDownloaded

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = option.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = when (state) {
                                                ModelDownloadState.Downloaded -> "${option.code.uppercase()} • Installed"
                                                ModelDownloadState.Downloading -> "${option.code.uppercase()} • Downloading..."
                                                is ModelDownloadState.Failed -> "${option.code.uppercase()} • Failed"
                                                ModelDownloadState.NotDownloaded -> "${option.code.uppercase()} • Available"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = when (state) {
                                                ModelDownloadState.Downloaded -> MaterialTheme.colorScheme.primary
                                                is ModelDownloadState.Failed -> MaterialTheme.colorScheme.error
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }

                                    when (state) {
                                        ModelDownloadState.Downloading -> {
                                            CircularProgressIndicator(
                                                modifier = Modifier
                                                    .padding(horizontal = 12.dp)
                                                    .size(22.dp),
                                                strokeWidth = 2.dp
                                            )
                                        }
                                        ModelDownloadState.Downloaded -> {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Surface(
                                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                                    shape = MaterialTheme.shapes.small
                                                ) {
                                                    Text(
                                                        text = "Ready",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                    )
                                                }
                                                IconButton(
                                                    onClick = { onRequestDelete(option) },
                                                    modifier = Modifier.testTag("delete_model_browser_${option.code}")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Delete,
                                                        contentDescription = "Delete ${option.displayName} model",
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }
                                        }
                                        is ModelDownloadState.Failed -> {
                                            OutlinedButton(
                                                onClick = { onDownload(option.code) },
                                                modifier = Modifier.testTag("retry_model_${option.code}")
                                            ) {
                                                Text("Retry")
                                            }
                                        }
                                        ModelDownloadState.NotDownloaded -> {
                                            OutlinedButton(
                                                onClick = { onDownload(option.code) },
                                                modifier = Modifier.testTag("download_model_${option.code}")
                                            ) {
                                                Text("Download")
                                            }
                                        }
                                    }
                                }
                                if (state is ModelDownloadState.Failed) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = state.message,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
internal fun ConfirmDeleteDialog(
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
