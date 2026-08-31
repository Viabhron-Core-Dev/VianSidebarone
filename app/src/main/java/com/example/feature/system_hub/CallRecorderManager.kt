package com.example.feature.system_hub

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.telephony.TelephonyCallback
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.documentfile.provider.DocumentFile
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.provider.ContactsContract

class CallRecorderManager(private val context: Context, private val prefs: SharedPreferences) {

    companion object {
        @Volatile
        private var instance: CallRecorderManager? = null

        fun getInstance(context: Context): CallRecorderManager {
            return instance ?: synchronized(this) {
                instance ?: CallRecorderManager(
                    context.applicationContext,
                    context.applicationContext.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
                ).also { instance = it }
            }
        }
    }

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var isListening = false
    private var currentRecordFile: File? = null
    private var currentRecordPfd: ParcelFileDescriptor? = null
    private var lastPhoneNumber: String? = null
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
    private var recordButtonView: FloatingRecordButtonView? = null

    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: TelephonyCallback? = null
    
    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                if (number != null) {
                    lastPhoneNumber = number
                }
                if (stateStr == TelephonyManager.EXTRA_STATE_IDLE) {
                    lastPhoneNumber = null
                }
            } else if (intent.action == Intent.ACTION_NEW_OUTGOING_CALL) {
                val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
                if (number != null) {
                    lastPhoneNumber = number
                }
            }
        }
    }

    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    fun startListening() {
        stopListening()
        val autoEnabled = prefs.getBoolean("call_recorder_enabled", false)
        val manualEnabled = prefs.getBoolean("call_recorder_manual_enabled", false)
        if (!autoEnabled && !manualEnabled) return

        val filter = IntentFilter()
        filter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        filter.addAction(Intent.ACTION_NEW_OUTGOING_CALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(phoneStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(phoneStateReceiver, filter)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallState(state, null)
                }
            }
            try {
                telephonyManager.registerTelephonyCallback(context.mainExecutor, telephonyCallback!!)
                isListening = true
            } catch (e: Exception) {
                Log.e("CallRecorder", "Failed to register callback", e)
                com.example.core.LogKeeper.writeLog("CallRecorder", "Failed to register callback: ${e.message}")
            }
        } else {
            phoneStateListener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallState(state, phoneNumber)
                }
            }
            try {
                @Suppress("DEPRECATION")
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
                isListening = true
            } catch (e: Exception) {
                Log.e("CallRecorder", "Failed to register listener", e)
                com.example.core.LogKeeper.writeLog("CallRecorder", "Failed to register listener: ${e.message}")
            }
        }
    }

    fun stopListening() {
        if (!isListening && telephonyCallback == null && phoneStateListener == null) return
        try {
            context.unregisterReceiver(phoneStateReceiver)
        } catch (e: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
                telephonyCallback = null
            } else {
                phoneStateListener?.let { 
                    @Suppress("DEPRECATION")
                    telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE) 
                }
                phoneStateListener = null
            }
        } catch (e: Exception) {}
        isListening = false
        stopRecording()
    }

    private fun isContact(phoneNumber: String): Boolean {
        if (phoneNumber.isEmpty()) return false
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val projection = arrayOf(ContactsContract.PhoneLookup._ID)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                return cursor.moveToFirst()
            }
        } catch (e: Exception) {
            Log.e("CallRecorder", "Failed to check contact", e)
        }
        return false
    }

    private fun shouldRecordCall(): Boolean {
        val filterMode = prefs.getString("call_recorder_filter_mode", "all") ?: "all"
        if (filterMode == "all") return true
        
        val number = lastPhoneNumber ?: ""
        
        when (filterMode) {
            "non_contacts" -> {
                return !isContact(number)
            }
            "whitelist" -> {
                val whitelist = prefs.getStringSet("call_recorder_whitelist", emptySet()) ?: emptySet()
                return whitelist.contains(number) || whitelist.any { number.contains(it) }
            }
            "blacklist" -> {
                val blacklist = prefs.getStringSet("call_recorder_blacklist", emptySet()) ?: emptySet()
                return !(blacklist.contains(number) || blacklist.any { number.contains(it) })
            }
        }
        return true
    }

    fun onBroadcastEvent(stateStr: String?, phoneNumber: String?) {
        if (phoneNumber != null) {
            lastPhoneNumber = phoneNumber
        }
        val autoEnabled = prefs.getBoolean("call_recorder_enabled", false)
        val manualEnabled = prefs.getBoolean("call_recorder_manual_enabled", false)
        if (!autoEnabled && !manualEnabled) {
            stopRecording()
            hideManualButton()
            return
        }

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                handleCallState(TelephonyManager.CALL_STATE_OFFHOOK, phoneNumber)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                handleCallState(TelephonyManager.CALL_STATE_IDLE, phoneNumber)
            }
            TelephonyManager.EXTRA_STATE_RINGING -> {
                handleCallState(TelephonyManager.CALL_STATE_RINGING, phoneNumber)
            }
        }
    }

    private fun handleCallState(state: Int, phoneNumber: String?) {
        if (phoneNumber != null) {
            lastPhoneNumber = phoneNumber
        }
        val autoEnabled = prefs.getBoolean("call_recorder_enabled", false)
        val manualEnabled = prefs.getBoolean("call_recorder_manual_enabled", false)

        if (!autoEnabled && !manualEnabled) {
            stopRecording()
            hideManualButton()
            return
        }

        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (manualEnabled) {
                    showManualButton()
                }
                if (autoEnabled && shouldRecordCall()) {
                    startRecording()
                } else if (autoEnabled) {
                    com.example.core.LogKeeper.writeLog("CallRecorder", "Call ignored due to filter rule")
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                stopRecording()
                hideManualButton()
                lastPhoneNumber = null
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                // Not recording yet
            }
        }
    }

    private fun showManualButton() {
        if (recordButtonView == null) {
            recordButtonView = FloatingRecordButtonView(context, prefs, windowManager) {
                if (isRecording) {
                    stopRecording()
                } else {
                    startRecording()
                }
            }
        }
        recordButtonView?.attach()
        recordButtonView?.setRecordingState(isRecording)
    }

    private fun hideManualButton() {
        recordButtonView?.detach()
        recordButtonView = null
    }

    private fun startRecording() {
        if (isRecording) return
        
        try {
            val formatStr = prefs.getString("call_recorder_format", "MPEG_4") ?: "MPEG_4"
            val quality = prefs.getInt("call_recorder_quality", 128000)
            val saveFolderStr = prefs.getString("call_recorder_save_folder", "") ?: ""
            
            val ext = if (formatStr == "THREE_GPP") "3gp" else "m4a"
            val mime = if (formatStr == "THREE_GPP") "audio/3gpp" else "audio/mp4"
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "CALL_$timeStamp.$ext"

            if (saveFolderStr.isNotEmpty()) {
                try {
                    val uri = Uri.parse(saveFolderStr)
                    val documentFile = DocumentFile.fromTreeUri(context, uri)
                    if (documentFile != null && documentFile.exists()) {
                        val newFile = documentFile.createFile(mime, fileName)
                        if (newFile != null) {
                            currentRecordPfd = context.contentResolver.openFileDescriptor(newFile.uri, "w")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CallRecorder", "Failed SAF", e)
                }
            }

            if (currentRecordPfd == null) {
                val recordsDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), ".Records")
                if (!recordsDir.exists()) {
                    recordsDir.mkdirs()
                }
                val nomedia = File(recordsDir, ".nomedia")
                if (!nomedia.exists()) {
                    nomedia.createNewFile()
                }
                currentRecordFile = File(recordsDir, fileName)
            }

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                val audioSource = prefs.getInt("call_recorder_audio_source", MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setAudioSource(audioSource)
                
                if (formatStr == "THREE_GPP") {
                    setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                } else {
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                }
                
                setAudioEncodingBitRate(quality)
                setAudioSamplingRate(if (formatStr == "THREE_GPP") 8000 else 44100)
                
                if (currentRecordPfd != null) {
                    setOutputFile(currentRecordPfd!!.fileDescriptor)
                } else {
                    setOutputFile(currentRecordFile?.absolutePath)
                }
                
                prepare()
                start()
            }
            isRecording = true
            recordButtonView?.setRecordingState(true)
            val savePath = currentRecordFile?.absolutePath ?: "SAF Directory"
            Log.d("CallRecorder", "Started recording to $savePath")
            com.example.core.LogKeeper.writeLog("CallRecorder", "Started recording to $savePath")
        } catch (e: Exception) {
            Log.e("CallRecorder", "Failed to start recording", e)
            com.example.core.LogKeeper.writeLog("CallRecorder", "Failed to start recording: ${e.message}")
            stopRecording()
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
            Log.d("CallRecorder", "Stopped recording")
            com.example.core.LogKeeper.writeLog("CallRecorder", "Stopped recording")
        } catch (e: Exception) {
            Log.e("CallRecorder", "Failed to stop recording", e)
            com.example.core.LogKeeper.writeLog("CallRecorder", "Failed to stop recording: ${e.message}")
        } finally {
            try {
                currentRecordPfd?.close()
            } catch (e: Exception) {
                // Ignore
            }
            currentRecordPfd = null
            mediaRecorder = null
            isRecording = false
            recordButtonView?.setRecordingState(false)
        }
    }
}
