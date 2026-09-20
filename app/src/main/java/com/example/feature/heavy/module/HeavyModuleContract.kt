package com.example.feature.heavy.module

import android.content.Context

/**
 * Handle representing an active mount session of a Heavy module.
 * Owned by callers within the Heavy process or by the IPC layer on behalf of Main clients.
 */
interface HeavyModuleSession {
    val sessionToken: String
    val moduleId: String
    val descriptor: HeavyModuleDescriptor
    val ownerId: String?
    val isMounted: Boolean

    /**
     * Executes an operation on the mounted module instance.
     */
    fun use(operation: String, params: Map<String, String> = emptyMap()): HeavyModuleResult

    /**
     * Releases this mount session, decrementing the module's active reference count.
     */
    fun unmount(): Boolean
}

/**
 * Base contract for actual Heavy-process modules (:heavy).
 *
 * Lifecycle flow:
 * register (descriptor + factory) → acquire/mount (onMount) → use (onUse) → release/unmount (onUnmount) → dispose (onDispose)
 *
 * Modules MUST:
 * 1. Reside strictly in the Heavy process (:heavy).
 * 2. Remain lazy: never be instantiated until first mount.
 * 3. Clean up internal resources, views, or listeners in onDispose() when all sessions are unmounted.
 * 4. Handle multiple concurrent sessions cleanly via onMount/onUnmount.
 */
interface HeavyModuleContract {
    val descriptor: HeavyModuleDescriptor

    /**
     * Checks if this module can execute in the current runtime environment.
     */
    fun isAvailable(context: Context?): Boolean = true

    /**
     * Called when a new session mounts this module.
     *
     * @param sessionToken Unique token identifying this session.
     * @param params Initialization and configuration parameters.
     * @return [HeavyModuleResult] indicating mount success or failure.
     */
    fun onMount(sessionToken: String, params: Map<String, String>): HeavyModuleResult =
        HeavyModuleResult.success()

    /**
     * Executes an operation dispatched to an active session.
     */
    fun onUse(request: HeavyModuleExecutionRequest): HeavyModuleResult

    /**
     * Called when an active session unmounts.
     *
     * @param sessionToken The unmounting session token.
     * @return True if session was cleaned up successfully.
     */
    fun onUnmount(sessionToken: String): Boolean = true

    /**
     * Called when the module has 0 active sessions and is being evicted/discarded from memory.
     * Used to clean up memory, engines, caches, or window resources.
     */
    fun onDispose() {}
}

/**
 * Factory for lazy instantiation of [HeavyModuleContract] implementations.
 */
fun interface HeavyModuleFactory {
    fun create(context: Context?): HeavyModuleContract
}

/**
 * Listener invoked when all module sessions in Heavy become idle.
 */
fun interface HeavyModuleIdleListener {
    fun onIdle()
}
