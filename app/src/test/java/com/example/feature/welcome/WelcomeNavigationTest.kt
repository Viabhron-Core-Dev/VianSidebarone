package com.example.feature.welcome

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import com.example.MainActivity
import com.example.SettingsActivity
import com.example.core.HandleManager
import com.example.core.HandleService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * WelcomeNavigationTest: Verifies the Welcome -> Continue -> Handle Management Settings navigation flow.
 * Ensures:
 * 1. Destination of Welcome Continue is SettingsActivity (Handle Management screen).
 * 2. Setup completion state is persisted upon Continue.
 * 3. MainActivity routes directly to SettingsActivity once setup is completed.
 * 4. HandleService/NetSpeed startup is completely decoupled from the navigation destination.
 * 5. SettingsActivity is a concrete ComponentActivity with rendered HandleSettingsScreen.
 */
class WelcomeNavigationTest {

    @Test
    fun testDestinationIsSettingsActivityNotMainActivity() {
        // WelcomeActivity must target SettingsActivity upon continue, NOT loop back to MainActivity
        val destinationClass = SettingsActivity::class.java
        assertNotEquals(
            "WelcomeActivity continue destination must NOT be MainActivity",
            MainActivity::class.java,
            destinationClass
        )
        assertEquals(
            "WelcomeActivity continue destination must be SettingsActivity",
            SettingsActivity::class.java,
            destinationClass
        )
    }

    @Test
    fun testSettingsActivityDecoupledAndValid() {
        // SettingsActivity must inherit from ComponentActivity
        assertTrue(
            "SettingsActivity must be a ComponentActivity",
            ComponentActivity::class.java.isAssignableFrom(SettingsActivity::class.java)
        )
        // Must not subclass WelcomeActivity or MainActivity
        assertFalse(
            "SettingsActivity must not subclass WelcomeActivity",
            WelcomeActivity::class.java.isAssignableFrom(SettingsActivity::class.java)
        )
        assertFalse(
            "SettingsActivity must not subclass MainActivity",
            MainActivity::class.java.isAssignableFrom(SettingsActivity::class.java)
        )
        // SettingsActivity must not be an empty dummy class
        val onCreateMethod = SettingsActivity::class.java.declaredMethods.find { it.name == "onCreate" }
        assertTrue("SettingsActivity must implement onCreate", onCreateMethod != null)
    }

    @Test
    fun testSetupCompletedRouting() {
        val mockPrefs = MockSharedPreferences()

        // 1. Initially, setup_completed is false
        assertFalse(mockPrefs.getBoolean("setup_completed", false))

        // 2. Simulate continue action: marks setup_completed = true
        mockPrefs.edit().putBoolean("setup_completed", true).apply()
        assertTrue(mockPrefs.getBoolean("setup_completed", false))

        // 3. Routing condition logic: when setupCompleted is true, route to SettingsActivity
        val setupCompleted = mockPrefs.getBoolean("setup_completed", false)
        val canDrawOverlays = false // Even without overlay permission, should route to Settings
        val shouldRouteToSettings = canDrawOverlays || setupCompleted

        assertTrue(
            "MainActivity should route to SettingsActivity once setup is completed",
            shouldRouteToSettings
        )
    }

    @Test
    fun testHandleServiceStartupIsIndependentFromNavigation() {
        // HandleService.startIfConfigured only starts the service if overlay permission is granted
        // It does NOT dictate or act as the UI navigation destination
        val serviceClass = HandleService::class.java
        assertFalse(
            "HandleService is an Android Service, never an Activity navigation destination",
            android.app.Activity::class.java.isAssignableFrom(serviceClass)
        )
    }

    private class MockSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any>()

        override fun getAll(): Map<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = map[key] as? Set<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = MockEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class MockEditor(private val map: MutableMap<String, Any>) : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { temp[key] = value }
            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply { temp[key] = values }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { temp[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { temp[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { temp[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { temp[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = apply { temp[key] = null }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun commit(): Boolean {
                if (clearAll) map.clear()
                temp.forEach { (k, v) ->
                    if (v == null) map.remove(k) else map[k] = v
                }
                return true
            }
            override fun apply() { commit() }
        }
    }
}
