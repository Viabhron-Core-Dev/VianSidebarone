package com.example.feature.quickcall

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.IconCacheManager
import com.example.core.SquircleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Dedicated Quick Call editor wired into the Sidebar / Hybrid Grid long-press Edit flow.
 *
 * Allows editing:
 * - Displayed name / label
 * - Phone number (validated before saving; marks `manuallyEditedNumber = true` so manual edits
 *   remain authoritative and are never silently overwritten by Contacts)
 * - Custom icon via zero-permission Android Photo Picker ([ActivityResultContracts.PickVisualMedia])
 * - Reset to default Android caller icon
 */
class QuickCallEditActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val elementId = intent.getStringExtra("EDIT_ELEMENT_ID")
        val uuid = QuickCallStore.extractUuid(elementId)
        val initialRecord = uuid?.let { QuickCallStore.getInstance(this).getRecord(it) }

        if (uuid.isNullOrBlank() || initialRecord == null) {
            Toast.makeText(this, "Quick Call record not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    QuickCallEditScreen(
                        record = initialRecord,
                        onSaved = {
                            setResult(Activity.RESULT_OK)
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
private fun QuickCallEditScreen(
    record: QuickCallRecord,
    onSaved: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var displayName by remember { mutableStateOf(record.displayName) }
    var phoneNumber by remember { mutableStateOf(record.phoneNumber) }
    var selectedIconUri by remember { mutableStateOf<Uri?>(null) }
    var isResettingIcon by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    val hasExistingCustomIcon = remember(record.elementId, record.customIconPath) {
        val customFile = IconCacheManager.getCustomIconFile(context, record.elementId)
        customFile.exists() && customFile.length() > 0 || !record.customIconPath.isNullOrEmpty()
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedIconUri = uri
            isResettingIcon = false
        }
    }

    val previewBitmap: Bitmap? = remember(record.elementId, record.customIconPath, selectedIconUri, isResettingIcon) {
        if (isResettingIcon) {
            null
        } else if (selectedIconUri != null) {
            try {
                context.contentResolver.openInputStream(selectedIconUri!!)?.use { stream ->
                    val raw = BitmapFactory.decodeStream(stream)
                    if (raw != null) SquircleHelper.toSquircleBitmap(raw) else null
                }
            } catch (_: Throwable) {
                null
            }
        } else {
            IconCacheManager.getCustomIcon(context, record.elementId)
                ?: record.customIconPath?.let { path ->
                    val file = File(path)
                    if (file.exists() && file.length() > 0) {
                        try {
                            val raw = BitmapFactory.decodeFile(file.absolutePath)
                            if (raw != null) SquircleHelper.toSquircleBitmap(raw) else null
                        } catch (_: Throwable) {
                            null
                        }
                    } else null
                }
        }
    }

    val isNumberValid = remember(phoneNumber) {
        QuickCallNumberValidator.isValidPhoneNumber(phoneNumber)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Quick Call") },
                navigationIcon = {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag("quick_call_edit_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                modifier = Modifier.testTag("quick_call_edit_topbar")
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Icon customization row
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                RoundedCornerShape(14.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (previewBitmap != null) {
                            Image(
                                bitmap = previewBitmap.asImageBitmap(),
                                contentDescription = "Quick Call Icon",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(2.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = "Default Caller Icon",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("quick_call_change_icon_button")
                            ) {
                                Text("Change Icon", fontSize = 12.sp)
                            }
                            if (selectedIconUri != null || (hasExistingCustomIcon && !isResettingIcon)) {
                                TextButton(
                                    onClick = {
                                        selectedIconUri = null
                                        isResettingIcon = true
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("quick_call_reset_icon_button")
                                ) {
                                    Text(
                                        text = "Reset Icon",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        Text(
                            text = when {
                                isResettingIcon -> "Default caller icon will be restored"
                                selectedIconUri != null -> "New custom icon selected"
                                hasExistingCustomIcon -> "Custom icon active"
                                else -> "Default caller icon"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Displayed Name / Label
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Display Name") },
                placeholder = { Text("e.g. Mom or Office") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("quick_call_edit_name_input")
            )

            // Phone Number
            OutlinedTextField(
                value = phoneNumber,
                onValueChange = { phoneNumber = it },
                label = { Text("Phone Number") },
                placeholder = { Text("+1 555-0100") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                isError = phoneNumber.isNotBlank() && !isNumberValid,
                supportingText = {
                    when {
                        phoneNumber.isNotBlank() && !isNumberValid -> {
                            Text(
                                text = "Enter a valid dialable phone number (digits, +, -, spaces, *, #)",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 11.sp
                            )
                        }
                        QuickCallNumberValidator.isEmergencyNumber(context, phoneNumber) -> {
                            Text(
                                text = "Emergency numbers always open in the system dialer.",
                                fontSize = 11.sp
                            )
                        }
                        record.manuallyEditedNumber -> {
                            Text(
                                text = "Manually edited number — preserved independently from Contacts.",
                                fontSize = 11.sp
                            )
                        }
                        else -> {
                            Text(
                                text = "Stored locally for this Quick Call element.",
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("quick_call_edit_number_input")
            )

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !isSaving,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("quick_call_edit_cancel_button")
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        if (!isNumberValid || isSaving) return@Button
                        isSaving = true
                        scope.launch {
                            val store = QuickCallStore.getInstance(context)
                            val updated = withContext(Dispatchers.IO) {
                                val res = store.updateRecord(
                                    uuid = record.uuid,
                                    displayName = displayName.trim().ifEmpty { "Quick Call" },
                                    phoneNumber = phoneNumber.trim(),
                                    markManuallyEdited = true
                                )
                                if (res != null) {
                                    if (selectedIconUri != null) {
                                        IconCacheManager.saveCustomIconFromUri(
                                            context,
                                            record.elementId,
                                            selectedIconUri!!
                                        )
                                        val file = IconCacheManager.getCustomIconFile(context, record.elementId)
                                        if (file.exists()) {
                                            store.updateCustomIconPath(record.uuid, file.absolutePath)
                                        }
                                    } else if (isResettingIcon) {
                                        store.resetCustomIcon(record.uuid)
                                    }
                                }
                                res
                            }

                            isSaving = false
                            if (updated != null) {
                                val updateIntent = Intent("com.example.UPDATE_SIDEBAR_ICONS").apply {
                                    putExtra("item_id", record.elementId)
                                    `package` = context.packageName
                                }
                                context.sendBroadcast(updateIntent)
                                Toast.makeText(context, "Quick Call saved", Toast.LENGTH_SHORT).show()
                                onSaved()
                            } else {
                                Toast.makeText(context, "Invalid phone number", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = isNumberValid && !isSaving,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("quick_call_edit_save_button")
                ) {
                    Text("Save", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
