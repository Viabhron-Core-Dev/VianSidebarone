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
    const val KEY_SESSION_ID = "session_id"
    const val KEY_FILE_PATH = "file_path"
    const val KEY_DURATION_MS = "duration_ms"
    const val KEY_ERROR_CODE = "error_code"
    const val KEY_ERROR_MESSAGE = "error_message"
    const val KEY_RECORDING_ACTIVE = "recording_active"

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

    // Events dispatched from Heavy to Main
    const val EVENT_RECORDING_STARTED = "CALL_RECORDING_STARTED"
    const val EVENT_RECORDING_STOPPED = "CALL_RECORDING_STOPPED"
    const val EVENT_RECORDING_ERROR = "CALL_RECORDING_ERROR"

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
        sessionId: String? = null,
        timestamp: Long = System.currentTimeMillis()
    ): HeavyCommand {
        val payload = mutableMapOf(
            KEY_RAW_STATE to rawState.toString(),
            KEY_CALL_STATE to stateStr,
            KEY_TRANSITION to transition,
            KEY_TIMESTAMP to timestamp.toString()
        )
        if (!sessionId.isNullOrEmpty()) {
            payload[KEY_SESSION_ID] = sessionId
        }
        return HeavyCommand(
            commandId = "call_${timestamp}_${stateStr.lowercase()}",
            type = HeavyCommandType.CALL_STATE_CHANGED,
            targetId = TARGET_CALL_RECORDER,
            payload = payload,
            timestamp = timestamp
        )
    }

    /**
     * Creates a minimal immutable HeavyCommand requesting a Call Recorder engine operation.
     */
    fun createRequestRecorderCommand(
        operation: String,
        sessionId: String? = null,
        timestamp: Long = System.currentTimeMillis()
    ): HeavyCommand {
        val payload = mutableMapOf(
            KEY_OPERATION to operation,
            KEY_TIMESTAMP to timestamp.toString()
        )
        if (!sessionId.isNullOrEmpty()) {
            payload[KEY_SESSION_ID] = sessionId
        }
        return HeavyCommand(
            commandId = "req_recorder_${timestamp}_${operation.lowercase()}",
            type = HeavyCommandType.REQUEST_CALL_RECORDER,
            targetId = TARGET_CALL_RECORDER,
            payload = payload,
            timestamp = timestamp
        )
    }

    /**
     * Creates a HeavyEvent indicating a recording session was successfully initiated.
     */
    fun createRecordingStartedEvent(
        sessionId: String,
        filePath: String,
        timestamp: Long = System.currentTimeMillis()
    ): HeavyEvent {
        return HeavyEvent(
            eventId = "rec_start_${timestamp}_${sessionId.take(8)}",
            type = HeavyEventType.STATE_UPDATED,
            sourceId = TARGET_CALL_RECORDER,
            payload = mapOf(
                KEY_OPERATION to EVENT_RECORDING_STARTED,
                KEY_SESSION_ID to sessionId,
                KEY_FILE_PATH to filePath,
                KEY_RECORDING_ACTIVE to "true",
                KEY_TIMESTAMP to timestamp.toString()
            ),
            timestamp = timestamp
        )
    }

    /**
     * Creates a HeavyEvent indicating a recording session was completed and saved.
     */
    fun createRecordingStoppedEvent(
        sessionId: String,
        filePath: String,
        durationMs: Long,
        timestamp: Long = System.currentTimeMillis()
    ): HeavyEvent {
        return HeavyEvent(
            eventId = "rec_stop_${timestamp}_${sessionId.take(8)}",
            type = HeavyEventType.OPERATION_FINISHED,
            sourceId = TARGET_CALL_RECORDER,
            payload = mapOf(
                KEY_OPERATION to EVENT_RECORDING_STOPPED,
                KEY_SESSION_ID to sessionId,
                KEY_FILE_PATH to filePath,
                KEY_DURATION_MS to durationMs.toString(),
                KEY_RECORDING_ACTIVE to "false",
                KEY_TIMESTAMP to timestamp.toString()
            ),
            timestamp = timestamp
        )
    }

    /**
     * Creates a HeavyEvent indicating a recording error or audio capture failure.
     */
    fun createRecordingErrorEvent(
        sessionId: String,
        errorCode: String,
        errorMessage: String,
        timestamp: Long = System.currentTimeMillis()
    ): HeavyEvent {
        return HeavyEvent(
            eventId = "rec_err_${timestamp}_${sessionId.take(8)}",
            type = HeavyEventType.ERROR_REPORTED,
            sourceId = TARGET_CALL_RECORDER,
            payload = mapOf(
                KEY_OPERATION to EVENT_RECORDING_ERROR,
                KEY_SESSION_ID to sessionId,
                KEY_ERROR_CODE to errorCode,
                KEY_ERROR_MESSAGE to errorMessage,
                KEY_RECORDING_ACTIVE to "false",
                KEY_TIMESTAMP to timestamp.toString()
            ),
            timestamp = timestamp
        )
    }
}

