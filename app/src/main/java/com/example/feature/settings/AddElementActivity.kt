package com.example.feature.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.room.Room


class AddElementActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val density = resources.displayMetrics.density
        
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#121212"))
        }
        
        // Toolbar
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (56 * density).toInt())
            setBackgroundColor(Color.parseColor("#1DB954")) // A green-ish or teal color
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
        }
        
        val backIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert) // Use revert as back
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt()).apply { marginEnd = (16 * density).toInt() }
            setOnClickListener { finish() }
        }
        
        val titleView = TextView(this).apply {
            text = "Add element"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        toolbar.addView(backIcon)
        toolbar.addView(titleView)
        mainLayout.addView(toolbar)
        
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        
        fun addHeader(text: String) {
            val header = TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setBackgroundColor(Color.parseColor("#333333"))
                setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            contentLayout.addView(header)
        }
        
        fun addItem(iconRes: Int, text: String, onClick: () -> Unit) {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
                isClickable = true
                isFocusable = true
                val outValue = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { onClick() }
            }
            
            val icon = ImageView(this).apply {
                setImageResource(iconRes)
                setColorFilter(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt()).apply { marginEnd = (16 * density).toInt() }
            }
            
            val label = TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            
            itemLayout.addView(icon)
            itemLayout.addView(label)
            contentLayout.addView(itemLayout)
            
            // Divider
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(Color.parseColor("#222222"))
            }
            contentLayout.addView(divider)
        }
        
        addHeader("Default actions")
        addItem(android.R.drawable.ic_menu_agenda, "App") {
            startActivityForResult(Intent(this, AppPickerActivity::class.java), 200)
        }
        addItem(android.R.drawable.ic_menu_share, "Shortcut") {
            startActivityForResult(Intent(this, ShortcutPickerActivity::class.java), 300)
        }
        addItem(android.R.drawable.ic_menu_manage, "Intent") {
            startActivityForResult(Intent(this, IntentPickerActivity::class.java), 301)
        }
        addItem(android.R.drawable.ic_menu_gallery, "Widget") {
            val intent = Intent(this, com.example.WidgetPickerActivity::class.java).apply {
                putExtra("ACTION_TYPE", "RETURN_ID")
            }
            startActivityForResult(intent, 400)
        }
        
        addItem(android.R.drawable.ic_menu_gallery, "Popup Widget") {
            val intent = Intent(this, com.example.WidgetPickerActivity::class.java).apply {
                putExtra("ACTION_TYPE", "RETURN_ID")
            }
            startActivityForResult(intent, 500)
        }

        addHeader("Special items")
        val isFloatingTrigger = intent.getBooleanExtra("IS_FLOATING_TRIGGER_SELECTION", false)
        addItem(android.R.drawable.ic_menu_more, "Folder") {
            val options = arrayOf("Grid", "Stack")
            android.app.AlertDialog.Builder(this)
                .setTitle("Folder style")
                .setItems(options) { _, which ->
                    val input = android.widget.EditText(this)
                    input.hint = "Folder Name"
                    input.setText("New Folder")
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Folder Name")
                        .setView(input)
                        .setPositiveButton("OK") { _, _ ->
                            val folderName = input.text.toString().takeIf { it.isNotEmpty() } ?: "New Folder"
                            val uuid = java.util.UUID.randomUUID().toString()
                            val folderJson = JSONObject().apply {
                                put("name", folderName)
                                put("colorHex", "#444444")
                                put("items", org.json.JSONArray())
                                put("folderStyle", which)
                                put("popupColumns", 3)
                                put("popupRows", 3)
                            }
                            finishWithId("folder:$uuid:${folderJson.toString()}")
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                .show()
        }
        addItem(android.R.drawable.ic_menu_set_as, "Link") {
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val titleInput = android.widget.EditText(this).apply {
                hint = "Title (e.g. Google)"
            }
            val urlInput = android.widget.EditText(this).apply {
                hint = "URL (e.g. https://google.com)"
            }
            layout.addView(titleInput)
            layout.addView(urlInput)
            
            android.app.AlertDialog.Builder(this)
                .setTitle("Add Link")
                .setView(layout)
                .setPositiveButton("OK") { _, _ ->
                    val titleStr = titleInput.text.toString().takeIf { it.isNotEmpty() } ?: "Link"
                    val urlStr = urlInput.text.toString().takeIf { it.isNotEmpty() } ?: "https://"
                    val uuid = java.util.UUID.randomUUID().toString()
                    val linkJson = JSONObject().apply {
                        put("url", urlStr)
                        put("label", titleStr)
                    }
                    finishWithId("link:$uuid:${linkJson.toString()}")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        addItem(android.R.drawable.ic_menu_close_clear_cancel, "Empty item") {
            val uuid = java.util.UUID.randomUUID().toString()
            val spacerJson = JSONObject().apply {
                put("heightDp", 56)
            }
            finishWithId("spacer:$uuid:${spacerJson.toString()}")
        }
        
        addHeader("Android actions")
        fun openActionPicker(category: String, title: String) {
            val intent = Intent(this, ActionPickerActivity::class.java).apply {
                putExtra("CATEGORY", category)
                putExtra("TITLE", title)
            }
            startActivityForResult(intent, 500)
        }
        
        addItem(android.R.drawable.ic_menu_camera, "Record") { openActionPicker("screen_capture", "Record") }
        addItem(android.R.drawable.ic_menu_manage, "System Quick Tiles") { openActionPicker("quick_tiles", "Quick Tiles") }
        addItem(android.R.drawable.ic_menu_preferences, "Android Settings Shortcut") { openActionPicker("settings_shortcut", "Settings Shortcut") }
        addItem(android.R.drawable.ic_menu_info_details, "System") { openActionPicker("system", "System Actions") }
        addItem(android.R.drawable.ic_lock_silent_mode, "Volume") { openActionPicker("volume", "Volume Actions") }
        addItem(android.R.drawable.ic_media_play, "Media") { openActionPicker("media", "Media Actions") }
        addItem(android.R.drawable.ic_menu_view, "Display") { openActionPicker("display", "Display Actions") }
        addItem(android.R.drawable.ic_menu_agenda, "Utilities") { openActionPicker("utilities", "Utilities") }
        
        addHeader("Floating Windows")
        if (!isFloatingTrigger) {
            addItem(android.R.drawable.ic_menu_crop, "Floating Trigger") {
                val intent = Intent(this, AddElementActivity::class.java).apply {
                    putExtra("IS_FLOATING_TRIGGER_SELECTION", true)
                }
                startActivityForResult(intent, 700)
            }
        }
        addItem(android.R.drawable.ic_menu_gallery, "Hybrid Grid") {
            finishWithId("system:hybrid_grid_floating")
        }

        addItem(com.example.R.drawable.ic_library_books, "eBook Reader") {
            finishWithId("system:ebook_reader")
        }
        addItem(android.R.drawable.ic_menu_sort_alphabetically, "Dictionary") {
            finishWithId("system:dictionary_floating")
        }
        addItem(android.R.drawable.ic_menu_compass, "Cursor Trackpad") {
            finishWithId("system:cursor")
        }
        addItem(android.R.drawable.ic_menu_edit, "Work Notes") {
            finishWithId("system:work_notes")
        }
        addItem(android.R.drawable.ic_menu_add, "PWA Loader") {
//             startActivityForResult(Intent(this, PwaPickerActivity::class.java), 800)
        }
        addItem(android.R.drawable.ic_menu_gallery, "Page Window") {
//             startActivityForResult(Intent(this, PageWindowPickerActivity::class.java), 900)
        }

        scrollView.addView(contentLayout)
        mainLayout.addView(scrollView)
        
        setContentView(mainLayout)
    }
    
    private fun finishWithId(id: String) {
        if (intent?.action == "SELECT_ELEMENT_FOR_HANDLE") {
            val prefix = intent.getStringExtra("handle_prefix") ?: return
            val gesture = intent.getStringExtra("gesture") ?: return
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("${prefix}$gesture", "open_element:$id").apply()
            
            // Notify service to reload configuration
            val updateIntent = Intent(this, com.example.core.HandleService::class.java).apply {
                action = "UPDATE_CONFIG"
            }
            startService(updateIntent)
        } else {
            val resultIntent = Intent().apply { putExtra("ELEMENT_ID", id) }
            setResult(Activity.RESULT_OK, resultIntent)
        }
        finish()
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        com.example.core.LogKeeper.writeLog("AddElementActivity", "onActivityResult req=$requestCode res=$resultCode data=$data")
        if (resultCode == Activity.RESULT_OK && data != null) {
            val id = data.getStringExtra("ELEMENT_ID")
            if (id != null) {
                if (requestCode == 500 && id.startsWith("widget:")) {
                    finishWithId("popup_widget:" + id.removePrefix("widget:"))
                } else if (requestCode == 700) {
                    finishWithId("floating_trigger:$id")
                } else {
                    finishWithId(id)
                }
            } else {
                com.example.core.LogKeeper.writeLog("AddElementActivity", "ELEMENT_ID was null in data!")
            }
        } else {
            // Did it fail? If they cancelled, we might just stay here. 
            // But if it was a failure in picking, they are stuck. 
            // Let's just finish the AddElementActivity if a picker is cancelled so they don't get stuck.
            if (requestCode == 300) { // ShortcutPickerActivity
                com.example.core.LogKeeper.writeLog("AddElementActivity", "ShortcutPicker cancelled or failed.")
            }
        }
    }
}
