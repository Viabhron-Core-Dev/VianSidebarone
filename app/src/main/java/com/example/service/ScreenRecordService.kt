package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenRecordService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private var outputFile = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START_RECORDING") {
            val resultCode = intent.getIntExtra("resultCode", -1)
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("data", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("data")
            }

            if (resultCode != android.app.Activity.RESULT_OK || data == null) {
                stopSelf()
                return START_NOT_STICKY
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }

            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            
            // Adjust metrics for recording if needed
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi

            // Load settings
            val prefs = getSharedPreferences("ScreenCapPrefs", Context.MODE_PRIVATE)
            val quality = prefs.getInt("record_quality", 720)
            val recordAudio = prefs.getBoolean("record_audio", false)

            val isPortrait = screenHeight > screenWidth
            val smallerDim = if (isPortrait) screenWidth else screenHeight
            val largerDim = if (isPortrait) screenHeight else screenWidth
            
            var targetSmaller = smallerDim
            if (quality == 720 && smallerDim > 720) {
                targetSmaller = 720
            } else if (quality == 1080 && smallerDim > 1080) {
                targetSmaller = 1080
            }
            
            val scale = targetSmaller.toFloat() / smallerDim.toFloat()
            var targetLarger = (largerDim * scale).toInt()
            
            // Make them multiples of 16 for better encoder compatibility
            targetSmaller = (targetSmaller / 16) * 16
            targetLarger = (targetLarger / 16) * 16
            
            if (isPortrait) {
                screenWidth = targetSmaller
                screenHeight = targetLarger
            } else {
                screenWidth = targetLarger
                screenHeight = targetSmaller
            }

            startRecording(recordAudio)
        } else if (action == "STOP_RECORDING") {
            stopRecording()
        }
        return START_STICKY
    }

    private fun startRecording(recordAudio: Boolean) {
        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            
            if (recordAudio) {
                mediaRecorder?.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            mediaRecorder?.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mediaRecorder?.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

            val dir = File(cacheDir, "screen_records")
            if (!dir.exists()) dir.mkdirs()
            outputFile = File(dir, "record_${System.currentTimeMillis()}.mp4").absolutePath

            mediaRecorder?.setOutputFile(outputFile)
            mediaRecorder?.setVideoSize(screenWidth, screenHeight)
            mediaRecorder?.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (recordAudio) {
                mediaRecorder?.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            }
            mediaRecorder?.setVideoEncodingBitRate(5 * screenWidth * screenHeight)
            mediaRecorder?.setVideoFrameRate(30)

            mediaRecorder?.prepare()
            
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenRecordService",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder?.surface, null, null
            )

            mediaRecorder?.start()
            isRecording = true
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
            virtualDisplay?.release()
            mediaProjection?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaRecorder = null
        virtualDisplay = null
        mediaProjection = null

        saveRecording()
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun saveRecording() {
        val prefs = getSharedPreferences("ScreenCapPrefs", Context.MODE_PRIVATE)
        val saveLocation = prefs.getString("save_location", "Default (Pictures/Screenshots)") ?: "Default (Pictures/Screenshots)"
        
        try {
            val sourceFile = File(outputFile)
            if (!sourceFile.exists()) return

            if (saveLocation != "Default (Pictures/Screenshots)") {
                val uri = Uri.parse(saveLocation)
                val dir = DocumentFile.fromTreeUri(this, uri)
                if (dir != null && dir.isDirectory) {
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val fileName = "ScreenRecord_$timestamp.mp4"
                    val file = dir.createFile("video/mp4", fileName)
                    if (file != null) {
                        val out = contentResolver.openOutputStream(file.uri)
                        if (out != null) {
                            sourceFile.inputStream().copyTo(out)
                            out.flush()
                            out.close()
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(this, "Recording saved to custom location", Toast.LENGTH_SHORT).show()
                            }
                            sourceFile.delete()
                            return
                        }
                    }
                }
            }
            // Fallback if custom location fails or is default
            val defaultDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES), "ScreenRecords")
            if (!defaultDir.exists()) defaultDir.mkdirs()
            val destFile = File(defaultDir, "ScreenRecord_${System.currentTimeMillis()}.mp4")
            sourceFile.copyTo(destFile, true)
            sourceFile.delete()
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Recording saved to Movies/ScreenRecords", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Failed to save recording", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Recording",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = "STOP_RECORDING"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Recording")
            .setContentText("Tap to stop recording")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "screen_record_channel"
        const val NOTIFICATION_ID = 2000
        var isRecording = false
    }
}
