package com.example.feature.system_hub

import android.content.Context
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class OcrModuleStatus {
    object Unknown : OcrModuleStatus()
    object Checking : OcrModuleStatus()
    object NotDownloaded : OcrModuleStatus()
    data class Downloading(val progress: Float, val bytesDownloaded: Long, val totalBytes: Long) : OcrModuleStatus()
    object Installed : OcrModuleStatus()
    data class Error(val message: String) : OcrModuleStatus()
}

class OcrModuleManager(context: Context) {
    private val appContext = context.applicationContext
    private val moduleInstallClient: ModuleInstallClient = ModuleInstall.getClient(appContext)
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val _status = MutableStateFlow<OcrModuleStatus>(OcrModuleStatus.Unknown)
    val status: StateFlow<OcrModuleStatus> = _status.asStateFlow()

    fun checkStatus(onResult: ((Boolean) -> Unit)? = null) {
        _status.value = OcrModuleStatus.Checking
        moduleInstallClient.areModulesAvailable(textRecognizer)
            .addOnSuccessListener { response ->
                if (response.areModulesAvailable()) {
                    _status.value = OcrModuleStatus.Installed
                    onResult?.invoke(true)
                } else {
                    _status.value = OcrModuleStatus.NotDownloaded
                    onResult?.invoke(false)
                }
            }
            .addOnFailureListener { e ->
                _status.value = OcrModuleStatus.Error(e.localizedMessage ?: "Failed to check OCR module status")
                onResult?.invoke(false)
            }
    }

    fun downloadModule(onComplete: ((Boolean, String?) -> Unit)? = null) {
        _status.value = OcrModuleStatus.Downloading(0f, 0, 0)
        val moduleInstallRequest = ModuleInstallRequest.newBuilder()
            .addApi(textRecognizer)
            .setListener { statusUpdate ->
                when (statusUpdate.installState) {
                    ModuleInstallStatusUpdate.InstallState.STATE_DOWNLOADING -> {
                        val progressInfo = statusUpdate.progressInfo
                        val total = progressInfo?.totalBytesToDownload ?: 0L
                        val current = progressInfo?.bytesDownloaded ?: 0L
                        val progress = if (total > 0) current.toFloat() / total.toFloat() else 0f
                        _status.value = OcrModuleStatus.Downloading(progress, current, total)
                    }
                    ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> {
                        _status.value = OcrModuleStatus.Installed
                        onComplete?.invoke(true, null)
                    }
                    ModuleInstallStatusUpdate.InstallState.STATE_FAILED -> {
                        val err = "Download failed (Code: ${statusUpdate.errorCode})"
                        _status.value = OcrModuleStatus.Error(err)
                        onComplete?.invoke(false, err)
                    }
                    ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> {
                        _status.value = OcrModuleStatus.NotDownloaded
                        onComplete?.invoke(false, "Download canceled")
                    }
                }
            }
            .build()

        moduleInstallClient.installModules(moduleInstallRequest)
            .addOnSuccessListener { response ->
                if (response.areModulesAlreadyInstalled()) {
                    _status.value = OcrModuleStatus.Installed
                    onComplete?.invoke(true, null)
                }
            }
            .addOnFailureListener { e ->
                val err = e.localizedMessage ?: "Failed to initiate download"
                _status.value = OcrModuleStatus.Error(err)
                onComplete?.invoke(false, err)
            }
    }
}
