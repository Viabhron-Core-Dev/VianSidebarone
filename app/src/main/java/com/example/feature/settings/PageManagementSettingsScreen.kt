package com.example.feature.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.example.R
import com.example.utils.PageManager
import com.example.utils.SidebarPage
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageManagementSettingsScreen(handleId: String = "sidebar", onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE) }
    
    var pages by remember { mutableStateOf(PageManager.getPages(prefs, handleId)) }
    var defaultIndex by remember { mutableStateOf(PageManager.getDefaultPageIndex(prefs, handleId)) }
    
    var showAddDialog by remember { mutableStateOf(false) }

    DisposableEffect(context, handleId) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: android.content.Intent?) {
                if (intent?.action == "WIDGET_PAGE_CREATED") {
                    val widgetId = intent.getIntExtra("WIDGET_ID", -1)
                    if (widgetId != -1) {
                        val newPages = pages.toMutableList()
                        val title = "App Widget"
                        val page = SidebarPage.createDefault(id = "widget:$widgetId", type = "widget", title = title)
                        newPages.add(page)
                        pages = newPages
                        PageManager.savePages(prefs, handleId, pages)
                    }
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, android.content.IntentFilter("WIDGET_PAGE_CREATED"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, android.content.IntentFilter("WIDGET_PAGE_CREATED"))
        }
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
    
    fun savePages() {
        PageManager.savePages(prefs, handleId, pages, context)
        PageManager.saveDefaultPageIndex(prefs, handleId, defaultIndex, context)
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Page Management") },
                navigationIcon = {
                    IconButton(onClick = {
                        savePages()
                        onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, "Add Page")
            }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            itemsIndexed(pages) { index, page ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = page.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    supportingContent = {
                        Text(
                            text = page.type.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingContent = {
                        RadioButton(
                            selected = index == defaultIndex,
                            onClick = {
                                defaultIndex = index
                                savePages()
                            }
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    if (index > 1) { // 1 instead of 0 to protect index 0
                                        val newPages = pages.toMutableList()
                                        val temp = newPages[index]
                                        newPages[index] = newPages[index - 1]
                                        newPages[index - 1] = temp
                                        if (defaultIndex == index) defaultIndex = index - 1
                                        else if (defaultIndex == index - 1) defaultIndex = index
                                        pages = newPages
                                        savePages()
                                    }
                                },
                                enabled = index > 1,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(painterResource(R.drawable.ic_arrow_upward), "Move Up", modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = {
                                    if (index > 0 && index < pages.size - 1) {
                                        val newPages = pages.toMutableList()
                                        val temp = newPages[index]
                                        newPages[index] = newPages[index + 1]
                                        newPages[index + 1] = temp
                                        if (defaultIndex == index) defaultIndex = index + 1
                                        else if (defaultIndex == index + 1) defaultIndex = index
                                        pages = newPages
                                        savePages()
                                    }
                                },
                                enabled = index > 0 && index < pages.size - 1,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(painterResource(R.drawable.ic_arrow_downward), "Move Down", modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = {
                                    if (index > 0 && pages.size > 1) {
                                        val newPages = pages.toMutableList()
                                        newPages.removeAt(index)
                                        if (defaultIndex == index) defaultIndex = 0
                                        else if (defaultIndex > index) defaultIndex--
                                        pages = newPages
                                        savePages()
                                    }
                                },
                                enabled = index > 0 && pages.size > 1,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                )
                Divider()
            }
        }
        
        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("Add Sidebar Page") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        val types = listOf(
                            "apps" to "Apps Grid",
                            "scheduler" to "Short Reminders",
                            "calculator" to "Calculator",
                            "media_player" to "Media Player",
                            "compass" to "Compass",
                            "notifications" to "Notifications",
                            "widgets_grid" to "Widgets Grid",
                            "hybrid_grid" to "Hybrid",
                            "app_tracker" to "App Tracker",
                            "resources_tracker" to "Resources Tracker",
                            "pwa_loader" to "PWA Loader"
                        )
                        types.forEach { (type, title) ->
                            TextButton(onClick = {
                                val newPages = pages.toMutableList()
                                newPages.add(SidebarPage.createDefault(id = UUID.randomUUID().toString(), type = type, title = title))
                                pages = newPages
                                savePages()
                                showAddDialog = false
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(title, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAddDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
