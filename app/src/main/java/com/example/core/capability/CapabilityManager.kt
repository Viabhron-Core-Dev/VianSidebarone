package com.example.core.capability

import android.content.Context
import com.example.core.LogKeeper
import com.example.core.ipc.HeavyProcessConnectionManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * CapabilityManager: Central registry, router, and session lifecycle coordinator
 * for modular capabilities in the Main process.
 *
 * Guarantees:
 * 1. Main owns the capability registry/router.
 * 2. Strict distinction between tiny local Main-side modules and Heavy-routed capabilities.
 * 3. Main-side modules are lazily instantiated on first mount, and discarded when unmounted.
 * 4. Heavy capabilities load ZERO UI or engine code into the Main process.
 * 5. Does not keep permanent references to mini-app Views, Activities, engines, or heavy objects.
 * 6. Heavy process remains strictly on-demand.
 * 7. Supports the complete mount → use → unmount lifecycle with owner-scoped cleanup.
 * 8. Two-process architecture preserved; zero watchdogs, zero polling loops.
 */
class CapabilityManager internal constructor(private val context: Context? = null) {

    private val descriptors = ConcurrentHashMap<String, CapabilityDescriptor>()
    private val localFactories = ConcurrentHashMap<String, LocalCapabilityFactory>()

    // Active local Main-side module instances and their session ref-counts
    private val activeLocalModules = ConcurrentHashMap<String, LocalCapabilityModule>()
    private val localModuleRefCounts = ConcurrentHashMap<String, AtomicInteger>()

    // Active sessions and owner mappings
    private val activeSessions = ConcurrentHashMap<String, CapabilitySessionImpl>()
    private val ownerSessions = ConcurrentHashMap<String, MutableSet<String>>()

    // Router for Heavy-process capabilities
    private val heavyRouter by lazy {
        val connMgr = if (context != null) HeavyProcessConnectionManager.getInstance(context) else HeavyProcessConnectionManager(null)
        HeavyCapabilityRouter(context, connMgr)
    }

    private fun safeLog(msg: String) {
        context?.let { LogKeeper.log(it, TAG, msg) }
    }

    private fun safeLogError(msg: String, t: Throwable) {
        context?.let { LogKeeper.logError(it, TAG, msg, t) }
    }

    /**
     * Registers a tiny, lightweight Main-side capability module lazily.
     * The factory is NOT invoked during registration; instantiation occurs only upon first mount.
     */
    fun registerLocal(descriptor: CapabilityDescriptor, factory: LocalCapabilityFactory) {
        val canonical = descriptor.copy(routing = CapabilityRouting.LOCAL_MAIN)
        descriptors[canonical.capabilityId] = canonical
        localFactories[canonical.capabilityId] = factory
        safeLog("Registered local capability: ${canonical.capabilityId}")
    }

    /**
     * Registers a local capability using a lambda constructor.
     */
    fun registerLocal(descriptor: CapabilityDescriptor, supplier: (Context?) -> LocalCapabilityModule) {
        registerLocal(descriptor, LocalCapabilityFactory { ctx -> supplier(ctx) })
    }

    /**
     * Registers a capability that is routed across the IPC bridge to the Heavy process.
     * ZERO implementation code or UI is stored or loaded into Main.
     */
    fun registerHeavy(descriptor: CapabilityDescriptor) {
        val canonical = descriptor.copy(routing = CapabilityRouting.REMOTE_HEAVY)
        descriptors[canonical.capabilityId] = canonical
        safeLog("Registered Heavy-routed capability: ${canonical.capabilityId}")
    }

    /**
     * Retrieves the lightweight descriptor for a capability ID, if registered.
     */
    fun getDescriptor(capabilityId: String): CapabilityDescriptor? = descriptors[capabilityId]

    /**
     * Retrieves all registered capability descriptors for query or reflection without instantiation.
     */
    fun getAllDescriptors(): List<CapabilityDescriptor> = descriptors.values.toList()

    /**
     * Checks if a capability is registered and available in the current environment.
     */
    fun isAvailable(capabilityId: String): Boolean {
        val descriptor = descriptors[capabilityId] ?: return false
        return when (descriptor.routing) {
            CapabilityRouting.LOCAL_MAIN -> {
                val active = activeLocalModules[capabilityId]
                if (active != null) {
                    active.isAvailable(context)
                } else {
                    localFactories.containsKey(capabilityId)
                }
            }
            CapabilityRouting.REMOTE_HEAVY -> {
                // Heavy process is on-demand; always available if registered
                true
            }
        }
    }

    /**
     * Mounts a capability for a client/owner (e.g. an Element or page).
     *
     * @param capabilityId The target capability ID.
     * @param ownerId An optional client/owner identifier for batch cleanup (e.g. placementId or actionKey).
     * @param params Optional initialization parameters.
     * @return [MountResult] containing the session handle and status.
     */
    @Synchronized
    fun mount(
        capabilityId: String,
        ownerId: String? = null,
        params: Map<String, String> = emptyMap()
    ): MountResult {
        val descriptor = descriptors[capabilityId]
        if (descriptor == null) {
            safeLog("Mount failed: capability '$capabilityId' not found")
            return MountResult(
                session = null,
                result = CapabilityResult.error(CapabilityErrors.NOT_FOUND, "Capability '$capabilityId' not found")
            )
        }

        if (!isAvailable(capabilityId)) {
            safeLog("Mount failed: capability '$capabilityId' unavailable")
            return MountResult(
                session = null,
                result = CapabilityResult.error(CapabilityErrors.UNAVAILABLE, "Capability '$capabilityId' unavailable")
            )
        }

        val sessionToken = "cap_${capabilityId}_${UUID.randomUUID().toString().take(8)}"

        val mountResult = when (descriptor.routing) {
            CapabilityRouting.LOCAL_MAIN -> {
                mountLocalCapability(descriptor, sessionToken, params)
            }
            CapabilityRouting.REMOTE_HEAVY -> {
                heavyRouter.mount(descriptor, sessionToken, params)
            }
        }

        if (!mountResult.success) {
            return MountResult(session = null, result = mountResult)
        }

        val session = CapabilitySessionImpl(
            sessionToken = sessionToken,
            capabilityId = capabilityId,
            descriptor = descriptor,
            routing = descriptor.routing,
            ownerId = ownerId,
            manager = this
        )

        activeSessions[sessionToken] = session
        if (ownerId != null) {
            ownerSessions.computeIfAbsent(ownerId) { ConcurrentHashMap.newKeySet() }.add(sessionToken)
        }

        safeLog("Mounted capability '$capabilityId' [${descriptor.routing}] session '$sessionToken' (owner=$ownerId)")

        return MountResult(session = session, result = mountResult)
    }

    private fun mountLocalCapability(
        descriptor: CapabilityDescriptor,
        sessionToken: String,
        params: Map<String, String>
    ): CapabilityResult {
        val capabilityId = descriptor.capabilityId
        val module = activeLocalModules.getOrPut(capabilityId) {
            val factory = localFactories[capabilityId]
                ?: return CapabilityResult.error(CapabilityErrors.NOT_FOUND, "No factory for local capability '$capabilityId'")
            try {
                factory.create(context)
            } catch (e: Throwable) {
                safeLogError("Failed to instantiate local module '$capabilityId'", e)
                return CapabilityResult.error(CapabilityErrors.OPERATION_FAILED, "Instantiation error: ${e.message}")
            }
        }

        val refCount = localModuleRefCounts.getOrPut(capabilityId) { AtomicInteger(0) }
        refCount.incrementAndGet()

        return try {
            module.onMount(sessionToken, params)
        } catch (e: Throwable) {
            safeLogError("Exception mounting local module '$capabilityId'", e)
            refCount.decrementAndGet()
            CapabilityResult.error(CapabilityErrors.OPERATION_FAILED, "Mount error: ${e.message}")
        }
    }

    /**
     * Dispatches an operation to an active capability session.
     */
    fun use(
        sessionToken: String,
        operation: String,
        params: Map<String, String> = emptyMap()
    ): CapabilityResult {
        val session = activeSessions[sessionToken]
            ?: return CapabilityResult.error(CapabilityErrors.NOT_MOUNTED, "Session '$sessionToken' not found")

        val request = CapabilityRequest(
            capabilityId = session.capabilityId,
            sessionToken = sessionToken,
            operation = operation,
            parameters = params
        )

        return when (session.routing) {
            CapabilityRouting.LOCAL_MAIN -> {
                val module = activeLocalModules[session.capabilityId]
                    ?: return CapabilityResult.error(CapabilityErrors.NOT_MOUNTED, "Local module '${session.capabilityId}' not active")
                try {
                    module.onUse(request)
                } catch (e: Throwable) {
                    safeLogError("Error executing '$operation' on '${session.capabilityId}'", e)
                    CapabilityResult.error(CapabilityErrors.OPERATION_FAILED, "Execution error: ${e.message}")
                }
            }
            CapabilityRouting.REMOTE_HEAVY -> {
                heavyRouter.use(request)
            }
        }
    }

    /**
     * Unmounts and releases an active capability session.
     * If the capability is a local Main module and no other sessions are using it,
     * the module is discarded to reclaim memory.
     */
    @Synchronized
    fun unmount(sessionToken: String): Boolean {
        val session = activeSessions.remove(sessionToken) ?: return false

        // Remove from owner index
        session.ownerId?.let { owner ->
            ownerSessions[owner]?.remove(sessionToken)
            if (ownerSessions[owner]?.isEmpty() == true) {
                ownerSessions.remove(owner)
            }
        }

        session.markUnmounted()

        when (session.routing) {
            CapabilityRouting.LOCAL_MAIN -> {
                val capabilityId = session.capabilityId
                val module = activeLocalModules[capabilityId]
                if (module != null) {
                    try {
                        module.onUnmount(sessionToken)
                    } catch (e: Throwable) {
                        safeLogError("Error unmounting local module '$capabilityId'", e)
                    }
                }

                val refCount = localModuleRefCounts[capabilityId]
                if (refCount != null && refCount.decrementAndGet() <= 0) {
                    localModuleRefCounts.remove(capabilityId)
                    val discarded = activeLocalModules.remove(capabilityId)
                    try {
                        discarded?.onDiscard()
                    } catch (e: Throwable) {
                        safeLogError("Error discarding local module '$capabilityId'", e)
                    }
                    safeLog("Discarded idle local module '$capabilityId'")
                }
            }
            CapabilityRouting.REMOTE_HEAVY -> {
                heavyRouter.unmount(sessionToken)
            }
        }

        safeLog("Unmounted session '$sessionToken' of capability '${session.capabilityId}'")
        return true
    }

    /**
     * Unmounts all active capabilities associated with a specific owner (e.g. an Element or page).
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
            safeLog("Unmounted $unmountedCount capabilities for owner '$ownerId'")
        }
        return unmountedCount
    }

    /**
     * Releases all sessions, local modules, and routers.
     */
    @Synchronized
    fun releaseAll() {
        val allSessionTokens = activeSessions.keys.toList()
        for (token in allSessionTokens) {
            unmount(token)
        }
        for ((_, module) in activeLocalModules) {
            try {
                module.onDiscard()
            } catch (ignored: Exception) {}
        }
        activeLocalModules.clear()
        localModuleRefCounts.clear()
        activeSessions.clear()
        ownerSessions.clear()
        heavyRouter.releaseAll()
        safeLog("CapabilityManager: released all active capabilities and sessions")
    }

    internal fun getActiveSessionCount(): Int = activeSessions.size

    internal fun getActiveLocalModuleCount(): Int = activeLocalModules.size

    /**
     * Internal implementation of [CapabilitySession].
     */
    private class CapabilitySessionImpl(
        override val sessionToken: String,
        override val capabilityId: String,
        override val descriptor: CapabilityDescriptor,
        override val routing: CapabilityRouting,
        val ownerId: String?,
        private val manager: CapabilityManager
    ) : CapabilitySession {

        @Volatile
        private var mounted = true

        override val isMounted: Boolean get() = mounted

        fun markUnmounted() {
            mounted = false
        }

        override fun use(operation: String, params: Map<String, String>): CapabilityResult {
            if (!mounted) {
                return CapabilityResult.error(CapabilityErrors.NOT_MOUNTED, "Session '$sessionToken' is already unmounted")
            }
            return manager.use(sessionToken, operation, params)
        }

        override fun unmount(): Boolean {
            if (!mounted) return false
            return manager.unmount(sessionToken)
        }
    }

    companion object {
        private const val TAG = "CapabilityManager"

        @Volatile
        private var instance: CapabilityManager? = null

        fun getInstance(context: Context? = null): CapabilityManager {
            return instance ?: synchronized(this) {
                instance ?: CapabilityManager(context?.applicationContext).also { instance = it }
            }
        }
    }
}
