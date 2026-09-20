package com.example.feature.heavy.module

import java.util.UUID

/**
 * Standard error codes for Heavy module operations.
 */
object HeavyModuleErrors {
    const val NOT_FOUND = "MODULE_NOT_FOUND"
    const val UNAVAILABLE = "MODULE_UNAVAILABLE"
    const val NOT_MOUNTED = "MODULE_NOT_MOUNTED"
    const val OPERATION_FAILED = "MODULE_OPERATION_FAILED"
    const val INVALID_ARGUMENTS = "MODULE_INVALID_ARGUMENTS"
    const val ALREADY_RELEASED = "MODULE_ALREADY_RELEASED"
}

/**
 * Immutable descriptor defining a module available in the Heavy process (:heavy).
 *
 * @param moduleId Unique identifier for the module (e.g. "heavy_reader", "heavy_dictionary").
 * @param displayName Human-readable module name.
 * @param version Schema/contract version of the module.
 * @param description Brief description of module functionality.
 * @param category Category grouping for the module.
 * @param isLazy Whether the module is instantiated on-demand (defaults to true).
 * @param metadata Arbitrary key-value metadata.
 */
data class HeavyModuleDescriptor(
    val moduleId: String,
    val displayName: String = moduleId,
    val version: Int = 1,
    val description: String = "",
    val category: String = "general",
    val isLazy: Boolean = true,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Immutable execution request dispatched to an active Heavy module session.
 */
data class HeavyModuleExecutionRequest(
    val moduleId: String,
    val sessionToken: String,
    val operation: String,
    val parameters: Map<String, String> = emptyMap(),
    val requestId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Result returned by Heavy module operations and lifecycle methods.
 */
data class HeavyModuleResult(
    val success: Boolean,
    val message: String = "",
    val data: Map<String, String> = emptyMap(),
    val errorCode: String = ""
) {
    companion object {
        fun success(
            data: Map<String, String> = emptyMap(),
            message: String = "Success"
        ): HeavyModuleResult = HeavyModuleResult(
            success = true,
            message = message,
            data = data
        )

        fun error(
            errorCode: String,
            message: String,
            data: Map<String, String> = emptyMap()
        ): HeavyModuleResult = HeavyModuleResult(
            success = false,
            message = message,
            errorCode = errorCode,
            data = data
        )
    }
}

/**
 * Result returned upon mounting or acquiring a Heavy module.
 */
data class HeavyModuleMountResult(
    val session: HeavyModuleSession?,
    val result: HeavyModuleResult
) {
    val isSuccess: Boolean get() = session != null && result.success
}
