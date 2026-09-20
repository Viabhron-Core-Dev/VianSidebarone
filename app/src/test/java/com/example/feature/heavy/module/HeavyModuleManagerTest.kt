package com.example.feature.heavy.module

import android.content.Context
import android.content.ContextWrapper
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyCommandType
import com.example.core.ipc.IpcErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class HeavyModuleManagerTest {

    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example"
    }

    private lateinit var mockContext: Context
    private lateinit var moduleManager: HeavyModuleManager

    @Before
    fun setUp() {
        mockContext = TestContext()
        moduleManager = HeavyModuleManager(mockContext)
    }

    // 1. Registration & Discovery: verify descriptors are retrievable and modules are registered
    @Test
    fun testRegistrationAndDiscovery() {
        val descriptor = HeavyModuleDescriptor(
            moduleId = "heavy_reader",
            displayName = "Offline Reader Engine",
            version = 1,
            description = "High-performance epub reader in Heavy process",
            category = "productivity"
        )
        val factoryCalled = AtomicBoolean(false)

        moduleManager.register(descriptor) {
            factoryCalled.set(true)
            object : HeavyModuleContract {
                override val descriptor: HeavyModuleDescriptor = descriptor
                override fun onUse(request: HeavyModuleExecutionRequest): HeavyModuleResult =
                    HeavyModuleResult.success()
            }
        }

        assertTrue(moduleManager.isRegistered("heavy_reader"))
        assertEquals(descriptor, moduleManager.getDescriptor("heavy_reader"))
        assertTrue(moduleManager.getAllDescriptors().any { it.moduleId == "heavy_reader" })
        assertFalse("Factory must not be called during registration", factoryCalled.get())
        assertFalse("Module must not be active before mount", moduleManager.isModuleActive("heavy_reader"))
    }

    // 2. Ensuring registered modules are not instantiated until needed (lazy module creation)
    @Test
    fun testEnsuringRegisteredModulesAreNotInstantiatedUntilNeeded() {
        val factoryCounts = (1..5).map { AtomicInteger(0) }

        (1..5).forEach { i ->
            val desc = HeavyModuleDescriptor(moduleId = "module_$i")
            moduleManager.register(desc) {
                factoryCounts[i - 1].incrementAndGet()
                object : HeavyModuleContract {
                    override val descriptor: HeavyModuleDescriptor = desc
                    override fun onUse(request: HeavyModuleExecutionRequest): HeavyModuleResult =
                        HeavyModuleResult.success()
                }
            }
        }

        // None should be instantiated
        factoryCounts.forEach { assertEquals(0, it.get()) }
        assertEquals(0, moduleManager.getActiveModuleCount())

        // Mount only module 3
        val mountResult = moduleManager.mount("module_3")
        assertTrue(mountResult.isSuccess)

        // Only module 3 factory was called
        assertEquals(0, factoryCounts[0].get())
        assertEquals(0, factoryCounts[1].get())
        assertEquals(1, factoryCounts[2].get())
        assertEquals(0, factoryCounts[3].get())
        assertEquals(0, factoryCounts[4].get())

        assertEquals(1, moduleManager.getActiveModuleCount())
        assertTrue(moduleManager.isModuleActive("module_3"))
        assertFalse(moduleManager.isModuleActive("module_1"))
    }

    // 3. Mount / Acquire: parameters passed to onMount and session returned
    @Test
    fun testMountAndAcquire() {
        val mountParamsReceived = HashMap<String, String>()
        val desc = HeavyModuleDescriptor(moduleId = "heavy_calc")

        moduleManager.register(desc) {
            object : HeavyModuleContract {
                override val descriptor: HeavyModuleDescriptor = desc
                override fun onMount(sessionToken: String, params: Map<String, String>): HeavyModuleResult {
                    mountParamsReceived.putAll(params)
                    return HeavyModuleResult.success(mapOf("status" to "ready"))
                }
                override fun onUse(request: HeavyModuleExecutionRequest): HeavyModuleResult =
                    HeavyModuleResult.success()
            }
        }

        val mountResult = moduleManager.mount(
            moduleId = "heavy_calc",
            sessionToken = "session_calc_1",
            ownerId = "owner_widget_1",
            params = mapOf("precision" to "double", "mode" to "scientific")
        )

        assertTrue(mountResult.isSuccess)
        assertNotNull(mountResult.session)
        assertEquals("session_calc_1", mountResult.session?.sessionToken)
        assertEquals("heavy_calc", mountResult.session?.moduleId)
        assertEquals("owner_widget_1", mountResult.session?.ownerId)
        assertTrue(mountResult.session?.isMounted == true)

        assertEquals("double", mountParamsReceived["precision"])
        assertEquals("scientific", mountParamsReceived["mode"])
        assertEquals("ready", mountResult.result.data["status"])
    }

    // 4. Multiple sessions & reference counting
    @Test
    fun testMultipleSessionsAndReferenceCounting() {
        val factoryCount = AtomicInteger(0)
        val disposeCount = AtomicInteger(0)
        val desc = HeavyModuleDescriptor(moduleId = "shared_engine")

        moduleManager.register(desc) {
            factoryCount.incrementAndGet()
            object : HeavyModuleContract {
                override val descriptor: HeavyModuleDescriptor = desc
                override fun onUse(request: HeavyModuleExecutionRequest): HeavyModuleResult =
                    HeavyModuleResult.success()
                override fun onDispose() {
                    disposeCount.incrementAndGet()
                }
            }
        }

        // Mount session 1
        val mount1 = moduleManager.mount("shared_engine", sessionToken = "session_1")
        assertTrue(mount1.isSuccess)
        assertEquals(1, factoryCount.get())
        assertEquals(1, moduleManager.getActiveModuleCount())
        assertEquals(1, moduleManager.getActiveSessionCount())

        // Mount session 2
        val mount2 = moduleManager.mount("shared_engine", sessionToken = "session_2")
        assertTrue(mount2.isSuccess)
        assertEquals("Factory must not be called again for second session", 1, factoryCount.get())
        assertEquals(1, moduleManager.getActiveModuleCount())
        assertEquals(2, moduleManager.getActiveSessionCount())

        // Unmount session 1 -> module should NOT be disposed yet
        val unmounted1 = moduleManager.unmount("session_1")
        assertTrue(unmounted1)
        assertEquals(0, disposeCount.get())
        assertTrue(moduleManager.isModuleActive("shared_engine"))
        assertEquals(1, moduleManager.getActiveSessionCount())

        // Unmount session 2 -> module SHOULD be disposed
        val unmounted2 = moduleManager.unmount("session_2")
        assertTrue(unmounted2)
        assertEquals(1, disposeCount.get())
        assertFalse(moduleManager.isModuleActive("shared_engine"))
        assertEquals(0, moduleManager.getActiveSessionCount())
    }

    // 5. Operation dispatch: verify request routing and response
    @Test
    fun testOperationDispatch() {
        val desc = HeavyModuleDescriptor(moduleId = "heavy_evaluator")
        moduleManager.register(desc) {
            object : HeavyModuleContract {
                override val descriptor: HeavyModuleDescriptor = desc
                override fun onUse(request: HeavyModuleExecutionRequest): HeavyModuleResult {
                    return if (request.operation == "add") {
                        val a = request.parameters["a"]?.toIntOrNull() ?: 0
                        val b = request.parameters["b"]?.toIntOrNull() ?: 0
                        HeavyModuleResult.success(mapOf("sum" to (a + b).toString()))
                    } else {
                        HeavyModuleResult.error("UNKNOWN_OP", "Unsupported: ${request.operation}")
                    }
                }
            }
        }

        val mount = moduleManager.mount("heavy_evaluator")
        val session = mount.session!!

        // Call use via session handle
        val result1 = session.use("add", mapOf("a" to "15", "b" to "27"))
        assertTrue(result1.success)
        assertEquals("42", result1.data["sum"])

        // Call use via manager
        val result2 = moduleManager.use(session.sessionToken, "add", mapOf("a" to "100", "b" to "200"))
        assertTrue(result2.success)
        assertEquals("300", result2.data["sum"])
    }

    // 6. Release & Unmount: verify state transition and onUnmount call
    @Test
    fun testReleaseAndUnmount() {
        val unmountCalled = AtomicBoolean(false)
        val desc = HeavyModuleDescriptor(moduleId = "heavy_logger")

        moduleManager.register(desc) {
            object : HeavyModuleContract {
                override val descriptor: HeavyModuleDescriptor = desc
                override fun onUse(request: HeavyModuleExecutionRequest): HeavyModuleResult =
                    HeavyModuleResult.success()
                override fun onUnmount(sessionToken: String): Boolean {
                    unmountCalled.set(true)
                    return true
                }
            }
        }

        val mount = moduleManager.mount("heavy_logger")
        val session = mount.session!!
        assertTrue(session.isMounted)

        val unmounted = session.unmount()
        assertTrue(unmounted)
        assertFalse(session.isMounted)
        assertTrue(unmountCalled.get())
    }

    // 7. Idle module disposal: idleListener triggered when sessions reach 0
    @Test
    fun testIdleModuleDisposal() {
        val idleTriggered = AtomicBoolean(false)
        moduleManager.setIdleListener { idleTriggered.set(true) }

        val desc = HeavyModuleDescriptor(moduleId = "ephemeral_module")
        moduleManager.register(desc) {
            object : HeavyModuleContract {
                override val descriptor: HeavyModuleDescriptor = desc
                override fun onUse(request: HeavyModuleExecutionRequest): HeavyModuleResult =
                    HeavyModuleResult.success()
            }
        }

        val mount = moduleManager.mount("ephemeral_module")
        val session = mount.session!!
        assertFalse(idleTriggered.get())

        moduleManager.unmount(session.sessionToken)
        assertTrue("Idle listener must be triggered when active sessions reach 0", idleTriggered.get())
    }

    // 8. Owner-scoped cleanup: bulk unmount by owner identifier
    @Test
    fun testOwnerScopedCleanup() {
        val desc = HeavyModuleDescriptor(moduleId = "modular_widget")
        moduleManager.register(desc) {
            object : HeavyModuleContract {
                override val descriptor: HeavyModuleDescriptor = desc
                override fun onUse(request: HeavyModuleExecutionRequest): HeavyModuleResult =
                    HeavyModuleResult.success()
            }
        }

        // Mount 3 sessions for owner A
        val s1 = moduleManager.mount("modular_widget", ownerId = "owner_page_1").session!!
        val s2 = moduleManager.mount("modular_widget", ownerId = "owner_page_1").session!!
        val s3 = moduleManager.mount("modular_widget", ownerId = "owner_page_1").session!!

        // Mount 1 session for owner B
        val s4 = moduleManager.mount("modular_widget", ownerId = "owner_page_2").session!!

        assertEquals(4, moduleManager.getActiveSessionCount())

        // Bulk cleanup for owner A
        val unmountedCount = moduleManager.unmountAll("owner_page_1")
        assertEquals(3, unmountedCount)
        assertFalse(s1.isMounted)
        assertFalse(s2.isMounted)
        assertFalse(s3.isMounted)

        // Owner B session must still be active
        assertTrue(s4.isMounted)
        assertEquals(1, moduleManager.getActiveSessionCount())

        // Cleanup owner B
        assertEquals(1, moduleManager.unmountAll("owner_page_2"))
        assertFalse(s4.isMounted)
        assertEquals(0, moduleManager.getActiveSessionCount())
    }

    // 9. Unknown module error handling
    @Test
    fun testUnknownModule() {
        val mountResult = moduleManager.mount("non_existent_module_999")
        assertFalse(mountResult.isSuccess)
        assertNull(mountResult.session)
        assertEquals(HeavyModuleErrors.NOT_FOUND, mountResult.result.errorCode)
    }

    // 10. Unavailable module error handling
    @Test
    fun testUnavailableModule() {
        val desc = HeavyModuleDescriptor(moduleId = "unavailable_module")
        val factoryCalled = AtomicBoolean(false)

        moduleManager.register(desc) {
            factoryCalled.set(true)
            object : HeavyModuleContract {
                override val descriptor: HeavyModuleDescriptor = desc
                override fun isAvailable(context: Context?): Boolean = false
                override fun onUse(request: HeavyModuleExecutionRequest): HeavyModuleResult =
                    HeavyModuleResult.success()
            }
        }

        // Pre-instantiate to trigger isAvailable false, or test with uninstantiated
        // If module is instantiated and isAvailable returns false:
        val mount1 = moduleManager.mount("unavailable_module")
        assertTrue(mount1.isSuccess) // First mount instantiates it

        // Now that it's active and isAvailable returns false:
        val mount2 = moduleManager.mount("unavailable_module", sessionToken = "session_unavail_2")
        assertFalse(mount2.isSuccess)
        assertEquals(HeavyModuleErrors.UNAVAILABLE, mount2.result.errorCode)
    }

    // 11. Invalid / released session error handling
    @Test
    fun testInvalidOrReleasedSession() {
        // Use with completely unknown session
        val res1 = moduleManager.use("invalid_session_token", "ping")
        assertFalse(res1.success)
        assertEquals(HeavyModuleErrors.NOT_MOUNTED, res1.errorCode)

        // Mount and unmount, then attempt use
        val desc = HeavyModuleDescriptor(moduleId = "test_mod")
        moduleManager.register(desc) {
            object : HeavyModuleContract {
                override val descriptor: HeavyModuleDescriptor = desc
                override fun onUse(request: HeavyModuleExecutionRequest): HeavyModuleResult =
                    HeavyModuleResult.success()
            }
        }

        val mount = moduleManager.mount("test_mod")
        val token = mount.session!!.sessionToken
        moduleManager.unmount(token)

        val res2 = moduleManager.use(token, "ping")
        assertFalse(res2.success)
        assertEquals(HeavyModuleErrors.NOT_MOUNTED, res2.errorCode)
    }

    // 12. Repeated release safety: unmounting multiple times must be safe and idempotent
    @Test
    fun testRepeatedRelease() {
        val desc = HeavyModuleDescriptor(moduleId = "safe_release_mod")
        moduleManager.register(desc) {
            object : HeavyModuleContract {
                override val descriptor: HeavyModuleDescriptor = desc
                override fun onUse(request: HeavyModuleExecutionRequest): HeavyModuleResult =
                    HeavyModuleResult.success()
            }
        }

        val mount = moduleManager.mount("safe_release_mod")
        val token = mount.session!!.sessionToken

        // 1st unmount: succeeds
        assertTrue(moduleManager.unmount(token))

        // 2nd unmount: safely returns false without exception
        assertFalse(moduleManager.unmount(token))

        // 3rd unmount: safely returns false
        assertFalse(moduleManager.unmount(token))
    }

    // 13. Operation failure handling: gracefully catches exceptions in onUse
    @Test
    fun testOperationFailure() {
        val desc = HeavyModuleDescriptor(moduleId = "faulty_module")
        moduleManager.register(desc) {
            object : HeavyModuleContract {
                override val descriptor: HeavyModuleDescriptor = desc
                override fun onUse(request: HeavyModuleExecutionRequest): HeavyModuleResult {
                    throw IllegalStateException("Hardware engine crashed unexpectedly")
                }
            }
        }

        val mount = moduleManager.mount("faulty_module")
        val session = mount.session!!

        val result = session.use("crash_test")
        assertFalse(result.success)
        assertEquals(HeavyModuleErrors.OPERATION_FAILED, result.errorCode)
        assertTrue(result.message.contains("Hardware engine crashed unexpectedly"))
    }

    // 14. IPC command round-trip handling: mount -> use -> unmount -> unmountAll
    @Test
    fun testIpcCommandHandling() {
        val desc = HeavyModuleDescriptor(moduleId = "ipc_test_mod")
        moduleManager.register(desc) {
            object : HeavyModuleContract {
                override val descriptor: HeavyModuleDescriptor = desc
                override fun onMount(sessionToken: String, params: Map<String, String>): HeavyModuleResult {
                    return HeavyModuleResult.success(mapOf("init_ok" to "true"))
                }
                override fun onUse(request: HeavyModuleExecutionRequest): HeavyModuleResult {
                    return HeavyModuleResult.success(mapOf("echo" to (request.parameters["msg"] ?: "")))
                }
            }
        }

        // 1. IPC Mount
        val mountCmd = HeavyCommand(
            commandId = "cmd_mount",
            type = HeavyCommandType.START_OPERATION,
            payload = mapOf(
                "action" to "mount",
                "moduleId" to "ipc_test_mod",
                "sessionToken" to "ipc_token_1",
                "ownerId" to "ipc_owner_1"
            )
        )
        val mountRes = moduleManager.handleIpcCommand(mountCmd)
        assertTrue(mountRes.success)
        assertEquals("true", mountRes.data["init_ok"])

        // 2. IPC Use
        val useCmd = HeavyCommand(
            commandId = "cmd_use",
            type = HeavyCommandType.START_OPERATION,
            payload = mapOf(
                "action" to "use",
                "sessionToken" to "ipc_token_1",
                "operation" to "echo",
                "msg" to "hello_heavy"
            )
        )
        val useRes = moduleManager.handleIpcCommand(useCmd)
        assertTrue(useRes.success)
        assertEquals("hello_heavy", useRes.data["echo"])

        // 3. IPC Unmount
        val unmountCmd = HeavyCommand(
            commandId = "cmd_unmount",
            type = HeavyCommandType.STOP_OPERATION,
            payload = mapOf(
                "action" to "unmount",
                "sessionToken" to "ipc_token_1"
            )
        )
        val unmountRes = moduleManager.handleIpcCommand(unmountCmd)
        assertTrue(unmountRes.success)
        assertEquals(0, moduleManager.getActiveSessionCount())

        // 4. IPC UnmountAll
        moduleManager.mount("ipc_test_mod", sessionToken = "token_a", ownerId = "owner_x")
        moduleManager.mount("ipc_test_mod", sessionToken = "token_b", ownerId = "owner_x")
        assertEquals(2, moduleManager.getActiveSessionCount())

        val unmountAllCmd = HeavyCommand(
            commandId = "cmd_unmount_all",
            type = HeavyCommandType.STOP_OPERATION,
            payload = mapOf(
                "action" to "unmountAll",
                "ownerId" to "owner_x"
            )
        )
        val unmountAllRes = moduleManager.handleIpcCommand(unmountAllCmd)
        assertTrue(unmountAllRes.success)
        assertEquals("2", unmountAllRes.data["unmounted_count"])
        assertEquals(0, moduleManager.getActiveSessionCount())
    }
}
