package com.example.feature.pwa.floating

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.example.core.LogKeeper
import com.example.core.ipc.ConnectionState
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyCommandType
import com.example.core.ipc.HeavyProcessConnectionManager
import com.example.feature.floating.HeavyFloatingHost
import com.example.feature.pwa.PwaMimeHelper
import com.example.feature.pwa.PwaPermissionManager
import java.util.UUID

/**
 * FloatingPwaRelayActivity: Translucent Main-process Activity handling SAF file chooser
 * and runtime permission prompts requested by [FloatingPwaMiniAppInstance] in :heavy.
 */
class FloatingPwaRelayActivity : ComponentActivity() {

    companion object {
        const val MODE_FILE_CHOOSER = "file_chooser"
        const val MODE_PERMISSION = "permission"

        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_INSTANCE_ID = "extra_instance_id"
        const val EXTRA_REQUEST_ID = "extra_request_id"
        const val EXTRA_PWA_ID = "extra_pwa_id"
        const val EXTRA_IS_MULTIPLE = "extra_is_multiple"
        const val EXTRA_ACCEPT_TYPES = "extra_accept_types"
        const val EXTRA_CAPABILITY = "extra_capability"
        const val EXTRA_WANTS_CAMERA = "extra_wants_camera"
        const val EXTRA_WANTS_MIC = "extra_wants_mic"

        private const val TAG = "FloatingPwaRelay"
    }

    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private var instanceId: String = ""
    private var requestId: String = ""
    private var pwaId: String = ""
    private var capability: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mode = intent.getStringExtra(EXTRA_MODE) ?: run {
            finish()
            return
        }
        instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID) ?: ""
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: ""
        pwaId = intent.getStringExtra(EXTRA_PWA_ID) ?: ""
        capability = intent.getStringExtra(EXTRA_CAPABILITY) ?: ""

        fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val data = result.data!!
                val uris = mutableListOf<Uri>()
                val clip = data.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) {
                        uris.add(clip.getItemAt(i).uri)
                    }
                } else if (data.data != null) {
                    uris.add(data.data!!)
                }

                for (u in uris) {
                    try {
                        contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: Throwable) {}
                    if (pwaId.isNotEmpty()) {
                        PwaPermissionManager.addGrantedUri(this, pwaId, u)
                    }
                }
                dispatchResultToHeavy(
                    FloatingPwaMiniAppInstance.CMD_PWA_FILE_RESULT,
                    mapOf(
                        "requestId" to requestId,
                        "cancelled" to "false",
                        "uris" to uris.joinToString("\n") { it.toString() }
                    )
                )
            } else {
                dispatchResultToHeavy(
                    FloatingPwaMiniAppInstance.CMD_PWA_FILE_RESULT,
                    mapOf("requestId" to requestId, "cancelled" to "true")
                )
            }
            finish()
        }

        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val cameraGranted = results[Manifest.permission.CAMERA] == true
            val micGranted = results[Manifest.permission.RECORD_AUDIO] == true
            val locGranted = (results[Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
                (results[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
            dispatchResultToHeavy(
                FloatingPwaMiniAppInstance.CMD_PWA_PERMISSION_RESULT,
                mapOf(
                    "requestId" to requestId,
                    "cap" to capability,
                    "cameraGranted" to cameraGranted.toString(),
                    "micGranted" to micGranted.toString(),
                    "locationGranted" to locGranted.toString()
                )
            )
            finish()
        }

        when (mode) {
            MODE_FILE_CHOOSER -> launchFileChooser()
            MODE_PERMISSION -> launchPermissionPrompt()
            else -> finish()
        }
    }

    private fun launchFileChooser() {
        val rawAccept = intent.getStringExtra(EXTRA_ACCEPT_TYPES)
        val isMultiple = intent.getBooleanExtra(EXTRA_IS_MULTIPLE, false)
        val acceptArray = rawAccept?.split(',')?.toTypedArray()
        val (primaryMime, extraMimes) = PwaMimeHelper.resolveIntentMimeTypes(acceptArray)

        var pickIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = primaryMime
            if (!extraMimes.isNullOrEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, extraMimes)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, isMultiple)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (pickIntent.resolveActivity(packageManager) == null) {
            pickIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, isMultiple)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        try {
            fileChooserLauncher.launch(pickIntent)
        } catch (t: Throwable) {
            LogKeeper.logError(this, TAG, "File chooser launch failed", t)
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show()
            dispatchResultToHeavy(
                FloatingPwaMiniAppInstance.CMD_PWA_FILE_RESULT,
                mapOf("requestId" to requestId, "cancelled" to "true")
            )
            finish()
        }
    }

    private fun launchPermissionPrompt() {
        val permissions = mutableListOf<String>()
        if (capability == "media") {
            if (intent.getBooleanExtra(EXTRA_WANTS_CAMERA, false)) permissions.add(Manifest.permission.CAMERA)
            if (intent.getBooleanExtra(EXTRA_WANTS_MIC, false)) permissions.add(Manifest.permission.RECORD_AUDIO)
        } else if (capability == "location") {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (permissions.isEmpty()) {
            finish()
            return
        }
        try {
            permissionLauncher.launch(permissions.toTypedArray())
        } catch (t: Throwable) {
            LogKeeper.logError(this, TAG, "Permission prompt launch failed", t)
            finish()
        }
    }

    private fun dispatchResultToHeavy(action: String, extra: Map<String, String>) {
        if (instanceId.isEmpty()) return
        val payload = LinkedHashMap<String, String>().apply {
            put("action", action)
            put("instance_id", instanceId)
            putAll(extra)
        }
        val cm = HeavyProcessConnectionManager.getInstance(this)
        if (cm.state == ConnectionState.CONNECTED) {
            cm.sendCommand(
                HeavyCommand(
                    commandId = UUID.randomUUID().toString(),
                    type = HeavyCommandType.PAUSE_OPERATION,
                    targetId = instanceId,
                    payload = payload
                ),
                autoConnect = false
            )
        } else {
            val inst = HeavyFloatingHost.getInstance(this).getActiveInstance(instanceId)
            if (inst is FloatingPwaMiniAppInstance) {
                inst.handleCommand(this, action, payload)
            }
        }
    }
}
