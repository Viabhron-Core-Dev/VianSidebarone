package com.example.feature.element

import android.content.Context
import android.graphics.Color
import com.example.core.FaviconFetcher
import com.example.core.LogKeeper
import com.example.feature.sidebar.ALL_DISPLAY_ACTIONS
import com.example.feature.sidebar.ALL_FLOATING_WINDOWS
import com.example.feature.sidebar.ALL_MEDIA_ACTIONS
import com.example.feature.sidebar.ALL_QUICK_TILES
import com.example.feature.sidebar.ALL_SCREEN_CAPTURE_ACTIONS
import com.example.feature.sidebar.ALL_SETTINGS_SHORTCUTS
import com.example.feature.sidebar.ALL_SYSTEM_ACTIONS
import com.example.feature.sidebar.ALL_UTILITIES_ACTIONS
import com.example.feature.sidebar.ALL_VOLUME_ACTIONS
import com.example.feature.sidebar.ElementMetadataStore
import com.example.feature.sidebar.SidebarItem
import org.json.JSONObject
import java.net.URLDecoder

/**
 * ElementIdParser: Lightweight, stateless parser for element and action IDs.
 *
 * Replaces heavy SidebarAppsManager instantiations previously used only for ID parsing.
 * Preserves the canonical ID formats and returns strongly-typed [SidebarItem] models.
 */
object ElementIdParser {

    private const val TAG = "ElementIdParser"

    fun parse(context: Context, id: String): SidebarItem? {
        if (id.isEmpty()) return null

        try {
            return when {
                id.startsWith("app:") -> parseApp(context, id)
                id.startsWith("intent:") -> parseIntent(id)
                id.startsWith("floating_trigger:") -> parseFloatingTrigger(context, id)
                id.startsWith("quicktile:") -> parseQuickTile(id)
                id.startsWith("system:") -> parseSystemAction(id)
                id.startsWith("page_window:") -> parsePageWindow(id)
                id.startsWith("volume:") -> parseVolumeAction(id)
                id.startsWith("media:") -> parseMediaAction(id)
                id.startsWith("display:") -> parseDisplayAction(id)
                id.startsWith("settings_shortcut:") -> parseSettingsShortcut(id)
                id.startsWith("widget:") -> parseWidget(id)
                id.startsWith("folder:") -> parseFolder(id)
                id.startsWith("link:") -> parseLink(context, id)
                id.startsWith("spacer:") -> parseSpacer(id)
                else -> null
            }
        } catch (e: Throwable) {
            LogKeeper.logError(context, TAG, "Error parsing element ID '$id'", e)
            return null
        }
    }

    private fun parseApp(context: Context, id: String): SidebarItem.App {
        val pkg = id.substringAfter("app:").substringBefore(":")
        val meta = ElementMetadataStore.get(context, id)
        val launchable = ElementMetadataStore.isPackageLaunchable(context, pkg)
        if (meta != null) {
            return SidebarItem.App(
                packageName = meta.target,
                label = meta.label,
                iconPath = meta.iconPath,
                isLaunchable = launchable,
                id = id
            )
        }
        var label = pkg
        try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            label = pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {}
        val savedMeta = ElementMetadataStore.saveAppElement(context, pkg, label, id)
        return SidebarItem.App(
            packageName = pkg,
            label = label,
            iconPath = savedMeta.iconPath,
            isLaunchable = launchable,
            id = id
        )
    }

    private fun parseIntent(id: String): SidebarItem.IntentAction {
        val parts = id.split(":", limit = 4)
        return if (parts.size >= 3) {
            val encodedLabel = parts[1]
            val encodedUri = parts[2]
            val iconPath = if (parts.size >= 4) parts[3] else null
            val label = try { URLDecoder.decode(encodedLabel, "UTF-8") } catch (_: Exception) { encodedLabel }
            val uri = try { URLDecoder.decode(encodedUri, "UTF-8") } catch (_: Exception) { encodedUri }
            SidebarItem.IntentAction(uri, label, iconPath)
        } else {
            val componentStr = id.substringAfter("intent:")
            SidebarItem.IntentAction(componentStr, componentStr)
        }
    }

    private fun parseFloatingTrigger(context: Context, id: String): SidebarItem.FloatingTrigger {
        val targetId = id.substringAfter("floating_trigger:")
        val innerParsed = parse(context, targetId)
        val label = innerParsed?.label ?: "Trigger"
        return SidebarItem.FloatingTrigger(targetId, "Trigger: $label", id)
    }

    private fun parseQuickTile(id: String): SidebarItem.QuickTile? {
        val action = id.substringAfter("quicktile:")
        val qTile = ALL_QUICK_TILES.find { it.action == action } ?: return null
        return SidebarItem.QuickTile(action, qTile.label, qTile.iconResId)
    }

    private fun parseSystemAction(id: String): SidebarItem.SystemAction? {
        val action = id.substringAfter("system:")
        val sysAction = ALL_SYSTEM_ACTIONS.find { it.action == action }
            ?: ALL_SCREEN_CAPTURE_ACTIONS.find { it.action == action }
            ?: ALL_UTILITIES_ACTIONS.filterIsInstance<SidebarItem.SystemAction>().find { it.action == action }
            ?: ALL_FLOATING_WINDOWS.find { it.action == action }
            ?: return null
        return SidebarItem.SystemAction(action, sysAction.label, sysAction.iconResId)
    }

    private fun parsePageWindow(id: String): SidebarItem.PageWindow {
        val pageType = id.substringAfter("page_window:")
        val title = when (pageType) {
            "calculator" -> "Calculator"
            "compass" -> "Compass"
            "scheduler" -> "Short Reminders"
            "notifications" -> "Notifications"
            "app_tracker" -> "App Tracker"
            "resources_tracker" -> "Resources Tracker"
            "media_player" -> "Media Player"
            "widget" -> "Android Widget"
            else -> "Page Window"
        }
        return SidebarItem.PageWindow(pageType, "Window: $title", android.R.drawable.ic_menu_gallery)
    }

    private fun parseVolumeAction(id: String): SidebarItem.VolumeAction? {
        val actionId = id.substringAfter("volume:")
        val volAction = ALL_VOLUME_ACTIONS.find { "${it.stream}_${it.action}" == actionId } ?: return null
        return SidebarItem.VolumeAction(volAction.stream, volAction.action, volAction.label, volAction.iconResId)
    }

    private fun parseMediaAction(id: String): SidebarItem.MediaAction? {
        val actionId = id.substringAfter("media:")
        val mediaAction = ALL_MEDIA_ACTIONS.find { it.action == actionId } ?: return null
        return SidebarItem.MediaAction(actionId, mediaAction.label, mediaAction.iconResId)
    }

    private fun parseDisplayAction(id: String): SidebarItem.DisplayAction? {
        val actionId = id.substringAfter("display:")
        val displayAction = ALL_DISPLAY_ACTIONS.find { it.action == actionId }
            ?: ALL_UTILITIES_ACTIONS.filterIsInstance<SidebarItem.DisplayAction>().find { it.action == actionId }
            ?: return null
        return SidebarItem.DisplayAction(actionId, displayAction.label, displayAction.iconResId)
    }

    private fun parseSettingsShortcut(id: String): SidebarItem.SettingsShortcut? {
        val actionId = id.substringAfter("settings_shortcut:")
        val settingsAction = ALL_SETTINGS_SHORTCUTS.find { it.action == actionId } ?: return null
        return SidebarItem.SettingsShortcut(actionId, settingsAction.label, settingsAction.iconResId)
    }

    private fun parseWidget(id: String): SidebarItem.Widget? {
        val parts = id.split(":", limit = 3)
        if (parts.size < 2) return null
        val widgetId = parts[1].toIntOrNull() ?: return null
        val jsonStr = parts.getOrNull(2)
        var label = "Widget $widgetId"
        if (!jsonStr.isNullOrEmpty()) {
            try {
                val json = JSONObject(jsonStr)
                label = json.optString("label", label)
            } catch (_: Exception) {}
        }
        return SidebarItem.Widget(widgetId, label)
    }

    private fun parseFolder(id: String): SidebarItem.Folder? {
        val parts = id.split(":", limit = 3)
        val uuid = parts.getOrNull(1) ?: ""
        val folderDataStr = if (parts.size >= 3) parts[2] else "{}"
        val obj = try { JSONObject(folderDataStr) } catch (_: Exception) { JSONObject() }
        val itemsArr = obj.optJSONArray("items")
        val itemsList = mutableListOf<String>()
        if (itemsArr != null) {
            for (i in 0 until itemsArr.length()) {
                itemsList.add(itemsArr.getString(i))
            }
        }
        val folderStyle = obj.optInt("folderStyle", 0)
        val popupColumns = obj.optInt("popupColumns", 0)
        val popupRows = obj.optInt("popupRows", 0)
        return SidebarItem.Folder(
            uuid = uuid,
            name = obj.optString("name", "Folder"),
            colorHex = obj.optString("colorHex", "#444444"),
            items = itemsList,
            folderStyle = folderStyle,
            popupColumns = popupColumns,
            popupRows = popupRows,
            id = id
        )
    }

    private fun parseLink(context: Context, id: String): SidebarItem.Link {
        val parts = id.split(":", limit = 3)
        val uuid = parts.getOrNull(1) ?: id
        val meta = ElementMetadataStore.get(context, "link:$uuid") ?: ElementMetadataStore.get(context, id)
        if (meta != null) {
            return SidebarItem.Link(
                uuid = uuid,
                url = meta.target,
                label = meta.label,
                iconPath = meta.iconPath,
                browserPackage = meta.browserPackage,
                account = meta.account,
                id = id
            )
        }
        val linkDataStr = if (parts.size >= 3) parts[2] else "{}"
        val obj = try { JSONObject(linkDataStr) } catch (_: Exception) { JSONObject() }
        val url = obj.optString("url", "https://")
        val label = obj.optString("label", "Link")
        val iconPath = obj.optString("iconPath", "")
        val browserPackage = if (obj.has("browserPackage") && !obj.isNull("browserPackage")) obj.getString("browserPackage") else null
        val account = if (obj.has("account") && !obj.isNull("account")) obj.getString("account") else null
        val savedMeta = ElementMetadataStore.saveLinkElement(context, uuid, url, label, "link:$uuid", browserPackage, account)
        return SidebarItem.Link(
            uuid = uuid,
            url = url,
            label = label,
            iconPath = if (iconPath.isNotEmpty()) iconPath else savedMeta.iconPath,
            browserPackage = browserPackage,
            account = account,
            id = id
        )
    }

    private fun parseSpacer(id: String): SidebarItem.Spacer {
        val parts = id.split(":", limit = 3)
        val uuid = parts.getOrNull(1) ?: ""
        val spacerDataStr = if (parts.size > 2) parts[2] else "{}"
        var height = 50
        try {
            val obj = JSONObject(spacerDataStr)
            height = obj.optInt("heightDp", 50)
        } catch (_: Exception) {
            height = spacerDataStr.toIntOrNull() ?: 50
        }
        return SidebarItem.Spacer(uuid, height, "spacer:$uuid")
    }
}
