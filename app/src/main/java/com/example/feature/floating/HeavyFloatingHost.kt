package com.example.feature.floating

import android.content.Context
import com.example.core.LogKeeper
import com.example.core.WindowBounds
import java.util.concurrent.ConcurrentHashMap

/**
 * HeavyFloatingHost: The Heavy-process (:heavy) host coordinator for floating mini-apps.
 *
 * Responsibilities:
 * 1. Hosts future floating mini-app instances without Main having dependencies on specific mini-app implementations.
 * 2. Owns mini-app lifecycle (onCreate, onStart, onResume, onPause, onStop, onDestroy).
 * 3. Commits state immediately to DurableMiniAppStore so data survives process death or restarts.
 * 4. Provides restoration hooks when the Heavy process restarts.
 * 5. Does NOT create a competing Window Manager; window hierarchy and Z-order remain authoritative in Main.
 */
class HeavyFloatingHost private constructor(private val context: Context) {

    private val store = DurableMiniAppStore.getInstance(context)
    private val factories = ConcurrentHashMap<String, MiniAppFactory>()
    private val activeInstances = LinkedHashMap<String, MiniAppInstance>()
    private val lock = Any()

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
        initialBounds: WindowBounds? = null
    ): MiniAppInstance? {
        synchronized(lock) {
            val existing = activeInstances[instanceId]
            if (existing != null) {
                if (existing.lifecycleState == MiniAppLifecycleState.PAUSED ||
                    existing.lifecycleState == MiniAppLifecycleState.STOPPED
                ) {
                    existing.onStart()
                    existing.onResume()
                }
                return existing
            }

            // Retrieve or initialize durable record
            var record = store.loadInstance(instanceId)
            if (record == null) {
                record = MiniAppRecord(
                    instanceId = instanceId,
                    appType = appType,
                    bounds = initialBounds?.copyBounds() ?: WindowBounds(100, 100, 600, 800),
                    lastActiveTimestamp = System.currentTimeMillis()
                )
                store.saveInstance(record)
            } else if (initialBounds != null) {
                record.bounds = initialBounds.copyBounds()
                store.updateBounds(instanceId, record.bounds)
            }

            val factory = factories[appType]
            val instance = factory?.createAppInstance(instanceId, appType)

            if (instance != null) {
                try {
                    instance.onCreate(record)
                    instance.onStart()
                    instance.onResume()
                    activeInstances[instanceId] = instance
                    log("Started mini-app instance $instanceId ($appType)")
                } catch (e: Throwable) {
                    logCrash("startInstance failed", e)
                    return null
                }
            } else {
                log("No factory registered for mini-app type $appType; instance $instanceId tracked durably")
            }

            return instance
        }
    }

    /**
     * Pauses an active instance and saves its durable state.
     */
    fun pauseInstance(instanceId: String) {
        synchronized(lock) {
            val instance = activeInstances[instanceId] ?: return
            try {
                instance.onPause()
                val stateData = instance.onSaveState()
                if (stateData.isNotEmpty()) {
                    store.updateData(instanceId, stateData)
                }
            } catch (e: Throwable) {
                logCrash("pauseInstance failed", e)
            }
        }
    }

    /**
     * Stops an active instance and updates durable state.
     */
    fun stopInstance(instanceId: String) {
        synchronized(lock) {
            val instance = activeInstances[instanceId] ?: return
            try {
                if (instance.lifecycleState == MiniAppLifecycleState.RESUMED) {
                    instance.onPause()
                }
                instance.onStop()
                val stateData = instance.onSaveState()
                if (stateData.isNotEmpty()) {
                    store.updateData(instanceId, stateData)
                }
            } catch (e: Throwable) {
                logCrash("stopInstance failed", e)
            }
        }
    }

    /**
     * Destroys an active instance and optionally purges its durable record.
     */
    fun destroyInstance(instanceId: String, deleteDurable: Boolean = false) {
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
                    instance.onDestroy()
                } catch (e: Throwable) {
                    logCrash("destroyInstance onDestroy failed", e)
                }
            }

            if (deleteDurable) {
                store.deleteInstance(instanceId)
            } else if (instance != null) {
                val stateData = instance.onSaveState()
                if (stateData.isNotEmpty()) {
                    store.updateData(instanceId, stateData)
                }
            }
            log("Destroyed mini-app instance $instanceId (deleteDurable=$deleteDurable)")
        }
    }

    /**
     * Updates coordinates/dimensions for an instance.
     */
    fun updateBounds(instanceId: String, bounds: WindowBounds) {
        store.updateBounds(instanceId, bounds)
    }

    /**
     * Updates fold state for an instance.
     */
    fun updateFold(instanceId: String, isFolded: Boolean) {
        store.updateFoldState(instanceId, isFolded)
    }

    /**
     * Restoration hook: Reads durable records from disk to support reconstruction after Heavy restart.
     */
    fun restoreSavedInstances(): List<MiniAppRecord> {
        synchronized(lock) {
            val records = store.getAllInstances()
            for (record in records) {
                // If instance is not active in memory, attempt restoration via factory if registered
                if (!activeInstances.containsKey(record.instanceId)) {
                    val factory = factories[record.appType]
                    val instance = factory?.createAppInstance(record.instanceId, record.appType)
                    if (instance != null) {
                        try {
                            instance.onCreate(record)
                            instance.onStart()
                            activeInstances[record.instanceId] = instance
                        } catch (e: Throwable) {
                            logCrash("restoreSavedInstances failed", e)
                        }
                    }
                }
            }
            return records
        }
    }

    /**
     * Returns an active instance if running.
     */
    fun getActiveInstance(instanceId: String): MiniAppInstance? {
        synchronized(lock) {
            return activeInstances[instanceId]
        }
    }

    /**
     * Returns count of currently active instances.
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
    }
}
