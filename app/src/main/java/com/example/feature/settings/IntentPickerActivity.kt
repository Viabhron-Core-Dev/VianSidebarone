package com.example.feature.settings

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import java.net.URLEncoder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IntentPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    IntentPickerScreen(
                        onIntentSelected = { label, uri ->
                            val encodedLabel = URLEncoder.encode(label, "UTF-8")
                            val encodedUri = URLEncoder.encode(uri, "UTF-8")
                            val id = "intent:$encodedLabel:$encodedUri"
                            val resultIntent = Intent().apply {
                                putExtra("ELEMENT_ID", id)
                            }
                            setResult(RESULT_OK, resultIntent)
                            finish()
                        },
                        onCancel = {
                            setResult(RESULT_CANCELED)
                            finish()
                        }
                    )
                }
            }
        }
    }
}

data class SavedIntent(val label: String, val uri: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntentPickerScreen(onIntentSelected: (String, String) -> Unit, onCancel: () -> Unit) {
    var showScanAll by remember { mutableStateOf(false) }
    var showAddCustom by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)

    val savedIntents = remember {
        val list = mutableListOf<SavedIntent>()
        val count = prefs.getInt("saved_intents_count", 0)
        for (i in 0 until count) {
            val label = prefs.getString("saved_intent_label_$i", "") ?: ""
            val uri = prefs.getString("saved_intent_uri_$i", "") ?: ""
            if (label.isNotEmpty() && uri.isNotEmpty()) {
                list.add(SavedIntent(label, uri))
            }
        }
        list
    }

    if (showScanAll) {
        ScanAllIntentsScreen(onIntentSelected, onBack = { showScanAll = false })
        return
    }

    if (showAddCustom) {
        AddCustomIntentScreen(
            onSave = { label, uri ->
                val newCount = savedIntents.size + 1
                prefs.edit().apply {
                    putInt("saved_intents_count", newCount)
                    putString("saved_intent_label_${newCount - 1}", label)
                    putString("saved_intent_uri_${newCount - 1}", uri)
                }.apply()
                onIntentSelected(label, uri)
            },
            onCancel = { showAddCustom = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select Action") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            item {
                Text("Options", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 8.dp))
                ListItem(
                    headlineContent = { Text("Scan all activities") },
                    supportingContent = { Text("Find any activity on your device") },
                    modifier = Modifier.clickable { showScanAll = true }
                )
                ListItem(
                    headlineContent = { Text("Add custom intent") },
                    supportingContent = { Text("Manually enter a URI") },
                    modifier = Modifier.clickable { showAddCustom = true }
                )
                HorizontalDivider()
            }

            if (savedIntents.isNotEmpty()) {
                item {
                    Text("Saved / Added Intents", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 8.dp))
                }
                items(savedIntents) { item ->
                    ListItem(
                        headlineContent = { Text(item.label) },
                        supportingContent = { Text(item.uri, maxLines = 1) },
                        modifier = Modifier.clickable { onIntentSelected(item.label, item.uri) }
                    )
                }
                item { HorizontalDivider() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCustomIntentScreen(onSave: (String, String) -> Unit, onCancel: () -> Unit) {
    var label by remember { mutableStateOf("") }
    var uri by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Custom Intent") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.ArrowBack, "Cancel")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { onSave(label, uri) },
                        enabled = label.isNotBlank() && uri.isNotBlank()
                    ) {
                        Text("Save")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label (e.g. Open Camera)") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )
            OutlinedTextField(
                value = uri,
                onValueChange = { uri = it },
                label = { Text("Intent URI") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )
            Text("Hint: You can use an explicit app intent or a deep link like https://...", style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color.Gray)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanAllIntentsScreen(onIntentSelected: (String, String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var activities by remember { mutableStateOf<List<ResolveInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as android.content.pm.LauncherApps
            val list = mutableListOf<ResolveInfo>()
            try {
                val apps = launcherApps.getActivityList(null, android.os.Process.myUserHandle())
                for (app in apps) {
                    val intent = Intent(Intent.ACTION_MAIN)
                    intent.setClassName(app.applicationInfo.packageName, app.componentName.className)
                    val resolveInfos = pm.queryIntentActivities(intent, 0)
                    list.addAll(resolveInfos)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            withContext(Dispatchers.Main) {
                activities = list
                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("All Activities") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search apps or activities") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                singleLine = true
            )

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val filtered = remember(searchQuery, activities) {
                    val pm = context.packageManager
                    val query = searchQuery.lowercase()
                    if (query.isEmpty()) {
                        activities
                    } else {
                        activities.filter {
                            val label = it.loadLabel(pm).toString().lowercase()
                            val name = it.activityInfo.name.lowercase()
                            label.contains(query) || name.contains(query)
                        }
                    }
                }

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filtered) { info ->
                        val pm = context.packageManager
                        val label = info.loadLabel(pm).toString()
                        val intent = Intent()
                        intent.setClassName(info.activityInfo.packageName, info.activityInfo.name)
                        
                        ListItem(
                            headlineContent = { Text(label) },
                            supportingContent = { Text(info.activityInfo.name, maxLines = 1) },
                            modifier = Modifier.clickable {
                                onIntentSelected(label, intent.toUri(0))
                            }
                        )
                    }
                }
            }
        }
    }
}
