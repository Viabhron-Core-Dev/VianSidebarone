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
        val pageId = intent.getStringExtra("PAGE_ID")
        val containerId = intent.getStringExtra("CONTAINER_ID") ?: "sidebar"
        val isHybridGrid = intent.getBooleanExtra("IS_HYBRID_GRID", false)
        
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
            setPadding(24, 24, 24, 24)
        }
        
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 24)
        }

        val backIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(72, 72).apply { marginEnd = 24 }
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }

        val titleView = TextView(this).apply {
            text = titleStr
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        toolbar.addView(backIcon)
        toolbar.addView(titleView)
        mainLayout.addView(toolbar)
        
        val allItems: List<SidebarItem> = when (category) {
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

        fun matchesQuery(item: SidebarItem, q: String): Boolean {
            if (q.isEmpty()) return true
            val query = q.lowercase().trim()
            if (item.label.lowercase().contains(query)) return true
            if (item is SidebarItem.SettingsShortcut) {
                val action = item.action.lowercase()
                if (action.contains(query)) return true
                // Keyword synonyms mapping
                val keywords = when (action) {
                    "wifi", "network_provider", "data_usage", "hotspot", "airplane", "vpn", "nfc" ->
                        listOf("network", "internet", "cellular", "mobile", "sim", "tether", "wireless", "broadband")
                    "bluetooth", "cast" ->
                        listOf("device", "pair", "connect", "screen", "mirror", "wireless", "tv")
                    "display", "night_display", "wallpaper" ->
                        listOf("screen", "brightness", "eye", "dark", "theme", "light", "background")
                    "sound", "do_not_disturb" ->
                        listOf("volume", "ring", "audio", "vibrate", "mute", "silent", "dnd", "media")
                    "notifications", "notification_history" ->
                        listOf("alerts", "messages", "status", "history")
                    "apps", "all_apps", "default_apps", "permissions" ->
                        listOf("applications", "install", "manage", "access", "privacy", "defaults")
                    "battery", "battery_saver" ->
                        listOf("power", "charge", "energy", "drain", "saver")
                    "storage" ->
                        listOf("disk", "memory", "space", "files", "sd")
                    "security", "privacy", "biometrics" ->
                        listOf("lock", "pin", "fingerprint", "face", "password", "screen lock", "protect")
                    "accounts" ->
                        listOf("sync", "google", "login", "profile", "user")
                    "language", "keyboard" ->
                        listOf("input", "typing", "ime", "locale", "text", "spell")
                    "accessibility", "captioning" ->
                        listOf("vision", "hearing", "subtitles", "talkback", "assist", "captions")
                    "developer_options", "device_info", "system_update" ->
                        listOf("about", "phone", "version", "build", "debug", "usb", "os", "firmware")
                    else -> emptyList()
                }
                return keywords.any { it.contains(query) || query.contains(it) }
            }
            return false
        }
        
        val searchBox = android.widget.EditText(this).apply {
            hint = "Search ${titleStr.lowercase()}..."
            setHintTextColor(Color.parseColor("#888888"))
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(32, 20, 32, 20)
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#222222"))
                cornerRadius = 16f
                setStroke(1, Color.parseColor("#333333"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 24
            }
            isSingleLine = true
        }
        mainLayout.addView(searchBox)

        val adapter = ActionAdapter(allItems) { item ->
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
                if (isHybridGrid && !pageId.isNullOrEmpty()) {
                    com.example.feature.element.ElementPlacementHelper.addElementToHybridGrid(
                        this@ActionPickerActivity,
                        pageId,
                        id,
                        containerId = containerId
                    )
                    android.widget.Toast.makeText(this@ActionPickerActivity, "Added: ${item.label}", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val targetId = com.example.feature.element.ElementPlacementHelper.assignInstanceIfNote(id)
                    val resultIntent = Intent().apply {
                        putExtra("ELEMENT_ID", targetId)
                        putExtra("CONTAINER_ID", containerId)
                    }
                    setResult(Activity.RESULT_OK, resultIntent)
                    finish()
                }
            }
        }

        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString() ?: ""
                val filtered = allItems.filter { matchesQuery(it, q) }
                adapter.updateItems(filtered)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        val recyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = LinearLayoutManager(this@ActionPickerActivity)
            this.adapter = adapter
        }
        mainLayout.addView(recyclerView)
        setContentView(mainLayout)
    }
    
    inner class ActionAdapter(
        initialItems: List<SidebarItem>,
        private val onClick: (SidebarItem) -> Unit
    ) : RecyclerView.Adapter<ActionAdapter.ViewHolder>() {
        private var items: List<SidebarItem> = initialItems

        fun updateItems(newItems: List<SidebarItem>) {
            items = newItems
            notifyDataSetChanged()
        }

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
            holder.nameView.text = item.label
            holder.nameView.alpha = 1.0f
            holder.iconView.alpha = 1.0f
            
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
