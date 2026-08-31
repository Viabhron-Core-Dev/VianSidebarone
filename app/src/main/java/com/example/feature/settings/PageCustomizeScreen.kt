package com.example.feature.settings

import androidx.compose.foundation.layout.*
import android.content.Intent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.utils.SidebarPage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageCustomizeScreen(
    page: SidebarPage,
    handleId: String,
    onSave: (SidebarPage) -> Unit,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val maxScreenWidth = configuration.screenWidthDp.toFloat()
    val maxScreenHeight = configuration.screenHeightDp.toFloat()
    
    var useCustomSettings by remember { mutableStateOf(page.useCustomSettings) }
    var width by remember { mutableStateOf(page.width) }
    var height by remember { mutableStateOf(page.height) }
    var wrapContentHeight by remember { mutableStateOf(page.wrapContentHeight) }
    var transparency by remember { mutableStateOf(page.transparency) }
    var title by remember { mutableStateOf(page.title) }
    var gridColumns by remember { mutableStateOf(if (page.type == "widgets_grid") context.getSharedPreferences("FloatingReaderPrefs", android.content.Context.MODE_PRIVATE).getInt("widgets_grid_cols_${page.id}", 4) else page.gridColumns) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Customize: ${page.title}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = {
                val updatedPage = page.copy(
                    title = title,
                    useCustomSettings = useCustomSettings,
                    width = width,
                    height = height,
                    wrapContentHeight = wrapContentHeight,
                    transparency = transparency,
                    gridColumns = gridColumns
                )
                if (page.type == "widgets_grid") {
                    val p = context.getSharedPreferences("FloatingReaderPrefs", android.content.Context.MODE_PRIVATE)
                    p.edit().putInt("widgets_grid_cols_${page.id}", gridColumns).commit()
                    com.example.core.OverlaySyncManager.syncInt(context, "widgets_grid_cols_${page.id}", gridColumns)
                }
                onSave(updatedPage)
                onBack()
            }) {
                Text("Save")
            }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {

            item {
                if (page.type == "apps" || page.type == "widgets_grid" || page.type == "hybrid_grid" || page.type == "app_tracker" || page.type == "notification" || page.type == "notifications") {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val targetClass = when (page.type) {
                                "apps" -> com.example.SidebarEditActivity::class.java
                                "widgets_grid" -> com.example.WidgetsGridEditActivity::class.java
                                "hybrid_grid" -> com.example.HybridGridEditActivity::class.java
                                "app_tracker" -> com.example.AppTrackerSettingsActivity::class.java
                                "notification", "notifications" -> com.example.NotificationHistoryActivity::class.java
                                else -> null
                            }
                            if (targetClass != null) {
                                val intent = Intent(context, targetClass).apply {
                                    if (page.type == "apps" || page.type == "widgets_grid" || page.type == "hybrid_grid") {
                                        putExtra("PAGE_ID", page.id)
                                        putExtra("HANDLE_ID", handleId)
                                        putExtra("CONTAINER_ID", handleId)
                                    }
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        Text(
                            when (page.type) {
                                "apps" -> "EDIT APPS"
                                "hybrid_grid" -> "EDIT GRID"
                                "widgets_grid" -> "EDIT WIDGETS"
                                "app_tracker" -> "VIEW APP TRACKER"
                                "notification", "notifications" -> "NOTIFICATION HISTORY"
                                else -> "EDIT"
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                ListItem(
                    headlineContent = { Text("Page Title") },
                    supportingContent = {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }
                )
                Divider()

                ListItem(
                    headlineContent = { Text("Use Custom Settings") },
                    supportingContent = { Text("Override global sidebar settings for this page") },
                    trailingContent = {
                        Switch(
                            checked = useCustomSettings,
                            onCheckedChange = { useCustomSettings = it }
                        )
                    }
                )
                Divider()

                if (useCustomSettings) {
                    if (page.type == "apps" || page.type == "widgets_grid") {
                        ListItem(
                            headlineContent = { Text("App Grid Columns") },
                            supportingContent = {
                                Slider(
                                    value = gridColumns.toFloat(),
                                    onValueChange = { gridColumns = it.toInt() },
                                    valueRange = 2f..6f,
                                    steps = 3
                                )
                            },
                            trailingContent = { Text(gridColumns.toString()) }
                        )
                        Divider()
                    }
                    ListItem(
                        headlineContent = { Text("Width") },
                        supportingContent = {
                            Slider(
                                value = width.toFloat(),
                                onValueChange = { width = it.toInt() },
                                valueRange = 100f..maxScreenWidth,
                                steps = ((maxScreenWidth - 100f) / 10f).toInt()
                            )
                        },
                        trailingContent = { Text("${width}dp") }
                    )
                    Divider()
                    
                    ListItem(
                        headlineContent = { Text("Height (Max)") },
                        supportingContent = {
                            Slider(
                                value = height.toFloat(),
                                onValueChange = { height = it.toInt() },
                                valueRange = 300f..maxScreenHeight,
                                steps = ((maxScreenHeight - 300f) / 10f).toInt()
                            )
                        },
                        trailingContent = { Text("${height}dp") }
                    )
                    Divider()
                    
                    ListItem(
                        headlineContent = { Text("Wrap Content Height") },
                        supportingContent = { Text("Shrink to fit content instead of fixed height") },
                        trailingContent = {
                            Switch(
                                checked = wrapContentHeight,
                                onCheckedChange = { wrapContentHeight = it }
                            )
                        }
                    )
                    Divider()
                    
                    ListItem(
                        headlineContent = { Text("Background Opacity") },
                        supportingContent = {
                            Slider(
                                value = transparency,
                                onValueChange = { transparency = it },
                                valueRange = 0f..1f,
                                steps = 20
                            )
                        },
                        trailingContent = { Text("${(transparency * 100).toInt()}%") }
                    )
                    Divider()
                }
                
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}
