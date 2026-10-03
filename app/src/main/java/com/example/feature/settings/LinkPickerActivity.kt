package com.example.feature.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.feature.element.CustomTabLauncher
import com.example.feature.element.CustomTabProviderInfo
import com.example.feature.sidebar.ElementMetadata
import com.example.feature.sidebar.ElementMetadataStore
import java.util.UUID

/**
 * LinkPickerActivity: Dedicated destination for managing, creating, editing,
 * and selecting Link Elements within the Sidebar / Container ecosystem.
 *
 * Enforces:
 * - HTTPS URLs only.
 * - Per-link Custom Tab browser provider selection.
 * - Tap opens link in Android Custom Tab.
 * - Long press or action menu exposes standard CRUD actions (Select, Edit, Delete, Open).
 * - Optional and extensible Account configuration.
 */
class LinkPickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LinkPickerScreen(
                        onLinkSelected = { elementId ->
                            val resultIntent = Intent().apply {
                                putExtra("ELEMENT_ID", elementId)
                            }
                            setResult(Activity.RESULT_OK, resultIntent)
                            finish()
                        },
                        onCancel = {
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkPickerScreen(
    onLinkSelected: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var linksList by remember {
        mutableStateOf(ElementMetadataStore.getAllSavedLinks(context))
    }
    val providers = remember {
        CustomTabLauncher.getCustomTabProviders(context)
    }

    var showAddEditDialog by remember { mutableStateOf(false) }
    var editingMeta by remember { mutableStateOf<ElementMetadata?>(null) }
    var selectedForActions by remember { mutableStateOf<ElementMetadata?>(null) }

    fun refreshLinks() {
        linksList = ElementMetadataStore.getAllSavedLinks(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Links") },
                navigationIcon = {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag("link_picker_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                modifier = Modifier.testTag("link_picker_topbar")
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingMeta = null
                    showAddEditDialog = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("fab_add_link")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Link")
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (linksList.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                        .testTag("empty_links_container"),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painter = painterResource(android.R.drawable.ic_menu_set_as),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No saved links yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap '+' to add an HTTPS link. You can assign a specific Custom Tab browser for each link.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("links_grid")
                ) {
                    items(linksList, key = { it.id }) { item ->
                        LinkGridItem(
                            item = item,
                            providers = providers,
                            onTap = {
                                // Tap = Open link via Custom Tab
                                CustomTabLauncher.openLink(context, item.target, item.browserPackage)
                            },
                            onOpenActions = {
                                selectedForActions = item
                            },
                            onSelectForSidebar = {
                                onLinkSelected(item.id)
                            }
                        )
                    }
                }
            }
        }
    }

    // Add / Edit Modal Dialog
    if (showAddEditDialog) {
        AddEditLinkDialog(
            initialMeta = editingMeta,
            providers = providers,
            onDismiss = {
                showAddEditDialog = false
                editingMeta = null
            },
            onSave = { label, url, browserPkg, account ->
                val uuid = editingMeta?.id?.substringAfter("link:")?.substringBefore(":")
                    ?: UUID.randomUUID().toString()
                ElementMetadataStore.saveLinkElement(
                    context = context,
                    uuid = uuid,
                    url = url,
                    label = label,
                    elementId = "link:$uuid",
                    browserPackage = browserPkg,
                    account = account
                )
                refreshLinks()
                showAddEditDialog = false
                editingMeta = null
                Toast.makeText(context, "Link saved", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Long Press / Options Action Sheet Dialog
    selectedForActions?.let { item ->
        AlertDialog(
            onDismissRequest = { selectedForActions = null },
            title = {
                Text(
                    text = item.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = item.target,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    ListItem(
                        headlineContent = { Text("Select for Sidebar") },
                        supportingContent = { Text("Place this link onto current page") },
                        leadingContent = {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val targetId = item.id
                                selectedForActions = null
                                onLinkSelected(targetId)
                            }
                            .testTag("action_select_sidebar")
                    )

                    ListItem(
                        headlineContent = { Text("Open in Custom Tab") },
                        supportingContent = { Text("Launch URL now") },
                        leadingContent = {
                            Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedForActions = null
                                CustomTabLauncher.openLink(context, item.target, item.browserPackage)
                            }
                            .testTag("action_open_tab")
                    )

                    ListItem(
                        headlineContent = { Text("Edit") },
                        supportingContent = { Text("Modify URL, browser, or account") },
                        leadingContent = {
                            Icon(Icons.Default.Edit, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val target = item
                                selectedForActions = null
                                editingMeta = target
                                showAddEditDialog = true
                            }
                            .testTag("action_edit_link")
                    )

                    ListItem(
                        headlineContent = { Text("Delete") },
                        supportingContent = { Text("Remove from saved links") },
                        leadingContent = {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val uuid = item.id.substringAfter("link:").substringBefore(":")
                                ElementMetadataStore.removeSavedLinkId(context, uuid)
                                refreshLinks()
                                selectedForActions = null
                                Toast.makeText(context, "Link deleted", Toast.LENGTH_SHORT).show()
                            }
                            .testTag("action_delete_link")
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedForActions = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun LinkGridItem(
    item: ElementMetadata,
    providers: List<CustomTabProviderInfo>,
    onTap: () -> Unit,
    onOpenActions: () -> Unit,
    onSelectForSidebar: () -> Unit
) {
    val browserLabel = remember(item.browserPackage, providers) {
        if (item.browserPackage.isNullOrBlank()) {
            "Default Browser"
        } else {
            providers.firstOrNull { it.packageName == item.browserPackage }?.label ?: (item.browserPackage ?: "Default Browser")
        }
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("link_item_${item.id.replace(":", "_")}")
            .clickable(onClick = onTap)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(android.R.drawable.ic_menu_set_as),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )

                Row {
                    IconButton(
                        onClick = onSelectForSidebar,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("btn_select_sidebar_${item.id.replace(":", "_")}")
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Select for Sidebar",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = onOpenActions,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("btn_menu_${item.id.replace(":", "_")}")
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More actions",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = item.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = item.target,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Browser Provider Tag
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Text(
                    text = browserLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            // Optional Account Tag
            if (!item.account.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(
                        text = "Account: ${item.account}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AddEditLinkDialog(
    initialMeta: ElementMetadata?,
    providers: List<CustomTabProviderInfo>,
    onDismiss: () -> Unit,
    onSave: (label: String, url: String, browserPackage: String?, account: String?) -> Unit
) {
    var label by remember { mutableStateOf(initialMeta?.label ?: "") }
    var url by remember { mutableStateOf(initialMeta?.target ?: "https://") }
    var selectedBrowserPackage by remember { mutableStateOf(initialMeta?.browserPackage) }
    var account by remember { mutableStateOf(initialMeta?.account ?: "") }
    var showBrowserDialog by remember { mutableStateOf(false) }

    val isHttpsValid = remember(url) {
        CustomTabLauncher.isValidHttpsUrl(url)
    }

    val selectedBrowserLabel = remember(selectedBrowserPackage, providers) {
        if (selectedBrowserPackage.isNullOrBlank()) {
            "System Default Browser"
        } else {
            providers.firstOrNull { it.packageName == selectedBrowserPackage }?.label ?: (selectedBrowserPackage ?: "System Default Browser")
        }
    }

    if (showBrowserDialog) {
        AlertDialog(
            onDismissRequest = { showBrowserDialog = false },
            title = { Text("Select Custom Tab Browser") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedBrowserPackage = null
                                showBrowserDialog = false
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedBrowserPackage == null,
                            onClick = {
                                selectedBrowserPackage = null
                                showBrowserDialog = false
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("System Default Browser", fontWeight = FontWeight.Medium)
                    }

                    HorizontalDivider()

                    providers.forEach { provider ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedBrowserPackage = provider.packageName
                                    showBrowserDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedBrowserPackage == provider.packageName,
                                onClick = {
                                    selectedBrowserPackage = provider.packageName
                                    showBrowserDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(provider.label, fontWeight = FontWeight.Medium)
                                Text(
                                    provider.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBrowserDialog = false }) {
                    Text("Done")
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initialMeta == null) "Add Link" else "Edit Link")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Title / Label
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Title") },
                    placeholder = { Text("e.g. Documentation") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_link_title")
                )

                // HTTPS URL
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("HTTPS URL") },
                    placeholder = { Text("https://example.com") },
                    singleLine = true,
                    isError = url.isNotBlank() && !isHttpsValid,
                    supportingText = {
                        if (url.isNotBlank() && !isHttpsValid) {
                            Text(
                                "Must start with https:// and have a valid domain",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 11.sp
                            )
                        } else {
                            Text("Secure HTTPS protocol required", fontSize = 11.sp)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_link_url")
                )

                // Browser Provider Selector Card
                OutlinedCard(
                    onClick = { showBrowserDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dropdown_link_browser")
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Custom Tab Browser",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = selectedBrowserLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Optional Account Configuration
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    label = { Text("Account Profile (Optional)") },
                    placeholder = { Text("e.g. Work or Personal") },
                    singleLine = true,
                    supportingText = {
                        Text("For labeling multi-account workflows", fontSize = 11.sp)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_link_account")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalLabel = if (label.isNotBlank()) label.trim() else "Link"
                    val finalAccount = if (account.isNotBlank()) account.trim() else null
                    onSave(finalLabel, url.trim(), selectedBrowserPackage, finalAccount)
                },
                enabled = isHttpsValid,
                modifier = Modifier.testTag("btn_save_link")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("btn_cancel_link")
            ) {
                Text("Cancel")
            }
        }
    )
}
