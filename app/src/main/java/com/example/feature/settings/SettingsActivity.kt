package com.example.feature.settings

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

import com.example.util.AppLogger
import android.widget.Toast
import android.content.Intent
import android.os.Build
import android.net.Uri

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.core.LogKeeper.writeLog("Settings", "Opened SettingsActivity")
        enableEdgeToEdge()
        val startRoute = intent.getStringExtra("start_route") ?: "main"
        
        setContent {
            MaterialTheme {
                SettingsApp(startRoute = startRoute) {
                    finishAndRemoveTask()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsApp(startRoute: String, onFinish: () -> Unit) {
    val backStack = remember { androidx.compose.runtime.mutableStateListOf(startRoute) }
    val currentRoute = backStack.lastOrNull() ?: "main"
    
    fun navigateTo(route: String) {
        if (backStack.lastOrNull() != route) {
            backStack.add(route)
        }
    }
    
    fun navigateBack() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.size - 1)
        } else {
            onFinish()
        }
    }
    
    androidx.activity.compose.BackHandler {
        navigateBack()
    }
    
    Scaffold { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (currentRoute) {
                "main" -> MainSettingsScreen(
                    onNavigateToDict = { navigateTo("dict") },
                    onNavigateToReader = { navigateTo("reader") },
                    onNavigateToGeneral = { navigateTo("general") },
                    onNavigateToNetSpeed = { navigateTo("netspeed") },
                    onNavigateToData = { navigateTo("data") },
                    onNavigateToPages = { navigateTo("pages") },
                    onNavigateToHandles = { navigateTo("handles") },
                    onNavigateToCallRecorder = { navigateTo("call_recorder") },
                    onNavigateToScreenCap = { navigateTo("screencap") },
                    onNavigateToPermissions = { navigateTo("permissions") },
                    onNavigateToBrowser = { navigateTo("browser") },
                    onBack = { navigateBack() }
                )
                "reader" -> ReaderSettingsScreen(
                    onBack = { navigateBack() }
                )
                "general" -> SidebarSettingsScreen(
                    handleId = "sidebar",
                    onBack = { navigateBack() }
                )
                "netspeed" -> NetSpeedSettingsScreen(
                    onBack = { navigateBack() }
                )
                "data" -> DataSettingsScreen(
                    onBack = { navigateBack() }
                )
                "pages" -> PageManagementSettingsScreen(
                    onBack = { navigateBack() }
                )
                "handles" -> HandlesListSettingsScreen(
                    onNavigateToHandle = { navigateTo("handle_$it") },
                    onNavigateToSidebarSettings = { navigateTo("pages_$it") },
                    onBack = { navigateBack() }
                )
                "call_recorder" -> CallRecorderSettingsScreen(
                    onBack = { navigateBack() }
                )
                "screencap" -> ScreenCapSettingsScreen(
                    onBack = { navigateBack() }
                )
                "dict" -> DictionarySettingsScreen(
                    onBack = { navigateBack() }
                )
                "permissions" -> PermissionManagerScreen(
                    isFirstLaunch = false,
                    onContinue = { navigateBack() }
                )
                "browser" -> BrowserSettingsScreen(
                    onBack = { navigateBack() }
                )
            }
            if (currentRoute.startsWith("pages_")) {
                val remainder = currentRoute.removePrefix("pages_")
                val parts = remainder.split("|")
                val handleId = parts[0]
                var initAction: String? = null
                var initialEditPageId: String? = null
                if (parts.size > 1) {
                    if (parts[1].startsWith("edit_page:")) {
                        initialEditPageId = parts[1].removePrefix("edit_page:")
                    } else {
                        initAction = parts[1]
                    }
                }
                SidebarSettingsScreen(
                    handleId = handleId,
                    initAction = initAction,
                    initialEditPageId = initialEditPageId,
                    onBack = { navigateBack() }
                )
            } else if (currentRoute.startsWith("handle_")) {
                val handleId = currentRoute.removePrefix("handle_")
                HandleEditScreen(
                    handleId = handleId,
                    onBack = { navigateBack() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainSettingsScreen(onNavigateToReader: () -> Unit, onNavigateToGeneral: () -> Unit, onNavigateToNetSpeed: () -> Unit, onNavigateToData: () -> Unit, onNavigateToPages: () -> Unit, onNavigateToHandles: () -> Unit, onNavigateToCallRecorder: () -> Unit, onNavigateToScreenCap: () -> Unit, onNavigateToDict: () -> Unit, onNavigateToPermissions: () -> Unit,
    onNavigateToBrowser: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            }
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                ListItem(
                    headlineContent = { Text("Vian Permissions Manager") },
                    supportingContent = { Text("Manage and grant necessary permissions") },
                    modifier = Modifier.clickable { onNavigateToPermissions() }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("eBook Reader Settings") },
                    supportingContent = { Text("Theme, font size, gestures, backups, logs") },
                    modifier = Modifier.clickable { onNavigateToReader() }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Handles & Sidebar") },
                    supportingContent = { Text("Customize trigger handles & gestures") },
                    modifier = Modifier.clickable { onNavigateToHandles() }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Launcher Settings") },
                    supportingContent = { Text("Home screen, app drawer & desktop options") },
                    modifier = Modifier.clickable { 
                        Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()
                    }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Internet Speed Monitor") },
                    supportingContent = { Text("Toggle, units, and data usage statistics") },
                    modifier = Modifier.clickable { onNavigateToNetSpeed() }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Call Recorder Settings") },
                    supportingContent = { Text("Automatic call recording & privacy") },
                    modifier = Modifier.clickable { onNavigateToCallRecorder() }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Record") },
                    supportingContent = { Text("Screenshot and screen recording location") },
                    modifier = Modifier.clickable { onNavigateToScreenCap() }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Dictionary & Translations") },
                    supportingContent = { Text("Import and manage offline dictionaries") },
                    modifier = Modifier.clickable { onNavigateToDict() }
                )

                Divider()
                ListItem(
                    headlineContent = { Text("Browser Settings") },
                    supportingContent = { Text("Global settings for Floating Browser") },
                    modifier = Modifier.clickable { onNavigateToBrowser() }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Log Keeper") },
                    supportingContent = { Text("View system and crash logs") },
                    modifier = Modifier.clickable { 
                        val intent = Intent(context, LogKeeperActivity::class.java)
                        context.startActivity(intent)
                    }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("PWA Loader") },
                    supportingContent = { Text("Import and manage PWAs") },
                    modifier = Modifier.clickable { 
                        val intent = Intent().apply {
                            
                        }
                        android.widget.Toast.makeText(context, "Not Migrated", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Appywork Vibe Coding") },
                    supportingContent = { Text("Manage AI coding projects and GitHub auth") },
                    modifier = Modifier.clickable { 
                        val intent = Intent().apply {
                            
                        }
                        android.widget.Toast.makeText(context, "Not Migrated", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Data & Backup") },
                    supportingContent = { Text("Full app backup and restore") },
                    modifier = Modifier.clickable { onNavigateToData() }
                )
                Divider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE) }
    val coroutineScope = rememberCoroutineScope()
    
    var tapToTurn by remember { mutableStateOf(prefs.getBoolean("tap_to_turn", true)) }
    var keepScreenOn by remember { mutableStateOf(prefs.getBoolean("keep_screen_on", true)) }
    var enableBookmarks by remember { mutableStateOf(prefs.getBoolean("enable_bookmarks", false)) }
    var continuousSave by remember { mutableStateOf(prefs.getBoolean("continuous_save", false)) }
    var useScopedDir by remember { mutableStateOf(prefs.getBoolean("use_scoped_dir", false)) }
    var fontScale by remember { mutableStateOf(prefs.getFloat("font_size_scale", 1.0f)) }
    var useDarkTheme by remember { mutableStateOf(prefs.getBoolean("use_dark_theme", true)) }


    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            Toast.makeText(context, "Importing data...", Toast.LENGTH_SHORT).show()
            coroutineScope.launch {
                val res = kotlin.Result.failure<Unit>(Exception("Not Migrated"))
                if (res.isSuccess) {
                    Toast.makeText(context, "Import successful. Please restart the app.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Import failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val storageLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        val hasPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || android.os.Environment.isExternalStorageManager()
        useScopedDir = hasPerm
        prefs.edit().putBoolean("use_scoped_dir", hasPerm).apply()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("eBook Reader Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            }
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                ListItem(
                    headlineContent = { Text("Dark Theme") },
                    trailingContent = {
                        Switch(
                            checked = useDarkTheme,
                            onCheckedChange = { 
                                useDarkTheme = it
                                prefs.edit().putBoolean("use_dark_theme", it).apply()
                            }
                        )
                    }
                )

                Divider()
                ListItem(
                    headlineContent = { Text("Font Size Scale") },
                    supportingContent = {
                        Slider(
                            value = fontScale,
                            onValueChange = { 
                                fontScale = it
                                prefs.edit().putFloat("font_size_scale", it).apply()
                            },
                            valueRange = 0.5f..2.0f,
                            steps = 15
                        )
                    }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Tap to Turn") },
                    supportingContent = { Text("Tap edges of screen to turn pages") },
                    trailingContent = {
                        Switch(
                            checked = tapToTurn,
                            onCheckedChange = { 
                                tapToTurn = it
                                prefs.edit().putBoolean("tap_to_turn", it).apply()
                            }
                        )
                    }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Keep Screen On") },
                    supportingContent = { Text("Prevent screen from turning off while reading") },
                    trailingContent = {
                        Switch(
                            checked = keepScreenOn,
                            onCheckedChange = { 
                                keepScreenOn = it
                                prefs.edit().putBoolean("keep_screen_on", it).apply()
                            }
                        )
                    }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Enable Bookmarks") },
                    trailingContent = {
                        Switch(
                            checked = enableBookmarks,
                            onCheckedChange = { 
                                enableBookmarks = it
                                prefs.edit().putBoolean("enable_bookmarks", it).apply()
                                Toast.makeText(context, "Restart reader to apply", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Continuous Save") },
                    supportingContent = { Text("Save progress constantly (may impact performance)") },
                    trailingContent = {
                        Switch(
                            checked = continuousSave,
                            onCheckedChange = { 
                                continuousSave = it
                                prefs.edit().putBoolean("continuous_save", it).apply()
                            }
                        )
                    }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("File Explorer Mode") },
                    supportingContent = { Text("Browse all folders for books directly") },
                    trailingContent = {
                        Switch(
                            checked = useScopedDir,
                            onCheckedChange = { checked -> 
                                if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
                                    try {
                                        val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                            data = Uri.parse("package:" + context.packageName)
                                        }
                                        storageLauncher.launch(intent)
                                    } catch (e: Exception) {
                                        val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                        storageLauncher.launch(intent)
                                    }
                                } else {
                                    useScopedDir = checked
                                    prefs.edit().putBoolean("use_scoped_dir", checked).apply()
                                }
                            }
                        )
                    }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Backup Reader Data") },
                    supportingContent = { Text("Database and settings, no books") },
                    modifier = Modifier.clickable {
                        Toast.makeText(context, "Backing up reader data...", Toast.LENGTH_SHORT).show()
                        coroutineScope.launch {
                            val res = kotlin.Result.failure<String>(Exception("Not Migrated"))
                            if (res.isSuccess) {
                                Toast.makeText(context, "Backup saved: ${java.io.File(res.getOrNull() ?: "").name}", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Backup failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Backup Reader Data (With Books)") },
                    supportingContent = { Text("Database, settings, and downloaded books") },
                    modifier = Modifier.clickable {
                        Toast.makeText(context, "Backing up reader data with books...", Toast.LENGTH_SHORT).show()
                        coroutineScope.launch {
                            val res = kotlin.Result.failure<String>(Exception("Not Migrated"))
                            if (res.isSuccess) {
                                Toast.makeText(context, "Backup saved: ${java.io.File(res.getOrNull() ?: "").name}", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Backup failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Import Reader Backup") },
                    modifier = Modifier.clickable {
                        importLauncher.launch("application/zip")
                    }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Export Logs") },
                    modifier = Modifier.clickable {
                        try {
                            val f = AppLogger.export(context)
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", f)
                            val i = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(i, "Export Logs").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                        } catch (e: Exception) {
                            Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                            AppLogger.export(context)
                        }
                    }
                )
            }
        }
    }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            Toast.makeText(context, "Importing full app data...", Toast.LENGTH_SHORT).show()
            coroutineScope.launch {
                val res = kotlin.Result.failure<Unit>(Exception("Not Migrated"))
                if (res.isSuccess) {
                    Toast.makeText(context, "Import successful. Please restart the app.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Import failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Data & Backup") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            }
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                ListItem(
                    headlineContent = { Text("Reset Data Tracking") },
                    supportingContent = { Text("Reset today's tracked data usage to 0 MB. Use this if the tracking spikes due to an Android error.") },
                    modifier = Modifier.clickable {
                        context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE).edit()
                            .putLong("daily_mobile_rx", 0L)
                            .putLong("daily_mobile_tx", 0L)
                            .putLong("daily_wifi_rx", 0L)
                            .putLong("daily_wifi_tx", 0L)
                            .apply()
                        Toast.makeText(context, "Data usage reset to 0", Toast.LENGTH_SHORT).show()
                    }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Backup Full App Data") },
                    supportingContent = { Text("Includes everything: reader data, sidebar structure, and all settings.") },
                    modifier = Modifier.clickable {
                        Toast.makeText(context, "Backing up full app data...", Toast.LENGTH_SHORT).show()
                        coroutineScope.launch {
                            val res = kotlin.Result.failure<String>(Exception("Not Migrated"))
                            if (res.isSuccess) {
                                Toast.makeText(context, "Backup saved: ${java.io.File(res.getOrNull() ?: "").name}", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Backup failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Import Full App Data") },
                    supportingContent = { Text("Restore a previously backed-up full app data zip.") },
                    modifier = Modifier.clickable {
                        importLauncher.launch("application/zip")
                    }
                )
                Divider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenCapSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ScreenCapPrefs", Context.MODE_PRIVATE) }
    
    var saveLocation by remember { mutableStateOf(prefs.getString("save_location", "Default (Pictures/Screenshots)") ?: "Default (Pictures/Screenshots)") }
    var delaySeconds by remember { mutableStateOf(prefs.getInt("screenshot_delay", 0)) }
    var recordQuality by remember { mutableStateOf(prefs.getInt("record_quality", 720)) }
    var recordAudio by remember { mutableStateOf(prefs.getBoolean("record_audio", false)) }

    val dirLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val path = uri.toString()
            prefs.edit().putString("save_location", path).apply()
            saveLocation = path
            Toast.makeText(context, "Location saved", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Screen Cap Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            }
        )
        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            item {
                Text(
                    text = "Save Location (Screenshot & Video)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = saveLocation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { dirLauncher.launch(null) }) {
                        Text("Change Location")
                    }
                    Button(onClick = { 
                        prefs.edit().putString("save_location", "Default (Pictures/Screenshots)").apply()
                        saveLocation = "Default (Pictures/Screenshots)"
                    }, colors = ButtonDefaults.outlinedButtonColors()) {
                        Text("Reset")
                    }
                }
                
                Divider(modifier = Modifier.padding(vertical = 24.dp))
                
                Text(
                    text = "Screenshot Settings",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "Delay before capturing screen: ${delaySeconds}s",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Slider(
                    value = delaySeconds.toFloat(),
                    onValueChange = { 
                        delaySeconds = it.toInt()
                        prefs.edit().putInt("screenshot_delay", delaySeconds).apply()
                    },
                    valueRange = 0f..10f,
                    steps = 9
                )
                
                Divider(modifier = Modifier.padding(vertical = 24.dp))
                
                Text(
                    text = "Screen Record Settings",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Record Audio (Microphone)")
                    Switch(checked = recordAudio, onCheckedChange = {
                        recordAudio = it
                        prefs.edit().putBoolean("record_audio", it).apply()
                    })
                }
                Text("Video Quality")
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = recordQuality == 720, onClick = { 
                            recordQuality = 720
                            prefs.edit().putInt("record_quality", 720).apply()
                        })
                        Text("720p")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = recordQuality == 1080, onClick = { 
                            recordQuality = 1080
                            prefs.edit().putInt("record_quality", 1080).apply()
                        })
                        Text("1080p")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = recordQuality == 0, onClick = { 
                            recordQuality = 0 // Original
                            prefs.edit().putInt("record_quality", 0).apply()
                        })
                        Text("Original")
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 24.dp))

                val ocrManager = remember { com.example.feature.system_hub.OcrModuleManager(context) }
                val ocrStatus by ocrManager.status.collectAsState()

                LaunchedEffect(Unit) {
                    ocrManager.checkStatus()
                }

                Text(
                    text = "OCR (Text Recognition) Feature",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "Extract text from cropped screenshots and camera scanner offline via Google ML Kit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "OCR Model Status",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                when (val status = ocrStatus) {
                                    is com.example.feature.system_hub.OcrModuleStatus.Installed -> {
                                        Text(
                                            text = "Installed & Ready",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    is com.example.feature.system_hub.OcrModuleStatus.Downloading -> {
                                        val percent = (status.progress * 100).toInt()
                                        Text(
                                            text = "Downloading: $percent%",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                    is com.example.feature.system_hub.OcrModuleStatus.Checking -> {
                                        Text(
                                            text = "Checking status...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    is com.example.feature.system_hub.OcrModuleStatus.Error -> {
                                        Text(
                                            text = "Error: ${status.message}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    else -> {
                                        Text(
                                            text = "Not Downloaded (On-demand)",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            when (val status = ocrStatus) {
                                is com.example.feature.system_hub.OcrModuleStatus.Installed -> {
                                    OutlinedButton(
                                        onClick = { ocrManager.checkStatus() }
                                    ) {
                                        Text("Installed")
                                    }
                                }
                                is com.example.feature.system_hub.OcrModuleStatus.Downloading -> {
                                    CircularProgressIndicator(
                                        progress = { status.progress },
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                is com.example.feature.system_hub.OcrModuleStatus.Checking -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                                else -> {
                                    Button(
                                        onClick = {
                                            ocrManager.downloadModule { success, err ->
                                                if (success) {
                                                    Toast.makeText(context, "OCR model installed successfully!", Toast.LENGTH_SHORT).show()
                                                } else if (err != null) {
                                                    Toast.makeText(context, "OCR download failed: $err", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    ) {
                                        Text("Download")
                                    }
                                }
                            }
                        }

                        if (ocrStatus is com.example.feature.system_hub.OcrModuleStatus.Downloading) {
                            val downloading = ocrStatus as com.example.feature.system_hub.OcrModuleStatus.Downloading
                            Spacer(modifier = Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { downloading.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}



@androidx.compose.runtime.Composable fun DictionarySettingsScreen(onBack: () -> Unit) {}
@androidx.compose.runtime.Composable fun WelcomeScreen(onContinue: () -> Unit) {}
@androidx.compose.runtime.Composable fun BrowserSettingsScreen(onBack: () -> Unit) {}
