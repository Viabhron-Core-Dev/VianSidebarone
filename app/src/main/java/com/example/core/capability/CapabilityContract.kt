package com.example.core.capability

import android.content.Context

/**
 * CapabilitySession: A client-facing handle representing an active mount session for a capability.
 * Enables Elements and runtime modules to interact with and release capabilities through
 * a stable contract without coupling to concrete implementations or process boundaries.
 */
interface CapabilitySession {
    val sessionToken: String
    val capabilityId: String
    val descriptor: CapabilityDescriptor
    val routing: CapabilityRouting
    val isMounted: Boolean

    /**
     * Executes a requested operation on the mounted capability.
     */
    fun use(operation: String, params: Map<String, String> = emptyMap()): CapabilityResult

    /**
     * Releases and unmounts this session, freeing associated resources.
     */
    fun unmount(): Boolean
}

/**
 * CapabilityContract: Universal contract for capability providers.
 * Both local Main-side modules and Heavy-side routers implement or adapt to this contract.
 */
interface CapabilityContract {
    val descriptor: CapabilityDescriptor

    /**
     * Checks whether this capability is executable/available in the current runtime environment.
     */
    fun isAvailable(context: Context?): Boolean = true

    /**
     * Mounts the capability for a new session.
     */
    fun onMount(sessionToken: String, params: Map<String, String>): CapabilityResult

    /**
     * Executes an operation for an active session.
     */
    fun onUse(request: CapabilityRequest): CapabilityResult

    /**
     * Unmounts and cleans up resources for the given session.
     */
    fun onUnmount(sessionToken: String): Boolean
}

/**
 * LocalCapabilityModule: Base interface for tiny, lightweight Main-process capability modules.
 * Must remain small, lazy, and never hold heavy UI/engine objects.
 */
interface LocalCapabilityModule : CapabilityContract {
    /**
     * Called when the module has zero remaining active sessions and is being discarded from memory.
     */
    fun onDiscard() {}
}

/**
 * Factory for on-demand lazy creation of LocalCapabilityModules.
 */
fun interface LocalCapabilityFactory {
    fun create(context: Context?): LocalCapabilityModule
}
