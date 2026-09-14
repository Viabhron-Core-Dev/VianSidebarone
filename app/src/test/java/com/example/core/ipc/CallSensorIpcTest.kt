package com.example.core.ipc

import android.telephony.TelephonyManager
import com.example.core.CallRecorderManager
import com.example.feature.call.DefaultHeavyCallHostExtension
import com.example.feature.call.HeavyCallHostExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CallSensorIpcTest {

    // 1. Call-state to Heavy command mapping & contract tests
    @Test
    fun testCallStateContractMapping() {
        assertEquals(CallIpcContract.STATE_IDLE, CallIpcContract.toCallStateString(TelephonyManager.CALL_STATE_IDLE))
        assertEquals(CallIpcContract.STATE_RINGING, CallIpcContract.toCallStateString(TelephonyManager.CALL_STATE_RINGING))
        assertEquals(CallIpcContract.STATE_OFFHOOK, CallIpcContract.toCallStateString(TelephonyManager.CALL_STATE_OFFHOOK))
        assertEquals(CallIpcContract.STATE_UNKNOWN, CallIpcContract.toCallStateString(99))

        // State transitions
        assertEquals(
            CallIpcContract.TRANSITION_RINGING,
            CallIpcContract.determineTransition(TelephonyManager.CALL_STATE_IDLE, TelephonyManager.CALL_STATE_RINGING)
        )
        assertEquals(
            CallIpcContract.TRANSITION_STARTED,
            CallIpcContract.determineTransition(TelephonyManager.CALL_STATE_RINGING, TelephonyManager.CALL_STATE_OFFHOOK)
        )
        assertEquals(
            CallIpcContract.TRANSITION_STARTED,
            CallIpcContract.determineTransition(TelephonyManager.CALL_STATE_IDLE, TelephonyManager.CALL_STATE_OFFHOOK)
        )
        assertEquals(
            CallIpcContract.TRANSITION_ENDED,
            CallIpcContract.determineTransition(TelephonyManager.CALL_STATE_OFFHOOK, TelephonyManager.CALL_STATE_IDLE)
        )
        assertEquals(
            CallIpcContract.TRANSITION_UNCHANGED,
            CallIpcContract.determineTransition(TelephonyManager.CALL_STATE_IDLE, TelephonyManager.CALL_STATE_IDLE)
        )
    }

    @Test
    fun testCallCommandPayloadIntegrity() {
        val cmd = CallIpcContract.createCallStateCommand(
            rawState = TelephonyManager.CALL_STATE_OFFHOOK,
            stateStr = CallIpcContract.STATE_OFFHOOK,
            transition = CallIpcContract.TRANSITION_STARTED,
            timestamp = 1710000000000L
        )

        assertEquals(HeavyCommandType.CALL_STATE_CHANGED, cmd.type)
        assertEquals(CallIpcContract.TARGET_CALL_RECORDER, cmd.targetId)
        assertEquals("2", cmd.payload[CallIpcContract.KEY_RAW_STATE])
        assertEquals("OFFHOOK", cmd.payload[CallIpcContract.KEY_CALL_STATE])
        assertEquals("CALL_STARTED", cmd.payload[CallIpcContract.KEY_TRANSITION])
        assertEquals("1710000000000", cmd.payload[CallIpcContract.KEY_TIMESTAMP])

        // Verify JSON roundtrip
        val json = cmd.toJson()
        val parsed = HeavyCommand.fromJson(json)
        assertEquals(cmd.commandId, parsed.commandId)
        assertEquals(cmd.type, parsed.type)
        assertEquals(cmd.payload, parsed.payload)

        // Request operation command
        val reqCmd = CallIpcContract.createRequestRecorderCommand(CallIpcContract.OP_START, 1710000001000L)
        assertEquals(HeavyCommandType.REQUEST_CALL_RECORDER, reqCmd.type)
        assertEquals(CallIpcContract.TARGET_CALL_RECORDER, reqCmd.targetId)
        assertEquals("START", reqCmd.payload[CallIpcContract.KEY_OPERATION])
    }

    // 2. Call start / end transitions & dispatch via CallRecorderManager
    @Test
    fun testCallTransitionsAndDispatchSequence() {
        val dispatchedCommands = CopyOnWriteArrayList<HeavyCommand>()
        val mockProxy = object : IHeavyHostContract {
            override fun sendCommand(commandJson: String): String {
                val cmd = HeavyCommand.fromJson(commandJson)
                dispatchedCommands.add(cmd)
                return IpcResult.success("Handled ${cmd.type}").toJson()
            }
            override fun syncSnapshot(snapshotJson: String): String = IpcResult.success().toJson()
            override fun registerCallback(callbackBinder: android.os.IBinder): Boolean = true
            override fun unregisterCallback(): Boolean = true
            override fun ping(): Boolean = true
        }

        val connManager = HeavyProcessConnectionManager(context = null)
        connManager.setMockProxyForTesting(mockProxy, ConnectionState.CONNECTED)

        val callManager = CallRecorderManager(context = null, connectionManager = connManager)
        val transitionsObserved = mutableListOf<String>()
        callManager.addListener { prev, newSt, trans ->
            transitionsObserved.add("$prev->$newSt:$trans")
        }

        // Step A: Incoming call rings
        callManager.handleCallState(TelephonyManager.CALL_STATE_RINGING)
        assertEquals(TelephonyManager.CALL_STATE_RINGING, callManager.currentCallState)
        assertEquals(CallIpcContract.TRANSITION_RINGING, callManager.lastTransition)
        assertEquals(1, dispatchedCommands.size)
        assertEquals(HeavyCommandType.CALL_STATE_CHANGED, dispatchedCommands[0].type)
        assertEquals("RINGING", dispatchedCommands[0].payload[CallIpcContract.KEY_CALL_STATE])

        // Step B: Call answered (Active/OFFHOOK)
        callManager.handleCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        assertEquals(TelephonyManager.CALL_STATE_OFFHOOK, callManager.currentCallState)
        assertEquals(CallIpcContract.TRANSITION_STARTED, callManager.lastTransition)
        // Should have sent CALL_STATE_CHANGED and REQUEST_CALL_RECORDER (START)
        assertEquals(3, dispatchedCommands.size)
        assertEquals(HeavyCommandType.CALL_STATE_CHANGED, dispatchedCommands[1].type)
        assertEquals(HeavyCommandType.REQUEST_CALL_RECORDER, dispatchedCommands[2].type)
        assertEquals("START", dispatchedCommands[2].payload[CallIpcContract.KEY_OPERATION])

        // Step C: Duplicate state ignored
        callManager.handleCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        assertEquals(3, dispatchedCommands.size)

        // Step D: Call ended (IDLE)
        callManager.handleCallState(TelephonyManager.CALL_STATE_IDLE)
        assertEquals(TelephonyManager.CALL_STATE_IDLE, callManager.currentCallState)
        assertEquals(CallIpcContract.TRANSITION_ENDED, callManager.lastTransition)
        // Should have sent CALL_STATE_CHANGED and REQUEST_CALL_RECORDER (STOP)
        assertEquals(5, dispatchedCommands.size)
        assertEquals(HeavyCommandType.CALL_STATE_CHANGED, dispatchedCommands[3].type)
        assertEquals(HeavyCommandType.REQUEST_CALL_RECORDER, dispatchedCommands[4].type)
        assertEquals("STOP", dispatchedCommands[4].payload[CallIpcContract.KEY_OPERATION])

        assertEquals(3, transitionsObserved.size)
        assertEquals("0->1:CALL_RINGING", transitionsObserved[0])
        assertEquals("1->2:CALL_STARTED", transitionsObserved[1])
        assertEquals("2->0:CALL_ENDED", transitionsObserved[2])
    }

    // 3. Heavy host routing & extension point tests
    @Test
    fun testHeavyProcessHostCallExtensionRouting() {
        val host = HeavyProcessHost(context = null)
        val defaultExtension = host.getCallHostExtension()
        assertNotNull(defaultExtension)
        assertTrue(defaultExtension is DefaultHeavyCallHostExtension)

        // Dispatch call state command to HeavyProcessHost
        val stateCmd = CallIpcContract.createCallStateCommand(
            rawState = TelephonyManager.CALL_STATE_RINGING,
            stateStr = CallIpcContract.STATE_RINGING,
            transition = CallIpcContract.TRANSITION_RINGING
        )
        val resultState = host.handleCommand(stateCmd)
        assertTrue(resultState.success)
        assertTrue(resultState.message.contains("RINGING"))

        // Dispatch request recorder command
        val reqCmd = CallIpcContract.createRequestRecorderCommand(CallIpcContract.OP_START)
        val resultReq = host.handleCommand(reqCmd)
        assertTrue(resultReq.success)
        assertTrue(resultReq.message.contains("START"))

        // Test custom extension point registration
        val customCalled = AtomicBoolean(false)
        val customExtension = object : HeavyCallHostExtension {
            override fun onCallStateChanged(command: HeavyCommand): IpcResult {
                customCalled.set(true)
                return IpcResult.success("Custom extension received call state")
            }

            override fun onRequestCallRecorder(command: HeavyCommand): IpcResult {
                return IpcResult.success("Custom extension received operation")
            }
        }

        host.setCallHostExtension(customExtension)
        val customResult = host.handleCommand(stateCmd)
        assertTrue(customResult.success)
        assertTrue(customCalled.get())
        assertEquals("Custom extension received call state", customResult.message)
    }

    // 4. Heavy unavailable & dead process resilience
    @Test
    fun testHeavyUnavailableAndQueueingBehavior() {
        val connManager = HeavyProcessConnectionManager(context = null)
        assertEquals(ConnectionState.DISCONNECTED, connManager.state)

        val callManager = CallRecorderManager(context = null, connectionManager = connManager)

        // When Heavy is disconnected, dispatching must not throw or crash Main
        callManager.handleCallState(TelephonyManager.CALL_STATE_RINGING)
        assertEquals(TelephonyManager.CALL_STATE_RINGING, callManager.currentCallState)

        // Verify command was enqueued for when Heavy connects
        assertTrue(connManager.getPendingCommandCountForTesting() > 0)

        // When connection succeeds, queued commands are flushed to the proxy
        val delivered = CopyOnWriteArrayList<String>()
        val mockProxy = object : IHeavyHostContract {
            override fun sendCommand(commandJson: String): String {
                delivered.add(commandJson)
                return IpcResult.success().toJson()
            }
            override fun syncSnapshot(snapshotJson: String): String = IpcResult.success().toJson()
            override fun registerCallback(callbackBinder: android.os.IBinder): Boolean = true
            override fun unregisterCallback(): Boolean = true
            override fun ping(): Boolean = true
        }

        connManager.setMockProxyForTesting(mockProxy, ConnectionState.CONNECTED)
        assertEquals(0, connManager.getPendingCommandCountForTesting())
        assertTrue(delivered.isNotEmpty())
    }

    @Test
    fun testDeadBinderResilience() {
        val connManager = HeavyProcessConnectionManager(context = null)
        val callManager = CallRecorderManager(context = null, connectionManager = connManager)

        // Simulate dead binder event
        connManager.triggerDeathRecipientForTesting()
        assertEquals(ConnectionState.DEAD, connManager.state)

        // Main process Call Sensor must continue operating cleanly without crash
        callManager.handleCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        assertEquals(TelephonyManager.CALL_STATE_OFFHOOK, callManager.currentCallState)
    }

    // 5. Screen-off / background availability
    @Test
    fun testScreenOffCallSensorAvailability() {
        val callManager = CallRecorderManager(context = null)
        assertTrue(callManager.isAvailableDuringScreenOff)

        // Can receive and process call state transitions when screen is off
        callManager.handleCallState(TelephonyManager.CALL_STATE_RINGING)
        assertEquals(TelephonyManager.CALL_STATE_RINGING, callManager.currentCallState)
        assertEquals(CallIpcContract.TRANSITION_RINGING, callManager.lastTransition)
    }
}
