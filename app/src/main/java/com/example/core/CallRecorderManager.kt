package com.example.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import com.example.core.ipc.CallIpcContract
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyProcessConnectionManager
import java.util.concurrent.CopyOnWriteArrayList

/**
 * CallRecorderManager: Lightweight resident Call Sensor in the Main process (com.example).
 *
 * Architecture:
 * 1. Resident in Main process: Operates continuously and remains available when screen is OFF/locked.
 * 2. Decoupled from screen-ON lifecycle: Does not depend on handles, gestures, or NetSpeed polling.
 * 3. Minimal IPC: On call state transitions, wakes/notifies the Heavy process (:heavy) with minimal commands.
 * 4. Fault Tolerant: Dispatches across Binder safely; remains stable if Heavy is dead or unavailable.
 */
class CallRecorderManager internal constructor(
    private val context: Context?,
    private val connectionManager: HeavyProcessConnectionManager? = null
) {

    private val prefs: SharedPreferences? by lazy {
        context?.getSharedPreferences(HandleManager.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val telephonyManager: TelephonyManager? by lazy {
        context?.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    }

    @Volatile
    var isListening = false
        private set

    /**
     * Call sensor availability indicator: Remains active during screen-off / locked states.
     */
    val isAvailableDuringScreenOff: Boolean = true

    @Volatile
    var currentCallState: Int = TelephonyManager.CALL_STATE_IDLE
        private set

    @Volatile
    var lastTransition: String = CallIpcContract.TRANSITION_UNCHANGED
        private set

    private var telephonyCallback: Any? = null

    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    fun interface CallStateListener {
        fun onCallTransition(previousState: Int, newState: Int, transition: String)
    }

    private val listeners = CopyOnWriteArrayList<CallStateListener>()

    fun addListener(listener: CallStateListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: CallStateListener) {
        listeners.remove(listener)
    }

    private fun safeLog(tag: String, msg: String) {
        context?.let { LogKeeper.log(it, tag, msg) }
    }

    private fun safeLogCrash(tag: String, t: Throwable) {
        context?.let { LogKeeper.logCrash(it, tag, t) }
    }

    fun isEnabled(): Boolean {
        return prefs?.getBoolean(KEY_CALL_RECORDER_ENABLED, false) ?: true
    }

    fun startListening() {
        if (isListening || !isEnabled()) return
        val tm = telephonyManager ?: return
        val ctx = context ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallState(state)
                    }
                }
                telephonyCallback = callback
                ctx.mainExecutor.let { executor ->
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
            Log.d(TAG, "Call recorder state listener started in Main process")
            safeLog(TAG, "Call recorder state listener started")
        } catch (e: SecurityException) {
            safeLogCrash(TAG, e)
        } catch (e: Exception) {
            safeLogCrash(TAG, e)
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
            safeLog(TAG, "Call recorder state listener stopped")
        } catch (e: Exception) {
            safeLogCrash(TAG, e)
        }
    }

    fun onCallStateReceived(state: Int) {
        handleCallState(state)
    }

    internal fun handleCallState(state: Int) {
        val previousState = currentCallState
        if (state == previousState && state != TelephonyManager.CALL_STATE_IDLE) {
            return
        }

        val transition = CallIpcContract.determineTransition(previousState, state)
        currentCallState = state
        lastTransition = transition

        val stateStr = CallIpcContract.toCallStateString(state)
        Log.d(TAG, "Call state transition: $previousState -> $state ($stateStr, transition=$transition)")
        safeLog(TAG, "Call state transition: $stateStr (transition=$transition)")

        notifyListeners(previousState, state, transition)

        // 1. Dispatch Call State Changed command to Heavy process
        val stateCmd = CallIpcContract.createCallStateCommand(
            rawState = state,
            stateStr = stateStr,
            transition = transition
        )
        dispatchToHeavy(stateCmd)

        // 2. Dispatch Call Recorder Operation if entering or leaving active call
        if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
            val reqCmd = CallIpcContract.createRequestRecorderCommand(CallIpcContract.OP_START)
            dispatchToHeavy(reqCmd)
        } else if (state == TelephonyManager.CALL_STATE_IDLE && previousState == TelephonyManager.CALL_STATE_OFFHOOK) {
            val reqCmd = CallIpcContract.createRequestRecorderCommand(CallIpcContract.OP_STOP)
            dispatchToHeavy(reqCmd)
        }
    }

    private fun dispatchToHeavy(command: HeavyCommand) {
        val cm = connectionManager ?: context?.let {
            HeavyProcessConnectionManager.getInstance(it)
        }
        if (cm == null) {
            safeLog(TAG, "HeavyProcessConnectionManager unavailable; command not dispatched")
            return
        }

        try {
            val result = cm.sendCommand(command, autoConnect = true)
            safeLog(TAG, "Dispatched ${command.type} to Heavy: success=${result.success}, code=${result.errorCode}")
        } catch (e: Throwable) {
            safeLogCrash(TAG, e)
        }
    }

    private fun notifyListeners(previousState: Int, newState: Int, transition: String) {
        for (listener in listeners) {
            try {
                listener.onCallTransition(previousState, newState, transition)
            } catch (e: Throwable) {
                safeLogCrash(TAG, e)
            }
        }
    }

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

        internal fun resetInstanceForTesting() {
            instance = null
        }
    }
}
