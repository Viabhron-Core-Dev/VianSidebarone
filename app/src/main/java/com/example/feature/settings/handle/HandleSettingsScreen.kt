package com.example.feature.settings.handle

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.HandleConfig
import com.example.core.HandleGestures
import com.example.core.HandleManager
import com.example.core.OverlaySyncManager
import com.example.util.HandleEdge
import com.example.util.HandleShape

/**
 * HandleSettingsScreen: Adapted directly from reference HandlesListSettingsScreen and HandleEditScreen.
 * Preserves the exact reference UI layout, typography, controls, and behavior while binding
 * directly to the current 2-process HandleManager architecture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandleSettingsScreen(
    initialRoute: String? = null,
    onNavigateBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val handleManager = remember { HandleManager.getInstance(context) }
    var selectedHandleId by remember { mutableStateOf<String?>(null) }

    // Managing container state: Triple(containerId, handleName, gestureLabel)
    var managingContainerInfo by remember {
        mutableStateOf(
            if (initialRoute?.startsWith("pages_") == true) {
                val cId = initialRoute.removePrefix("pages_").substringBefore("|")
                val container = handleManager.getContainer(cId)
                val hName = container?.let { handleManager.getHandle(it.handleId)?.name } ?: "Sidebar"
                val gLabel = container?.gesture?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: "Gesture"
                Triple(cId, hName, gLabel)
            } else null
        )
    }

    if (managingContainerInfo != null) {
        val (cId, hName, gLabel) = managingContainerInfo!!
        ContainerPageManagementScreen(
            containerId = cId,
            handleName = hName,
            gestureLabel = gLabel,
            onBack = {
                managingContainerInfo = null
                if (initialRoute?.startsWith("pages_") == true) {
                    onNavigateBack?.invoke()
                }
            }
        )
        return
    }

    if (selectedHandleId == null) {
        ReferenceHandlesListScreen(
            handleManager = handleManager,
            onNavigateToHandle = { handleId -> selectedHandleId = handleId },
            onManageContainer = { containerId, handleName, gestureLabel ->
                managingContainerInfo = Triple(containerId, handleName, gestureLabel)
            },
            onBack = onNavigateBack
        )
    } else {
        ReferenceHandleEditScreen(
            handleId = selectedHandleId!!,
            handleManager = handleManager,
            onBack = { selectedHandleId = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferenceHandlesListScreen(
    handleManager: HandleManager,
    onNavigateToHandle: (String) -> Unit,
    onManageContainer: (containerId: String, handleName: String, gestureLabel: String) -> Unit,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var handles by remember { mutableStateOf(handleManager.getAllHandles()) }
    var expandedHandleId by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        handles = handleManager.getAllHandles()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Handles & Sidebar") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    handleManager.createHandle("Handle ${handles.size + 1}")
                    refresh()
                },
                modifier = Modifier.testTag("add_handle_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Handle")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
                .testTag("handles_list")
        ) {
            items(handles, key = { it.id }) { handle ->
                ReferenceHandleItem(
                    handle = handle,
                    handleManager = handleManager,
                    isExpanded = expandedHandleId == handle.id,
                    canDelete = handles.size > 1,
                    onExpand = {
                        expandedHandleId = if (expandedHandleId == handle.id) null else handle.id
                    },
                    onNavigateToHandle = { onNavigateToHandle(handle.id) },
                    onManageContainer = onManageContainer,
                    onRefresh = { refresh() }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun ReferenceHandleItem(
    handle: HandleConfig,
    handleManager: HandleManager,
    isExpanded: Boolean,
    canDelete: Boolean,
    onExpand: () -> Unit,
    onNavigateToHandle: () -> Unit,
    onManageContainer: (containerId: String, handleName: String, gestureLabel: String) -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current

    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showAddGestureDialog by remember { mutableStateOf(false) }
    var showTwoChoiceDialog by remember { mutableStateOf(false) }
    var gestureToConfigure by remember { mutableStateOf("") }
    var pendingDirectElementGesture by remember { mutableStateOf("") }
    var showChangeTriggerDialog by remember { mutableStateOf(false) }
    var triggerToChange by remember { mutableStateOf("") }

    val elementPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val elementId = result.data?.getStringExtra("ELEMENT_ID")
        val targetGesture = pendingDirectElementGesture
        if (!elementId.isNullOrEmpty() && targetGesture.isNotEmpty()) {
            handleManager.configureGesture(handle.id, targetGesture, elementId)
            onRefresh()
        }
        pendingDirectElementGesture = ""
    }

    val gestureLabels = remember {
        mapOf(
            HandleGestures.TAP to "Single Tap",
            HandleGestures.DOUBLE_TAP to "Double Tap",
            HandleGestures.LONG_PRESS to "Long Press",
            HandleGestures.SWIPE_UP to "Swipe Up",
            HandleGestures.SWIPE_DOWN to "Swipe Down",
            HandleGestures.SWIPE_LEFT to "Swipe Left",
            HandleGestures.SWIPE_RIGHT to "Swipe Right"
        )
    }

    val actionLabels = remember {
        mapOf(
            HandleManager.ACTION_OPEN_SIDEBAR to "Sidebar (Default / Active Page)",
            "action_screenshot" to "Action: Take Screenshot",
            "action_long_screenshot" to "Action: Long Screenshot",
            "action_lock_screen" to "Action: Lock Screen",
            "action_notifications" to "Action: Notifications Panel",
            "action_quick_settings" to "Action: Quick Settings",
            "action_recents" to "Action: Recent Apps",
            "action_home" to "Action: Go Home",
            "action_back" to "Action: Back",
            "action_splitscreen" to "Action: Split Screen",
            "action_cursor" to "Action: Virtual Cursor",
            "action_auto_scroll" to "Action: Auto Scroll",
            "action_audio_record" to "Action: Audio Record",
            "action_barcode_scanner" to "Action: Secure Camera Scanner",
            HandleManager.ACTION_NONE to "None"
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("handle_card_${handle.id}"),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
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
                    onCheckedChange = { isChecked ->
                        handleManager.setHandleEnabled(handle.id, isChecked)
                        onRefresh()
                    },
                    modifier = Modifier.testTag("handle_switch_${handle.id}")
                )
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
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
                        if (canDelete) {
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMenu = false
                                    handleManager.deleteHandle(handle.id)
                                    onRefresh()
                                }
                            )
                        }
                    }
                }
            }

            // Expanded Gestures content
            if (isExpanded) {
                HorizontalDivider()
                Column(modifier = Modifier.padding(16.dp)) {
                    val configuredGestures = remember(handle) {
                        HandleGestures.ALL.mapNotNull { g ->
                            val act = handle.getActionForGesture(g)
                            if (act != HandleManager.ACTION_NONE) g to act else null
                        }
                    }

                    if (configuredGestures.isEmpty()) {
                        Text(
                            text = "No gestures configured yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    } else {
                        configuredGestures.forEach { (gesture, action) ->
                            val actionName = when {
                                action == HandleManager.ACTION_OPEN_SIDEBAR -> "Sidebar"
                                action.startsWith("app:") -> "App: " + action.removePrefix("app:")
                                action.startsWith("link:") -> "Link: " + action.removePrefix("link:")
                                action.startsWith("system:") -> "System: " + action.removePrefix("system:").replace("_", " ").replaceFirstChar { it.uppercase() }
                                action.startsWith("widget:") -> "Widget: " + action.removePrefix("widget:")
                                action.startsWith("popup_widget:") -> "Popup Widget: " + action.removePrefix("popup_widget:")
                                action.startsWith("folder:") -> "Folder: " + action.removePrefix("folder:")
                                action.startsWith("floating_trigger:") -> "Floating: " + action.removePrefix("floating_trigger:")
                                actionLabels.containsKey(action) -> actionLabels[action]!!
                                else -> "Action: $action"
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (action == HandleManager.ACTION_OPEN_SIDEBAR) {
                                                val cId = HandleManager.getContainerId(handle.id, gesture)
                                                onManageContainer(cId, handle.name, gestureLabels[gesture] ?: gesture)
                                            } else {
                                                gestureToConfigure = gesture
                                                showTwoChoiceDialog = true
                                            }
                                        }
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            gestureLabels[gesture] ?: gesture,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Text(actionName, style = MaterialTheme.typography.bodyMedium)
                                        if (action == HandleManager.ACTION_OPEN_SIDEBAR) {
                                            Text(
                                                "Tap to manage pages",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    var showGestureItemMenu by remember { mutableStateOf(false) }
                                    Box {
                                        IconButton(onClick = { showGestureItemMenu = true }) {
                                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                                        }
                                        DropdownMenu(
                                            expanded = showGestureItemMenu,
                                            onDismissRequest = { showGestureItemMenu = false }
                                        ) {
                                            if (action == HandleManager.ACTION_OPEN_SIDEBAR) {
                                                DropdownMenuItem(
                                                    text = { Text("Manage Sidebar Pages") },
                                                    onClick = {
                                                        showGestureItemMenu = false
                                                        val cId = HandleManager.getContainerId(handle.id, gesture)
                                                        onManageContainer(cId, handle.name, gestureLabels[gesture] ?: gesture)
                                                    }
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text("Change Action") },
                                                onClick = {
                                                    showGestureItemMenu = false
                                                    gestureToConfigure = gesture
                                                    showTwoChoiceDialog = true
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Change Gesture") },
                                                onClick = {
                                                    showGestureItemMenu = false
                                                    triggerToChange = gesture
                                                    showChangeTriggerDialog = true
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Remove", color = MaterialTheme.colorScheme.error) },
                                                onClick = {
                                                    showGestureItemMenu = false
                                                    handleManager.removeGesture(handle.id, gesture)
                                                    onRefresh()
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_gesture_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("ADD GESTURE")
                    }
                }
            }
        }
    }

    // Rename Dialog
    if (showRenameDialog) {
        var newName by remember { mutableStateOf(handle.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Handle") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Handle Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        handleManager.saveHandle(handle.copy(name = newName.trim()))
                        onRefresh()
                    }
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

    // Add Gesture Dialog

    // Step 1 Dialog: Choose Gesture Trigger
    if (showAddGestureDialog) {
        val availableGestures = HandleGestures.ALL.filter { handle.getActionForGesture(it) == HandleManager.ACTION_NONE }
        var selectedGesture by remember { mutableStateOf(availableGestures.firstOrNull() ?: "") }

        AlertDialog(
            onDismissRequest = { showAddGestureDialog = false },
            title = { Text("Add Gesture") },
            text = {
                Column {
                    if (availableGestures.isEmpty()) {
                        Text("All gestures are already assigned.")
                    } else {
                        Text("Gesture Trigger:", style = MaterialTheme.typography.bodySmall)
                        availableGestures.forEach { g ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedGesture = g }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selectedGesture == g, onClick = { selectedGesture = g })
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(gestureLabels[g] ?: g)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (availableGestures.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            if (selectedGesture.isNotEmpty()) {
                                gestureToConfigure = selectedGesture
                                showAddGestureDialog = false
                                showTwoChoiceDialog = true
                            }
                        },
                        modifier = Modifier.testTag("dialog_confirm_add_gesture")
                    ) {
                        Text("Next")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddGestureDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Step 2 Dialog: Exactly TWO choices (Sidebar Page vs Element directly)
    if (showTwoChoiceDialog && gestureToConfigure.isNotEmpty()) {
        val targetGesture = gestureToConfigure
        AlertDialog(
            onDismissRequest = {
                showTwoChoiceDialog = false
                gestureToConfigure = ""
            },
            title = { Text("Configure Gesture: ${gestureLabels[targetGesture] ?: targetGesture}") },
            text = {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        "Choose the target type for this gesture:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Option A: Sidebar Page
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                handleManager.configureGesture(handle.id, targetGesture, HandleManager.ACTION_OPEN_SIDEBAR)
                                onRefresh()
                                showTwoChoiceDialog = false
                                gestureToConfigure = ""
                                val cId = HandleManager.getContainerId(handle.id, targetGesture)
                                onManageContainer(cId, handle.name, gestureLabels[targetGesture] ?: targetGesture)
                            }
                            .testTag("option_sidebar_page"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Sidebar Page",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "This gesture opens and manages the multi-page Sidebar belonging to this container.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Option B: Element directly
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                pendingDirectElementGesture = targetGesture
                                showTwoChoiceDialog = false
                                gestureToConfigure = ""
                                val intent = Intent(context, com.example.feature.settings.AddElementActivity::class.java).apply {
                                    putExtra("handle_id", handle.id)
                                    putExtra("gesture", targetGesture)
                                }
                                elementPickerLauncher.launch(intent)
                            }
                            .testTag("option_direct_element"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Element directly",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Directly triggers one specific element or shortcut when this gesture is performed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    showTwoChoiceDialog = false
                    gestureToConfigure = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Change Trigger Dialog
    if (showChangeTriggerDialog && triggerToChange.isNotEmpty()) {
        val availableGestures = HandleGestures.ALL.filter { it == triggerToChange || handle.getActionForGesture(it) == HandleManager.ACTION_NONE }
        var selectedNewGesture by remember { mutableStateOf(availableGestures.firstOrNull { it != triggerToChange } ?: triggerToChange) }

        AlertDialog(
            onDismissRequest = { showChangeTriggerDialog = false },
            title = { Text("Change Gesture for ${gestureLabels[triggerToChange] ?: triggerToChange}") },
            text = {
                Column {
                    if (availableGestures.size <= 1) {
                        Text("No other available gestures.")
                    } else {
                        availableGestures.filter { it != triggerToChange }.forEach { g ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedNewGesture = g }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selectedNewGesture == g, onClick = { selectedNewGesture = g })
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(gestureLabels[g] ?: g)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (selectedNewGesture != triggerToChange) {
                        val currentAct = handle.getActionForGesture(triggerToChange)
                        handleManager.removeGesture(handle.id, triggerToChange)
                        handleManager.configureGesture(handle.id, selectedNewGesture, currentAct)
                        onRefresh()
                    }
                    showChangeTriggerDialog = false
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
}

/**
 * ContainerPageManagementScreen: Dedicated per-container Sidebar Page management screen.
 * Strictly scoped to containerId = HandleManager.getContainerId(handleId, gesture).
 * Allows viewing, adding, deleting, reordering, and selecting the opening face.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerPageManagementScreen(
    containerId: String,
    handleName: String,
    gestureLabel: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val pageManager = remember { com.example.core.PageManager.getInstance(context) }
    var pages by remember { mutableStateOf(pageManager.getPageStack(containerId)) }
    var selectedFaceId by remember { mutableStateOf(pageManager.getSelectedPageId(containerId) ?: pages.firstOrNull()?.pageId ?: "") }
    var showAddPageDialog by remember { mutableStateOf(false) }

    var pageToRename by remember { mutableStateOf<com.example.core.SidebarPage?>(null) }

    fun refreshPages() {
        pages = pageManager.getPageStack(containerId)
        selectedFaceId = pageManager.getSelectedPageId(containerId) ?: pages.firstOrNull()?.pageId ?: ""
    }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Sidebar Pages")
                        Text(
                            "$handleName • $gestureLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("pages_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddPageDialog = true },
                modifier = Modifier.testTag("add_page_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Page")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
                .testTag("container_pages_list")
        ) {
            item {
                Text(
                    "Pages in this Sidebar (${pages.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Horizontal swiping in the Sidebar navigates between these pages. Tap the radio button to select the opening face.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            items(pages, key = { it.pageId }) { page ->
                val isFace = page.pageId == selectedFaceId
                val pageIndex = pages.indexOf(page)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .testTag("page_card_${page.pageId}"),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isFace) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isFace,
                            onClick = {
                                selectedFaceId = page.pageId
                                pageManager.saveSelectedPageId(containerId, page.pageId)
                            },
                            modifier = Modifier.testTag("radio_face_${page.pageId}")
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    page.title,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                if (isFace) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.primary
                                    ) {
                                        Text(
                                            "Opening Face",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                "Type: ${com.example.core.PageTypes.resolveDefaultTitle(page.type)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }

                        // Page actions: Rename, Reorder, Delete
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { pageToRename = page },
                                modifier = Modifier.size(36.dp).testTag("rename_page_${page.pageId}")
                            ) {
                                Text("✎", style = MaterialTheme.typography.bodyLarge)
                            }
                            if (pageIndex > 0) {
                                IconButton(
                                    onClick = {
                                        pageManager.reorderPages(containerId, pageIndex, pageIndex - 1)
                                        refreshPages()
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Text("▲", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            if (pageIndex < pages.size - 1) {
                                IconButton(
                                    onClick = {
                                        pageManager.reorderPages(containerId, pageIndex, pageIndex + 1)
                                        refreshPages()
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Text("▼", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            // Delete button (can delete if more than 1 page)
                            if (pages.size > 1) {
                                IconButton(
                                    onClick = {
                                        pageManager.removePage(containerId, page.pageId)
                                        refreshPages()
                                    },
                                    modifier = Modifier.size(36.dp).testTag("delete_page_${page.pageId}")
                                ) {
                                    Text("✕", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }

    // Rename Page Dialog
    if (pageToRename != null) {
        val targetPage = pageToRename!!
        var updatedTitle by remember { mutableStateOf(targetPage.title) }
        AlertDialog(
            onDismissRequest = { pageToRename = null },
            title = { Text("Rename Page") },
            text = {
                OutlinedTextField(
                    value = updatedTitle,
                    onValueChange = { updatedTitle = it },
                    label = { Text("Page Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("rename_page_input")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (updatedTitle.isNotBlank()) {
                            pageManager.renamePage(containerId, targetPage.pageId, updatedTitle.trim())
                            refreshPages()
                        }
                        pageToRename = null
                    },
                    modifier = Modifier.testTag("dialog_confirm_rename_page")
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { pageToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAddPageDialog) {
        val supportedTypes = listOf(
            com.example.core.PageTypes.HYBRID_GRID to "Home Grid",
            com.example.core.PageTypes.APPS to "Apps",
            com.example.core.PageTypes.WIDGETS_GRID to "Widgets Grid",
            "widget" to "Single Widget",
            com.example.core.PageTypes.CALCULATOR to "Calculator",
            com.example.core.PageTypes.COMPASS to "Compass",
            com.example.core.PageTypes.MEDIA to "Media Player",
            com.example.core.PageTypes.APP_TRACKER to "App Tracker",
            com.example.core.PageTypes.RESOURCES_TRACKER to "Resources Tracker",
            com.example.core.PageTypes.SCHEDULER to "Scheduler",
            com.example.core.PageTypes.NOTIFICATIONS to "Notifications"
        )
        var selectedType by remember { mutableStateOf(supportedTypes.first().first) }

        AlertDialog(
            onDismissRequest = { showAddPageDialog = false },
            title = { Text("Add Sidebar Page") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Select Page Type:", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    supportedTypes.forEach { (typeKey, typeLabel) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedType = typeKey }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedType == typeKey,
                                onClick = { selectedType = typeKey }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(typeLabel, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedEntry = supportedTypes.firstOrNull { it.first == selectedType }
                        val selectedLabel = selectedEntry?.second ?: com.example.core.PageTypes.resolveDefaultTitle(selectedType)
                        val newPageId = "${selectedType}_${System.currentTimeMillis()}"
                        pageManager.addPage(containerId, newPageId, position = -1, pageType = selectedType, title = selectedLabel)
                        refreshPages()
                        showAddPageDialog = false
                    },
                    modifier = Modifier.testTag("dialog_confirm_add_page")
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddPageDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * ReferenceHandleEditScreen: Adapted directly from reference HandleEditScreen.
 * Provides live instant editing of edge position, Y position slider, width slider,
 * height slider, color preset circles + custom hex, and shape selector.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferenceHandleEditScreen(
    handleId: String,
    handleManager: HandleManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var currentHandle by remember { mutableStateOf(handleManager.getHandle(handleId) ?: handleManager.getAllHandles().first()) }

    var edge by remember { mutableStateOf(currentHandle.edge) }
    var yPos by remember { mutableFloatStateOf(currentHandle.positionPercent * 100f) }
    var sizeWidth by remember { mutableFloatStateOf(currentHandle.widthDp.toFloat()) }
    var sizeHeight by remember { mutableFloatStateOf(currentHandle.heightDp.toFloat()) }
    var colorInt by remember { mutableIntStateOf(currentHandle.color) }
    var shape by remember { mutableStateOf(currentHandle.shape) }

    fun syncAndSave(updated: HandleConfig) {
        currentHandle = updated
        handleManager.saveHandle(updated)
        OverlaySyncManager.syncInt(context, "handle_${updated.id}_y", (updated.positionPercent * 100).toInt())
        OverlaySyncManager.syncInt(context, "handle_${updated.id}_width", updated.widthDp)
        OverlaySyncManager.syncInt(context, "handle_${updated.id}_height", updated.heightDp)
        OverlaySyncManager.syncString(context, "handle_${updated.id}_edge", updated.edge.name.lowercase())
    }

    val handleBack = {
        syncAndSave(
            currentHandle.copy(
                edge = edge,
                positionPercent = (yPos / 100f).coerceIn(0.05f, 0.95f),
                widthDp = sizeWidth.toInt(),
                heightDp = sizeHeight.toInt(),
                color = colorInt,
                shape = shape
            )
        )
        onBack()
    }

    BackHandler(onBack = handleBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Handle") },
                navigationIcon = {
                    IconButton(onClick = handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Live responsive visual preview (updates locally/in memory immediately during dragging)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .testTag("handle_preview_card"),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clip(RoundedCornerShape(8.dp))
                    ) {
                        Text(
                            text = if (edge == HandleEdge.LEFT) "← Left Screen Edge" else "Right Screen Edge →",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier
                                .align(if (edge == HandleEdge.LEFT) Alignment.TopStart else Alignment.TopEnd)
                                .padding(8.dp)
                        )

                        val previewHeightDp = (sizeHeight * 0.4f).coerceIn(16f, 120f).dp
                        val previewWidthDp = (sizeWidth * 0.5f).coerceIn(4f, 24f).dp
                        val handleCornerShape = when (shape) {
                            HandleShape.SLANTED_BLOCK -> if (edge == HandleEdge.LEFT) {
                                RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 8.dp, bottomEnd = 2.dp)
                            } else {
                                RoundedCornerShape(topStart = 8.dp, bottomStart = 2.dp, topEnd = 0.dp, bottomEnd = 0.dp)
                            }
                            HandleShape.HALF_OVAL -> if (edge == HandleEdge.LEFT) {
                                RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 16.dp, bottomEnd = 16.dp)
                            } else {
                                RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp, topEnd = 0.dp, bottomEnd = 0.dp)
                            }
                            HandleShape.RECTANGLE -> RoundedCornerShape(2.dp)
                            else -> RoundedCornerShape(4.dp)
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .align(if (edge == HandleEdge.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
                        ) {
                            BoxWithConstraints(modifier = Modifier.fillMaxHeight()) {
                                val availableH = maxHeight - previewHeightDp
                                val topOffset = availableH * (yPos / 100f).coerceIn(0.05f, 0.95f)
                                Box(
                                    modifier = Modifier
                                        .offset(y = topOffset)
                                        .width(previewWidthDp)
                                        .height(previewHeightDp)
                                        .background(Color(colorInt), handleCornerShape)
                                        .border(
                                            width = 1.dp,
                                            color = Color.White.copy(alpha = 0.3f),
                                            shape = handleCornerShape
                                        )
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Appearance (Applies Instantly)", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))

            Text("Edge Position:")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(HandleEdge.LEFT, HandleEdge.RIGHT).forEach { e ->
                    FilterChip(
                        selected = edge == e,
                        onClick = {
                            edge = e
                            syncAndSave(currentHandle.copy(edge = e))
                        },
                        label = { Text(if (e == HandleEdge.LEFT) "Left" else "Right") }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            Text("Y Position: ${yPos.toInt()}%")
            Slider(
                value = yPos,
                onValueChange = {
                    yPos = it
                    currentHandle = currentHandle.copy(positionPercent = (it / 100f).coerceIn(0.05f, 0.95f))
                },
                onValueChangeFinished = {
                    syncAndSave(currentHandle.copy(positionPercent = (yPos / 100f).coerceIn(0.05f, 0.95f)))
                },
                valueRange = 5f..95f,
                modifier = Modifier.testTag("handle_y_pos_slider")
            )
            Spacer(modifier = Modifier.height(8.dp))

            Text("Width (Thickness): ${sizeWidth.toInt()}dp")
            Slider(
                value = sizeWidth,
                onValueChange = {
                    sizeWidth = it
                    currentHandle = currentHandle.copy(widthDp = it.toInt())
                },
                onValueChangeFinished = {
                    syncAndSave(currentHandle.copy(widthDp = sizeWidth.toInt()))
                },
                valueRange = 2f..50f,
                modifier = Modifier.testTag("handle_width_slider")
            )
            Spacer(modifier = Modifier.height(8.dp))

            Text("Height (Length): ${sizeHeight.toInt()}dp")
            Slider(
                value = sizeHeight,
                onValueChange = {
                    sizeHeight = it
                    currentHandle = currentHandle.copy(heightDp = it.toInt())
                },
                onValueChangeFinished = {
                    syncAndSave(currentHandle.copy(heightDp = sizeHeight.toInt()))
                },
                valueRange = 20f..300f,
                modifier = Modifier.testTag("handle_height_slider")
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text("Handle Color:")
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val presetColors = listOf(
                    "#242962ff" to "Translucent Blue",
                    "#44102d42" to "Slate Blue",
                    "#88000000" to "Translucent Black",
                    "#44ffffff" to "Translucent White",
                    "#66e53935" to "Red",
                    "#6643a047" to "Green",
                    "#66fb8c00" to "Orange",
                    "#668e24aa" to "Purple"
                )

                presetColors.forEach { (hex, name) ->
                    val parsedColor = try { AndroidColor.parseColor(hex) } catch (_: Exception) { AndroidColor.BLUE }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color(parsedColor), CircleShape)
                            .border(
                                width = if (colorInt == parsedColor) 3.dp else 1.dp,
                                color = if (colorInt == parsedColor) MaterialTheme.colorScheme.primary else Color.Gray,
                                shape = CircleShape
                            )
                            .clickable {
                                colorInt = parsedColor
                                syncAndSave(currentHandle.copy(color = parsedColor))
                            }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            Text("Handle Shape:")
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    HandleShape.SLANTED_BLOCK to "Slanted Block",
                    HandleShape.HALF_OVAL to "Half Oval",
                    HandleShape.RECTANGLE to "Rectangle"
                ).forEach { (sh, label) ->
                    FilterChip(
                        selected = shape == sh,
                        onClick = {
                            shape = sh
                            syncAndSave(currentHandle.copy(shape = sh))
                        },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
