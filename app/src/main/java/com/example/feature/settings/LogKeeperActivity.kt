package com.example.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.example.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LogKeeperActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF006C50),
                onPrimary = Color.White,
                primaryContainer = Color(0xFF8CF5D1),
                onPrimaryContainer = Color(0xFF002117),
                background = Color(0xFFFBFDF9),
                onBackground = Color(0xFF191C1B),
                surface = Color(0xFFFFFFFF),
                onSurface = Color(0xFF191C1B),
                surfaceVariant = Color(0xFFDBE5DF),
                onSurfaceVariant = Color(0xFF3F4945)
            )) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFF8F9FA)
                ) {
                    LogKeeperScreen(onClose = { finish() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogKeeperScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Running Events & System Logs, 1 = Crash Logs
    var normalLogContent by remember { mutableStateOf("") }
    var crashLogContent by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }

    fun loadLogs() {
        isLoading = true
        coroutineScope.launch(Dispatchers.IO) {
            val normalText = readLogFile(context, "LiteReader_Log.txt")
            val crashText = readLogFile(context, "LiteReader_CrashLog.txt")
            withContext(Dispatchers.Main) {
                normalLogContent = normalText
                crashLogContent = crashText
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadLogs()
    }

    val currentContent = if (selectedTab == 0) normalLogContent else crashLogContent
    val currentFileName = if (selectedTab == 0) "LiteReader_Log.txt" else "LiteReader_CrashLog.txt"

    Scaffold(
        containerColor = Color(0xFFF8F9FA),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Log Keeper", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF1C1B1F))
                        Text(
                            if (selectedTab == 0) "Live Running Events & IPC" else "Crash & Exception Reports",
                            fontSize = 11.sp,
                            color = Color(0xFF74777F)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color(0xFF1C1B1F))
                    }
                },
                actions = {
                    // Refresh
                    IconButton(onClick = { loadLogs() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Color(0xFF1C1B1F))
                    }
                    // Copy
                    IconButton(
                        onClick = {
                            if (currentContent.isNotBlank()) {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText(currentFileName, currentContent)
                                cm.setPrimaryClip(clip)
                                Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "No logs to copy", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(painterResource(R.drawable.ic_content_copy), contentDescription = "Copy", tint = Color(0xFF1C1B1F))
                    }
                    // Share
                    IconButton(
                        onClick = {
                            if (currentContent.isNotBlank()) {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, currentFileName)
                                    putExtra(Intent.EXTRA_TEXT, currentContent)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Logs"))
                            } else {
                                Toast.makeText(context, "No logs to share", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color(0xFF1C1B1F))
                    }
                    // Clear / Delete
                    IconButton(
                        onClick = {
                            coroutineScope.launch(Dispatchers.IO) {
                                clearLogFile(context, currentFileName)
                                withContext(Dispatchers.Main) {
                                    loadLogs()
                                    Toast.makeText(context, "$currentFileName cleared", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) {
                        Icon(painterResource(R.drawable.ic_delete_sweep), contentDescription = "Clear", tint = Color(0xFFBA1A1A))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFFFFFFF))
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Tab row in Light Theme
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.White,
                contentColor = Color(0xFF006C50)
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painterResource(R.drawable.ic_description),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (selectedTab == 0) Color(0xFF006C50) else Color(0xFF74777F)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Running Events",
                                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium,
                                color = if (selectedTab == 0) Color(0xFF006C50) else Color(0xFF444746)
                            )
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val hasCrashes = crashLogContent.isNotBlank()
                            Icon(
                                painterResource(R.drawable.ic_bug_report),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (hasCrashes) Color(0xFFBA1A1A) else if (selectedTab == 1) Color(0xFF006C50) else Color(0xFF74777F)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Crash Logs",
                                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium,
                                color = if (hasCrashes) Color(0xFFBA1A1A) else if (selectedTab == 1) Color(0xFF006C50) else Color(0xFF444746)
                            )
                        }
                    }
                )
            }

            HorizontalDivider(color = Color(0xFFE0E3E1))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF006C50))
                }
            } else if (currentContent.isBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (selectedTab == 0) {
                                Icon(
                                    imageVector = Icons.Filled.Info,
                                    contentDescription = null,
                                    tint = Color(0xFF006C50),
                                    modifier = Modifier.size(48.dp)
                                )
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check_circle),
                                    contentDescription = null,
                                    tint = Color(0xFF006C50),
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (selectedTab == 0) "No running logs recorded yet." else "No crashes detected! All components healthy.",
                                color = Color(0xFF191C1B),
                                fontWeight = FontWeight.Medium,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (selectedTab == 0) "Actions and lifecycle events will appear here." else "Crash traces will be caught and displayed here.",
                                color = Color(0xFF74777F),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            } else {
                val lines = remember(currentContent) { currentContent.lines() }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFF4F6F4))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    items(lines) { line ->
                        val isHeader = line.startsWith("===") || line.startsWith("---")
                        val isError = line.contains("Exception") || line.contains("Error") || line.contains("FATAL")
                        val isMeta = line.contains("Timestamp:") || line.contains("Thread:") || line.contains("Action:")
                        val isStackTrace = line.trim().startsWith("at ")

                        val textColor = when {
                            isError -> Color(0xFFBA1A1A)
                            isHeader -> Color(0xFF006C50)
                            isMeta -> Color(0xFF004D40)
                            isStackTrace -> Color(0xFF53635D)
                            else -> Color(0xFF191C1B)
                        }

                        val bgColor = when {
                            isHeader -> Color(0xFFE8F5E9)
                            isError -> Color(0xFFFFEBEE)
                            else -> Color.Transparent
                        }

                        val fontWeight = when {
                            isHeader || isError -> FontWeight.Bold
                            isMeta -> FontWeight.SemiBold
                            else -> FontWeight.Normal
                        }

                        Surface(
                            color = bgColor,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = if (isHeader) 3.dp else 0.5.dp)
                        ) {
                            Text(
                                text = line,
                                color = textColor,
                                fontWeight = fontWeight,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(horizontal = if (isHeader || isError) 6.dp else 2.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun getTargetLogDirectory(context: Context): File? {
    return context.filesDir
}

private fun readLogFile(context: Context, fileName: String): String {
    return try {
        val primaryFile = File(context.filesDir, fileName)
        if (primaryFile.exists() && primaryFile.length() > 0) {
            return primaryFile.readText()
        }
        val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (fallbackDir != null) {
            val fallbackFile = File(fallbackDir, fileName)
            if (fallbackFile.exists() && fallbackFile.length() > 0) {
                val text = fallbackFile.readText()
                primaryFile.writeText(text)
                return text
            }
        }
        if (primaryFile.exists()) primaryFile.readText() else ""
    } catch (e: Exception) {
        ""
    }
}

private fun clearLogFile(context: Context, fileName: String) {
    try {
        val file = File(context.filesDir, fileName)
        if (file.exists()) {
            file.delete()
        }
        val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (fallbackDir != null) {
            val fallbackFile = File(fallbackDir, fileName)
            if (fallbackFile.exists()) {
                fallbackFile.delete()
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
