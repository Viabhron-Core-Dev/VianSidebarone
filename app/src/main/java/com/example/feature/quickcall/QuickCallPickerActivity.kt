package com.example.feature.quickcall

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.example.feature.element.ElementPlacementHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated contact-selection screen for adding **Quick Calls** elements to the Sidebar,
 * Hybrid Grid, or Floating Trigger.
 *
 * Permission & Refresh discipline:
 * - Requests [Manifest.permission.READ_CONTACTS] ONLY when opened if missing; never re-requests
 *   repeatedly once granted.
 * - Queries Android Contacts (`ContactsContract.CommonDataKinds.Phone`) ONLY on open (when
 *   permission is granted) or when the user explicitly taps Refresh.
 * - Never queries `CallLog`, never registers ContentObservers, and never polls in the background.
 * - Saves a durable UUID-backed snapshot (`quick_call:<uuid>`) in [QuickCallStore].
 */
class QuickCallPickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pageId = intent.getStringExtra("PAGE_ID")
        val containerId = intent.getStringExtra("CONTAINER_ID") ?: "sidebar"
        val isHybridGrid = intent.getBooleanExtra("IS_HYBRID_GRID", false)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    QuickCallPickerScreen(
                        activity = this@QuickCallPickerActivity,
                        onContactNumberChosen = { contact, entry ->
                            val record = QuickCallStore.getInstance(this@QuickCallPickerActivity)
                                .createFromContact(
                                    displayName = contact.displayName,
                                    phoneNumber = entry.number,
                                    contactLookupKey = contact.lookupKey
                                )
                            if (record == null) {
                                Toast.makeText(
                                    this@QuickCallPickerActivity,
                                    "Could not save Quick Call: invalid phone number",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@QuickCallPickerScreen
                            }

                            if (isHybridGrid && !pageId.isNullOrEmpty()) {
                                ElementPlacementHelper.addElementToHybridGrid(
                                    context = this@QuickCallPickerActivity,
                                    pageId = pageId,
                                    elementId = record.elementId,
                                    containerId = containerId
                                )
                                Toast.makeText(
                                    this@QuickCallPickerActivity,
                                    "Added Quick Call: ${record.displayName}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                val resultIntent = Intent().apply {
                                    putExtra("ELEMENT_ID", record.elementId)
                                    putExtra("DISPLAY_NAME", record.displayName)
                                    putExtra("CONTAINER_ID", containerId)
                                }
                                setResult(Activity.RESULT_OK, resultIntent)
                                finish()
                            }
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
private fun QuickCallPickerScreen(
    activity: ComponentActivity,
    onContactNumberChosen: (DeviceContactGroup, ContactPhoneEntry) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember {
        mutableStateOf(QuickCallContactsLoader.hasContactsPermission(context))
    }
    var hasRequestedOnce by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf<List<DeviceContactGroup>>(emptyList()) }
    var multiNumberContact by remember { mutableStateOf<DeviceContactGroup?>(null) }

    fun loadContactsOnDemand() {
        if (!QuickCallContactsLoader.hasContactsPermission(context)) {
            hasPermission = false
            contacts = emptyList()
            return
        }
        hasPermission = true
        scope.launch {
            isLoading = true
            val loaded = withContext(Dispatchers.IO) {
                QuickCallContactsLoader.loadDeviceContacts(context)
            }
            contacts = loaded
            isLoading = false
        }
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) {
            loadContactsOnDemand()
        }
    }

    LaunchedEffect(Unit) {
        if (QuickCallContactsLoader.hasContactsPermission(context)) {
            hasPermission = true
            loadContactsOnDemand()
        } else if (!hasRequestedOnce) {
            hasRequestedOnce = true
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    val filteredContacts = remember(contacts, searchQuery) {
        QuickCallContactsLoader.filterContacts(contacts, searchQuery)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quick Calls") },
                navigationIcon = {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag("quick_call_picker_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (hasPermission) {
                        IconButton(
                            onClick = { loadContactsOnDemand() },
                            enabled = !isLoading,
                            modifier = Modifier.testTag("quick_call_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh contacts"
                            )
                        }
                    }
                },
                modifier = Modifier.testTag("quick_call_picker_topbar")
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!hasPermission) {
                val permanentlyDenied = hasRequestedOnce && !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.READ_CONTACTS
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                        .testTag("quick_call_permission_denied_state"),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Contacts permission needed",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Allow access to saved contacts to pick a contact for Quick Calls. Existing Quick Calls continue to work without Contacts access.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = {
                            if (permanentlyDenied) {
                                val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                try {
                                    context.startActivity(settingsIntent)
                                } catch (_: Throwable) {}
                            } else {
                                hasRequestedOnce = true
                                contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                            }
                        },
                        modifier = Modifier.testTag("quick_call_grant_contacts_button")
                    ) {
                        Text(if (permanentlyDenied) "Open App Settings" else "Allow Contacts")
                    }
                }
            } else {
                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search saved contacts or numbers") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.testTag("quick_call_clear_search_button")
                            ) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .testTag("quick_call_search_input")
                )

                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("quick_call_loading_indicator"),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    filteredContacts.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp)
                                .testTag("quick_call_empty_state"),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (contacts.isEmpty()) "No saved contacts with phone numbers" else "No matching contacts",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (contacts.isEmpty()) {
                                    "Add a contact with a valid phone number in your Contacts app, then tap Refresh."
                                } else {
                                    "Try another name or phone number."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("quick_call_contacts_list"),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredContacts, key = { it.lookupKey }) { contact ->
                                QuickCallContactRow(
                                    contact = contact,
                                    onClick = {
                                        if (contact.numbers.size == 1) {
                                            onContactNumberChosen(contact, contact.numbers.first())
                                        } else {
                                            multiNumberContact = contact
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Multi-number selection dialog when a contact has more than one valid number
    multiNumberContact?.let { contact ->
        AlertDialog(
            onDismissRequest = { multiNumberContact = null },
            title = {
                Text(
                    text = contact.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Select a phone number for this Quick Call:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    contact.numbers.forEachIndexed { index, entry ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("quick_call_number_option_$index")
                                .clickable {
                                    val chosen = contact
                                    multiNumberContact = null
                                    onContactNumberChosen(chosen, entry)
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Call,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.number,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = entry.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { multiNumberContact = null },
                    modifier = Modifier.testTag("quick_call_number_dialog_cancel")
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun QuickCallContactRow(
    contact: DeviceContactGroup,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("quick_call_contact_${contact.lookupKey.replace(":", "_")}")
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                val primary = contact.numbers.first()
                val subtitle = if (contact.numbers.size == 1) {
                    "${primary.number} • ${primary.label}"
                } else {
                    "${primary.number} (+${contact.numbers.size - 1} more)"
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (contact.numbers.size > 1) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "${contact.numbers.size} numbers",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}
