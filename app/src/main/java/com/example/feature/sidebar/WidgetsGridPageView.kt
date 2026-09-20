package com.example.feature.sidebar
import com.example.feature.system_hub.VianSideAccessibilityService
import com.example.core.AppWidgetHelper
import com.example.feature.system_hub.DisplayHandler
import com.example.feature.system_hub.MediaVolumeHandler
import com.example.feature.system_hub.QuickTileHandler

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
        clipChildren = false
        clipToPadding = false
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
        clipChildren = false
        clipToPadding = false
        val scrollView = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            clipChildren = false
            clipToPadding = false
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
        if (items.none { it.id == "widget:$widgetId" || it.id.startsWith("widget:$widgetId:") }) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val info = appWidgetManager.getAppWidgetInfo(widgetId)
            val totalCols = prefs.getInt("widgets_grid_cols_$pageId", 4)
            val density = context.resources.displayMetrics.density
            val cellW = if (width > 0) width / totalCols else (60 * density).toInt()
            val (cols, rows) = com.example.calculateWidgetSpan(info, totalCols, cellW, density)
            var targetY = 0
            for (it in items) {
                targetY = maxOf(targetY, it.y + it.rows)
            }
            items.add(GridWidgetItem("widget:$widgetId", cols, rows, 0, targetY))
            saveWidgetItems(items)
            com.example.core.LogKeeper.writeLog("WidgetsGrid", "Added widget:$widgetId (${cols}x${rows}) to page $pageId")
        }
    }
    
    private fun addElementIdToPrefs(elementId: String) {
        val items = getWidgetItems().toMutableList()
        if (items.none { it.id == elementId }) {
            var cols = 1
            var rows = 1
            if (elementId.startsWith("widget:")) {
                val wId = elementId.removePrefix("widget:").substringBefore(":").toIntOrNull()
                try {
                    val parts = elementId.split(":", limit = 3)
                    if (parts.size >= 3) {
                        val json = JSONObject(parts[2])
                        if (json.has("cols")) cols = json.getInt("cols")
                        if (json.has("rows")) rows = json.getInt("rows")
                    }
                } catch (e: Exception) {}
                if (wId != null && (cols <= 1 || rows <= 1)) {
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    val info = appWidgetManager.getAppWidgetInfo(wId)
                    val totalCols = prefs.getInt("widgets_grid_cols_$pageId", 4)
                    val density = context.resources.displayMetrics.density
                    val cellW = if (width > 0) width / totalCols else (60 * density).toInt()
                    val (calcCols, calcRows) = com.example.calculateWidgetSpan(info, totalCols, cellW, density)
                    cols = maxOf(cols, calcCols)
                    rows = maxOf(rows, calcRows)
                }
            }
            var targetY = 0
            for (it in items) {
                targetY = maxOf(targetY, it.y + it.rows)
            }
            items.add(GridWidgetItem(elementId, cols, rows, 0, targetY))
            saveWidgetItems(items)
            com.example.core.LogKeeper.writeLog("WidgetsGrid", "Added element:$elementId (${cols}x${rows}) to page $pageId")
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
                            hostView.clipChildren = false
                            hostView.clipToPadding = false
                            
                            val density = context.resources.displayMetrics.density
                            val cellWidthDp = if (cellWidth > 0) (cellWidth / density).toInt() else 60
                            val cellHeightDp = if (cellHeight > 0) (cellHeight / density).toInt() else 60
                            
                            val minWdp = if (android.os.Build.VERSION.SDK_INT >= 16 && info.minResizeWidth > 0) {
                                minOf(info.minWidth, info.minResizeWidth)
                            } else {
                                info.minWidth
                            }
                            val minHdp = if (android.os.Build.VERSION.SDK_INT >= 16 && info.minResizeHeight > 0) {
                                minOf(info.minHeight, info.minResizeHeight)
                            } else {
                                info.minHeight
                            }
                            val reqCols = if (cellWidthDp > 0 && minWdp > 0) Math.ceil(minWdp.toDouble() / cellWidthDp).toInt() else 1
                            val reqRows = if (cellHeightDp > 0 && minHdp > 0) Math.ceil(minHdp.toDouble() / cellHeightDp).toInt() else 1
                            
                            val wCols = minOf(totalCols, maxOf(item.cols, reqCols))
                            val wRows = maxOf(item.rows, reqRows)
                            
                            val widgetWidth = cellWidth * wCols
                            val widgetHeight = cellHeight * wRows
                            
                            val params = FrameLayout.LayoutParams(widgetWidth, widgetHeight).apply {
                                leftMargin = item.x * cellWidth
                                topMargin = item.y * cellHeight
                            }
                            gridLayout.addView(hostView, params)
                            
                            hostView.post {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
                                    val curDensity = context.resources.displayMetrics.density
                                    val wDp = if (hostView.width > 0) (hostView.width / curDensity).toInt() else (widgetWidth / curDensity).toInt()
                                    val hDp = if (hostView.height > 0) (hostView.height / curDensity).toInt() else (widgetHeight / curDensity).toInt()
                                    hostView.updateAppWidgetSize(null, wDp, hDp, wDp, hDp)
                                }
                            }
                            maxHeight = max(maxHeight, item.y * cellHeight + widgetHeight)
                            
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
                                    com.example.feature.sidebar.SidebarManager.getInstance(context).openContainerById(parsed.targetId)
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
