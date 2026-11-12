package com.example.feature.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.core.LogKeeper
import com.example.core.ipc.ConnectionState
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyCommandType
import com.example.core.ipc.HeavyProcessConnectionManager
import java.util.UUID

/**
 * Transparent helper activity in Main to launch SAF ACTION_OPEN_DOCUMENT_TREE
 * for the Reader's in-app folder explorer and persist the granted tree URI.
 */
class ReaderFolderTreeActivity : ComponentActivity() {

    private val openTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (t: Throwable) {
                LogKeeper.log(applicationContext, "ReaderFolderTreeActivity", "Persist tree URI permission warning: ${t.message}")
            }

            val uriStr = uri.toString()
            ReaderFolderPrefs.saveFolderUri(applicationContext, uriStr)
            ReaderEventManager.notifyFolderGranted(uriStr)

            try {
                val cm = HeavyProcessConnectionManager.getInstance(applicationContext)
                if (cm.state == ConnectionState.CONNECTED) {
                    cm.sendCommand(
                        HeavyCommand(
                            commandId = UUID.randomUUID().toString(),
                            type = HeavyCommandType.PAUSE_OPERATION,
                            targetId = "reader",
                            payload = mapOf(
                                "action" to "reader_folder_granted",
                                "instance_id" to "reader",
                                "folder_uri" to uriStr
                            )
                        ),
                        autoConnect = false
                    )
                }
            } catch (_: Throwable) {}

            Toast.makeText(applicationContext, "Books folder linked.", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogKeeper.log(this, "ReaderFolderTreeActivity", "Launching ACTION_OPEN_DOCUMENT_TREE")
        try {
            val existingUri = ReaderFolderPrefs.getFolderUri(applicationContext)?.let {
                try { Uri.parse(it) } catch (_: Throwable) { null }
            }
            openTreeLauncher.launch(existingUri)
        } catch (e: Exception) {
            LogKeeper.log(this, "ReaderFolderTreeActivity", "Failed to open folder picker: ${e.message}")
            Toast.makeText(this, "Unable to open folder picker.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
