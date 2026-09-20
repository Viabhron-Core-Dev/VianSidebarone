package com.example.core.capability

import java.util.UUID

/**
 * Defines whether a capability is handled locally within the lightweight Main process,
 * or delegated across the Binder IPC boundary to the on-demand Heavy process (:heavy).
 */
enum class CapabilityRouting {
    LOCAL_MAIN,
    REMOTE_HEAVY
}

/**
 * Lifecycle state of a capability session.
 */
enum class CapabilityState {
    UNMOUNTED,
    MOUNTED,
    ERROR,
    RELEASED
}

/**
 * Standard error codes for capability resolution, acquisition, and execution failures.
 */
object CapabilityErrors {
    const val NOT_FOUND = "CAPABILITY_NOT_FOUND"
    const val UNAVAILABLE = "CAPABILITY_UNAVAILABLE"
    const val NOT_MOUNTED = "CAPABILITY_NOT_MOUNTED"
    const val ALREADY_MOUNTED = "CAPABILITY_ALREADY_MOUNTED"
    const val OPERATION_FAILED = "CAPABILITY_OPERATION_FAILED"
    const val UNSUPPORTED_OPERATION = "CAPABILITY_UNSUPPORTED_OPERATION"
    const val IPC_FAILURE = "CAPABILITY_IPC_FAILURE"
    const val HEAVY_UNAVAILABLE = "CAPABILITY_HEAVY_UNAVAILABLE"
    const val PROCESS_DIED = "CAPABILITY_PROCESS_DIED"
}

/**
 * Lightweight metadata descriptor for a capability.
 * Stored in the registry without instantiating any implementation, UI, or engine.
 */
data class CapabilityDescriptor(
    val capabilityId: String,
    val displayName: String,
    val routing: CapabilityRouting,
    val description: String = "",
    val version: Int = 1
)

/**
 * Immutable request dispatched to an active capability.
 */
data class CapabilityRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val capabilityId: String,
    val sessionToken: String,
    val operation: String,
    val parameters: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Standard response model returned from capability operations or lifecycle transitions.
 */
data class CapabilityResult(
    val success: Boolean,
    val data: Map<String, String> = emptyMap(),
    val errorCode: String? = null,
    val errorMessage: String? = null
) {
    companion object {
        fun success(data: Map<String, String> = emptyMap()): CapabilityResult {
            return CapabilityResult(success = true, data = data)
        }

        fun error(code: String, message: String, data: Map<String, String> = emptyMap()): CapabilityResult {
            return CapabilityResult(
                success = false,
                errorCode = code,
                errorMessage = message,
                data = data
            )
        }
    }
}

/**
 * Result wrapper for capability mount operations, returning the active session
 * handle (if successful) alongside status and error details.
 */
data class MountResult(
    val session: CapabilitySession?,
    val result: CapabilityResult
) {
    val isSuccess: Boolean get() = session != null && result.success
}
