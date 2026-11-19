package com.example.feature.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

const val ROUTE_DICTIONARY_TRANSLATION_SETTINGS = "dictionary_translation_settings"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryTranslationSettingsScreen(
    onBack: () -> Unit
) {
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
                text = "Shared configuration for the standalone Dictionary and Translation floating windows and text-selection popups.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 1. Dictionary Section
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
                        title = "Dictionary",
                        statusLabel = "Not configured",
                        statusTag = "dictionary_status_badge"
                    )

                    Text(
                        text = "Configure offline dictionary sources for the Dictionary mini-app and the Meaning text-selection popup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider()

                    PlaceholderSettingRow(
                        title = "StarDict Support",
                        subtitle = "Offline StarDict (.ifo / .idx / .dict) parsing and lookup will be configured here in a future update.",
                        statusValue = "Planned",
                        tag = "dictionary_stardict_placeholder"
                    )

                    HorizontalDivider()

                    PlaceholderSettingRow(
                        title = "Imported Dictionaries",
                        subtitle = "Dictionary import and local index management are not enabled yet.",
                        statusValue = "0 installed",
                        tag = "dictionary_import_status_placeholder"
                    )
                }
            }

            // 2. Translation Section
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
                    SectionHeaderRow(
                        title = "Translation",
                        statusLabel = "Not configured",
                        statusTag = "translation_status_badge"
                    )

                    Text(
                        text = "Configure translation engines, offline language packs, and default language preferences for the Translation mini-app and Translate popup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider()

                    PlaceholderSettingRow(
                        title = "Translation Models & Packs",
                        subtitle = "No translation engine or offline language packs are installed yet. Model management will appear here once translation support is added.",
                        statusValue = "0 installed",
                        tag = "translation_models_placeholder"
                    )

                    HorizontalDivider()

                    PlaceholderSettingRow(
                        title = "Language Configuration",
                        subtitle = "Default source/target language profiles and offline fallback preferences will be managed here.",
                        statusValue = "Auto → English",
                        tag = "translation_language_config_placeholder"
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
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
            fontWeight = FontWeight.SemiBold
        )
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
private fun PlaceholderSettingRow(
    title: String,
    subtitle: String,
    statusValue: String,
    tag: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
                text = statusValue,
                style = MaterialTheme.typography.labelMedium,
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
