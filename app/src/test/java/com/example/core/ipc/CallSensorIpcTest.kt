package com.example.core.ipc

import android.telephony.TelephonyManager
import com.example.core.CallRecorderManager
import com.example.feature.call.DefaultHeavyCallHostExtension
import com.example.feature.call.HeavyCallHostExtension
import com.example.feature.call.HeavyCallRecorderEngine
import com.example.feature.call.RealHeavyCallHostExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
            sessionId = "session_test_123",
            timestamp = 1710000000000L
        )

        assertEquals(HeavyCommandType.CALL_STATE_CHANGED, cmd.type)
        assertEquals(CallIpcContract.TARGET_CALL_RECORDER, cmd.targetId)
        assertEquals("2", cmd.payload[CallIpcContract.KEY_RAW_STATE])
        assertEquals("OFFHOOK", cmd.payload[CallIpcContract.KEY_CALL_STATE])
        assertEquals("CALL_STARTED", cmd.payload[CallIpcContract.KEY_TRANSITION])
        assertEquals("session_test_123", cmd.payload[CallIpcContract.KEY_SESSION_ID])
        assertEquals("1710000000000", cmd.payload[CallIpcContract.KEY_TIMESTAMP])

        // Verify JSON roundtrip
        val json = cmd.toJson()
        val parsed = HeavyCommand.fromJson(json)
        assertEquals(cmd.commandId, parsed.commandId)
        assertEquals(cmd.type, parsed.type)
        assertEquals(cmd.payload, parsed.payload)

        // Request operation command
        val reqCmd = CallIpcContract.createRequestRecorderCommand(CallIpcContract.OP_START, "session_test_123", 1710000001000L)
        assertEquals(HeavyCommandType.REQUEST_CALL_RECORDER, reqCmd.type)
        assertEquals(CallIpcContract.TARGET_CALL_RECORDER, reqCmd.targetId)
        assertEquals("START", reqCmd.payload[CallIpcContract.KEY_OPERATION])
        assertEquals("session_test_123", reqCmd.payload[CallIpcContract.KEY_SESSION_ID])
    }

    @Test
    fun testEventPayloadIntegrity() {
        val startEvent = CallIpcContract.createRecordingStartedEvent("sess_456", "/path/to/CALL_test.m4a", 1710000002000L)
        assertEquals(HeavyEventType.STATE_UPDATED, startEvent.type)
        assertEquals(CallIpcContract.TARGET_CALL_RECORDER, startEvent.sourceId)
        assertEquals("sess_456", startEvent.payload[CallIpcContract.KEY_SESSION_ID])
        assertEquals("/path/to/CALL_test.m4a", startEvent.payload[CallIpcContract.KEY_FILE_PATH])
        assertEquals("true", startEvent.payload[CallIpcContract.KEY_RECORDING_ACTIVE])

        val stopEvent = CallIpcContract.createRecordingStoppedEvent("sess_456", "/path/to/CALL_test.m4a", 45000L, 1710000047000L)
        assertEquals(HeavyEventType.OPERATION_FINISHED, stopEvent.type)
        assertEquals("45000", stopEvent.payload[CallIpcContract.KEY_DURATION_MS])
        assertEquals("false", stopEvent.payload[CallIpcContract.KEY_RECORDING_ACTIVE])

        val errEvent = CallIpcContract.createRecordingErrorEvent("sess_456", "AUDIO_CAPTURE_UNAVAILABLE", "Mic busy", 1710000003000L)
        assertEquals(HeavyEventType.ERROR_REPORTED, errEvent.type)
        assertEquals("AUDIO_CAPTURE_UNAVAILABLE", errEvent.payload[CallIpcContract.KEY_ERROR_CODE])
        assertEquals("Mic busy", errEvent.payload[CallIpcContract.KEY_ERROR_MESSAGE])
    }

    // 2. Call start / end transitions & dispatch via CallRecorderManager
    @Test
    fun testCallTransitionsAndDispatchSequence() {
        val dispatchedCommands = CopyOnWriteArrayList<HeavyCommand>()
        val mockProxy = object : IHeavyHostContract {
            override fun sendCommand(commandJson: String): String {
                val cmd = HeavyCommand.fromJson(commandJson)
                dispatchedCommands.add(cmd)
                return IpcResult.success("Handled ${cmd.type}", mapOf(CallIpcContract.KEY_RECORDING_ACTIVE to "true", CallIpcContract.KEY_FILE_PATH to "/path/CALL_test.m4a")).toJson()
            }
            override fun syncSnapshot(snapshotJson: String): String = IpcResult.success().toJson()
            override fun registerCallback(callbackBinder: android.os.IBinder): Boolean = true
            override fun unregisterCallback(): Boolean = true
            override fun ping(): Boolean = true
        }

        val connManager = HeavyProcessConnectionManager(context = null)
        connManager.setMockProxyForTesting(mockProxy, ConnectionState.CONNECTED)

        val callManager = CallRecorderManager(context = null, connectionManager = connManager)
        callManager.testEnabledOverride = true

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
        assertTrue(callManager.isRecordingExpected)
        assertNotNull(callManager.activeSessionId)
        assertTrue(callManager.isHeavyRecordingActive)

        // Should have sent CALL_STATE_CHANGED and REQUEST_CALL_RECORDER (START)
        assertEquals(3, dispatchedCommands.size)
        assertEquals(HeavyCommandType.CALL_STATE_CHANGED, dispatchedCommands[1].type)
        assertEquals(HeavyCommandType.REQUEST_CALL_RECORDER, dispatchedCommands[2].type)
        assertEquals("START", dispatchedCommands[2].payload[CallIpcContract.KEY_OPERATION])

        // Step C: Duplicate state ignored completely (Idempotent!)
        callManager.handleCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        assertEquals(3, dispatchedCommands.size)

        // Step D: Call ended (IDLE)
        callManager.handleCallState(TelephonyManager.CALL_STATE_IDLE)
        assertEquals(TelephonyManager.CALL_STATE_IDLE, callManager.currentCallState)
        assertEquals(CallIpcContract.TRANSITION_ENDED, callManager.lastTransition)
        assertFalse(callManager.isRecordingExpected)
        assertNull(callManager.activeSessionId)
        assertFalse(callManager.isHeavyRecordingActive)

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

    // 3. Authoritative Call State Machine Idempotency
    @Test
    fun testAuthoritativeCallStateMachineIdempotency() {
        val dispatchedCommands = CopyOnWriteArrayList<HeavyCommand>()
        val mockProxy = object : IHeavyHostContract {
            override fun sendCommand(commandJson: String): String {
                val cmd = HeavyCommand.fromJson(commandJson)
                dispatchedCommands.add(cmd)
                return IpcResult.success("OK").toJson()
            }
            override fun syncSnapshot(snapshotJson: String): String = IpcResult.success().toJson()
            override fun registerCallback(callbackBinder: android.os.IBinder): Boolean = true
            override fun unregisterCallback(): Boolean = true
            override fun ping(): Boolean = true
        }

        val connManager = HeavyProcessConnectionManager(context = null)
        connManager.setMockProxyForTesting(mockProxy, ConnectionState.CONNECTED)

        val callManager = CallRecorderManager(context = null, connectionManager = connManager)
        callManager.testEnabledOverride = true

        // Simulate rapid repeated OFFHOOK events from both receiver and telephony callback
        callManager.handleCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        val firstSession = callManager.activeSessionId
        assertNotNull(firstSession)
        val initialCommandCount = dispatchedCommands.size

        // Repeated identical callbacks
        callManager.handleCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        callManager.handleCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        callManager.onCallStateReceived(TelephonyManager.CALL_STATE_OFFHOOK)

        // Must not create new sessions or dispatch duplicate commands
        assertEquals(firstSession, callManager.activeSessionId)
        assertEquals(initialCommandCount, dispatchedCommands.size)

        // End call
        callManager.handleCallState(TelephonyManager.CALL_STATE_IDLE)
        assertNull(callManager.activeSessionId)
        val afterIdleCount = dispatchedCommands.size

        // Repeated IDLE callbacks
        callManager.handleCallState(TelephonyManager.CALL_STATE_IDLE)
        callManager.onCallStateReceived(TelephonyManager.CALL_STATE_IDLE)
        assertEquals(afterIdleCount, dispatchedCommands.size)
    }

    // 4. Heavy host routing & extension point tests
    @Test
    fun testHeavyProcessHostCallExtensionRouting() {
        val host = HeavyProcessHost(context = null)
        val defaultExtension = host.getCallHostExtension()
        assertNotNull(defaultExtension)
        assertTrue(defaultExtension is RealHeavyCallHostExtension)

        // Dispatch call state command to HeavyProcessHost
        val stateCmd = CallIpcContract.createCallStateCommand(
            rawState = TelephonyManager.CALL_STATE_RINGING,
            stateStr = CallIpcContract.STATE_RINGING,
            transition = CallIpcContract.TRANSITION_RINGING
        )
        val resultState = host.handleCommand(stateCmd)
        assertTrue(resultState.success)
        assertTrue(resultState.message.contains("RINGING"))

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

            override fun isRecordingActive(): Boolean = false
        }

        host.setCallHostExtension(customExtension)
        val customResult = host.handleCommand(stateCmd)
        assertTrue(customResult.success)
        assertTrue(customCalled.get())
        assertEquals("Custom extension received call state", customResult.message)
    }

    // 5. Heavy unavailable & dead process resilience
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
        callManager.testEnabledOverride = true

        // Simulate dead binder event
        connManager.triggerDeathRecipientForTesting()
        assertEquals(ConnectionState.DEAD, connManager.state)

        // Main process Call Sensor must continue operating cleanly without crash
        callManager.handleCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        assertEquals(TelephonyManager.CALL_STATE_OFFHOOK, callManager.currentCallState)
    }

    // 6. Heavy process death and reconnect state resynchronization
    @Test
    fun testHeavyDeathAndReconnectSynchronization() {
        val dispatchedCommands = CopyOnWriteArrayList<HeavyCommand>()
        val mockProxy = object : IHeavyHostContract {
            override fun sendCommand(commandJson: String): String {
                val cmd = HeavyCommand.fromJson(commandJson)
                dispatchedCommands.add(cmd)
                return IpcResult.success("OK").toJson()
            }
            override fun syncSnapshot(snapshotJson: String): String = IpcResult.success().toJson()
            override fun registerCallback(callbackBinder: android.os.IBinder): Boolean = true
            override fun unregisterCallback(): Boolean = true
            override fun ping(): Boolean = true
        }

        val connManager = HeavyProcessConnectionManager(context = null)
        connManager.setMockProxyForTesting(mockProxy, ConnectionState.CONNECTED)

        val callManager = CallRecorderManager(context = null, connectionManager = connManager)
        callManager.testEnabledOverride = true

        // 1. Call starts in OFFHOOK
        callManager.handleCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        val activeSession = callManager.activeSessionId
        assertNotNull(activeSession)

        // 2. Heavy dies during active call
        callManager.onHeavyProcessDied()
        assertFalse(callManager.isHeavyRecordingActive)
        assertEquals(activeSession, callManager.activeSessionId)

        // 3. Heavy reconnects while call is STILL active
        dispatchedCommands.clear()
        connManager.setMockProxyForTesting(mockProxy, ConnectionState.CONNECTED)
        callManager.onConnected()

        // Verify resynchronization: sent OP_START for ongoing session
        assertTrue(dispatchedCommands.any {
            it.type == HeavyCommandType.REQUEST_CALL_RECORDER &&
                    it.payload[CallIpcContract.KEY_OPERATION] == "START" &&
                    it.payload[CallIpcContract.KEY_SESSION_ID] == activeSession
        })
    }

    // 7. HeavyEvent dispatch updates Main state
    @Test
    fun testHeavyEventDispatchUpdatesMainState() {
        val callManager = CallRecorderManager(context = null)
        callManager.testEnabledOverride = true

        // Simulate call answered
        callManager.handleCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        val sessionId = callManager.activeSessionId ?: "sess_1"

        // Heavy reports RECORDING_STARTED
        val startEvent = CallIpcContract.createRecordingStartedEvent(sessionId, "/storage/CALL_123.m4a")
        callManager.onHeavyEvent(startEvent)
        assertTrue(callManager.isHeavyRecordingActive)
        assertEquals("/storage/CALL_123.m4a", callManager.activeRecordingPath)

        // Heavy reports RECORDING_STOPPED
        val stopEvent = CallIpcContract.createRecordingStoppedEvent(sessionId, "/storage/CALL_123.m4a", 15000L)
        callManager.onHeavyEvent(stopEvent)
        assertFalse(callManager.isHeavyRecordingActive)
        assertNull(callManager.activeRecordingPath)

        // Heavy reports RECORDING_ERROR
        val errorEvent = CallIpcContract.createRecordingErrorEvent(sessionId, "AUDIO_CAPTURE_UNAVAILABLE", "Failed to start")
        callManager.onHeavyEvent(errorEvent)
        assertFalse(callManager.isHeavyRecordingActive)
    }

    // 8. Screen-off / background availability
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
