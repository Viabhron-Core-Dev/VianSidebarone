package com.example.feature.sidebar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

sealed class SidebarItem {
    abstract var id: String
    abstract val label: String
    
    data class PopupWidget(val widgetId: Int, override val label: String, override var id: String = "popup_widget:$widgetId") : SidebarItem()

    data class App(
        val packageName: String,
        override val label: String
    ) : SidebarItem() {
        override var id = "app:$packageName"
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
    }

    data class Link(
        val uuid: String,
        val url: String,
        override val label: String,
        override var id: String = "link:$uuid"
    ) : SidebarItem()

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
        override var id = if (iconPath != null) "intent:${java.net.URLEncoder.encode(label, "UTF-8")}:${java.net.URLEncoder.encode(uri, "UTF-8")}:$iconPath" else "intent:${java.net.URLEncoder.encode(label, "UTF-8")}:${java.net.URLEncoder.encode(uri, "UTF-8")}"
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
    SidebarItem.SystemAction("redact_screenshot", "Redact Screenshot", android.R.drawable.ic_menu_edit),
    SidebarItem.SystemAction("qr_scan", "Secure Screen Scanner", android.R.drawable.ic_menu_search),
    SidebarItem.SystemAction("barcode_scanner", "Secure Camera Scanner", android.R.drawable.ic_menu_camera),
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
    SidebarItem.SystemAction("log_keeper", "Log Keeper", android.R.drawable.ic_menu_agenda),
    SidebarItem.SystemAction("cursor", "Cursor", android.R.drawable.ic_menu_directions),
)

val ALL_FLOATING_WINDOWS = listOf(
    SidebarItem.SystemAction("ebook_reader", "eBook Reader", com.example.R.drawable.ic_library_books),
    SidebarItem.SystemAction("dictionary_floating", "Dictionary (Floating)", android.R.drawable.ic_menu_sort_alphabetically),
    SidebarItem.SystemAction("translation_floating", "Translation (Floating)", android.R.drawable.ic_menu_sort_alphabetically),
    SidebarItem.SystemAction("work_notes", "Work Notes", android.R.drawable.ic_menu_edit),
    SidebarItem.SystemAction("hybrid_grid_floating", "Hybrid Grid (Floating)", android.R.drawable.ic_menu_gallery),
)

data class AppInfo(
    val packageName: String,
    val label: String
)

class SidebarAppsManager(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val coroutineScope: CoroutineScope,
    private val prefKey: String,
    private val onAppsUpdated: () -> Unit
) {

    var activeItems = listOf<SidebarItem>()
        private set

    var allInstalledApps = listOf<AppInfo>()
        private set

    private var hasLoadedOnce = false

    val iconCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 48).toInt().coerceIn(512, 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    private val updateListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addUpdateListener(listener: () -> Unit) {
        if (!updateListeners.contains(listener)) {
            updateListeners.add(listener)
        }
    }

    fun removeUpdateListener(listener: () -> Unit) {
        updateListeners.remove(listener)
    }

    private fun notifyUpdated() {
        onAppsUpdated()
        updateListeners.forEach { 
            try {
                it.invoke() 
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            coroutineScope.launch {
                iconCache.evictAll()
                loadAllAppsFromPackageManager()
                loadActiveApps()
                withContext(Dispatchers.Main) {
                    notifyUpdated()
                }
            }
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == prefKey || key == "sidebar_apps" || (key != null && key.startsWith("sidebar_apps_"))) {
            coroutineScope.launch {
                loadActiveApps()
                withContext(Dispatchers.Main) {
                    notifyUpdated()
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        context.registerReceiver(packageReceiver, filter)
        try {
            prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        } catch (e: Exception) {}
    }

    fun destroy() {
        try {
            context.unregisterReceiver(packageReceiver)
        } catch (e: Exception) {}
        try {
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (e: Exception) {}
        iconCache.evictAll()
        updateListeners.clear()
        allInstalledApps = emptyList()
        activeItems = emptyList()
        hasLoadedOnce = false
    }

    fun trimMemory(level: Int) {
        iconCache.evictAll()
    }

    fun ensureLoaded() {
        if (!hasLoadedOnce) {
            coroutineScope.launch {
                loadAllAppsFromPackageManager()
                loadActiveApps()
                hasLoadedOnce = true
                withContext(Dispatchers.Main) {
                    notifyUpdated()
                }
            }
        } else {
            notifyUpdated()
        }
    }

    private suspend fun loadAllAppsFromPackageManager() = withContext(Dispatchers.IO) {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as android.content.pm.LauncherApps
        val userHandle = android.os.Process.myUserHandle()
        val apps = launcherApps.getActivityList(null, userHandle)
        val result = mutableListOf<AppInfo>()
        for (activityInfo in apps) {
            val packageName = activityInfo.applicationInfo.packageName
            val label = activityInfo.label.toString()
            result.add(AppInfo(packageName, label))
        }
        val distinctResult = result.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
        allInstalledApps = distinctResult
    }

    


    fun bindIcon(id: String, icon: android.widget.ImageView, prefs: android.content.SharedPreferences, coroutineScope: kotlinx.coroutines.CoroutineScope, onUpdate: () -> Unit) {
        val parsed = parseId(id) ?: return
        val customIconFile = java.io.File(context.filesDir, "custom_icons/${id.replace(Regex("[^a-zA-Z0-9.-]"), "_")}.webp")
        if (customIconFile.exists()) {
            var customCached = iconCache.get("custom_${id}")
            if (customCached == null) {
                try {
                    val rawBmp = android.graphics.BitmapFactory.decodeFile(customIconFile.absolutePath)
                    customCached = if (rawBmp != null) {
                        val density = context.resources.displayMetrics.density
                        val targetSize = Math.round(56f * density).coerceIn(48, 192)
                        if (rawBmp.width > targetSize || rawBmp.height > targetSize) {
                            Bitmap.createScaledBitmap(rawBmp, targetSize, targetSize, true)
                        } else {
                            rawBmp
                        }
                    } else null
                    if (customCached != null) iconCache.put("custom_${id}", customCached)
                } catch (e: Exception) {}
            }
            if (customCached != null) {
                icon.setImageDrawable(null)
                icon.clearColorFilter()
                icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                icon.setImageBitmap(customCached)
                return
            }
        }
        val customIconStr = prefs.getString("custom_icon_${id}", null)
        if (!customIconStr.isNullOrEmpty()) {
            icon.setImageDrawable(null)
            icon.clearColorFilter()
            icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            if (customIconStr.length <= 4 && !customIconStr.contains(".")) {
                val density = context.resources.displayMetrics.density
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.SUBPIXEL_TEXT_FLAG).apply {
                    textSize = 28f * density
                    color = android.graphics.Color.WHITE
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
                    isDither = true
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                val iconBoxSize = Math.round(56f * density).coerceAtLeast(64)
                val bitmap = android.graphics.Bitmap.createBitmap(iconBoxSize, iconBoxSize, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                val fontMetrics = paint.fontMetrics
                val baseline = (iconBoxSize - (fontMetrics.descent + fontMetrics.ascent)) / 2f
                canvas.drawText(customIconStr, iconBoxSize / 2f, baseline, paint)
                icon.setImageBitmap(bitmap)
            } else {
                val cached = iconCache.get(customIconStr)
                if (cached != null) {
                    icon.setImageBitmap(cached)
                } else {
                    coroutineScope.launch {
                        val bitmap = loadIcon(customIconStr)
                        if (bitmap != null) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                icon.setImageBitmap(bitmap)
                            }
                        }
                    }
                }
            }
            return
        }
        
        if (parsed is SidebarItem.App) {
            val cached = getIconBitmap(id)
            if (cached != null) {
                icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                icon.setImageBitmap(cached)
            } else {
                coroutineScope.launch {
                    val bitmap = loadIcon(parsed.packageName)
                    if (bitmap != null) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            icon.setImageBitmap(bitmap)
                        }
                    }
                }
            }
        } else if (parsed is SidebarItem.IntentAction) {
            val pkg = try {
                android.content.Intent.parseUri(parsed.uri, android.content.Intent.URI_INTENT_SCHEME).`package` ?: android.content.Intent.parseUri(parsed.uri, android.content.Intent.URI_INTENT_SCHEME).component?.packageName ?: ""
            } catch (e: Exception) { "" }
            val cached = getIconBitmap(id)
            if (cached != null) {
                icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                icon.setImageBitmap(cached)
            } else {
                coroutineScope.launch {
                    var customIconBitmap: android.graphics.Bitmap? = null
                    if (parsed.iconPath != null) {
                        try {
                            val file = java.io.File(parsed.iconPath)
                            if (file.exists()) {
                                customIconBitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                            }
                        } catch(e: Exception) {}
                    }
                    val bitmap = customIconBitmap ?: loadIcon(pkg)
                    if (bitmap != null) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            icon.setImageBitmap(bitmap)
                        }
                    }
                }
            }
        } else if (parsed is SidebarItem.Widget) {
            val cached = getIconBitmap(id)
            if (cached != null) {
                icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                icon.setImageBitmap(cached)
            }
        } else if (parsed is SidebarItem.QuickTile) {
            icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            icon.setImageResource(parsed.iconResId)
            icon.setColorFilter(android.graphics.Color.WHITE)
        } else if (parsed is SidebarItem.SystemAction) {
            icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            if (parsed.action == "screen_record" && com.example.service.ScreenRecordService.isRecording) {
                icon.setImageResource(android.R.drawable.ic_media_pause)
                icon.setColorFilter(android.graphics.Color.RED)
                icon.alpha = 1.0f
            } else if (parsed.action == "force_stop_running_apps") {
                icon.setImageResource(parsed.iconResId)
                val isPresent = com.example.utils.AppTrackerHelper.isAppTrackerConfigured(context)
                if (isPresent) {
                    icon.setColorFilter(android.graphics.Color.parseColor("#00E676"))
                    icon.alpha = 1.0f
                } else {
                    icon.setColorFilter(android.graphics.Color.parseColor("#888888"))
                    icon.alpha = 0.38f
                }
            } else {
                icon.setImageResource(parsed.iconResId)
                icon.setColorFilter(android.graphics.Color.WHITE)
                icon.alpha = 1.0f
            }
        } else if (parsed is SidebarItem.PageWindow) {
            icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            icon.setImageResource(parsed.iconResId)
            icon.setColorFilter(android.graphics.Color.WHITE)
        } else if (parsed is SidebarItem.DisplayAction) {
            icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            icon.setImageResource(parsed.iconResId)
            if (parsed.action == "blue_light_filter" && com.example.service.BlueLightFilterManager.isEnabled) {
                icon.setColorFilter(android.graphics.Color.parseColor("#FF9900"))
            } else {
                icon.setColorFilter(android.graphics.Color.WHITE)
            }
        } else if (parsed is SidebarItem.VolumeAction || parsed is SidebarItem.MediaAction || parsed is SidebarItem.SettingsShortcut || parsed is SidebarItem.Link) {
            val cached = getIconBitmap(id)
            if (cached != null) {
                icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                icon.setImageBitmap(cached)
            } else {
                icon.setImageResource(android.R.drawable.ic_menu_gallery)
            }
        } else if (parsed is SidebarItem.Folder) {
            icon.setImageDrawable(null)
            icon.clearColorFilter()
            icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            
            val cHex = try { android.graphics.Color.parseColor(parsed.colorHex) } catch(e:Exception){ android.graphics.Color.parseColor("#00BFA5") }
            val iconC = android.graphics.Color.WHITE
            
            val miniIcons = parsed.items.take(9).mapNotNull { getIconBitmap(it) }
            icon.setImageDrawable(FolderStyleDrawable(parsed.folderStyle, cHex, iconC, miniIcons))
            
            if (miniIcons.size < kotlin.math.min(parsed.items.size, 9)) {
                coroutineScope.launch {
                    var newlyLoaded = false
                    for (subItem in parsed.items.take(9)) {
                        if (getIconBitmap(subItem) == null) {
                            val pkg = when {
                                subItem.startsWith("app:") -> subItem.substringAfter("app:")
                                subItem.startsWith("intent:") -> subItem.substringAfter("intent:").split("/").getOrNull(0) ?: ""
                                else -> ""
                            }
                            if (pkg.isNotEmpty()) {
                                val bitmap = loadIcon(pkg)
                                if (bitmap != null) {
                                    newlyLoaded = true
                                }
                            }
                        }
                    }
                    if (newlyLoaded) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onUpdate()
                        }
                    }
                }
            }
        } else if (parsed is SidebarItem.FloatingTrigger) {
            val innerBmp = getIconBitmap(parsed.targetId)
            icon.setImageDrawable(BubbleDrawable(innerBmp))
            icon.clearColorFilter()
            icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }

    fun getIconBitmap(id: String): Bitmap? {
        val customIconFile = java.io.File(context.filesDir, "custom_icons/${id.replace(Regex("[^a-zA-Z0-9.-]"), "_")}.webp")
        if (customIconFile.exists()) {
            var b = iconCache.get("custom_$id")
            if (b == null) {
                try {
                    val rawBmp = android.graphics.BitmapFactory.decodeFile(customIconFile.absolutePath)
                    b = if (rawBmp != null) {
                        val density = context.resources.displayMetrics.density
                        val targetSize = Math.round(56f * density).coerceIn(48, 192)
                        if (rawBmp.width > targetSize || rawBmp.height > targetSize) {
                            Bitmap.createScaledBitmap(rawBmp, targetSize, targetSize, true)
                        } else {
                            rawBmp
                        }
                    } else null
                    if (b != null) iconCache.put("custom_$id", b)
                } catch(e: Exception) {}
            }
            if (b != null) return b
        }
        if (id.startsWith("app:")) {
            val pkg = id.substringAfter("app:")
            iconCache.get(pkg)?.let { return it }
            com.example.core.IconCacheManager.getCachedBitmap(context, pkg)?.let {
                iconCache.put(pkg, it)
                return it
            }
        } else if (id.startsWith("intent:")) {
            val pkg = id.substringAfter("intent:").split("/").getOrNull(0) ?: ""
            iconCache.get(pkg)?.let { return it }
            if (pkg.isNotEmpty()) {
                com.example.core.IconCacheManager.getCachedBitmap(context, pkg)?.let {
                    iconCache.put(pkg, it)
                    return it
                }
            }
        }
        val parsed = parseId(id) ?: return null
        if (parsed is SidebarItem.App) {
            iconCache.get(parsed.packageName)?.let { return it }
            com.example.core.IconCacheManager.getCachedBitmap(context, parsed.packageName)?.let {
                iconCache.put(parsed.packageName, it)
                return it
            }
        }
        val resId = when (parsed) {
            is SidebarItem.SystemAction -> parsed.iconResId
            is SidebarItem.PageWindow -> parsed.iconResId
            is SidebarItem.QuickTile -> parsed.iconResId
            is SidebarItem.VolumeAction -> parsed.iconResId
            is SidebarItem.MediaAction -> parsed.iconResId
            is SidebarItem.DisplayAction -> parsed.iconResId
                        is SidebarItem.SettingsShortcut -> parsed.iconResId
            is SidebarItem.Widget -> {
                try {
                    val appWidgetManager = android.appwidget.AppWidgetManager.getInstance(context)
                    val info = appWidgetManager.getAppWidgetInfo(parsed.widgetId)
                    if (info != null) {
                        val dr = info.loadIcon(context, context.resources.displayMetrics.densityDpi)
                        if (dr != null) {
                            return getBitmapFromDrawable(dr)
                        }
                    }
                } catch (e: Exception) {}
                android.R.drawable.ic_menu_gallery
            }
            is SidebarItem.Link -> android.R.drawable.ic_menu_set_as
            is SidebarItem.Folder -> android.R.drawable.ic_menu_agenda
            else -> 0
        }
        if (resId != 0) {
            val drawable = androidx.core.content.ContextCompat.getDrawable(context, resId)
            if (drawable != null) {
                // Check if we need to tint it white for the folder preview
                drawable.mutate().setColorFilter(android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
                return getBitmapFromDrawable(drawable)
            }
        }
        return null
    }

    fun parseId(id: String): SidebarItem? {
        val result = parseIdInternal(id)
        if (result == null) {
            com.example.core.LogKeeper.writeLog("SidebarAppsManager", "parseId returning null for: $id")
        }
        return result
    }
    
    private fun parseIdInternal(id: String): SidebarItem? {
        if (id.startsWith("app:")) {
            val pkg = id.substringAfter("app:")
            val appInfo = allInstalledApps.find { it.packageName == pkg }
            if (appInfo != null) {
                return SidebarItem.App(appInfo.packageName, appInfo.label)
            } else {
                try {
                    val pm = context.packageManager
                    val info = pm.getApplicationInfo(pkg, 0)
                    val label = pm.getApplicationLabel(info).toString()
                    return SidebarItem.App(pkg, label)
                } catch(e: Exception) {
                    return SidebarItem.App(pkg, pkg)
                }
            }
        } else if (id.startsWith("intent:")) {
            val parts = id.split(":", limit = 4)
            if (parts.size >= 3) {
                val encodedLabel = parts[1]
                val encodedUri = parts[2]
                val iconPath = if (parts.size >= 4) parts[3] else null
                val label = java.net.URLDecoder.decode(encodedLabel, "UTF-8")
                val uri = java.net.URLDecoder.decode(encodedUri, "UTF-8")
                return SidebarItem.IntentAction(uri, label, iconPath)
            } else {
                val componentStr = id.substringAfter("intent:")
                return SidebarItem.IntentAction(componentStr, componentStr)
            }
        } else if (id.startsWith("floating_trigger:")) {
            val targetId = id.substringAfter("floating_trigger:")
            val innerParsed = parseIdInternal(targetId)
            val label = innerParsed?.label ?: "Trigger"
            return SidebarItem.FloatingTrigger(targetId, "Trigger: $label", id)
        } else if (id.startsWith("quicktile:")) {
            val action = id.substringAfter("quicktile:")
            val qTile = ALL_QUICK_TILES.find { it.action == action }
            if (qTile != null) {
                return SidebarItem.QuickTile(action, qTile.label, qTile.iconResId)
            }
        } else if (id.startsWith("system:")) {
            val action = id.substringAfter("system:")
            val sysAction = ALL_SYSTEM_ACTIONS.find { it.action == action } ?: ALL_SCREEN_CAPTURE_ACTIONS.find { it.action == action } ?: ALL_UTILITIES_ACTIONS.filterIsInstance<SidebarItem.SystemAction>().find { it.action == action } ?: ALL_FLOATING_WINDOWS.find { it.action == action }
            if (sysAction != null) {
                return SidebarItem.SystemAction(action, sysAction.label, sysAction.iconResId)
            }
        } else if (id.startsWith("page_window:")) {
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
        } else if (id.startsWith("volume:")) {
            val actionId = id.substringAfter("volume:")
            val volAction = ALL_VOLUME_ACTIONS.find { "${it.stream}_${it.action}" == actionId }
            if (volAction != null) {
                return SidebarItem.VolumeAction(volAction.stream, volAction.action, volAction.label, volAction.iconResId)
            }
        } else if (id.startsWith("media:")) {
            val actionId = id.substringAfter("media:")
            val mediaAction = ALL_MEDIA_ACTIONS.find { it.action == actionId }
            if (mediaAction != null) {
                return SidebarItem.MediaAction(actionId, mediaAction.label, mediaAction.iconResId)
            }
        } else if (id.startsWith("display:")) {
            val actionId = id.substringAfter("display:")
            val displayAction = ALL_DISPLAY_ACTIONS.find { it.action == actionId } ?: ALL_UTILITIES_ACTIONS.filterIsInstance<SidebarItem.DisplayAction>().find { it.action == actionId }
            if (displayAction != null) {
                return SidebarItem.DisplayAction(actionId, displayAction.label, displayAction.iconResId)
            }
        } else if (id.startsWith("settings_shortcut:")) {
            val actionId = id.substringAfter("settings_shortcut:")
            val settingsAction = ALL_SETTINGS_SHORTCUTS.find { it.action == actionId }
            if (settingsAction != null) {
                return SidebarItem.SettingsShortcut(actionId, settingsAction.label, settingsAction.iconResId)
            }
        } else if (id.startsWith("settings_shortcut:")) {
                val actionId = id.substringAfter("settings_shortcut:")
                val settingsAction = ALL_SETTINGS_SHORTCUTS.find { it.action == actionId }
                if (settingsAction != null) {
                    return SidebarItem.SettingsShortcut(actionId, settingsAction.label, settingsAction.iconResId)
                }
                        } else if (id.startsWith("widget:")) {
            try {
                val parts = id.split(":", limit = 3)
                if (parts.size >= 2) {
                    val widgetId = parts[1].toInt()
                    val jsonStr = parts.getOrNull(2)
                    var label = "Widget $widgetId"
                                        if (jsonStr != null && jsonStr.isNotEmpty()) {
                        val json = org.json.JSONObject(jsonStr)
                        label = json.optString("label", label)
                    }
                    return SidebarItem.Widget(widgetId, label)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else if (id.startsWith("folder:")) {
            try {
                val parts = id.split(":", limit = 3)
                val uuid = parts[1]
                val folderDataStr = parts[2]
                val obj = org.json.JSONObject(folderDataStr)
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
                return SidebarItem.Folder(uuid, obj.getString("name"), obj.getString("colorHex"), itemsList, folderStyle, popupColumns, popupRows, id)
            } catch (e: Exception) { 
                    com.example.core.LogKeeper.writeLog("SidebarAppsManager", "Error parsing folder id: $id - ${e.message}")
                    e.printStackTrace() 
                }
        } else if (id.startsWith("link:")) {
            try {
                val parts = id.split(":", limit = 3)
                val uuid = parts[1]
                val linkDataStr = parts[2]
                val obj = org.json.JSONObject(linkDataStr)
                return SidebarItem.Link(uuid, obj.getString("url"), obj.getString("label"), id)
            } catch (e: Exception) { 
                    com.example.core.LogKeeper.writeLog("SidebarAppsManager", "Error parsing folder id: $id - ${e.message}")
                    e.printStackTrace() 
                }
        } else if (id.startsWith("spacer:")) {
            try {
                val parts = id.split(":", limit = 3)
                val uuid = parts[1]
                val spacerDataStr = if (parts.size > 2) parts[2] else "{}"
                var height = 50
                try {
                    val obj = org.json.JSONObject(spacerDataStr)
                    height = obj.optInt("heightDp", 50)
                } catch(e: Exception) {
                    height = spacerDataStr.toIntOrNull() ?: 50
                }
                return SidebarItem.Spacer(uuid, height, id)
            } catch (e: Exception) { 
                    com.example.core.LogKeeper.writeLog("SidebarAppsManager", "Error parsing folder id: $id - ${e.message}")
                    e.printStackTrace() 
                }
        }
        return null
    }

    fun reloadActiveApps() {
        coroutineScope.launch {
            loadActiveApps()
            withContext(Dispatchers.Main) {
                onAppsUpdated()
            }
        }
    }

    private suspend fun loadActiveApps() = withContext(Dispatchers.IO) {
        var jsonStr = prefs.getString(prefKey, null)
        if (jsonStr == null) {
            if (prefKey == "sidebar_apps_sidebar_default_apps") {
                jsonStr = prefs.getString("sidebar_apps", null)
            }
            if (jsonStr == null) {
                // Fallback to legacy key before using empty list
                val pageId = prefKey.substringAfterLast("_")
                jsonStr = prefs.getString("sidebar_apps_$pageId", "[]") ?: "[]"
            }
        }
        val jsonArray = JSONArray(jsonStr)
        val selectedIds = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
            val itemStr = jsonArray.getString(i)
            if (!itemStr.contains(":")) {
                selectedIds.add("app:$itemStr")
            } else {
                selectedIds.add(itemStr)
            }
        }

        val result = mutableListOf<SidebarItem>()
        for (id in selectedIds) {
            val parsed = parseId(id)
            if (parsed != null) {
                result.add(parsed)
                continue
            }
            if (id.startsWith("app:")) {
                val pkg = id.substringAfter("app:")
                val appInfo = allInstalledApps.find { it.packageName == pkg }
                if (appInfo != null) {
                    result.add(SidebarItem.App(appInfo.packageName, appInfo.label))
                }
            } else if (id.startsWith("intent:")) {
                val parts = id.split(":", limit = 4)
                if (parts.size >= 3) {
                    val encodedLabel = parts[1]
                    val encodedUri = parts[2]
                    val iconPath = if (parts.size >= 4) parts[3] else null
                    val label = java.net.URLDecoder.decode(encodedLabel, "UTF-8")
                    val uri = java.net.URLDecoder.decode(encodedUri, "UTF-8")
                    result.add(SidebarItem.IntentAction(uri, label, iconPath))
                } else {
                    val componentStr = id.substringAfter("intent:")
                    result.add(SidebarItem.IntentAction(componentStr, componentStr))
                }
            } else if (id.startsWith("quicktile:")) {
                val action = id.substringAfter("quicktile:")
                val qTile = ALL_QUICK_TILES.find { it.action == action }
                if (qTile != null) {
                    result.add(SidebarItem.QuickTile(action, qTile.label, qTile.iconResId))
                }
            } else if (id.startsWith("system:")) {
                val action = id.substringAfter("system:")
                val sysAction = ALL_SYSTEM_ACTIONS.find { it.action == action } ?: ALL_SCREEN_CAPTURE_ACTIONS.find { it.action == action } ?: ALL_UTILITIES_ACTIONS.filterIsInstance<SidebarItem.SystemAction>().find { it.action == action } ?: ALL_FLOATING_WINDOWS.find { it.action == action }
                if (sysAction != null) {
                    result.add(SidebarItem.SystemAction(action, sysAction.label, sysAction.iconResId))
                }
            } else if (id.startsWith("page_window:")) {
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
                "resources_tracker" -> "Resources Tracker"
                "media_player" -> "Media Player"
                "widget" -> "Android Widget"
                    else -> "Page Window"
                }
                result.add(SidebarItem.PageWindow(pageType, "Window: $title", android.R.drawable.ic_menu_gallery))
            } else if (id.startsWith("volume:")) {
                val actionId = id.substringAfter("volume:")
                val volAction = ALL_VOLUME_ACTIONS.find { "${it.stream}_${it.action}" == actionId }
                if (volAction != null) {
                    result.add(SidebarItem.VolumeAction(volAction.stream, volAction.action, volAction.label, volAction.iconResId))
                }
            } else if (id.startsWith("media:")) {
                val actionId = id.substringAfter("media:")
                val mediaAction = ALL_MEDIA_ACTIONS.find { it.action == actionId }
                if (mediaAction != null) {
                    result.add(SidebarItem.MediaAction(actionId, mediaAction.label, mediaAction.iconResId))
                }
            } else if (id.startsWith("display:")) {
                val actionId = id.substringAfter("display:")
                val displayAction = ALL_DISPLAY_ACTIONS.find { it.action == actionId } ?: ALL_UTILITIES_ACTIONS.filterIsInstance<SidebarItem.DisplayAction>().find { it.action == actionId }
                if (displayAction != null) {
                    result.add(SidebarItem.DisplayAction(actionId, displayAction.label, displayAction.iconResId))
                }
            } else if (id.startsWith("settings_shortcut:")) {
                val actionId = id.substringAfter("settings_shortcut:")
                val settingsAction = ALL_SETTINGS_SHORTCUTS.find { it.action == actionId }
                if (settingsAction != null) {
                    result.add(SidebarItem.SettingsShortcut(actionId, settingsAction.label, settingsAction.iconResId))
                }
                        } else if (id.startsWith("widget:")) {
            try {
                val parts = id.split(":", limit = 3)
                if (parts.size >= 2) {
                    val widgetId = parts[1].toInt()
                    val jsonStr = parts.getOrNull(2)
                    var label = "Widget $widgetId"
                    if (jsonStr != null && jsonStr.isNotEmpty()) {
                        val json = org.json.JSONObject(jsonStr)
                        label = json.optString("label", label)
                    }
                    result.add(SidebarItem.Widget(widgetId, label))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else if (id.startsWith("folder:")) {
                try {
                    val parts = id.split(":", limit = 3)
                    val uuid = parts[1]
                    val folderDataStr = parts[2]
                    val obj = org.json.JSONObject(folderDataStr)
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
                    result.add(SidebarItem.Folder(uuid, obj.getString("name"), obj.getString("colorHex"), itemsList, folderStyle, popupColumns, popupRows, id))
                } catch (e: Exception) { 
                    com.example.core.LogKeeper.writeLog("SidebarAppsManager", "Error parsing folder id: $id - ${e.message}")
                    e.printStackTrace() 
                }
            } else if (id.startsWith("link:")) {
                try {
                    val parts = id.split(":", limit = 3)
                    val uuid = parts[1]
                    val linkDataStr = parts[2]
                    val obj = org.json.JSONObject(linkDataStr)
                    result.add(SidebarItem.Link(uuid, obj.getString("url"), obj.getString("label"), id))
                } catch (e: Exception) { 
                    com.example.core.LogKeeper.writeLog("SidebarAppsManager", "Error parsing folder id: $id - ${e.message}")
                    e.printStackTrace() 
                }
            } else if (id.startsWith("spacer:")) {
                try {
                    val parts = id.split(":", limit = 3)
                    val uuid = parts[1]
                    val spacerDataStr = parts[2]
                    val obj = org.json.JSONObject(spacerDataStr)
                    result.add(SidebarItem.Spacer(uuid, obj.getInt("heightDp"), id))
                } catch (e: Exception) { 
                    com.example.core.LogKeeper.writeLog("SidebarAppsManager", "Error parsing folder id: $id - ${e.message}")
                    e.printStackTrace() 
                }
            }
        }
        activeItems = result
    }

    suspend fun loadIcon(packageName: String): Bitmap? = withContext(Dispatchers.IO) {
        iconCache.get(packageName)?.let { return@withContext it }

        val cachedDisk = com.example.core.IconCacheManager.getOrLoadBitmap(context, packageName)
        if (cachedDisk != null) {
            iconCache.put(packageName, cachedDisk)
            return@withContext cachedDisk
        }

        val pm = context.packageManager
        return@withContext try {
            val icon = pm.getApplicationIcon(packageName)
            val bitmap = getBitmapFromDrawable(icon)
            if (bitmap != null) {
                iconCache.put(packageName, bitmap)
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    fun getBitmapFromDrawable(drawable: Drawable): Bitmap? {
        val density = context.resources.displayMetrics.density
        val targetSize = Math.round(56f * density).coerceIn(48, 192)
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val bmp = drawable.bitmap
            if (bmp.width <= targetSize && bmp.height <= targetSize) {
                return bmp
            }
            return Bitmap.createScaledBitmap(bmp, targetSize, targetSize, true)
        }
        try {
            val w = if (drawable.intrinsicWidth > 0) minOf(drawable.intrinsicWidth, targetSize) else targetSize
            val h = if (drawable.intrinsicHeight > 0) minOf(drawable.intrinsicHeight, targetSize) else targetSize
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        } catch (e: Exception) {
            return null
        }
    }

    fun addItem(id: String) {
        coroutineScope.launch(Dispatchers.IO) {
            val currentStr = prefs.getString(prefKey, "[]") ?: "[]"
            val current = JSONArray(currentStr)
            for (i in 0 until current.length()) {
                var item = current.getString(i)
                if (!item.contains(":")) item = "app:$item"
                if (item == id) return@launch
            }
            current.put(id)
            prefs.edit().putString(prefKey, current.toString()).apply()
            loadActiveApps()
            withContext(Dispatchers.Main) {
                notifyUpdated()
            }
        }
    }

    fun moveItem(id: String, moveUp: Boolean) {
        coroutineScope.launch(Dispatchers.IO) {
            val currentStr = prefs.getString(prefKey, "[]") ?: return@launch
            val current = JSONArray(currentStr)
            val items = mutableListOf<String>()
            var targetIndex = -1
            for (i in 0 until current.length()) {
                var item = current.getString(i)
                if (!item.contains(":")) item = "app:$item"
                items.add(item)
                if (item == id) targetIndex = i
            }
            if (targetIndex != -1) {
                if (moveUp && targetIndex > 0) {
                    val temp = items[targetIndex]
                    items[targetIndex] = items[targetIndex - 1]
                    items[targetIndex - 1] = temp
                } else if (!moveUp && targetIndex < items.size - 1) {
                    val temp = items[targetIndex]
                    items[targetIndex] = items[targetIndex + 1]
                    items[targetIndex + 1] = temp
                } else {
                    return@launch
                }
                val newArray = JSONArray()
                items.forEach { newArray.put(it) }
                prefs.edit().putString(prefKey, newArray.toString()).apply()
                loadActiveApps()
                withContext(Dispatchers.Main) {
                    notifyUpdated()
                }
            }
        }
    }

    fun removeItem(id: String) {
        coroutineScope.launch(Dispatchers.IO) {
            val currentStr = prefs.getString(prefKey, "[]") ?: "[]"
            val current = JSONArray(currentStr)
            val newArray = JSONArray()
            for (i in 0 until current.length()) {
                var item = current.getString(i)
                if (!item.contains(":")) item = "app:$item"
                
                val itemId = if (item.startsWith("folder:") || item.startsWith("link:") || item.startsWith("spacer:")) {
                    val parts = item.split(":", limit = 3)
                    if (parts.size >= 2) "${parts[0]}:${parts[1]}" else item
                } else {
                    item
                }
                
                val targetId = if (id.startsWith("folder:") || id.startsWith("link:") || id.startsWith("spacer:")) {
                    val parts = id.split(":", limit = 3)
                    if (parts.size >= 2) "${parts[0]}:${parts[1]}" else id
                } else {
                    id
                }

                if (itemId != targetId) {
                    newArray.put(item)
                }
            }
            prefs.edit().putString(prefKey, newArray.toString()).apply()
            loadActiveApps()
            withContext(Dispatchers.Main) {
                notifyUpdated()
            }
        }
    }

    fun addItemToFolder(folderUuid: String, itemId: String) {
        
        coroutineScope.launch(Dispatchers.IO) {
            val currentStr = prefs.getString(prefKey, "[]") ?: return@launch
            val current = JSONArray(currentStr)
            val newArray = JSONArray()
            for (i in 0 until current.length()) {
                var item = current.getString(i)
                if (item.startsWith("folder:$folderUuid:")) {
                    try {
                        val parts = item.split(":", limit = 3)
                        val folderDataStr = parts[2]
                        val obj = org.json.JSONObject(folderDataStr)
                        val itemsArr = obj.optJSONArray("items") ?: org.json.JSONArray()
                        itemsArr.put(itemId)
                        obj.put("items", itemsArr)
                        item = "folder:$folderUuid:${obj.toString()}"
                    } catch (e: Exception) {}
                }
                newArray.put(item)
            }
            prefs.edit().putString(prefKey, newArray.toString()).apply()
            loadActiveApps()
            withContext(Dispatchers.Main) {
                notifyUpdated()
            }
        }
    }
}
