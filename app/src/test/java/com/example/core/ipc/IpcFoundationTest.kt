package com.example.core.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class IpcFoundationTest {

    // 1. Serialization Tests
    @Test
    fun testHeavyCommandSerialization() {
        val command = HeavyCommand(
            commandId = "cmd_12345",
            type = HeavyCommandType.START_OPERATION,
            targetId = "target_abc",
            payload = mapOf(
                "key1" to "value1",
                "quote" to "Hello \"World\"",
                "newline" to "Line 1\nLine 2"
            ),
            timestamp = 1700000000000L
        )

        val json = command.toJson()
        val parsed = HeavyCommand.fromJson(json)

        assertEquals("cmd_12345", parsed.commandId)
        assertEquals(HeavyCommandType.START_OPERATION, parsed.type)
        assertEquals("target_abc", parsed.targetId)
        assertEquals(1700000000000L, parsed.timestamp)
        assertEquals("value1", parsed.payload["key1"])
        assertEquals("Hello \"World\"", parsed.payload["quote"])
        assertEquals("Line 1\nLine 2", parsed.payload["newline"])
    }

    @Test
    fun testHeavyCommandNullableTarget() {
        val command = HeavyCommand(
            commandId = "cmd_no_target",
            type = HeavyCommandType.PING,
            targetId = null
        )
        val json = command.toJson()
        val parsed = HeavyCommand.fromJson(json)

        assertEquals("cmd_no_target", parsed.commandId)
        assertEquals(HeavyCommandType.PING, parsed.type)
        assertNull(parsed.targetId)
        assertTrue(parsed.payload.isEmpty())
    }

    @Test
    fun testHeavyEventSerialization() {
        val event = HeavyEvent(
            eventId = "evt_999",
            type = HeavyEventType.LIFECYCLE_CHANGED,
            sourceId = "heavy_worker_1",
            payload = mapOf("state" to "ACTIVE"),
            timestamp = 1700000005000L
        )

        val json = event.toJson()
        val parsed = HeavyEvent.fromJson(json)

        assertEquals("evt_999", parsed.eventId)
        assertEquals(HeavyEventType.LIFECYCLE_CHANGED, parsed.type)
        assertEquals("heavy_worker_1", parsed.sourceId)
        assertEquals("ACTIVE", parsed.payload["state"])
        assertEquals(1700000005000L, parsed.timestamp)
    }

    @Test
    fun testMainStateSnapshotSerialization() {
        val snapshot = MainStateSnapshot(
            snapshotId = "snap_001",
            timestamp = 1700000010000L,
            isScreenOn = true,
            isEditMode = false,
            activeContainerId = "sidebar_left",
            activePageId = "page_tools",
            config = mapOf("theme" to "dark", "orientation" to "portrait")
        )

        val json = snapshot.toJson()
        val parsed = MainStateSnapshot.fromJson(json)

        assertEquals("snap_001", parsed.snapshotId)
        assertEquals(1700000010000L, parsed.timestamp)
        assertTrue(parsed.isScreenOn)
        assertFalse(parsed.isEditMode)
        assertEquals("sidebar_left", parsed.activeContainerId)
        assertEquals("page_tools", parsed.activePageId)
        assertEquals("dark", parsed.config["theme"])
        assertEquals("portrait", parsed.config["orientation"])
    }

    @Test
    fun testMainCommandRequestSerialization() {
        val request = MainCommandRequest(
            requestId = "req_toast",
            action = MainActionType.SHOW_TOAST,
            targetId = null,
            parameters = mapOf("message" to "Operation complete"),
            timestamp = 1700000020000L
        )

        val json = request.toJson()
        val parsed = MainCommandRequest.fromJson(json)

        assertEquals("req_toast", parsed.requestId)
        assertEquals(MainActionType.SHOW_TOAST, parsed.action)
        assertNull(parsed.targetId)
        assertEquals("Operation complete", parsed.parameters["message"])
        assertEquals(1700000020000L, parsed.timestamp)
    }

    @Test
    fun testIpcResultSerialization() {
        val successResult = IpcResult.success("Operation ok", mapOf("resultCode" to "200"))
        val parsedSuccess = IpcResult.fromJson(successResult.toJson())
        assertTrue(parsedSuccess.success)
        assertEquals(IpcErrorCode.OK, parsedSuccess.errorCode)
        assertEquals("Operation ok", parsedSuccess.message)
        assertEquals("200", parsedSuccess.data["resultCode"])

        val errorResult = IpcResult.error(IpcErrorCode.DEAD_BINDER, "Process terminated")
        val parsedError = IpcResult.fromJson(errorResult.toJson())
        assertFalse(parsedError.success)
        assertEquals(IpcErrorCode.DEAD_BINDER, parsedError.errorCode)
        assertEquals("Process terminated", parsedError.message)
    }

    // 2. Heavy Unavailable Behavior
    @Test
    fun testHeavyUnavailableBehavior() {
        val manager = HeavyProcessConnectionManager(context = null)
        assertEquals(ConnectionState.DISCONNECTED, manager.state)

        // Sending command while disconnected with autoConnect=false
        val cmd = HeavyCommand("c1", HeavyCommandType.PING)
        val result = manager.sendCommand(cmd, autoConnect = false)
        assertFalse(result.success)
        assertEquals(IpcErrorCode.HEAVY_UNAVAILABLE, result.errorCode)

        // Syncing snapshot while disconnected
        val snapshot = MainStateSnapshot("s1")
        val syncResult = manager.syncSnapshot(snapshot, autoConnect = false)
        assertFalse(syncResult.success)
        assertEquals(IpcErrorCode.HEAVY_UNAVAILABLE, syncResult.errorCode)

        // Ping while disconnected
        assertFalse(manager.ping())
    }

    // 3. Connection and Dispatch Behavior
    @Test
    fun testConnectionAndDispatchWithMockProxy() {
        val manager = HeavyProcessConnectionManager(context = null)

        val lastReceivedCommandJson = StringBuilder()
        val mockProxy = object : IHeavyHostContract {
            override fun sendCommand(commandJson: String): String {
                lastReceivedCommandJson.setLength(0)
                lastReceivedCommandJson.append(commandJson)
                return IpcResult.success("Handled mock command").toJson()
            }

            override fun syncSnapshot(snapshotJson: String): String {
                return IpcResult.success("Snapshot synced").toJson()
            }

            override fun ping(): Boolean = true
            override fun registerCallback(callbackBinder: android.os.IBinder): Boolean = true
            override fun unregisterCallback(): Boolean = true
        }

        manager.setMockProxyForTesting(mockProxy, ConnectionState.CONNECTED)
        assertEquals(ConnectionState.CONNECTED, manager.state)
        assertTrue(manager.ping())

        val cmd = HeavyCommand("cmd_test", HeavyCommandType.UPDATE_CONFIG, payload = mapOf("rate" to "60"))
        val result = manager.sendCommand(cmd)
        assertTrue(result.success)
        assertEquals("Handled mock command", result.message)

        val parsedFromMock = HeavyCommand.fromJson(lastReceivedCommandJson.toString())
        assertEquals("cmd_test", parsedFromMock.commandId)
        assertEquals("60", parsedFromMock.payload["rate"])

        // Test Disconnect
        manager.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, manager.state)
        assertFalse(manager.ping())

        // Command after disconnect should fail safely
        val afterDisconnectResult = manager.sendCommand(cmd, autoConnect = false)
        assertFalse(afterDisconnectResult.success)
        assertEquals(IpcErrorCode.HEAVY_UNAVAILABLE, afterDisconnectResult.errorCode)
    }

    // 4. Binder Death and Reconnect Handling
    @Test
    fun testBinderDeathHandling() {
        val manager = HeavyProcessConnectionManager(context = null)

        val deathNotified = AtomicBoolean(false)
        val disconnectNotified = AtomicBoolean(false)
        val listener = object : HeavyConnectionListener {
            override fun onHeavyProcessDied() {
                deathNotified.set(true)
            }

            override fun onDisconnected() {
                disconnectNotified.set(true)
            }
        }
        manager.addListener(listener)

        val mockProxy = object : IHeavyHostContract {
            override fun sendCommand(commandJson: String): String = IpcResult.success().toJson()
            override fun syncSnapshot(snapshotJson: String): String = IpcResult.success().toJson()
            override fun ping(): Boolean = true
            override fun registerCallback(callbackBinder: android.os.IBinder): Boolean = true
            override fun unregisterCallback(): Boolean = true
        }

        manager.setMockProxyForTesting(mockProxy, ConnectionState.CONNECTED)
        assertEquals(ConnectionState.CONNECTED, manager.state)

        // Trigger death recipient
        manager.triggerDeathRecipientForTesting()

        assertEquals(ConnectionState.DEAD, manager.state)
        assertTrue("Expected listener to receive onHeavyProcessDied()", deathNotified.get())
        assertFalse(manager.ping())

        // Sending command while dead returns error and does not crash
        val cmd = HeavyCommand("cmd_dead", HeavyCommandType.PING)
        val res = manager.sendCommand(cmd, autoConnect = false)
        assertFalse(res.success)
        assertEquals(IpcErrorCode.HEAVY_UNAVAILABLE, res.errorCode)

        // Reconnect simulation
        manager.setMockProxyForTesting(mockProxy, ConnectionState.CONNECTED)
        assertEquals(ConnectionState.CONNECTED, manager.state)
        val reconnectedRes = manager.sendCommand(cmd, autoConnect = false)
        assertTrue(reconnectedRes.success)

        manager.removeListener(listener)
    }

    // 5. Heavy Process Host & Snapshot Hierarchy Isolation
    @Test
    fun testHeavyProcessHostIsolationAndCommandHandler() {
        val host = HeavyProcessHost(context = null)

        // Sync snapshot from Main
        val snapshot = MainStateSnapshot(
            snapshotId = "main_snap_1",
            activeContainerId = "cont_primary",
            activePageId = "page_1"
        )
        val syncResJson = host.onSyncSnapshot(snapshot.toJson())
        val syncResult = IpcResult.fromJson(syncResJson)
        assertTrue(syncResult.success)

        // Verify Heavy has cached read-only snapshot, does not instantiate duplicate hierarchy
        assertNotNull(host.cachedSnapshot)
        assertEquals("main_snap_1", host.cachedSnapshot?.snapshotId)
        assertEquals("cont_primary", host.cachedSnapshot?.activeContainerId)

        // Register Command Handler in Heavy
        val commandCount = AtomicInteger(0)
        val handler = HeavyCommandHandler { cmd ->
            commandCount.incrementAndGet()
            IpcResult.success("Heavy handled ${cmd.commandId}")
        }
        host.registerCommandHandler(HeavyCommandType.START_OPERATION, handler)

        val cmd = HeavyCommand("start_1", HeavyCommandType.START_OPERATION, targetId = "worker_1")
        val cmdResJson = host.onSendCommand(cmd.toJson())
        val cmdResult = IpcResult.fromJson(cmdResJson)
        assertTrue(cmdResult.success)
        assertEquals("Heavy handled start_1", cmdResult.message)
        assertEquals(1, commandCount.get())

        // Unregister handler
        host.unregisterCommandHandler(HeavyCommandType.START_OPERATION, handler)
        val cmdRes2Json = host.onSendCommand(cmd.toJson())
        val cmdResult2 = IpcResult.fromJson(cmdRes2Json)
        assertTrue(cmdResult2.success)
        assertEquals(1, commandCount.get()) // Handler was not invoked again
    }
}
