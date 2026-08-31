package com.example.feature.sidebar
import com.example.feature.miniapps.reader.FloatingReaderService
import com.example.feature.system_hub.VianSideAccessibilityService
import com.example.core.AppWidgetHelper
import com.example.feature.system_hub.DisplayHandler
import com.example.feature.system_hub.MediaVolumeHandler
import com.example.feature.system_hub.QuickTileHandler
import com.example.service.FloatingTriggerService

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import kotlin.math.max
import android.widget.ScrollView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject


class HybridGridPageView(
    context: Context,
    private val pageId: String,
    private val scope: CoroutineScope,
    private val containerId: String = "sidebar",
    private val onClose: (() -> Unit)? = null,
    private val onDimSidebar: ((Boolean) -> Unit)? = null,
    private val onHeightChanged: (Int) -> Unit
) : FrameLayout(context), SidebarPageControllable {

    private val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)

    private val appsManager = SidebarAppsManager(context, prefs, scope, "hg_${pageId}") {
        post { loadWidgets() }
    }

    private val gridLayout = FrameLayout(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "ELEMENT_ADDED_TO_HYBRID" || intent?.action == "UPDATE_GRID") {
                val targetPageId = intent.getStringExtra("PAGE_ID")
                if (targetPageId == pageId) {
                    val widgetId = intent.getIntExtra("WIDGET_ID", -1)
                    if (widgetId != -1) {
                        addWidgetIdToPrefs(widgetId)
                    }
                    val elementId = intent.getStringExtra("ELEMENT_ID")
                    if (elementId != null) {
                        addElementIdToPrefs(elementId)
                    }
                    loadWidgets()
                }
            } else if (intent?.action == "com.example.UPDATE_SIDEBAR_ICONS") {
                loadWidgets()
            }
        }
    }

    init {
        appsManager.ensureLoaded()
        com.example.core.LogKeeper.writeLog("HybridGrid", "Opened widgets grid page")
        val scrollView = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            addView(gridLayout)
        }
        addView(scrollView)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && oldw != w) {
            post { loadWidgets() }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { loadWidgets() }
        val filter = IntentFilter()
        filter.addAction("ELEMENT_ADDED_TO_HYBRID")
        filter.addAction("UPDATE_GRID")
        filter.addAction("com.example.UPDATE_SIDEBAR_ICONS")
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {}
        appsManager.destroy()
        gridLayout.removeAllViews()
    }

    override fun onPageSelected() {
        if (gridLayout.childCount == 0 && width > 0) {
            loadWidgets()
        }
    }

    override fun onPageUnselected() {
        // Keeps memory lightweight when navigated away
    }

    private fun getWidgetItems(): List<GridWidgetItem> {
        var jsonStr = prefs.getString("hybrid_grid_$pageId", null)
        val isModified = prefs.getBoolean("hybrid_grid_modified_$pageId", false)
        if (jsonStr == null || (jsonStr == "[]" && pageId.startsWith("default_hybrid") && !isModified)) {
            if (pageId.startsWith("default_hybrid")) {
                jsonStr = """[{"id": "system:ebook_reader", "cols": 1, "rows": 1, "x": 0, "y": 0}, {"id": "system:log_keeper", "cols": 1, "rows": 1, "x": 1, "y": 0}]"""
            } else {
                jsonStr = "[]"
            }
        }
        
        val arr = JSONArray(jsonStr)
        val list = mutableListOf<GridWidgetItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i)
            if (obj != null) {
                val idStr = if (obj.has("id")) {
                    val rawId = obj.get("id")
                    if (rawId is Int) "widget:$rawId" else rawId.toString()
                } else ""
                if (idStr.isNotEmpty()) {
                    list.add(GridWidgetItem(
                        idStr,
                        obj.optInt("cols", 1),
                        obj.optInt("rows", 1),
                        obj.optInt("x", 0),
                        obj.optInt("y", 0)
                    ))
                }
            } else {
                val id = arr.optInt(i, -1)
                if (id != -1) {
                    list.add(GridWidgetItem("widget:$id", 2, 2, 0, 0))
                }
            }
        }
        return list
    }

    private fun saveWidgetItems(items: List<GridWidgetItem>) {
        val arr = JSONArray()
        items.forEach { 
            val obj = JSONObject()
            obj.put("id", it.id)
            obj.put("cols", it.cols)
            obj.put("rows", it.rows)
            obj.put("x", it.x)
            obj.put("y", it.y)
            arr.put(obj)
        }
        prefs.edit().putString("hybrid_grid_$pageId", arr.toString())
            .putBoolean("hybrid_grid_modified_$pageId", true)
            .apply()
        com.example.core.LogKeeper.writeLog("HybridGrid", "Saved ${items.size} items to prefs for page $pageId")
    }

    private fun addWidgetIdToPrefs(widgetId: Int) {
        val items = getWidgetItems().toMutableList()
        // Prevent duplicate addition if already exists
        if (items.none { it.id == "widget:$widgetId" }) {
            items.add(GridWidgetItem("widget:$widgetId", 2, 2, 0, 0))
            saveWidgetItems(items)
            com.example.core.LogKeeper.writeLog("HybridGrid", "Added widget:$widgetId to page $pageId")
        }
    }
    
    private fun addElementIdToPrefs(elementId: String) {
        val items = getWidgetItems().toMutableList()
        if (items.none { it.id == elementId }) {
            items.add(GridWidgetItem(elementId, 1, 1, 0, 0))
            saveWidgetItems(items)
            com.example.core.LogKeeper.writeLog("HybridGrid", "Added element:$elementId to page $pageId")
        }
    }

    fun getCurrentHeightPx(): Int {
        if (gridLayout.childCount == 0) {
            return 0
        }
        val lpHeight = gridLayout.layoutParams?.height ?: 0
        if (lpHeight > 0) return lpHeight
        
        gridLayout.measure(
            View.MeasureSpec.makeMeasureSpec(context.resources.displayMetrics.widthPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return gridLayout.measuredHeight
    }

    fun loadWidgets() {
        if (width == 0) {
            return
        }
        gridLayout.removeAllViews()
        val totalCols = prefs.getInt("hybrid_grid_cols_$pageId", 4)
        
        val gridWidth = width
        val cellWidth = gridWidth / totalCols
        val cellHeight = cellWidth 
        
        val items = getWidgetItems()
        if (items.isEmpty()) {
            val density = context.resources.displayMetrics.density
            val emptyHeight = (120 * density).toInt()
            val emptyView = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val pad = (16 * density).toInt()
                setPadding(pad, pad, pad, pad)
                val tv = TextView(context).apply {
                    text = if (pageId.startsWith("default_hybrid")) {
                        "Empty Home Grid\nTap to add widgets & apps"
                    } else {
                        "Empty Hybrid Grid\nTap to add widgets & apps"
                    }
                    setTextColor(Color.LTGRAY)
                    textSize = 13f
                    gravity = Gravity.CENTER
                }
                addView(tv)
                setOnClickListener {
                    onEditClicked()
                }
            }
            gridLayout.addView(emptyView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, emptyHeight))
            gridLayout.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, emptyHeight)
            onHeightChanged(emptyHeight)
            return
        }
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHelper.getHost(context)
            
            
            var maxHeight = 0
            for (item in items) {
                try {
                    if (item.id.startsWith("widget:")) {
                        val wId = item.id.removePrefix("widget:").substringBefore(":").toIntOrNull() ?: continue
                        val info = appWidgetManager.getAppWidgetInfo(wId)
                        if (info != null) {
                            val hostView = host.createView(context, wId, info)
                            hostView.setPadding(0, 0, 0, 0)
                            
                            val wCols = minOf(item.cols, totalCols)
                            val wRows = item.rows
                            
                            val params = FrameLayout.LayoutParams(cellWidth * wCols, cellHeight * wRows).apply {
                                leftMargin = item.x * cellWidth
                                topMargin = item.y * cellHeight
                            }
                            gridLayout.addView(hostView, params)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
                                val density = context.resources.displayMetrics.density
                                val minW = ((cellWidth * wCols) / density).toInt()
                                val minH = ((cellHeight * wRows) / density).toInt()
                                hostView.updateAppWidgetSize(null, minW, minH, minW, minH)
                            }
                            maxHeight = max(maxHeight, item.y * cellHeight + cellHeight * wRows)
                            
                            hostView.setOnLongClickListener {
                                val actionList = mutableListOf("App Info", "Remove")

                                var popupWindow: PopupWindow? = null
                                val popupLayout = LinearLayout(context).apply {
                                    orientation = LinearLayout.VERTICAL
                                    val pad = (8 * context.resources.displayMetrics.density).toInt()
                                    setPadding(pad, pad, pad, pad)
                                }

                                actionList.forEach { action ->
                                    val actionView = TextView(context).apply {
                                        text = action
                                        setTextColor(Color.WHITE)
                                        setPadding(0, (12 * context.resources.displayMetrics.density).toInt(), 0, (12 * context.resources.displayMetrics.density).toInt())
                                        gravity = Gravity.CENTER
                                        
                                        val shape = android.graphics.drawable.GradientDrawable()
                                        shape.cornerRadius = 8 * context.resources.displayMetrics.density
                                        shape.setColor(Color.parseColor("#333333"))
                                        shape.setStroke(1, Color.LTGRAY)
                                        background = shape
                                        
                                        layoutParams = LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.WRAP_CONTENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT
                                        ).apply {
                                            setMargins(0, 0, 0, (8 * context.resources.displayMetrics.density).toInt())
                                        }
                                        
                                        setOnClickListener {
                                            popupWindow?.dismiss()
                                            when (action) {
                                                "Remove" -> {
                                                    val newItems = items.toMutableList()
                                                    newItems.removeAll { it.id == item.id }
                                                    saveWidgetItems(newItems)
                                                    context.sendBroadcast(Intent("ELEMENT_ADDED_TO_HYBRID").apply { putExtra("PAGE_ID", pageId) })
                                                }
                                                "App Info" -> {
                                                    try {
                                                        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                                        intent.data = android.net.Uri.parse("package:${info.provider.packageName}")
                                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        context.startActivity(intent)
                                                    } catch (e: Exception) {}
                                                }
                                            }
                                        }
                                    }
                                    popupLayout.addView(actionView)
                                }
                                
                                popupLayout.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                                popupWindow = PopupWindow(
                                    popupLayout,
                                    (150 * context.resources.displayMetrics.density).toInt(),
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    true
                                ).apply {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                        windowLayoutType = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                    } else {
                                        @Suppress("DEPRECATION")
                                        windowLayoutType = android.view.WindowManager.LayoutParams.TYPE_PHONE
                                    }
                                    setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                                    isOutsideTouchable = true
                                }
                                val location = IntArray(2)
                                hostView.getLocationOnScreen(location)
                                val x = location[0]
                                var y = location[1] - popupLayout.measuredHeight
                                if (y < 0) y = location[1] + hostView.measuredHeight
                                popupWindow?.showAtLocation(hostView, Gravity.NO_GRAVITY, x, y)
                                true
                            }
                        }
                    } else {
                        val parsed = appsManager.parseId(item.id)
                        if (parsed != null) {
                            val elementView = android.view.LayoutInflater.from(context).inflate(com.example.R.layout.item_sidebar_app, null, false)
                            val icon = elementView.findViewById<android.widget.ImageView>(com.example.R.id.app_icon)
                            val label = elementView.findViewById<android.widget.TextView>(com.example.R.id.app_label)
                            icon.maxWidth = Int.MAX_VALUE
                            icon.maxHeight = Int.MAX_VALUE
                            val lp = icon.layoutParams as android.widget.LinearLayout.LayoutParams
                            lp.height = 0
                            lp.weight = 1f
                            icon.layoutParams = lp
                            
                            label.text = parsed.label
                            
                            val isForceStop = (parsed is SidebarItem.SystemAction && parsed.action == "force_stop_running_apps")
                            val isConfigured = !isForceStop || com.example.utils.AppTrackerHelper.isAppTrackerConfigured(context)
                            label.alpha = if (isConfigured) 1.0f else 0.38f

                            appsManager.bindIcon(item.id, icon, prefs, scope) {
                                appsManager.bindIcon(item.id, icon, prefs, scope) {}
                            }
                            
                            elementView.setOnClickListener {
                                when (parsed) {
                                    is SidebarItem.App -> {
                                        val intent = context.packageManager.getLaunchIntentForPackage(parsed.packageName)
                                        if (intent != null) {
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            try {
                                                com.example.core.LogKeeper.writeLog("HybridGrid", "Launch app: ${parsed.packageName}")
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                com.example.core.LogKeeper.writeLog("HybridGrid", "Error launching app: ${e.message}")
                                            }
                                        }
                                    }
                                    is SidebarItem.PageWindow -> {
                                        com.example.core.LogKeeper.writeLog("HybridGrid", "Toggle page window: ${parsed.pageType}")
                                        com.example.feature.miniapps.MiniAppManager.toggleApp(context, parsed.pageType)
                                    }
                                    is SidebarItem.FloatingTrigger -> {
                                        val intent = Intent(context, FloatingTriggerService::class.java)
                                        intent.putExtra("TARGET_ID", parsed.targetId)
                                        context.startService(intent)
                                    }
                                    is SidebarItem.Folder -> {
                                        showFolderPopup(elementView, parsed, appsManager)
                                    }
                                    is SidebarItem.PopupWidget -> {
                                        showWidgetPopup(elementView, parsed)
                                    }
                                    is SidebarItem.Link -> {
                                        try {
                                            com.example.core.LogKeeper.writeLog("HybridGrid", "Open link: ${parsed.url}")
                                            val intent = if (parsed.url.startsWith("intent:")) {
                                                Intent.parseUri(parsed.url, Intent.URI_INTENT_SCHEME)
                                            } else {
                                                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(parsed.url))
                                            }
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            com.example.core.LogKeeper.writeLog("HybridGrid", "Error opening link: ${e.message}")
                                        }
                                    }
                                    is SidebarItem.QuickTile -> {
                                        com.example.core.LogKeeper.writeLog("HybridGrid", "QuickTile action: ${parsed.action}")
                                        QuickTileHandler.handleQuickTileAction(context, parsed.action)
                                    }
                                    is SidebarItem.IntentAction -> {
                                        try {
                                            com.example.core.LogKeeper.writeLog("HybridGrid", "IntentAction: ${parsed.label}")
                                            val intent = Intent.parseUri(parsed.uri, Intent.URI_INTENT_SCHEME)
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            com.example.core.LogKeeper.writeLog("HybridGrid", "Error launching intent: ${e.message}")
                                        }
                                    }
                                    is SidebarItem.SystemAction -> {
                                        if (parsed.action == "force_stop_running_apps") {
                                            com.example.utils.AppTrackerHelper.startForceStopSequence(context)
                                        } else if (parsed.action == "log_keeper") {
                                            val intent = Intent(context, com.example.feature.settings.LogKeeperActivity::class.java)
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(intent)
                                        } else if (parsed.action == "dictionary_floating" || parsed.action == "translation_floating" || parsed.action == "hybrid_grid_floating" || parsed.action == "dictionary_full" || parsed.action == "work_notes") {
                                            val intent = Intent(context, com.example.service.SidebarService::class.java)
                                            intent.action = "EXECUTE_ACTION"
                                            intent.putExtra("ACTION_ID", "system:" + parsed.action)
                                            context.startService(intent)
                                        } else if (parsed.action == "ebook_reader") {
                                            val intent = Intent(context, com.example.feature.miniapps.reader.FloatingReaderService::class.java)
                                            intent.putExtra("UNFOLD", true)
                                            context.startService(intent)
                                        } else if (parsed.action == "screen_record") {
                                            val intent = Intent(context, ScreenRecordActivity::class.java)
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(intent)
                                        } else if (parsed.action == "audio_record") {
                                            com.example.feature.system_hub.AudioRecordFloatingPanel.toggle(context)
                                        } else if (parsed.action == "camera_measure") {
                                            val intent = Intent(context, com.example.feature.system_hub.CameraMeasureActivity::class.java)
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(intent)
                                        } else if (parsed.action == "arrangement_checker" || parsed.action == "ghost_camera") {
                                            val intent = Intent(context, com.example.feature.system_hub.GhostCameraActivity::class.java)
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(intent)
                                        } else if (parsed.action == "settings") {
                                            val intent = Intent(context, com.example.SettingsActivity::class.java)
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(intent)
                                        } else {
                                            val service = com.example.feature.system_hub.VianSideAccessibilityService.instance
                                            if (service != null && service.performAction(parsed.action)) {
                                                com.example.core.LogKeeper.writeLog("HybridGrid", "System action trigger: ${parsed.action}")
                                            } else {
                                                android.widget.Toast.makeText(context, "Please enable VianSide Accessibility Service", android.widget.Toast.LENGTH_SHORT).show()
                                                com.example.core.LogKeeper.writeLog("HybridGrid", "Failed system action trigger: ${parsed.action}")
                                                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                try { context.startActivity(intent) } catch (e: Exception) {}
                                            }
                                        }
                                    }
                                    is SidebarItem.VolumeAction -> {
                                        try {
                                            com.example.core.LogKeeper.writeLog("HybridGrid", "Volume action: ${parsed.stream}_${parsed.action}")
                                            MediaVolumeHandler.handleVolumeAction(context, parsed.stream, parsed.action)
                                        } catch (e: Exception) {
                                            com.example.core.LogKeeper.writeLog("HybridGrid", "Volume action err: ${e.message}")
                                        }
                                    }
                                    is SidebarItem.MediaAction -> {
                                        try {
                                            com.example.core.LogKeeper.writeLog("HybridGrid", "Media action: ${parsed.action}")
                                            MediaVolumeHandler.handleMediaAction(context, parsed.action)
                                        } catch (e: Exception) {
                                            com.example.core.LogKeeper.writeLog("HybridGrid", "Media action err: ${e.message}")
                                        }
                                    }
                                    is SidebarItem.DisplayAction -> {
                                        try {
                                            com.example.core.LogKeeper.writeLog("HybridGrid", "Display action: ${parsed.action}")
                                            DisplayHandler.handleDisplayAction(context, parsed.action)
                                        } catch (e: Exception) {
                                            com.example.core.LogKeeper.writeLog("HybridGrid", "Display action err: ${e.message}")
                                        }
                                    }
                                    is SidebarItem.SettingsShortcut -> {
                                        val settingsIntent = when (parsed.action) {
                                            "wifi" -> Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                                            "bluetooth" -> Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                                            "display" -> Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)
                                            "sound" -> Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)
                                            "location" -> Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                            "apps" -> Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS)
                                            "security" -> Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                                            "battery" -> Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)
                                            "date" -> Intent(android.provider.Settings.ACTION_DATE_SETTINGS)
                                            else -> Intent(android.provider.Settings.ACTION_SETTINGS)
                                        }
                                        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        try {
                                            com.example.core.LogKeeper.writeLog("HybridGrid", "Settings shortcut: ${parsed.action}")
                                            context.startActivity(settingsIntent)
                                        } catch (e: Exception) {
                                            com.example.core.LogKeeper.writeLog("HybridGrid", "Settings shortcut err: ${e.message}")
                                        }
                                    }
                                    else -> {}
                                }
                                if (parsed !is SidebarItem.Folder && parsed !is SidebarItem.PopupWidget && parsed !is SidebarItem.VolumeAction && parsed !is SidebarItem.DisplayAction && parsed !is SidebarItem.QuickTile) {
                                    onClose?.invoke() ?: com.example.service.SidebarService.instance?.closeSidebar()
                                }
                            }
                            
                            elementView.setOnLongClickListener {
                                val actionList = mutableListOf<String>()
                                if (parsed is SidebarItem.App) {
                                    actionList.add("App Info")
                                }
                                actionList.add("Change Icon")
                                val customIconFile = java.io.File(context.filesDir, "custom_icons/${item.id.replace(Regex("[^a-zA-Z0-9.-]"), "_")}.webp")
                                if (customIconFile.exists()) {
                                    actionList.add("Reset Icon")
                                }
                                actionList.add("Remove")

                                var popupWindow: PopupWindow? = null
                                val popupLayout = LinearLayout(context).apply {
                                    orientation = LinearLayout.VERTICAL
                                    val pad = (8 * context.resources.displayMetrics.density).toInt()
                                    setPadding(pad, pad, pad, pad)
                                }

                                actionList.forEach { action ->
                                    val actionView = TextView(context).apply {
                                        text = action
                                        setTextColor(Color.WHITE)
                                        setPadding(0, (12 * context.resources.displayMetrics.density).toInt(), 0, (12 * context.resources.displayMetrics.density).toInt())
                                        gravity = Gravity.CENTER
                                        
                                        val shape = android.graphics.drawable.GradientDrawable()
                                        shape.cornerRadius = 8 * context.resources.displayMetrics.density
                                        shape.setColor(Color.parseColor("#333333"))
                                        shape.setStroke(1, Color.LTGRAY)
                                        background = shape
                                        
                                        layoutParams = LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.WRAP_CONTENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT
                                        ).apply {
                                            setMargins(0, 0, 0, (8 * context.resources.displayMetrics.density).toInt())
                                        }
                                        
                                        setOnClickListener {
                                            popupWindow?.dismiss()
                                            when (action) {
                                                "Remove" -> {
                                                    // Need to remove from items and save
                                                    val newItems = items.toMutableList()
                                                    newItems.removeAll { it.id == item.id }
                                                    saveWidgetItems(newItems)
                                                    // Trigger reload
                                                    context.sendBroadcast(Intent("ELEMENT_ADDED_TO_HYBRID").apply { putExtra("PAGE_ID", pageId) })
                                                }
                                                "Change Icon" -> {
                                                    val intent = Intent(context, com.example.IconPickerActivity::class.java).apply {
                                                        putExtra("item_id", item.id)
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    context.startActivity(intent)
                                                }
                                                "Reset Icon" -> {
                                                    val file = java.io.File(context.filesDir, "custom_icons/${item.id.replace(Regex("[^a-zA-Z0-9.-]"), "_")}.webp")
                                                    if (file.exists()) file.delete()
                                                    appsManager.iconCache.remove("custom_${item.id}")
                                                    appsManager.iconCache.remove(item.id)
                                                    context.sendBroadcast(Intent("com.example.UPDATE_SIDEBAR_ICONS").apply {
                                                        putExtra("item_id", item.id)
                                                    })
                                                }
                                                "App Info" -> {
                                                    if (parsed is SidebarItem.App) {
                                                        try {
                                                            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                                            intent.data = android.net.Uri.parse("package:${parsed.packageName}")
                                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                            context.startActivity(intent)
                                                        } catch (e: Exception) {}
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    popupLayout.addView(actionView)
                                }
                                
                                popupLayout.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                                popupWindow = PopupWindow(
                                    popupLayout,
                                    (150 * context.resources.displayMetrics.density).toInt(),
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    true
                                ).apply {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                        windowLayoutType = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                    } else {
                                        @Suppress("DEPRECATION")
                                        windowLayoutType = android.view.WindowManager.LayoutParams.TYPE_PHONE
                                    }
                                    setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                                    isOutsideTouchable = true
                                }
                                val location = IntArray(2)
                                elementView.getLocationOnScreen(location)
                                val x = location[0]
                                var y = location[1] - popupLayout.measuredHeight
                                if (y < 0) y = location[1] + elementView.height
                                popupWindow?.showAtLocation(elementView, Gravity.NO_GRAVITY, x, y)
                                true
                            }
                            
                            val wCols = minOf(item.cols, totalCols)
                            val wRows = item.rows
                            
                            val params = FrameLayout.LayoutParams(cellWidth * wCols, cellHeight * wRows).apply {
                                leftMargin = item.x * cellWidth
                                topMargin = item.y * cellHeight
                            }
                            gridLayout.addView(elementView, params)
                            maxHeight = max(maxHeight, item.y * cellHeight + cellHeight * wRows)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            gridLayout.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, maxHeight)
            onHeightChanged(getCurrentHeightPx())
    }

    private fun showFolderPopup(anchor: View, folder: SidebarItem.Folder, appsManager: SidebarAppsManager) {
        val density = context.resources.displayMetrics.density
        val popupView = FrameLayout(context)
        val recyclerView = RecyclerView(context)
        val padding = (16 * density).toInt()
        recyclerView.setPadding(padding, padding, padding, padding)
        popupView.addView(recyclerView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val maxCols = if (folder.popupColumns > 0) folder.popupColumns else prefs.getInt("sidebar_columns", 3)
        val columns = if (folder.items.size <= maxCols && folder.items.isNotEmpty()) folder.items.size else maxCols
        val validCols = if (columns > 0) columns else 1
        
        recyclerView.layoutManager = GridLayoutManager(context, validCols)

        val popupOpacity = prefs.getFloat("handle_${containerId}_sidebar_transparency", prefs.getFloat("sidebar_transparency", 0.9f))
        val colorHex = prefs.getString("handle_${containerId}_sidebar_color", prefs.getString("sidebar_color", "#1E1E2E")) ?: "#1E1E2E"
        val baseColor = try { Color.parseColor(colorHex) } catch(e: Exception) { Color.parseColor("#1E1E2E") }
        val alphaInt = (popupOpacity * 255).toInt().coerceIn(0, 255)
        val r = Color.red(baseColor)
        val g = Color.green(baseColor)
        val b = Color.blue(baseColor)

        val popupBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(Color.argb(alphaInt, r, g, b))
            setStroke((1 * density).toInt(), Color.argb(80, 255, 255, 255))
            cornerRadius = 16 * density
        }
        popupView.background = popupBg

        val itemWidthDp = 72
        val itemHeightDp = 72
        val autoRows = Math.ceil(folder.items.size.toDouble() / validCols).toInt()
        val rows = if (folder.popupRows > 0) kotlin.math.min(folder.popupRows, autoRows) else autoRows
        val displayRows = if (folder.popupRows > 0) folder.popupRows else rows

        val totalWidth = (validCols * itemWidthDp * density + padding * 2).toInt()
        val totalHeight = (displayRows * itemHeightDp * density + padding * 2).toInt()
        
        popupView.layoutParams = ViewGroup.LayoutParams(totalWidth, totalHeight)

        var popupWindow: PopupWindow? = null
        
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = android.view.LayoutInflater.from(context).inflate(com.example.R.layout.item_sidebar_app, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }
            override fun getItemCount() = folder.items.size
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val itemId = folder.items[position]
                val parsed = appsManager.parseId(itemId) ?: return
                val icon = holder.itemView.findViewById<android.widget.ImageView>(com.example.R.id.app_icon)
                val label = holder.itemView.findViewById<android.widget.TextView>(com.example.R.id.app_label)
                label.text = parsed.label
                
                appsManager.bindIcon(itemId, icon, prefs, scope) {}
                
                holder.itemView.setOnClickListener {
                    when (parsed) {
                        is SidebarItem.App -> {
                            val intent = context.packageManager.getLaunchIntentForPackage(parsed.packageName)
                            if (intent != null) {
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                try {
                                    com.example.core.LogKeeper.writeLog("HybridGrid", "Launch folder app: ${parsed.packageName}")
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    com.example.core.LogKeeper.writeLog("HybridGrid", "Error launching folder app: ${e.message}")
                                }
                                popupWindow?.dismiss()
                            }
                        }
                        is SidebarItem.PageWindow -> {
                            com.example.core.LogKeeper.writeLog("HybridGrid", "Toggle folder page window: ${parsed.pageType}")
                            com.example.feature.miniapps.MiniAppManager.toggleApp(context, parsed.pageType)
                            popupWindow?.dismiss()
                        }
                        is SidebarItem.Link -> {
                            try {
                                com.example.core.LogKeeper.writeLog("HybridGrid", "Open folder link: ${parsed.url}")
                                val intent = if (parsed.url.startsWith("intent:")) {
                                    Intent.parseUri(parsed.url, Intent.URI_INTENT_SCHEME)
                                } else {
                                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(parsed.url))
                                }
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                com.example.core.LogKeeper.writeLog("HybridGrid", "Error opening folder link: ${e.message}")
                            }
                            popupWindow?.dismiss()
                        }
                        is SidebarItem.QuickTile -> {
                            com.example.core.LogKeeper.writeLog("HybridGrid", "Folder QuickTile: ${parsed.action}")
                            QuickTileHandler.handleQuickTileAction(context, parsed.action)
                            popupWindow?.dismiss()
                        }
                        is SidebarItem.IntentAction -> {
                            try {
                                com.example.core.LogKeeper.writeLog("HybridGrid", "Folder IntentAction: ${parsed.label}")
                                val intent = Intent.parseUri(parsed.uri, Intent.URI_INTENT_SCHEME)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                com.example.core.LogKeeper.writeLog("HybridGrid", "Error folder intent: ${e.message}")
                            }
                            popupWindow?.dismiss()
                        }
                        is SidebarItem.SystemAction -> {
                            if (parsed.action == "screen_record") {
                                val intent = Intent(context, ScreenRecordActivity::class.java)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } else {
                                val intent = Intent(context, com.example.service.SidebarService::class.java)
                                intent.action = "EXECUTE_ACTION"
                                intent.putExtra("ACTION_ID", "system:" + parsed.action)
                                context.startService(intent)
                            }
                            popupWindow?.dismiss()
                        }
                        is SidebarItem.VolumeAction -> {
                            try {
                                com.example.core.LogKeeper.writeLog("HybridGrid", "Folder Volume action: ${parsed.stream}_${parsed.action}")
                                MediaVolumeHandler.handleVolumeAction(context, parsed.stream, parsed.action)
                            } catch (e: Exception) {
                                com.example.core.LogKeeper.writeLog("HybridGrid", "Folder Volume action err: ${e.message}")
                            }
                            popupWindow?.dismiss()
                        }
                        is SidebarItem.MediaAction -> {
                            try {
                                com.example.core.LogKeeper.writeLog("HybridGrid", "Folder Media action: ${parsed.action}")
                                MediaVolumeHandler.handleMediaAction(context, parsed.action)
                            } catch (e: Exception) {
                                com.example.core.LogKeeper.writeLog("HybridGrid", "Folder Media action err: ${e.message}")
                            }
                            popupWindow?.dismiss()
                        }
                        is SidebarItem.DisplayAction -> {
                            try {
                                com.example.core.LogKeeper.writeLog("HybridGrid", "Folder Display action: ${parsed.action}")
                                DisplayHandler.handleDisplayAction(context, parsed.action)
                            } catch (e: Exception) {
                                com.example.core.LogKeeper.writeLog("HybridGrid", "Folder Display action err: ${e.message}")
                            }
                            popupWindow?.dismiss()
                        }
                        is SidebarItem.SettingsShortcut -> {
                            val settingsIntent = when (parsed.action) {
                                "wifi" -> Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                                "bluetooth" -> Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                                "display" -> Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)
                                "sound" -> Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)
                                "location" -> Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                "apps" -> Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS)
                                "security" -> Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                                "battery" -> Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)
                                "date" -> Intent(android.provider.Settings.ACTION_DATE_SETTINGS)
                                else -> Intent(android.provider.Settings.ACTION_SETTINGS)
                            }
                            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            try {
                                com.example.core.LogKeeper.writeLog("HybridGrid", "Folder Settings shortcut: ${parsed.action}")
                                context.startActivity(settingsIntent)
                            } catch (e: Exception) {
                                com.example.core.LogKeeper.writeLog("HybridGrid", "Folder Settings shortcut err: ${e.message}")
                            }
                            popupWindow?.dismiss()
                        }
                        else -> {}
                    }
                }
                
                holder.itemView.setOnLongClickListener {
                    val actionList = mutableListOf<String>()
                    if (parsed is SidebarItem.App) {
                        actionList.add("App Info")
                    }
                    actionList.add("Change Icon")
                    val customIconFile = java.io.File(context.filesDir, "custom_icons/${itemId.replace(Regex("[^a-zA-Z0-9.-]"), "_")}.webp")
                    if (customIconFile.exists()) {
                        actionList.add("Reset Icon")
                    }
                    actionList.add("Remove")
                    
                    var actionMenuPopup: PopupWindow? = null
                    val popupLayout = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        val pad = (8 * density).toInt()
                        setPadding(pad, pad, pad, pad)
                    }
                    
                    actionList.forEach { action ->
                        val actionView = TextView(context).apply {
                            text = action
                            setTextColor(Color.BLACK)
                            val padV = (10 * density).toInt()
                            val padH = (16 * density).toInt()
                            setPadding(padH, padV, padH, padV)
                            textSize = 14f
                            
                            val shape = android.graphics.drawable.GradientDrawable()
                            shape.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                            shape.cornerRadius = 24f * density
                            shape.setColor(Color.WHITE)
                            shape.setStroke(1, Color.LTGRAY)
                            background = shape
                            
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                setMargins(0, 0, 0, (8 * density).toInt())
                            }
                            
                            setOnClickListener {
                                actionMenuPopup?.dismiss()
                                when (action) {
                                    "Remove" -> {
                                        appsManager.removeItem(itemId)
                                        popupWindow?.dismiss()
                                    }
                                    "Change Icon" -> {
                                        val intent = Intent(context, com.example.IconPickerActivity::class.java).apply {
                                            putExtra("item_id", itemId)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                        popupWindow?.dismiss()
                                    }
                                    "Reset Icon" -> {
                                        val file = java.io.File(context.filesDir, "custom_icons/${itemId.replace(Regex("[^a-zA-Z0-9.-]"), "_")}.webp")
                                        if (file.exists()) file.delete()
                                        appsManager.iconCache.remove("custom_${itemId}")
                                        appsManager.iconCache.remove(itemId)
                                        context.sendBroadcast(Intent("com.example.UPDATE_SIDEBAR_ICONS").apply {
                                            putExtra("item_id", itemId)
                                        })
                                        popupWindow?.dismiss()
                                    }
                                    "App Info" -> {
                                        if (parsed is SidebarItem.App) {
                                            try {
                                                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                                intent.data = android.net.Uri.parse("package:${parsed.packageName}")
                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(intent)
                                                popupWindow?.dismiss()
                                            } catch (e: Exception) {}
                                        }
                                    }
                                }
                            }
                        }
                        popupLayout.addView(actionView)
                    }
                    
                    actionMenuPopup = PopupWindow(popupLayout, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            windowLayoutType = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        } else {
                            @Suppress("DEPRECATION")
                            windowLayoutType = android.view.WindowManager.LayoutParams.TYPE_PHONE
                        }
                        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                        elevation = 8f * density
                    }
                    
                    val loc = IntArray(2)
                    holder.itemView.getLocationOnScreen(loc)
                    actionMenuPopup?.showAtLocation(holder.itemView, Gravity.NO_GRAVITY, loc[0] + holder.itemView.width / 4, loc[1] + holder.itemView.height / 2)
                    
                    true
                }
            }
        }
        recyclerView.adapter = adapter

        popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                windowLayoutType = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                windowLayoutType = android.view.WindowManager.LayoutParams.TYPE_PHONE
            }
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            setOnDismissListener {
                onDimSidebar?.invoke(false)
            }
        }

        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val anchorX = location[0]
        val anchorY = location[1]
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels

        var x = anchorX
        if (anchorX > screenWidth / 2) {
            x = anchorX - totalWidth
        } else {
            x = anchorX + anchor.width
        }

        var y = anchorY - (totalHeight / 2) + (anchor.height / 2)
        if (y < 0) y = 0
        if (y + totalHeight > screenHeight) y = screenHeight - totalHeight

        onDimSidebar?.invoke(true)
        popupWindow.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
    }

    private fun showWidgetPopup(anchor: View, widget: SidebarItem.PopupWidget) {
        val density = context.resources.displayMetrics.density
        val popupView = FrameLayout(context)
        
        val padding = (8 * density).toInt()
        popupView.setPadding(padding, padding, padding, padding)

        val popupOpacity = prefs.getFloat("handle_${containerId}_sidebar_transparency", prefs.getFloat("sidebar_transparency", 0.9f))
        val colorHex = prefs.getString("handle_${containerId}_sidebar_color", prefs.getString("sidebar_color", "#1E1E2E")) ?: "#1E1E2E"
        val baseColor = try { Color.parseColor(colorHex) } catch(e: Exception) { Color.parseColor("#1E1E2E") }
        val alphaInt = (popupOpacity * 255).toInt().coerceIn(0, 255)
        val r = Color.red(baseColor)
        val g = Color.green(baseColor)
        val b = Color.blue(baseColor)

        val popupBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(Color.argb(alphaInt, r, g, b))
            setStroke((1 * density).toInt(), Color.argb(80, 255, 255, 255))
            cornerRadius = 16 * density
        }
        popupView.background = popupBg
        
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val host = com.example.core.AppWidgetHelper.getHost(context)
        val info = appWidgetManager.getAppWidgetInfo(widget.widgetId)
        
        var popupWindow: PopupWindow? = null
        if (info != null) {
            val hostView = host.createView(context, widget.widgetId, info)
            hostView.setPadding(0, 0, 0, 0)
            
            val minW = info.minWidth
            val minH = info.minHeight
            val w = if (minW > 0) minW else (200 * density).toInt()
            val h = if (minH > 0) minH else (200 * density).toInt()
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
                hostView.updateAppWidgetSize(null, (w / density).toInt(), (h / density).toInt(), (w / density).toInt(), (h / density).toInt())
            }
            
            val params = FrameLayout.LayoutParams(w, h)
            popupView.addView(hostView, params)
        }

        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val totalWidth = popupView.measuredWidth
        val totalHeight = popupView.measuredHeight

        popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                windowLayoutType = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                windowLayoutType = android.view.WindowManager.LayoutParams.TYPE_PHONE
            }
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            setOnDismissListener {
                onDimSidebar?.invoke(false)
            }
        }
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val anchorX = location[0]
        val anchorY = location[1]
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        var x = anchorX
        if (anchorX > screenWidth / 2) {
            x = anchorX - totalWidth
        } else {
            x = anchorX + anchor.width
        }
        var y = anchorY - (totalHeight / 2) + (anchor.height / 2)
        if (y < 0) y = 0
        if (y + totalHeight > screenHeight) y = screenHeight - totalHeight
        onDimSidebar?.invoke(true)
        popupWindow.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
    }

    override fun onEditClicked() {
        val intent = android.content.Intent(context, com.example.HybridGridEditActivity::class.java).apply {
            putExtra("PAGE_ID", pageId)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        context.sendBroadcast(android.content.Intent("com.example.CLOSE_SIDEBAR"))
    }
}
