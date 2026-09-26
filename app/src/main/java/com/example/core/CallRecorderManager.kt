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
import com.example.core.ipc.HeavyConnectionListener
import com.example.core.ipc.HeavyEvent
import com.example.core.ipc.HeavyProcessConnectionManager
import com.example.core.ipc.IpcResult
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * CallRecorderManager: Lightweight resident Call Sensor and State Machine in the Main process (com.example).
 *
 * Guarantees:
 * 1. Minimal footprint: Holds ZERO audio buffers, MediaRecorder instances, or recording databases in Main.
 * 2. Idempotent state machine: Strictly protects against duplicate callbacks from BroadcastReceivers and TelephonyCallbacks.
 * 3. Authoritative source of truth: Tracks call state, expected recording intent, and active session tokens.
 * 4. Fault tolerance & recovery: Resynchronizes with the Heavy process (:heavy) across Binder on connect or process death.
 */
class CallRecorderManager internal constructor(
    private val context: Context?,
    private val connectionManager: HeavyProcessConnectionManager? = null
) : HeavyConnectionListener {

    private val lock = Any()

    private val prefs: SharedPreferences? by lazy {
        context?.getSharedPreferences(HandleManager.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val telephonyManager: TelephonyManager? by lazy {
        context?.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    }

    private val cm: HeavyProcessConnectionManager?
        get() = connectionManager ?: context?.let { HeavyProcessConnectionManager.getInstance(it) }

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

    @Volatile
    var isRecordingExpected: Boolean = false
        private set

    @Volatile
    var isHeavyRecordingActive: Boolean = false
        private set

    @Volatile
    var activeSessionId: String? = null
        private set

    @Volatile
    var activeRecordingPath: String? = null
        private set

    @Volatile
    var pendingOperation: String? = null
        private set

    @Volatile
    private var recoveryAttempted: Boolean = false

    private var telephonyCallback: Any? = null

    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    fun interface CallStateListener {
        fun onCallTransition(previousState: Int, newState: Int, transition: String)
    }

    fun interface RecordingSessionListener {
        fun onRecordingStateChanged(sessionId: String?, isRecording: Boolean, filePath: String?)
    }

    private val listeners = CopyOnWriteArrayList<CallStateListener>()
    private val recordingListeners = CopyOnWriteArrayList<RecordingSessionListener>()

    init {
        cm?.addListener(this)
    }

    fun addListener(listener: CallStateListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: CallStateListener) {
        listeners.remove(listener)
    }

    fun addRecordingListener(listener: RecordingSessionListener) {
        recordingListeners.add(listener)
    }

    fun removeRecordingListener(listener: RecordingSessionListener) {
        recordingListeners.remove(listener)
    }

    private fun safeLog(tag: String, msg: String) {
        context?.let { LogKeeper.log(it, tag, msg) }
    }

    private fun safeLogCrash(tag: String, t: Throwable) {
        context?.let { LogKeeper.logCrash(it, tag, t) }
    }

    internal var testEnabledOverride: Boolean? = null

    fun isEnabled(): Boolean {
        testEnabledOverride?.let { return it }
        return prefs?.getBoolean(KEY_CALL_RECORDER_ENABLED, false) ?: false
    }


    fun startListening() {
        if (isListening) return
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

    /**
     * Entry point for incoming phone state events from BroadcastReceivers (e.g. CallStateReceiver).
     */
    fun onCallStateReceived(state: Int) {
        handleCallState(state)
    }

    /**
     * Authoritative idempotent state machine execution for call state changes.
     */
    internal fun handleCallState(state: Int) {
        synchronized(lock) {
            val previousState = currentCallState

            // 1. Strict idempotence check: Ignore duplicate callbacks with unchanged state
            if (state == previousState) {
                return
            }

            val transition = CallIpcContract.determineTransition(previousState, state)
            currentCallState = state
            lastTransition = transition
            val stateStr = CallIpcContract.toCallStateString(state)

            Log.d(TAG, "Call state transition: $previousState -> $state ($stateStr, transition=$transition)")
            safeLog(TAG, "Call state transition: $stateStr (transition=$transition)")

            when (state) {
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    // Call connected: Generate a unique session token
                    val sessionId = "call_rec_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
                    activeSessionId = sessionId
                    recoveryAttempted = false

                    // 1. Dispatch call state change to Heavy
                    val stateCmd = CallIpcContract.createCallStateCommand(
                        rawState = state,
                        stateStr = stateStr,
                        transition = transition,
                        sessionId = sessionId
                    )
                    dispatchToHeavy(stateCmd)

                    // 2. If auto recording is configured in prefs, initiate START request to Heavy
                    if (isEnabled()) {
                        isRecordingExpected = true
                        pendingOperation = CallIpcContract.OP_START
                        val reqCmd = CallIpcContract.createRequestRecorderCommand(CallIpcContract.OP_START, sessionId)
                        val ipcResult = dispatchToHeavy(reqCmd)
                        if (ipcResult.success && ipcResult.data[CallIpcContract.KEY_RECORDING_ACTIVE] == "true") {
                            isHeavyRecordingActive = true
                            activeRecordingPath = ipcResult.data[CallIpcContract.KEY_FILE_PATH]
                            pendingOperation = null
                            notifyRecordingListeners(sessionId, true, activeRecordingPath)
                        }
                    } else {
                        isRecordingExpected = false
                        pendingOperation = null
                    }
                }

                TelephonyManager.CALL_STATE_IDLE -> {
                    val finishedSession = activeSessionId

                    // 1. Dispatch call state change to Heavy
                    val stateCmd = CallIpcContract.createCallStateCommand(
                        rawState = state,
                        stateStr = stateStr,
                        transition = transition,
                        sessionId = finishedSession
                    )
                    dispatchToHeavy(stateCmd)

                    // 2. Stop recording session if active or requested
                    if (previousState == TelephonyManager.CALL_STATE_OFFHOOK || isHeavyRecordingActive || isRecordingExpected) {
                        val reqCmd = CallIpcContract.createRequestRecorderCommand(CallIpcContract.OP_STOP, finishedSession)
                        dispatchToHeavy(reqCmd)
                    }

                    // Reset session state
                    isRecordingExpected = false
                    isHeavyRecordingActive = false
                    activeRecordingPath = null
                    pendingOperation = null
                    recoveryAttempted = false

                    activeSessionId = null
                    notifyRecordingListeners(finishedSession, false, null)
                }


                TelephonyManager.CALL_STATE_RINGING -> {
                    val stateCmd = CallIpcContract.createCallStateCommand(
                        rawState = state,
                        stateStr = stateStr,
                        transition = transition
                    )
                    dispatchToHeavy(stateCmd)
                }
            }

            notifyListeners(previousState, state, transition)
        }
    }

    private fun dispatchToHeavy(command: HeavyCommand): IpcResult {
        val connection = cm
        if (connection == null) {
            safeLog(TAG, "HeavyProcessConnectionManager unavailable; command not dispatched")
            return IpcResult.error(com.example.core.ipc.IpcErrorCode.HEAVY_UNAVAILABLE, "Connection manager is null")
        }

        return try {
            val result = connection.sendCommand(command, autoConnect = true)
            safeLog(TAG, "Dispatched ${command.type} (${command.payload[CallIpcContract.KEY_OPERATION] ?: ""}): success=${result.success}")
            result
        } catch (e: Throwable) {
            safeLogCrash(TAG, e)
            IpcResult.error(com.example.core.ipc.IpcErrorCode.UNKNOWN_ERROR, e.message ?: "dispatch failed")
        }
    }

    // --- HeavyConnectionListener (Recovery & Event Handling) ---

    override fun onConnected() {
        synchronized(lock) {
            safeLog(TAG, "Heavy process connected: checking Call Recorder state synchronization")
            // Re-sync: If a call is active and recording is expected, ensure Heavy starts the session
            if (currentCallState == TelephonyManager.CALL_STATE_OFFHOOK && isRecordingExpected && !isHeavyRecordingActive) {
                val sessionId = activeSessionId ?: "call_rec_${System.currentTimeMillis()}"
                activeSessionId = sessionId
                safeLog(TAG, "Resynchronizing call recording session after connect (sessionId=$sessionId)")
                val reqCmd = CallIpcContract.createRequestRecorderCommand(CallIpcContract.OP_START, sessionId)
                dispatchToHeavy(reqCmd)
            } else if (currentCallState == TelephonyManager.CALL_STATE_IDLE && isHeavyRecordingActive) {
                val reqCmd = CallIpcContract.createRequestRecorderCommand(CallIpcContract.OP_STOP, activeSessionId)
                dispatchToHeavy(reqCmd)
                isHeavyRecordingActive = false
            }
        }
    }

    override fun onHeavyProcessDied() {
        synchronized(lock) {
            safeLog(TAG, "Heavy process died while call state was $currentCallState")
            isHeavyRecordingActive = false

            // If call is ongoing, attempt recovery once without infinite looping
            if (currentCallState == TelephonyManager.CALL_STATE_OFFHOOK && isRecordingExpected && !recoveryAttempted) {
                recoveryAttempted = true
                safeLog(TAG, "Triggering on-demand reconnection to recover active call recording session")
                cm?.connect(autoCreate = true)
            }
        }
    }

    override fun onHeavyEvent(event: HeavyEvent) {
        if (event.sourceId != CallIpcContract.TARGET_CALL_RECORDER) return

        synchronized(lock) {
            val op = event.payload[CallIpcContract.KEY_OPERATION]
            val sessionId = event.payload[CallIpcContract.KEY_SESSION_ID]

            when (op) {
                CallIpcContract.EVENT_RECORDING_STARTED -> {
                    isHeavyRecordingActive = true
                    activeRecordingPath = event.payload[CallIpcContract.KEY_FILE_PATH]
                    pendingOperation = null
                    safeLog(TAG, "Heavy confirmed recording started for session '$sessionId' at $activeRecordingPath")
                    notifyRecordingListeners(sessionId, true, activeRecordingPath)
                }

                CallIpcContract.EVENT_RECORDING_STOPPED -> {
                    isHeavyRecordingActive = false
                    activeRecordingPath = null
                    pendingOperation = null
                    val duration = event.payload[CallIpcContract.KEY_DURATION_MS] ?: "0"
                    safeLog(TAG, "Heavy confirmed recording stopped for session '$sessionId' (duration=${duration}ms)")
                    notifyRecordingListeners(sessionId, false, null)
                }

                CallIpcContract.EVENT_RECORDING_ERROR -> {
                    isHeavyRecordingActive = false
                    pendingOperation = null
                    val errCode = event.payload[CallIpcContract.KEY_ERROR_CODE] ?: "UNKNOWN"
                    val errMsg = event.payload[CallIpcContract.KEY_ERROR_MESSAGE] ?: "Unknown error"
                    safeLog(TAG, "Heavy reported recording error: code=$errCode, msg=$errMsg")
                    notifyRecordingListeners(sessionId, false, null)
                }
            }
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

    private fun notifyRecordingListeners(sessionId: String?, isRecording: Boolean, filePath: String?) {
        for (listener in recordingListeners) {
            try {
                listener.onRecordingStateChanged(sessionId, isRecording, filePath)
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
