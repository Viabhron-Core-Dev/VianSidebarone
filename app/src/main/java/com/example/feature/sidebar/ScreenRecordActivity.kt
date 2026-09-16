package com.example.feature.sidebar

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import com.example.service.ScreenRecordService

class ScreenRecordActivity : Activity() {

    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (ScreenRecordService.isRecording) {
            val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
                action = "STOP_RECORDING"
            }
            startService(stopIntent)
            finish()
            return
        }

        val prefs = getSharedPreferences("ScreenCapPrefs", Context.MODE_PRIVATE)
        val recordAudio = prefs.getBoolean("record_audio", false)

        if (recordAudio && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO_CODE)
            return
        }

        startProjection()
    }

    private fun startProjection() {
        val pm = packageManager
        
        // Try getting launch intent for MIUI screen recorder
        val launchIntent = pm.getLaunchIntentForPackage("com.miui.screenrecorder")
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(launchIntent)
                finish()
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // If not, try explicit component
        try {
            val miuiIntent = Intent().apply {
                setClassName("com.miui.screenrecorder", "com.miui.screenrecorder.activity.ScreenRecorderHomeActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(miuiIntent)
            finish()
            return
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        try {
            val miuiIntent = Intent().apply {
                setClassName("com.miui.screenrecorder", "com.miui.screenrecorder.ScreenRecorderActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(miuiIntent)
            finish()
            return
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Try Xiaomi's screen recorder quick setting tile action or just the package
        try {
            val intent = Intent("miui.intent.action.SCREEN_RECORDER")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
            return
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Fallback to our own MediaProjection
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startProjection()
            } else {
                Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                val serviceIntent = Intent(this, ScreenRecordService::class.java).apply {
                    action = "START_RECORDING"
                    putExtra("resultCode", resultCode)
                    putExtra("data", data)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } else {
                Toast.makeText(this, "Screen recording permission denied", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    companion object {
        private const val REQUEST_CODE = 1000
        private const val REQUEST_AUDIO_CODE = 1001
    }
}
