package com.example.feature.heavy.module

import android.content.Context
import com.example.core.LogKeeper
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.IpcErrorCode
import com.example.core.ipc.IpcResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Internal implementation of [HeavyModuleSession].
 */
private class HeavyModuleSessionImpl(
    override val sessionToken: String,
    override val moduleId: String,
    override val descriptor: HeavyModuleDescriptor,
    override val ownerId: String?,
    private val manager: HeavyModuleManager
) : HeavyModuleSession {

    @Volatile
    private var mounted: Boolean = true

    override val isMounted: Boolean get() = mounted

    fun markUnmounted() {
        mounted = false
    }

    override fun use(operation: String, params: Map<String, String>): HeavyModuleResult {
        if (!mounted) {
            return HeavyModuleResult.error(
                HeavyModuleErrors.NOT_MOUNTED,
                "Session '$sessionToken' is already unmounted"
            )
        }
        return manager.use(sessionToken, operation, params)
    }

    override fun unmount(): Boolean {
        return manager.unmount(sessionToken)
    }
}

/**
 * HeavyModuleManager: Resident module registry, lifecycle manager, and operation dispatcher
 * running strictly inside the Heavy process (:heavy).
 *
 * Counterpart to Main's CapabilityManager:
 * Main:  Element → CapabilityManager → HeavyCapabilityRouter (IPC)
 * Heavy: HeavyProcessHost (IPC) → HeavyModuleManager → actual future Heavy Module
 *
 * Guarantees:
 * 1. Process-local lifecycle only: Lives strictly inside :heavy; disappears cleanly if :heavy dies.
 * 2. Modules are strictly lazy: Registered descriptors/factories do NOT create module instances.
 * 3. Reference-counted session management: Modules are instantiated upon first mount, shared across
 *    sessions, and disposed immediately when active session count reaches 0.
 * 4. Safe repeated unmount: Unmounting an already unmounted session returns false safely.
 * 5. Owner-scoped cleanup: Supports bulk unmounting by owner identifier.
 * 6. Graceful error handling: Handles unknown, unavailable, or failing modules without crashing :heavy.
 * 7. Zero watchdogs, zero polling loops, zero permanent background services.
 */
class HeavyModuleManager internal constructor(private val context: Context? = null) {

    private val descriptors = ConcurrentHashMap<String, HeavyModuleDescriptor>()
    private val factories = ConcurrentHashMap<String, HeavyModuleFactory>()

    // Active module instances in memory and their active session reference counts
    private val activeModules = ConcurrentHashMap<String, HeavyModuleContract>()
    private val moduleRefCounts = ConcurrentHashMap<String, AtomicInteger>()

    // Active sessions and owner mappings
    private val activeSessions = ConcurrentHashMap<String, HeavyModuleSessionImpl>()
    private val ownerSessions = ConcurrentHashMap<String, MutableSet<String>>()

    @Volatile
    private var idleListener: HeavyModuleIdleListener? = null

    private fun safeLog(msg: String) {
        context?.let { LogKeeper.log(it, TAG, msg) }
    }

    private fun safeLogError(msg: String, t: Throwable) {
        context?.let { LogKeeper.logError(it, TAG, msg, t) }
    }

    /**
     * Sets a callback triggered when all active module sessions drop to zero.
     */
    fun setIdleListener(listener: HeavyModuleIdleListener?) {
        this.idleListener = listener
    }

    /**
     * Registers a module descriptor with a lazy factory.
     * The factory is NOT invoked during registration; instantiation occurs only upon first mount.
     */
    fun register(descriptor: HeavyModuleDescriptor, factory: HeavyModuleFactory) {
        descriptors[descriptor.moduleId] = descriptor
        factories[descriptor.moduleId] = factory
        safeLog("Registered Heavy module: ${descriptor.moduleId} (lazy=${descriptor.isLazy})")
    }

    /**
     * Convenience registration taking a lambda constructor.
     */
    fun register(descriptor: HeavyModuleDescriptor, supplier: (Context?) -> HeavyModuleContract) {
        register(descriptor, HeavyModuleFactory { ctx -> supplier(ctx) })
    }

    /**
     * Unregisters a module descriptor and factory, unmounting any active sessions.
     */
    @Synchronized
    fun unregister(moduleId: String): Boolean {
        val removedDescriptor = descriptors.remove(moduleId) ?: return false
        factories.remove(moduleId)

        // Unmount all active sessions using this module
        val sessionsToUnmount = activeSessions.values
            .filter { it.moduleId == moduleId }
            .map { it.sessionToken }

        for (token in sessionsToUnmount) {
            unmount(token)
        }

        safeLog("Unregistered Heavy module: ${removedDescriptor.moduleId}")
        return true
    }

    /**
     * Retrieves the descriptor for a module ID, if registered.
     */
    fun getDescriptor(moduleId: String): HeavyModuleDescriptor? = descriptors[moduleId]

    /**
     * Retrieves all registered module descriptors without instantiating any module.
     */
    fun getAllDescriptors(): List<HeavyModuleDescriptor> = descriptors.values.toList()

    /**
     * Checks if a module is registered in this manager.
     */
    fun isRegistered(moduleId: String): Boolean = descriptors.containsKey(moduleId)

    /**
     * Checks if a module is registered and available in the current runtime environment.
     */
    fun isAvailable(moduleId: String): Boolean {
        if (!descriptors.containsKey(moduleId)) return false
        val active = activeModules[moduleId]
        return if (active != null) {
            try {
                active.isAvailable(context)
            } catch (e: Throwable) {
                safeLogError("Error checking availability for active module '$moduleId'", e)
                false
            }
        } else {
            // Uninstantiated module: availability is true if factory is present
            factories.containsKey(moduleId)
        }
    }

    /**
     * Checks whether a module instance currently exists in memory.
     */
    fun isModuleActive(moduleId: String): Boolean = activeModules.containsKey(moduleId)

    /**
     * Returns the count of active module instances loaded in memory.
     */
    fun getActiveModuleCount(): Int = activeModules.size

    /**
     * Returns the total count of active sessions across all modules.
     */
    fun getActiveSessionCount(): Int = activeSessions.size

    /**
     * Mounts/acquires a module for a session.
     * Instantiates the module lazily if no other active sessions exist for it.
     *
     * @param moduleId The target module identifier.
     * @param sessionToken An explicit session token, or auto-generated if blank.
     * @param ownerId Optional owner identifier (e.g. element placement or page) for grouped cleanup.
     * @param params Initialization and configuration parameters.
     * @return [HeavyModuleMountResult] containing the session and status.
     */
    @Synchronized
    fun mount(
        moduleId: String,
        sessionToken: String = generateSessionToken(moduleId),
        ownerId: String? = null,
        params: Map<String, String> = emptyMap()
    ): HeavyModuleMountResult {
        val descriptor = descriptors[moduleId]
        if (descriptor == null) {
            safeLog("Mount failed: module '$moduleId' not registered")
            return HeavyModuleMountResult(
                session = null,
                result = HeavyModuleResult.error(
                    HeavyModuleErrors.NOT_FOUND,
                    "Module '$moduleId' is not registered"
                )
            )
        }

        if (!isAvailable(moduleId)) {
            safeLog("Mount failed: module '$moduleId' is unavailable")
            return HeavyModuleMountResult(
                session = null,
                result = HeavyModuleResult.error(
                    HeavyModuleErrors.UNAVAILABLE,
                    "Module '$moduleId' is currently unavailable"
                )
            )
        }

        // Idempotency: return existing active session if token is already registered
        val existingSession = activeSessions[sessionToken]
        if (existingSession != null && existingSession.isMounted) {
            return HeavyModuleMountResult(existingSession, HeavyModuleResult.success())
        }

        // 1. Lazy instantiation if not active
        val module = activeModules.getOrPut(moduleId) {
            val factory = factories[moduleId]
            if (factory == null) {
                safeLog("Mount failed: no factory registered for module '$moduleId'")
                return HeavyModuleMountResult(
                    session = null,
                    result = HeavyModuleResult.error(
                        HeavyModuleErrors.NOT_FOUND,
                        "No factory registered for module '$moduleId'"
                    )
                )
            }
            try {
                safeLog("Instantiating Heavy module '$moduleId' lazily")
                factory.create(context)
            } catch (e: Throwable) {
                safeLogError("Failed to lazily instantiate module '$moduleId'", e)
                return HeavyModuleMountResult(
                    session = null,
                    result = HeavyModuleResult.error(
                        HeavyModuleErrors.OPERATION_FAILED,
                        "Instantiation error: ${e.message}"
                    )
                )
            }
        }

        // 2. Track reference count
        val refCount = moduleRefCounts.getOrPut(moduleId) { AtomicInteger(0) }
        refCount.incrementAndGet()

        // 3. Notify module of mount
        val mountResult = try {
            module.onMount(sessionToken, params)
        } catch (e: Throwable) {
            safeLogError("Exception during onMount for module '$moduleId'", e)
            refCount.decrementAndGet()
            if (refCount.get() <= 0) {
                moduleRefCounts.remove(moduleId)
                activeModules.remove(moduleId)
                try {
                    module.onDispose()
                } catch (ignored: Throwable) {}
            }
            return HeavyModuleMountResult(
                session = null,
                result = HeavyModuleResult.error(
                    HeavyModuleErrors.OPERATION_FAILED,
                    "Mount error: ${e.message}"
                )
            )
        }

        if (!mountResult.success) {
            refCount.decrementAndGet()
            if (refCount.get() <= 0) {
                moduleRefCounts.remove(moduleId)
                activeModules.remove(moduleId)
                try {
                    module.onDispose()
                } catch (ignored: Throwable) {}
            }
            return HeavyModuleMountResult(session = null, result = mountResult)
        }

        // 4. Create and record session
        val session = HeavyModuleSessionImpl(
            sessionToken = sessionToken,
            moduleId = moduleId,
            descriptor = descriptor,
            ownerId = ownerId,
            manager = this
        )

        activeSessions[sessionToken] = session
        if (ownerId != null) {
            ownerSessions.computeIfAbsent(ownerId) { ConcurrentHashMap.newKeySet() }.add(sessionToken)
        }

        safeLog("Mounted module '$moduleId' session '$sessionToken' (owner=$ownerId, activeRef=${refCount.get()})")
        return HeavyModuleMountResult(session = session, result = mountResult)
    }

    /**
     * Dispatches an operation to an active module session.
     */
    fun use(
        sessionToken: String,
        operation: String,
        params: Map<String, String> = emptyMap()
    ): HeavyModuleResult {
        val session = activeSessions[sessionToken]
        if (session == null || !session.isMounted) {
            return HeavyModuleResult.error(
                HeavyModuleErrors.NOT_MOUNTED,
                "Session '$sessionToken' is not active or has been unmounted"
            )
        }

        val module = activeModules[session.moduleId]
            ?: return HeavyModuleResult.error(
                HeavyModuleErrors.NOT_MOUNTED,
                "Module '${session.moduleId}' instance is not active in memory"
            )

        val request = HeavyModuleExecutionRequest(
            moduleId = session.moduleId,
            sessionToken = sessionToken,
            operation = operation,
            parameters = params
        )

        return try {
            module.onUse(request)
        } catch (e: Throwable) {
            safeLogError("Error executing operation '$operation' on '${session.moduleId}'", e)
            HeavyModuleResult.error(
                HeavyModuleErrors.OPERATION_FAILED,
                "Execution error: ${e.message}"
            )
        }
    }

    /**
     * Unmounts a module session, safely decrementing reference counts.
     * When the active session count drops to 0, the module instance is disposed and evicted from memory.
     * Safe to call multiple times with the same token (returns false if already unmounted).
     */
    @Synchronized
    fun unmount(sessionToken: String): Boolean {
        val session = activeSessions.remove(sessionToken) ?: return false

        // Mark session inactive
        session.markUnmounted()

        // Remove from owner index
        session.ownerId?.let { owner ->
            ownerSessions[owner]?.remove(sessionToken)
            if (ownerSessions[owner]?.isEmpty() == true) {
                ownerSessions.remove(owner)
            }
        }

        val moduleId = session.moduleId
        val module = activeModules[moduleId]

        // Notify module instance of unmount
        if (module != null) {
            try {
                module.onUnmount(sessionToken)
            } catch (e: Throwable) {
                safeLogError("Error in onUnmount for module '$moduleId'", e)
            }
        }

        // Decrement reference count and dispose if idle
        val refCount = moduleRefCounts[moduleId]
        if (refCount != null && refCount.decrementAndGet() <= 0) {
            moduleRefCounts.remove(moduleId)
            val discarded = activeModules.remove(moduleId)
            try {
                discarded?.onDispose()
            } catch (e: Throwable) {
                safeLogError("Error in onDispose for module '$moduleId'", e)
            }
            safeLog("Disposed idle module instance '$moduleId'")
        }

        safeLog("Unmounted session '$sessionToken' of module '$moduleId' (remaining sessions: ${activeSessions.size})")

        if (activeSessions.isEmpty()) {
            try {
                idleListener?.onIdle()
            } catch (e: Throwable) {
                safeLogError("Error notifying idleListener", e)
            }
        }

        return true
    }

    /**
     * Unmounts all active module sessions associated with a specific owner.
     *
     * @param ownerId The owner identifier to clean up.
     * @return Number of sessions unmounted.
     */
    @Synchronized
    fun unmountAll(ownerId: String): Int {
        val sessions = ownerSessions.remove(ownerId)?.toList() ?: return 0
        var unmountedCount = 0
        for (sessionToken in sessions) {
            if (unmount(sessionToken)) {
                unmountedCount++
            }
        }
        if (unmountedCount > 0) {
            safeLog("Unmounted $unmountedCount module sessions for owner '$ownerId'")
        }
        return unmountedCount
    }

    /**
     * Disposes any active module instance whose session ref-count has dropped to 0 or below.
     */
    @Synchronized
    fun disposeIdleModules() {
        for ((moduleId, refCount) in moduleRefCounts.entries.toList()) {
            if (refCount.get() <= 0) {
                moduleRefCounts.remove(moduleId)
                val discarded = activeModules.remove(moduleId)
                try {
                    discarded?.onDispose()
                } catch (e: Throwable) {
                    safeLogError("Error in onDispose during disposeIdleModules for '$moduleId'", e)
                }
                safeLog("Disposed idle module '$moduleId'")
            }
        }
    }

    /**
     * Releases all sessions, active module instances, and owner associations.
     */
    @Synchronized
    fun releaseAll() {
        val sessionTokens = activeSessions.keys.toList()
        for (token in sessionTokens) {
            unmount(token)
        }
        for ((moduleId, module) in activeModules) {
            try {
                module.onDispose()
            } catch (ignored: Throwable) {}
        }
        activeModules.clear()
        moduleRefCounts.clear()
        activeSessions.clear()
        ownerSessions.clear()
        safeLog("HeavyModuleManager: released all active sessions and module instances")
    }

    /**
     * Bridges incoming IPC commands dispatched from Main's HeavyCapabilityRouter.
     */
    fun handleIpcCommand(command: HeavyCommand): IpcResult {
        val action = command.payload["action"] ?: ""
        return when (action) {
            "mount" -> {
                val moduleId = command.payload["capabilityId"]
                    ?: command.payload["moduleId"]
                    ?: command.targetId
                    ?: ""
                val sessionToken = command.payload["sessionToken"] ?: generateSessionToken(moduleId)
                val ownerId = command.payload["ownerId"]

                // Strip internal routing keys from module parameters
                val params = command.payload.filterKeys {
                    it != "action" && it != "sessionToken" && it != "capabilityId" && it != "moduleId" && it != "ownerId"
                }

                val mountResult = mount(moduleId, sessionToken, ownerId, params)
                if (mountResult.isSuccess) {
                    IpcResult.success("Mounted module '$moduleId'", mountResult.result.data)
                } else {
                    val errData = HashMap(mountResult.result.data).apply {
                        put("error_code", mountResult.result.errorCode)
                    }
                    IpcResult.error(
                        IpcErrorCode.COMMAND_FAILED,
                        mountResult.result.message,
                        errData
                    )
                }
            }

            "use" -> {
                val sessionToken = command.payload["sessionToken"] ?: ""
                val operation = command.payload["operation"] ?: ""
                val params = command.payload.filterKeys {
                    it != "action" && it != "sessionToken" && it != "operation" && it != "capabilityId" && it != "moduleId"
                }

                val result = use(sessionToken, operation, params)
                if (result.success) {
                    IpcResult.success(result.message, result.data)
                } else {
                    val errData = HashMap(result.data).apply {
                        put("error_code", result.errorCode)
                    }
                    IpcResult.error(
                        IpcErrorCode.COMMAND_FAILED,
                        result.message,
                        errData
                    )
                }
            }

            "unmount" -> {
                val sessionToken = command.payload["sessionToken"] ?: ""
                val unmounted = unmount(sessionToken)
                IpcResult.success("Unmounted: $unmounted")
            }

            "unmountAll" -> {
                val ownerId = command.payload["ownerId"] ?: ""
                val count = unmountAll(ownerId)
                IpcResult.success("Unmounted $count sessions", mapOf("unmounted_count" to count.toString()))
            }

            else -> {
                IpcResult.error(
                    IpcErrorCode.COMMAND_FAILED,
                    "Unknown or unsupported module action: '$action'"
                )
            }
        }
    }

    private fun generateSessionToken(moduleId: String): String {
        return "hmod_${moduleId}_${UUID.randomUUID().toString().take(8)}"
    }

    companion object {
        private const val TAG = "HeavyModuleManager"

        @Volatile
        private var instance: HeavyModuleManager? = null

        fun getInstance(context: Context?): HeavyModuleManager {
            return instance ?: synchronized(this) {
                instance ?: HeavyModuleManager(context?.applicationContext).also { instance = it }
            }
        }
    }
}
