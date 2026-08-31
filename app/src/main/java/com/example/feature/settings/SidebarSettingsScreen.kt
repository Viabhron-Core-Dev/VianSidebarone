package com.example.feature.settings

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.painterResource
import com.example.R
import com.example.utils.PageManager
import com.example.utils.SidebarPage
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SidebarSettingsScreen(
    handleId: String,
    initAction: String? = null,
    initialEditPageId: String? = null,
    onBack: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val maxScreenWidth = configuration.screenWidthDp.toFloat()
    val maxScreenHeight = configuration.screenHeightDp.toFloat()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE) }
    
    var customisingPage by remember { mutableStateOf<SidebarPage?>(null) }
    var selectedActionPage by remember { mutableStateOf<SidebarPage?>(null) }
    var pageActionIndex by remember { mutableStateOf(-1) }
    
    // Pages - the 1st item in the list is always the default page for this gesture
    var pages by remember { mutableStateOf(PageManager.getPages(prefs, handleId)) }

    LaunchedEffect(initialEditPageId) {
        if (initialEditPageId != null) {
            val pageToEdit = pages.find { it.id == initialEditPageId }
            if (pageToEdit != null) {
                customisingPage = pageToEdit
            }
        }
    }

    LaunchedEffect(initAction) {
        if (initAction != null && initAction.startsWith("open_page:")) {
            val target = initAction.removePrefix("open_page:")
            val effectiveType = if (target.startsWith("default_hybrid")) "hybrid_grid" else target
            
            val index = pages.indexOfFirst { 
                it.id == target || it.type == target || (effectiveType == "hybrid_grid" && (it.id.startsWith("default_hybrid") || it.type == "hybrid_grid"))
            }
            
            if (index == -1) {
                val title = when (effectiveType) {
                    "apps" -> "Apps Grid"
                    "scheduler" -> "Short Reminders"
                    "calculator" -> "Calculator"
                    "compass" -> "Compass"
                    "notification", "notifications" -> "Notifications"
                    "widgets_grid" -> "Widgets Grid"
                    "hybrid_grid" -> if (target.startsWith("default_hybrid")) "Home Grid" else "Hybrid"
                    "app_tracker" -> "App Tracker"
                    "resources_tracker" -> "Resources Tracker"
                    "media_player" -> "Media Player"
                    else -> effectiveType.replaceFirstChar { it.uppercase() }
                }
                val newPage = com.example.utils.SidebarPage.createDefault(
                    id = if (effectiveType == "hybrid_grid") "default_hybrid_$handleId" else UUID.randomUUID().toString(),
                    type = effectiveType,
                    title = title
                )
                val newPages = if (pages.size == 1 && pages[0].type == "hybrid_grid" && effectiveType != "hybrid_grid" && !prefs.contains("handle_${handleId}_pages")) {
                    mutableListOf(newPage)
                } else {
                    val list = pages.toMutableList()
                    // Put chosen action page at the beginning if requested as initial gesture action
                    list.add(0, newPage)
                    list
                }
                pages = newPages
                PageManager.savePages(prefs, handleId, newPages)
                PageManager.saveDefaultPageIndex(prefs, handleId, 0)
            } else if (index > 0) {
                // Move it to first if explicitly opened with this action
                val newPages = pages.toMutableList()
                val item = newPages.removeAt(index)
                newPages.add(0, item)
                pages = newPages
                PageManager.savePages(prefs, handleId, newPages)
                PageManager.saveDefaultPageIndex(prefs, handleId, 0)
            }
        }
    }

    if (customisingPage != null) {
        androidx.activity.compose.BackHandler {
            customisingPage = null
        }
        PageCustomizeScreen(
            page = customisingPage!!,
            handleId = handleId,
            onSave = { updated ->
                val newPages = PageManager.getPages(prefs, handleId).toMutableList()
                val idx = newPages.indexOfFirst { it.id == updated.id }
                if (idx != -1) {
                    newPages[idx] = updated
                    PageManager.savePages(prefs, handleId, newPages)
                    pages = newPages
                }
            },
            onBack = {
                customisingPage = null
            }
        )
        return
    }

    // Sidebar options
    var sidebarColorHex by remember { mutableStateOf(prefs.getString("handle_${handleId}_sidebar_color", prefs.getString("sidebar_color", "#1E1E2E")) ?: "#1E1E2E") }
    var sidebarTransparency by remember { mutableStateOf(prefs.getFloat("handle_${handleId}_sidebar_transparency", prefs.getFloat("sidebar_transparency", 0.9f))) }
    var showAddDialog by remember { mutableStateOf(false) }

    fun savePages(newPages: List<SidebarPage> = pages) {
        pages = newPages
        PageManager.savePages(prefs, handleId, newPages)
        PageManager.saveDefaultPageIndex(prefs, handleId, 0)
    }

    fun openPageEditor(page: SidebarPage) {
        when (page.type) {
            "apps" -> {
                val intent = Intent(context, com.example.SidebarEditActivity::class.java).apply {
                    putExtra("PAGE_ID", page.id)
                    putExtra("CONTAINER_ID", handleId)
                    putExtra("HANDLE_ID", handleId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            "widgets_grid" -> {
                val intent = Intent(context, com.example.WidgetsGridEditActivity::class.java).apply {
                    putExtra("PAGE_ID", page.id)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            "hybrid_grid" -> {
                val intent = Intent(context, com.example.HybridGridEditActivity::class.java).apply {
                    putExtra("PAGE_ID", page.id)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            "app_tracker" -> {
                val intent = Intent(context, com.example.AppTrackerSettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            "notification", "notifications" -> {
                val intent = Intent(context, com.example.NotificationHistoryActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            else -> {
                customisingPage = page
            }
        }
    }

    // Drag and drop state
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sidebar Settings") },
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
            item {
                Text(
                    text = "Appearance & Layout",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp, 8.dp)
                )
                ListItem(
                    headlineContent = { Text("Sidebar Color") },
                    supportingContent = {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val presetColors = listOf(
                                "#1E1E2E", "#000000", "#FFFFFF", "#FF5252", "#4CAF50", "#2196F3", "#FFEB3B", "#87CEEB"
                            )
                            presetColors.forEach { colorString ->
                                val parsedColor = try {
                                    Color(android.graphics.Color.parseColor(colorString))
                                } catch (e: Exception) {
                                    Color.Gray
                                }
                                val baseColorStr = if (colorString.length >= 7) colorString.substring(colorString.length - 6) else colorString
                                val currentBaseStr = if (sidebarColorHex.length >= 7) sidebarColorHex.substring(sidebarColorHex.length - 6) else sidebarColorHex
                                val isSelected = baseColorStr.equals(currentBaseStr, ignoreCase = true)
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(parsedColor, androidx.compose.foundation.shape.CircleShape)
                                        .border(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                                            shape = androidx.compose.foundation.shape.CircleShape
                                        )
                                        .clickable {
                                            sidebarColorHex = colorString
                                            prefs.edit().putString("handle_${handleId}_sidebar_color", colorString).apply()
                                        }
                                )
                            }
                        }
                    }
                )
                Divider()
                ListItem(
                    headlineContent = { Text("Background Opacity") },
                    supportingContent = {
                        Slider(
                            value = sidebarTransparency,
                            onValueChange = { 
                                sidebarTransparency = it
                                prefs.edit().putFloat("handle_${handleId}_sidebar_transparency", it).apply()
                            },
                            valueRange = 0f..1f,
                            steps = 20
                        )
                    },
                    trailingContent = { Text("${(sidebarTransparency * 100).toInt()}%") }
                )
                
                Divider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Pages Management",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "First page is default for gesture",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
                val isDragging = draggingIndex == index
                val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp)

                Box(
                    modifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .shadow(elevation)
                        .background(if (isDragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                ) {
                    ListItem(
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                customisingPage = page
                            },
                            onLongClick = {
                                selectedActionPage = page
                                pageActionIndex = index
                            }
                        ),
                        leadingContent = {
                            // Drag handle icon placed first to arrange pages easily
                            Box(
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .pointerInput(Unit) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                draggingIndex = index
                                                dragOffsetY = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragOffsetY += dragAmount.y
                                                val threshold = 55.dp.toPx()
                                                val currentIndex = draggingIndex ?: return@detectDragGesturesAfterLongPress
                                                if (dragOffsetY > threshold && currentIndex < pages.size - 1) {
                                                    val newPages = pages.toMutableList()
                                                    val item = newPages.removeAt(currentIndex)
                                                    newPages.add(currentIndex + 1, item)
                                                    draggingIndex = currentIndex + 1
                                                    dragOffsetY -= threshold
                                                    savePages(newPages)
                                                } else if (dragOffsetY < -threshold && currentIndex > 0) {
                                                    val newPages = pages.toMutableList()
                                                    val item = newPages.removeAt(currentIndex)
                                                    newPages.add(currentIndex - 1, item)
                                                    draggingIndex = currentIndex - 1
                                                    dragOffsetY += threshold
                                                    savePages(newPages)
                                                }
                                            },
                                            onDragEnd = {
                                                draggingIndex = null
                                                dragOffsetY = 0f
                                            },
                                            onDragCancel = {
                                                draggingIndex = null
                                                dragOffsetY = 0f
                                            }
                                        )
                                    }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_drag_handle),
                                    contentDescription = "Drag to reorder",
                                    tint = if (index == 0) MaterialTheme.colorScheme.primary else Color.Gray,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        },
                        headlineContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = page.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (index == 0) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = MaterialTheme.shapes.extraSmall
                                    ) {
                                        Text(
                                            text = "Default",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            softWrap = false,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        },
                        supportingContent = { Text(page.type.replace("_", " ").replaceFirstChar { it.uppercase() }) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            val newPages = pages.toMutableList()
                                            val temp = newPages[index]
                                            newPages[index] = newPages[index - 1]
                                            newPages[index - 1] = temp
                                            savePages(newPages)
                                        }
                                    },
                                    enabled = index > 0,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(painterResource(R.drawable.ic_arrow_upward), "Move Up", modifier = Modifier.size(20.dp))
                                }
                                IconButton(
                                    onClick = {
                                        if (index < pages.size - 1) {
                                            val newPages = pages.toMutableList()
                                            val temp = newPages[index]
                                            newPages[index] = newPages[index + 1]
                                            newPages[index + 1] = temp
                                            savePages(newPages)
                                        }
                                    },
                                    enabled = index < pages.size - 1,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(painterResource(R.drawable.ic_arrow_downward), "Move Down", modifier = Modifier.size(20.dp))
                                }
                                IconButton(
                                    onClick = {
                                        selectedActionPage = page
                                        pageActionIndex = index
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.MoreVert, "More Options", modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    )

                    DropdownMenu(
                        expanded = selectedActionPage == page && pageActionIndex == index,
                        onDismissRequest = {
                            selectedActionPage = null
                            pageActionIndex = -1
                        }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit Page Content") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                val p = selectedActionPage ?: page
                                selectedActionPage = null
                                pageActionIndex = -1
                                openPageEditor(p)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Customize Settings") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_tune), contentDescription = null) },
                            onClick = {
                                customisingPage = selectedActionPage ?: page
                                selectedActionPage = null
                                pageActionIndex = -1
                            }
                        )
                        if (index > 0) {
                            DropdownMenuItem(
                                text = { Text("Set as Default (Move to First)") },
                                leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) },
                                onClick = {
                                    val newPages = pages.toMutableList()
                                    val item = newPages.removeAt(pageActionIndex)
                                    newPages.add(0, item)
                                    savePages(newPages)
                                    selectedActionPage = null
                                    pageActionIndex = -1
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Delete Page", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                if (pages.size > 1) {
                                    val newPages = pages.toMutableList()
                                    newPages.removeAt(pageActionIndex)
                                    savePages(newPages)
                                } else {
                                    val defaultPage = SidebarPage(
                                        id = "default_hybrid_$handleId",
                                        type = "hybrid_grid",
                                        title = "Home Grid"
                                    )
                                    savePages(listOf(defaultPage))
                                }
                                selectedActionPage = null
                                pageActionIndex = -1
                            }
                        )
                    }
                }
                Divider()
            }
            item {
                Spacer(modifier = Modifier.height(80.dp)) // padding for FAB
            }
        }

        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("Add Sidebar Page") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        val types = listOf(
                            "hybrid_grid" to "Hybrid",
                            "apps" to "Apps Grid",
                            "widgets_grid" to "Widgets Grid",
                            "scheduler" to "Short Reminders",
                            "calculator" to "Calculator",
                            "compass" to "Compass",
                            "notification" to "Notifications",
                            "app_tracker" to "App Tracker",
                            "resources_tracker" to "Resources Tracker",
                            "media_player" to "Media Player"
                        )
                        types.forEach { (type, title) ->
                            TextButton(
                                onClick = {
                                    val newPages = pages.toMutableList()
                                    val newPage = SidebarPage.createDefault(
                                        id = UUID.randomUUID().toString(),
                                        type = type,
                                        title = title
                                    )
                                    newPages.add(newPage)
                                    savePages(newPages)
                                    showAddDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = title,
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodyLarge
                                )
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
