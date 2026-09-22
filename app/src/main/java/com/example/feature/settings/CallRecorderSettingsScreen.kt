package com.example.feature.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallRecorderSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)

    var enabled by remember { mutableStateOf(prefs.getBoolean("call_recorder_enabled", false)) }
    var manualEnabled by remember { mutableStateOf(prefs.getBoolean("call_recorder_manual_enabled", false)) }
    var audioSource by remember { mutableStateOf(prefs.getInt("call_recorder_audio_source", MediaRecorder.AudioSource.VOICE_RECOGNITION)) }
    var format by remember { mutableStateOf(prefs.getString("call_recorder_format", "MPEG_4") ?: "MPEG_4") }
    var quality by remember { mutableStateOf(prefs.getInt("call_recorder_quality", 128000)) }
    var saveFolder by remember { mutableStateOf(prefs.getString("call_recorder_save_folder", "") ?: "") }

    var pinLockEnabled by remember { mutableStateOf(prefs.getBoolean("call_recorder_pin_enabled", false)) }
    var currentPinHash by remember { mutableStateOf(prefs.getString("call_recorder_pin_hash", "")) }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinDialogMode by remember { mutableStateOf("enable") } // enable, disable, change
    var pinInput by remember { mutableStateOf("") }
    var oldPinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    val safLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            saveFolder = uri.toString()
            prefs.edit().putString("call_recorder_save_folder", uri.toString()).apply()
        }
    }

    val permissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS
    )
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.all { it.value }) {
            enabled = true
            prefs.edit().putBoolean("call_recorder_enabled", true).apply()
        } else {
            enabled = false
            prefs.edit().putBoolean("call_recorder_enabled", false).apply()
        }
    }

    val manualLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.all { it.value }) {
            manualEnabled = true
            prefs.edit().putBoolean("call_recorder_manual_enabled", true).apply()
        } else {
            manualEnabled = false
            prefs.edit().putBoolean("call_recorder_manual_enabled", false).apply()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Call Recorder") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            }
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                ListItem(
                    headlineContent = { Text("View Recordings") },
                    supportingContent = { Text("Browse and manage saved call recordings") },
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(context, com.example.feature.system_hub.RecordingsActivity::class.java))
                    }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Enable Automatic Recording") },
                    supportingContent = { Text("Records all calls based on filter rules") },
                    trailingContent = {
                        Switch(
                            checked = enabled,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    launcher.launch(permissions)
                                } else {
                                    enabled = false
                                    prefs.edit().putBoolean("call_recorder_enabled", false).apply()
                                }
                            }
                        )
                    }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Enable Manual Record Button") },
                    supportingContent = { Text("Shows a floating record button during calls") },
                    trailingContent = {
                        Switch(
                            checked = manualEnabled,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    manualLauncher.launch(permissions)
                                } else {
                                    manualEnabled = false
                                    prefs.edit().putBoolean("call_recorder_manual_enabled", false).apply()
                                }
                            }
                        )
                    }
                )
                ListItem(
                    headlineContent = { Text("Enable PIN Lock on Recordings") },
                    supportingContent = { Text("Require a 4-digit PIN to view recordings") },
                    trailingContent = {
                        Switch(
                            checked = pinLockEnabled,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    pinDialogMode = "enable"
                                    pinInput = ""
                                    pinError = false
                                    showPinDialog = true
                                } else {
                                    pinDialogMode = "disable"
                                    pinInput = ""
                                    pinError = false
                                    showPinDialog = true
                                }
                            }
                        )
                    }
                )
                if (pinLockEnabled) {
                    ListItem(
                        headlineContent = { Text("Change PIN") },
                        modifier = Modifier.clickable {
                            pinDialogMode = "change"
                            oldPinInput = ""
                            pinInput = ""
                            pinError = false
                            showPinDialog = true
                        }
                    )
                }
                Divider()
                
                Text(
                    text = "Audio Source",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp)
                )

                val sources = listOf(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION to "Voice Recognition (Recommended)",
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION to "Voice Communication",
                    MediaRecorder.AudioSource.MIC to "Microphone",
                    MediaRecorder.AudioSource.VOICE_CALL to "Voice Call (May not work)"
                )

                sources.forEach { (sourceValue, title) ->
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        RadioButton(
                            selected = audioSource == sourceValue,
                            onClick = {
                                audioSource = sourceValue
                                prefs.edit().putInt("call_recorder_audio_source", sourceValue).apply()
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(title)
                    }
                }
                
                Divider()
                
                Text(
                    text = "Format & Quality",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp)
                )

                val formats = listOf("MPEG_4" to "MP3 / AAC (MPEG-4)", "THREE_GPP" to "WAV / AMR (3GPP)")
                formats.forEach { (formatValue, title) ->
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable {
                            format = formatValue
                            prefs.edit().putString("call_recorder_format", formatValue).apply()
                        }
                    ) {
                        RadioButton(selected = format == formatValue, onClick = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(title)
                    }
                }

                val qualities = listOf(64000 to "Low (64 kbps)", 128000 to "Medium (128 kbps)", 256000 to "High (256 kbps)")
                qualities.forEach { (qualityValue, title) ->
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable {
                            quality = qualityValue
                            prefs.edit().putInt("call_recorder_quality", qualityValue).apply()
                        }
                    ) {
                        RadioButton(selected = quality == qualityValue, onClick = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(title)
                    }
                }

                Divider()

                ListItem(
                    headlineContent = { Text("Save Folder") },
                    supportingContent = { Text(if (saveFolder.isEmpty()) "Default (Music/.Records)" else android.net.Uri.parse(saveFolder).path ?: "Custom Folder") },
                    trailingContent = {
                        Button(onClick = { safLauncher.launch(null) }) {
                            Text("Change")
                        }
                    }
                )

                Divider()
                
                Text(
                    text = "Filter Mode",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp)
                )

                val filterModes = listOf(
                    "all" to "Record all calls",
                    "non_contacts" to "Non-contacts only",
                    "whitelist" to "Selected contacts only (whitelist)",
                    "blacklist" to "Exclude specific numbers (blacklist)"
                )
                
                var filterMode by remember { mutableStateOf(prefs.getString("call_recorder_filter_mode", "all") ?: "all") }
                var showNumberDialog by remember { mutableStateOf<String?>(null) }
                
                filterModes.forEach { (modeValue, title) ->
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable {
                            filterMode = modeValue
                            prefs.edit().putString("call_recorder_filter_mode", modeValue).apply()
                        }
                    ) {
                        RadioButton(selected = filterMode == modeValue, onClick = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(title)
                    }
                }
                
                if (filterMode == "whitelist" || filterMode == "blacklist") {
                    Button(
                        onClick = { showNumberDialog = filterMode },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("Manage ${if (filterMode == "whitelist") "Whitelist" else "Blacklist"}")
                    }
                }
                
                if (showNumberDialog != null) {
                    var numberInput by remember { mutableStateOf("") }
                    var numbers by remember { 
                        mutableStateOf(prefs.getStringSet("call_recorder_${showNumberDialog}", emptySet())?.toList() ?: emptyList()) 
                    }
                    
                    AlertDialog(
                        onDismissRequest = { showNumberDialog = null },
                        title = { Text("Manage ${if (showNumberDialog == "whitelist") "Whitelist" else "Blacklist"}") },
                        text = {
                            Column {
                                OutlinedTextField(
                                    value = numberInput,
                                    onValueChange = { numberInput = it },
                                    label = { Text("Phone Number") }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(onClick = {
                                    if (numberInput.isNotBlank()) {
                                        val newSet = (numbers + numberInput).toSet()
                                        prefs.edit().putStringSet("call_recorder_${showNumberDialog}", newSet).apply()
                                        numbers = newSet.toList()
                                        numberInput = ""
                                    }
                                }) {
                                    Text("Add")
                                }
                                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                                    items(numbers.size) { index ->
                                        val num = numbers[index]
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                        ) {
                                            Text(num)
                                            IconButton(onClick = {
                                                val newSet = numbers.filter { it != num }.toSet()
                                                prefs.edit().putStringSet("call_recorder_${showNumberDialog}", newSet).apply()
                                                numbers = newSet.toList()
                                            }) {
                                                Icon(Icons.Default.Delete, contentDescription = "Remove")
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showNumberDialog = null }) {
                                Text("Close")
                            }
                        }
                    )
                }

                Divider()
                
                Text(
                    text = "Privacy Note: The recordings are saved in the app's external music directory inside a hidden folder ('.Records') with a '.nomedia' file to prevent gallery or music apps from scanning the audio files.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        
        if (showPinDialog) {
            AlertDialog(
                onDismissRequest = { 
                    showPinDialog = false
                    pinInput = ""
                    oldPinInput = ""
                },
                title = { 
                    Text(
                        when (pinDialogMode) {
                            "enable" -> "Set PIN"
                            "disable" -> "Enter Current PIN"
                            else -> "Change PIN"
                        }
                    ) 
                },
                text = {
                    Column {
                        if (pinDialogMode == "change" || pinDialogMode == "disable") {
                            OutlinedTextField(
                                value = oldPinInput,
                                onValueChange = { if (it.length <= 4 && it.all { char -> char.isDigit() }) oldPinInput = it },
                                label = { Text("Current 4-Digit PIN") },
                                isError = pinError,
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        if (pinDialogMode == "change" || pinDialogMode == "enable") {
                            OutlinedTextField(
                                value = pinInput,
                                onValueChange = { if (it.length <= 4 && it.all { char -> char.isDigit() }) pinInput = it },
                                label = { Text("New 4-Digit PIN") },
                                isError = pinError,
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword)
                            )
                        }
                        if (pinError) {
                            Text("Invalid PIN.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (pinDialogMode == "enable") {
                            if (pinInput.length == 4) {
                                val hash = com.example.core.PinUtils.hashPin(pinInput)
                                currentPinHash = hash
                                pinLockEnabled = true
                                prefs.edit()
                                    .putBoolean("call_recorder_pin_enabled", true)
                                    .putString("call_recorder_pin_hash", hash)
                                    .apply()
                                showPinDialog = false
                            } else {
                                pinError = true
                            }
                        } else if (pinDialogMode == "disable") {
                            if (com.example.core.PinUtils.hashPin(oldPinInput) == currentPinHash) {
                                pinLockEnabled = false
                                currentPinHash = ""
                                prefs.edit()
                                    .putBoolean("call_recorder_pin_enabled", false)
                                    .remove("call_recorder_pin_hash")
                                    .apply()
                                showPinDialog = false
                            } else {
                                pinError = true
                            }
                        } else if (pinDialogMode == "change") {
                            if (com.example.core.PinUtils.hashPin(oldPinInput) == currentPinHash && pinInput.length == 4) {
                                val hash = com.example.core.PinUtils.hashPin(pinInput)
                                currentPinHash = hash
                                prefs.edit()
                                    .putString("call_recorder_pin_hash", hash)
                                    .apply()
                                showPinDialog = false
                            } else {
                                pinError = true
                            }
                        }
                    }) {
                        Text("Confirm")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPinDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
