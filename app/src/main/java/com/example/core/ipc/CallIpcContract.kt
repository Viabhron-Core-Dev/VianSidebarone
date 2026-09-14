package com.example.core.ipc

/**
 * Minimal IPC Contract for Call Sensor -> Heavy Process communication.
 *
 * Characteristics:
 * 1. Minimal payload: Exclusively transfers call state indicators, transitions, and operations.
 * 2. Privacy preserved: Never transmits phone numbers, contacts, call audio, or PII.
 * 3. Authority: Main process maintains the persistent Call Sensor; Heavy receives on-demand wake commands.
 */
object CallIpcContract {
    const val TARGET_CALL_RECORDER = "call_recorder"

    // Payload keys
    const val KEY_CALL_STATE = "call_state"
    const val KEY_RAW_STATE = "raw_state"
    const val KEY_TRANSITION = "transition"
    const val KEY_TIMESTAMP = "timestamp"
    const val KEY_OPERATION = "operation"

    // Canonical Call State constants
    const val STATE_IDLE = "IDLE"
    const val STATE_RINGING = "RINGING"
    const val STATE_OFFHOOK = "OFFHOOK"
    const val STATE_UNKNOWN = "UNKNOWN"

    // Transition Identifiers
    const val TRANSITION_RINGING = "CALL_RINGING"
    const val TRANSITION_STARTED = "CALL_STARTED"
    const val TRANSITION_ENDED = "CALL_ENDED"
    const val TRANSITION_UNCHANGED = "CALL_UNCHANGED"

    // Call Recorder Operations
    const val OP_START = "START"
    const val OP_STOP = "STOP"
    const val OP_STATUS = "STATUS"

    /**
     * Converts Android TelephonyManager integer call state to canonical string.
     */
    fun toCallStateString(rawState: Int): String {
        return when (rawState) {
            0 -> STATE_IDLE      // TelephonyManager.CALL_STATE_IDLE
            1 -> STATE_RINGING   // TelephonyManager.CALL_STATE_RINGING
            2 -> STATE_OFFHOOK   // TelephonyManager.CALL_STATE_OFFHOOK
            else -> STATE_UNKNOWN
        }
    }

    /**
     * Determines the state transition between previous and current call states.
     */
    fun determineTransition(previousState: Int, newState: Int): String {
        if (previousState == newState) return TRANSITION_UNCHANGED
        return when (newState) {
            1 -> TRANSITION_RINGING   // CALL_STATE_RINGING
            2 -> TRANSITION_STARTED   // CALL_STATE_OFFHOOK (Active Call)
            0 -> TRANSITION_ENDED     // CALL_STATE_IDLE (Call Finished)
            else -> TRANSITION_UNCHANGED
        }
    }

    /**
     * Creates a minimal immutable HeavyCommand for call state updates.
     */
    fun createCallStateCommand(
        rawState: Int,
        stateStr: String = toCallStateString(rawState),
        transition: String = TRANSITION_UNCHANGED,
        timestamp: Long = System.currentTimeMillis()
    ): HeavyCommand {
        return HeavyCommand(
            commandId = "call_${timestamp}_${stateStr.lowercase()}",
            type = HeavyCommandType.CALL_STATE_CHANGED,
            targetId = TARGET_CALL_RECORDER,
            payload = mapOf(
                KEY_RAW_STATE to rawState.toString(),
                KEY_CALL_STATE to stateStr,
                KEY_TRANSITION to transition,
                KEY_TIMESTAMP to timestamp.toString()
            ),
            timestamp = timestamp
        )
    }

    /**
     * Creates a minimal immutable HeavyCommand requesting a Call Recorder engine operation.
     */
    fun createRequestRecorderCommand(
        operation: String,
        timestamp: Long = System.currentTimeMillis()
    ): HeavyCommand {
        return HeavyCommand(
            commandId = "req_recorder_${timestamp}_${operation.lowercase()}",
            type = HeavyCommandType.REQUEST_CALL_RECORDER,
            targetId = TARGET_CALL_RECORDER,
            payload = mapOf(
                KEY_OPERATION to operation,
                KEY_TIMESTAMP to timestamp.toString()
            ),
            timestamp = timestamp
        )
    }
}
