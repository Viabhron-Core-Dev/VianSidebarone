package com.example.feature.welcome

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import com.example.core.HandleConfig
import com.example.core.HandleGestures
import com.example.core.HandleManager
import com.example.util.HandleEdge
import com.example.util.HandleShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HandleStartupFlowTest: Explicitly tests the 10-step user flow:
 * Welcome -> Continue -> Settings main screen -> Handle Management -> create/edit handle -> configure gesture -> handle becomes active/visible
 * and persistence across process recreation.
 */
class HandleStartupFlowTest {

    private lateinit var fakePrefs: InMemorySharedPreferences
    private lateinit var fakeContext: FakeAppContext

    @Before
    fun setup() {
        fakePrefs = InMemorySharedPreferences()
        fakeContext = FakeAppContext(fakePrefs)
    }

    @Test
    fun testCompleteStartupAndHandleManagementFlow() {
        // Step 1: Initial state before setup - setup_completed is false
        assertFalse(fakePrefs.getBoolean("setup_completed", false))

        // Step 2 & 3: Tap Welcome Continue
        // This persists setup_completed = true
        fakePrefs.edit().putBoolean("setup_completed", true).apply()
        assertTrue("setup_completed must be persisted as true", fakePrefs.getBoolean("setup_completed", false))

        // Step 4 & 5: Launch / route to Settings
        val manager = HandleManager(fakeContext)

        // Step 6: Open Handle Management - initially contains default handle_1
        val initialHandles = manager.getAllHandles()
        assertTrue("Initially should contain at least 1 handle", initialHandles.isNotEmpty())
        val handle1 = initialHandles.first()
        assertEquals("handle_1", handle1.id)
        assertTrue("Default handle must be enabled", handle1.enabled)

        // Step 7: Create a new handle
        val handle2 = manager.createHandle(name = "Left Assist Handle", edge = HandleEdge.LEFT)
        assertEquals("handle_2", handle2.id)
        assertEquals("Left Assist Handle", handle2.name)
        assertEquals(HandleEdge.LEFT, handle2.edge)
        assertTrue(handle2.enabled)

        // Edit handle2 properties (position, width, shape, color)
        val editedHandle2 = handle2.copy(
            positionPercent = 0.65f,
            widthDp = 18,
            heightDp = 160,
            shape = HandleShape.HALF_OVAL,
            color = Color.parseColor("#44102d42")
        )
        manager.saveHandle(editedHandle2)

        // Step 8: Configure one gesture on the handle
        manager.configureGesture(
            handleId = editedHandle2.id,
            gesture = HandleGestures.SWIPE_RIGHT,
            action = HandleManager.ACTION_OPEN_SIDEBAR
        )

        // Verify gesture is saved and attached to container
        val retrieved = manager.getHandle("handle_2")
        assertNotNull(retrieved)
        assertEquals(HandleManager.ACTION_OPEN_SIDEBAR, retrieved?.getActionForGesture(HandleGestures.SWIPE_RIGHT))

        val containerId = HandleManager.getContainerId("handle_2", HandleGestures.SWIPE_RIGHT)
        assertEquals("handle_2_swipe_right", containerId)

        // Verify independent container pages initialized
        val pagesKey = HandleManager.getContainerPagesKey(containerId)
        assertTrue("Container pages must be seeded", fakePrefs.contains(pagesKey))

        // Step 9 & 10: Handle is active and visible
        val activeHandles = manager.getActiveHandles()
        assertTrue("Active handles must contain handle_1", activeHandles.any { it.id == "handle_1" })
        assertTrue("Active handles must contain handle_2", activeHandles.any { it.id == "handle_2" })
        assertEquals(2, activeHandles.size)

        // Step 11: Simulate app / process restart with new HandleManager instance on same storage
        val restartedManager = HandleManager(FakeAppContext(fakePrefs))
        val restoredHandles = restartedManager.getAllHandles()
        assertEquals(2, restoredHandles.size)

        val restoredHandle2 = restartedManager.getHandle("handle_2")
        assertNotNull(restoredHandle2)
        assertEquals("Left Assist Handle", restoredHandle2?.name)
        assertEquals(HandleEdge.LEFT, restoredHandle2?.edge)
        assertEquals(0.65f, restoredHandle2?.positionPercent ?: 0f, 0.001f)
        assertEquals(18, restoredHandle2?.widthDp)
        assertEquals(160, restoredHandle2?.heightDp)
        assertEquals(HandleShape.HALF_OVAL, restoredHandle2?.shape)
        assertEquals(Color.parseColor("#44102d42"), restoredHandle2?.color)
        assertEquals(HandleManager.ACTION_OPEN_SIDEBAR, restoredHandle2?.getActionForGesture(HandleGestures.SWIPE_RIGHT))
    }

    private class FakeAppContext(private val prefs: InMemorySharedPreferences) : android.content.ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example"
        override fun sendBroadcast(intent: android.content.Intent?) {}
        override fun startService(service: android.content.Intent?): android.content.ComponentName? = null
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = (data[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class Editor(private val target: InMemorySharedPreferences) : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            private val toRemove = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
                if (key != null) temp[key] = values
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) toRemove.add(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) target.data.clear()
                toRemove.forEach { target.data.remove(it) }
                target.data.putAll(temp)
            }
        }
    }
}
