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
import android.widget.FrameLayout
import android.widget.GridLayout
import kotlin.math.max
import android.widget.ScrollView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.LinearLayout

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class GridWidgetItem(
    val id: String,
    var cols: Int = 2,
    var rows: Int = 2,
    var x: Int = 0,
    var y: Int = 0
)

class WidgetsGridPageView(
    context: Context,
    private val pageId: String,
    private val scope: CoroutineScope,
    private val onHeightChanged: (Int) -> Unit
) : FrameLayout(context), SidebarPageControllable {

    private val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)

    private val appsManager = SidebarAppsManager(context, prefs, scope, "wg_${pageId}") {
        post { loadWidgets() }
    }

    private val gridLayout = FrameLayout(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "WIDGET_ADDED_TO_GRID" || intent?.action == "UPDATE_GRID") {
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
            }
        }
    }

    init {
        appsManager.ensureLoaded()
        com.example.core.LogKeeper.writeLog("WidgetsGrid", "Opened widgets grid page")
        val scrollView = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
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
        filter.addAction("WIDGET_ADDED_TO_GRID")
        filter.addAction("UPDATE_GRID")
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {}
    }

    private fun getWidgetItems(): List<GridWidgetItem> {
        val jsonStr = prefs.getString("widgets_grid_$pageId", "[]") ?: "[]"
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
                        obj.optInt("cols", 2),
                        obj.optInt("rows", 2),
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
        prefs.edit().putString("widgets_grid_$pageId", arr.toString()).apply()
    }

    private fun addWidgetIdToPrefs(widgetId: Int) {
        val items = getWidgetItems().toMutableList()
        if (items.none { it.id == "widget:$widgetId" }) {
            // Default size 2x2
            items.add(GridWidgetItem("widget:$widgetId", 2, 2, 0, 0))
            saveWidgetItems(items)
            com.example.core.LogKeeper.writeLog("WidgetsGrid", "Added widget:$widgetId to page $pageId")
        }
    }
    
    private fun addElementIdToPrefs(elementId: String) {
        val items = getWidgetItems().toMutableList()
        if (items.none { it.id == elementId }) {
            // Default size 1x1 for elements
            items.add(GridWidgetItem(elementId, 1, 1, 0, 0))
            saveWidgetItems(items)
            com.example.core.LogKeeper.writeLog("WidgetsGrid", "Added element:$elementId to page $pageId")
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

    private fun loadWidgets() {
        if (width == 0) {
            return
        }
        gridLayout.removeAllViews()
        val totalCols = prefs.getInt("widgets_grid_cols_$pageId", 4)
        
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
                    text = "Empty Widget Grid\nTap to add widgets"
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
                                                    context.sendBroadcast(Intent("WIDGET_ADDED_TO_GRID").apply { putExtra("PAGE_ID", pageId) })
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
                            
                            label.text = parsed.label
                            
                            scope.launch {
                                val bmp = appsManager.getIconBitmap(item.id)
                                if (bmp != null) {
                                    icon.setImageBitmap(bmp)
                                }
                            }
                            
                            elementView.setOnClickListener {
                                if (parsed is SidebarItem.App) {
                                    val intent = context.packageManager.getLaunchIntentForPackage(parsed.packageName)
                                    if (intent != null) {
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        try { context.startActivity(intent) } catch (e: Exception) {}
                                    }
                                } else if (parsed is SidebarItem.FloatingTrigger) {
                                    val intent = Intent(context, FloatingTriggerService::class.java)
                                    intent.putExtra("TARGET_ID", parsed.targetId)
                                    context.startService(intent)
                                } else if (parsed is SidebarItem.Link) {
                                    try {
                                        val intent = if (parsed.url.startsWith("intent:")) {
                                            Intent.parseUri(parsed.url, Intent.URI_INTENT_SCHEME)
                                        } else {
                                            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(parsed.url))
                                        }
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(intent)
                                    } catch (e: Exception) {}
                                } else if (parsed is SidebarItem.QuickTile) {
                                    QuickTileHandler.handleQuickTileAction(context, parsed.action)
                                } else if (parsed is SidebarItem.IntentAction) {
                                    try {
                                        val intent = Intent.parseUri(parsed.uri, Intent.URI_INTENT_SCHEME)
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(intent)
                                    } catch (e: Exception) {}
                                }
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

    override fun onEditClicked() {
        val intent = android.content.Intent(context, com.example.WidgetsGridEditActivity::class.java).apply {
            putExtra("PAGE_ID", pageId)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        context.sendBroadcast(android.content.Intent("com.example.CLOSE_SIDEBAR"))
    }
}
