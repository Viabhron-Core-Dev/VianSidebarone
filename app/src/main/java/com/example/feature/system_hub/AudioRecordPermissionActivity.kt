package com.example.feature.system_hub

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.core.LogKeeper

/**
 * Lightweight trampoline Activity to legally request the runtime RECORD_AUDIO permission.
 * Services and overlay windows cannot legally request runtime permissions directly;
 * this Activity requests the permission, launches AudioRecordFloatingPanel upon success,
 * or shows an error toast upon denial, and terminates immediately.
 */
class AudioRecordPermissionActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            LogKeeper.writeLog(TAG, "RECORD_AUDIO permission granted by user")
            AudioRecordFloatingPanel.show(applicationContext)
        } else {
            LogKeeper.writeLog(TAG, "RECORD_AUDIO permission denied by user")
            Toast.makeText(
                this,
                "Microphone permission is required for audio recording",
                Toast.LENGTH_LONG
            ).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            AudioRecordFloatingPanel.show(applicationContext)
            finish()
            return
        }

        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    companion object {
        private const val TAG = "AudioRecordPermission"
    }
}
