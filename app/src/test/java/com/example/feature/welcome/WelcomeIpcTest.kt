package com.example.feature.welcome

import com.example.core.ipc.ConnectionState
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyCommandType
import com.example.core.ipc.HeavyProcessConnectionManager
import com.example.core.ipc.HeavyProcessHost
import com.example.core.ipc.IHeavyHostContract
import com.example.core.ipc.IpcErrorCode
import com.example.core.ipc.IpcResult
import com.example.core.ipc.WelcomeIpcContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class WelcomeIpcTest {

    @Before
    fun setUp() {
        WelcomeActivity.setActiveForTesting(false)
        MainWelcomeController.resetInstanceForTesting()
    }

    @After
    fun tearDown() {
        WelcomeActivity.setActiveForTesting(false)
        MainWelcomeController.resetInstanceForTesting()
    }

    // 1. Contract mapping & JSON serialization integrity
    @Test
    fun testWelcomeContractAndPayloadIntegrity() {
        val timestamp = 1710000000000L
        val cmd = WelcomeIpcContract.createShowWelcomeCommand(
            reason = WelcomeIpcContract.REASON_OVERLAY_REQUIRED,
            timestamp = timestamp
        )

        assertEquals(HeavyCommandType.SHOW_WELCOME, cmd.type)
        assertEquals(WelcomeIpcContract.TARGET_WELCOME, cmd.targetId)
        assertEquals("overlay_required", cmd.payload[WelcomeIpcContract.KEY_REASON])
        assertEquals("1710000000000", cmd.payload[WelcomeIpcContract.KEY_TIMESTAMP])

        // Verify JSON roundtrip
        val json = cmd.toJson()
        val parsed = HeavyCommand.fromJson(json)
        assertEquals(cmd.commandId, parsed.commandId)
        assertEquals(cmd.type, parsed.type)
        assertEquals(cmd.targetId, parsed.targetId)
        assertEquals(cmd.payload, parsed.payload)
        assertEquals(cmd.timestamp, parsed.timestamp)
    }

    // 2. Main request -> Heavy Welcome routing via HeavyProcessHost
    @Test
    fun testMainRequestToHeavyWelcomeRouting() {
        val host = HeavyProcessHost(context = null)
        val receivedCommands = CopyOnWriteArrayList<HeavyCommand>()

        val testExtension = object : HeavyWelcomeHostExtension {
            override fun onShowWelcome(command: HeavyCommand): IpcResult {
                receivedCommands.add(command)
                return IpcResult.success("Handled SHOW_WELCOME")
            }
        }
        host.setWelcomeHostExtension(testExtension)

        val cmd = WelcomeIpcContract.createShowWelcomeCommand(reason = WelcomeIpcContract.REASON_SETUP)
        val result = host.handleCommand(cmd)

        assertTrue(result.success)
        assertEquals(1, receivedCommands.size)
        assertEquals(HeavyCommandType.SHOW_WELCOME, receivedCommands[0].type)
        assertEquals("setup", receivedCommands[0].payload[WelcomeIpcContract.KEY_REASON])
    }

    // 3. Deduplication: No duplicate Welcome launches from repeated requests
    @Test
    fun testNoDuplicateWelcomeLaunchesWhenAlreadyActive() {
        val extension = DefaultHeavyWelcomeHostExtension(context = null, debounceWindowMs = 1000L)
        val cmd = WelcomeIpcContract.createShowWelcomeCommand(reason = "setup")

        // Case A: When WelcomeActivity is already active in foreground
        WelcomeActivity.setActiveForTesting(true)
        val activeResult = extension.onShowWelcome(cmd)
        assertTrue(activeResult.success)
        assertEquals("Welcome already active", activeResult.message)

        // Case B: WelcomeActivity is inactive, first launch succeeds/acknowledges
        WelcomeActivity.setActiveForTesting(false)
        val firstResult = extension.onShowWelcome(cmd)
        assertTrue(firstResult.success)

        // Rapid repeated launch within debounce window (< 1000ms)
        val secondResult = extension.onShowWelcome(cmd)
        assertTrue(secondResult.success)
        assertEquals("Welcome launch debounced", secondResult.message)
    }

    // 4. Heavy unavailable/dead behavior: Main remains functional and does not crash
    @Test
    fun testHeavyUnavailableAndDeadResilienceInMain() {
        val connManager = HeavyProcessConnectionManager(context = null)
        connManager.setMockProxyForTesting(null, ConnectionState.DISCONNECTED)

        val controller = MainWelcomeController(context = null, connectionManager = connManager)

        // Request when disconnected: returns error code HEAVY_UNAVAILABLE without crashing
        val resultDisconnected = controller.requestWelcome("overlay_needed")
        assertFalse(resultDisconnected.success)
        assertEquals(IpcErrorCode.HEAVY_UNAVAILABLE, resultDisconnected.errorCode)

        // Simulate Heavy process death
        connManager.setMockProxyForTesting(null, ConnectionState.DEAD)
        val resultDead = controller.requestWelcome("overlay_needed")
        assertFalse(resultDead.success)
        assertEquals(IpcErrorCode.HEAVY_UNAVAILABLE, resultDead.errorCode)
    }

    // 5. Successful Main -> ConnectionManager -> Mock Proxy dispatch
    @Test
    fun testMainWelcomeControllerSuccessfulDispatch() {
        val dispatchedCommands = CopyOnWriteArrayList<HeavyCommand>()
        val mockProxy = object : IHeavyHostContract {
            override fun sendCommand(commandJson: String): String {
                val cmd = HeavyCommand.fromJson(commandJson)
                dispatchedCommands.add(cmd)
                return IpcResult.success("Welcome handled by Heavy").toJson()
            }
            override fun syncSnapshot(snapshotJson: String): String = IpcResult.success().toJson()
            override fun registerCallback(callbackBinder: android.os.IBinder): Boolean = true
            override fun unregisterCallback(): Boolean = true
            override fun ping(): Boolean = true
        }

        val connManager = HeavyProcessConnectionManager(context = null)
        connManager.setMockProxyForTesting(mockProxy, ConnectionState.CONNECTED)

        val controller = MainWelcomeController(context = null, connectionManager = connManager)
        val result = controller.requestWelcome(reason = "user_settings_click")

        assertTrue(result.success)
        assertEquals(1, dispatchedCommands.size)
        assertEquals(HeavyCommandType.SHOW_WELCOME, dispatchedCommands[0].type)
        assertEquals("user_settings_click", dispatchedCommands[0].payload[WelcomeIpcContract.KEY_REASON])
    }
}
