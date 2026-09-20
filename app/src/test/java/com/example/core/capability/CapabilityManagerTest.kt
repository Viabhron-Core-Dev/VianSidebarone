package com.example.core.capability

import android.content.Context
import android.content.ContextWrapper
import com.example.core.ipc.ConnectionState
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyConnectionListener
import com.example.core.ipc.HeavyProcessConnectionManager
import com.example.core.ipc.IHeavyHostContract
import com.example.core.ipc.IpcErrorCode
import com.example.core.ipc.IpcResult
import com.example.feature.element.CommonElementRuntimeContract
import com.example.feature.element.ElementCategory
import com.example.feature.element.ElementDescriptor
import com.example.feature.element.ElementExecutionContext
import com.example.feature.element.mountCapability
import com.example.feature.element.unmountCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CapabilityManagerTest {

    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example"
    }

    private lateinit var mockContext: Context
    private lateinit var capabilityManager: CapabilityManager

    @Before
    fun setUp() {
        mockContext = TestContext()
        capabilityManager = CapabilityManager.getInstance(mockContext)
        capabilityManager.releaseAll()
    }

    // 1. Verify Local Capability Mount -> Use -> Unmount Lifecycle
    @Test
    fun testLocalCapabilityFullLifecycle() {
        val factoryInvocations = AtomicInteger(0)
        val mountInvocations = AtomicInteger(0)
        val useInvocations = AtomicInteger(0)
        val unmountInvocations = AtomicInteger(0)
        val discardInvocations = AtomicInteger(0)

        val descriptor = CapabilityDescriptor(
            capabilityId = "test_local_math",
            displayName = "Local Math Engine",
            routing = CapabilityRouting.LOCAL_MAIN
        )

        capabilityManager.registerLocal(descriptor) {
            factoryInvocations.incrementAndGet()
            object : LocalCapabilityModule {
                override val descriptor: CapabilityDescriptor = descriptor

                override fun onMount(sessionToken: String, params: Map<String, String>): CapabilityResult {
                    mountInvocations.incrementAndGet()
                    return CapabilityResult.success(mapOf("status" to "mounted"))
                }

                override fun onUse(request: CapabilityRequest): CapabilityResult {
                    useInvocations.incrementAndGet()
                    val a = request.parameters["a"]?.toIntOrNull() ?: 0
                    val b = request.parameters["b"]?.toIntOrNull() ?: 0
                    return CapabilityResult.success(mapOf("sum" to "${a + b}"))
                }

                override fun onUnmount(sessionToken: String): Boolean {
                    unmountInvocations.incrementAndGet()
                    return true
                }

                override fun onDiscard() {
                    discardInvocations.incrementAndGet()
                }
            }
        }

        // Factory must NOT be invoked at registration time
        assertEquals(0, factoryInvocations.get())
        assertTrue(capabilityManager.isAvailable("test_local_math"))

        // Mount session
        val mountResult = capabilityManager.mount("test_local_math", ownerId = "element_1", params = emptyMap())
        assertTrue(mountResult.isSuccess)
        assertNotNull(mountResult.session)
        val session = mountResult.session!!

        assertEquals(1, factoryInvocations.get())
        assertEquals(1, mountInvocations.get())
        assertTrue(session.isMounted)

        // Use capability
        val useResult = session.use("add", mapOf("a" to "5", "b" to "7"))
        assertTrue(useResult.success)
        assertEquals("12", useResult.data["sum"])
        assertEquals(1, useInvocations.get())

        // Unmount session
        val unmounted = session.unmount()
        assertTrue(unmounted)
        assertFalse(session.isMounted)
        assertEquals(1, unmountInvocations.get())
        assertEquals(1, discardInvocations.get())
        assertEquals(0, capabilityManager.getActiveLocalModuleCount())

        // Subsequent use on unmounted session must fail
        val failedUse = session.use("add", mapOf("a" to "1", "b" to "2"))
        assertFalse(failedUse.success)
        assertEquals(CapabilityErrors.NOT_MOUNTED, failedUse.errorCode)
    }

    // 2. Verify Reference Counting and Idle Module Discard on Multiple Sessions
    @Test
    fun testLocalModuleSharedRefCountAndDiscard() {
        val factoryCount = AtomicInteger(0)
        val discardCount = AtomicInteger(0)

        val descriptor = CapabilityDescriptor(
            capabilityId = "shared_local_tool",
            displayName = "Shared Tool",
            routing = CapabilityRouting.LOCAL_MAIN
        )

        capabilityManager.registerLocal(descriptor) {
            factoryCount.incrementAndGet()
            object : LocalCapabilityModule {
                override val descriptor: CapabilityDescriptor = descriptor
                override fun onMount(sessionToken: String, params: Map<String, String>) = CapabilityResult.success()
                override fun onUse(request: CapabilityRequest) = CapabilityResult.success()
                override fun onUnmount(sessionToken: String) = true
                override fun onDiscard() {
                    discardCount.incrementAndGet()
                }
            }
        }

        val session1 = capabilityManager.mount("shared_local_tool", ownerId = "ownerA").session!!
        val session2 = capabilityManager.mount("shared_local_tool", ownerId = "ownerB").session!!

        // Single instance shared across sessions
        assertEquals(1, factoryCount.get())
        assertEquals(1, capabilityManager.getActiveLocalModuleCount())

        // Unmount session1 -> module still retained by session2
        session1.unmount()
        assertEquals(0, discardCount.get())
        assertEquals(1, capabilityManager.getActiveLocalModuleCount())

        // Unmount session2 -> module cleanly discarded
        session2.unmount()
        assertEquals(1, discardCount.get())
        assertEquals(0, capabilityManager.getActiveLocalModuleCount())
    }

    // 3. Verify Heavy-Routed Capability Mount -> Use -> Unmount
    @Test
    fun testHeavyRoutedCapabilityLifecycle() {
        val descriptor = CapabilityDescriptor(
            capabilityId = "future_heavy_mini_app",
            displayName = "Future Heavy Capability",
            routing = CapabilityRouting.REMOTE_HEAVY
        )

        capabilityManager.registerHeavy(descriptor)
        assertEquals(CapabilityRouting.REMOTE_HEAVY, capabilityManager.getDescriptor("future_heavy_mini_app")?.routing)

        // Set up mock Heavy process IPC proxy
        val connManager = HeavyProcessConnectionManager.getInstance(mockContext)
        val lastCommand = java.util.concurrent.atomic.AtomicReference<String?>()

        val mockHostProxy = object : IHeavyHostContract {
            override fun sendCommand(commandJson: String): String {
                lastCommand.set(commandJson)
                val cmd = HeavyCommand.fromJson(commandJson)
                return if (cmd.payload["action"] == "use") {
                    IpcResult.success("Handled by mock Heavy", mapOf("output" to "heavy_ack")).toJson()
                } else {
                    IpcResult.success("Ack").toJson()
                }
            }
            override fun syncSnapshot(snapshotJson: String): String = IpcResult.success().toJson()
            override fun ping(): Boolean = true
            override fun registerCallback(callbackBinder: android.os.IBinder): Boolean = true
            override fun unregisterCallback(): Boolean = true
        }

        connManager.setMockProxyForTesting(mockHostProxy, ConnectionState.CONNECTED)

        // Mount Heavy-routed capability
        val mountResult = capabilityManager.mount("future_heavy_mini_app", ownerId = "element_heavy")
        assertTrue(mountResult.isSuccess)
        val session = mountResult.session!!
        assertEquals(CapabilityRouting.REMOTE_HEAVY, session.routing)

        assertNotNull(lastCommand.get())
        val mountCmd = HeavyCommand.fromJson(lastCommand.get()!!)
        assertEquals("future_heavy_mini_app", mountCmd.targetId)
        assertEquals("mount", mountCmd.payload["action"])

        // Use Heavy capability
        val useResult = session.use("heavy_operation", mapOf("query" to "test_value"))
        assertTrue(useResult.success)
        assertEquals("heavy_ack", useResult.data["output"])

        val useCmd = HeavyCommand.fromJson(lastCommand.get()!!)
        assertEquals("use", useCmd.payload["action"])
        assertEquals("heavy_operation", useCmd.payload["operation"])
        assertEquals("test_value", useCmd.payload["query"])

        // Unmount
        assertTrue(session.unmount())
        val unmountCmd = HeavyCommand.fromJson(lastCommand.get()!!)
        assertEquals("unmount", unmountCmd.payload["action"])
    }

    // 4. Verify Heavy Process Death Recovery Resilience
    @Test
    fun testHeavyProcessDeathResilience() {
        val descriptor = CapabilityDescriptor(
            capabilityId = "heavy_resilience_test",
            displayName = "Heavy Resilience",
            routing = CapabilityRouting.REMOTE_HEAVY
        )
        capabilityManager.registerHeavy(descriptor)

        val connManager = HeavyProcessConnectionManager.getInstance(mockContext)
        connManager.setMockProxyForTesting(null, ConnectionState.DISCONNECTED)

        val mountResult = capabilityManager.mount("heavy_resilience_test")
        // Since proxy is disconnected and autoConnect starts connecting, manager gracefully handles it
        assertNotNull(mountResult)

        // Trigger death recipient: Main process must not crash and sessions remain tracked or recoverable
        connManager.triggerDeathRecipientForTesting()
        assertEquals(ConnectionState.DEAD, connManager.state)
    }

    // 5. Verify Owner-Scoped Batch Unmount (e.g. When Element or Page is Released)
    @Test
    fun testOwnerScopedBatchUnmount() {
        val descriptorA = CapabilityDescriptor("cap_a", "Cap A", CapabilityRouting.LOCAL_MAIN)
        val descriptorB = CapabilityDescriptor("cap_b", "Cap B", CapabilityRouting.LOCAL_MAIN)

        val unmountedA = AtomicBoolean(false)
        val unmountedB = AtomicBoolean(false)

        capabilityManager.registerLocal(descriptorA) {
            object : LocalCapabilityModule {
                override val descriptor = descriptorA
                override fun onMount(s: String, p: Map<String, String>) = CapabilityResult.success()
                override fun onUse(r: CapabilityRequest) = CapabilityResult.success()
                override fun onUnmount(s: String): Boolean {
                    unmountedA.set(true)
                    return true
                }
            }
        }

        capabilityManager.registerLocal(descriptorB) {
            object : LocalCapabilityModule {
                override val descriptor = descriptorB
                override fun onMount(s: String, p: Map<String, String>) = CapabilityResult.success()
                override fun onUse(r: CapabilityRequest) = CapabilityResult.success()
                override fun onUnmount(s: String): Boolean {
                    unmountedB.set(true)
                    return true
                }
            }
        }

        capabilityManager.mount("cap_a", ownerId = "element_weather")
        capabilityManager.mount("cap_b", ownerId = "element_weather")

        assertEquals(2, capabilityManager.getActiveSessionCount())

        // Batch unmount for element_weather
        val unmountedCount = capabilityManager.unmountAll("element_weather")
        assertEquals(2, unmountedCount)
        assertEquals(0, capabilityManager.getActiveSessionCount())
        assertTrue(unmountedA.get())
        assertTrue(unmountedB.get())
    }

    // 6. Verify Element Integration Extensions
    @Test
    fun testElementCapabilityExtensionIntegration() {
        val descriptor = CapabilityDescriptor("element_helper_cap", "Helper", CapabilityRouting.LOCAL_MAIN)
        capabilityManager.registerLocal(descriptor) {
            object : LocalCapabilityModule {
                override val descriptor = descriptor
                override fun onMount(s: String, p: Map<String, String>) = CapabilityResult.success(mapOf("init" to "ok"))
                override fun onUse(r: CapabilityRequest) = CapabilityResult.success(mapOf("pong" to "true"))
                override fun onUnmount(s: String) = true
            }
        }

        // Test from ElementExecutionContext
        val execContext = ElementExecutionContext(
            context = mockContext,
            actionKey = "action_mock_tool",
            pageId = "page_1"
        )

        val mountResult = execContext.mountCapability("element_helper_cap")
        assertTrue(mountResult.isSuccess)
        val session = mountResult.session!!
        assertEquals("true", session.use("ping").data["pong"])

        // Test from CommonElementRuntimeContract
        val mockElement = object : CommonElementRuntimeContract {
            override val descriptor = ElementDescriptor(
                actionKey = "action_mock_tool",
                displayName = "Mock Tool",
                category = ElementCategory.TOOL_ACTION
            )
            override fun execute(executionContext: ElementExecutionContext): Boolean = true
        }

        // Unmount via extension
        val unmountedCount = mockElement.unmountCapabilities(mockContext, ownerId = "page_1_action_mock_tool")
        assertEquals(1, unmountedCount)
    }

    // 7. Verify Unknown / Unavailable Capability Handling
    @Test
    fun testUnknownCapabilityHandling() {
        val result = capabilityManager.mount("non_existent_capability")
        assertFalse(result.isSuccess)
        assertNull(result.session)
        assertEquals(CapabilityErrors.NOT_FOUND, result.result.errorCode)
    }
}
