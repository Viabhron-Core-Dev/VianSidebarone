package com.example

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppTrackerSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("MODE") ?: ""
        val isForceStopOnly = mode == "force_stop_only"
        val containerId = intent.getStringExtra("CONTAINER_ID") ?: "sidebar"
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppTrackerSettingsScreen(
                    onBack = { finish() },
                    isForceStopOnly = isForceStopOnly,
                    containerId = containerId
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTrackerSettingsScreen(
    onBack: () -> Unit,
    isForceStopOnly: Boolean = false,
    containerId: String = "sidebar"
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
    
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = if (isForceStopOnly) {
        listOf("Running", "Permissions")
    } else {
        listOf("Running", "Cache", "Execute", "All Apps")
    }
    
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(if (isForceStopOnly) "Force Stop Apps Setup" else "App Tracker Edit") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    edgePadding = 8.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab])
                        )
                    }
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (isForceStopOnly) {
                when (selectedTab) {
                    0 -> WhitelistTab(context, prefs, "running", containerId = containerId, isForceStopOnly = true)
                    1 -> ExecutePermsTab(context, prefs, isForceStopOnly = true, containerId = containerId)
                }
            } else {
                when (selectedTab) {
                    0 -> WhitelistTab(context, prefs, "running", containerId = containerId, isForceStopOnly = false)
                    1 -> WhitelistTab(context, prefs, "cache", containerId = containerId, isForceStopOnly = false)
                    2 -> ExecutePermsTab(context, prefs, isForceStopOnly = false, containerId = containerId)
                    3 -> AllAppsTab(context)
                }
            }
        }
    }
}

@Composable
fun WhitelistTab(
    context: Context,
    prefs: android.content.SharedPreferences,
    type: String,
    containerId: String = "sidebar",
    isForceStopOnly: Boolean = false
) {
    var showSystemApps by remember { mutableStateOf(prefs.getBoolean("app_tracker_show_system_$type", false)) }
    val prefKey = if (type == "running") com.example.utils.AppTrackerHelper.getContainerWhitelistKey(containerId) else "app_tracker_whitelist_cache"
    val initialWhitelist = if (isForceStopOnly) {
        com.example.utils.AppTrackerHelper.getForceStopWhitelist(context, containerId)
    } else if (type == "running") {
        com.example.utils.AppTrackerHelper.getWhitelist(context, containerId)
    } else {
        prefs.getStringSet(prefKey, emptySet()) ?: emptySet()
    }
    var whitelist by remember { mutableStateOf(initialWhitelist) }
    
    var apps by remember { mutableStateOf<List<com.example.feature.sidebar.TrackedAppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    
    fun saveWhitelist(set: Set<String>) {
        whitelist = set
        if (isForceStopOnly) {
            com.example.utils.AppTrackerHelper.saveForceStopWhitelist(context, containerId, set)
        } else if (type == "running") {
            com.example.utils.AppTrackerHelper.saveWhitelist(context, containerId, set)
        } else {
            prefs.edit().putStringSet(prefKey, set).commit()
            com.example.core.OverlaySyncManager.syncStringSet(context, prefKey, set)
        }
    }
    
    LaunchedEffect(type) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val list = mutableListOf<com.example.feature.sidebar.TrackedAppInfo>()
            if (type == "running") {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                if (usm != null) {
                    val endTime = System.currentTimeMillis()
                    val startTime = endTime - (1000L * 60 * 60 * 24 * 7)
                    val usageStats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, startTime, endTime)
                    val aggregated = mutableMapOf<String, UsageStats>()
                    for (stat in usageStats) {
                        val existing = aggregated[stat.packageName]
                        if (existing == null || stat.lastTimeUsed > existing.lastTimeUsed) {
                            aggregated[stat.packageName] = stat
                        }
                    }
                    for ((pkgName, stat) in aggregated) {
                        if (stat.lastTimeUsed <= 0 || pkgName == context.packageName) continue
                        try {
                            val appInfo = pm.getApplicationInfo(pkgName, 0)
                            if ((appInfo.flags and ApplicationInfo.FLAG_STOPPED) != 0) continue
                            val label = appInfo.loadLabel(pm).toString()
                            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                            list.add(com.example.feature.sidebar.TrackedAppInfo(pkgName, label, isSystem = isSystem))
                        } catch (e: Exception) {}
                    }
                }
            } else {
                val packages = pm.getInstalledPackages(0)
                for (pkg in packages) {
                    val appInfo = pkg.applicationInfo ?: continue
                    val label = appInfo.loadLabel(pm).toString()
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    list.add(com.example.feature.sidebar.TrackedAppInfo(pkg.packageName, label, isSystem = isSystem))
                }
            }
            list.sortBy { it.appName.lowercase() }
            apps = list
        }
        isLoading = false
    }
    
    val filteredApps = remember(apps, showSystemApps) {
        if (showSystemApps) apps.filter { it.isSystem } else apps.filter { !it.isSystem }
    }
    
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { 
                    showSystemApps = !showSystemApps
                    prefs.edit().putBoolean("app_tracker_show_system_$type", showSystemApps).apply()
                },
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
            ) {
                Text(if (showSystemApps) "SYSTEM ONLY" else "USER ONLY", fontSize = 11.sp)
            }
            Button(
                onClick = { 
                    val newWhitelist = filteredApps.map { it.packageName }.toSet()
                    saveWhitelist(newWhitelist)
                },
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
            ) {
                Text("SELECT ALL", fontSize = 11.sp)
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { saveWhitelist(emptySet()) },
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
            ) {
                Text("DESELECT ALL", fontSize = 11.sp)
            }
            Button(
                onClick = {
                    val current = whitelist.toMutableSet()
                    filteredApps.forEach { 
                        if (current.contains(it.packageName)) current.remove(it.packageName)
                        else current.add(it.packageName)
                    }
                    saveWhitelist(current)
                },
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
            ) {
                Text("INVERT", fontSize = 11.sp)
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "${filteredApps.size} ${if (showSystemApps) "System & User" else "User"} App(s)",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)
        )
        
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val isSelected = whitelist.contains(app.packageName)
                    var bitmapState by remember(app.packageName) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                    
                    LaunchedEffect(app.packageName) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                val pm = context.packageManager
                                val icon = pm.getApplicationIcon(app.packageName)
                                val bitmap = icon.toBitmap(
                                    width = if (icon.intrinsicWidth > 0) icon.intrinsicWidth else 96,
                                    height = if (icon.intrinsicHeight > 0) icon.intrinsicHeight else 96
                                ).asImageBitmap()
                                bitmapState = bitmap
                            } catch (e: Exception) {}
                        }
                    }
                    val bgColor = if (isSelected) Color.White else Color(0xFF1E1E1E)
                    val textColor = if (isSelected) Color.Black else Color.White
                    
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(bgColor)
                            .clickable { 
                                val newSet = whitelist.toMutableSet()
                                if (isSelected) newSet.remove(app.packageName) else newSet.add(app.packageName)
                                saveWhitelist(newSet)
                            }
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val currentBitmap = bitmapState
                        if (currentBitmap != null) {
                            Image(bitmap = currentBitmap, contentDescription = app.appName, modifier = Modifier.fillMaxSize())
                        } else {
                            Text(app.appName.take(1).uppercase(), color = textColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExecutePermsTab(
    context: Context,
    prefs: android.content.SharedPreferences,
    isForceStopOnly: Boolean = false,
    containerId: String = "sidebar"
) {
    var autoForceStop by remember { mutableStateOf(prefs.getBoolean("app_tracker_auto_force_stop", false)) }
    val hasUsageAccess = remember { com.example.utils.AppTrackerHelper.checkUsageStatsPermission(context) }
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (isForceStopOnly) {
            Text("Sequential Force Stop Loop", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "When triggered, Force Stop Apps sequentially opens each active app's settings. You tap 'Force stop' and return to continue to the next app. Whitelisted apps are automatically preserved.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.LightGray
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    com.example.utils.AppTrackerHelper.startForceStopSequence(context, containerId)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Test Force Stop Sequence")
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        } else {
            Text("Execute / Automation", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto Force Stop")
                    Text("Uses Accessibility Service to click 'Force stop' and 'OK'.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Switch(
                    checked = autoForceStop,
                    onCheckedChange = { 
                        autoForceStop = it
                        prefs.edit().putBoolean("app_tracker_auto_force_stop", it).commit()
                        com.example.core.OverlaySyncManager.syncBoolean(context, "app_tracker_auto_force_stop", it)
                    }
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        }
        Text("Permissions", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            if (hasUsageAccess) "Usage Access: Granted ✓" else "Usage Access: Required to detect active running apps",
            style = MaterialTheme.typography.bodySmall,
            color = if (hasUsageAccess) Color(0xFF00E676) else Color(0xFFFFB74D)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = {
                try {
                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    context.startActivity(intent)
                } catch (e: Exception) {}
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (hasUsageAccess) "Manage Usage Access Settings" else "Grant Usage Access")
        }
        if (!isForceStopOnly) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        context.startActivity(intent)
                    } catch (e: Exception) {}
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Accessibility Settings")
            }
        }
    }
}

@Composable
fun AllAppsTab(context: Context) {
    var allApps by remember { mutableStateOf<List<com.example.feature.sidebar.TrackedAppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(0)
            val list = mutableListOf<com.example.feature.sidebar.TrackedAppInfo>()
            for (pkg in packages) {
                val appInfo = pkg.applicationInfo ?: continue
                val label = appInfo.loadLabel(pm).toString()
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                list.add(com.example.feature.sidebar.TrackedAppInfo(pkg.packageName, label))
            }
            allApps = list.sortedBy { it.appName.lowercase() }
        }
        isLoading = false
    }
    
    val filteredApps = remember(allApps, showSystemApps, searchQuery) {
        allApps.filter { app ->
            if (searchQuery.isNotBlank()) {
                app.appName.contains(searchQuery, ignoreCase = true) ||
                app.packageName.contains(searchQuery, ignoreCase = true)
            } else true
        }
    }
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Show System Apps")
            Switch(checked = showSystemApps, onCheckedChange = { showSystemApps = it })
        }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search Apps") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filteredApps, key = { it.packageName }) { app ->
                    ListItem(
                        headlineContent = { Text(app.appName) },
                        supportingContent = { Text(app.packageName) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
