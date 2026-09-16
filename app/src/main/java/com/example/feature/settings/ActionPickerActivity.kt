package com.example.feature.settings

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.feature.sidebar.SidebarItem
import com.example.feature.sidebar.ALL_QUICK_TILES
import com.example.feature.sidebar.ALL_SYSTEM_ACTIONS
import com.example.feature.sidebar.ALL_SCREEN_CAPTURE_ACTIONS
import com.example.feature.sidebar.ALL_UTILITIES_ACTIONS
import com.example.feature.sidebar.ALL_VOLUME_ACTIONS
import com.example.feature.sidebar.ALL_MEDIA_ACTIONS
import com.example.feature.sidebar.ALL_DISPLAY_ACTIONS
import com.example.feature.sidebar.ALL_SETTINGS_SHORTCUTS

import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.service.*

class ActionPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val category = intent.getStringExtra("CATEGORY") ?: ""
        val titleStr = intent.getStringExtra("TITLE") ?: "Select Action"
        
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
            setPadding(32, 32, 32, 32)
        }
        
        val titleView = TextView(this).apply {
            text = titleStr
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        mainLayout.addView(titleView)
        
        val items: List<SidebarItem> = when (category) {
            "quick_tiles" -> ALL_QUICK_TILES
            "system" -> ALL_SYSTEM_ACTIONS
            "screen_capture" -> ALL_SCREEN_CAPTURE_ACTIONS
            "utilities" -> ALL_UTILITIES_ACTIONS
            "volume" -> ALL_VOLUME_ACTIONS
            "media" -> ALL_MEDIA_ACTIONS
            "display" -> ALL_DISPLAY_ACTIONS
            "settings_shortcut" -> ALL_SETTINGS_SHORTCUTS
            else -> emptyList()
        }
        
        val recyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = LinearLayoutManager(this@ActionPickerActivity)
            adapter = ActionAdapter(items) { item ->
                val id = when (item) {
                    is SidebarItem.QuickTile -> "quicktile:${item.action}"
                    is SidebarItem.SystemAction -> "system:${item.action}"
                    is SidebarItem.VolumeAction -> "volume:${item.stream}_${item.action}"
                    is SidebarItem.MediaAction -> "media:${item.action}"
                    is SidebarItem.DisplayAction -> "display:${item.action}"
                    is SidebarItem.SettingsShortcut -> "settings_shortcut:${item.action}"
                    else -> ""
                }
                if (id.isNotEmpty()) {
                    val resultIntent = Intent().apply { putExtra("ELEMENT_ID", id) }
                    setResult(Activity.RESULT_OK, resultIntent)
                    finish()
                }
            }
        }
        mainLayout.addView(recyclerView)
        setContentView(mainLayout)
    }
    
    inner class ActionAdapter(private val items: List<SidebarItem>, private val onClick: (SidebarItem) -> Unit) : RecyclerView.Adapter<ActionAdapter.ViewHolder>() {
        inner class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
            val iconView: ImageView = view.findViewById(1)
            val nameView: TextView = view.findViewById(2)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val layout = LinearLayout(this@ActionPickerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 32, 16, 32)
            }
            val iconView = ImageView(this@ActionPickerActivity).apply {
                id = 1
                layoutParams = LinearLayout.LayoutParams(64, 64).apply { marginEnd = 32 }
                setColorFilter(Color.WHITE)
            }
            val nameView = TextView(this@ActionPickerActivity).apply {
                id = 2
                setTextColor(Color.WHITE)
                textSize = 16f
            }
            layout.addView(iconView)
            layout.addView(nameView)
            return ViewHolder(layout)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val isForceStop = (item is SidebarItem.SystemAction && item.action == "force_stop_running_apps")
            val isConfigured = if (isForceStop) com.example.utils.AppTrackerHelper.isAppTrackerConfigured(this@ActionPickerActivity) else true

            if (isForceStop && !isConfigured) {
                holder.nameView.text = "${item.label} (Requires App Tracker Page)"
                holder.nameView.alpha = 0.5f
                holder.iconView.alpha = 0.5f
            } else {
                holder.nameView.text = item.label
                holder.nameView.alpha = 1.0f
                holder.iconView.alpha = 1.0f
            }
            
            val iconResId = when (item) {
                is SidebarItem.QuickTile -> item.iconResId
                is SidebarItem.SystemAction -> item.iconResId
                is SidebarItem.VolumeAction -> item.iconResId
                is SidebarItem.MediaAction -> item.iconResId
                is SidebarItem.DisplayAction -> item.iconResId
                is SidebarItem.SettingsShortcut -> item.iconResId
                else -> android.R.drawable.sym_def_app_icon
            }
            holder.iconView.setImageResource(iconResId)
            
            holder.itemView.setOnClickListener { onClick(item) }
        }
        override fun getItemCount() = items.size
    }
}
