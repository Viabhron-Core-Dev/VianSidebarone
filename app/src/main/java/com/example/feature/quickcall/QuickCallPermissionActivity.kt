package com.example.feature.quickcall

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.core.LogKeeper

/**
 * Lightweight translucent trampoline Activity to legally request [Manifest.permission.CALL_PHONE]
 * when a Quick Call is triggered from an overlay window (Sidebar, Hybrid Grid, or Floating Trigger).
 *
 * - Requests `CALL_PHONE` ONLY on demand when missing.
 * - On grant: immediately executes the Quick Call via [QuickCallActionHandler].
 * - On denial: offers an explicit `ACTION_DIAL` fallback dialog so the user can still dial
 *   manually without granting direct-call permission.
 * - Never logs phone numbers.
 */
class QuickCallPermissionActivity : ComponentActivity() {

    private var targetUuid: String? = null

    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val uuid = targetUuid
        if (uuid.isNullOrBlank()) {
            finish()
            return@registerForActivityResult
        }

        if (isGranted) {
            LogKeeper.log(this, TAG, "CALL_PHONE_GRANTED uuid=$uuid")
            QuickCallActionHandler.executeByUuid(applicationContext, uuid)
            finish()
        } else {
            LogKeeper.log(this, TAG, "CALL_PHONE_DENIED uuid=$uuid")
            showDialerFallbackDialog(uuid)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val elementId = intent.getStringExtra(QuickCallActionHandler.EXTRA_ELEMENT_ID)
        val uuid = QuickCallStore.extractUuid(elementId)
        if (uuid.isNullOrBlank()) {
            finish()
            return
        }
        targetUuid = uuid

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            QuickCallActionHandler.executeByUuid(applicationContext, uuid)
            finish()
            return
        }

        callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
    }

    private fun showDialerFallbackDialog(uuid: String) {
        if (isFinishing || isDestroyed) {
            finish()
            return
        }
        val label = QuickCallStore.getInstance(this).getRecord(uuid)?.displayName ?: "this contact"
        AlertDialog.Builder(this)
            .setTitle("Phone Permission Denied")
            .setMessage(
                "Direct calling requires Phone permission. You can still open $label in the system dialer instead."
            )
            .setPositiveButton("Open Dialer") { _, _ ->
                QuickCallActionHandler.launchDialerFallback(applicationContext, uuid)
                finish()
            }
            .setNegativeButton("Cancel") { _, _ ->
                finish()
            }
            .setOnCancelListener {
                finish()
            }
            .show()
    }

    companion object {
        private const val TAG = "QuickCallPermission"
    }
}
