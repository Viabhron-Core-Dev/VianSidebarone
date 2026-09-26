package com.example.feature.call

import android.content.Context
import android.content.ContextWrapper
import com.example.core.ipc.CallIpcContract
import com.example.core.ipc.HeavyEvent
import com.example.core.ipc.HeavyProcessHost
import com.example.core.ipc.IpcErrorCode
import com.example.core.ipc.IpcResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class HeavyCallRecorderEngineTest {

    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example"
    }

    private val reportedEvents = CopyOnWriteArrayList<HeavyEvent>()

    private val mockHost = object : HeavyProcessHost(context = null) {
        override fun reportEvent(event: HeavyEvent): IpcResult {
            reportedEvents.add(event)
            return IpcResult.success()
        }
    }

    @Before
    fun setUp() {
        reportedEvents.clear()
        HeavyCallRecorderEngine.resetForTesting()
    }

    @Test
    fun testEngineInitialState() {
        val engine = HeavyCallRecorderEngine(context = null, hostProvider = { mockHost })
        assertFalse(engine.isRecording)
        assertNull(engine.currentSessionId)
        assertNull(engine.currentFilePath)

        val status = engine.getStatus()
        assertTrue(status.success)
        assertEquals("false", status.data[CallIpcContract.KEY_RECORDING_ACTIVE])
    }

    @Test
    fun testStopWhenNotRecordingIsIdempotent() {
        val engine = HeavyCallRecorderEngine(context = null, hostProvider = { mockHost })
        val result = engine.stopRecording("any_session")
        assertTrue(result.success)
        assertEquals("false", result.data[CallIpcContract.KEY_RECORDING_ACTIVE])
        assertFalse(engine.isRecording)
    }

    @Test
    fun testRealHeavyCallHostExtensionDelegation() {
        val mockEngine = object : HeavyCallRecorderEngine(context = null) {
            var startCalledWith: String? = null
            var stopCalledWith: String? = null

            override fun startRecording(sessionId: String, audioSourceOverride: Int?): IpcResult {
                startCalledWith = sessionId
                return IpcResult.success("Started $sessionId", mapOf(CallIpcContract.KEY_RECORDING_ACTIVE to "true", CallIpcContract.KEY_SESSION_ID to sessionId))
            }

            override fun stopRecording(sessionId: String?): IpcResult {
                stopCalledWith = sessionId
                return IpcResult.success("Stopped $sessionId", mapOf(CallIpcContract.KEY_RECORDING_ACTIVE to "false", CallIpcContract.KEY_SESSION_ID to (sessionId ?: "")))
            }

            override fun getStatus(): IpcResult {
                return IpcResult.success("Status OK", mapOf(CallIpcContract.KEY_RECORDING_ACTIVE to isRecording.toString()))
            }
        }

        val extension = RealHeavyCallHostHostExtensionWithEngine(mockEngine)

        // 1. Request OP_START
        val startCmd = CallIpcContract.createRequestRecorderCommand(CallIpcContract.OP_START, "session_abc")
        val startRes = extension.onRequestCallRecorder(startCmd)
        assertTrue(startRes.success)
        assertEquals("session_abc", mockEngine.startCalledWith)

        // 2. Request OP_STATUS
        val statusCmd = CallIpcContract.createRequestRecorderCommand(CallIpcContract.OP_STATUS, "session_abc")
        val statusRes = extension.onRequestCallRecorder(statusCmd)
        assertTrue(statusRes.success)

        // 3. Request OP_STOP
        val stopCmd = CallIpcContract.createRequestRecorderCommand(CallIpcContract.OP_STOP, "session_abc")
        val stopRes = extension.onRequestCallRecorder(stopCmd)
        assertTrue(stopRes.success)
        assertEquals("session_abc", mockEngine.stopCalledWith)
    }

    @Test
    fun testNullContextHandling() {
        val engine = HeavyCallRecorderEngine(context = null, hostProvider = { mockHost })
        val result = engine.startRecording("session_xyz")
        // When context is null, it returns HEAVY_UNAVAILABLE error without crash or fake success
        assertFalse(result.success)
        assertEquals(IpcErrorCode.HEAVY_UNAVAILABLE, result.errorCode)
        assertFalse(engine.isRecording)
    }

    private class RealHeavyCallHostHostExtensionWithEngine(
        engine: HeavyCallRecorderEngine
    ) : RealHeavyCallHostExtension(context = null, recorderEngine = engine)
}
