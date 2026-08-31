package com.example.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log

/**
 * CallRecorderManager: Minimal persistent call state listener.
 * Detects incoming/outgoing call sensor events when call recording is enabled in preferences.
 */
class CallRecorderManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(HandleManager.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val telephonyManager: TelephonyManager? by lazy {
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    }

    private var isListening = false
    private var telephonyCallback: Any? = null

    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    companion object {
        private const val TAG = "CallRecorderManager"
        const val KEY_CALL_RECORDER_ENABLED = "call_recorder_enabled"

        @Volatile
        private var instance: CallRecorderManager? = null

        fun getInstance(context: Context): CallRecorderManager {
            return instance ?: synchronized(this) {
                instance ?: CallRecorderManager(context.applicationContext).also { instance = it }
            }
        }
    }

    fun isEnabled(): Boolean {
        return prefs.getBoolean(KEY_CALL_RECORDER_ENABLED, false)
    }

    fun startListening() {
        if (isListening || !isEnabled()) return
        val tm = telephonyManager ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallState(state)
                    }
                }
                telephonyCallback = callback
                context.mainExecutor.let { executor ->
                    tm.registerTelephonyCallback(executor, callback)
                }
                isListening = true
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallState(state)
                    }
                }
                phoneStateListener = listener
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                isListening = true
            }
            Log.d(TAG, "Call recorder state listener started")
        } catch (e: SecurityException) {
            LogKeeper.logError(context, "CallRecorderManager", "Missing READ_PHONE_STATE permission", e)
        } catch (e: Exception) {
            LogKeeper.logError(context, "CallRecorderManager", "Failed to start listening", e)
        }
    }

    fun stopListening() {
        if (!isListening) return
        val tm = telephonyManager ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (telephonyCallback as? TelephonyCallback)?.let {
                    tm.unregisterTelephonyCallback(it)
                }
                telephonyCallback = null
            } else {
                phoneStateListener?.let {
                    @Suppress("DEPRECATION")
                    tm.listen(it, PhoneStateListener.LISTEN_NONE)
                }
                phoneStateListener = null
            }
            isListening = false
            Log.d(TAG, "Call recorder state listener stopped")
        } catch (e: Exception) {
            LogKeeper.logError(context, "CallRecorderManager", "Failed to stop listening", e)
        }
    }

    fun onCallStateReceived(state: Int) {
        handleCallState(state)
    }

    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                Log.d(TAG, "Call State: RINGING")
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.d(TAG, "Call State: OFFHOOK (Call Active)")
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                Log.d(TAG, "Call State: IDLE")
            }
        }
    }
}
