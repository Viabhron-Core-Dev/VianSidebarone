package com.example.feature.sidebar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
// Note: SidebarItem sealed hierarchy and ALL_* actions have been extracted to SidebarItem.kt

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

    var allInstalledApps: List<AppInfo> = emptyList()
        internal set

    fun filterApps(query: String): List<AppInfo> {
        val q = query.trim()
        if (q.isEmpty()) return allInstalledApps
        return allInstalledApps.filter { app ->
            app.label.contains(q, ignoreCase = true) || app.packageName.contains(q, ignoreCase = true)
        }
    }

    fun loadAppsFromHeavy(
        containerId: String,
        pageId: String? = null,
        onComplete: ((List<AppInfo>) -> Unit)? = null
    ) {
        val connMgr = com.example.core.ipc.HeavyProcessConnectionManager.getInstance(context)
        connMgr.acquireConsumer("apps_manager_${prefKey}")
        coroutineScope.launch(Dispatchers.IO) {
            val cmd = com.example.core.ipc.HeavyCommand(
                commandId = "apps_req_${System.currentTimeMillis()}",
                type = com.example.core.ipc.HeavyCommandType.GET_APPS_DATA,
                targetId = containerId,
                payload = mapOf(
                    "containerId" to containerId,
                    "pageId" to (pageId ?: ""),
                    "operation" to "GET_INSTALLED_APPS",
                    "precache_icons" to "true"
                )
            )
            val result = connMgr.sendCommandSuspending(cmd)
            val apps = if (result.success) {
                val jsonStr = result.data["apps"]
                parseAppsJson(jsonStr).also {
                    allInstalledApps = it
                }
            } else {
                allInstalledApps
            }

            withContext(Dispatchers.Main) {
                notifyUpdated()
                onComplete?.invoke(apps)
            }
        }
    }

    fun releaseHeavyConnection() {
        val connMgr = com.example.core.ipc.HeavyProcessConnectionManager.getInstance(context)
        connMgr.releaseConsumer("apps_manager_${prefKey}")
    }

    private fun parseAppsJson(jsonStr: String?): List<AppInfo> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        val list = mutableListOf<AppInfo>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val pkg = obj.optString("packageName", obj.optString("p", ""))
                val label = obj.optString("label", obj.optString("l", pkg))
                if (pkg.isNotEmpty()) {
                    list.add(AppInfo(pkg, label))
                }
            }
        } catch (_: Exception) {}
        return list
    }

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
        try {
            prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        } catch (e: Exception) {}
        coroutineScope.launch(Dispatchers.IO) {
            ElementMetadataStore.migrateLegacyElements(context)
        }
    }

    fun destroy() {
        try {
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (e: Exception) {}
        iconCache.evictAll()
        updateListeners.clear()
        activeItems = emptyList()
        allInstalledApps = emptyList()
        hasLoadedOnce = false
        releaseHeavyConnection()
    }

    fun trimMemory(level: Int) {
        iconCache.evictAll()
    }

    fun ensureLoaded() {
        if (!hasLoadedOnce) {
            coroutineScope.launch {
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
                icon.setColorFilter(android.graphics.Color.parseColor("#00E676"))
                icon.alpha = 1.0f
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
            } else if (parsed.action == "privacy_curtain" && com.example.service.PrivacyCurtainManager.isEnabled) {
                icon.setColorFilter(android.graphics.Color.parseColor("#1DB954"))
            } else if (parsed.action == "e_ink_mode" && com.example.feature.system_hub.EInkPaperFilterManager.isEnabled) {
                icon.setColorFilter(android.graphics.Color.parseColor("#E6C280"))
            } else {
                icon.setColorFilter(android.graphics.Color.WHITE)
            }
        } else if (parsed is SidebarItem.VolumeAction || parsed is SidebarItem.MediaAction || parsed is SidebarItem.SettingsShortcut) {
            val cached = getIconBitmap(id)
            if (cached != null) {
                icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                icon.setImageBitmap(cached)
            } else {
                icon.setImageResource(android.R.drawable.ic_menu_gallery)
            }
        } else if (parsed is SidebarItem.Link) {
            val cached = getIconBitmap(id)
            if (cached != null) {
                icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                icon.clearColorFilter()
                icon.setImageBitmap(cached)
            } else {
                icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                icon.setImageResource(com.example.R.drawable.ic_language)
                icon.setColorFilter(android.graphics.Color.WHITE)
                coroutineScope.launch {
                    val meta = ElementMetadataStore.get(context, id)
                    val iconPath = meta?.iconPath
                    var bitmap: Bitmap? = null
                    if (!iconPath.isNullOrEmpty()) {
                        val f = java.io.File(iconPath)
                        if (f.exists() && f.length() > 0) {
                            try {
                                bitmap = BitmapFactory.decodeFile(f.absolutePath)
                            } catch (_: Exception) {}
                        }
                    }
                    if (bitmap == null && meta != null && meta.target.isNotEmpty()) {
                        val uuid = id.substringAfter("link:").substringBefore(":")
                        val downloaded = com.example.core.FaviconFetcher.fetchAndCacheSiteIcon(context, uuid, meta.target)
                        if (downloaded != null) {
                            val f = java.io.File(downloaded)
                            if (f.exists()) {
                                try {
                                    bitmap = BitmapFactory.decodeFile(f.absolutePath)
                                    ElementMetadataStore.save(context, meta.copy(iconPath = downloaded))
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    if (bitmap != null) {
                        iconCache.put(id, bitmap)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            icon.clearColorFilter()
                            icon.setImageBitmap(bitmap)
                        }
                    }
                }
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

    fun invalidateIcon(id: String) {
        iconCache.remove(id)
        iconCache.remove("custom_$id")
        if (id.startsWith("app:")) {
            val pkg = id.substringAfter("app:").substringBefore(":")
            iconCache.remove(pkg)
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
        iconCache.get(id)?.let { return it }

        val meta = ElementMetadataStore.get(context, id)
        if (meta != null && meta.iconPath.isNotEmpty()) {
            val iconFile = java.io.File(meta.iconPath)
            if (iconFile.exists() && iconFile.length() > 0) {
                try {
                    val bmp = BitmapFactory.decodeFile(iconFile.absolutePath)
                    if (bmp != null) {
                        iconCache.put(id, bmp)
                        return bmp
                    }
                } catch (e: Exception) {}
            }
        }

        if (id.startsWith("app:")) {
            val pkg = id.substringAfter("app:").substringBefore(":")
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
        val result = com.example.feature.element.ElementIdParser.parse(context, id)
        if (result == null) {
            com.example.core.LogKeeper.writeLog("SidebarAppsManager", "parseId returning null for: $id")
        }
        return result
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
            if (prefKey == "sidebar_apps_sidebar_default_apps" || prefKey == "sidebar_apps_sidebar_apps") {
                jsonStr = prefs.getString("sidebar_apps", null)
            }
            if (jsonStr == null && prefKey.startsWith("sidebar_apps_")) {
                val rest = prefKey.removePrefix("sidebar_apps_")
                // Check if rest itself was a legacy page ID directly under sidebar_apps_$rest
                jsonStr = prefs.getString("sidebar_apps_$rest", null)

                if (jsonStr == null) {
                    val (containerId, pageId) = parseContainerAndPageId(rest)
                    if (pageId.isNotEmpty()) {
                        val cleanHandle = com.example.core.PageManager.getCleanHandleId(containerId)
                        jsonStr = prefs.getString("sidebar_apps_${cleanHandle}_$pageId", null)
                            ?: prefs.getString("sidebar_apps_$pageId", null)
                            ?: if (containerId == "sidebar" || containerId == "handle_1_swipe_left" || containerId == "handle_1") {
                                prefs.getString("sidebar_apps", null)
                            } else null
                    }
                }
            } else if (jsonStr == null && prefKey.startsWith("hg_")) {
                val rest = prefKey.removePrefix("hg_")
                val pageId = if (rest.contains("_")) parseContainerAndPageId(rest).second.ifEmpty { rest } else rest
                jsonStr = prefs.getString("hg_$pageId", null)
            } else if (jsonStr == null && prefKey.startsWith("wg_")) {
                val rest = prefKey.removePrefix("wg_")
                val pageId = if (rest.contains("_")) parseContainerAndPageId(rest).second.ifEmpty { rest } else rest
                jsonStr = prefs.getString("wg_$pageId", null)
            }

            if (jsonStr != null && jsonStr != "[]") {
                prefs.edit().putString(prefKey, jsonStr).apply()
            }
        }
        val safeJson = jsonStr ?: "[]"
        val jsonArray = JSONArray(safeJson)
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
            if (id.startsWith("intent:")) {
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
                    val uuid = parts.getOrNull(1) ?: ""
                    val folderDataStr = if (parts.size >= 3) parts[2] else "{}"
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
                    result.add(SidebarItem.Folder(uuid, obj.optString("name", "Folder"), obj.optString("colorHex", "#444444"), itemsList, folderStyle, popupColumns, popupRows, id))
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

    companion object {
        fun parseContainerAndPageId(rest: String): Pair<String, String> {
            val gestures = listOf(
                "_swipe_left_",
                "_swipe_right_",
                "_swipe_up_",
                "_swipe_down_",
                "_double_tap_",
                "_long_press_",
                "_tap_"
            )
            for (g in gestures) {
                val idx = rest.indexOf(g)
                if (idx != -1) {
                    val containerId = rest.substring(0, idx + g.length - 1)
                    val pageId = rest.substring(idx + g.length)
                    return containerId to pageId
                }
            }
            if (rest.startsWith("sidebar_")) {
                return "sidebar" to rest.removePrefix("sidebar_")
            }
            val handleRegex = Regex("^(handle_\\w+?)_(.*)$")
            val match = handleRegex.find(rest)
            if (match != null) {
                return match.groupValues[1] to match.groupValues[2]
            }
            return "" to rest
        }
    }
}
