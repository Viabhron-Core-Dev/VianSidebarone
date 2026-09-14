package com.example.feature.element

import android.content.Context
import com.example.core.LogKeeper
import java.util.concurrent.ConcurrentHashMap

/**
 * ElementRuntimeResolver: Central lazy resolution, instantiation, and execution coordinator
 * for modular Elements in the Main process.
 *
 * Guarantees:
 * 1. Strictly lazy: does NOT instantiate all registered elements when Sidebar opens.
 * 2. On-demand resolution: instantiates and initializes an Element only when its placement
 *    needs to be rendered or executed.
 * 3. Enforces execution scope: creates strongly-typed [ElementExecutionContext] containing
 *    handleId, gesture, containerId, pageId, and extras.
 * 4. Strict isolation: elements from another container or page never receive cross-scope data.
 * 5. Lifecycle coordination: manages initialize and release for active runtime instances.
 * 6. Thread-safe cached resolution per active session.
 */
class ElementRuntimeResolver private constructor(private val context: Context) {

    private val actionRegistry: ElementActionRegistry = ElementActionRegistry.getInstance(context)

    // Cache of active resolved instances in the current runtime session
    private val activeInstances = ConcurrentHashMap<String, CommonElementRuntimeContract>()

    /**
     * Resolves an Element instance lazily for the given action key.
     * If the registered contract implements [CommonElementRuntimeContract], it is initialized and returned.
     * If it implements the base [ElementActionContract], it is wrapped into a [CommonElementRuntimeContract].
     * Returns null if actionKey is unregistered or instantiation fails.
     */
    fun resolveElement(actionKey: String): CommonElementRuntimeContract? {
        if (!actionRegistry.isRegistered(actionKey)) {
            return null
        }

        val cached = activeInstances[actionKey]
        if (cached != null) {
            return cached
        }

        val contract = actionRegistry.resolve(actionKey) ?: return null
        val commonContract = if (contract is CommonElementRuntimeContract) {
            contract
        } else {
            // Adapt standard ElementActionContract to CommonElementRuntimeContract
            object : CommonElementRuntimeContract {
                override val descriptor: ElementDescriptor = contract.descriptor
                override fun isAvailable(context: Context): Boolean = contract.isAvailable(context)
                override fun execute(executionContext: ElementExecutionContext): Boolean = contract.execute(executionContext)
            }
        }

        try {
            commonContract.onInitialize(context)
        } catch (e: Throwable) {
            LogKeeper.logError(context, TAG, "Error initializing element '$actionKey'", e)
        }

        activeInstances[actionKey] = commonContract
        return commonContract
    }

    /**
     * Checks if a placed element is executable/available in this runtime environment.
     */
    fun isAvailable(placement: ElementPlacement): Boolean {
        val descriptor = actionRegistry.getDescriptor(placement.actionKey)
        if (descriptor != null && !descriptor.isAvailable) {
            return false
        }
        val element = resolveElement(placement.actionKey) ?: return false
        return element.isAvailable(context)
    }

    /**
     * Executes a placed element within its exact container, page, and placement context.
     * Enforces that Element instances never determine their own container/page scope.
     */
    fun executePlacement(
        placement: ElementPlacement,
        handleId: String? = null,
        gesture: String? = null,
        extras: Map<String, Any?> = emptyMap()
    ): Boolean {
        val actionKey = placement.actionKey
        val element = resolveElement(actionKey)
        if (element == null) {
            LogKeeper.log(context, TAG, "Cannot execute placement '${placement.placementId}': element '$actionKey' unresolved")
            return false
        }

        if (!element.isAvailable(context)) {
            LogKeeper.log(context, TAG, "Element '$actionKey' is unavailable on this device")
            return false
        }

        val executionContext = ElementExecutionContext(
            context = context,
            actionKey = actionKey,
            handleId = handleId,
            gesture = gesture,
            containerId = placement.containerId,
            pageId = placement.pageId,
            extras = extras + placement.config
        )

        return try {
            val handled = element.execute(executionContext)
            LogKeeper.log(
                context,
                TAG,
                "Executed element '$actionKey' on container '${placement.containerId}' page '${placement.pageId}' (handled=$handled)"
            )
            handled
        } catch (e: Throwable) {
            LogKeeper.logError(context, TAG, "Exception executing element '$actionKey'", e)
            false
        }
    }

    /**
     * Creates an [ElementRenderContext] for rendering into a Sidebar page.
     */
    fun createRenderContext(
        placement: ElementPlacement,
        handleId: String? = null,
        gesture: String? = null,
        isEditMode: Boolean = false,
        onActionTriggered: ((ElementPlacement) -> Unit)? = null,
        onRemoveRequested: ((ElementPlacement) -> Unit)? = null
    ): ElementRenderContext {
        val executionContext = ElementExecutionContext(
            context = context,
            actionKey = placement.actionKey,
            handleId = handleId,
            gesture = gesture,
            containerId = placement.containerId,
            pageId = placement.pageId,
            extras = placement.config
        )
        return ElementRenderContext(
            context = context,
            placement = placement,
            executionContext = executionContext,
            isEditMode = isEditMode,
            onActionTriggered = onActionTriggered,
            onRemoveRequested = onRemoveRequested
        )
    }

    /**
     * Releases an element instance if cached.
     */
    fun releaseElement(actionKey: String) {
        val instance = activeInstances.remove(actionKey)
        try {
            instance?.onRelease()
        } catch (e: Throwable) {
            LogKeeper.logError(context, TAG, "Error releasing element '$actionKey'", e)
        }
    }

    /**
     * Releases all active element instances when the Sidebar container closes or dismisses.
     */
    fun releaseAll() {
        val count = activeInstances.size
        for ((key, instance) in activeInstances) {
            try {
                instance.onRelease()
            } catch (e: Throwable) {
                LogKeeper.logError(context, TAG, "Error releasing element '$key'", e)
            }
        }
        activeInstances.clear()
        if (count > 0) {
            LogKeeper.log(context, TAG, "Released $count active element instances")
        }
    }

    companion object {
        private const val TAG = "ElementRuntimeResolver"

        @Volatile
        private var instance: ElementRuntimeResolver? = null

        fun getInstance(context: Context): ElementRuntimeResolver {
            return instance ?: synchronized(this) {
                instance ?: ElementRuntimeResolver(context.applicationContext).also { instance = it }
            }
        }
    }
}
