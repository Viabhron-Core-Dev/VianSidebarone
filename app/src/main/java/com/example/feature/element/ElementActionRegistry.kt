package com.example.feature.element

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.example.core.HandleManager
import com.example.core.LogKeeper
import java.util.concurrent.ConcurrentHashMap

/**
 * ElementActionRegistry: Lightweight, on-demand registry and dispatcher for all Element and action types
 * executing within the Main process.
 *
 * Guarantees:
 * 1. Elements are Main-process, on-demand modules.
 * 2. Elements are NEVER instantiated at startup; stored strictly as [ElementDescriptor] metadata + [ElementActionFactory].
 * 3. Safely resolves and dispatches action keys, handling unknown, disabled, or unavailable actions gracefully.
 * 4. Distinct from SidebarManager Container targets: gestures targeting [GestureTarget.Container] continue to be
 *    handled exclusively by SidebarManager.
 * 5. Flexible enough for full-screen content, another-app links, Android widgets, screen overlays, tools/actions,
 *    and sidebar/page content.
 */
class ElementActionRegistry private constructor(private val context: Context) {

    private val descriptors = ConcurrentHashMap<String, ElementDescriptor>()
    private val factories = ConcurrentHashMap<String, ElementActionFactory>()
    private var isReceiverRegistered = false

    /**
     * BroadcastReceiver listening for HandleService or system gesture action dispatches.
     */
    val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TRIGGER_ACTION) {
                val actionKey = intent.getStringExtra(EXTRA_ACTION_KEY) ?: return
                val handleId = intent.getStringExtra(EXTRA_HANDLE_ID)
                val gesture = intent.getStringExtra(EXTRA_GESTURE)
                val containerId = intent.getStringExtra(EXTRA_CONTAINER_ID)
                val pageId = intent.getStringExtra(EXTRA_PAGE_ID)

                dispatchAction(
                    actionKey = actionKey,
                    handleId = handleId,
                    gesture = gesture,
                    containerId = containerId,
                    pageId = pageId
                )
            }
        }
    }

    init {
        registerBuiltInActions()
    }

    /**
     * Registers an Element/action type lazily.
     * Only the descriptor and factory are stored. The factory is NOT invoked here.
     */
    fun register(descriptor: ElementDescriptor, factory: ElementActionFactory) {
        descriptors[descriptor.actionKey] = descriptor
        factories[descriptor.actionKey] = factory
    }

    /**
     * Registers an Element/action using a descriptor and a lazy constructor lambda.
     */
    fun register(descriptor: ElementDescriptor, supplier: () -> ElementActionContract) {
        register(descriptor, ElementActionFactory { supplier() })
    }

    /**
     * Checks if an action key is registered in the registry or matches a modular Element prefix.
     */
    fun isRegistered(actionKey: String): Boolean {
        if (descriptors.containsKey(actionKey)) return true
        if (actionKey.contains(":")) {
            val prefix = actionKey.substringBefore(":")
            return when (prefix) {
                "app", "link", "intent", "quicktile", "system", "volume", "media", "display",
                "settings_shortcut", "page_window", "floating_trigger", "widget", "popup_widget",
                "folder", "spacer" -> true
                else -> false
            }
        }
        return false
    }

    /**
     * Resolves the lightweight descriptor for an action key without instantiating the element.
     */
    fun getDescriptor(actionKey: String): ElementDescriptor? {
        descriptors[actionKey]?.let { return it }
        if (actionKey.contains(":")) {
            val prefix = actionKey.substringBefore(":")
            val category = when (prefix) {
                "app", "link", "intent" -> ElementCategory.ANOTHER_APP_LINK
                "widget", "popup_widget" -> ElementCategory.ANDROID_WIDGET
                "system", "quicktile", "volume", "media", "display", "settings_shortcut" -> ElementCategory.TOOL_ACTION
                "page_window" -> ElementCategory.SCREEN_OVERLAY
                "floating_trigger" -> ElementCategory.SIDEBAR_PAGE_CONTENT
                else -> ElementCategory.TOOL_ACTION
            }
            return ElementDescriptor(
                actionKey = actionKey,
                displayName = actionKey.substringAfter(":"),
                category = category,
                description = "Modular element action for $actionKey"
            )
        }
        return null
    }

    /**
     * Resolves all registered descriptors, suitable for pickers or diagnostics, with zero instantiation.
     */
    fun getAllDescriptors(): List<ElementDescriptor> {
        return descriptors.values.toList()
    }

    /**
     * Resolves an action key to its ElementActionContract instance on-demand.
     * Invokes the registered factory lazily or generates an on-demand contract for modular Element keys.
     */
    fun resolve(actionKey: String): ElementActionContract? {
        val factory = factories[actionKey]
        if (factory != null) {
            return try {
                factory.create()
            } catch (e: Throwable) {
                LogKeeper.logError(context, TAG, "Failed to instantiate ElementAction for key '$actionKey'", e)
                null
            }
        }
        if (isRegistered(actionKey)) {
            val descriptor = getDescriptor(actionKey) ?: return null
            return object : CommonElementRuntimeContract {
                override val descriptor: ElementDescriptor = descriptor
                override fun execute(executionContext: ElementExecutionContext): Boolean {
                    val item = ElementIdParser.parse(executionContext.context, actionKey) ?: return false
                    return ElementActionDispatcher.execute(executionContext.context, item)
                }
            }
        }
        return null
    }

    /**
     * Checks if an action is currently available to execute.
     */
    fun isActionAvailable(actionKey: String): Boolean {
        val descriptor = descriptors[actionKey] ?: return false
        if (!descriptor.isAvailable) return false
        val contract = resolve(actionKey) ?: return false
        return contract.isAvailable(context)
    }

    /**
     * Dispatches an action configured for a gesture or trigger.
     * Returns true if successfully resolved and executed, false otherwise.
     * Safely handles unknown or unavailable actions without throwing.
     */
    fun dispatchAction(
        actionKey: String,
        handleId: String? = null,
        gesture: String? = null,
        containerId: String? = null,
        pageId: String? = null,
        extras: Map<String, Any?> = emptyMap()
    ): Boolean {
        // Special case: "none" action key
        if (actionKey.isEmpty() || actionKey == HandleManager.ACTION_NONE) {
            return false
        }

        // Check registration
        if (!isRegistered(actionKey)) {
            LogKeeper.log(context, TAG, "Action key '$actionKey' is not registered. Ignoring dispatch.")
            return false
        }

        val contract = resolve(actionKey)
        if (contract == null) {
            LogKeeper.log(context, TAG, "Action key '$actionKey' could not be resolved. Ignoring dispatch.")
            return false
        }

        if (!contract.isAvailable(context)) {
            LogKeeper.log(context, TAG, "Action key '$actionKey' is currently unavailable on this device. Ignoring dispatch.")
            return false
        }

        val executionContext = ElementExecutionContext(
            context = context,
            actionKey = actionKey,
            handleId = handleId,
            gesture = gesture,
            containerId = containerId,
            pageId = pageId,
            extras = extras
        )

        return try {
            val handled = contract.execute(executionContext)
            LogKeeper.log(context, TAG, "Dispatched action '$actionKey' (handleId=$handleId, gesture=$gesture, handled=$handled)")
            handled
        } catch (e: Throwable) {
            LogKeeper.logError(context, TAG, "Exception executing action '$actionKey'", e)
            false
        }
    }

    /**
     * Registers the broadcast receiver for `com.example.action.TRIGGER_ACTION`.
     */
    fun registerReceiver(ctx: Context) {
        if (!isReceiverRegistered) {
            val filter = IntentFilter(ACTION_TRIGGER_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, filter)
            }
            isReceiverRegistered = true
            LogKeeper.log(ctx, TAG, "Registered ElementActionRegistry receiver for $ACTION_TRIGGER_ACTION")
        }
    }

    /**
     * Unregisters the broadcast receiver safely.
     */
    fun unregisterReceiver(ctx: Context) {
        if (isReceiverRegistered) {
            try {
                ctx.unregisterReceiver(receiver)
            } catch (e: Exception) {
                // Ignored
            }
            isReceiverRegistered = false
            LogKeeper.log(ctx, TAG, "Unregistered ElementActionRegistry receiver")
        }
    }

    /**
     * Registers standard known placeholder/contract definitions for built-in action keys
     * without instantiating heavy implementations.
     */
    private fun registerBuiltInActions() {
        // ACTION_MOVE_HANDLE ("move_handle") - Handled directly in touch layer, but registered for contract completeness
        register(
            ElementDescriptor(
                actionKey = HandleManager.ACTION_MOVE_HANDLE,
                displayName = "Move Handle",
                category = ElementCategory.TOOL_ACTION,
                description = "Enables drag-to-reposition mode for the touch trigger handle."
            )
        ) {
            object : ElementActionContract {
                override val descriptor = descriptors[HandleManager.ACTION_MOVE_HANDLE]!!
                override fun execute(executionContext: ElementExecutionContext): Boolean {
                    // Handled internally in TriggerHandleView on touch events
                    return true
                }
            }
        }

        register(
            ElementDescriptor(
                actionKey = "display:keep_screen_on",
                displayName = "Keep Screen On",
                category = ElementCategory.TOOL_ACTION,
                description = "Toggles persistent screen-awake state without modifying system timeout."
            )
        ) {
            object : ElementActionContract {
                override val descriptor = descriptors["display:keep_screen_on"]!!
                override fun execute(executionContext: ElementExecutionContext): Boolean {
                    com.example.feature.system_hub.DisplayHandler.handleDisplayAction(executionContext.context, "keep_screen_on")
                    return true
                }
            }
        }

        register(
            ElementDescriptor(
                actionKey = "display:screen_orientation",
                displayName = "Screen Orientation",
                category = ElementCategory.TOOL_ACTION,
                description = "Toggles screen orientation across Auto-Rotate, Portrait, and Landscape modes."
            )
        ) {
            object : ElementActionContract {
                override val descriptor = descriptors["display:screen_orientation"]!!
                override fun execute(executionContext: ElementExecutionContext): Boolean {
                    com.example.feature.system_hub.DisplayHandler.handleDisplayAction(executionContext.context, "screen_orientation")
                    return true
                }
            }
        }

        register(
            ElementDescriptor(
                actionKey = "display:privacy_curtain",
                displayName = "Privacy Curtain",
                category = ElementCategory.TOOL_ACTION,
                description = "Toggles full-screen privacy darkening overlay with resizable transparent viewing window."
            )
        ) {
            object : ElementActionContract {
                override val descriptor = descriptors["display:privacy_curtain"]!!
                override fun execute(executionContext: ElementExecutionContext): Boolean {
                    com.example.feature.system_hub.DisplayHandler.handleDisplayAction(executionContext.context, "privacy_curtain")
                    return true
                }
            }
        }
    }

    companion object {
        private const val TAG = "ElementActionRegistry"

        const val ACTION_TRIGGER_ACTION = "com.example.action.TRIGGER_ACTION"
        const val EXTRA_ACTION_KEY = "action_key"
        const val EXTRA_HANDLE_ID = "handle_id"
        const val EXTRA_GESTURE = "gesture"
        const val EXTRA_CONTAINER_ID = "container_id"
        const val EXTRA_PAGE_ID = "page_id"

        @Volatile
        private var instance: ElementActionRegistry? = null

        fun getInstance(context: Context): ElementActionRegistry {
            return instance ?: synchronized(this) {
                instance ?: ElementActionRegistry(context.applicationContext).also { instance = it }
            }
        }
    }
}
