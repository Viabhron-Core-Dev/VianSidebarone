package com.example.feature.sidebar
import com.example.LogKeeperActivity
import com.example.feature.miniapps.reader.FloatingReaderService
import com.example.feature.system_hub.VianSideAccessibilityService
import com.example.core.AppWidgetHelper
import com.example.feature.system_hub.DisplayHandler
import com.example.feature.system_hub.MediaVolumeHandler
import com.example.feature.system_hub.QuickTileHandler
import com.example.service.FloatingTriggerService

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("ViewConstructor")
class AppsPageView(
    context: Context,
    private val handleId: String,
    val pageConfig: com.example.utils.SidebarPage?,
    private val manager: SidebarAppsManager,
    private val serviceScope: CoroutineScope,
    private val onCloseSidebar: () -> Unit,
    private val onDimSidebar: ((Boolean) -> Unit)? = null,
    private val onHeightChanged: ((Int) -> Unit)? = null,
    private val onEditModeClicked: (() -> Unit)? = null
) : FrameLayout(context), SidebarPageControllable {

    private val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)

    private var columns: Int = 3
    private val recyclerView: RecyclerView
    private val adapter: AppsAdapter

    private var displayedItems = listOf<SidebarItem>()
    private val expandedFolders = mutableSetOf<String>()
    
    // Track measured height
    private var lastCalculatedHeightPx = 0
    
    fun getCurrentHeightPx(): Int {
        if (lastCalculatedHeightPx == 0) {
            calculateAndDispatchHeight()
        }
        return lastCalculatedHeightPx
    }

    private var emptyView: android.widget.LinearLayout

    init {
        val density = context.resources.displayMetrics.density
        val c = prefs.getInt("handle_${handleId}_page_${pageConfig?.id}_columns", -1)
        val defaultCols = prefs.getInt("handle_${handleId}_columns", prefs.getInt("sidebar_columns", 3))
        columns = if (pageConfig?.useCustomSettings == true) pageConfig.gridColumns else (if (c != -1) c else defaultCols)

        adapter = AppsAdapter(displayedItems)

        recyclerView = RecyclerView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = 0
            }
            layoutManager = GridLayoutManager(context, columns).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        return if (this@AppsPageView.adapter.getItemViewType(position) == 1) columns else 1
                    }
                }
            }
            setHasFixedSize(true)
            setItemViewCacheSize(20)
        }

        emptyView = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (120 * density).toInt())
            
            val tv = android.widget.TextView(context).apply {
                text = "Empty Apps Grid\nTap to add apps"
                setTextColor(android.graphics.Color.LTGRAY)
                textSize = 13f
                gravity = android.view.Gravity.CENTER
            }
            addView(tv)
            setOnClickListener {
                onEditClicked()
            }
            visibility = android.view.View.GONE
        }

        recyclerView.adapter = adapter

        addView(recyclerView)
        addView(emptyView)
    }

    private val onManagerUpdatedListener: () -> Unit = {
        post {
            val c = prefs.getInt("handle_${handleId}_page_${pageConfig?.id}_columns", -1)
            val defaultCols = prefs.getInt("handle_${handleId}_columns", prefs.getInt("sidebar_columns", 3))
            val updatedCols = if (pageConfig?.useCustomSettings == true) pageConfig.gridColumns else (if (c != -1) c else defaultCols)
            if (updatedCols != columns && updatedCols > 0) {
                columns = updatedCols
                (recyclerView.layoutManager as? GridLayoutManager)?.spanCount = columns
            }
            updateData(manager.activeItems)
        }
    }

    private var sourceApps = listOf<SidebarItem>()

    fun updateData(apps: List<SidebarItem>) {
        sourceApps = apps
        refreshList()
    }

    private fun refreshList() {
        val flatList = mutableListOf<SidebarItem>()
        for (item in sourceApps) {
            flatList.add(item)
            if (item is SidebarItem.Folder && expandedFolders.contains(item.id)) {
                for (itemId in item.items) {
                    val parsedItem = manager.parseId(itemId)
                    if (parsedItem != null) {
                        flatList.add(parsedItem)
                    }
                }
            }
        }
        displayedItems = flatList
        adapter.updateItems(flatList)
        
        if (flatList.isEmpty()) {
            emptyView.visibility = android.view.View.VISIBLE
            recyclerView.visibility = android.view.View.GONE
        } else {
            emptyView.visibility = android.view.View.GONE
            recyclerView.visibility = android.view.View.VISIBLE
        }

        calculateAndDispatchHeight()
    }

    private fun calculateAndDispatchHeight() {
        var gridHeightDp = 0
        var currentSpan = 0
        
        if (displayedItems.isEmpty()) {
            gridHeightDp = 120
        } else {
            for (item in displayedItems) {
                if (item is SidebarItem.Spacer) {
                    if (currentSpan > 0) {
                        gridHeightDp += 72 // end current row
                        currentSpan = 0
                    }
                    gridHeightDp += item.heightDp
                } else {
                    currentSpan += 1
                    if (currentSpan == columns) {
                        gridHeightDp += 72 // 56dp per normal row
                        currentSpan = 0
                    }
                }
            }
            if (currentSpan > 0) {
                gridHeightDp += 72 // partial row
            }
        }
        
        val density = context.resources.displayMetrics.density
        val totalHeightPx = (gridHeightDp * density).toInt()
        
        if (totalHeightPx != lastCalculatedHeightPx) {
            lastCalculatedHeightPx = totalHeightPx
            onHeightChanged?.invoke(totalHeightPx)
        }
    }

    private var currentFolderPopup: android.widget.PopupWindow? = null


    private fun showWidgetPopup(anchor: View, widgetId: Int) {
        currentFolderPopup?.dismiss()
        val density = context.resources.displayMetrics.density
        val popupView = FrameLayout(context)
        
        try {
            val appWidgetManager = android.appwidget.AppWidgetManager.getInstance(context)
            val appWidgetInfo = appWidgetManager.getAppWidgetInfo(widgetId)
            
            if (appWidgetInfo != null) {
                val widgetView = com.example.core.AppWidgetHelper.getHost(context).createView(context, widgetId, appWidgetInfo)
                
                val padding = (12 * density).toInt()
                popupView.setPadding(padding, padding, padding, padding)
                
                val popupOpacity = if (pageConfig?.useCustomSettings == true) pageConfig.transparency else prefs.getFloat("handle_${handleId}_sidebar_transparency", prefs.getFloat("sidebar_transparency", 0.9f))
                val colorHex = prefs.getString("handle_${handleId}_sidebar_color", prefs.getString("sidebar_color", "#1E1E2E")) ?: "#1E1E2E"
                val baseColor = try { android.graphics.Color.parseColor(colorHex) } catch(e: Exception) { android.graphics.Color.parseColor("#1E1E2E") }
                val alphaInt = (popupOpacity * 255).toInt().coerceIn(0, 255)
                val r = android.graphics.Color.red(baseColor)
                val g = android.graphics.Color.green(baseColor)
                val b = android.graphics.Color.blue(baseColor)

                val popupBg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    setColor(android.graphics.Color.argb(alphaInt, r, g, b))
                    setStroke((1 * density).toInt(), android.graphics.Color.argb(80, 255, 255, 255))
                    cornerRadius = 16 * density
                }
                popupView.background = popupBg
                
                // Allow widget to determine its own size, but set a min size based on provider info
                val minWidth = (appWidgetInfo.minWidth * density).toInt()
                val minHeight = (appWidgetInfo.minHeight * density).toInt()
                
                widgetView.minimumWidth = minWidth
                widgetView.minimumHeight = minHeight
                
                popupView.addView(widgetView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
                
                val popupWindow = android.widget.PopupWindow(
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
                    setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    isOutsideTouchable = true
                    setOnDismissListener {
                        onDimSidebar?.invoke(false)
                    }
                }
                
                currentFolderPopup = popupWindow
                
                val location = IntArray(2)
                anchor.getLocationOnScreen(location)
                val anchorX = location[0]
                val anchorY = location[1]
                val screenWidth = context.resources.displayMetrics.widthPixels
                
                popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                val popupWidth = popupView.measuredWidth
                val popupHeight = popupView.measuredHeight
                
                var x = anchorX
                if (anchorX > screenWidth / 2) {
                    x = anchorX - popupWidth
                } else {
                    x = anchorX + anchor.width
                }
                
                var y = anchorY
                val screenHeight = context.resources.displayMetrics.heightPixels
                if (y + popupHeight > screenHeight) {
                    y = screenHeight - popupHeight
                }
                if (y < 0) y = 0
                
                onDimSidebar?.invoke(true)
                popupWindow.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY, x, y)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showFolderPopup(anchor: View, folder: SidebarItem.Folder) {
        currentFolderPopup?.dismiss()
        
        com.example.core.LogKeeper.writeLog("Sidebar", "Folder opened: ${folder.label}")
        
        val density = context.resources.displayMetrics.density
        val popupView = FrameLayout(context)
        val recyclerView = RecyclerView(context)
        val padding = (16 * density).toInt()
        recyclerView.setPadding(padding, padding, padding, padding)
        popupView.addView(recyclerView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        
        val folderItems = mutableListOf<SidebarItem>()
        for (itemId in folder.items) {
            val parsedItem = manager.parseId(itemId)
            if (parsedItem != null) {
                folderItems.add(parsedItem)
            }
        }
        
        val maxCols = if (folder.popupColumns > 0) folder.popupColumns else (if (pageConfig?.useCustomSettings == true) pageConfig.gridColumns else prefs.getInt("handle_${handleId}_columns", prefs.getInt("sidebar_columns", 3)))
        val columns = if (folderItems.size <= maxCols && folderItems.isNotEmpty()) folderItems.size else maxCols
        val validCols = if (columns > 0) columns else 1
        
        recyclerView.layoutManager = GridLayoutManager(context, validCols)
        val popupAdapter = AppsAdapter(folderItems)
        recyclerView.adapter = popupAdapter
        
        val popupOpacity = if (pageConfig?.useCustomSettings == true) pageConfig.transparency else prefs.getFloat("handle_${handleId}_sidebar_transparency", prefs.getFloat("sidebar_transparency", 0.9f))
        val colorHex = prefs.getString("handle_${handleId}_sidebar_color", prefs.getString("sidebar_color", "#1E1E2E")) ?: "#1E1E2E"
        val baseColor = try { android.graphics.Color.parseColor(colorHex) } catch(e: Exception) { android.graphics.Color.parseColor("#1E1E2E") }
        val alphaInt = (popupOpacity * 255).toInt().coerceIn(0, 255)
        val r = android.graphics.Color.red(baseColor)
        val g = android.graphics.Color.green(baseColor)
        val b = android.graphics.Color.blue(baseColor)

        val popupBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(android.graphics.Color.argb(alphaInt, r, g, b))
            setStroke((1 * density).toInt(), android.graphics.Color.argb(80, 255, 255, 255))
            cornerRadius = 16 * density
        }
        popupView.background = popupBg
        
        // Calculate exact size for compact wrap_content appearance
        val itemWidthDp = 72 // 44dp icon + 6dp padding on each side
        val itemHeightDp = 72
        val autoRows = Math.ceil(folderItems.size.toDouble() / validCols).toInt()
        val rows = if (folder.popupRows > 0) minOf(folder.popupRows, autoRows) else autoRows
        val displayRows = if (folder.popupRows > 0) folder.popupRows else rows
        
        val totalWidth = (validCols * itemWidthDp * density + padding * 2).toInt()
        val totalHeight = (displayRows * itemHeightDp * density + padding * 2).toInt()
        
        popupView.layoutParams = ViewGroup.LayoutParams(totalWidth, totalHeight)
        
        val popupWindow = android.widget.PopupWindow(
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
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
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
        currentFolderPopup = popupWindow
    }


    private val iconUpdateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            val itemId = intent.getStringExtra("item_id")
            if (itemId != null) {
                manager.iconCache.remove("custom_$itemId")
                manager.iconCache.remove(itemId)
                adapter.notifyDataSetChanged()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        manager.addUpdateListener(onManagerUpdatedListener)
        manager.ensureLoaded()
        updateData(manager.activeItems)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(iconUpdateReceiver, android.content.IntentFilter("com.example.UPDATE_SIDEBAR_ICONS"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(iconUpdateReceiver, android.content.IntentFilter("com.example.UPDATE_SIDEBAR_ICONS"))
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        manager.removeUpdateListener(onManagerUpdatedListener)
        try {
            context.unregisterReceiver(iconUpdateReceiver)
        } catch(e: Exception) {}
    }

    private inner class AppsAdapter(var items: List<SidebarItem>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        fun updateItems(newItems: List<SidebarItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return if (items[position] is SidebarItem.Spacer) 1 else 0
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 1) {
                val view = View(parent.context)
                return SpacerViewHolder(view)
            }
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_sidebar_app, parent, false)
            return AppViewHolder(view)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            if (holder is AppViewHolder) {
                holder.bind(item, position)
            } else if (holder is SpacerViewHolder && item is SidebarItem.Spacer) {
                holder.bind(item, position)
            }
        }
    }

    private inner class SpacerViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        fun bind(item: SidebarItem.Spacer, position: Int) {
            val density = view.context.resources.displayMetrics.density
            val lp = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, (item.heightDp * density).toInt())
            view.layoutParams = lp
            view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            
            view.setOnLongClickListener {
                manager.removeItem(item.id)
                true
            }
        }
    }

    private inner class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: android.widget.ImageView = view.findViewById(R.id.app_icon)
        val label: TextView = view.findViewById(R.id.app_label)
        
        fun bind(item: SidebarItem, position: Int) {
            label.text = item.label
            icon.setImageDrawable(null)
            icon.clearColorFilter()
            icon.setBackgroundColor(android.graphics.Color.DKGRAY)
            
            itemView.setOnClickListener {
                if (item is SidebarItem.App) {
                    val intent = context.packageManager.getLaunchIntentForPackage(item.packageName)
                    if (intent != null) {
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        currentFolderPopup?.dismiss()
                        onCloseSidebar()
                    }
                } else if (item is SidebarItem.FloatingTrigger) {
                    val intent = android.content.Intent(context, FloatingTriggerService::class.java)
                    intent.putExtra("TARGET_ID", item.targetId)
                    context.startService(intent)
                    currentFolderPopup?.dismiss()
                    onCloseSidebar()
                } else if (item is SidebarItem.Folder) {
                    showFolderPopup(itemView, item)
                } else if (item is SidebarItem.Link) {
                    try {
                        val intent = if (item.url.startsWith("intent:")) {
                            android.content.Intent.parseUri(item.url, android.content.Intent.URI_INTENT_SCHEME)
                        } else {
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(item.url))
                        }
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    currentFolderPopup?.dismiss()
                        onCloseSidebar()
                } else if (item is SidebarItem.Widget) {
                    showWidgetPopup(itemView, item.widgetId)
                    // Do not close sidebar, just show popup
                
                } else if (item is SidebarItem.QuickTile) {
                    QuickTileHandler.handleQuickTileAction(context, item.action)
                    currentFolderPopup?.dismiss()
                    onCloseSidebar()
                } else if (item is SidebarItem.IntentAction) {
                    try {
                        val intent = android.content.Intent.parseUri(item.uri, android.content.Intent.URI_INTENT_SCHEME)
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    currentFolderPopup?.dismiss()
                    onCloseSidebar()
                } else if (item is SidebarItem.SystemAction) {
                    if (item.action == "force_stop_running_apps") {
                        com.example.utils.AppTrackerHelper.startForceStopSequence(context)
                    } else if (item.action == "log_keeper") {
                        val intent = android.content.Intent(context, com.example.feature.settings.LogKeeperActivity::class.java)
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } else if (item.action == "dictionary_floating") {
                        val intent = android.content.Intent(context, com.example.service.SidebarService::class.java)
                        intent.action = "EXECUTE_ACTION"
                        intent.putExtra("ACTION_ID", "system:dictionary_floating")
                        context.startService(intent)
                    } else if (item.action == "translation_floating") {
                        val intent = android.content.Intent(context, com.example.service.SidebarService::class.java)
                        intent.action = "EXECUTE_ACTION"
                        intent.putExtra("ACTION_ID", "system:translation_floating")
                        context.startService(intent)
                    } else if (item.action == "hybrid_grid_floating") {
                        val intent = android.content.Intent(context, com.example.service.SidebarService::class.java)
                        intent.action = "EXECUTE_ACTION"
                        intent.putExtra("ACTION_ID", "system:hybrid_grid_floating")


                        context.startService(intent)
                    } else if (item.action == "dictionary_full") {
                        val intent = android.content.Intent(context, com.example.service.SidebarService::class.java)
                        intent.action = "EXECUTE_ACTION"
                        intent.putExtra("ACTION_ID", "system:dictionary_full")
                        context.startService(intent)
                    } else if (item.action == "work_notes") {
                        val intent = android.content.Intent(context, com.example.service.SidebarService::class.java)
                        intent.action = "EXECUTE_ACTION"
                        intent.putExtra("ACTION_ID", "system:work_notes")
                        context.startService(intent)
                    } else if (item.action == "ebook_reader") {
                        val intent = android.content.Intent(context, FloatingReaderService::class.java)
                        intent.putExtra("UNFOLD", true)
                        context.startService(intent)
                    } else if (item.action == "screen_record") {
                        val intent = android.content.Intent(context, ScreenRecordActivity::class.java)
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } else if (item.action == "audio_record") {
                        com.example.feature.system_hub.AudioRecordFloatingPanel.toggle(context)
                    } else if (item.action == "camera_measure") {
                        val intent = android.content.Intent(context, com.example.feature.system_hub.CameraMeasureActivity::class.java)
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } else if (item.action == "arrangement_checker" || item.action == "ghost_camera") {
                        val intent = android.content.Intent(context, com.example.feature.system_hub.GhostCameraActivity::class.java)
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } else if (item.action == "settings") {
                        val intent = android.content.Intent(context, com.example.SettingsActivity::class.java)
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } else {
                        val service = VianSideAccessibilityService.instance
                        if (service != null && service.performAction(item.action)) {
                            // success
                            com.example.core.LogKeeper.writeLog("Sidebar", "System action trigger: ${item.action}")
                        } else {
                            android.widget.Toast.makeText(context, "Please enable VianSide Accessibility Service", android.widget.Toast.LENGTH_SHORT).show()
                            com.example.core.LogKeeper.writeLog("Sidebar", "Failed system action trigger: ${item.action}")
                            val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                    currentFolderPopup?.dismiss()
                        onCloseSidebar()
                } else if (item is SidebarItem.PageWindow) {
                    com.example.feature.miniapps.MiniAppManager.toggleApp(context, item.pageType)
                    currentFolderPopup?.dismiss()
                    onCloseSidebar()
                } else if (item is SidebarItem.VolumeAction) {
                    try {
                        com.example.core.LogKeeper.writeLog("Sidebar", "Volume action: ${item.stream}_${item.action}")
                        MediaVolumeHandler.handleVolumeAction(context, item.stream, item.action)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        com.example.core.LogKeeper.writeLog("Sidebar", "Volume action err: ${e.message}")
                    }
                    currentFolderPopup?.dismiss()
                        onCloseSidebar()
                } else if (item is SidebarItem.MediaAction) {
                    try {
                        com.example.core.LogKeeper.writeLog("Sidebar", "Media action: ${item.action}")
                        MediaVolumeHandler.handleMediaAction(context, item.action)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        com.example.core.LogKeeper.writeLog("Sidebar", "Media action err: ${e.message}")
                    }
                    currentFolderPopup?.dismiss()
                        onCloseSidebar()
                } else if (item is SidebarItem.SettingsShortcut) {
                    val intent = when (item.action) {
                        "wifi" -> android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                        "bluetooth" -> android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                        "display" -> android.content.Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)
                        "sound" -> android.content.Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)
                        "location" -> android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        "apps" -> android.content.Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS)
                        "security" -> android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                        "battery" -> android.content.Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS) // Generic fallback
                        "date" -> android.content.Intent(android.provider.Settings.ACTION_DATE_SETTINGS)
                        else -> android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                    }
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(intent)
                        context.sendBroadcast(android.content.Intent("com.example.CLOSE_SIDEBAR"))
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(context, "Cannot open settings", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else if (item is SidebarItem.SettingsShortcut) {
                icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                icon.setImageResource(item.iconResId)
                icon.setColorFilter(android.graphics.Color.WHITE)
            } else if (item is SidebarItem.DisplayAction) {
                    try {
                        com.example.core.LogKeeper.writeLog("Sidebar", "Display action: ${item.action}")
                        DisplayHandler.handleDisplayAction(context, item.action)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        com.example.core.LogKeeper.writeLog("Sidebar", "Display action err: ${e.message}")
                    }
                    currentFolderPopup?.dismiss()
                        onCloseSidebar()
                }
            }

            itemView.setOnLongClickListener {
                val actionList = mutableListOf<String>()
                if (item is SidebarItem.App) {
                    actionList.add("App Info")
                }
                actionList.add("Change Icon")
                val customIconFile = java.io.File(context.filesDir, "custom_icons/${item.id.replace(Regex("[^a-zA-Z0-9.-]"), "_")}.webp")
                if (customIconFile.exists()) {
                    actionList.add("Reset Icon")
                }
                actionList.add("Remove")

                var popupWindow: android.widget.PopupWindow? = null
                val popupLayout = android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    val pad = (8 * context.resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, pad)
                }

                actionList.forEach { action ->
                    val actionView = android.widget.TextView(context).apply {
                        text = action
                        val padV = (10 * context.resources.displayMetrics.density).toInt()
                        val padH = (16 * context.resources.displayMetrics.density).toInt()
                        setPadding(padH, padV, padH, padV)
                        setTextColor(android.graphics.Color.BLACK)
                        textSize = 14f
                        
                        val shape = android.graphics.drawable.GradientDrawable()
                        shape.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        shape.cornerRadius = 24f * context.resources.displayMetrics.density
                        shape.setColor(android.graphics.Color.WHITE)
                        shape.setStroke(1, android.graphics.Color.LTGRAY)
                        background = shape
                        
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 0, 0, (8 * context.resources.displayMetrics.density).toInt())
                        }
                        
                        setOnClickListener {
                            popupWindow?.dismiss()
                            when (action) {
                                "Remove" -> manager.removeItem(item.id)
                                "Change Icon" -> {
                                    val intent = android.content.Intent(context, com.example.IconPickerActivity::class.java).apply {
                                        putExtra("item_id", item.id)
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                    currentFolderPopup?.dismiss()
                                    onCloseSidebar()
                                }
                                "Reset Icon" -> {
                                    val file = java.io.File(context.filesDir, "custom_icons/${item.id.replace(Regex("[^a-zA-Z0-9.-]"), "_")}.webp")
                                    if (file.exists()) file.delete()
                                    manager.iconCache.remove("custom_${item.id}")
                                    manager.iconCache.remove(item.id)
                                    context.sendBroadcast(android.content.Intent("com.example.UPDATE_SIDEBAR_ICONS").apply {
                                        putExtra("item_id", item.id)
                                    })
                                    currentFolderPopup?.dismiss()
                                }
                                "App Info" -> {
                                    if (item is SidebarItem.App) {
                                        try {
                                            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                            intent.data = android.net.Uri.parse("package:${item.packageName}")
                                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(intent)
                                            currentFolderPopup?.dismiss()
                        onCloseSidebar()
                                        } catch (e: Exception) { e.printStackTrace() }
                                    }
                                }
                            }
                        }
                    }
                    popupLayout.addView(actionView)
                }

                popupWindow = android.widget.PopupWindow(popupLayout, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        windowLayoutType = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        windowLayoutType = android.view.WindowManager.LayoutParams.TYPE_PHONE
                    }
                    setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    elevation = 8f * context.resources.displayMetrics.density
                }
                
                val location = IntArray(2)
                itemView.getLocationOnScreen(location)
                popupWindow?.showAtLocation(itemView, android.view.Gravity.NO_GRAVITY, location[0] + itemView.width / 4, location[1] + itemView.height / 2)
                
                true
            }

            val customIconFile = java.io.File(context.filesDir, "custom_icons/${item.id.replace(Regex("[^a-zA-Z0-9.-]"), "_")}.webp")
            if (customIconFile.exists()) {
                val customCached = manager.iconCache.get("custom_${item.id}") ?: android.graphics.BitmapFactory.decodeFile(customIconFile.absolutePath)?.also { manager.iconCache.put("custom_${item.id}", it) }
                if (customCached != null) {
                    icon.setImageDrawable(null)
                    icon.clearColorFilter()
                    icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    icon.setImageBitmap(customCached)
                    return
                }
            }
            val customIconStr = prefs.getString("custom_icon_${item.id}", null)
            if (!customIconStr.isNullOrEmpty()) {
                icon.setImageDrawable(null)
                icon.clearColorFilter()
                icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                if (customIconStr.length <= 4 && !customIconStr.contains(".")) {
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    paint.textSize = 28f * context.resources.displayMetrics.density
                    paint.color = android.graphics.Color.WHITE
                    paint.textAlign = android.graphics.Paint.Align.LEFT
                    val baseline = -paint.ascent()
                    val width = (paint.measureText(customIconStr) + 0.5f).toInt().coerceAtLeast(1)
                    val height = (baseline + paint.descent() + 0.5f).toInt().coerceAtLeast(1)
                    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    canvas.drawText(customIconStr, 0f, baseline, paint)
                    icon.setImageBitmap(bitmap)
                } else {
                    val cached = manager.iconCache.get(customIconStr)
                    if (cached != null) {
                        icon.setImageBitmap(cached)
                    } else {
                        serviceScope.launch {
                            val bitmap = manager.loadIcon(customIconStr)
                            if (bitmap != null) {
                                withContext(Dispatchers.Main) {
                                    icon.setImageBitmap(bitmap)
                                }
                            }
                        }
                    }
                }
                return
            }

            if (item is SidebarItem.App) {
                val cached = manager.getIconBitmap(item.id)
                if (cached != null) {
                    icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    icon.setImageBitmap(cached)
                } else {
                    serviceScope.launch {
                        val bitmap = manager.loadIcon(item.packageName)
                        if (bitmap != null) {
                            withContext(Dispatchers.Main) {
                                icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                icon.setImageBitmap(bitmap)
                            }
                        }
                    }
                }
            } else if (item is SidebarItem.IntentAction) {
                val pkg = try {
                    android.content.Intent.parseUri(item.uri, android.content.Intent.URI_INTENT_SCHEME).`package` ?: android.content.Intent.parseUri(item.uri, android.content.Intent.URI_INTENT_SCHEME).component?.packageName ?: ""
                } catch (e: Exception) { "" }
                val cached = manager.getIconBitmap(item.id)
                if (cached != null) {
                    icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    icon.setImageBitmap(cached)
                } else {
                    serviceScope.launch {
                        var customIconBitmap: android.graphics.Bitmap? = null
                        if (item.iconPath != null) {
                            try {
                                val file = java.io.File(item.iconPath)
                                if (file.exists()) {
                                    customIconBitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                                }
                            } catch(e: Exception) {}
                        }
                        
                        val bitmap = customIconBitmap ?: manager.loadIcon(pkg)
                        if (bitmap != null) {
                            withContext(Dispatchers.Main) {
                                icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                icon.setImageBitmap(bitmap)
                            }
                        }
                    }
                }
            } else if (item is SidebarItem.Widget) {
                val cached = manager.getIconBitmap(item.id)
                if (cached != null) {
                    icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    icon.setImageBitmap(cached)
                }
            } else if (item is SidebarItem.QuickTile) {
                icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                icon.setImageResource(item.iconResId)
                icon.setColorFilter(android.graphics.Color.WHITE)
            } else if (item is SidebarItem.SystemAction) {
                icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                if (item.action == "screen_record" && com.example.service.ScreenRecordService.isRecording) {
                    icon.setImageResource(android.R.drawable.ic_media_pause)
                    icon.setColorFilter(android.graphics.Color.RED)
                    icon.alpha = 1.0f
                    label.alpha = 1.0f
                } else if (item.action == "force_stop_running_apps") {
                    icon.setImageResource(item.iconResId)
                    val isPresent = com.example.utils.AppTrackerHelper.isAppTrackerConfigured(context)
                    if (isPresent) {
                        icon.setColorFilter(android.graphics.Color.parseColor("#00E676"))
                        icon.alpha = 1.0f
                        label.alpha = 1.0f
                    } else {
                        icon.setColorFilter(android.graphics.Color.parseColor("#888888"))
                        icon.alpha = 0.38f
                        label.alpha = 0.38f
                    }
                } else {
                    icon.setImageResource(item.iconResId)
                    icon.setColorFilter(android.graphics.Color.WHITE)
                    icon.alpha = 1.0f
                    label.alpha = 1.0f
                }
            } else if (item is SidebarItem.VolumeAction) {
                icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                icon.setImageResource(item.iconResId)
                icon.setColorFilter(android.graphics.Color.WHITE)
            } else if (item is SidebarItem.MediaAction) {
                icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                icon.setImageResource(item.iconResId)
                icon.setColorFilter(android.graphics.Color.WHITE)
            } else if (item is SidebarItem.SettingsShortcut) {
                icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                icon.setImageResource(item.iconResId)
                icon.setColorFilter(android.graphics.Color.WHITE)
            } else if (item is SidebarItem.DisplayAction) {
                icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                icon.setImageResource(item.iconResId)
                if (item.action == "blue_light_filter" && com.example.service.BlueLightFilterManager.isEnabled) {
                    icon.setColorFilter(android.graphics.Color.parseColor("#FF9900"))
                } else {
                    icon.setColorFilter(android.graphics.Color.WHITE)
                }
            } else if (item is SidebarItem.Folder) {
                icon.setImageDrawable(null)
                icon.clearColorFilter()
                icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                
                val cHex = try { android.graphics.Color.parseColor(item.colorHex) } catch(e:Exception){ android.graphics.Color.parseColor("#00BFA5") }
                val iconC = android.graphics.Color.WHITE
                
                val miniIcons = item.items.take(9).mapNotNull { manager.getIconBitmap(it) }
                icon.setImageDrawable(FolderStyleDrawable(item.folderStyle, cHex, iconC, miniIcons))
                
                if (miniIcons.size < minOf(item.items.size, 9)) {
                    serviceScope.launch {
                        var newlyLoaded = false
                        for (subItem in item.items.take(9)) {
                            if (manager.getIconBitmap(subItem) == null) {
                                val pkg = when {
                                    subItem.startsWith("app:") -> subItem.substringAfter("app:")
                                    subItem.startsWith("intent:") -> subItem.substringAfter("intent:").split("/").getOrNull(0) ?: ""
                                    else -> ""
                                }
                                if (pkg.isNotEmpty()) {
                                    val bitmap = manager.loadIcon(pkg)
                                    if (bitmap != null) {
                                        newlyLoaded = true
                                    }
                                }
                            }
                        }
                        if (newlyLoaded) {
                            withContext(Dispatchers.Main) {
                                adapter.notifyItemChanged(position)
                            }
                        }
                    }
                }
            } else if (item is SidebarItem.Link) {
                icon.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                icon.setImageResource(android.R.drawable.ic_menu_set_as) // Generic link icon
                icon.setColorFilter(android.graphics.Color.WHITE)
            }
        }
    }

    override fun onEditClicked() {
        val intent = android.content.Intent(context, com.example.SidebarEditActivity::class.java).apply {
            putExtra("PAGE_ID", pageConfig?.id ?: "")
            putExtra("HANDLE_ID", handleId)
            putExtra("CONTAINER_ID", handleId)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        onCloseSidebar()
    }
}
