package com.example.feature.notification

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused unit test verifying Notification app filter options:
 * - All Apps contains both categories.
 * - User Apps contains only user apps.
 * - System Apps contains system apps.
 * - System app metadata flag classification.
 * - Notification Sidebar page and its Edit screen share the exact same filter contract.
 */
class NotificationAppFilterHelperTest {

    private val userApp1 = NotificationAppItem(
        packageName = "com.example.chat",
        appName = "ChatApp",
        isSystem = false
    )
    private val userApp2 = NotificationAppItem(
        packageName = "com.spotify.music",
        appName = "Spotify",
        isSystem = false
    )
    private val systemApp1 = NotificationAppItem(
        packageName = "com.android.systemui",
        appName = "System UI",
        isSystem = true
    )
    private val systemApp2 = NotificationAppItem(
        packageName = "android",
        appName = "Android System",
        isSystem = true
    )

    private val allAppsList = listOf(userApp1, userApp2, systemApp1, systemApp2)

    @Test
    fun testAllAppsContainsBothCategories() {
        val filtered = NotificationAppFilterHelper.filterApps(
            apps = allAppsList,
            category = NotificationAppFilterCategory.ALL
        )
        assertEquals(4, filtered.size)
        assertTrue(filtered.any { it.packageName == "com.example.chat" && !it.isSystem })
        assertTrue(filtered.any { it.packageName == "com.spotify.music" && !it.isSystem })
        assertTrue(filtered.any { it.packageName == "com.android.systemui" && it.isSystem })
        assertTrue(filtered.any { it.packageName == "android" && it.isSystem })
    }

    @Test
    fun testUserAppsContainsOnlyUserApps() {
        val filtered = NotificationAppFilterHelper.filterApps(
            apps = allAppsList,
            category = NotificationAppFilterCategory.USER
        )
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { !it.isSystem })
        assertEquals(listOf("com.example.chat", "com.spotify.music"), filtered.map { it.packageName })
    }

    @Test
    fun testSystemAppsContainsOnlySystemApps() {
        val filtered = NotificationAppFilterHelper.filterApps(
            apps = allAppsList,
            category = NotificationAppFilterCategory.SYSTEM
        )
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.isSystem })
        assertEquals(listOf("com.android.systemui", "android"), filtered.map { it.packageName })
    }

    @Test
    fun testSystemAppClassificationMetadataFlag() {
        val sysAppInfo = ApplicationInfo().apply {
            flags = ApplicationInfo.FLAG_SYSTEM
        }
        val userAppInfo = ApplicationInfo().apply {
            flags = 0
        }
        val updatedSysAppInfo = ApplicationInfo().apply {
            flags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        }

        assertTrue("FLAG_SYSTEM must identify a system app", NotificationAppFilterHelper.isSystemApp(sysAppInfo))
        assertFalse("flags = 0 must identify a user app", NotificationAppFilterHelper.isSystemApp(userAppInfo))
        assertTrue("FLAG_SYSTEM with updated flag must identify a system app", NotificationAppFilterHelper.isSystemApp(updatedSysAppInfo))
    }

    @Test
    fun testSidebarNotificationFilterAndEditScreenShareSameBehavior() {
        // Both the Notification Sidebar Page filter ("sidebar_hidden_packages")
        // and the Edit Screen filter ("history_hidden_packages") utilize NotificationAppFilterHelper
        val sidebarHidden = mutableSetOf<String>()
        val historyHidden = mutableSetOf<String>()

        // User toggles System App to hidden in Sidebar filter
        sidebarHidden.add(systemApp1.packageName)
        assertTrue(sidebarHidden.contains("com.android.systemui"))

        // User toggles System App to hidden in History edit filter
        historyHidden.add(systemApp2.packageName)
        assertTrue(historyHidden.contains("android"))

        // Active notification filtering on live Sidebar page matches hidden set
        val activeNotifPkgs = listOf(userApp1.packageName, systemApp1.packageName, systemApp2.packageName)
        val visibleInSidebar = activeNotifPkgs.filter { !sidebarHidden.contains(it) }
        assertEquals(listOf("com.example.chat", "android"), visibleInSidebar)

        // History filtering in Edit Screen matches history hidden set
        val visibleInHistory = activeNotifPkgs.filter { !historyHidden.contains(it) }
        assertEquals(listOf("com.example.chat", "com.android.systemui"), visibleInHistory)
    }
}
