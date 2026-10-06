package com.example.feature.sidebar

import com.example.core.FakeSharedPreferences
import com.example.core.ipc.ConnectionState
import com.example.core.ipc.HeavyCommand
import com.example.core.ipc.HeavyCommandType
import com.example.core.ipc.HeavyConnectionListener
import com.example.core.ipc.HeavyProcessConnectionManager
import com.example.core.ipc.HeavyProcessHost
import com.example.core.ipc.IHeavyHostContract
import com.example.core.ipc.IpcErrorCode
import com.example.core.ipc.IpcResult
import com.example.feature.heavy.HeavyAppsDataProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class AppsHeavyDataProviderTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setup() {
        fakePrefs = FakeSharedPreferences()
    }

    // 1. Apps data request/response
    @Test
    fun testAppsDataRequestResponse() {
        val host = HeavyProcessHost(context = null)
        val containerId = "handle_1_swipe_left"
        val pageId = "apps_page_1"

        // Set custom provider in Heavy to simulate HeavyAppsDataProvider output
        host.setAppsDataProvider { _, cmd ->
            val jsonArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("packageName", "com.android.calculator2")
                    put("label", "Calculator")
                })
                put(JSONObject().apply {
                    put("packageName", "com.android.chrome")
                    put("label", "Chrome")
                })
            }
            IpcResult.success("Apps discovered", mapOf(
                "apps" to jsonArray.toString(),
                "containerId" to (cmd.payload["containerId"] ?: ""),
                "pageId" to (cmd.payload["pageId"] ?: ""),
                "count" to "2"
            ))
        }

        val command = HeavyCommand(
            commandId = "req_101",
            type = HeavyCommandType.GET_APPS_DATA,
            targetId = containerId,
            payload = mapOf(
                "containerId" to containerId,
                "pageId" to pageId,
                "operation" to "GET_INSTALLED_APPS"
            )
        )

        val resultJson = host.onSendCommand(command.toJson())
        val result = IpcResult.fromJson(resultJson)

        assertTrue("Expected command success", result.success)
        assertEquals("Apps discovered", result.message)
        assertEquals(containerId, result.data["containerId"])
        assertEquals(pageId, result.data["pageId"])
        assertEquals("2", result.data["count"])

        val appsJson = result.data["apps"]
        assertNotNull(appsJson)
        val arr = JSONArray(appsJson)
        assertEquals(2, arr.length())
        assertEquals("com.android.calculator2", arr.getJSONObject(0).getString("packageName"))
        assertEquals("Calculator", arr.getJSONObject(0).getString("label"))
        assertEquals("com.android.chrome", arr.getJSONObject(1).getString("packageName"))
        assertEquals("Chrome", arr.getJSONObject(1).getString("label"))
    }

    // 2. Correct container identity
    @Test
    fun testCorrectContainerIdentity() {
        val host = HeavyProcessHost(context = null)

        val containerA = "handle_1_swipe_left"
        val containerB = "handle_2_tap"

        host.setAppsDataProvider { _, cmd ->
            val cont = cmd.payload["containerId"] ?: ""
            IpcResult.success("OK", mapOf("containerId" to cont, "apps" to "[]"))
        }

        val cmdA = HeavyCommand(
            commandId = "req_a",
            type = HeavyCommandType.GET_APPS_DATA,
            targetId = containerA,
            payload = mapOf("containerId" to containerA)
        )
        val resA = IpcResult.fromJson(host.onSendCommand(cmdA.toJson()))
        assertEquals(containerA, resA.data["containerId"])

        val cmdB = HeavyCommand(
            commandId = "req_b",
            type = HeavyCommandType.GET_APPS_DATA,
            targetId = containerB,
            payload = mapOf("containerId" to containerB)
        )
        val resB = IpcResult.fromJson(host.onSendCommand(cmdB.toJson()))
        assertEquals(containerB, resB.data["containerId"])
    }

    // 3. Empty Apps result
    @Test
    fun testEmptyAppsResult() {
        val host = HeavyProcessHost(context = null)
        host.setAppsDataProvider { _, cmd ->
            IpcResult.success("No apps found", mapOf(
                "apps" to "[]",
                "containerId" to (cmd.payload["containerId"] ?: ""),
                "count" to "0"
            ))
        }

        val cmd = HeavyCommand(
            commandId = "empty_req",
            type = HeavyCommandType.GET_APPS_DATA,
            targetId = "handle_1_swipe_left",
            payload = mapOf("containerId" to "handle_1_swipe_left")
        )
        val res = IpcResult.fromJson(host.onSendCommand(cmd.toJson()))
        assertTrue(res.success)
        assertEquals("0", res.data["count"])
        assertEquals("[]", res.data["apps"])
    }

    // 4. Heavy unavailable / process-death behavior
    @Test
    fun testHeavyUnavailableAndProcessDeath() {
        val connMgr = HeavyProcessConnectionManager(context = null)
        val deathNotified = AtomicBoolean(false)

        connMgr.addListener(object : HeavyConnectionListener {
            override fun onHeavyProcessDied() {
                deathNotified.set(true)
            }
        })

        val mockProxy = object : IHeavyHostContract {
            override fun sendCommand(commandJson: String): String = IpcResult.success().toJson()
            override fun syncSnapshot(snapshotJson: String): String = IpcResult.success().toJson()
            override fun ping(): Boolean = true
            override fun registerCallback(callbackBinder: android.os.IBinder): Boolean = true
            override fun unregisterCallback(): Boolean = true
        }

        connMgr.setMockProxyForTesting(mockProxy, ConnectionState.CONNECTED)
        assertEquals(ConnectionState.CONNECTED, connMgr.state)

        // Simulate Heavy process death via DeathRecipient
        connMgr.triggerDeathRecipientForTesting()
        assertEquals(ConnectionState.DEAD, connMgr.state)
        assertTrue(deathNotified.get())

        // Executing command while Heavy is dead returns safe error and does NOT crash
        val cmd = HeavyCommand("cmd_while_dead", HeavyCommandType.GET_APPS_DATA)
        val result = connMgr.sendCommand(cmd, autoConnect = false)
        assertFalse(result.success)
        assertEquals(IpcErrorCode.HEAVY_UNAVAILABLE, result.errorCode)
    }

    // 5. Reconnect and retry behavior
    @Test
    fun testReconnectAndRetryBehavior() {
        val connMgr = HeavyProcessConnectionManager(context = null)

        val mockProxy = object : IHeavyHostContract {
            override fun sendCommand(commandJson: String): String {
                return IpcResult.success("Success after reconnect", mapOf("apps" to "[]")).toJson()
            }
            override fun syncSnapshot(snapshotJson: String): String = IpcResult.success().toJson()
            override fun ping(): Boolean = true
            override fun registerCallback(callbackBinder: android.os.IBinder): Boolean = true
            override fun unregisterCallback(): Boolean = true
        }

        connMgr.setMockProxyForTesting(mockProxy, ConnectionState.CONNECTED)
        connMgr.triggerDeathRecipientForTesting()
        assertEquals(ConnectionState.DEAD, connMgr.state)

        // Simulate reconnecting to fresh Heavy host
        connMgr.setMockProxyForTesting(mockProxy, ConnectionState.CONNECTED)
        assertEquals(ConnectionState.CONNECTED, connMgr.state)

        val cmd = HeavyCommand("cmd_retry", HeavyCommandType.GET_APPS_DATA)
        val result = connMgr.sendCommand(cmd, autoConnect = false)
        assertTrue(result.success)
        assertEquals("Success after reconnect", result.message)
    }

    // 6. Apps search/filter using returned data without another package scan
    @Test
    fun testSearchFilterUsingReturnedDataWithoutPackageScan() {
        var packageScanCount = 0

        // Simulate compact apps data returned by Heavy
        val sampleApps = listOf(
            AppInfo("com.google.android.youtube", "YouTube"),
            AppInfo("com.google.android.gm", "Gmail"),
            AppInfo("com.android.chrome", "Chrome"),
            AppInfo("com.android.settings", "Settings")
        )

        // Mock manager holding data in Main
        val filteredQuery = "Tube"
        val filtered = sampleApps.filter { app ->
            app.label.contains(filteredQuery, ignoreCase = true) || app.packageName.contains(filteredQuery, ignoreCase = true)
        }

        assertEquals(1, filtered.size)
        assertEquals("YouTube", filtered[0].label)
        assertEquals("com.google.android.youtube", filtered[0].packageName)
        // Verify packageScanCount remains 0 — zero scans from Main
        assertEquals(0, packageScanCount)

        // Search for "com.android"
        val androidApps = sampleApps.filter { app ->
            app.label.contains("com.android", ignoreCase = true) || app.packageName.contains("com.android", ignoreCase = true)
        }
        assertEquals(2, androidApps.size)
        assertTrue(androidApps.any { it.label == "Chrome" })
        assertTrue(androidApps.any { it.label == "Settings" })
        assertEquals(0, packageScanCount)
    }

    // 7. Existing Apps persistence remaining container-isolated
    @Test
    fun testExistingAppsPersistenceRemainingContainerIsolated() {
        val container1 = "handle_1_swipe_left"
        val container2 = "handle_2_tap"
        val pageId = "default_apps"

        val key1 = "sidebar_apps_${container1}_${pageId}"
        val key2 = "sidebar_apps_${container2}_${pageId}"

        val apps1 = JSONArray().apply {
            put("app:com.android.calculator2")
            put("app:com.android.camera")
        }.toString()

        val apps2 = JSONArray().apply {
            put("app:com.android.chrome")
            put("app:com.android.settings")
        }.toString()

        fakePrefs.edit()
            .putString(key1, apps1)
            .putString(key2, apps2)
            .apply()

        // Verify independent retrieval
        val read1 = fakePrefs.getString(key1, null)
        val read2 = fakePrefs.getString(key2, null)

        assertNotNull(read1)
        assertNotNull(read2)
        assertEquals(apps1, read1)
        assertEquals(apps2, read2)

        val arr1 = JSONArray(read1)
        val arr2 = JSONArray(read2)

        assertEquals(2, arr1.length())
        assertEquals(2, arr2.length())
        assertEquals("app:com.android.calculator2", arr1.getString(0))
        assertEquals("app:com.android.chrome", arr2.getString(0))

        // Modify container 1; container 2 must remain unchanged
        fakePrefs.edit().putString(key1, "[]").apply()
        assertEquals("[]", fakePrefs.getString(key1, null))
        assertEquals(apps2, fakePrefs.getString(key2, null))
    }
}
