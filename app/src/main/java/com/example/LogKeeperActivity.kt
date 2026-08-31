package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.LogKeeper
import com.example.data.AppDatabase
import com.example.data.LogEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogKeeperActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF6750A4),
                    background = Color(0xFFFCFCFF)
                )
            ) {
                LogKeeperScreen(
                    onBack = { finish() },
                    db = AppDatabase.getDatabase(this),
                    context = this
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogKeeperScreen(onBack: () -> Unit, db: AppDatabase, context: Context) {
    val coroutineScope = rememberCoroutineScope()
    var isMasterSwitchEnabled by remember { mutableStateOf(LogKeeper.isEnabled) }
    
    var timeFilterHours by remember { mutableStateOf<Int?>(1) }
    var logs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    
    LaunchedEffect(timeFilterHours) {
        val minTimestamp = timeFilterHours?.let { System.currentTimeMillis() - it * 60 * 60 * 1000L } ?: 0L
        db.logDao().getLogsFlow(minTimestamp).collect { list ->
            logs = list
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text("The Log Keeper", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Text(
                        text = "Master Switch", 
                        fontWeight = FontWeight.Bold, 
                        fontSize = 16.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Switch(
                        checked = isMasterSwitchEnabled,
                        onCheckedChange = { 
                            isMasterSwitchEnabled = it
                            LogKeeper.isEnabled = it
                        },
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // Time filters
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(1 to "1H", 6 to "6H", 12 to "12H", 24 to "24H").forEach { (hours, label) ->
                    FilterChip(
                        selected = timeFilterHours == hours,
                        onClick = { timeFilterHours = hours },
                        label = { Text(label, fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            }
            
            // Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
            ) {
                Button(
                    onClick = {
                        val allLogText = logs.joinToString("\n") { 
                            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(it.timestamp))
                            "[$dateStr] ${it.tag} - ${it.message}"
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Logs", allLogText)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Copy", fontWeight = FontWeight.Bold)
                }
                
                Button(
                    onClick = {
                        coroutineScope.launch {
                            val allLogText = logs.joinToString("\n") { 
                                val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(it.timestamp))
                                "[$dateStr] ${it.tag} - ${it.message}"
                            }
                            val dateString = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                            if (downloadsDir != null && downloadsDir.exists()) {
                                try {
                                    val file = File(downloadsDir, "LogKeeper_Export_$dateString.txt")
                                    val writer = FileWriter(file)
                                    writer.write(allLogText)
                                    writer.close()
                                    Toast.makeText(context, "Downloaded to Downloads folder", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to download", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Download", fontWeight = FontWeight.Bold)
                }
            }
            
            // Log list
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentPadding = PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(logs) { log ->
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFEADDFF).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                    ) {
                        Column {
                            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(log.timestamp))
                            Text(
                                "[$dateStr] INFO - ${log.tag}", 
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = Color.Black
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                log.message, 
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.Black
                            )
                        }
                    }
                }
            }
        }
    }
}
