package com.example.feature.pwa

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.core.IconCacheManager
import com.example.core.SquircleHelper
import com.example.data.AppDatabase
import com.example.data.PwaEntity
import com.example.feature.sidebar.ElementMetadataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PwaEditActivity: Configuration, metadata, and orientation editor for imported Offline PWAs.
 */
class PwaEditActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pwaId = intent.getStringExtra("PWA_ID") ?: run {
            finish()
            return
        }

        val prefs = getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var pwa by remember { mutableStateOf<PwaEntity?>(null) }
                val initialOrientation = remember(pwaId) {
                    prefs.getString("pwa_pref_${pwaId}_orientation", "manifest") ?: "manifest"
                }
                val initialCloseButton = remember(pwaId) {
                    prefs.getString("pwa_pref_${pwaId}_close_button", "show") ?: "show"
                }

                LaunchedEffect(pwaId) {
                    val entity = withContext(Dispatchers.IO) {
                        AppDatabase.getDatabase(this@PwaEditActivity).pwaDao().getById(pwaId)
                    }
                    pwa = entity
                }

                val currentPwa = pwa
                if (currentPwa != null) {
                    PwaEditScreen(
                        pwa = currentPwa,
                        initialOrientation = initialOrientation,
                        initialCloseButton = initialCloseButton,
                        onBack = { finish() },
                        onSave = { updatedName, selectedOrientation, selectedCloseButton ->
                            lifecycleScope.launch {
                                withContext(Dispatchers.IO) {
                                    AppDatabase.getDatabase(this@PwaEditActivity).pwaDao()
                                        .updateMetadata(
                                            id = currentPwa.id,
                                            name = updatedName,
                                            iconPath = currentPwa.iconPath,
                                            updatedAt = System.currentTimeMillis()
                                        )
                                    ElementMetadataStore.savePwaElement(
                                        this@PwaEditActivity,
                                        currentPwa.id,
                                        updatedName,
                                        currentPwa.iconPath
                                    )
                                    prefs.edit()
                                        .putString("pwa_pref_${currentPwa.id}_orientation", selectedOrientation)
                                        .putString("pwa_pref_${currentPwa.id}_close_button", selectedCloseButton)
                                        .apply()

                                    if (!currentPwa.iconPath.isNullOrEmpty()) {
                                        IconCacheManager.getPwaIcon(this@PwaEditActivity, currentPwa.id, currentPwa.iconPath)
                                    }
                                }
                                Toast.makeText(this@PwaEditActivity, "Saved", Toast.LENGTH_SHORT).show()
                                finish()
                            }
                        }
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PwaEditScreen(
    pwa: PwaEntity,
    initialOrientation: String,
    initialCloseButton: String,
    onBack: () -> Unit,
    onSave: (newName: String, orientation: String, closeButton: String) -> Unit
) {
    var name by remember { mutableStateOf(pwa.name) }
    var orientation by remember { mutableStateOf(initialOrientation) }
    var closeButtonOption by remember { mutableStateOf(initialCloseButton) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit PWA") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("pwa_edit_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { if (name.isNotBlank()) onSave(name.trim(), orientation, closeButtonOption) },
                        modifier = Modifier.testTag("pwa_edit_save_button")
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Save", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color(0xFF121212))
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header with Icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val iconBitmap = remember(pwa.iconPath) {
                    if (!pwa.iconPath.isNullOrEmpty()) {
                        val file = File(pwa.iconPath)
                        if (file.exists()) {
                            val raw = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                            if (raw != null) SquircleHelper.toSquircleBitmap(raw) else null
                        } else null
                    } else null
                }

                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(Color(0xFF2A2A3C), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap.asImageBitmap(),
                            contentDescription = pwa.name,
                            modifier = Modifier.fillMaxSize().padding(4.dp)
                        )
                    } else {
                        Text(
                            text = pwa.name.take(1).uppercase(),
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pwa.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Text(
                        text = "ID: ${pwa.id}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }

            // Editable Name
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("App Name") },
                modifier = Modifier.fillMaxWidth().testTag("pwa_edit_name_input"),
                singleLine = true
            )

            // Orientation Override Setting
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Orientation",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )

                    val options = listOf(
                        "manifest" to "Follow Manifest (${pwa.orientation.ifBlank { "unspecified" }})",
                        "unspecified" to "Auto / Unspecified",
                        "portrait" to "Portrait",
                        "landscape" to "Landscape"
                    )

                    options.forEach { (key, label) ->
                        val isSelected = orientation == key
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable { orientation = key }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("pwa_orientation_$key"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { orientation = key },
                                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSelected) Color.White else Color.LightGray
                            )
                        }
                    }
                }
            }

            // Close Button Setting
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Close Button",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )

                    val closeOptions = listOf(
                        "show" to "Show (Display floating exit button)",
                        "hide" to "Hide (Android Back key exits at root)"
                    )

                    closeOptions.forEach { (key, label) ->
                        val isSelected = closeButtonOption == key
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable { closeButtonOption = key }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("pwa_close_button_$key"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { closeButtonOption = key },
                                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSelected) Color.White else Color.LightGray
                            )
                        }
                    }
                }
            }

            // Read-only Details Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Package Details", style = MaterialTheme.typography.titleSmall, color = Color.White)
                    DetailRow("Entrypoint", pwa.startUrl)
                    DetailRow("Display Mode", pwa.displayMode)
                    DetailRow("Package Size", PwaStorageManager.formatPackageSize(pwa.packageSize))
                    DetailRow("Version", pwa.version)
                    DetailRow("Imported On", dateFormat.format(Date(pwa.createdAt)))
                    if (pwa.lastLaunchedAt > 0) {
                        DetailRow("Last Launched", dateFormat.format(Date(pwa.lastLaunchedAt)))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { if (name.isNotBlank()) onSave(name.trim(), orientation, closeButtonOption) },
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("pwa_save_changes_button"),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Save Changes")
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Text(text = value, style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
    }
}
