package com.example.feature.system_hub

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import com.example.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class AudioRecordFloatingPanel private constructor(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs: SharedPreferences = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // MediaRecorder components
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var isPaused = false
    private var currentRecordFile: File? = null
    private var currentRecordPfd: ParcelFileDescriptor? = null
    private var currentDocumentFile: DocumentFile? = null
    private var currentDefaultName = ""

    // UI elements
    private var tvRecordTimer: TextView? = null
    private var ivRecStatus: ImageView? = null
    private var btnPausePlay: ImageButton? = null
    private var btnNext: ImageButton? = null
    private var btnStop: ImageButton? = null
    private var btnClose: ImageButton? = null
    private var llNameInputRow: LinearLayout? = null
    private var etRecordName: EditText? = null
    private var btnSaveName: ImageButton? = null
    private var btnCancelName: ImageButton? = null

    // Timer & Handler
    private val handler = Handler(Looper.getMainLooper())
    private var elapsedSeconds = 0L

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording && !isPaused) {
                elapsedSeconds++
                updateTimerText()
                // Pulse recording dot
                ivRecStatus?.let { dot ->
                    dot.alpha = if (elapsedSeconds % 2L == 0L) 1.0f else 0.3f
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun updateTimerText() {
        val minutes = elapsedSeconds / 60
        val seconds = elapsedSeconds % 60
        tvRecordTimer?.text = String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun show() {
        if (floatingView != null) return

        val inflater = LayoutInflater.from(context)
        floatingView = inflater.inflate(R.layout.overlay_audio_record, null)

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val density = context.resources.displayMetrics.density

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = (120 * density).toInt()
        }
        layoutParams = params

        initViews()
        setupDrag()

        try {
            windowManager.addView(floatingView, params)
            startNewRecording()
        } catch (e: Exception) {
            Log.e("AudioRecordPanel", "Failed to add floating view", e)
            com.example.core.LogKeeper.writeLog("AudioRecordPanel", "Failed to add view: ${e.message}")
            close()
        }
    }

    private fun initViews() {
        val root = floatingView ?: return
        tvRecordTimer = root.findViewById(R.id.tv_record_timer)
        ivRecStatus = root.findViewById(R.id.iv_rec_status)
        btnPausePlay = root.findViewById(R.id.btn_pause_play)
        btnNext = root.findViewById(R.id.btn_next)
        btnStop = root.findViewById(R.id.btn_stop)
        btnClose = root.findViewById(R.id.btn_close)
        llNameInputRow = root.findViewById(R.id.ll_name_input_row)
        etRecordName = root.findViewById(R.id.et_record_name)
        btnSaveName = root.findViewById(R.id.btn_save_name)
        btnCancelName = root.findViewById(R.id.btn_cancel_name)

        btnPausePlay?.setOnClickListener {
            togglePauseResume()
        }

        btnNext?.setOnClickListener {
            nextRecording()
        }

        btnStop?.setOnClickListener {
            stopAndPromptName()
        }

        btnClose?.setOnClickListener {
            close()
        }

        btnSaveName?.setOnClickListener {
            saveRenamedFile()
        }

        btnCancelName?.setOnClickListener {
            hideNameInput()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        val root = floatingView ?: return
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        root.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = initialTouchY - event.rawY
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDragging = true
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        try {
                            windowManager.updateViewLayout(root, params)
                        } catch (e: Exception) {}
                    }
                    isDragging
                }
                else -> false
            }
        }
    }

    private fun startNewRecording() {
        stopActiveRecordingInternal()

        elapsedSeconds = 0L
        isPaused = false
        updateTimerText()
        ivRecStatus?.alpha = 1.0f
        btnPausePlay?.setImageResource(android.R.drawable.ic_media_pause)
        btnPausePlay?.isEnabled = true
        llNameInputRow?.visibility = View.GONE

        try {
            val formatStr = prefs.getString("call_recorder_format", "MPEG_4") ?: "MPEG_4"
            val quality = prefs.getInt("call_recorder_quality", 128000)
            val saveFolderStr = prefs.getString("call_recorder_save_folder", "") ?: ""

            val ext = if (formatStr == "THREE_GPP") "3gp" else "m4a"
            val mime = if (formatStr == "THREE_GPP") "audio/3gpp" else "audio/mp4"
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            currentDefaultName = "AUDIO_$timeStamp"
            val fileName = "$currentDefaultName.$ext"

            currentRecordPfd = null
            currentRecordFile = null
            currentDocumentFile = null

            if (saveFolderStr.isNotEmpty()) {
                try {
                    val uri = Uri.parse(saveFolderStr)
                    val documentFile = DocumentFile.fromTreeUri(context, uri)
                    if (documentFile != null && documentFile.exists()) {
                        val newFile = documentFile.createFile(mime, fileName)
                        if (newFile != null) {
                            currentDocumentFile = newFile
                            currentRecordPfd = context.contentResolver.openFileDescriptor(newFile.uri, "w")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AudioRecordPanel", "SAF setup failed", e)
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
                setAudioSource(MediaRecorder.AudioSource.MIC)
                if (formatStr == "THREE_GPP") {
                    setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                    setAudioSamplingRate(8000)
                } else {
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(44100)
                }
                setAudioEncodingBitRate(quality)

                if (currentRecordPfd != null) {
                    setOutputFile(currentRecordPfd!!.fileDescriptor)
                } else {
                    setOutputFile(currentRecordFile?.absolutePath)
                }

                prepare()
                start()
            }

            isRecording = true
            handler.removeCallbacks(timerRunnable)
            handler.post(timerRunnable)
            com.example.core.LogKeeper.writeLog("AudioRecordPanel", "Recording started: $fileName")
        } catch (e: Exception) {
            Log.e("AudioRecordPanel", "Failed to start audio recording", e)
            com.example.core.LogKeeper.writeLog("AudioRecordPanel", "Failed to start: ${e.message}")
            Toast.makeText(context, "Record Error: ${e.message}", Toast.LENGTH_SHORT).show()
            stopActiveRecordingInternal()
        }
    }

    private fun togglePauseResume() {
        if (!isRecording) return
        val recorder = mediaRecorder ?: return

        try {
            if (!isPaused) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    recorder.pause()
                    isPaused = true
                    btnPausePlay?.setImageResource(android.R.drawable.ic_media_play)
                    ivRecStatus?.alpha = 0.4f
                    Toast.makeText(context, "Recording Paused", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Pause not supported on this Android version", Toast.LENGTH_SHORT).show()
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    recorder.resume()
                    isPaused = false
                    btnPausePlay?.setImageResource(android.R.drawable.ic_media_pause)
                    ivRecStatus?.alpha = 1.0f
                    Toast.makeText(context, "Recording Resumed", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("AudioRecordPanel", "Failed to toggle pause/resume", e)
        }
    }

    private fun nextRecording() {
        if (isRecording) {
            val currentName = currentDefaultName
            stopActiveRecordingInternal()
            Toast.makeText(context, "Saved: $currentName", Toast.LENGTH_SHORT).show()
        }
        startNewRecording()
    }

    private fun stopAndPromptName() {
        if (!isRecording && llNameInputRow?.visibility == View.VISIBLE) {
            return
        }

        stopActiveRecordingInternal()

        // Switch to focusable for name input
        val params = layoutParams
        val root = floatingView
        if (params != null && root != null) {
            params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            try {
                windowManager.updateViewLayout(root, params)
            } catch (e: Exception) {}
        }

        etRecordName?.setText(currentDefaultName)
        llNameInputRow?.visibility = View.VISIBLE
        btnPausePlay?.isEnabled = false
        ivRecStatus?.alpha = 0.3f
        Toast.makeText(context, "Recording Completed", Toast.LENGTH_SHORT).show()
    }

    private fun saveRenamedFile() {
        val newName = etRecordName?.text?.toString()?.trim()
        if (!newName.isNullOrEmpty() && newName != currentDefaultName) {
            val ext = if (currentRecordFile?.name?.endsWith(".3gp") == true || currentDocumentFile?.name?.endsWith(".3gp") == true) "3gp" else "m4a"
            val targetFileName = if (newName.endsWith(".$ext")) newName else "$newName.$ext"

            try {
                if (currentRecordFile != null && currentRecordFile!!.exists()) {
                    val renamed = File(currentRecordFile!!.parentFile, targetFileName)
                    if (currentRecordFile!!.renameTo(renamed)) {
                        currentRecordFile = renamed
                        currentDefaultName = newName
                    }
                } else if (currentDocumentFile != null && currentDocumentFile!!.exists()) {
                    currentDocumentFile?.renameTo(targetFileName)
                    currentDefaultName = newName
                }
                Toast.makeText(context, "Saved as $targetFileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("AudioRecordPanel", "Failed to rename file", e)
            }
        } else {
            Toast.makeText(context, "Saved as $currentDefaultName", Toast.LENGTH_SHORT).show()
        }

        hideNameInput()
    }

    private fun hideNameInput() {
        llNameInputRow?.visibility = View.GONE

        // Restore non-focusable layout flag
        val params = layoutParams
        val root = floatingView
        if (params != null && root != null) {
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            try {
                windowManager.updateViewLayout(root, params)
            } catch (e: Exception) {}
        }
    }

    private fun stopActiveRecordingInternal() {
        if (isRecording) {
            try {
                mediaRecorder?.apply {
                    stop()
                    reset()
                    release()
                }
            } catch (e: Exception) {
                Log.e("AudioRecordPanel", "Error stopping MediaRecorder", e)
            }
            mediaRecorder = null
            isRecording = false
            isPaused = false
            handler.removeCallbacks(timerRunnable)
        }

        try {
            currentRecordPfd?.close()
        } catch (e: Exception) {}
        currentRecordPfd = null
    }

    fun close() {
        stopActiveRecordingInternal()

        if (floatingView != null) {
            try {
                windowManager.removeView(floatingView)
            } catch (e: Exception) {}
            floatingView = null
        }

        if (instance == this) {
            instance = null
        }
        com.example.core.LogKeeper.writeLog("AudioRecordPanel", "Audio record panel closed")
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        private var instance: AudioRecordFloatingPanel? = null

        val isShowing: Boolean
            get() = instance != null

        fun show(context: Context) {
            if (instance == null) {
                instance = AudioRecordFloatingPanel(context.applicationContext).apply {
                    show()
                }
            }
        }

        fun hide() {
            instance?.close()
            instance = null
        }

        fun toggle(context: Context) {
            if (instance != null) {
                hide()
            } else {
                show(context)
            }
        }
    }
}
