package com.example.feature.system_hub

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.SettingsActivity
import com.example.core.LogKeeper

/**
 * RecordingActionHelper: Unified entry point for recording capabilities across Sidebar, Settings, and Elements.
 *
 * Guarantees:
 * 1. Single recording subsystem: Sidebar elements and Settings features share the exact same underlying actions,
 *    audio configurations, and storage directories (.Records / SAF tree).
 * 2. Safe permission handling: Checks RECORD_AUDIO and launches trampoline activity when needed.
 * 3. Reusable across components: ElementActionDispatcher, CallRecorderSettingsScreen, and Settings shortcuts
 *    all invoke these standardized entry points.
 */
object RecordingActionHelper {

    private const val TAG = "RecordingActionHelper"

    /**
     * Starts or toggles the audio record panel, checking runtime permissions first via trampoline activity.
     */
    fun startOrToggleAudioRecord(context: Context) {
        val hasMic = AudioRecordFloatingPanel.permissionChecker(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (hasMic) {
            LogKeeper.log(context, TAG, "Toggling AudioRecordFloatingPanel")
            AudioRecordFloatingPanel.toggle(context)
        } else {
            LogKeeper.log(context, TAG, "RECORD_AUDIO missing, launching AudioRecordPermissionActivity")
            val intent = Intent(context, AudioRecordPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * Opens the unified RecordingsActivity browsing saved call and audio recordings.
     */
    fun openRecordings(context: Context) {
        LogKeeper.log(context, TAG, "Opening RecordingsActivity")
        val intent = Intent(context, RecordingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Opens Call Recorder settings screen in SettingsActivity.
     */
    fun openCallRecorderSettings(context: Context) {
        LogKeeper.log(context, TAG, "Opening Call Recorder Settings")
        val intent = Intent(context, SettingsActivity::class.java).apply {
            putExtra("start_route", "call_recorder")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
