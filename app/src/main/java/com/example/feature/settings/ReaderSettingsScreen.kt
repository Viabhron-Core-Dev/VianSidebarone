package com.example.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.feature.reader.BookTrackerActivity
import com.example.feature.reader.ReaderPreferences
import kotlin.math.roundToInt

/**
 * Dedicated Reader Settings screen in `:heavy` (`SettingsActivity`), separate from Book Tracker.
 * Configures default reading typography, theme preset, auto-scroll speed, TTS rate,
 * and provides direct navigation to Book Tracker and LiteReader Backups.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var fontSizeSp by remember {
        mutableFloatStateOf(ReaderPreferences.getDefaultFontSizeSp(context))
    }
    var lineSpacingMult by remember {
        mutableFloatStateOf(ReaderPreferences.getLineSpacingMultiplier(context))
    }
    var themePreset by remember {
        mutableStateOf(ReaderPreferences.getThemePreset(context))
    }
    var autoScrollSpeed by remember {
        mutableFloatStateOf(ReaderPreferences.getAutoScrollSpeed(context))
    }
    var ttsRate by remember {
        mutableFloatStateOf(ReaderPreferences.getTtsSpeechRate(context))
    }

    val themes = listOf(
        ReaderPreferences.THEME_DARK to "Dark",
        ReaderPreferences.THEME_SEPIA to "Sepia",
        ReaderPreferences.THEME_AMOLED to "AMOLED",
        ReaderPreferences.THEME_LIGHT to "Light"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reader Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("reader_settings_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Typography & Theme", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                    Text("Default Font Size: ${fontSizeSp.roundToInt()} sp", fontSize = 14.sp)
                    Slider(
                        value = fontSizeSp,
                        onValueChange = {
                            fontSizeSp = it
                            ReaderPreferences.setDefaultFontSizeSp(context, it)
                        },
                        valueRange = 12f..28f,
                        steps = 7
                    )

                    Text("Line Spacing: ${String.format("%.2f", lineSpacingMult)}x", fontSize = 14.sp)
                    Slider(
                        value = lineSpacingMult,
                        onValueChange = {
                            lineSpacingMult = it
                            ReaderPreferences.setLineSpacingMultiplier(context, it)
                        },
                        valueRange = 1.0f..2.2f,
                        steps = 5
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Reader Theme Preset", fontSize = 14.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        themes.forEach { (key, label) ->
                            FilterChip(
                                selected = themePreset.equals(key, ignoreCase = true),
                                onClick = {
                                    themePreset = key
                                    ReaderPreferences.setThemePreset(context, key)
                                },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Auto-Scroll & Text-to-Speech", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                    Text("Auto-Scroll Speed: ${String.format("%.1f", autoScrollSpeed)}x", fontSize = 14.sp)
                    Slider(
                        value = autoScrollSpeed,
                        onValueChange = {
                            autoScrollSpeed = it
                            ReaderPreferences.setAutoScrollSpeed(context, it)
                        },
                        valueRange = 0.5f..3.0f,
                        steps = 4
                    )

                    Text("TTS Speech Rate: ${String.format("%.2f", ttsRate)}x", fontSize = 14.sp)
                    Slider(
                        value = ttsRate,
                        onValueChange = {
                            ttsRate = it
                            ReaderPreferences.setTtsSpeechRate(context, it)
                        },
                        valueRange = 0.5f..2.0f,
                        steps = 5
                    )
                }
            }

            HorizontalDivider()

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Book Tracker & Backups", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "Manage reading history, edit book metadata, or import/export LiteReader ZIP, TSV, and Moon+ backups.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { BookTrackerActivity.launch(context, openBackupPanel = false) },
                        modifier = Modifier.fillMaxWidth().testTag("open_book_tracker_button")
                    ) {
                        Text("Open Book Tracker")
                    }
                    OutlinedButton(
                        onClick = { BookTrackerActivity.launch(context, openBackupPanel = true) },
                        modifier = Modifier.fillMaxWidth().testTag("open_reader_backups_button")
                    ) {
                        Text("Import / Export Reader Backups")
                    }
                }
            }
        }
    }
}
