package com.example.feature.element

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.core.AppWidgetHelper
import com.example.core.LogKeeper
import com.example.feature.sidebar.GridWidgetItem
import com.example.feature.sidebar.SidebarAppsManager
import com.example.feature.sidebar.SidebarItem
import com.example.utils.AppTrackerHelper
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * ElementViewRenderer: Dedicated rendering and UI interaction engine for modular Elements.
 *
 * Responsibilities:
 * 1. Element Tile Rendering: Inflates and binds element tiles with icons, labels, alpha, and styling.
 * 2. Gesture & Click Dispatching: Routes interactions to [ElementActionDispatcher].
 * 3. Context Menus: Displays on-demand popup context menus ("App Info", "Remove", "Change Icon", "Reset Icon").
 * 4. Folder & Widget Popups: Renders modal popups for Folder elements and Popup Widgets.
 */
class ElementViewRenderer(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val containerId: String = "sidebar",
    private val pageId: String,
    private val onClose: (() -> Unit)? = null,
    private val onDimSidebar: ((Boolean) -> Unit)? = null,
    private val onItemRemoved: ((String) -> Unit)? = null
) {

    private val density = context.resources.displayMetrics.density

    /**
     * Binds a standard Element tile view for a [SidebarItem].
     */
    fun bindElementView(
        item: GridWidgetItem,
        parsed: SidebarItem,
        appsManager: SidebarAppsManager
    ): View {
        val elementView = LayoutInflater.from(context).inflate(R.layout.item_sidebar_app, null, false)
        val icon = elementView.findViewById<ImageView>(R.id.app_icon)
        val label = elementView.findViewById<TextView>(R.id.app_label)

        icon.maxWidth = Int.MAX_VALUE
        icon.maxHeight = Int.MAX_VALUE
        val lp = icon.layoutParams as LinearLayout.LayoutParams
        lp.height = 0
        lp.weight = 1f
        icon.layoutParams = lp

        label.text = parsed.label

        label.alpha = 1.0f

        appsManager.bindIcon(item.id, icon, prefs, scope) {
            appsManager.bindIcon(item.id, icon, prefs, scope) {}
        }

        elementView.setOnClickListener {
            val handled = ElementActionDispatcher.execute(
                context = context,
                item = parsed,
                onShowFolder = { folder -> showFolderPopup(elementView, folder, appsManager) },
                onShowWidget = { widget -> showWidgetPopup(elementView, widget) }
            )
            if (handled && parsed !is SidebarItem.Folder && parsed !is SidebarItem.PopupWidget &&
                parsed !is SidebarItem.VolumeAction && parsed !is SidebarItem.DisplayAction &&
                parsed !is SidebarItem.QuickTile) {
                onClose?.invoke() ?: com.example.feature.sidebar.SidebarManager.getInstance(context).closeSidebar()
            }
        }

        elementView.setOnLongClickListener {
            showContextMenuPopup(elementView, item, parsed, appsManager)
            true
        }

        return elementView
    }

    /**
     * Displays the long-press context menu for an Element tile.
     */
    fun showContextMenuPopup(
        anchor: View,
        item: GridWidgetItem,
        parsed: SidebarItem,
        appsManager: SidebarAppsManager
    ) {
        val actionList = mutableListOf<String>()
        if (parsed is SidebarItem.App) {
            actionList.add("App Info")
        }
        val isForceStop = (parsed is SidebarItem.SystemAction && parsed.action == "force_stop_running_apps")
        if (isForceStop) {
            actionList.add("Edit")
        }
        actionList.add("Change Icon")
        val customIconFile = File(context.filesDir, "custom_icons/${item.id.replace(Regex("[^a-zA-Z0-9.-]"), "_")}.webp")
        if (customIconFile.exists()) {
            actionList.add("Reset Icon")
        }
        actionList.add("Remove")

        var popupWindow: PopupWindow? = null
        val popupLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (8 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        actionList.forEach { action ->
            val actionView = TextView(context).apply {
                text = action
                setTextColor(Color.WHITE)
                setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
                gravity = Gravity.CENTER

                val shape = GradientDrawable().apply {
                    cornerRadius = 8 * density
                    setColor(Color.parseColor("#333333"))
                    setStroke(1, Color.LTGRAY)
                }
                background = shape

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, (8 * density).toInt())
                }

                setOnClickListener {
                    popupWindow?.dismiss()
                    when (action) {
                        "Edit" -> {
                            if (isForceStop) {
                                val intent = Intent(context, com.example.AppTrackerSettingsActivity::class.java).apply {
                                    putExtra("MODE", "force_stop_only")
                                    putExtra("CONTAINER_ID", containerId)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                                onClose?.invoke() ?: com.example.feature.sidebar.SidebarManager.getInstance(context).closeSidebar()
                            }
                        }
                        "Remove" -> {
                            onItemRemoved?.invoke(item.id)
                        }
                        "Change Icon" -> {
                            val intent = Intent(context, com.example.IconPickerActivity::class.java).apply {
                                putExtra("item_id", item.id)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                        "Reset Icon" -> {
                            val file = File(context.filesDir, "custom_icons/${item.id.replace(Regex("[^a-zA-Z0-9.-]"), "_")}.webp")
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
                                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = android.net.Uri.parse("package:${parsed.packageName}")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
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
            (150 * density).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                windowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                windowLayoutType = WindowManager.LayoutParams.TYPE_PHONE
            }
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
        }

        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val x = location[0]
        var y = location[1] - popupLayout.measuredHeight
        if (y < 0) y = location[1] + anchor.height
        popupWindow.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
    }

    /**
     * Displays a modal popup for a Folder element.
     */
    fun showFolderPopup(anchor: View, folder: SidebarItem.Folder, appsManager: SidebarAppsManager) {
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
        val baseColor = try { Color.parseColor(colorHex) } catch (_: Exception) { Color.parseColor("#1E1E2E") }
        val alphaInt = (popupOpacity * 255).toInt().coerceIn(0, 255)
        val r = Color.red(baseColor)
        val g = Color.green(baseColor)
        val b = Color.blue(baseColor)

        popupView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.argb(alphaInt, r, g, b))
            setStroke((1 * density).toInt(), Color.argb(80, 255, 255, 255))
            cornerRadius = 16 * density
        }

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
                val view = LayoutInflater.from(context).inflate(R.layout.item_sidebar_app, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }
            override fun getItemCount() = folder.items.size
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val itemId = folder.items[position]
                val parsed = appsManager.parseId(itemId) ?: return
                val icon = holder.itemView.findViewById<ImageView>(R.id.app_icon)
                val label = holder.itemView.findViewById<TextView>(R.id.app_label)
                label.text = parsed.label

                appsManager.bindIcon(itemId, icon, prefs, scope) {}

                holder.itemView.setOnClickListener {
                    val handled = ElementActionDispatcher.execute(
                        context = context,
                        item = parsed,
                        onShowFolder = null,
                        onShowWidget = null
                    )
                    if (handled) {
                        popupWindow?.dismiss()
                        if (parsed !is SidebarItem.Folder && parsed !is SidebarItem.PopupWidget &&
                            parsed !is SidebarItem.VolumeAction && parsed !is SidebarItem.DisplayAction &&
                            parsed !is SidebarItem.QuickTile) {
                            onClose?.invoke() ?: com.example.feature.sidebar.SidebarManager.getInstance(context).closeSidebar()
                        }
                    }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                windowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                windowLayoutType = WindowManager.LayoutParams.TYPE_PHONE
            }
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
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

    /**
     * Displays a modal popup for a PopupWidget element.
     */
    fun showWidgetPopup(anchor: View, widget: SidebarItem.PopupWidget) {
        val popupView = FrameLayout(context)
        val padding = (8 * density).toInt()
        popupView.setPadding(padding, padding, padding, padding)

        val popupOpacity = prefs.getFloat("handle_${containerId}_sidebar_transparency", prefs.getFloat("sidebar_transparency", 0.9f))
        val colorHex = prefs.getString("handle_${containerId}_sidebar_color", prefs.getString("sidebar_color", "#1E1E2E")) ?: "#1E1E2E"
        val baseColor = try { Color.parseColor(colorHex) } catch (_: Exception) { Color.parseColor("#1E1E2E") }
        val alphaInt = (popupOpacity * 255).toInt().coerceIn(0, 255)
        val r = Color.red(baseColor)
        val g = Color.green(baseColor)
        val b = Color.blue(baseColor)

        popupView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.argb(alphaInt, r, g, b))
            setStroke((1 * density).toInt(), Color.argb(80, 255, 255, 255))
            cornerRadius = 16 * density
        }

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHelper.getHost(context)
        val info = appWidgetManager.getAppWidgetInfo(widget.widgetId)

        if (info != null) {
            val hostView = host.createView(context, widget.widgetId, info)
            hostView.setPadding(0, 0, 0, 0)

            val minW = info.minWidth
            val minH = info.minHeight
            val w = if (minW > 0) minW else (200 * density).toInt()
            val h = if (minH > 0) minH else (200 * density).toInt()

            val lp = FrameLayout.LayoutParams(w, h).apply {
                gravity = Gravity.CENTER
            }
            popupView.addView(hostView, lp)

            val popupWindow = PopupWindow(
                popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    windowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    windowLayoutType = WindowManager.LayoutParams.TYPE_PHONE
                }
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                isOutsideTouchable = true
                setOnDismissListener {
                    onDimSidebar?.invoke(false)
                }
            }

            val location = IntArray(2)
            anchor.getLocationOnScreen(location)
            onDimSidebar?.invoke(true)
            popupWindow.showAtLocation(anchor, Gravity.CENTER, 0, 0)
        }
    }
}
