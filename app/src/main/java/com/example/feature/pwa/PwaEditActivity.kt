package com.example.feature.pwa

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.data.AppDatabase
import com.example.data.PwaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PwaEditActivity: Configuration and metadata editor for imported Offline PWAs.
 */
class PwaEditActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pwaId = intent.getStringExtra("PWA_ID") ?: run {
            finish()
            return
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var pwa by remember { mutableStateOf<PwaEntity?>(null) }

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
                        onBack = { finish() },
                        onSave = { updatedName ->
                            lifecycleScope.launch {
                                withContext(Dispatchers.IO) {
                                    AppDatabase.getDatabase(this@PwaEditActivity).pwaDao()
                                        .updateMetadata(
                                            id = currentPwa.id,
                                            name = updatedName,
                                            iconPath = currentPwa.iconPath,
                                            updatedAt = System.currentTimeMillis()
                                        )
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
    onBack: () -> Unit,
    onSave: (newName: String) -> Unit
) {
    var name by remember { mutableStateOf(pwa.name) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit PWA") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { if (name.isNotBlank()) onSave(name.trim()) }) {
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
                            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                        } else null
                    } else null
                }

                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(Color(0xFF2A2A3C), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap.asImageBitmap(),
                            contentDescription = pwa.name,
                            modifier = Modifier.fillMaxSize().padding(8.dp)
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
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

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
                onClick = { if (name.isNotBlank()) onSave(name.trim()) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
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
