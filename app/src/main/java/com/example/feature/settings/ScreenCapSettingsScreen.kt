package com.example.feature.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * ScreenCapSettingsScreen: Settings interface for adjusting screenshot capture delay,
 * custom storage directory (SAF), and screen recording video quality / audio toggles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenCapSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ScreenCapPrefs", Context.MODE_PRIVATE) }

    var saveLocation by remember {
        mutableStateOf(prefs.getString("save_location", "Default (Pictures/Screenshots)") ?: "Default (Pictures/Screenshots)")
    }
    var delaySeconds by remember {
        mutableStateOf(prefs.getInt("screenshot_delay", 0))
    }
    var recordQuality by remember {
        mutableStateOf(prefs.getInt("record_quality", 720))
    }
    var recordAudio by remember {
        mutableStateOf(prefs.getBoolean("record_audio", false))
    }

    val dirLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Ignore security exceptions if URI does not support persistable grants
            }
            val path = uri.toString()
            prefs.edit().putString("save_location", path).apply()
            saveLocation = path
            Toast.makeText(context, "Location saved", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Screen Cap Settings") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("screencap_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                modifier = Modifier.testTag("screencap_topbar")
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .testTag("screencap_settings_list")
        ) {
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Save Location (Screenshot & Video)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = saveLocation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .testTag("text_save_location")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { dirLauncher.launch(null) },
                        modifier = Modifier.testTag("btn_change_location")
                    ) {
                        Text("Change Location")
                    }
                    Button(
                        onClick = {
                            prefs.edit().putString("save_location", "Default (Pictures/Screenshots)").apply()
                            saveLocation = "Default (Pictures/Screenshots)"
                        },
                        colors = ButtonDefaults.outlinedButtonColors(),
                        modifier = Modifier.testTag("btn_reset_location")
                    ) {
                        Text("Reset")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

                Text(
                    text = "Screenshot Settings",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "Delay before capturing screen: ${delaySeconds}s",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .testTag("text_screenshot_delay")
                )
                Slider(
                    value = delaySeconds.toFloat(),
                    onValueChange = {
                        delaySeconds = it.toInt()
                        prefs.edit().putInt("screenshot_delay", delaySeconds).apply()
                    },
                    valueRange = 0f..10f,
                    steps = 9,
                    modifier = Modifier.testTag("slider_screenshot_delay")
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

                Text(
                    text = "Screen Record Settings",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Record Audio (Microphone)")
                    Switch(
                        checked = recordAudio,
                        onCheckedChange = {
                            recordAudio = it
                            prefs.edit().putBoolean("record_audio", it).apply()
                        },
                        modifier = Modifier.testTag("switch_record_audio")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Video Quality",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable {
                                recordQuality = 720
                                prefs.edit().putInt("record_quality", 720).apply()
                            }
                    ) {
                        RadioButton(
                            selected = recordQuality == 720,
                            onClick = {
                                recordQuality = 720
                                prefs.edit().putInt("record_quality", 720).apply()
                            },
                            modifier = Modifier.testTag("radio_quality_720")
                        )
                        Text("720p")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable {
                                recordQuality = 1080
                                prefs.edit().putInt("record_quality", 1080).apply()
                            }
                    ) {
                        RadioButton(
                            selected = recordQuality == 1080,
                            onClick = {
                                recordQuality = 1080
                                prefs.edit().putInt("record_quality", 1080).apply()
                            },
                            modifier = Modifier.testTag("radio_quality_1080")
                        )
                        Text("1080p")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable {
                                recordQuality = 0
                                prefs.edit().putInt("record_quality", 0).apply()
                            }
                    ) {
                        RadioButton(
                            selected = recordQuality == 0,
                            onClick = {
                                recordQuality = 0
                                prefs.edit().putInt("record_quality", 0).apply()
                            },
                            modifier = Modifier.testTag("radio_quality_original")
                        )
                        Text("Original")
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
