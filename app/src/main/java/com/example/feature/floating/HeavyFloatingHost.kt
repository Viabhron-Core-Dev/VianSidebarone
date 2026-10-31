package com.example.feature.floating

import android.content.Context
import com.example.core.LogKeeper
import com.example.core.WindowBounds
import java.util.concurrent.ConcurrentHashMap

/**
 * HeavyFloatingHost: The Heavy-process (:heavy) host coordinator for floating mini-apps.
 *
 * Responsibilities:
 * 1. Hosts floating mini-app instances in the disposable :heavy process without Main having
 *    compile-time dependencies on specific mini-app implementations.
 * 2. Owns mini-app runtime lifecycle (onCreate, onStart, onResume, onPause, onStop, onDestroy).
 * 3. Implements the sleep contract: extracts compact payloads on sleep and tears down runtimes,
 *    enabling Heavy to reach 0 MB idle without keeping the process alive.
 * 4. Relies on Main as the SINGLE AUTHORITATIVE STORE. Does NOT write directly to disk files.
 * 5. Window hierarchy, bounds, and Z-order remain strictly authoritative in Main's FloatingWindowManager.
 */
class HeavyFloatingHost private constructor(private val context: Context) {

    private val factories = ConcurrentHashMap<String, MiniAppFactory>()
    private val activeInstances = LinkedHashMap<String, MiniAppInstance>()
    private val lock = Any()
    private val defaultFactory = DefaultMiniAppFactory()
    private val testNotesFactory = TestNotesMiniAppFactory()

    init {
        // Register default and test mini-app factories
        registerFactory("generic", defaultFactory)
        registerFactory("work_notes", defaultFactory)
        registerFactory("reader", defaultFactory)
        registerFactory("dictionary", defaultFactory)
        registerFactory("translation", defaultFactory)
        registerFactory("test_app", defaultFactory)
        registerFactory(TestNotesMiniAppInstance.APP_TYPE, testNotesFactory)
    }

    /**
     * Registers a factory for a specific mini-app type.
     */
    fun registerFactory(appType: String, factory: MiniAppFactory) {
        factories[appType] = factory
    }

    /**
     * Unregisters a factory.
     */
    fun unregisterFactory(appType: String) {
        factories.remove(appType)
    }

    /**
     * Starts or resumes a mini-app instance in the Heavy process.
     */
    fun startInstance(
        instanceId: String,
        appType: String,
        initialBounds: WindowBounds? = null,
        initialPayload: Map<String, String> = emptyMap()
    ): MiniAppInstance? {
        log("INSTANCE_CREATE_REQUEST id=$instanceId type=$appType initialPayloadKeys=${initialPayload.keys}")
        synchronized(lock) {
            val existing = activeInstances[instanceId]
            if (existing != null) {
                if (existing.lifecycleState == MiniAppLifecycleState.PAUSED ||
                    existing.lifecycleState == MiniAppLifecycleState.STOPPED ||
                    existing.lifecycleState == MiniAppLifecycleState.SLEEPING
                ) {
                    existing.onStart()
                    existing.onResume()
                }
                log("INSTANCE_ACTIVE id=$instanceId type=$appType resumedExisting=true state=${existing.lifecycleState}")
                return existing
            }

            val factory = factories[appType] ?: defaultFactory
            val instance = factory.createAppInstance(instanceId, appType)

            if (instance != null) {
                try {
                    log("INSTANCE_CREATED id=$instanceId type=$appType factory=${factory.javaClass.simpleName}")
                    val snapshot = MiniAppRecoverySnapshot(
                        instanceId = instanceId,
                        appType = appType,
                        windowBounds = initialBounds?.copyBounds() ?: WindowBounds(100, 100, 600, 800),
                        payload = LinkedHashMap(initialPayload)
                    )
                    instance.onCreate(snapshot)
                    instance.restoreFromSnapshot(snapshot)
                    instance.onStart()
                    instance.onResume()
                    activeInstances[instanceId] = instance
                    log("INSTANCE_ACTIVE id=$instanceId type=$appType bounds=${snapshot.windowBounds} activeTotal=${activeInstances.size}")
                } catch (e: Throwable) {
                    logCrash("INSTANCE_CREATE_FAILURE id=$instanceId type=$appType error=${e.message}", e)
                    return null
                }
            } else {
                log("INSTANCE_CREATE_FAILURE id=$instanceId type=$appType reason=No factory registered")
            }

            return instance
        }
    }

    /**
     * Prepares an active instance for sleep: gathers its recovery payload, releases its runtime,
     * destroys the in-memory instance, and returns the payload to Main for durable storage.
     */
    fun prepareSleep(instanceId: String, additionalPayload: Map<String, String> = emptyMap()): Map<String, String> {
        log("INSTANCE_RELEASE_REQUEST id=$instanceId reason=sleep additionalKeys=${additionalPayload.keys}")
        synchronized(lock) {
            val instance = activeInstances.remove(instanceId) ?: run {
                log("INSTANCE_RELEASED id=$instanceId reason=sleep foundInMemory=false")
                return emptyMap()
            }
            return try {
                if (additionalPayload.isNotEmpty()) {
                    instance.restoreFromSnapshot(
                        MiniAppRecoverySnapshot(
                            instanceId = instanceId,
                            appType = instance.appType,
                            payload = LinkedHashMap(additionalPayload)
                        )
                    )
                }
                if (instance.lifecycleState == MiniAppLifecycleState.RESUMED) {
                    instance.onPause()
                }
                if (instance.lifecycleState != MiniAppLifecycleState.STOPPED) {
                    instance.onStop()
                }
                val payload = instance.prepareForSleep()
                val textLen = payload["text"]?.length ?: 0
                log("STATE_SAVED id=$instanceId reason=sleep keys=${payload.keys} textLength=$textLen")
                instance.releaseRuntime()
                instance.onDestroy()
                log("INSTANCE_RELEASED id=$instanceId reason=sleep remainingActive=${activeInstances.size}")
                payload
            } catch (e: Throwable) {
                logCrash("prepareSleep failed for $instanceId", e)
                emptyMap()
            }
        }
    }

    /**
     * Pauses an active instance without destroying runtime.
     */
    fun pauseInstance(instanceId: String): Map<String, String> {
        synchronized(lock) {
            val instance = activeInstances[instanceId] ?: return emptyMap()
            return try {
                instance.onPause()
                val state = instance.onSaveState()
                val textLen = state["text"]?.length ?: 0
                log("STATE_SAVED id=$instanceId reason=pause keys=${state.keys} textLength=$textLen")
                state
            } catch (e: Throwable) {
                logCrash("pauseInstance failed", e)
                emptyMap()
            }
        }
    }

    /**
     * Stops an active instance, gathers its state, and releases runtime.
     */
    fun stopInstance(instanceId: String): Map<String, String> {
        log("INSTANCE_RELEASE_REQUEST id=$instanceId reason=stop")
        synchronized(lock) {
            val instance = activeInstances.remove(instanceId) ?: run {
                log("INSTANCE_CLOSED id=$instanceId foundInMemory=false")
                return emptyMap()
            }
            return try {
                if (instance.lifecycleState == MiniAppLifecycleState.RESUMED) {
                    instance.onPause()
                }
                instance.onStop()
                val payload = instance.onSaveState()
                val textLen = payload["text"]?.length ?: 0
                log("STATE_SAVED id=$instanceId reason=stop keys=${payload.keys} textLength=$textLen")
                instance.releaseRuntime()
                instance.onDestroy()
                log("INSTANCE_CLOSED id=$instanceId remainingActive=${activeInstances.size}")
                payload
            } catch (e: Throwable) {
                logCrash("stopInstance failed", e)
                emptyMap()
            }
        }
    }

    /**
     * Destroys an active instance and releases all associated memory.
     */
    fun destroyInstance(instanceId: String) {
        log("INSTANCE_RELEASE_REQUEST id=$instanceId reason=destroy")
        synchronized(lock) {
            val instance = activeInstances.remove(instanceId)
            if (instance != null) {
                try {
                    if (instance.lifecycleState == MiniAppLifecycleState.RESUMED) {
                        instance.onPause()
                    }
                    if (instance.lifecycleState != MiniAppLifecycleState.STOPPED) {
                        instance.onStop()
                    }
                    instance.releaseRuntime()
                    instance.onDestroy()
                } catch (e: Throwable) {
                    logCrash("destroyInstance failed", e)
                }
            }
            log("INSTANCE_CLOSED id=$instanceId remainingActive=${activeInstances.size}")
        }
    }

    /**
     * Returns an active instance if running in Heavy.
     */
    fun getActiveInstance(instanceId: String): MiniAppInstance? {
        synchronized(lock) {
            return activeInstances[instanceId]
        }
    }

    /**
     * Returns count of currently active mini-app runtimes.
     * Sleeping mini-apps return 0 since their runtimes have been destroyed.
     */
    fun getActiveInstanceCount(): Int {
        synchronized(lock) {
            return activeInstances.size
        }
    }

    /**
     * Forwards system onTrimMemory signals to all active mini-app instances.
     */
    fun onTrimMemory(level: Int) {
        synchronized(lock) {
            for (instance in activeInstances.values) {
                try {
                    instance.onTrimMemory(level)
                } catch (e: Throwable) {
                    logCrash("onTrimMemory failed", e)
                }
            }
        }
    }

    private fun log(message: String) {
        LogKeeper.log(context, "HeavyFloatingHost", message)
    }

    private fun logCrash(message: String, throwable: Throwable) {
        LogKeeper.logCrash(context, "HeavyFloatingHost: $message", throwable)
    }

    companion object {
        @Volatile
        private var instance: HeavyFloatingHost? = null

        fun getInstance(context: Context): HeavyFloatingHost {
            return instance ?: synchronized(this) {
                instance ?: HeavyFloatingHost(context.applicationContext).also { instance = it }
            }
        }

        fun resetForTesting() {
            synchronized(this) {
                instance = null
            }
        }
    }
}
