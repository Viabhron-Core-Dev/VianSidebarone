package com.example.feature.settings.handle

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.*
import com.example.feature.welcome.WelcomeActivity
import com.example.util.HandleEdge
import com.example.util.HandleShape

/**
 * HandleSettingsScreen: Primary Settings interface resident strictly in the :heavy process.
 * Manages floating Handles, multi-gesture bindings, and independent Container identities.
 *
 * Strict Architectural Rule:
 * Handle -> Gesture -> Independent Container -> Page -> Element
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandleSettingsScreen(
    onNavigateBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val handleManager = remember { HandleManager.getInstance(context) }

    var handleList by remember { mutableStateOf(handleManager.getAllHandles()) }
    var selectedHandleId by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    // Reload helper
    fun refreshHandles() {
        handleList = handleManager.getAllHandles()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (selectedHandleId == null) "Handles & Gestures" else "Configure Handle",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    if (selectedHandleId != null) {
                        IconButton(onClick = {
                            selectedHandleId = null
                            refreshHandles()
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to Handles List"
                            )
                        }
                    } else if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
                actions = {
                    if (selectedHandleId == null) {
                        TextButton(onClick = {
                            val intent = Intent(context, WelcomeActivity::class.java)
                            context.startActivity(intent)
                        }) {
                            Text("Permissions")
                        }
                        IconButton(onClick = { showAddDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add New Handle"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    titleContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val currentSelectedId = selectedHandleId
            if (currentSelectedId == null) {
                HandleListOverview(
                    handles = handleList,
                    onSelectHandle = { handleId ->
                        selectedHandleId = handleId
                    },
                    onToggleEnabled = { handleId, enabled ->
                        handleManager.setHandleEnabled(handleId, enabled)
                        refreshHandles()
                    },
                    onDeleteHandle = { handleId ->
                        handleManager.deleteHandle(handleId)
                        refreshHandles()
                    },
                    onAddHandleClicked = {
                        showAddDialog = true
                    }
                )
            } else {
                val currentConfig = handleManager.getHandle(currentSelectedId)
                if (currentConfig != null) {
                    HandleDetailEditor(
                        initialConfig = currentConfig,
                        handleManager = handleManager,
                        onSaved = {
                            refreshHandles()
                        },
                        onBack = {
                            selectedHandleId = null
                            refreshHandles()
                        }
                    )
                } else {
                    selectedHandleId = null
                }
            }

            if (showAddDialog) {
                AddHandleDialog(
                    onDismiss = { showAddDialog = false },
                    onConfirm = { name, edge ->
                        showAddDialog = false
                        val newHandle = handleManager.createHandle(name = name, edge = edge)
                        refreshHandles()
                        selectedHandleId = newHandle.id
                    }
                )
            }
        }
    }
}

/**
 * HandleListOverview: Displays all configured Handles with state toggles, gesture counts,
 * container previews, and options to edit or delete.
 */
@Composable
private fun HandleListOverview(
    handles: List<HandleConfig>,
    onSelectHandle: (String) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onDeleteHandle: (String) -> Unit,
    onAddHandleClicked: () -> Unit
) {
    val context = LocalContext.current
    var handleToDelete by remember { mutableStateOf<HandleConfig?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "i",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Hierarchy & Isolation",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Handle → Gesture → Independent Container → Page → Element\n\nEach handle supports multiple distinct gestures. Every gesture targeting a sidebar resolves to its own isolated container identity with no stack or element bleed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Configured Handles (${handles.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Button(
                    onClick = onAddHandleClicked,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Handle", fontSize = 13.sp)
                }
            }
        }

        items(handles, key = { it.id }) { handle ->
            HandleCard(
                handle = handle,
                canDelete = handles.size > 1,
                onSelect = { onSelectHandle(handle.id) },
                onToggleEnabled = { enabled -> onToggleEnabled(handle.id, enabled) },
                onDelete = { handleToDelete = handle }
            )
        }
    }

    handleToDelete?.let { handle ->
        AlertDialog(
            onDismissRequest = { handleToDelete = null },
            title = { Text("Delete Handle?") },
            text = {
                Text(
                    "Are you sure you want to delete '${handle.name}' (${handle.id})?\n\n" +
                    "All gesture bindings and container stacks under this handle will be permanently removed. Other handles and containers will remain completely untouched."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDelete = handleToDelete
                        handleToDelete = null
                        if (toDelete != null) {
                            onDeleteHandle(toDelete.id)
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { handleToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * HandleCard: Summary card showing handle appearance, active edge, gestures, and quick actions.
 */
@Composable
private fun HandleCard(
    handle: HandleConfig,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (handle.enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Mini visual representation of handle
                    Box(
                        modifier = Modifier
                            .width(8.dp)
                            .height(36.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(handle.color).copy(alpha = (handle.alphaPercent / 100f).coerceIn(0.2f, 1f)))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = handle.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Text(
                            text = "${handle.id} • ${handle.edge.name} edge",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Switch(
                    checked = handle.enabled,
                    onCheckedChange = onToggleEnabled
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(10.dp))

            // Gestures summary
            val configuredGestures = remember(handle) {
                HandleGestures.ALL.mapNotNull { g ->
                    val action = handle.getActionForGesture(g)
                    if (action != HandleManager.ACTION_NONE) {
                        g to action
                    } else null
                }
            }

            if (configuredGestures.isEmpty()) {
                Text(
                    text = "No gestures configured yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${configuredGestures.size} active gesture(s): " +
                                configuredGestures.joinToString(", ") { formatGestureName(it.first) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (canDelete) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete", fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                FilledTonalButton(
                    onClick = onSelect,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit & Gestures", fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * HandleDetailEditor: Detailed configuration editor for a single Handle, featuring Appearance & Positioning
 * tabs alongside Gestures & Container mappings.
 */
@Composable
private fun HandleDetailEditor(
    initialConfig: HandleConfig,
    handleManager: HandleManager,
    onSaved: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    var config by remember(initialConfig.id) { mutableStateOf(initialConfig) }
    var hasUnsavedChanges by remember { mutableStateOf(false) }

    // Gestures state
    var gesturesInfo by remember(config.id, hasUnsavedChanges) {
        mutableStateOf(handleManager.getGesturesForHandle(config.id))
    }

    fun updateConfig(newConfig: HandleConfig) {
        config = newConfig
        hasUnsavedChanges = true
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Appearance & Edge") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Gestures & Containers") }
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (selectedTab == 0) {
                HandleAppearanceTab(
                    config = config,
                    onConfigChange = { updateConfig(it) }
                )
            } else {
                HandleGesturesTab(
                    handleId = config.id,
                    config = config,
                    gesturesInfo = gesturesInfo,
                    onGestureActionChanged = { gesture, newAction ->
                        val updated = config.withGestureAction(gesture, newAction)
                        updateConfig(updated)
                        handleManager.configureGesture(config.id, gesture, newAction)
                        gesturesInfo = handleManager.getGesturesForHandle(config.id)
                    },
                    onRemoveGesture = { gesture, cleanContainer ->
                        val updated = config.withGestureAction(gesture, HandleManager.ACTION_NONE)
                        updateConfig(updated)
                        handleManager.removeGesture(config.id, gesture, cleanContainer)
                        gesturesInfo = handleManager.getGesturesForHandle(config.id)
                    },
                    onResetContainerPages = { containerId ->
                        handleManager.savePagesForContainer(containerId, listOf(HandleManager.DEFAULT_PAGE_HYBRID))
                        gesturesInfo = handleManager.getGesturesForHandle(config.id)
                    }
                )
            }
        }

        // Bottom action bar
        Surface(
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onBack) {
                    Text("Back to Handles")
                }

                Button(
                    onClick = {
                        handleManager.saveHandle(config)
                        hasUnsavedChanges = false
                        onSaved()
                    }
                ) {
                    Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save Configuration")
                }
            }
        }
    }
}

/**
 * HandleAppearanceTab: Configures name, edge, position, dimensions, shape, color, and alpha
 * with an interactive live visual preview.
 */
@Composable
private fun HandleAppearanceTab(
    config: HandleConfig,
    onConfigChange: (HandleConfig) -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Live Screen Mockup Preview
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                Text(
                    text = "Live Trigger Preview (${config.edge.name})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.TopCenter)
                )

                // Mock phone canvas
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 24.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.05f))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                ) {
                    // Simulated Handle View
                    val isRight = config.edge == HandleEdge.RIGHT
                    val alignment = if (isRight) Alignment.CenterEnd else Alignment.CenterStart
                    val heightRatio = (config.heightDp / 240f).coerceIn(0.2f, 0.85f)
                    val widthRatio = (config.widthDp / 32f).coerceIn(0.2f, 1f)

                    Box(
                        modifier = Modifier
                            .align(alignment)
                            .fillMaxHeight(heightRatio)
                            .width((14 * widthRatio).dp)
                            .clip(
                                when (config.shape) {
                                    HandleShape.ROUNDED_RECT -> RoundedCornerShape(6.dp)
                                    HandleShape.HALF_OVAL -> RoundedCornerShape(if (isRight) 16.dp else 0.dp, if (isRight) 0.dp else 16.dp, if (isRight) 0.dp else 16.dp, if (isRight) 16.dp else 0.dp)
                                    HandleShape.PILL -> RoundedCornerShape(50)
                                    HandleShape.LINE -> RoundedCornerShape(1.dp)
                                    HandleShape.RECTANGLE -> RoundedCornerShape(0.dp)
                                    else -> RoundedCornerShape(4.dp) // Slanted block
                                }
                            )
                            .background(
                                Color(config.color).copy(alpha = (config.alphaPercent / 100f).coerceIn(0.1f, 1f))
                            )
                    )
                }
            }
        }

        // Name
        OutlinedTextField(
            value = config.name,
            onValueChange = { onConfigChange(config.copy(name = it)) },
            label = { Text("Handle Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Edge Selector
        Text(text = "Edge Placement", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = config.edge == HandleEdge.RIGHT,
                onClick = { onConfigChange(config.copy(edge = HandleEdge.RIGHT)) },
                label = { Text("Right Edge") },
                leadingIcon = if (config.edge == HandleEdge.RIGHT) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = config.edge == HandleEdge.LEFT,
                onClick = { onConfigChange(config.copy(edge = HandleEdge.LEFT)) },
                label = { Text("Left Edge") },
                leadingIcon = if (config.edge == HandleEdge.LEFT) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
                modifier = Modifier.weight(1f)
            )
        }

        // Vertical Position Slider
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Vertical Position", style = MaterialTheme.typography.labelLarge)
                Text(text = "${(config.positionPercent * 100).toInt()}%", fontWeight = FontWeight.Bold)
            }
            Slider(
                value = config.positionPercent,
                onValueChange = { onConfigChange(config.copy(positionPercent = it)) },
                valueRange = 0.05f..0.95f
            )
        }

        // Height & Width Sliders
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Handle Height", style = MaterialTheme.typography.labelLarge)
                Text(text = "${config.heightDp} dp", fontWeight = FontWeight.Bold)
            }
            Slider(
                value = config.heightDp.toFloat(),
                onValueChange = { onConfigChange(config.copy(heightDp = it.toInt())) },
                valueRange = 40f..240f
            )
        }

        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Handle Width", style = MaterialTheme.typography.labelLarge)
                Text(text = "${config.widthDp} dp", fontWeight = FontWeight.Bold)
            }
            Slider(
                value = config.widthDp.toFloat(),
                onValueChange = { onConfigChange(config.copy(widthDp = it.toInt())) },
                valueRange = 6f..28f
            )
        }

        // Shape Selector
        Text(text = "Handle Shape", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val shapes = listOf(
                HandleShape.SLANTED_BLOCK to "Slanted",
                HandleShape.ROUNDED_RECT to "Rounded",
                HandleShape.HALF_OVAL to "Oval",
                HandleShape.PILL to "Pill",
                HandleShape.RECTANGLE to "Rect"
            )
            shapes.forEach { (shape, label) ->
                FilterChip(
                    selected = config.shape == shape,
                    onClick = { onConfigChange(config.copy(shape = shape)) },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }

        // Color Presets & Alpha Slider
        Text(text = "Color Palette", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        val modernColors = listOf(
            "#242962ff" to "Indigo",
            "#ff3b30" to "Crimson",
            "#34c759" to "Emerald",
            "#ff9500" to "Sunset",
            "#af52de" to "Purple",
            "#607d8b" to "Slate",
            "#009688" to "Teal",
            "#2196f3" to "Blue"
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            modernColors.forEach { (hex, _) ->
                val parsed = AndroidColor.parseColor(hex)
                val isSelected = config.color == parsed
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(parsed))
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = CircleShape
                        )
                        .clickable { onConfigChange(config.copy(color = parsed)) }
                )
            }
        }

        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Transparency / Opacity", style = MaterialTheme.typography.labelLarge)
                Text(text = "${config.alphaPercent}%", fontWeight = FontWeight.Bold)
            }
            Slider(
                value = config.alphaPercent.toFloat(),
                onValueChange = { onConfigChange(config.copy(alphaPercent = it.toInt())) },
                valueRange = 5f..100f
            )
        }
    }
}

/**
 * HandleGesturesTab: Manages gestures for this handle and inspects the isolated independent container.
 */
@Composable
private fun HandleGesturesTab(
    handleId: String,
    config: HandleConfig,
    gesturesInfo: List<HandleGestureInfo>,
    onGestureActionChanged: (String, String) -> Unit,
    onRemoveGesture: (String, Boolean) -> Unit,
    onResetContainerPages: (String) -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Banner reminding user of isolation architecture
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "❖",
                        color = MaterialTheme.colorScheme.onSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Independent Container Per Gesture",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Every gesture configured to open sidebar resolves to its own distinct container ID: ${handleId}_<gesture>. Pages and elements are completely isolated.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        Text(
            text = "Configured Gestures for ${config.name}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        // List all gestures
        gesturesInfo.forEach { info ->
            GestureConfigCard(
                info = info,
                onActionSelected = { newAction ->
                    onGestureActionChanged(info.gesture, newAction)
                },
                onDisable = { cleanData ->
                    onRemoveGesture(info.gesture, cleanData)
                },
                onResetPages = {
                    onResetContainerPages(info.containerId)
                }
            )
        }
    }
}

/**
 * GestureConfigCard: Individual gesture configuration tile displaying current binding,
 * container identity inspection, and reset controls.
 */
@Composable
private fun GestureConfigCard(
    info: HandleGestureInfo,
    onActionSelected: (String) -> Unit,
    onDisable: (Boolean) -> Unit,
    onResetPages: () -> Unit
) {
    var showActionPicker by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (info.isEnabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                if (info.isEnabled) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = getGestureSymbol(info.gesture),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = if (info.isEnabled) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = formatGestureName(info.gesture),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Action: ${formatActionName(info.action)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (info.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                FilledTonalButton(
                    onClick = { showActionPicker = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Change", fontSize = 13.sp)
                }
            }

            // If action is Open Sidebar, display container details
            AnimatedVisibility(visible = info.isContainerTarget) {
                Column(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "Container Target Identity",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = info.containerId,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Storage Key: handle_${info.containerId}_pages\nActive Pages: ${info.pageCount} page(s) isolated",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { showResetConfirm = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Reset Stack to Default", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    // Action Picker Dialog
    if (showActionPicker) {
        val actions = listOf(
            HandleManager.ACTION_OPEN_SIDEBAR to "Open Sidebar Container",
            HandleManager.ACTION_MOVE_HANDLE to "Move Handle Position",
            "torch" to "Toggle Flashlight / Torch",
            "screenshot" to "Capture Screenshot",
            "volume_panel" to "Show Volume Panel",
            "lock_screen" to "Lock Screen",
            HandleManager.ACTION_NONE to "None (Disabled)"
        )

        AlertDialog(
            onDismissRequest = { showActionPicker = false },
            title = { Text("Assign Action for ${formatGestureName(info.gesture)}") },
            text = {
                Column {
                    actions.forEach { (key, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showActionPicker = false
                                    onActionSelected(key)
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = info.action == key,
                                onClick = {
                                    showActionPicker = false
                                    onActionSelected(key)
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showActionPicker = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Reset Pages Confirmation
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset Container Stack?") },
            text = {
                Text("This will reset the page stack for container '${info.containerId}' back to the default Hybrid Grid page. Placed elements on other containers remain untouched.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    onResetPages()
                }) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * AddHandleDialog: Dialog allowing user to create a new handle with custom name and edge.
 */
@Composable
private fun AddHandleDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, edge: HandleEdge) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedEdge by remember { mutableStateOf(HandleEdge.RIGHT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Floating Handle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Handle Name (Optional)") },
                    placeholder = { Text("e.g. Left Sidebar Handle") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(text = "Edge Placement", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedEdge == HandleEdge.RIGHT,
                        onClick = { selectedEdge = HandleEdge.RIGHT },
                        label = { Text("Right Edge") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedEdge == HandleEdge.LEFT,
                        onClick = { selectedEdge = HandleEdge.LEFT },
                        label = { Text("Left Edge") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(
                    name.ifBlank { "Handle" },
                    selectedEdge
                )
            }) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// Helpers
private fun formatGestureName(gesture: String): String = when (gesture) {
    HandleGestures.SWIPE_LEFT -> "Swipe Left"
    HandleGestures.SWIPE_RIGHT -> "Swipe Right"
    HandleGestures.SWIPE_UP -> "Swipe Up"
    HandleGestures.SWIPE_DOWN -> "Swipe Down"
    HandleGestures.TAP -> "Single Tap"
    HandleGestures.DOUBLE_TAP -> "Double Tap"
    HandleGestures.LONG_PRESS -> "Long Press"
    else -> gesture.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun formatActionName(action: String): String = when (action) {
    HandleManager.ACTION_OPEN_SIDEBAR -> "Open Sidebar Container"
    HandleManager.ACTION_MOVE_HANDLE -> "Move Handle"
    HandleManager.ACTION_NONE -> "None"
    "torch" -> "Torch / Flashlight"
    "screenshot" -> "Screenshot"
    "volume_panel" -> "Volume Panel"
    "lock_screen" -> "Lock Screen"
    else -> action.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun getGestureSymbol(gesture: String) = when (gesture) {
    HandleGestures.SWIPE_LEFT -> "←"
    HandleGestures.SWIPE_RIGHT -> "→"
    HandleGestures.SWIPE_UP -> "↑"
    HandleGestures.SWIPE_DOWN -> "↓"
    HandleGestures.TAP -> "●"
    HandleGestures.DOUBLE_TAP -> "●●"
    HandleGestures.LONG_PRESS -> "■"
    else -> "◆"
}
