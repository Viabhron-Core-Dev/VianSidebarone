package com.example.feature.system_hub

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

object MediaVolumeHandler {
    fun handleVolumeAction(context: Context, stream: String, action: String) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streamType = when (stream) {
            "ringer" -> AudioManager.STREAM_RING
            "media" -> AudioManager.STREAM_MUSIC
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "alarm" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }

        when (action) {
            "vol_up" -> am.adjustStreamVolume(streamType, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "vol_down" -> am.adjustStreamVolume(streamType, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    am.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                } else {
                    am.setStreamMute(streamType, true)
                }
            }
            "unmute" -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    am.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                } else {
                    am.setStreamMute(streamType, false)
                }
            }
            "toggle_mute" -> {
                val isMuted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    am.isStreamMute(streamType)
                } else {
                    am.getStreamVolume(streamType) == 0
                }
                val dir = if (isMuted) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_MUTE
                am.adjustStreamVolume(streamType, dir, AudioManager.FLAG_SHOW_UI)
            }
            "mode_silent" -> am.ringerMode = AudioManager.RINGER_MODE_SILENT
            "mode_vibrate" -> am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
            "mode_normal" -> am.ringerMode = AudioManager.RINGER_MODE_NORMAL
            "mode_cycle" -> {
                am.ringerMode = when (am.ringerMode) {
                    AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                    AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                    else -> AudioManager.RINGER_MODE_NORMAL
                }
            }
        }
    }

    fun handleMediaAction(context: Context, action: String) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val keyCode = when (action) {
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return
        }

        val eventDown = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val eventUp = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        am.dispatchMediaKeyEvent(eventDown)
        am.dispatchMediaKeyEvent(eventUp)
    }
}
