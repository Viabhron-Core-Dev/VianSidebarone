package com.example

import com.example.feature.settings.AddElementActivity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.feature.sidebar.SidebarAppsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Collections

class SidebarEditActivity : ComponentActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: EditAdapter
    private lateinit var prefs: android.content.SharedPreferences
    val localIds = mutableListOf<String>()
    private var folderUuid: String? = null
    private var pageId: String = "default_apps"
    private var folderName: String = "Folder"
    private var folderColor: String = "#444444"
    private var folderStyle: Int = 0
    private var totalCols = 3
    private var totalRows = 3

    
    private lateinit var manager: SidebarAppsManager
    private lateinit var myPrefKey: String
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        prefs = getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        folderUuid = intent.getStringExtra("FOLDER_UUID")
        pageId = intent.getStringExtra("PAGE_ID") ?: "default_apps"
        val handleId = intent.getStringExtra("HANDLE_ID") ?: intent.getStringExtra("CONTAINER_ID") ?: "sidebar"
        myPrefKey = "sidebar_apps_" + handleId + "_" + pageId
        
        manager = SidebarAppsManager(this, prefs, serviceScope, myPrefKey) {
            runOnUiThread {
                if (::adapter.isInitialized) {
                    adapter.notifyDataSetChanged()
                }
            }
        }
        loadLocalIds()

        if (folderUuid == null) {
            val handleId = intent.getStringExtra("HANDLE_ID") ?: "sidebar"
            val c = prefs.getInt("handle_${handleId}_page_${pageId}_columns", -1)
            if (c == -1) {
                totalCols = prefs.getInt("handle_${handleId}_columns", prefs.getInt("sidebar_columns", 3))
                totalRows = prefs.getInt("handle_${handleId}_rows", prefs.getInt("sidebar_rows", 3))
            } else {
                totalCols = c
                totalRows = prefs.getInt("handle_${handleId}_page_${pageId}_rows", 3)
            }
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setPadding(32, 32, 32, 32)
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.BLACK)
        }        // Header
        val fullHeaderLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 8)
        }

        val controlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 4)
        }
        
        val colsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        colsLayout.addView(TextView(this).apply { text = "Cols: "; textSize = 12f; setTextColor(Color.LTGRAY) })
        val btnColMinus = Button(this).apply { text = "-"; textSize = 14f; setPadding(0, 0, 0, 0); layoutParams = LinearLayout.LayoutParams(80, 80); setOnClickListener { if (totalCols > 1) { totalCols--; updateColsDisplay(); updateGrid() } } }
        val tvCols = TextView(this).apply { id = 101; setTextColor(Color.WHITE); textSize = 14f; setPadding(8, 0, 8, 0); text = totalCols.toString() }
        val btnColPlus = Button(this).apply { text = "+"; textSize = 14f; setPadding(0, 0, 0, 0); layoutParams = LinearLayout.LayoutParams(80, 80); setOnClickListener { totalCols++; updateColsDisplay(); updateGrid() } }
        colsLayout.addView(btnColMinus); colsLayout.addView(tvCols); colsLayout.addView(btnColPlus)
        controlsLayout.addView(colsLayout)
        
        val rowsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rowsLayout.addView(TextView(this).apply { text = "Rows: "; textSize = 12f; setTextColor(Color.LTGRAY) })
        val btnRowMinus = Button(this).apply { text = "-"; textSize = 14f; setPadding(0, 0, 0, 0); layoutParams = LinearLayout.LayoutParams(80, 80); setOnClickListener { if (totalRows > 1) { totalRows--; updateRowsDisplay() } } }
        val tvRows = TextView(this).apply { id = 102; setTextColor(Color.WHITE); textSize = 14f; setPadding(8, 0, 8, 0); text = totalRows.toString() }
        val btnRowPlus = Button(this).apply { text = "+"; textSize = 14f; setPadding(0, 0, 0, 0); layoutParams = LinearLayout.LayoutParams(80, 80); setOnClickListener { totalRows++; updateRowsDisplay() } }
        rowsLayout.addView(btnRowMinus); rowsLayout.addView(tvRows); rowsLayout.addView(btnRowPlus)
        controlsLayout.addView(rowsLayout)
        fullHeaderLayout.addView(controlsLayout)

        val buttonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_VERTICAL
        }

        fun createSmallBtn(txt: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = txt
                textSize = 10f
                setPadding(4, 0, 4, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { onClick() }
            }
        }

        val btnAdd = createSmallBtn("Add") {
            val intent = Intent(this@SidebarEditActivity, AddElementActivity::class.java)
            startActivityForResult(intent, 100)
        }
        val btnEmpty = createSmallBtn("Empty") {
            localIds.add("spacer:${System.currentTimeMillis()}:{\"heightDp\":56}")
            adapter.notifyItemInserted(localIds.size - 1)
        }
        val btnSave = createSmallBtn("Save") {
            saveIds()
            finish()
        }
        val btnCancel = createSmallBtn("Cancel") {
            finish()
        }
        
        buttonsLayout.addView(btnAdd)
        buttonsLayout.addView(btnEmpty)
        buttonsLayout.addView(btnSave)
        buttonsLayout.addView(btnCancel)
        fullHeaderLayout.addView(buttonsLayout)
        
        mainLayout.addView(fullHeaderLayout)


        adapter = EditAdapter()
        recyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = GridLayoutManager(this@SidebarEditActivity, totalCols)
            this.adapter = this@SidebarEditActivity.adapter
        }
        mainLayout.addView(recyclerView)

        setContentView(mainLayout)
        setupItemTouchHelper()
    }
    
    private fun updateColsDisplay() {
        findViewById<TextView>(101)?.text = totalCols.toString()
    }
    private fun updateRowsDisplay() {
        findViewById<TextView>(102)?.text = totalRows.toString()
    }
    private fun updateGrid() {
        if (::recyclerView.isInitialized) {
            recyclerView.layoutManager = GridLayoutManager(this, totalCols)
        }
    }

    override fun onBackPressed() {
        saveIds()
        super.onBackPressed()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            val id = data?.getStringExtra("ELEMENT_ID")
            if (id != null) {
                localIds.add(id)
                adapter.notifyItemInserted(localIds.size - 1)
                saveIds() // Auto-save after addition
            }
        } else if (requestCode == 200 && resultCode == RESULT_OK) {
            val updatedFolder = data?.getStringExtra("UPDATED_FOLDER")
            val uuid = data?.getStringExtra("FOLDER_UUID")
            if (updatedFolder != null && uuid != null) {
                val index = localIds.indexOfFirst { it.startsWith("folder:$uuid:") }
                if (index != -1) {
                    localIds[index] = updatedFolder
                    adapter.notifyItemChanged(index)
                    saveIds() // Auto-save after folder edit
                }
            }
        }
    }

    private fun loadLocalIds() {
        localIds.clear()
        val fullFolderId = intent.getStringExtra("FOLDER_FULL_ID")
        if (folderUuid != null && fullFolderId != null && fullFolderId.startsWith("folder:")) {
            try {
                val parts = fullFolderId.split(":", limit = 3)
                val folderDataStr = parts[2]
                val obj = org.json.JSONObject(folderDataStr)
                folderName = obj.optString("name", "Folder")
                folderColor = obj.optString("colorHex", "#444444")
                folderStyle = obj.optInt("folderStyle", 0)
                totalCols = obj.optInt("popupColumns", 0)
                if (totalCols <= 0) totalCols = prefs.getInt("sidebar_columns", 3)
                totalRows = obj.optInt("popupRows", 0)
                
                val itemsArr = obj.optJSONArray("items") ?: org.json.JSONArray()
                for (j in 0 until itemsArr.length()) {
                    localIds.add(itemsArr.getString(j))
                }
            } catch (e: Exception) {}
        } else {
            var jsonStr = prefs.getString(myPrefKey, null)
            if (jsonStr == null) {
                // Fallback to legacy/general keys if not found for specific handle
                jsonStr = prefs.getString("sidebar_apps_${pageId}", null)
                    ?: prefs.getString("sidebar_apps", null)
            }
            if (jsonStr != null && jsonStr.isNotEmpty()) {
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    localIds.add(arr.getString(i))
                }
            }
        }
    }

    private fun saveIds() {
        val arr = JSONArray()
        localIds.forEach { arr.put(it) }
        
        if (folderUuid != null) {
            val obj = org.json.JSONObject()
            obj.put("name", folderName)
            obj.put("colorHex", folderColor)
            obj.put("folderStyle", folderStyle)
            obj.put("popupColumns", totalCols)
            obj.put("popupRows", totalRows)
            obj.put("items", arr)
            val newItemStr = "folder:$folderUuid:${obj.toString()}"
            
            val resultIntent = Intent().apply { 
                putExtra("UPDATED_FOLDER", newItemStr)
                putExtra("FOLDER_UUID", folderUuid)
            }
            setResult(RESULT_OK, resultIntent)
            // Removed direct save to prefs; parent grid will save
        } else {
            val handleId = intent.getStringExtra("HANDLE_ID") ?: intent.getStringExtra("CONTAINER_ID") ?: "sidebar"
            val appsJson = arr.toString()
            prefs.edit()
                .putString(myPrefKey, appsJson)
                .putString("sidebar_apps_$pageId", appsJson)
                .putInt("handle_${handleId}_page_${pageId}_columns", totalCols)
                .putInt("handle_${handleId}_page_${pageId}_rows", totalRows)
                .commit()
            com.example.core.OverlaySyncManager.syncString(this, myPrefKey, appsJson)
            com.example.core.OverlaySyncManager.syncString(this, "sidebar_apps_$pageId", appsJson)
            com.example.core.OverlaySyncManager.syncInt(this, "handle_${handleId}_page_${pageId}_columns", totalCols)
            com.example.core.OverlaySyncManager.syncInt(this, "handle_${handleId}_page_${pageId}_rows", totalRows)
            com.example.util.AppLogger.d("SidebarEdit", "Saved ${localIds.size} items to apps grid.")
        }
        
        try {
            val handleIntent = Intent(this, com.example.core.HandleService::class.java).apply {
                action = com.example.core.HandleService.ACTION_RELOAD_HANDLES
            }
            sendBroadcast(Intent(com.example.core.HandleService.ACTION_RELOAD_HANDLES))
            if (com.example.feature.miniapps.reader.FloatingReaderService.isRunning) {
                val updateIntent = Intent(this, com.example.feature.miniapps.reader.FloatingReaderService::class.java).apply {
                    action = "UPDATE_CONFIG"
                }
                startService(updateIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupItemTouchHelper() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false
                Collections.swap(localIds, fromPos, toPos)
                adapter.notifyItemMoved(fromPos, toPos)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled(): Boolean = true
        }
        ItemTouchHelper(callback).attachToRecyclerView(recyclerView)
    }

    inner class EditAdapter : RecyclerView.Adapter<EditAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val iconView: ImageView = view.findViewById(1)
            val nameView: TextView = view.findViewById(2)
            val btnRemove: ImageView = view.findViewById(3)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val density = resources.displayMetrics.density
            val layout = FrameLayout(this@SidebarEditActivity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (72 * density).toInt()
                )
                setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            }
            
            val contentLayout = LinearLayout(this@SidebarEditActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#33FFFFFF"))
            }

            val iconView = ImageView(this@SidebarEditActivity).apply {
                id = 1
                layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
            }

            val nameView = TextView(this@SidebarEditActivity).apply {
                id = 2
                setTextColor(Color.WHITE)
                textSize = 10f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (4 * density).toInt()
                }
                maxLines = 1
            }

            contentLayout.addView(iconView)
            contentLayout.addView(nameView)
            
            val btnRemove = ImageView(this@SidebarEditActivity).apply {
                id = 3
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                layoutParams = FrameLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt()).apply {
                    gravity = Gravity.TOP or Gravity.END
                }
                setColorFilter(Color.RED)
            }

            layout.addView(contentLayout)
            layout.addView(btnRemove)

            return ViewHolder(layout)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val id = localIds[position]
            
            if (id.startsWith("empty:")) {
                holder.iconView.setImageDrawable(null)
                holder.nameView.text = "Empty Space"
            } else {
                serviceScope.launch {
                    val item = manager.parseId(id)
                    val iconBitmap = manager.getIconBitmap(id)
                    withContext(Dispatchers.Main) {
                        if (item != null) {
                            holder.nameView.text = item.label
                        } else {
                            holder.nameView.text = "Unknown"
                        }
                        holder.iconView.tag = id
                        if (item is com.example.feature.sidebar.SidebarItem.Folder) {
                            holder.iconView.setImageDrawable(null)
                            holder.iconView.clearColorFilter()
                            holder.iconView.setBackgroundColor(Color.TRANSPARENT)
                            
                            val cHex = try { Color.parseColor(item.colorHex) } catch(e:Exception){ Color.parseColor("#00BFA5") }
                            val iconC = Color.WHITE
                            
                            val miniIcons = item.items.take(9).mapNotNull { manager.getIconBitmap(it) }
                            holder.iconView.setImageDrawable(com.example.feature.sidebar.FolderStyleDrawable(item.folderStyle, cHex, iconC, miniIcons))
                        } else if (iconBitmap != null) {
                            holder.iconView.setImageBitmap(iconBitmap)
                        } else {
                            holder.iconView.setImageResource(android.R.drawable.sym_def_app_icon)
                            if (item is com.example.feature.sidebar.SidebarItem.App) {
                                val loaded = manager.loadIcon(item.packageName)
                                if (loaded != null && holder.iconView.tag == id) {
                                    holder.iconView.setImageBitmap(loaded)
                                }
                            } else if (item is com.example.feature.sidebar.SidebarItem.IntentAction) {
                                var customIconLoaded = false
                                if (item.iconPath != null) {
                                    try {
                                        val file = java.io.File(item.iconPath)
                                        if (file.exists()) {
                                            val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                                            if (bmp != null && holder.iconView.tag == id) {
                                                holder.iconView.setImageBitmap(bmp)
                                                customIconLoaded = true
                                            }
                                        }
                                    } catch(e: Exception){}
                                }
                                if (!customIconLoaded) {
                                    try {
                                        val uriStr = item.uri
                                        val pkg = android.content.Intent.parseUri(uriStr, android.content.Intent.URI_INTENT_SCHEME).`package` ?: android.content.Intent.parseUri(uriStr, android.content.Intent.URI_INTENT_SCHEME).component?.packageName ?: ""
                                        val loaded = manager.loadIcon(pkg)
                                        if (loaded != null && holder.iconView.tag == id) {
                                            holder.iconView.setImageBitmap(loaded)
                                        }
                                    } catch (e: Exception) {}
                                }
                            }
                        }
                    }
                }
            }

            holder.itemView.setOnClickListener {
                if (id.startsWith("folder:")) {
                    val uuid = id.split(":")[1]
                    val intent = Intent(this@SidebarEditActivity, SidebarEditActivity::class.java).apply {
                        putExtra("FOLDER_UUID", uuid)
                        putExtra("FOLDER_FULL_ID", id)
                    }
                    startActivityForResult(intent, 200)
                }
            }
            holder.btnRemove.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    localIds.removeAt(pos)
                    notifyItemRemoved(pos)
                }
            }
        }

        override fun getItemCount(): Int = localIds.size
    }
}
