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
        
        val isFloatingTrigger = intent.getBooleanExtra("IS_FLOATING_TRIGGER_SELECTION", false)

        val titleView = TextView(this).apply {
            text = if (isFloatingTrigger) "Select trigger action" else "Add element"
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
        
        val pageId = intent.getStringExtra("PAGE_ID")
        val containerId = intent.getStringExtra("CONTAINER_ID") ?: "sidebar"
        val isHybridGrid = intent.getBooleanExtra("IS_HYBRID_GRID", false)
        val effectiveIsHybridGrid = isHybridGrid && !isFloatingTrigger
        val pageType = intent.getStringExtra("PAGE_TYPE")

        com.example.core.LogKeeper.log(
            this,
            "MiniAppLaunchRouting",
            "ROUTING_DIAG: ADD_ELEMENT_ACTIVITY_CREATED pageId=$pageId containerId=$containerId isHybrid=$isHybridGrid isFloatingTrigger=$isFloatingTrigger pageType=$pageType"
        )

        addHeader("Default actions")
        addItem(android.R.drawable.ic_menu_agenda, "App") {
            val appIntent = Intent(this, AppPickerActivity::class.java).apply {
                putExtra("PAGE_ID", pageId)
                putExtra("CONTAINER_ID", containerId)
                putExtra("IS_HYBRID_GRID", effectiveIsHybridGrid)
                putExtra("PAGE_TYPE", pageType)
            }
            startActivityForResult(appIntent, 200)
        }
        addItem(android.R.drawable.ic_menu_share, "Shortcut") {
            val sIntent = Intent(this, ShortcutPickerActivity::class.java).apply {
                putExtra("PAGE_ID", pageId)
                putExtra("CONTAINER_ID", containerId)
                putExtra("IS_HYBRID_GRID", effectiveIsHybridGrid)
                putExtra("PAGE_TYPE", pageType)
            }
            startActivityForResult(sIntent, 300)
        }
        addItem(android.R.drawable.ic_menu_manage, "Intent") {
            val iIntent = Intent(this, IntentPickerActivity::class.java).apply {
                putExtra("PAGE_ID", pageId)
                putExtra("CONTAINER_ID", containerId)
                putExtra("IS_HYBRID_GRID", effectiveIsHybridGrid)
                putExtra("PAGE_TYPE", pageType)
            }
            startActivityForResult(iIntent, 301)
        }
        if (!isFloatingTrigger) {
            addItem(android.R.drawable.ic_menu_gallery, "Widget") {
                val intent = Intent(this, com.example.WidgetPickerActivity::class.java).apply {
                    putExtra("ACTION_TYPE", "RETURN_ID")
                    putExtra("PAGE_ID", pageId)
                    putExtra("CONTAINER_ID", containerId)
                    putExtra("IS_HYBRID_GRID", effectiveIsHybridGrid)
                    putExtra("PAGE_TYPE", pageType)
                }
                startActivityForResult(intent, 400)
            }

            addItem(android.R.drawable.ic_menu_gallery, "Popup Widget") {
                val intent = Intent(this, com.example.WidgetPickerActivity::class.java).apply {
                    putExtra("ACTION_TYPE", "RETURN_ID")
                    putExtra("PAGE_ID", pageId)
                    putExtra("CONTAINER_ID", containerId)
                    putExtra("IS_HYBRID_GRID", effectiveIsHybridGrid)
                    putExtra("PAGE_TYPE", pageType)
                }
                startActivityForResult(intent, 500)
            }
        }

        addHeader("Special items")
        if (!isFloatingTrigger) {
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
                                val folderId = "folder:$uuid:${folderJson.toString()}"
                                handleElementSelected(folderId, folderName)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    .show()
            }
        }
        addItem(com.example.R.drawable.ic_language, "Link") {
            val linkIntent = Intent(this, LinkPickerActivity::class.java).apply {
                putExtra("PAGE_ID", pageId)
                putExtra("CONTAINER_ID", containerId)
                putExtra("IS_HYBRID_GRID", effectiveIsHybridGrid)
                putExtra("PAGE_TYPE", pageType)
            }
            startActivityForResult(linkIntent, 302)
        }
        addItem(com.example.R.drawable.ic_language, "Offline PWA") {
            val pwaIntent = Intent(this, com.example.feature.pwa.PwaListActivity::class.java).apply {
                putExtra("PAGE_ID", pageId)
                putExtra("CONTAINER_ID", containerId)
                putExtra("IS_HYBRID_GRID", effectiveIsHybridGrid)
                putExtra("PAGE_TYPE", pageType)
                putExtra("IS_PICKING", true)
            }
            startActivityForResult(pwaIntent, 600)
        }

        addHeader("Quick Calls")
        addItem(android.R.drawable.sym_action_call, "Quick Calls") {
            val quickCallIntent = Intent(this, com.example.feature.quickcall.QuickCallPickerActivity::class.java).apply {
                putExtra("PAGE_ID", pageId)
                putExtra("CONTAINER_ID", containerId)
                putExtra("IS_HYBRID_GRID", effectiveIsHybridGrid)
                putExtra("PAGE_TYPE", pageType)
            }
            startActivityForResult(quickCallIntent, 303)
        }
        
        addHeader("Android actions")
        fun openActionPicker(category: String, title: String) {
            val intent = Intent(this, ActionPickerActivity::class.java).apply {
                putExtra("CATEGORY", category)
                putExtra("TITLE", title)
                putExtra("PAGE_ID", pageId)
                putExtra("CONTAINER_ID", containerId)
                putExtra("IS_HYBRID_GRID", effectiveIsHybridGrid)
                putExtra("PAGE_TYPE", pageType)
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
                showFloatingTriggerAssociationDialog(pageId, containerId, isHybridGrid, pageType)
            }
        }
        addItem(android.R.drawable.ic_menu_search, "Dictionary") {
            handleElementSelected("system:dictionary_floating", "Dictionary")
        }
        addItem(com.example.R.drawable.ic_library_books, "eBook Reader") {
            handleElementSelected("system:ebook_reader", "eBook Reader")
        }
        addItem(android.R.drawable.ic_menu_view, "Floating PWA") {
            handleElementSelected("system:floating_pwa", "Floating PWA")
        }
        addItem(android.R.drawable.ic_menu_edit, "Note") {
            com.example.core.LogKeeper.log(
                this,
                "MiniAppLaunchRouting",
                "ROUTING_DIAG: ELEMENT_PICKER_ITEM_CLICKED elementId=system:test_notes isHybrid=$isHybridGrid pageId=$pageId containerId=$containerId"
            )
            handleElementSelected("system:test_notes", "Note")
        }

        scrollView.addView(contentLayout)
        mainLayout.addView(scrollView)
        
        setContentView(mainLayout)
    }

    private fun handleElementSelected(id: String, displayName: String? = null) {
        val pageId = intent.getStringExtra("PAGE_ID")
        val containerId = intent.getStringExtra("CONTAINER_ID") ?: "sidebar"
        val isFloatingTriggerSelection = intent.getBooleanExtra("IS_FLOATING_TRIGGER_SELECTION", false)
        val isHybridGrid = intent.getBooleanExtra("IS_HYBRID_GRID", false) && !isFloatingTriggerSelection

        if (isFloatingTriggerSelection) {
            val validation = com.example.feature.floating.trigger.FloatingTriggerCompat
                .validateTarget(this, id)
            if (validation !is com.example.feature.floating.trigger.FloatingTriggerTargetValidation.ValidStandalone) {
                val reason = (validation as? com.example.feature.floating.trigger.FloatingTriggerTargetValidation.Unsupported)?.reason
                    ?: "This Sidebar element cannot run as a standalone Floating Trigger."
                showUnsupportedFloatingTriggerExplanation(reason)
                return
            }
            finishWithId(id)
            return
        }

        com.example.core.LogKeeper.log(
            this,
            "MiniAppLaunchRouting",
            "ROUTING_DIAG: HANDLE_ELEMENT_SELECTED elementId=$id isHybrid=$isHybridGrid pageId=$pageId containerId=$containerId"
        )
        if (isHybridGrid && !pageId.isNullOrEmpty()) {
            val cols = if (id.startsWith("widget:") || id.startsWith("popup_widget:")) 2 else 1
            val rows = if (id.startsWith("widget:") || id.startsWith("popup_widget:")) 2 else 1
            com.example.feature.element.ElementPlacementHelper.addElementToHybridGrid(
                this,
                pageId,
                id,
                cols = cols,
                rows = rows,
                containerId = containerId
            )
            val name = displayName ?: when {
                id.startsWith("app:") -> id.removePrefix("app:")
                id.startsWith("pwa:") -> "Offline PWA"
                id.startsWith("link:") -> "Link"
                id.startsWith("quick_call:") -> com.example.feature.quickcall.QuickCallStore.getInstance(this).getRecordByElementId(id)?.displayName ?: "Quick Call"
                id.startsWith("system:") -> id.removePrefix("system:").replace("_", " ").replaceFirstChar { it.uppercase() }
                id.startsWith("widget:") -> "Widget"
                id.startsWith("popup_widget:") -> "Popup Widget"
                id.startsWith("folder:") -> "Folder"
                id.startsWith("spacer:") -> "Spacer"
                else -> "Element"
            }
            android.widget.Toast.makeText(this, "Added: $name", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            finishWithId(id)
        }
    }
    
    private fun finishWithId(id: String) {
        val isFloatingTriggerSelection = intent.getBooleanExtra("IS_FLOATING_TRIGGER_SELECTION", false)
        val targetId = com.example.feature.element.ElementPlacementHelper.assignInstanceIfNote(id)
        val containerId = intent.getStringExtra("CONTAINER_ID")
        com.example.core.LogKeeper.log(
            this,
            "MiniAppLaunchRouting",
            "ROUTING_DIAG: FINISH_WITH_ELEMENT_ID elementId=$targetId containerId=$containerId"
        )
        val resultIntent = Intent().apply {
            putExtra("ELEMENT_ID", targetId)
            if (containerId != null) {
                putExtra("CONTAINER_ID", containerId)
            }
        }
        setResult(Activity.RESULT_OK, resultIntent)

        if (!isFloatingTriggerSelection) {
            val handleId = intent.getStringExtra("handle_id")
            val gesture = intent.getStringExtra("gesture")
            if (!handleId.isNullOrEmpty() && !gesture.isNullOrEmpty()) {
                com.example.core.HandleManager.getInstance(this).configureGesture(handleId, gesture, id)
            } else if (intent?.action == "SELECT_ELEMENT_FOR_HANDLE") {
                val prefix = intent.getStringExtra("handle_prefix") ?: ""
                val gest = intent.getStringExtra("gesture") ?: ""
                if (prefix.isNotEmpty() && gest.isNotEmpty()) {
                    val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("${prefix}$gest", "open_element:$id").apply()

                    // Notify service to reload configuration
                    val updateIntent = Intent(this, com.example.core.HandleService::class.java).apply {
                        action = "UPDATE_CONFIG"
                    }
                    startService(updateIntent)
                }
            }
        }
        finish()
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        com.example.core.LogKeeper.writeLog("AddElementActivity", "onActivityResult req=$requestCode res=$resultCode data=$data")
        if (resultCode == Activity.RESULT_OK && data != null) {
            val id = data.getStringExtra("ELEMENT_ID")
            val displayName = data.getStringExtra("DISPLAY_NAME")
            if (id != null) {
                if (requestCode == 700) {
                    val validation = com.example.feature.floating.trigger.FloatingTriggerCompat
                        .validateTarget(this, id)
                    if (validation !is com.example.feature.floating.trigger.FloatingTriggerTargetValidation.ValidStandalone) {
                        val reason = (validation as? com.example.feature.floating.trigger.FloatingTriggerTargetValidation.Unsupported)?.reason
                            ?: "This Sidebar element cannot run as a standalone Floating Trigger."
                        showUnsupportedFloatingTriggerExplanation(reason)
                        return
                    }
                    val record = com.example.feature.floating.trigger.FloatingTriggerManager
                        .getInstance(this)
                        .ensureActiveTrigger(id, displayName)
                    if (record == null) {
                        showUnsupportedFloatingTriggerExplanation(
                            "Could not associate Floating Trigger with the selected Sidebar element."
                        )
                        return
                    }
                    handleElementSelected("floating_trigger:$id", displayName ?: "Trigger: ${record.label}")
                    return
                }
                val finalId = if (requestCode == 500 && id.startsWith("widget:")) {
                    "popup_widget:" + id.removePrefix("widget:")
                } else {
                    id
                }
                handleElementSelected(finalId, displayName)
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

    private fun showFloatingTriggerAssociationDialog(
        pageId: String?,
        containerId: String,
        isHybridGrid: Boolean,
        pageType: String?
    ) {
        val existingElements = com.example.feature.floating.trigger.FloatingTriggerStore
            .getInstance(this)
            .discoverExistingSidebarGridElements()

        if (existingElements.isEmpty()) {
            launchFullFloatingTriggerPicker(pageId, containerId, pageType)
            return
        }

        val labels = existingElements.map { it.label }.toMutableList().apply {
            add("Browse all Sidebar elements…")
        }.toTypedArray()

        android.app.AlertDialog.Builder(this)
            .setTitle("Associate Floating Trigger")
            .setItems(labels) { _, which ->
                if (which < existingElements.size) {
                    val chosen = existingElements[which]
                    val record = com.example.feature.floating.trigger.FloatingTriggerManager
                        .getInstance(this)
                        .ensureActiveTrigger(chosen.id, chosen.label)
                    if (record != null) {
                        handleElementSelected("floating_trigger:${chosen.id}", "Trigger: ${chosen.label}")
                    } else {
                        val reason = com.example.feature.floating.trigger.FloatingTriggerCompat
                            .unsupportedReason(this, chosen.id)
                            ?: "Selected Sidebar element cannot run as a standalone Floating Trigger."
                        showUnsupportedFloatingTriggerExplanation(reason)
                    }
                } else {
                    launchFullFloatingTriggerPicker(pageId, containerId, pageType)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchFullFloatingTriggerPicker(
        pageId: String?,
        containerId: String,
        pageType: String?
    ) {
        val intent = Intent(this, AddElementActivity::class.java).apply {
            putExtra("IS_FLOATING_TRIGGER_SELECTION", true)
            putExtra("PAGE_ID", pageId)
            putExtra("CONTAINER_ID", containerId)
            putExtra("IS_HYBRID_GRID", false)
            putExtra("PAGE_TYPE", pageType)
        }
        startActivityForResult(intent, 700)
    }

    private fun showUnsupportedFloatingTriggerExplanation(reason: String) {
        com.example.core.LogKeeper.log(this, "FloatingTriggerPicker", "UNSUPPORTED_TARGET reason=$reason")
        if (!isFinishing && !isDestroyed) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Unsupported Floating Trigger Target")
                .setMessage(reason)
                .setPositiveButton("OK", null)
                .show()
        } else {
            android.widget.Toast.makeText(this, reason, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
