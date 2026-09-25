package com.example.feature.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.app.usage.NetworkStatsManager
import android.app.usage.NetworkStats
import android.net.NetworkCapabilities
import android.os.RemoteException
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import android.graphics.drawable.Drawable
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.util.Calendar
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.sp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.os.Build

import java.util.Locale

data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val usageBytes: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetSpeedSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE) }
    
    var speedIndicatorEnabled by remember { 
        mutableStateOf(
            prefs.getBoolean("net_speed_enabled", 
                prefs.getBoolean("netspeed_enabled", 
                    prefs.getBoolean("speed_indicator_enabled", true)
                )
            )
        ) 
    }

    val networkStatsManager = remember { context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager }
    val packageManager = context.packageManager
    
    var hasPermission by remember { 
        mutableStateOf(hasUsageStatsPermission(context)) 
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    var usageData by remember { mutableStateOf<List<AppUsageInfo>>(emptyList()) }
    var timePeriod by remember { mutableStateOf("Daily") } // Daily, Weekly, Monthly
    var searchQuery by remember { mutableStateOf(TextFieldValue("")) }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val loadData = {
        if (hasPermission) {
            isLoading = true
            coroutineScope.launch(Dispatchers.IO) {
                val data = fetchUsageData(context, networkStatsManager, packageManager, timePeriod)
                withContext(Dispatchers.Main) {
                    usageData = data
                    isLoading = false
                }
            }
        }
    }

    LaunchedEffect(hasPermission, timePeriod) {
        loadData()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Internet Speed Monitor") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            }
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                ListItem(
                    headlineContent = { Text("Enable Internet Speed Monitor") },
                    supportingContent = { Text("Displays real-time upload/download speeds in the status bar") },
                    trailingContent = {
                        Switch(
                            checked = speedIndicatorEnabled,
                            onCheckedChange = { enabled ->
                                speedIndicatorEnabled = enabled
                                prefs.edit()
                                    .putBoolean("net_speed_enabled", enabled)
                                    .putBoolean("netspeed_enabled", enabled)
                                    .putBoolean("speed_indicator_enabled", enabled)
                                    .commit()
                                com.example.core.OverlaySyncManager.syncBoolean(context, "net_speed_enabled", enabled)
                                com.example.core.OverlaySyncManager.syncBoolean(context, "netspeed_enabled", enabled)
                                com.example.core.OverlaySyncManager.syncBoolean(context, "speed_indicator_enabled", enabled)
                                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                            }
                        )
                    }
                )
                Divider()

                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "App Data Usage",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp, 8.dp)
                )

                if (!hasPermission) {
                    ListItem(
                        headlineContent = { Text("Usage Access Required") },
                        supportingContent = { Text("Tap to grant permission to view app data usage") },
                        modifier = Modifier.clickable {
                            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf("Daily", "Weekly", "Monthly").forEach { period ->
                            FilterChip(
                                selected = timePeriod == period,
                                onClick = { timePeriod = period },
                                label = { Text(period) },
                                leadingIcon = if (timePeriod == period) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                } else null
                            )
                        }
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Search apps") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                        singleLine = true
                    )
                }
            }

            if (isLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (hasPermission) {
                val filteredData = usageData.filter {
                    it.appName.contains(searchQuery.text, ignoreCase = true) || 
                    it.packageName.contains(searchQuery.text, ignoreCase = true)
                }
                
                items(filteredData) { info ->
                    ListItem(
                        headlineContent = { Text(info.appName) },
                        supportingContent = { Text(formatDataUsage(info.usageBytes)) },
                        leadingContent = {
                            if (info.icon != null) {
                                Image(
                                    bitmap = info.icon.toBitmap(96, 96).asImageBitmap(),
                                    contentDescription = info.appName,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
    val mode = appOps.checkOpNoThrow(
        android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        context.packageName
    )
    if (mode == android.app.AppOpsManager.MODE_DEFAULT) {
        return context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
    }
    return mode == android.app.AppOpsManager.MODE_ALLOWED
}

fun formatDataUsage(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb > 1024) String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0) else String.format(Locale.getDefault(), "%.1f MB", mb)
}

fun fetchUsageData(context: Context, manager: NetworkStatsManager, pm: PackageManager, period: String): List<AppUsageInfo> {
    val uidMap = mutableMapOf<Int, Long>()
    
    val cal = Calendar.getInstance()
    val end = cal.timeInMillis
    when (period) {
        "Daily" -> {
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.clear(Calendar.MINUTE)
            cal.clear(Calendar.SECOND)
            cal.clear(Calendar.MILLISECOND)
        }
        "Weekly" -> cal.add(Calendar.DAY_OF_YEAR, -7)
        "Monthly" -> cal.add(Calendar.MONTH, -1)
    }
    val start = cal.timeInMillis

    try {
        val wifiStats = manager.querySummary(NetworkCapabilities.TRANSPORT_WIFI, null, start, end)
        val bucket = NetworkStats.Bucket()
        while (wifiStats.hasNextBucket()) {
            wifiStats.getNextBucket(bucket)
            uidMap[bucket.uid] = uidMap.getOrDefault(bucket.uid, 0L) + bucket.rxBytes + bucket.txBytes
        }
        wifiStats.close()
        
        val mobileStats = manager.querySummary(NetworkCapabilities.TRANSPORT_CELLULAR, null, start, end)
        while (mobileStats.hasNextBucket()) {
            mobileStats.getNextBucket(bucket)
            uidMap[bucket.uid] = uidMap.getOrDefault(bucket.uid, 0L) + bucket.rxBytes + bucket.txBytes
        }
        mobileStats.close()
    } catch (e: Exception) {
        e.printStackTrace()
    }

    val results = mutableListOf<AppUsageInfo>()
    for ((uid, bytes) in uidMap) {
        if (bytes == 0L) continue
        
        val packages = pm.getPackagesForUid(uid)
        if (packages != null && packages.isNotEmpty()) {
            val packageName = packages[0]
            try {
                val pInfo = pm.getApplicationInfo(packageName, 0)
                val appName = pm.getApplicationLabel(pInfo).toString()
                val icon = pm.getApplicationIcon(pInfo)
                results.add(AppUsageInfo(packageName, appName, icon, bytes))
            } catch (e: PackageManager.NameNotFoundException) {
            }
        }
    }
    
    return results.sortedByDescending { it.usageBytes }
}
