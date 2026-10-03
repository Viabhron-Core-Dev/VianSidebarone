package com.example.feature.sidebar

import android.graphics.Bitmap

/**
 * SidebarItem: Authoritative Element models and action categories for all modular Elements
 * within the Sidebar / Container runtime.
 *
 * Preserves the strict hierarchy:
 * Handle -> Gesture -> Container -> Page -> Element
 *
 * Supported Element types:
 * - App: Installed application launcher element
 * - Link: Web URL or custom URI link element
 * - Widget: Embedded Android AppWidget element
 * - PopupWidget: Modal popup AppWidget element
 * - Folder: Container folder grouping multiple elements
 * - QuickTile: System quick settings tile action
 * - SystemAction: System-level and accessibility service actions
 * - VolumeAction: Audio volume and ringer mode controls
 * - MediaAction: Media transport and playback controls
 * - DisplayAction: Screen timeout, rotation, torch, and filter controls
 * - SettingsShortcut: Direct shortcuts to Android system settings
 * - IntentAction: Custom Android Intent action
 * - PageWindow: Mini-app floating window toggle
 * - FloatingTrigger: Container navigation trigger
 * - Spacer: Layout spacing element
 */
sealed class SidebarItem {
    abstract var id: String
    abstract val label: String

    data class PopupWidget(
        val widgetId: Int,
        override val label: String,
        override var id: String = "popup_widget:$widgetId"
    ) : SidebarItem()

    data class App(
        val packageName: String,
        override val label: String,
        val iconPath: String? = null,
        val isLaunchable: Boolean = true,
        override var id: String = "app:$packageName"
    ) : SidebarItem() {
        constructor(packageName: String, label: String) : this(packageName, label, null, true, "app:$packageName")
        constructor(packageName: String, label: String, iconPath: String?) : this(packageName, label, iconPath, true, "app:$packageName")
        constructor(packageName: String, label: String, iconPath: String?, id: String) : this(packageName, label, iconPath, true, id)
    }

    data class SystemAction(
        val action: String,
        override val label: String,
        val iconResId: Int
    ) : SidebarItem() {
        override var id = "system:$action"
    }

    data class VolumeAction(
        val stream: String,
        val action: String,
        override val label: String,
        val iconResId: Int
    ) : SidebarItem() {
        override var id = "volume:${stream}_$action"
    }

    data class PageWindow(
        val pageType: String,
        override val label: String,
        val iconResId: Int
    ) : SidebarItem() {
        override var id = "page_window:$pageType"
    }

    data class MediaAction(
        val action: String,
        override val label: String,
        val iconResId: Int
    ) : SidebarItem() {
        override var id = "media:$action"
    }

    data class DisplayAction(
        val action: String,
        override val label: String,
        val iconResId: Int
    ) : SidebarItem() {
        override var id = "display:$action"
    }

    data class QuickTile(
        val action: String,
        override val label: String,
        val iconResId: Int
    ) : SidebarItem() {
        override var id = "quicktile:$action"
    }

    data class SettingsShortcut(
        val action: String,
        override val label: String,
        val iconResId: Int
    ) : SidebarItem() {
        override var id = "settings_shortcut:$action"
    }

    data class Widget(
        val widgetId: Int,
        override val label: String,
        val iconBitmap: Bitmap? = null
    ) : SidebarItem() {
        override var id = "widget:$widgetId"
    }

    data class Folder(
        val uuid: String,
        val name: String,
        val colorHex: String,
        val items: List<String>,
        val folderStyle: Int = 0,
        val popupColumns: Int = 0,
        val popupRows: Int = 0,
        override var id: String = "folder:$uuid"
    ) : SidebarItem() {
        override val label = name

        fun toSerializedId(): String {
            val obj = org.json.JSONObject().apply {
                put("name", name)
                put("colorHex", colorHex)
                put("folderStyle", folderStyle)
                put("popupColumns", popupColumns)
                put("popupRows", popupRows)
                val arr = org.json.JSONArray()
                items.forEach { arr.put(it) }
                put("items", arr)
            }
            return "folder:$uuid:${obj.toString()}"
        }
    }

    data class Link(
        val uuid: String,
        val url: String,
        override val label: String,
        val iconPath: String? = null,
        val browserPackage: String? = null,
        val account: String? = null,
        override var id: String = "link:$uuid"
    ) : SidebarItem() {
        constructor(uuid: String, url: String, label: String, id: String) : this(uuid, url, label, null, null, null, id)
        constructor(uuid: String, url: String, label: String, iconPath: String?, id: String) : this(uuid, url, label, iconPath, null, null, id)

        fun toSerializedId(): String {
            val obj = org.json.JSONObject().apply {
                put("url", url)
                put("label", label)
                if (!iconPath.isNullOrEmpty()) put("iconPath", iconPath)
                if (!browserPackage.isNullOrEmpty()) put("browserPackage", browserPackage)
                if (!account.isNullOrEmpty()) put("account", account)
            }
            return "link:$uuid:${obj.toString()}"
        }
    }

    data class Spacer(
        val uuid: String,
        val heightDp: Int,
        override var id: String = "spacer:$uuid"
    ) : SidebarItem() {
        override val label = "Spacer"
    }

    data class FloatingTrigger(
        val targetId: String,
        override val label: String,
        override var id: String = "floating_trigger:$targetId"
    ) : SidebarItem()

    data class IntentAction(
        val uri: String,
        override val label: String,
        val iconPath: String? = null
    ) : SidebarItem() {
        override var id = if (iconPath != null) {
            "intent:${java.net.URLEncoder.encode(label, "UTF-8")}:${java.net.URLEncoder.encode(uri, "UTF-8")}:$iconPath"
        } else {
            "intent:${java.net.URLEncoder.encode(label, "UTF-8")}:${java.net.URLEncoder.encode(uri, "UTF-8")}"
        }
    }
}

fun extractChildCountFromFolderId(folderId: String?): Int {
    if (folderId == null || !folderId.startsWith("folder:")) return 0
    return try {
        val parts = folderId.split(":", limit = 3)
        if (parts.size >= 3) {
            val obj = org.json.JSONObject(parts[2])
            val arr = obj.optJSONArray("items")
            arr?.length() ?: 0
        } else 0
    } catch (_: Exception) {
        0
    }
}

val ALL_QUICK_TILES = listOf(
    SidebarItem.QuickTile("torch", "Torch", android.R.drawable.ic_menu_camera),
    SidebarItem.QuickTile("wifi", "Wi-Fi", android.R.drawable.ic_menu_search),
    SidebarItem.QuickTile("bluetooth", "Bluetooth", android.R.drawable.ic_menu_share),
    SidebarItem.QuickTile("airplane", "Airplane Mode", android.R.drawable.ic_dialog_alert),
    SidebarItem.QuickTile("dnd", "Do Not Disturb", android.R.drawable.ic_lock_silent_mode_off),
    SidebarItem.QuickTile("location", "Location", android.R.drawable.ic_menu_mylocation),
    SidebarItem.QuickTile("nfc", "NFC", android.R.drawable.ic_menu_sort_by_size),
    SidebarItem.QuickTile("data", "Mobile Data", android.R.drawable.ic_menu_sort_alphabetically)
)

val ALL_SYSTEM_ACTIONS = listOf(
    SidebarItem.SystemAction("back", "Back", android.R.drawable.ic_menu_revert),
    SidebarItem.SystemAction("home", "Home", android.R.drawable.ic_menu_compass),
    SidebarItem.SystemAction("lock_screen", "Lock screen", android.R.drawable.ic_lock_power_off),
    SidebarItem.SystemAction("notifications", "Notifications", android.R.drawable.ic_menu_info_details),
    SidebarItem.SystemAction("quick_settings", "Quick settings", android.R.drawable.ic_menu_manage),
    SidebarItem.SystemAction("recents", "Recents", android.R.drawable.ic_menu_recent_history),
    SidebarItem.SystemAction("splitscreen", "Splitscreen", android.R.drawable.ic_menu_gallery),
    SidebarItem.SystemAction("settings", "Settings", android.R.drawable.ic_menu_preferences)
)

val ALL_SCREEN_CAPTURE_ACTIONS = listOf(
    SidebarItem.SystemAction("screenshot", "Screenshot", android.R.drawable.ic_menu_camera),
    SidebarItem.SystemAction("long_screenshot", "Long Screenshot", android.R.drawable.ic_menu_crop),
    SidebarItem.SystemAction("screen_record", "Screen Record", android.R.drawable.ic_media_play),
    SidebarItem.SystemAction("audio_record", "Audio Record", android.R.drawable.ic_btn_speak_now),
    SidebarItem.SystemAction("call_recorder", "Call Recorder", android.R.drawable.ic_menu_call),
    SidebarItem.SystemAction("recordings", "Recordings", android.R.drawable.ic_menu_save),
    SidebarItem.SystemAction("redact_screenshot", "Redact Screenshot", android.R.drawable.ic_menu_edit),
    SidebarItem.SystemAction("qr_scan", "Secure Screen Scanner", android.R.drawable.ic_menu_search),
    SidebarItem.SystemAction("barcode_scanner", "Secure Camera Scanner", android.R.drawable.ic_menu_camera)
)

val ALL_VOLUME_ACTIONS = listOf(
    SidebarItem.VolumeAction("ringer", "vol_up", "Ringer Vol+", android.R.drawable.ic_lock_silent_mode_off),
    SidebarItem.VolumeAction("ringer", "vol_down", "Ringer Vol-", android.R.drawable.ic_lock_silent_mode_off),
    SidebarItem.VolumeAction("ringer", "mute", "Ringer Mute", android.R.drawable.ic_lock_silent_mode),
    SidebarItem.VolumeAction("ringer", "unmute", "Ringer Unmute", android.R.drawable.ic_lock_silent_mode_off),
    SidebarItem.VolumeAction("ringer", "toggle_mute", "Ringer Toggle Mute", android.R.drawable.ic_lock_silent_mode),
    SidebarItem.VolumeAction("ringer", "mode_silent", "Silent Mode", android.R.drawable.ic_lock_silent_mode),
    SidebarItem.VolumeAction("ringer", "mode_vibrate", "Vibrate Mode", android.R.drawable.ic_lock_silent_mode_off),
    SidebarItem.VolumeAction("ringer", "mode_normal", "Normal Mode", android.R.drawable.ic_lock_silent_mode_off),
    SidebarItem.VolumeAction("ringer", "mode_cycle", "Cycle Mode", android.R.drawable.ic_popup_sync),

    SidebarItem.VolumeAction("media", "vol_up", "Media Vol+", android.R.drawable.ic_media_play),
    SidebarItem.VolumeAction("media", "vol_down", "Media Vol-", android.R.drawable.ic_media_play),
    SidebarItem.VolumeAction("media", "mute", "Media Mute", android.R.drawable.ic_lock_silent_mode_off),
    SidebarItem.VolumeAction("media", "unmute", "Media Unmute", android.R.drawable.ic_lock_silent_mode),
    SidebarItem.VolumeAction("media", "toggle_mute", "Media Toggle Mute", android.R.drawable.ic_lock_silent_mode),

    SidebarItem.VolumeAction("notification", "vol_up", "Notif Vol+", android.R.drawable.ic_menu_info_details),
    SidebarItem.VolumeAction("notification", "vol_down", "Notif Vol-", android.R.drawable.ic_menu_info_details),
    SidebarItem.VolumeAction("notification", "mute", "Notif Mute", android.R.drawable.ic_lock_silent_mode_off),
    SidebarItem.VolumeAction("notification", "unmute", "Notif Unmute", android.R.drawable.ic_lock_silent_mode),

    SidebarItem.VolumeAction("alarm", "vol_up", "Alarm Vol+", android.R.drawable.ic_lock_idle_alarm),
    SidebarItem.VolumeAction("alarm", "vol_down", "Alarm Vol-", android.R.drawable.ic_lock_idle_alarm),
    SidebarItem.VolumeAction("alarm", "mute", "Alarm Mute", android.R.drawable.ic_lock_silent_mode_off),
    SidebarItem.VolumeAction("alarm", "unmute", "Alarm Unmute", android.R.drawable.ic_lock_silent_mode)
)

val ALL_MEDIA_ACTIONS = listOf(
    SidebarItem.MediaAction("play_pause", "Play/Pause", android.R.drawable.ic_media_play),
    SidebarItem.MediaAction("next", "Next", android.R.drawable.ic_media_next),
    SidebarItem.MediaAction("previous", "Previous", android.R.drawable.ic_media_previous),
    SidebarItem.MediaAction("stop", "Stop", android.R.drawable.ic_media_pause)
)

val ALL_SETTINGS_SHORTCUTS = listOf(
    SidebarItem.SettingsShortcut("settings", "Settings", android.R.drawable.ic_menu_preferences),
    SidebarItem.SettingsShortcut("call_recorder", "Call Recorder", android.R.drawable.ic_menu_call),
    SidebarItem.SettingsShortcut("wifi", "Wi-Fi", android.R.drawable.ic_menu_preferences),
    SidebarItem.SettingsShortcut("bluetooth", "Bluetooth", android.R.drawable.ic_menu_preferences),
    SidebarItem.SettingsShortcut("display", "Display", android.R.drawable.ic_menu_preferences),
    SidebarItem.SettingsShortcut("sound", "Sound", android.R.drawable.ic_menu_preferences),
    SidebarItem.SettingsShortcut("location", "Location", android.R.drawable.ic_menu_preferences),
    SidebarItem.SettingsShortcut("apps", "Apps", android.R.drawable.ic_menu_preferences),
    SidebarItem.SettingsShortcut("security", "Security", android.R.drawable.ic_menu_preferences),
    SidebarItem.SettingsShortcut("battery", "Battery", android.R.drawable.ic_menu_preferences),
    SidebarItem.SettingsShortcut("date", "Date & Time", android.R.drawable.ic_menu_preferences)
)

val ALL_DISPLAY_ACTIONS = listOf(
    SidebarItem.DisplayAction("torch_toggle", "Flashlight", android.R.drawable.ic_menu_camera),
    SidebarItem.DisplayAction("timeout_cycle", "Screen Timeout", android.R.drawable.ic_menu_recent_history),
    SidebarItem.DisplayAction("orientation_toggle", "Rotation Toggle", android.R.drawable.ic_menu_always_landscape_portrait)
)

val ALL_UTILITIES_ACTIONS = listOf(
    SidebarItem.SystemAction("arrangement_checker", "Arrangement Checker", android.R.drawable.ic_menu_slideshow),
    SidebarItem.SystemAction("camera_measure", "Camera Measure", android.R.drawable.ic_menu_crop),
    SidebarItem.SystemAction("force_stop_running_apps", "Force Stop Apps", android.R.drawable.ic_menu_close_clear_cancel),
    SidebarItem.SystemAction("auto_scroll", "Auto Scroll", android.R.drawable.ic_menu_sort_by_size),
    SidebarItem.DisplayAction("blue_light_filter", "Blue Light Filter", android.R.drawable.ic_menu_view),
    SidebarItem.DisplayAction("e_ink_mode", "E-Ink Paper Mode", com.example.R.drawable.ic_library_books),
    SidebarItem.SystemAction("log_keeper", "Log Keeper", android.R.drawable.ic_menu_agenda),
    SidebarItem.SystemAction("cursor", "Cursor", android.R.drawable.ic_menu_directions),
    SidebarItem.DisplayAction("keep_screen_on", "Keep Screen On", android.R.drawable.ic_lock_idle_alarm),
    SidebarItem.DisplayAction("screen_orientation", "Screen Orientation", android.R.drawable.ic_menu_always_landscape_portrait),
    SidebarItem.DisplayAction("privacy_curtain", "Privacy Curtain", com.example.R.drawable.ic_crop_square)
)

val ALL_FLOATING_WINDOWS = listOf(
    SidebarItem.SystemAction("ebook_reader", "eBook Reader", com.example.R.drawable.ic_library_books),
    SidebarItem.SystemAction("dictionary_floating", "Dictionary (Floating)", android.R.drawable.ic_menu_sort_alphabetically),
    SidebarItem.SystemAction("translation_floating", "Translation (Floating)", android.R.drawable.ic_menu_sort_alphabetically),
    SidebarItem.SystemAction("work_notes", "Work Notes", android.R.drawable.ic_menu_edit),
    SidebarItem.SystemAction("hybrid_grid_floating", "Hybrid Grid (Floating)", android.R.drawable.ic_menu_gallery)
)
