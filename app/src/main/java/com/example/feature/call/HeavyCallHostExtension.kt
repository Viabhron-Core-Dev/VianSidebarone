package com.example.feature.call

import com.example.core.ipc.CallIpcContract
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.IpcResult

/**
 * Extension point for the future Call Recorder engine in the Heavy process (:heavy).
 *
 * Requirements:
 * 1. Heavy process receives only small explicit call-related commands.
 * 2. Heavy must NOT implement audio recording, UI, or recording engines yet.
 * 3. Acknowledges and routes call state transitions safely without failing.
 */
interface HeavyCallHostExtension {
    /**
     * Invoked when a call state transition is received from the Main process.
     */
    fun onCallStateChanged(command: HeavyCommand): IpcResult

    /**
     * Invoked when the Main process requests a Call Recorder operation (e.g. START, STOP).
     */
    fun onRequestCallRecorder(command: HeavyCommand): IpcResult
}

/**
 * Default foundation implementation of HeavyCallHostExtension.
 * Serves as the placeholder acknowledging requests until the full Call Recorder engine is implemented.
 */
class DefaultHeavyCallHostExtension : HeavyCallHostExtension {

    override fun onCallStateChanged(command: HeavyCommand): IpcResult {
        val stateStr = command.payload[CallIpcContract.KEY_CALL_STATE] ?: CallIpcContract.STATE_UNKNOWN
        val transition = command.payload[CallIpcContract.KEY_TRANSITION] ?: CallIpcContract.TRANSITION_UNCHANGED
        return IpcResult.success(
            "Heavy host acknowledged call state: $stateStr ($transition)",
            mapOf("call_state" to stateStr, "transition" to transition)
        )
    }

    override fun onRequestCallRecorder(command: HeavyCommand): IpcResult {
        val operation = command.payload[CallIpcContract.KEY_OPERATION] ?: "UNKNOWN"
        return IpcResult.success(
            "Heavy host acknowledged call recorder operation: $operation",
            mapOf("operation" to operation, "status" to "acknowledged")
        )
    }
}
