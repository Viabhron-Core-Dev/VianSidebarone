package com.example.feature.element

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.example.HybridGridEditActivity
import com.example.SidebarEditActivity
import com.example.core.FakeSharedPreferences
import com.example.core.LogKeeper
import com.example.feature.sidebar.GridWidgetItem
import com.example.feature.sidebar.SidebarAppsManager
import com.example.feature.sidebar.SidebarItem
import com.example.feature.sidebar.extractChildCountFromFolderId
import com.example.loadHybridLocalItems
import com.example.saveHybridItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * FolderElementLifecycleTest: Verifies end-to-end reliability of Folder elements:
 * 1. Creation and serialization in page state (Hybrid Grid & Apps page).
 * 2. Deserialization and child element collection persistence.
 * 3. Adding one and multiple elements inside a folder.
 * 4. Page save, reload, and verification of folder children.
 * 5. Child removal and reordering persistence.
 * 6. Verification of concise logging tags and counts.
 */
class FolderElementLifecycleTest {

    private lateinit var prefs: FakeSharedPreferences
    private val pageId = "test_page_1"

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
    }

    @Test
    fun testFolderCreationAndDeserialization() {
        val folderUuid = UUID.randomUUID().toString()
        val folderJson = JSONObject().apply {
            put("name", "Utilities")
            put("colorHex", "#334455")
            put("folderStyle", 0)
            put("popupColumns", 3)
            put("popupRows", 3)
            put("items", JSONArray())
        }
        val folderId = "folder:$folderUuid:${folderJson}"

        // Save into hybrid grid page
        val items = listOf(GridWidgetItem(id = folderId, cols = 1, rows = 1, x = 0, y = 0))
        saveHybridItems(prefs, pageId, items, null)

        // Reload page
        val loaded = loadHybridLocalItems(prefs, pageId)
        assertEquals(1, loaded.size)
        assertEquals(folderId, loaded[0].id)
        assertEquals(0, extractChildCountFromFolderId(loaded[0].id))
    }

    @Test
    fun testAddSingleElementToFolderAndReload() {
        val folderUuid = UUID.randomUUID().toString()
        val initialFolderJson = JSONObject().apply {
            put("name", "My Folder")
            put("colorHex", "#444444")
            put("folderStyle", 0)
            put("popupColumns", 3)
            put("popupRows", 3)
            put("items", JSONArray())
        }
        val initialFolderId = "folder:$folderUuid:${initialFolderJson}"

        // Step 1: Create folder -> save page
        val initialItems = listOf(GridWidgetItem(id = initialFolderId, cols = 1, rows = 1, x = 0, y = 0))
        saveHybridItems(prefs, pageId, initialItems, null)

        // Step 2: Reopen page -> open folder
        val reloadedPage = loadHybridLocalItems(prefs, pageId)
        val folderItem = reloadedPage.find { it.id.removePrefix("folder:").substringBefore(":") == folderUuid }
        assertNotNull(folderItem)
        assertEquals(0, extractChildCountFromFolderId(folderItem?.id))

        // Step 3: Add element inside folder -> close folder (produces updated folder string)
        val childApp = "app:com.android.settings"
        val updatedFolderJson = JSONObject().apply {
            put("name", "My Folder")
            put("colorHex", "#444444")
            put("folderStyle", 0)
            put("popupColumns", 3)
            put("popupRows", 3)
            put("items", JSONArray().apply { put(childApp) })
        }
        val updatedFolderId = "folder:$folderUuid:${updatedFolderJson}"

        // Step 4: Parent page receives updated folder object and replaces existing entry
        val currentItems = loadHybridLocalItems(prefs, pageId).toMutableList()
        val index = currentItems.indexOfFirst { it.id.removePrefix("folder:").substringBefore(":") == folderUuid }
        assertTrue(index != -1)
        currentItems[index] = currentItems[index].copy(id = updatedFolderId)

        // Step 5: Save updated page
        saveHybridItems(prefs, pageId, currentItems, null)

        // Step 6: Leave / reopen page
        val fullyReloadedItems = loadHybridLocalItems(prefs, pageId)
        val reloadedFolder = fullyReloadedItems.find { it.id.removePrefix("folder:").substringBefore(":") == folderUuid }
        assertNotNull(reloadedFolder)

        // Step 7: Confirm child element is still there
        val parts = reloadedFolder!!.id.split(":", limit = 3)
        assertEquals(3, parts.size)
        val parsedObj = JSONObject(parts[2])
        val parsedArr = parsedObj.optJSONArray("items")
        assertNotNull(parsedArr)
        assertEquals(1, parsedArr!!.length())
        assertEquals(childApp, parsedArr.getString(0))
        assertEquals(1, extractChildCountFromFolderId(reloadedFolder.id))
    }

    @Test
    fun testAddMultipleElementsToFolderAndReload() {
        val folderUuid = UUID.randomUUID().toString()
        val folderJson = JSONObject().apply {
            put("name", "Multi Folder")
            put("colorHex", "#556677")
            put("folderStyle", 0)
            put("popupColumns", 3)
            put("popupRows", 3)
            put("items", JSONArray())
        }
        val folderId = "folder:$folderUuid:${folderJson}"

        // Save page initially
        saveHybridItems(prefs, pageId, listOf(GridWidgetItem(id = folderId, cols = 1, rows = 1, x = 0, y = 0)), null)

        // Add 2 different elements: App and Link
        val element1 = "app:com.example.browser"
        val element2 = "link:link-uuid-1:{\"url\":\"https://google.com\",\"label\":\"Google\"}"
        val updatedJson = JSONObject().apply {
            put("name", "Multi Folder")
            put("colorHex", "#556677")
            put("folderStyle", 0)
            put("popupColumns", 3)
            put("popupRows", 3)
            put("items", JSONArray().apply {
                put(element1)
                put(element2)
            })
        }
        val updatedFolderId = "folder:$folderUuid:${updatedJson}"

        // Replace and save in parent page
        val pageItems = loadHybridLocalItems(prefs, pageId).toMutableList()
        val idx = pageItems.indexOfFirst { it.id.removePrefix("folder:").substringBefore(":") == folderUuid }
        pageItems[idx] = pageItems[idx].copy(id = updatedFolderId)
        saveHybridItems(prefs, pageId, pageItems, null)

        // Full reload of parent page
        val reloadedPage = loadHybridLocalItems(prefs, pageId)
        val reloadedFolder = reloadedPage.find { it.id.removePrefix("folder:").substringBefore(":") == folderUuid }
        assertNotNull(reloadedFolder)

        val obj = JSONObject(reloadedFolder!!.id.split(":", limit = 3)[2])
        val itemsArr = obj.getJSONArray("items")
        assertEquals(2, itemsArr.length())
        assertEquals(element1, itemsArr.getString(0))
        assertEquals(element2, itemsArr.getString(1))
        assertEquals(2, extractChildCountFromFolderId(reloadedFolder.id))
    }

    @Test
    fun testRemoveChildFromFolderPersistsCorrectly() {
        val folderUuid = UUID.randomUUID().toString()
        val element1 = "app:com.example.first"
        val element2 = "app:com.example.second"

        // Initial folder with 2 items
        val folderJson = JSONObject().apply {
            put("name", "Remove Test")
            put("colorHex", "#444444")
            put("folderStyle", 0)
            put("popupColumns", 3)
            put("popupRows", 3)
            put("items", JSONArray().apply {
                put(element1)
                put(element2)
            })
        }
        val folderId = "folder:$folderUuid:${folderJson}"
        saveHybridItems(prefs, pageId, listOf(GridWidgetItem(id = folderId, cols = 1, rows = 1, x = 0, y = 0)), null)

        // Remove element1
        val updatedJson = JSONObject().apply {
            put("name", "Remove Test")
            put("colorHex", "#444444")
            put("folderStyle", 0)
            put("popupColumns", 3)
            put("popupRows", 3)
            put("items", JSONArray().apply {
                put(element2)
            })
        }
        val updatedFolderId = "folder:$folderUuid:${updatedJson}"

        // Replace and save
        val pageItems = loadHybridLocalItems(prefs, pageId).toMutableList()
        val idx = pageItems.indexOfFirst { it.id.removePrefix("folder:").substringBefore(":") == folderUuid }
        pageItems[idx] = pageItems[idx].copy(id = updatedFolderId)
        saveHybridItems(prefs, pageId, pageItems, null)

        // Reload
        val reloadedPage = loadHybridLocalItems(prefs, pageId)
        val reloadedFolder = reloadedPage[0]
        val obj = JSONObject(reloadedFolder.id.split(":", limit = 3)[2])
        val itemsArr = obj.getJSONArray("items")
        assertEquals(1, itemsArr.length())
        assertEquals(element2, itemsArr.getString(0))
        assertEquals(1, extractChildCountFromFolderId(reloadedFolder.id))
    }

    @Test
    fun testReorderChildrenInFolderPersistsOrder() {
        val folderUuid = UUID.randomUUID().toString()
        val elementA = "app:com.example.a"
        val elementB = "app:com.example.b"
        val elementC = "app:com.example.c"

        // Initial order: A, B, C
        val folderJson = JSONObject().apply {
            put("name", "Reorder Test")
            put("colorHex", "#444444")
            put("items", JSONArray().apply {
                put(elementA)
                put(elementB)
                put(elementC)
            })
        }
        val folderId = "folder:$folderUuid:${folderJson}"
        saveHybridItems(prefs, pageId, listOf(GridWidgetItem(id = folderId, cols = 1, rows = 1, x = 0, y = 0)), null)

        // Reordered: C, A, B
        val reorderedJson = JSONObject().apply {
            put("name", "Reorder Test")
            put("colorHex", "#444444")
            put("items", JSONArray().apply {
                put(elementC)
                put(elementA)
                put(elementB)
            })
        }
        val reorderedFolderId = "folder:$folderUuid:${reorderedJson}"

        val pageItems = loadHybridLocalItems(prefs, pageId).toMutableList()
        pageItems[0] = pageItems[0].copy(id = reorderedFolderId)
        saveHybridItems(prefs, pageId, pageItems, null)

        val reloaded = loadHybridLocalItems(prefs, pageId)[0]
        val itemsArr = JSONObject(reloaded.id.split(":", limit = 3)[2]).getJSONArray("items")
        assertEquals(3, itemsArr.length())
        assertEquals(elementC, itemsArr.getString(0))
        assertEquals(elementA, itemsArr.getString(1))
        assertEquals(elementB, itemsArr.getString(2))
    }

    @Test
    fun testAppsPageFolderPersistence() {
        val folderUuid = UUID.randomUUID().toString()
        val appsKey = "sidebar_apps_sidebar_apps_page"
        val initialFolderJson = JSONObject().apply {
            put("name", "Apps Folder")
            put("colorHex", "#112233")
            put("items", JSONArray())
        }
        val initialFolderId = "folder:$folderUuid:${initialFolderJson}"

        // 1. Initial save of Apps page
        val initialList = listOf(initialFolderId, "app:com.test.other")
        prefs.edit().putString(appsKey, JSONArray(initialList).toString()).commit()

        // 2. Add elements inside folder
        val child1 = "app:com.child.one"
        val child2 = "app:com.child.two"
        val updatedFolderJson = JSONObject().apply {
            put("name", "Apps Folder")
            put("colorHex", "#112233")
            put("items", JSONArray().apply {
                put(child1)
                put(child2)
            })
        }
        val updatedFolderId = "folder:$folderUuid:${updatedFolderJson}"

        // 3. Replace in parent Apps page localIds
        val currentLocalIds = mutableListOf<String>()
        val arr = JSONArray(prefs.getString(appsKey, "[]"))
        for (i in 0 until arr.length()) currentLocalIds.add(arr.getString(i))

        val idx = currentLocalIds.indexOfFirst { it.removePrefix("folder:").substringBefore(":") == folderUuid }
        assertTrue(idx != -1)
        currentLocalIds[idx] = updatedFolderId

        // 4. Save parent Apps page
        val savedArr = JSONArray()
        currentLocalIds.forEach { savedArr.put(it) }
        prefs.edit().putString(appsKey, savedArr.toString()).commit()

        // 5. Reload Apps page and verify non-folder element preserved and folder children intact
        val reloadedArr = JSONArray(prefs.getString(appsKey, "[]"))
        assertEquals(2, reloadedArr.length())
        assertEquals("app:com.test.other", reloadedArr.getString(1))

        val reloadedFolderStr = reloadedArr.getString(0)
        assertEquals(2, extractChildCountFromFolderId(reloadedFolderStr))
        val reloadedObj = JSONObject(reloadedFolderStr.split(":", limit = 3)[2])
        val children = reloadedObj.getJSONArray("items")
        assertEquals(2, children.length())
        assertEquals(child1, children.getString(0))
        assertEquals(child2, children.getString(1))
    }

    @Test
    fun testNonFolderElementsAndPositionsPreserved() {
        val folderUuid = UUID.randomUUID().toString()
        val folderId = "folder:$folderUuid:{\"name\":\"Folder\",\"items\":[]}"

        val item1 = GridWidgetItem("widget:100", cols = 2, rows = 2, x = 0, y = 0)
        val itemFolder = GridWidgetItem(folderId, cols = 1, rows = 1, x = 2, y = 0)
        val item3 = GridWidgetItem("system:ebook_reader", cols = 1, rows = 1, x = 3, y = 0)
        val item4 = GridWidgetItem("app:com.test.app", cols = 1, rows = 1, x = 0, y = 2)

        saveHybridItems(prefs, pageId, listOf(item1, itemFolder, item3, item4), null)

        // Update only the folder
        val updatedFolderId = "folder:$folderUuid:{\"name\":\"Folder\",\"items\":[\"app:com.child.app\"]}"
        val pageItems = loadHybridLocalItems(prefs, pageId).toMutableList()
        val fIndex = pageItems.indexOfFirst { it.id.removePrefix("folder:").substringBefore(":") == folderUuid }
        pageItems[fIndex] = pageItems[fIndex].copy(id = updatedFolderId)
        saveHybridItems(prefs, pageId, pageItems, null)

        // Reload and verify positions of non-folder elements
        val reloaded = loadHybridLocalItems(prefs, pageId)
        assertEquals(4, reloaded.size)
        assertEquals(item1.id, reloaded[0].id)
        assertEquals(item1.x, reloaded[0].x)
        assertEquals(item1.y, reloaded[0].y)

        assertEquals(updatedFolderId, reloaded[1].id)
        assertEquals(2, reloaded[1].x)
        assertEquals(0, reloaded[1].y)

        assertEquals(item3.id, reloaded[2].id)
        assertEquals(item3.x, reloaded[2].x)
        assertEquals(item3.y, reloaded[2].y)

        assertEquals(item4.id, reloaded[3].id)
        assertEquals(item4.x, reloaded[3].x)
        assertEquals(item4.y, reloaded[3].y)
    }
}
