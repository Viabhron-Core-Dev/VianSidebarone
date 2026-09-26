package com.example.feature.call

import android.content.Context
import android.content.SharedPreferences
import com.example.core.HandleManager
import com.example.core.LogKeeper
import com.example.core.ipc.CallIpcContract
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.IpcErrorCode
import com.example.core.ipc.IpcResult

/**
 * Extension point for the Call Recorder engine in the Heavy process (:heavy).
 */
interface HeavyCallHostExtension {
    /**
     * Invoked when a call state transition is received from the Main process.
     */
    fun onCallStateChanged(command: HeavyCommand): IpcResult

    /**
     * Invoked when the Main process requests a Call Recorder operation (e.g. START, STOP, STATUS).
     */
    fun onRequestCallRecorder(command: HeavyCommand): IpcResult

    /**
     * Indicates whether an audio recording session is currently active.
     */
    fun isRecordingActive(): Boolean = false

    /**
     * Returns the underlying recorder engine if present.
     */
    fun getEngine(): HeavyCallRecorderEngine? = null
}

/**
 * RealHeavyCallHostExtension: Connects IPC requests and call state transitions
 * directly to the real HeavyCallRecorderEngine.
 */
open class RealHeavyCallHostExtension(
    private val context: Context?,
    private val recorderEngine: HeavyCallRecorderEngine? = null
) : HeavyCallHostExtension {

    private val internalEngine: HeavyCallRecorderEngine by lazy {
        recorderEngine ?: (context?.let { HeavyCallRecorderEngine.getInstance(it) } ?: HeavyCallRecorderEngine(null))
    }

    private val prefs: SharedPreferences? by lazy {
        context?.getSharedPreferences(HandleManager.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun safeLog(msg: String) {
        context?.let { LogKeeper.log(it, TAG, msg) }
    }

    override fun isRecordingActive(): Boolean = internalEngine.isRecording

    override fun getEngine(): HeavyCallRecorderEngine = internalEngine

    override fun onCallStateChanged(command: HeavyCommand): IpcResult {
        val stateStr = command.payload[CallIpcContract.KEY_CALL_STATE] ?: CallIpcContract.STATE_UNKNOWN
        val transition = command.payload[CallIpcContract.KEY_TRANSITION] ?: CallIpcContract.TRANSITION_UNCHANGED
        val sessionId = command.payload[CallIpcContract.KEY_SESSION_ID]
            ?: "session_${command.timestamp}"

        safeLog("Received CALL_STATE_CHANGED: state=$stateStr, transition=$transition, session=$sessionId")

        val autoEnabled = prefs?.getBoolean("call_recorder_enabled", false) ?: false

        when (transition) {
            CallIpcContract.TRANSITION_STARTED -> {
                if (autoEnabled && !internalEngine.isRecording) {
                    safeLog("Auto-record enabled: starting recording session for $sessionId")
                    internalEngine.startRecording(sessionId)
                }
            }
            CallIpcContract.TRANSITION_ENDED -> {
                if (internalEngine.isRecording) {
                    safeLog("Call ended: stopping active recording session")
                    internalEngine.stopRecording(sessionId)
                }
            }
        }

        return IpcResult.success(
            "Heavy host handled call state: $stateStr ($transition)",
            mapOf(
                CallIpcContract.KEY_CALL_STATE to stateStr,
                CallIpcContract.KEY_TRANSITION to transition,
                CallIpcContract.KEY_RECORDING_ACTIVE to internalEngine.isRecording.toString()
            )
        )
    }

    override fun onRequestCallRecorder(command: HeavyCommand): IpcResult {
        val operation = command.payload[CallIpcContract.KEY_OPERATION] ?: "UNKNOWN"
        val sessionId = command.payload[CallIpcContract.KEY_SESSION_ID]
            ?: "session_${command.timestamp}"

        safeLog("Received REQUEST_CALL_RECORDER: op=$operation, session=$sessionId")

        return when (operation) {
            CallIpcContract.OP_START -> {
                internalEngine.startRecording(sessionId)
            }
            CallIpcContract.OP_STOP -> {
                internalEngine.stopRecording(sessionId)
            }
            CallIpcContract.OP_STATUS -> {
                internalEngine.getStatus()
            }
            else -> {
                IpcResult.error(
                    IpcErrorCode.UNKNOWN_ERROR,
                    "Unsupported call recorder operation: $operation"
                )
            }
        }
    }

    companion object {
        private const val TAG = "RealHeavyCallHostExtension"
    }
}

/**
 * Backwards-compatibility alias and default implementation.
 */
typealias DefaultHeavyCallHostExtension = RealHeavyCallHostExtension
