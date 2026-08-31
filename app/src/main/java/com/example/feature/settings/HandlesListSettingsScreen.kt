package com.example.feature.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.UUID
import com.example.core.HandleManager
import com.example.core.HandleConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandlesListSettingsScreen(
    onNavigateToHandle: (String) -> Unit,
    onNavigateToSidebarSettings: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE) }
    
    var handles by remember { mutableStateOf(HandleManager.getHandles(prefs)) }
    
    fun save() {
        HandleManager.saveHandles(prefs, handles, context)
        handles = handles.toList() // trigger recomposition
    }

    var expandedHandleId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Handles & Sidebar") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val newId = UUID.randomUUID().toString()
                handles = handles + HandleConfig(id = newId, name = "Handle ${handles.size + 1}", enabled = true)
                save()
            }) {
                Icon(Icons.Default.Add, "Add Handle")
            }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            items(handles) { handle ->
                HandleItem(
                    handle = handle,
                    prefs = prefs,
                    isExpanded = expandedHandleId == handle.id,
                    onExpand = {
                        expandedHandleId = if (expandedHandleId == handle.id) null else handle.id
                    },
                    onNavigateToHandle = { onNavigateToHandle(handle.id) },
                    onNavigateToSidebarSettings = { gesture, action -> onNavigateToSidebarSettings("${handle.id}_$gesture" + (if (action != null) "|$action" else "")) },
                    onUpdate = { updated ->
                        handles = handles.map { if (it.id == updated.id) updated else it }
                        save()
                    },
                    onDelete = {
                        handles = handles.filter { it.id != handle.id }
                        save()
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandleItem(
    handle: HandleConfig,
    prefs: android.content.SharedPreferences,
    isExpanded: Boolean,
    onExpand: () -> Unit,
    onNavigateToHandle: () -> Unit,
    onNavigateToSidebarSettings: (String, String?) -> Unit,
    onUpdate: (HandleConfig) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showAddGestureDialog by remember { mutableStateOf(false) }
                    var showChangeGestureDialog by remember { mutableStateOf(false) }
                    var gestureToChange by remember { mutableStateOf("") }
    var showChangeTriggerDialog by remember { mutableStateOf(false) }
    var triggerToChange by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpand() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(handle.name, style = MaterialTheme.typography.titleMedium)
                }
                Switch(
                    checked = handle.enabled,
                    onCheckedChange = { onUpdate(handle.copy(enabled = it)) }
                )
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, "More options")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                showMenu = false
                                showRenameDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Adjust") },
                            onClick = {
                                showMenu = false
                                onNavigateToHandle()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }

            // Expanded content (Gestures)
            if (isExpanded) {
                Divider()
                Column(modifier = Modifier.padding(16.dp)) {
                    val gestureKeys = listOf("tap", "double_tap", "long_press", "swipe_up", "swipe_down", "swipe_left", "swipe_right")
                    val gestureLabels = mapOf(
                        "tap" to "Single Tap",
                        "double_tap" to "Double Tap",
                        "long_press" to "Long Press",
                        "swipe_up" to "Swipe Up",
                        "swipe_down" to "Swipe Down",
                        "swipe_left" to "Swipe Left",
                        "swipe_right" to "Swipe Right"
                    )
                    
                    val prefix = "handle_${handle.id}_"
                    val gesturesMap = remember { mutableStateMapOf<String, String>() }
                    
                    LaunchedEffect(isExpanded, handle.id) {
                        gesturesMap.clear()
                        gestureKeys.forEach { key ->
                            val action = prefs.getString("${prefix}$key", "none") ?: "none"
                            if (action != "none") {
                                gesturesMap[key] = action
                            }
                        }
                    }
                    
                    fun migrateGesture(oldGesture: String, newGesture: String) {
                        val oldPrefix = "handle_${handle.id}_${oldGesture}"
                        val newPrefix = "handle_${handle.id}_${newGesture}"
                        val editor = prefs.edit()
                        
                        val action = prefs.getString(oldPrefix, null)
                        if (action != null) {
                            editor.putString(newPrefix, action)
                            editor.remove(oldPrefix)
                        }
                        
                        prefs.all.keys.forEach { key ->
                            if (key.startsWith("${oldPrefix}_")) {
                                val newKey = key.replaceFirst(oldPrefix, newPrefix)
                                val value = prefs.all[key]
                                when (value) {
                                    is String -> editor.putString(newKey, value)
                                    is Int -> editor.putInt(newKey, value)
                                    is Boolean -> editor.putBoolean(newKey, value)
                                    is Float -> editor.putFloat(newKey, value)
                                    is Long -> editor.putLong(newKey, value)
                                }
                                editor.remove(key)
                            }
                        }
                        editor.apply()
                        
                        gesturesMap.remove(oldGesture)
                        if (action != null) {
                            gesturesMap[newGesture] = action
                        }
                    }
                    
                    fun updateGesture(gesture: String, action: String) {
                        val containerId = "${handle.id}_$gesture"
                        if (action == "none") {
                            gesturesMap.remove(gesture)
                        } else {
                            gesturesMap[gesture] = action
                            if (action.startsWith("open_page:")) {
                                val target = action.removePrefix("open_page:")
                                val pageType = if (target.startsWith("default_hybrid")) "hybrid_grid" else target
                                val pageTitle = when(pageType) {
                                    "apps" -> "Apps Grid"
                                    "widgets_grid" -> "Widgets Grid"
                                    "hybrid_grid" -> if (target == "default_hybrid" || target.startsWith("default_hybrid")) "Home Grid" else "Hybrid"
                                    "app_tracker" -> "App Tracker"
                                    "resources_tracker" -> "Resources Tracker"
                                    "media_player" -> "Media Player"
                                    "calculator" -> "Calculator"
                                    "scheduler" -> "Short Reminders"
                                    "compass" -> "Compass"
                                    "notifications", "notification" -> "Notifications"
                                    else -> pageType.replace("_", " ").replaceFirstChar { it.uppercase() }
                                }
                                val containerPages = com.example.utils.PageManager.getPages(prefs, containerId).toMutableList()
                                val existingIndex = containerPages.indexOfFirst { 
                                    it.id == target || it.type == pageType || (pageType == "hybrid_grid" && (it.id.startsWith("default_hybrid") || it.type == "hybrid_grid"))
                                }
                                if (existingIndex == -1) {
                                    val newPage = com.example.utils.SidebarPage.createDefault(
                                        id = if (pageType == "hybrid_grid") "default_hybrid_$containerId" else UUID.randomUUID().toString(),
                                        type = pageType,
                                        title = pageTitle
                                    )
                                    if (containerPages.size == 1 && containerPages[0].type == "hybrid_grid" && pageType != "hybrid_grid" && !prefs.contains("handle_${containerId}_pages")) {
                                        containerPages.clear()
                                        containerPages.add(newPage)
                                    } else {
                                        containerPages.add(0, newPage)
                                    }
                                    com.example.utils.PageManager.savePages(prefs, containerId, containerPages, context)
                                }
                            }
                        }
                        prefs.edit().putString("${prefix}$gesture", action).commit()
                        com.example.core.OverlaySyncManager.syncString(context, "${prefix}$gesture", action)
                    }

                    if (gesturesMap.isEmpty()) {
                        Text("No gestures assigned.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                    } else {
                        gesturesMap.forEach { (gesture, action) ->
                            val containerId = "${handle.id}_$gesture"
                            val configuredPages = com.example.utils.PageManager.getPages(prefs, containerId)
                            val actionName = when {
                                action == "toggle_sidebar" -> "Sidebar (Default / Active Page)"
                                action == "toggle_reader" -> "Toggle Floating Reader"
                                action.startsWith("open_page:") -> {
                                    val targetId = action.removePrefix("open_page:")
                                    val matchedPage = configuredPages.find { 
                                        it.id == targetId || it.type == targetId || (targetId.startsWith("default_hybrid") && it.type == "hybrid_grid")
                                    }
                                    if (matchedPage != null) "Sidebar: ${matchedPage.title}" else "Sidebar: $targetId"
                                }
                                action.startsWith("action_") || action.startsWith("action:") -> {
                                    val act = action.removePrefix("action_").removePrefix("action:")
                                    when (act) {
                                        "screenshot" -> "Action: Take Screenshot"
                                        "long_screenshot" -> "Action: Long Screenshot"
                                        "lock_screen" -> "Action: Lock Screen"
                                        "notifications" -> "Action: Notifications Panel"
                                        "quick_settings" -> "Action: Quick Settings"
                                        "recents" -> "Action: Recent Apps"
                                        "home" -> "Action: Go Home"
                                        "back" -> "Action: Back"
                                        "splitscreen" -> "Action: Split Screen"
                                        "cursor" -> "Action: Virtual Cursor"
                                        "auto_scroll" -> "Action: Auto Scroll"
                                        "audio_record" -> "Action: Audio Record"
                                        "barcode_scanner" -> "Action: Secure Camera Scanner"
                                        "qr_scan" -> "Action: Secure Screen Scanner"
                                        "redact_screenshot" -> "Action: Redact Screenshot"
                                        else -> "Action: " + act.replace("_", " ").replaceFirstChar { it.uppercase() }
                                    }
                                }
                                action.startsWith("open_element:") -> {
                                    val elem = action.removePrefix("open_element:").removePrefix("page_window:").removePrefix("system:")
                                    "Element: " + elem.replace("_", " ").replaceFirstChar { it.uppercase() }
                                }
                                else -> action
                            }
                            
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                onClick = {
                                    if (action == "toggle_sidebar") {
                                        onNavigateToSidebarSettings(gesture, null)
                                    } else if (action.startsWith("open_page:")) {
                                        onNavigateToSidebarSettings(gesture, action)
                                    }
                                }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(gestureLabels[gesture] ?: gesture, style = MaterialTheme.typography.titleSmall)
                                        Text(actionName, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    var showGestureMenu by remember { mutableStateOf(false) }
                                    Box {
                                        IconButton(onClick = { showGestureMenu = true }) {
                                            Icon(Icons.Default.MoreVert, "More")
                                        }
                                        DropdownMenu(
                                            expanded = showGestureMenu,
                                            onDismissRequest = { showGestureMenu = false }
                                        ) {
                                            if (action == "toggle_sidebar" || action.startsWith("open_page:")) {
                                                DropdownMenuItem(
                                                    text = { Text("Sidebar Settings") },
                                                    onClick = {
                                                        showGestureMenu = false
                                                        onNavigateToSidebarSettings(gesture, if (action == "toggle_sidebar") null else action)
                                                    }
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text("Change Action") },
                                                onClick = {
                                                    showGestureMenu = false
                                                    gestureToChange = gesture
                                                    showChangeGestureDialog = true
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Change Gesture") },
                                                onClick = {
                                                    showGestureMenu = false
                                                    triggerToChange = gesture
                                                    showChangeTriggerDialog = true
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Remove") },
                                                onClick = {
                                                    showGestureMenu = false
                                                    updateGesture(gesture, "none")
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { showAddGestureDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, "Add")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("ADD GESTURE")
                    }
                    
                    if (showChangeTriggerDialog) {
                        val availableGestures = gestureKeys.filter { !gesturesMap.containsKey(it) || it == triggerToChange }
                        var selectedNewGesture by remember { mutableStateOf(availableGestures.firstOrNull { it != triggerToChange } ?: availableGestures.firstOrNull() ?: "") }
                        val localContext = androidx.compose.ui.platform.LocalContext.current
                        
                        AlertDialog(
                            onDismissRequest = { showChangeTriggerDialog = false },
                            title = { Text("Change Gesture for ${gestureLabels[triggerToChange] ?: triggerToChange}") },
                            text = {
                                Column {
                                    if (availableGestures.size <= 1) {
                                        Text("No other available gestures.")
                                    } else {
                                        ActionDropdown(
                                            "Select New Gesture", 
                                            selectedNewGesture, 
                                            availableGestures.filter { it != triggerToChange }.map { it to (gestureLabels[it] ?: it) }
                                        ) { selectedNewGesture = it }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    if (selectedNewGesture.isNotEmpty() && selectedNewGesture != triggerToChange && availableGestures.contains(selectedNewGesture)) {
                                        migrateGesture(triggerToChange, selectedNewGesture)
                                        
                                        // Notify service to reload configuration
                                        val intent = android.content.Intent(localContext, com.example.core.HandleService::class.java).apply {
                                            action = "UPDATE_CONFIG"
                                        }
                                        localContext.startService(intent)
                                        
                                        showChangeTriggerDialog = false
                                    } else {
                                        showChangeTriggerDialog = false
                                    }
                                }) {
                                    Text("Change")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showChangeTriggerDialog = false }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }
                    
                    val elementActionOptions = listOf(
                        "action_screenshot" to "Take Screenshot",
                        "action_long_screenshot" to "Long Screenshot",
                        "action_lock_screen" to "Lock Screen",
                        "action_notifications" to "Notifications Panel",
                        "action_quick_settings" to "Quick Settings",
                        "action_recents" to "Recent Apps",
                        "action_home" to "Go Home",
                        "action_back" to "Back",
                        "action_splitscreen" to "Split Screen",
                        "action_cursor" to "Virtual Cursor",
                        "action_auto_scroll" to "Auto Scroll",
                        "action_audio_record" to "Audio Record",
                        "action_barcode_scanner" to "Secure Camera Scanner",
                        "action_qr_scan" to "Secure Screen Scanner",
                        "action_redact_screenshot" to "Redact Screenshot",
                        "toggle_reader" to "Toggle Floating Reader",
                        "open_element:page_window:calculator" to "Calculator (Floating)",
                        "open_element:page_window:compass" to "Compass (Floating)",
                        "open_element:page_window:dictionary" to "Dictionary (Floating)"
                    )

                    val availableSidebarPresets = listOf(
                        "open_page:default_hybrid" to "Home Grid (Hybrid)",
                        "open_page:apps" to "Apps Grid",
                        "open_page:widgets_grid" to "Widgets Grid",
                        "open_page:scheduler" to "Short Reminders",
                        "open_page:calculator" to "Calculator",
                        "open_page:compass" to "Compass",
                        "open_page:notification" to "Notifications",
                        "open_page:app_tracker" to "App Tracker",
                        "open_page:resources_tracker" to "Resources Tracker",
                        "open_page:media_player" to "Media Player"
                    )

                    if (showChangeGestureDialog) {
                        val containerId = "${handle.id}_$gestureToChange"
                        val pageConfigs = com.example.utils.PageManager.getPages(prefs, containerId)
                        val categoryOptions = listOf(
                            "sidebar" to "Sidebar",
                            "element" to "Action / Element"
                        )
                        var selectedCategory by remember { mutableStateOf("sidebar") }
                        
                        val sidebarPageOptions = mutableListOf<Pair<String, String>>()
                        availableSidebarPresets.forEach { preset ->
                            sidebarPageOptions.add(preset)
                        }
                        if (pageConfigs.isNotEmpty()) {
                            pageConfigs.forEach { page ->
                                val pageEntry = "open_page:${page.id}" to "${page.title} (${page.type})"
                                if (sidebarPageOptions.none { it.first == pageEntry.first || it.first == "open_page:${page.type}" }) {
                                    sidebarPageOptions.add(pageEntry)
                                }
                            }
                        }
                        
                        var selectedSidebarOption by remember { mutableStateOf(sidebarPageOptions.firstOrNull()?.first ?: "open_page:default_hybrid") }
                        var selectedElementOption by remember { mutableStateOf(elementActionOptions.first().first) }
                        
                        AlertDialog(
                            onDismissRequest = { showChangeGestureDialog = false },
                            title = { Text("Change Action for ${gestureLabels[gestureToChange] ?: gestureToChange}") },
                            text = {
                                Column {
                                    ActionDropdown("Category", selectedCategory, categoryOptions) { selectedCategory = it }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    
                                    if (selectedCategory == "sidebar") {
                                        ActionDropdown("Select Sidebar Page", selectedSidebarOption, sidebarPageOptions) { selectedSidebarOption = it }
                                    } else {
                                        ActionDropdown("Select Action / Element", selectedElementOption, elementActionOptions) { selectedElementOption = it }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    if (selectedCategory == "sidebar") {
                                        updateGesture(gestureToChange, selectedSidebarOption)
                                    } else {
                                        updateGesture(gestureToChange, selectedElementOption)
                                    }
                                    showChangeGestureDialog = false
                                }) {
                                    Text("Change")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showChangeGestureDialog = false }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }

                    if (showAddGestureDialog) {
                        var selectedGesture by remember { mutableStateOf(gestureKeys.firstOrNull { !gesturesMap.containsKey(it) } ?: gestureKeys.first()) }
                        val pageConfigs = com.example.utils.PageManager.getPages(prefs, "${handle.id}_$selectedGesture")
                        
                        val categoryOptions = listOf(
                            "sidebar" to "Sidebar",
                            "element" to "Action / Element"
                        )
                        var selectedCategory by remember { mutableStateOf("sidebar") }
                        
                        val sidebarPageOptions = mutableListOf<Pair<String, String>>()
                        availableSidebarPresets.forEach { preset ->
                            sidebarPageOptions.add(preset)
                        }
                        if (pageConfigs.isNotEmpty()) {
                            pageConfigs.forEach { page ->
                                val pageEntry = "open_page:${page.id}" to "${page.title} (${page.type})"
                                if (sidebarPageOptions.none { it.first == pageEntry.first || it.first == "open_page:${page.type}" }) {
                                    sidebarPageOptions.add(pageEntry)
                                }
                            }
                        }
                        
                        var selectedSidebarOption by remember { mutableStateOf(sidebarPageOptions.firstOrNull()?.first ?: "open_page:default_hybrid") }
                        var selectedElementOption by remember { mutableStateOf(elementActionOptions.first().first) }
                        
                        AlertDialog(
                            onDismissRequest = { showAddGestureDialog = false },
                            title = { Text("Add Gesture") },
                            text = {
                                Column {
                                    ActionDropdown("Select Gesture", selectedGesture, gestureKeys.filter { !gesturesMap.containsKey(it) }.map { it to (gestureLabels[it] ?: it) }) { selectedGesture = it }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    ActionDropdown("Category", selectedCategory, categoryOptions) { selectedCategory = it }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    
                                    if (selectedCategory == "sidebar") {
                                        ActionDropdown("Select Sidebar Page", selectedSidebarOption, sidebarPageOptions) { selectedSidebarOption = it }
                                    } else {
                                        ActionDropdown("Select Action / Element", selectedElementOption, elementActionOptions) { selectedElementOption = it }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    if (selectedCategory == "sidebar") {
                                        updateGesture(selectedGesture, selectedSidebarOption)
                                    } else {
                                        updateGesture(selectedGesture, selectedElementOption)
                                    }
                                    showAddGestureDialog = false
                                }) {
                                    Text("Add")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showAddGestureDialog = false }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showRenameDialog) {
        var newName by remember { mutableStateOf(handle.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Handle") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdate(handle.copy(name = newName))
                    showRenameDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
