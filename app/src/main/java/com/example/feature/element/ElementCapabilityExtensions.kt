package com.example.feature.element

import android.content.Context
import com.example.core.capability.CapabilityManager
import com.example.core.capability.CapabilityResult
import com.example.core.capability.CapabilitySession
import com.example.core.capability.MountResult

/**
 * ElementCapabilityExtensions: Integration bridge providing Elements in the Main process
 * with access to modular capabilities through the stable [CapabilityManager] contract.
 *
 * Guarantees:
 * 1. Elements depend strictly on capability contracts, NEVER on future concrete mini-app classes.
 * 2. Elements can acquire, use, and release capabilities through the mount → use → unmount lifecycle.
 * 3. Does NOT alter the existing ElementAction system or registry.
 * 4. Enables automatic owner-scoped unmounting when an Element is released or container is dismissed.
 */

/**
 * Mounts a capability from an active [ElementExecutionContext].
 * Automatically scopes ownership to the triggering element and page.
 */
fun ElementExecutionContext.mountCapability(
    capabilityId: String,
    params: Map<String, String> = emptyMap()
): MountResult {
    val manager = CapabilityManager.getInstance(context)
    val ownerId = pageId?.let { "${it}_${actionKey}" } ?: actionKey
    return manager.mount(capabilityId = capabilityId, ownerId = ownerId, params = params)
}

/**
 * Mounts a capability for an active [CommonElementRuntimeContract].
 *
 * @param context Android context
 * @param capabilityId Target capability identifier
 * @param ownerId Optional owner identifier (defaults to element actionKey)
 * @param params Optional configuration/initialization parameters
 */
fun CommonElementRuntimeContract.mountCapability(
    context: Context,
    capabilityId: String,
    ownerId: String? = null,
    params: Map<String, String> = emptyMap()
): MountResult {
    val manager = CapabilityManager.getInstance(context)
    val resolvedOwnerId = ownerId ?: descriptor.actionKey
    return manager.mount(capabilityId = capabilityId, ownerId = resolvedOwnerId, params = params)
}

/**
 * Releases all capabilities mounted by this Element instance.
 * Typically invoked in [CommonElementRuntimeContract.onRelease].
 */
fun CommonElementRuntimeContract.unmountCapabilities(
    context: Context,
    ownerId: String? = null
): Int {
    val manager = CapabilityManager.getInstance(context)
    val resolvedOwnerId = ownerId ?: descriptor.actionKey
    return manager.unmountAll(resolvedOwnerId)
}
