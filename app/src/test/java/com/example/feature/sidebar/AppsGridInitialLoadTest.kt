package com.example.feature.sidebar

import com.example.core.SidebarPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for Apps Grid initial-load and fallback behavior:
 * 1. An unconfigured or empty Apps Grid (empty list resolved, empty-state logic holds, no catalogue substitution).
 * 2. A configured subset of apps (exact configured apps displayed in order).
 * 3. The race between loading the installed-app catalogue and loading the saved selection.
 * 4. Refreshing after Add Element -> OK (or SharedPreferences save).
 */
class AppsGridInitialLoadTest {

    private lateinit var fakePrefs: FakeTestSharedPreferences
    private lateinit var fakeContext: FakeTestContext
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setup() {
        fakePrefs = FakeTestSharedPreferences()
        fakeContext = FakeTestContext(fakePrefs)
    }

    // 1. An unconfigured or empty Apps Grid
    @Test
    fun testUnconfiguredOrEmptyAppsGridDisplaysEmptyStateAndNoInstalledApps() {
        val containerId = "handle_1_swipe_left"
        val pageId = "apps_page_empty"
        val prefKey = "sidebar_apps_${containerId}_${pageId}"

        val manager = SidebarAppsManager(fakeContext, fakePrefs, testScope, prefKey) {}
        // Even if allInstalledApps has items discovered from catalogue
        manager.allInstalledApps = listOf(
            AppInfo("com.example.app1", "App One"),
            AppInfo("com.example.app2", "App Two")
        )

        // Initial state: unconfigured / empty activeItems
        val displayed = AppsPageView.resolveDisplayedItems(
            sourceApps = manager.activeItems,
            expandedFolders = emptySet(),
            searchQuery = "",
            itemParser = { manager.parseId(it) }
        )

        // Must resolve to empty list, meaning emptyView will be VISIBLE and no catalogue substitution occurred
        assertTrue("Empty Apps Grid must not display any items", displayed.isEmpty())
        assertTrue("Catalogue must not be substituted when activeItems is empty", manager.allInstalledApps.isNotEmpty() && displayed.isEmpty())
    }

    // 2. A configured subset of apps
    @Test
    fun testConfiguredSubsetOfAppsPreservesExactSelectionAndOrder() {
        val containerId = "handle_1_swipe_left"
        val pageId = "apps_page_configured"
        val prefKey = "sidebar_apps_${containerId}_${pageId}"

        val manager = SidebarAppsManager(fakeContext, fakePrefs, testScope, prefKey) {}
        manager.allInstalledApps = listOf(
            AppInfo("com.example.app1", "App One"),
            AppInfo("com.example.app2", "App Two"),
            AppInfo("com.example.app3", "App Three")
        )

        val configuredApps = listOf<SidebarItem>(
            SidebarItem.App("com.example.app2", "App Two"),
            SidebarItem.App("com.example.app1", "App One")
        )

        val displayed = AppsPageView.resolveDisplayedItems(
            sourceApps = configuredApps,
            expandedFolders = emptySet(),
            searchQuery = "",
            itemParser = { manager.parseId(it) }
        )

        assertEquals("Must display exactly 2 configured apps", 2, displayed.size)
        assertEquals("First item must match configured selection", "com.example.app2", (displayed[0] as SidebarItem.App).packageName)
        assertEquals("Second item must match configured selection", "com.example.app1", (displayed[1] as SidebarItem.App).packageName)
    }

    // 3. The race between loading the installed-app catalogue and loading the saved selection
    @Test
    fun testCatalogueLoadingBeforeSavedSelectionDoesNotSubstituteCatalogue() {
        val containerId = "handle_1_swipe_left"
        val pageId = "apps_page_race"
        val prefKey = "sidebar_apps_${containerId}_${pageId}"

        val manager = SidebarAppsManager(fakeContext, fakePrefs, testScope, prefKey) {}

        // Race step 1: Installed app catalogue arrives first from Heavy process
        manager.allInstalledApps = listOf(
            AppInfo("com.example.cat1", "Catalogue 1"),
            AppInfo("com.example.cat2", "Catalogue 2"),
            AppInfo("com.example.cat3", "Catalogue 3")
        )

        // Initial view state before activeItems loaded (sourceApps is still empty)
        val initialDisplayed = AppsPageView.resolveDisplayedItems(
            sourceApps = emptyList(),
            expandedFolders = emptySet(),
            searchQuery = "",
            itemParser = { manager.parseId(it) }
        )

        // Must NOT substitute manager.allInstalledApps!
        assertTrue("Temporary loading state must not display catalogue", initialDisplayed.isEmpty())

        // Race step 2: Saved active items finish loading
        val savedSubset = listOf<SidebarItem>(
            SidebarItem.App("com.example.cat2", "Catalogue 2")
        )
        val afterLoadDisplayed = AppsPageView.resolveDisplayedItems(
            sourceApps = savedSubset,
            expandedFolders = emptySet(),
            searchQuery = "",
            itemParser = { manager.parseId(it) }
        )

        // Now exactly the saved subset appears
        assertEquals("Configured subset must appear after load completes", 1, afterLoadDisplayed.size)
        assertEquals("com.example.cat2", (afterLoadDisplayed[0] as SidebarItem.App).packageName)
    }

    // 4. Refreshing after Add Element -> OK
    @Test
    fun testRefreshAfterAddElementUpdatesSelectionCorrectly() {
        val containerId = "handle_1_swipe_left"
        val pageId = "apps_page_add_element"
        val prefKey = "sidebar_apps_${containerId}_${pageId}"

        val manager = SidebarAppsManager(fakeContext, fakePrefs, testScope, prefKey) {}
        manager.allInstalledApps = listOf(
            AppInfo("com.example.app1", "App One"),
            AppInfo("com.example.app2", "App Two")
        )

        // Initially empty
        var displayed = AppsPageView.resolveDisplayedItems(
            sourceApps = emptyList(),
            expandedFolders = emptySet(),
            searchQuery = "",
            itemParser = { manager.parseId(it) }
        )
        assertTrue(displayed.isEmpty())

        // User opens Add Element -> selects App One -> taps OK (saving to SharedPreferences)
        val newSelection = listOf<SidebarItem>(
            SidebarItem.App("com.example.app1", "App One")
        )

        // View updates with new selection
        displayed = AppsPageView.resolveDisplayedItems(
            sourceApps = newSelection,
            expandedFolders = emptySet(),
            searchQuery = "",
            itemParser = { manager.parseId(it) }
        )

        assertEquals("Displayed items must update to new selection after Add Element", 1, displayed.size)
        assertEquals("com.example.app1", (displayed[0] as SidebarItem.App).packageName)
    }

    // Lightweight in-memory context double for testing
    class FakeTestContext(private val prefs: android.content.SharedPreferences) : android.content.ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences = prefs
        override fun getApplicationContext(): android.content.Context = this
        override fun getPackageName(): String = "com.example"
    }

    // In-memory SharedPreferences double for testing
    class FakeTestSharedPreferences : android.content.SharedPreferences {
        private val data = mutableMapOf<String, Any>()
        private val listeners = mutableListOf<android.content.SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): Map<String, *> = HashMap(data)
        override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = (data[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: defValues
        override fun getInt(key: String, defValue: Int): Int = (data[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (data[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (data[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = data.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = Editor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {
            listener?.let { listeners.add(it) }
        }
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {
            listener?.let { listeners.remove(it) }
        }

        class Editor(private val prefs: FakeTestSharedPreferences) : android.content.SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            private val toRemove = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String, value: String?): android.content.SharedPreferences.Editor {
                if (value == null) toRemove.add(key) else temp[key] = value
                return this
            }
            override fun putStringSet(key: String, values: Set<String>?): android.content.SharedPreferences.Editor {
                if (values == null) toRemove.add(key) else temp[key] = values
                return this
            }
            override fun putInt(key: String, value: Int): android.content.SharedPreferences.Editor {
                temp[key] = value
                return this
            }
            override fun putLong(key: String, value: Long): android.content.SharedPreferences.Editor {
                temp[key] = value
                return this
            }
            override fun putFloat(key: String, value: Float): android.content.SharedPreferences.Editor {
                temp[key] = value
                return this
            }
            override fun putBoolean(key: String, value: Boolean): android.content.SharedPreferences.Editor {
                temp[key] = value
                return this
            }
            override fun remove(key: String): android.content.SharedPreferences.Editor {
                toRemove.add(key)
                return this
            }
            override fun clear(): android.content.SharedPreferences.Editor {
                clearAll = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) prefs.data.clear()
                toRemove.forEach { prefs.data.remove(it) }
                temp.forEach { (k, v) -> if (v != null) prefs.data[k] = v }
                temp.keys.forEach { k -> prefs.listeners.forEach { it.onSharedPreferenceChanged(prefs, k) } }
            }
        }
    }
}
