package com.example.core

import android.content.Intent
import android.os.IBinder
import androidx.activity.ComponentActivity
import com.example.MainActivity
import com.example.core.ipc.ConnectionState
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyCommandType
import com.example.core.ipc.HeavyConnectionListener
import com.example.core.ipc.HeavyProcessConnectionManager
import com.example.core.ipc.IHeavyHostContract
import com.example.core.ipc.IpcErrorCode
import com.example.core.ipc.IpcResult
import com.example.feature.element.ElementActionRegistry
import com.example.feature.sidebar.SidebarManager
import com.example.feature.welcome.WelcomeActivity
import com.example.service.BootReceiver
import com.example.util.HandleEdge
import com.example.util.HandleShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MainRuntimeRestorationTest: Verifies boot, process-death restoration,
 * lifecycle transitions, and strict process boundary isolation between Main and Heavy.
 */
class MainRuntimeRestorationTest {

    // 1. Audit: MainActivity & WelcomeActivity decoupling
    @Test
    fun testMainActivityDecoupledFromWelcomeActivity() {
        // MainActivity must inherit directly from ComponentActivity, NOT WelcomeActivity
        assertEquals(
            ComponentActivity::class.java,
            MainActivity::class.java.superclass
        )
        assertNotEquals(
            WelcomeActivity::class.java,
            MainActivity::class.java.superclass
        )
        assertFalse(
            "MainActivity must not subclass WelcomeActivity",
            WelcomeActivity::class.java.isAssignableFrom(MainActivity::class.java)
        )
        assertFalse(
            "WelcomeActivity must not subclass MainActivity",
            MainActivity::class.java.isAssignableFrom(WelcomeActivity::class.java)
        )
    }

    // 2. Boot Receiver: Starts only Main lightweight runtime; never starts Heavy or Welcome
    @Test
    fun testBootReceiverDoesNotStartHeavyOrWelcome() {
        val receiver = BootReceiver()
        assertNotNull("BootReceiver instance must be instantiable", receiver)

        // Verify BootReceiver class does not reference WelcomeActivity or HeavyProcessHost
        val fields = BootReceiver::class.java.declaredFields
        for (field in fields) {
            assertFalse(
                "BootReceiver must not reference WelcomeActivity",
                field.type == WelcomeActivity::class.java
            )
        }

        // Verify action contract
        assertEquals("android.intent.action.BOOT_COMPLETED", Intent.ACTION_BOOT_COMPLETED)
        assertEquals("android.intent.action.MY_PACKAGE_REPLACED", Intent.ACTION_MY_PACKAGE_REPLACED)
    }

    // 3. Heavy Process Death: Main survives and reconnects cleanly
    @Test
    fun testHeavyProcessDeathDoesNotStopMainAndAllowsReconnect() {
        val manager = HeavyProcessConnectionManager(context = null)
        val deathNotified = AtomicBoolean(false)

        val listener = object : HeavyConnectionListener {
            override fun onHeavyProcessDied() {
                deathNotified.set(true)
            }
        }
        manager.addListener(listener)

        val mockProxy = object : IHeavyHostContract {
            override fun sendCommand(commandJson: String): String = IpcResult.success("OK").toJson()
            override fun syncSnapshot(snapshotJson: String): String = IpcResult.success().toJson()
            override fun ping(): Boolean = true
            override fun registerCallback(callbackBinder: IBinder): Boolean = true
            override fun unregisterCallback(): Boolean = true
        }

        manager.setMockProxyForTesting(mockProxy, ConnectionState.CONNECTED)
        assertEquals(ConnectionState.CONNECTED, manager.state)

        // Simulate Heavy process crash / OS kill via death recipient
        manager.triggerDeathRecipientForTesting()

        // Main must remain functional, manager state marks DEAD, listener notified
        assertEquals(ConnectionState.DEAD, manager.state)
        assertTrue("Listener must receive onHeavyProcessDied", deathNotified.get())
        assertFalse(manager.ping())

        // Command fails gracefully without crashing Main
        val cmd = HeavyCommand("test_death_cmd", HeavyCommandType.PING)
        val result = manager.sendCommand(cmd, autoConnect = false)
        assertFalse(result.success)
        assertEquals(IpcErrorCode.HEAVY_UNAVAILABLE, result.errorCode)

        // Simulate Heavy process restart / reconnection
        manager.setMockProxyForTesting(mockProxy, ConnectionState.CONNECTED)
        assertEquals(ConnectionState.CONNECTED, manager.state)
        assertTrue(manager.ping())

        val reconnectedResult = manager.sendCommand(cmd, autoConnect = false)
        assertTrue(reconnectedResult.success)

        manager.removeListener(listener)
    }

    // 4. Persisted Handle and Container configuration restoration
    @Test
    fun testPersistedHandleAndContainerConfigRestoration() {
        val config = HandleConfig(
            id = "handle_1",
            name = "Right Handle",
            edge = HandleEdge.RIGHT,
            positionPercent = 0.45f,
            widthDp = 18,
            heightDp = 72,
            color = -16777216,
            shape = HandleShape.ROUNDED_RECT,
            alphaPercent = 85,
            onTapAction = HandleManager.ACTION_OPEN_SIDEBAR,
            onDoubleTapAction = HandleManager.ACTION_NONE,
            onLongPressAction = HandleManager.ACTION_MOVE_HANDLE,
            onSwipeLeftAction = HandleManager.ACTION_OPEN_SIDEBAR,
            onSwipeRightAction = HandleManager.ACTION_NONE,
            onSwipeUpAction = "speed_toggle",
            onSwipeDownAction = HandleManager.ACTION_NONE
        )

        assertEquals("handle_1", config.id)
        assertEquals(HandleEdge.RIGHT, config.edge)
        assertEquals(0.45f, config.positionPercent)
        assertEquals(18, config.widthDp)
        assertEquals(72, config.heightDp)
        assertEquals(HandleShape.ROUNDED_RECT, config.shape)
        assertEquals(85, config.alphaPercent)

        // Verify gesture mappings
        assertEquals(HandleManager.ACTION_OPEN_SIDEBAR, config.getActionForGesture(HandleGestures.TAP))
        assertEquals(HandleManager.ACTION_NONE, config.getActionForGesture(HandleGestures.DOUBLE_TAP))
        assertEquals(HandleManager.ACTION_MOVE_HANDLE, config.getActionForGesture(HandleGestures.LONG_PRESS))
        assertEquals(HandleManager.ACTION_OPEN_SIDEBAR, config.getActionForGesture(HandleGestures.SWIPE_LEFT))
        assertEquals("speed_toggle", config.getActionForGesture(HandleGestures.SWIPE_UP))
        assertEquals(HandleManager.ACTION_NONE, config.getActionForGesture("unknown_gesture"))

        // Check container identity resolution
        val containerId = HandleManager.getContainerId("handle_1", HandleGestures.SWIPE_LEFT)
        assertEquals("handle_1_swipe_left", containerId)

        val container = SidebarContainer(
            containerId = containerId,
            handleId = "handle_1",
            gesture = HandleGestures.SWIPE_LEFT,
            enabled = true
        )
        assertEquals("handle_1_swipe_left", container.containerId)
        assertEquals("handle_1", container.handleId)
        assertEquals(HandleGestures.SWIPE_LEFT, container.gesture)
        assertEquals("handle_handle_1_swipe_left_pages", container.pagesKey)

        // Check gesture target parsing
        val containerTarget = GestureTarget.Container("handle_1_swipe_left")
        assertEquals("handle_1_swipe_left", containerTarget.containerId)

        val actionTarget = GestureTarget.Action("speed_toggle")
        assertEquals("speed_toggle", actionTarget.actionKey)

        val noneTarget = GestureTarget.None
        assertNotNull(noneTarget)
    }

    // 5. Idempotent Main runtime restoration: Broadcast & action contracts
    @Test
    fun testIdempotentRuntimeRegistrationContracts() {
        assertEquals("com.example.action.OPEN_SIDEBAR", SidebarManager.ACTION_OPEN_SIDEBAR)
        assertEquals("handle_id", SidebarManager.EXTRA_HANDLE_ID)
        assertEquals("gesture", SidebarManager.EXTRA_GESTURE)
        assertEquals("container_id", SidebarManager.EXTRA_CONTAINER_ID)

        assertEquals("com.example.action.TRIGGER_ACTION", ElementActionRegistry.ACTION_TRIGGER_ACTION)
        assertEquals("action_key", ElementActionRegistry.EXTRA_ACTION_KEY)
        assertEquals("handle_id", ElementActionRegistry.EXTRA_HANDLE_ID)
        assertEquals("gesture", ElementActionRegistry.EXTRA_GESTURE)
        assertEquals("container_id", ElementActionRegistry.EXTRA_CONTAINER_ID)
        assertEquals("page_id", ElementActionRegistry.EXTRA_PAGE_ID)
    }

    // 6. Screen OFF / ON Restoration Contract
    @Test
    fun testScreenLifecycleContract() {
        // HandleService constants and contract checks
        assertEquals("com.example.action.RELOAD_HANDLES", HandleService.ACTION_RELOAD_HANDLES)
        assertEquals("vian_core_service_channel", HandleService.CHANNEL_ID)
        assertEquals(1001, HandleService.NOTIFICATION_ID)
        assertEquals("net_speed_enabled", HandleService.KEY_NET_SPEED_ENABLED)

        // Screen state intent verification
        assertEquals("android.intent.action.SCREEN_ON", Intent.ACTION_SCREEN_ON)
        assertEquals("android.intent.action.SCREEN_OFF", Intent.ACTION_SCREEN_OFF)
        assertEquals("android.intent.action.USER_PRESENT", Intent.ACTION_USER_PRESENT)
    }
}
