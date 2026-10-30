package com.example.feature.pwa

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.core.IconCacheManager
import com.example.core.OverlaySyncManager
import com.example.core.SquircleHelper
import com.example.data.AppDatabase
import com.example.data.PwaEntity
import com.example.feature.sidebar.ElementMetadataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PwaEditActivity: Dedicated Mini App Info management screen for Offline PWAs.
 *
 * Sections:
 * 1. Appearance: Name, Icon preview/change/reset, Orientation, Close Button.
 * 2. Permissions / Access: Real enforceable toggles (Internet, Files SAF, Camera, Microphone, Location, Sound).
 *    All permissions default strictly to OFF for zero-trust sandbox execution.
 * 3. Storage / Package: Manifest details, package size, entrypoint, isolated paths.
 * 4. Backup & Export: Portable archive creation (.zip) with manifest, packages, icons, and permissions.
 * 5. Danger Zone: Permanent deletion with confirmation dialog.
 *
 * Top-bar ✓ is the ONLY save/confirm action.
 */
class PwaEditActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pwaId = intent.getStringExtra("PWA_ID") ?: run {
            finish()
            return
        }

        val prefs = getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var pwa by remember { mutableStateOf<PwaEntity?>(null) }
                val initialOrientation = remember(pwaId) {
                    prefs.getString("pwa_pref_${pwaId}_orientation", "manifest") ?: "manifest"
                }
                val initialCloseButton = remember(pwaId) {
                    prefs.getString("pwa_pref_${pwaId}_close_button", "show") ?: "show"
                }
                val initialPolicy = remember(pwaId) {
                    PwaPermissionManager.getPolicy(this@PwaEditActivity, pwaId)
                }

                LaunchedEffect(pwaId) {
                    val entity = withContext(Dispatchers.IO) {
                        AppDatabase.getDatabase(this@PwaEditActivity).pwaDao().getById(pwaId)
                    }
                    pwa = entity
                }

                val currentPwa = pwa
                if (currentPwa != null) {
                    MiniAppInfoScreen(
                        pwa = currentPwa,
                        initialOrientation = initialOrientation,
                        initialCloseButton = initialCloseButton,
                        initialPolicy = initialPolicy,
                        onBack = { finish() },
                        onDelete = {
                            lifecycleScope.launch {
                                withContext(Dispatchers.IO) {
                                    // 1. Delete all physical assets and database record
                                    PwaStorageManager.deletePwa(this@PwaEditActivity, currentPwa.id)

                                    // 2. Evict icon cache
                                    IconCacheManager.evictPwaIcon(currentPwa.id)

                                    // 3. Notify Sidebar grids
                                    val iconUpdateIntent = Intent("com.example.UPDATE_SIDEBAR_ICONS").apply {
                                        putExtra("item_id", "pwa:${currentPwa.id}")
                                        `package` = packageName
                                    }
                                    sendBroadcast(iconUpdateIntent)
                                }
                                Toast.makeText(this@PwaEditActivity, "Mini App deleted", Toast.LENGTH_SHORT).show()
                                finish()
                            }
                        },
                        onSave = { updatedName, selectedOrientation, selectedCloseButton, updatedPolicy, newCustomIconUri, resetIconToDefault ->
                            lifecycleScope.launch {
                                withContext(Dispatchers.IO) {
                                    var finalIconPath = currentPwa.iconPath

                                    if (newCustomIconUri != null) {
                                        val customSquircle = IconCacheManager.saveCustomIconFromUri(
                                            this@PwaEditActivity,
                                            "pwa_${currentPwa.id}",
                                            newCustomIconUri
                                        )
                                        if (customSquircle != null) {
                                            val customFile = IconCacheManager.getCustomIconFile(this@PwaEditActivity, "pwa_${currentPwa.id}")
                                            finalIconPath = customFile.absolutePath
                                        }
                                    } else if (resetIconToDefault) {
                                        val customFile = IconCacheManager.getCustomIconFile(this@PwaEditActivity, "pwa_${currentPwa.id}")
                                        if (customFile.exists()) {
                                            customFile.delete()
                                        }
                                        val originalPackageIcon = File(this@PwaEditActivity.filesDir, "pwa/${currentPwa.id}/app_icon.png")
                                        finalIconPath = if (originalPackageIcon.exists()) originalPackageIcon.absolutePath else null
                                    }

                                    // 1. Evict and update in-memory icon cache
                                    IconCacheManager.evictPwaIcon(currentPwa.id)
                                    if (!finalIconPath.isNullOrEmpty()) {
                                        IconCacheManager.getPwaIcon(this@PwaEditActivity, currentPwa.id, finalIconPath)
                                    }

                                    // 2. Update Room database record
                                    AppDatabase.getDatabase(this@PwaEditActivity).pwaDao()
                                        .updateMetadata(
                                            id = currentPwa.id,
                                            name = updatedName,
                                            iconPath = finalIconPath,
                                            updatedAt = System.currentTimeMillis()
                                        )

                                    // 3. Update ElementMetadataStore for zero-Room grid binding
                                    ElementMetadataStore.savePwaElement(
                                        this@PwaEditActivity,
                                        currentPwa.id,
                                        updatedName,
                                        finalIconPath
                                    )

                                    // 4. Synchronously commit preferences
                                    prefs.edit()
                                        .putString("pwa_pref_${currentPwa.id}_orientation", selectedOrientation)
                                        .putString("pwa_pref_${currentPwa.id}_close_button", selectedCloseButton)
                                        .commit()

                                    // 5. Broadcast preference sync via OverlaySyncManager
                                    OverlaySyncManager.syncString(this@PwaEditActivity, "pwa_pref_${currentPwa.id}_orientation", selectedOrientation)
                                    OverlaySyncManager.syncString(this@PwaEditActivity, "pwa_pref_${currentPwa.id}_close_button", selectedCloseButton)

                                    // 6. Save and synchronize capability permission policy
                                    PwaPermissionManager.savePolicy(this@PwaEditActivity, currentPwa.id, updatedPolicy)

                                    // 7. Broadcast icon update to all active Sidebar pages and grids
                                    val iconUpdateIntent = Intent("com.example.UPDATE_SIDEBAR_ICONS").apply {
                                        putExtra("item_id", "pwa:${currentPwa.id}")
                                        `package` = packageName
                                    }
                                    sendBroadcast(iconUpdateIntent)
                                }
                                Toast.makeText(this@PwaEditActivity, "Saved", Toast.LENGTH_SHORT).show()
                                finish()
                            }
                        }
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniAppInfoScreen(
    pwa: PwaEntity,
    initialOrientation: String,
    initialCloseButton: String,
    initialPolicy: PwaPermissionPolicy,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onSave: (newName: String, orientation: String, closeButton: String, policy: PwaPermissionPolicy, customIconUri: Uri?, resetIcon: Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(pwa.name) }
    var orientation by remember { mutableStateOf(initialOrientation) }
    var closeButtonOption by remember { mutableStateOf(initialCloseButton) }
    var permissionPolicy by remember { mutableStateOf(initialPolicy) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    var selectedIconUri by remember { mutableStateOf<Uri?>(null) }
    var isResettingIcon by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // SAF file grants state
    var grantedUris by remember { mutableStateOf(PwaPermissionManager.getGrantedUris(context, pwa.id)) }

    val customFile = remember(pwa.id) { IconCacheManager.getCustomIconFile(context, "pwa_${pwa.id}") }
    val hasCustomIconInitially = remember(customFile) { customFile.exists() && customFile.length() > 0 }

    // SAF image picker launcher for icon customization
    val iconPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedIconUri = uri
            isResettingIcon = false
        }
    }

    // SAF document creation launcher for PWA backup export
    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val success = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            PwaStorageManager.exportPwaBackup(context, pwa.id, stream)
                        } ?: false
                    } catch (e: Exception) {
                        false
                    }
                }
                if (success) {
                    Toast.makeText(context, "Mini App backup exported successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to export backup", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // SAF file grant picker launcher
    val addFileGrantLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {}
            PwaPermissionManager.addGrantedUri(context, pwa.id, uri)
            grantedUris = PwaPermissionManager.getGrantedUris(context, pwa.id)
            Toast.makeText(context, "File access granted", Toast.LENGTH_SHORT).show()
        }
    }

    // Decode preview bitmap
    val displayedIconBitmap: Bitmap? = remember(pwa.iconPath, selectedIconUri, isResettingIcon) {
        if (isResettingIcon) {
            val originalPackageIcon = File(context.filesDir, "pwa/${pwa.id}/app_icon.png")
            if (originalPackageIcon.exists()) {
                val raw = BitmapFactory.decodeFile(originalPackageIcon.absolutePath)
                if (raw != null) SquircleHelper.toSquircleBitmap(raw) else null
            } else null
        } else if (selectedIconUri != null) {
            try {
                context.contentResolver.openInputStream(selectedIconUri!!)?.use { stream ->
                    val raw = BitmapFactory.decodeStream(stream)
                    if (raw != null) SquircleHelper.toSquircleBitmap(raw) else null
                }
            } catch (_: Exception) {
                null
            }
        } else if (!pwa.iconPath.isNullOrEmpty()) {
            val file = File(pwa.iconPath)
            if (file.exists()) {
                val raw = BitmapFactory.decodeFile(file.absolutePath)
                if (raw != null) SquircleHelper.toSquircleBitmap(raw) else null
            } else null
        } else {
            null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mini App Info") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("pwa_info_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (name.isNotBlank()) {
                                onSave(name.trim(), orientation, closeButtonOption, permissionPolicy, selectedIconUri, isResettingIcon)
                            }
                        },
                        modifier = Modifier.testTag("pwa_info_save_button")
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Save", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color(0xFF121212))
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ==========================================
            // 1. APPEARANCE SECTION
            // ==========================================
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Icon row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(Color(0xFF2A2A3C), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (displayedIconBitmap != null) {
                                Image(
                                    bitmap = displayedIconBitmap.asImageBitmap(),
                                    contentDescription = pwa.name,
                                    modifier = Modifier.fillMaxSize().padding(4.dp)
                                )
                            } else {
                                Text(
                                    text = name.take(1).uppercase(),
                                    color = Color.White,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Icon",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { iconPickerLauncher.launch("image/*") },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("pwa_change_icon_button")
                                ) {
                                    Text("Change Icon", fontSize = 12.sp)
                                }
                                if ((hasCustomIconInitially && !isResettingIcon) || selectedIconUri != null) {
                                    TextButton(
                                        onClick = {
                                            selectedIconUri = null
                                            isResettingIcon = true
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.testTag("pwa_reset_icon_button")
                                    ) {
                                        Text("Reset", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                            val statusText = if (isResettingIcon) {
                                "Default icon will be restored upon saving"
                            } else if (selectedIconUri != null) {
                                "New custom icon selected (tap ✓ to save)"
                            } else if (hasCustomIconInitially) {
                                "Custom icon active"
                            } else {
                                "Original package icon"
                            }
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }

                    // Editable Name
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("App Name") },
                        modifier = Modifier.fillMaxWidth().testTag("pwa_edit_name_input"),
                        singleLine = true
                    )
                }
            }

            // Orientation Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Orientation",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )

                    val orientationOptions = listOf(
                        "manifest" to "Follow Manifest (${pwa.orientation.ifBlank { "unspecified" }})",
                        "unspecified" to "Auto / Unspecified",
                        "portrait" to "Portrait",
                        "landscape" to "Landscape"
                    )

                    orientationOptions.forEach { (key, label) ->
                        val isSelected = orientation == key
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable { orientation = key }
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                                .testTag("pwa_orientation_$key"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { orientation = key },
                                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSelected) Color.White else Color.LightGray
                            )
                        }
                    }
                }
            }

            // Close Button Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Close Button",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )

                    val closeOptions = listOf(
                        "show" to "Show (Display floating exit button)",
                        "hide" to "Hide (Android Back key exits at root)"
                    )

                    closeOptions.forEach { (key, label) ->
                        val isSelected = closeButtonOption == key
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable { closeButtonOption = key }
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                                .testTag("pwa_close_button_$key"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { closeButtonOption = key },
                                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSelected) Color.White else Color.LightGray
                            )
                        }
                    }
                }
            }

            // ==========================================
            // 2. PERMISSIONS / ACCESS SECTION
            // ==========================================
            Text(
                text = "Permissions & Access",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Sandbox Policies (Default OFF)",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )

                    // Internet Toggle
                    PermissionToggleRow(
                        title = "Internet Access",
                        description = "Enables connections to external domains and APIs. Local package assets always work offline.",
                        checked = permissionPolicy.internetAllowed,
                        testTag = "pwa_perm_internet_switch",
                        onCheckedChange = { permissionPolicy = permissionPolicy.copy(internetAllowed = it) }
                    )

                    HorizontalDivider(color = Color(0xFF2A2A3C))

                    // Files (SAF) Toggle
                    PermissionToggleRow(
                        title = "Files (Storage Access Framework)",
                        description = "Enables document picker access for file upload and storage. Managed via SAF.",
                        checked = permissionPolicy.filesAllowed,
                        testTag = "pwa_perm_files_switch",
                        onCheckedChange = { permissionPolicy = permissionPolicy.copy(filesAllowed = it) }
                    )

                    // If Files is enabled, display active SAF URI grants with Revoke buttons
                    if (permissionPolicy.filesAllowed) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF161622), RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Active File Grants (${grantedUris.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White
                                )
                                OutlinedButton(
                                    onClick = { addFileGrantLauncher.launch(arrayOf("*/*")) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.testTag("pwa_add_file_grant_button")
                                ) {
                                    Text("Grant File Access", fontSize = 11.sp)
                                }
                            }

                            if (grantedUris.isEmpty()) {
                                Text(
                                    text = "No files or folders currently granted. File chooser inputs in the Mini App will prompt SAF.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            } else {
                                grantedUris.forEach { uriStr ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = Uri.parse(uriStr).lastPathSegment ?: uriStr,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.LightGray,
                                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                                            maxLines = 1
                                        )
                                        TextButton(
                                            onClick = {
                                                PwaPermissionManager.revokeGrantedUri(context, pwa.id, uriStr)
                                                grantedUris = PwaPermissionManager.getGrantedUris(context, pwa.id)
                                                Toast.makeText(context, "File grant revoked", Toast.LENGTH_SHORT).show()
                                            },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text("Revoke", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = Color(0xFF2A2A3C))

                    // Camera Toggle
                    PermissionToggleRow(
                        title = "Camera",
                        description = "Enables hardware camera video capture via WebRTC / HTML5 video.",
                        checked = permissionPolicy.cameraAllowed,
                        testTag = "pwa_perm_camera_switch",
                        onCheckedChange = { permissionPolicy = permissionPolicy.copy(cameraAllowed = it) }
                    )

                    HorizontalDivider(color = Color(0xFF2A2A3C))

                    // Microphone Toggle
                    PermissionToggleRow(
                        title = "Microphone",
                        description = "Enables audio recording via WebRTC / HTML5 audio capture.",
                        checked = permissionPolicy.microphoneAllowed,
                        testTag = "pwa_perm_microphone_switch",
                        onCheckedChange = { permissionPolicy = permissionPolicy.copy(microphoneAllowed = it) }
                    )

                    HorizontalDivider(color = Color(0xFF2A2A3C))

                    // Location Toggle
                    PermissionToggleRow(
                        title = "Location",
                        description = "Enables GPS and network geolocation queries via HTML5 Geolocation API.",
                        checked = permissionPolicy.locationAllowed,
                        testTag = "pwa_perm_location_switch",
                        onCheckedChange = { permissionPolicy = permissionPolicy.copy(locationAllowed = it) }
                    )

                    HorizontalDivider(color = Color(0xFF2A2A3C))

                    // Sound Toggle
                    PermissionToggleRow(
                        title = "Sound & Audio Playback",
                        description = "Enables audio playback. When disabled, media and Web Audio are suppressed.",
                        checked = permissionPolicy.soundAllowed,
                        testTag = "pwa_perm_sound_switch",
                        onCheckedChange = { permissionPolicy = permissionPolicy.copy(soundAllowed = it) }
                    )
                }
            }

            // ==========================================
            // 3. STORAGE & PACKAGE SECTION
            // ==========================================
            Text(
                text = "Storage / Package",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Package Details", style = MaterialTheme.typography.titleSmall, color = Color.White)
                    DetailRow("Entrypoint", pwa.startUrl)
                    DetailRow("Display Mode", pwa.displayMode)
                    DetailRow("Package Size", PwaStorageManager.formatPackageSize(pwa.packageSize))
                    DetailRow("Version", pwa.version)
                    DetailRow("Origin Domain", "https://pwa-${pwa.id}.local/")
                    DetailRow("Imported On", dateFormat.format(Date(pwa.createdAt)))
                    if (pwa.lastLaunchedAt > 0) {
                        DetailRow("Last Launched", dateFormat.format(Date(pwa.lastLaunchedAt)))
                    }
                }
            }

            // ==========================================
            // 4. BACKUP & EXPORT SECTION
            // ==========================================
            Text(
                text = "Backup & Export",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Export a portable backup archive (.zip) containing the source package, manifest metadata, custom icons, and capability policies. Ready for safe migration and re-import.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    OutlinedButton(
                        onClick = {
                            val safeName = pwa.name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(30)
                            exportBackupLauncher.launch("${safeName}_backup.zip")
                        },
                        modifier = Modifier.fillMaxWidth().testTag("pwa_export_backup_button")
                    ) {
                        Text("Export Backup (.zip)")
                    }
                }
            }

            // ==========================================
            // 5. DANGER ZONE SECTION
            // ==========================================
            Text(
                text = "Danger Zone",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1515)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Deleting this Mini App permanently removes its source package, unpacked assets, isolated database storage, and icon cache.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFAAAA)
                    )
                    Button(
                        onClick = { showDeleteConfirmDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().testTag("pwa_delete_button")
                    ) {
                        Text("Delete Mini App", color = Color.White)
                    }
                }
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Mini App?") },
            text = { Text("Are you sure you want to delete \"${pwa.name}\"? All files, settings, and isolated app storage will be permanently erased.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PermissionToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Text(text = value, style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
    }
}
