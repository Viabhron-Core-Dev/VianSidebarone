package com.example.feature.reader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.core.LogKeeper
import com.example.data.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Transparent helper activity to safely launch Android's Storage Access Framework document picker
 * from the floating window / service context without violating Android window token rules.
 */
class ReaderImportActivity : ComponentActivity() {

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Throwable) {
                // Ignore if permission cannot be persisted across reboots
            }

            Toast.makeText(this, "Importing book...", Toast.LENGTH_SHORT).show()

            activityScope.launch {
                val repo = LibraryRepository(applicationContext)
                val book = repo.importBook(uri)
                if (book != null) {
                    Toast.makeText(applicationContext, "Imported: ${book.title}", Toast.LENGTH_SHORT).show()
                    ReaderEventManager.notifyLibraryChanged()
                    try {
                        val cm = com.example.core.ipc.HeavyProcessConnectionManager.getInstance(applicationContext)
                        if (cm.state == com.example.core.ipc.ConnectionState.CONNECTED) {
                            cm.sendCommand(
                                com.example.core.ipc.HeavyCommand(
                                    commandId = java.util.UUID.randomUUID().toString(),
                                    type = com.example.core.ipc.HeavyCommandType.PAUSE_OPERATION,
                                    targetId = "reader",
                                    payload = mapOf(
                                        "action" to "reader_library_changed",
                                        "instance_id" to "reader"
                                    )
                                ),
                                autoConnect = false
                            )
                        }
                    } catch (_: Throwable) {}
                } else {
                    Toast.makeText(applicationContext, "Failed to import book.", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogKeeper.log(this, "ReaderImportActivity", "Launching document picker for EPUB / TXT")
        try {
            openDocumentLauncher.launch(
                arrayOf(
                    "application/epub+zip",
                    "text/plain",
                    "application/octet-stream",
                    "application/x-epub+zip"
                )
            )
        } catch (e: Exception) {
            LogKeeper.log(this, "ReaderImportActivity", "Failed to launch open document: ${e.message}")
            Toast.makeText(this, "Unable to open file picker.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
