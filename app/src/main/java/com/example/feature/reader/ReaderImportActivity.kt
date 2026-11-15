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

    companion object {
        const val EXTRA_REIMPORT_BOOK_ID = "extra_reimport_book_id"
        const val EXTRA_OPEN_AFTER_IMPORT = "extra_open_after_import"
    }

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var reimportBookId: Int = -1
    private var openAfterImport: Boolean = false

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

            Toast.makeText(this, if (reimportBookId > 0) "Re-importing book..." else "Importing book...", Toast.LENGTH_SHORT).show()

            activityScope.launch {
                val repo = LibraryRepository(applicationContext)
                val book = if (reimportBookId > 0) {
                    repo.reimportBook(reimportBookId, uri)
                } else {
                    repo.importBook(uri, markOpenedOnImport = openAfterImport)
                }
                if (book != null) {
                    Toast.makeText(
                        applicationContext,
                        if (reimportBookId > 0) "Restored: ${book.title}" else "Imported: ${book.title}",
                        Toast.LENGTH_SHORT
                    ).show()
                    ReaderEventManager.notifyLibraryChanged(applicationContext)
                    if (openAfterImport) {
                        ReaderEventManager.requestOpenBookInReader(applicationContext, book.id)
                    }
                } else {
                    Toast.makeText(applicationContext, "Failed to import book. Existing files kept.", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reimportBookId = intent?.getIntExtra(EXTRA_REIMPORT_BOOK_ID, -1) ?: -1
        openAfterImport = intent?.getBooleanExtra(EXTRA_OPEN_AFTER_IMPORT, false) ?: false
        LogKeeper.log(this, "ReaderImportActivity", "Launching document picker for EPUB / TXT (reimportBookId=$reimportBookId)")
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
