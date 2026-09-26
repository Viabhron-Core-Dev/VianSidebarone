package com.example.feature.call

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.example.core.HandleManager
import com.example.core.LogKeeper
import com.example.core.ipc.CallIpcContract
import com.example.core.ipc.HeavyProcessHost
import com.example.core.ipc.IpcErrorCode
import com.example.core.ipc.IpcResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * HeavyCallRecorderEngine: Real Call Recorder engine running strictly in the Heavy process (:heavy).
 *
 * Responsibilities:
 * 1. Audio recording resources: Manages MediaRecorder lifecycle and audio capture sessions.
 * 2. Storage & Files: Resolves Storage Access Framework (SAF) trees or app-private storage (.Records/CALL_*.m4a).
 * 3. Graceful degradation: Catches hardware audio limitations and unsupported audio sources without crashing or reporting fake success.
 * 4. Idempotence: Protects against duplicate start/stop commands and safely stops before starting new sessions.
 * 5. Event reporting: Dispatches HeavyEvent (STARTED, STOPPED, ERROR) back to Main across Binder.
 */
open class HeavyCallRecorderEngine(
    private val context: Context?,
    private val hostProvider: (() -> HeavyProcessHost?)? = null
) {

    private val lock = Any()

    private val prefs: SharedPreferences? by lazy {
        context?.getSharedPreferences(HandleManager.PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Volatile
    private var mediaRecorder: MediaRecorder? = null

    @Volatile
    var isRecording: Boolean = false
        private set

    @Volatile
    var currentSessionId: String? = null
        private set

    @Volatile
    var currentFilePath: String? = null
        private set

    private var currentRecordFile: File? = null
    private var currentRecordPfd: ParcelFileDescriptor? = null
    private var startTimeMs: Long = 0L

    private fun safeLog(msg: String) {
        context?.let { LogKeeper.log(it, TAG, msg) }
        Log.d(TAG, msg)
    }

    private fun safeLogError(msg: String, t: Throwable?) {
        context?.let { LogKeeper.logError(it, TAG, msg, t) }
        Log.e(TAG, msg, t)
    }

    private fun getHost(): HeavyProcessHost? {
        return hostProvider?.invoke() ?: context?.let { HeavyProcessHost.getInstance(it) }
    }

    /**
     * Starts an audio recording session for a phone call.
     *
     * @param sessionId Unique session ID originated by Main's CallRecorderManager.
     * @param audioSourceOverride Optional audio source override (e.g. for testing or fallback).
     */
    open fun startRecording(sessionId: String, audioSourceOverride: Int? = null): IpcResult {
        synchronized(lock) {
            // 1. Idempotency check: Already recording this exact session
            if (isRecording && currentSessionId == sessionId) {
                safeLog("Session '$sessionId' is already actively recording")
                return IpcResult.success(
                    "Already recording session $sessionId",
                    mapOf(
                        CallIpcContract.KEY_SESSION_ID to sessionId,
                        CallIpcContract.KEY_RECORDING_ACTIVE to "true",
                        CallIpcContract.KEY_FILE_PATH to (currentFilePath ?: "")
                    )
                )
            }

            // 2. If a different recording session is active, stop it cleanly first
            if (isRecording) {
                safeLog("Stopping existing active session '$currentSessionId' before starting new session '$sessionId'")
                stopRecordingInternal(currentSessionId)
            }

            val ctx = context
            if (ctx == null) {
                return IpcResult.error(IpcErrorCode.HEAVY_UNAVAILABLE, "Context is null in HeavyCallRecorderEngine")
            }

            // 3. Permission verification: Check RECORD_AUDIO
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                safeLogError("RECORD_AUDIO permission not granted", null)
                reportErrorToMain(sessionId, "PERMISSION_DENIED", "RECORD_AUDIO permission not granted")
                return IpcResult.error(
                    IpcErrorCode.COMMAND_FAILED,
                    "RECORD_AUDIO permission not granted",
                    mapOf(CallIpcContract.KEY_ERROR_CODE to "PERMISSION_DENIED")
                )
            }

            // 4. Resolve recording preferences
            val formatStr = prefs?.getString("call_recorder_format", "MPEG_4") ?: "MPEG_4"
            val quality = prefs?.getInt("call_recorder_quality", 128000) ?: 128000
            val saveFolderStr = prefs?.getString("call_recorder_save_folder", "") ?: ""
            val preferredAudioSource = audioSourceOverride
                ?: prefs?.getInt("call_recorder_audio_source", MediaRecorder.AudioSource.VOICE_RECOGNITION)
                ?: MediaRecorder.AudioSource.VOICE_RECOGNITION

            val ext = if (formatStr == "THREE_GPP") "3gp" else "m4a"
            val mime = if (formatStr == "THREE_GPP") "audio/3gpp" else "audio/mp4"
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "CALL_$timeStamp.$ext"

            // 5. Resolve target storage (SAF tree or app-private storage in music dir)
            var pfd: ParcelFileDescriptor? = null
            var targetFile: File? = null
            var resolvedPath = ""

            if (saveFolderStr.isNotEmpty()) {
                try {
                    val uri = Uri.parse(saveFolderStr)
                    val documentFile = DocumentFile.fromTreeUri(ctx, uri)
                    if (documentFile != null && documentFile.exists()) {
                        val newFile = documentFile.createFile(mime, fileName)
                        if (newFile != null) {
                            pfd = ctx.contentResolver.openFileDescriptor(newFile.uri, "w")
                            resolvedPath = newFile.uri.toString()
                        }
                    }
                } catch (e: Throwable) {
                    safeLogError("SAF storage resolution failed; falling back to private storage", e)
                }
            }

            if (pfd == null) {
                val recordsDir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), ".Records")
                if (!recordsDir.exists()) {
                    recordsDir.mkdirs()
                }
                val nomedia = File(recordsDir, ".nomedia")
                if (!nomedia.exists()) {
                    try { nomedia.createNewFile() } catch (_: Exception) {}
                }
                val file = File(recordsDir, fileName)
                targetFile = file
                resolvedPath = file.absolutePath
            }

            // 6. Initialize MediaRecorder with fallback handling
            val recorderResult = initAndStartRecorder(
                ctx = ctx,
                preferredSource = preferredAudioSource,
                formatStr = formatStr,
                quality = quality,
                pfd = pfd,
                targetFile = targetFile
            )

            if (!recorderResult.success) {
                // Clean up file artifacts if recorder failed to start
                try { pfd?.close() } catch (_: Exception) {}
                targetFile?.let { if (it.exists() && it.length() == 0L) it.delete() }
                reportErrorToMain(sessionId, "AUDIO_CAPTURE_UNAVAILABLE", recorderResult.message)
                return IpcResult.error(
                    IpcErrorCode.COMMAND_FAILED,
                    "Failed to start call recording: ${recorderResult.message}",
                    mapOf(CallIpcContract.KEY_ERROR_CODE to "AUDIO_CAPTURE_UNAVAILABLE")
                )
            }

            // 7. Successfully started
            this.mediaRecorder = recorderResult.recorder
            this.currentRecordPfd = pfd
            this.currentRecordFile = targetFile
            this.currentFilePath = resolvedPath
            this.currentSessionId = sessionId
            this.startTimeMs = System.currentTimeMillis()
            this.isRecording = true

            safeLog("Started call recording for session '$sessionId' to $resolvedPath")
            reportStartedToMain(sessionId, resolvedPath)

            return IpcResult.success(
                "Recording started successfully",
                mapOf(
                    CallIpcContract.KEY_SESSION_ID to sessionId,
                    CallIpcContract.KEY_RECORDING_ACTIVE to "true",
                    CallIpcContract.KEY_FILE_PATH to resolvedPath
                )
            )
        }
    }

    /**
     * Helper to configure, prepare, and start MediaRecorder with fallback.
     */
    private data class RecorderInitResult(val success: Boolean, val recorder: MediaRecorder?, val message: String)

    private fun initAndStartRecorder(
        ctx: Context,
        preferredSource: Int,
        formatStr: String,
        quality: Int,
        pfd: ParcelFileDescriptor?,
        targetFile: File?
    ): RecorderInitResult {
        // Try preferred audio source first
        val firstAttempt = attemptRecorderStart(ctx, preferredSource, formatStr, quality, pfd, targetFile)
        if (firstAttempt.success) {
            return firstAttempt
        }

        // If preferred source was not MIC and failed (e.g. VOICE_RECOGNITION / VOICE_COMMUNICATION unsupported),
        // gracefully attempt fallback to standard MIC
        if (preferredSource != MediaRecorder.AudioSource.MIC) {
            safeLog("AudioSource $preferredSource failed (${firstAttempt.message}); attempting fallback to AudioSource.MIC")
            val fallbackAttempt = attemptRecorderStart(ctx, MediaRecorder.AudioSource.MIC, formatStr, quality, pfd, targetFile)
            if (fallbackAttempt.success) {
                return fallbackAttempt
            }
        }

        return firstAttempt
    }

    private fun attemptRecorderStart(
        ctx: Context,
        source: Int,
        formatStr: String,
        quality: Int,
        pfd: ParcelFileDescriptor?,
        targetFile: File?
    ): RecorderInitResult {
        var recorder: MediaRecorder? = null
        return try {
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(ctx)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(source)
                if (formatStr == "THREE_GPP") {
                    setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                } else {
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                }
                setAudioEncodingBitRate(quality)
                setAudioSamplingRate(if (formatStr == "THREE_GPP") 8000 else 44100)

                if (pfd != null) {
                    setOutputFile(pfd.fileDescriptor)
                } else {
                    setOutputFile(targetFile?.absolutePath)
                }

                prepare()
                start()
            }
            RecorderInitResult(success = true, recorder = recorder, message = "OK")
        } catch (e: Throwable) {
            try {
                recorder?.reset()
                recorder?.release()
            } catch (_: Exception) {}
            RecorderInitResult(success = false, recorder = null, message = e.message ?: "start() failed")
        }
    }

    /**
     * Stops the active call recording session.
     *
     * @param sessionId Optional session identifier.
     */
    open fun stopRecording(sessionId: String? = null): IpcResult {
        synchronized(lock) {
            return stopRecordingInternal(sessionId)
        }
    }

    private fun stopRecordingInternal(sessionId: String?): IpcResult {
        if (!isRecording) {
            safeLog("stopRecording called but not currently recording")
            return IpcResult.success(
                "Not currently recording",
                mapOf(CallIpcContract.KEY_RECORDING_ACTIVE to "false")
            )
        }

        val finishedSession = currentSessionId ?: sessionId ?: "unknown"
        val path = currentFilePath ?: ""
        val duration = if (startTimeMs > 0) System.currentTimeMillis() - startTimeMs else 0L

        try {
            mediaRecorder?.apply {
                try {
                    stop()
                } catch (e: Exception) {
                    safeLogError("MediaRecorder.stop() failed", e)
                }
                reset()
                release()
            }
            safeLog("Stopped call recording for session '$finishedSession' (duration=${duration}ms)")
        } catch (e: Throwable) {
            safeLogError("Error releasing MediaRecorder during stop", e)
        } finally {
            try { currentRecordPfd?.close() } catch (_: Exception) {}
            currentRecordPfd = null
            currentRecordFile = null
            mediaRecorder = null
            isRecording = false
            currentSessionId = null
            currentFilePath = null
            startTimeMs = 0L
        }

        reportStoppedToMain(finishedSession, path, duration)

        return IpcResult.success(
            "Recording stopped successfully",
            mapOf(
                CallIpcContract.KEY_SESSION_ID to finishedSession,
                CallIpcContract.KEY_RECORDING_ACTIVE to "false",
                CallIpcContract.KEY_FILE_PATH to path,
                CallIpcContract.KEY_DURATION_MS to duration.toString()
            )
        )
    }

    /**
     * Queries the current recorder state.
     */
    open fun getStatus(): IpcResult {

        synchronized(lock) {
            return IpcResult.success(
                "Call recorder status",
                mapOf(
                    CallIpcContract.KEY_RECORDING_ACTIVE to isRecording.toString(),
                    CallIpcContract.KEY_SESSION_ID to (currentSessionId ?: ""),
                    CallIpcContract.KEY_FILE_PATH to (currentFilePath ?: "")
                )
            )
        }
    }

    private fun reportStartedToMain(sessionId: String, filePath: String) {
        val host = getHost() ?: return
        val event = CallIpcContract.createRecordingStartedEvent(sessionId, filePath)
        host.reportEvent(event)
    }

    private fun reportStoppedToMain(sessionId: String, filePath: String, durationMs: Long) {
        val host = getHost() ?: return
        val event = CallIpcContract.createRecordingStoppedEvent(sessionId, filePath, durationMs)
        host.reportEvent(event)
    }

    private fun reportErrorToMain(sessionId: String, errorCode: String, message: String) {
        val host = getHost() ?: return
        val event = CallIpcContract.createRecordingErrorEvent(sessionId, errorCode, message)
        host.reportEvent(event)
    }

    companion object {
        private const val TAG = "HeavyCallRecorderEngine"

        @Volatile
        private var instance: HeavyCallRecorderEngine? = null

        fun getInstance(context: Context): HeavyCallRecorderEngine {
            return instance ?: synchronized(this) {
                instance ?: HeavyCallRecorderEngine(context.applicationContext).also { instance = it }
            }
        }

        internal fun resetForTesting() {
            instance = null
        }
    }
}
