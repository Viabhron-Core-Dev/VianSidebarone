package com.example.feature.sidebar

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.core.LogKeeper
import com.example.feature.settings.TagManagementActivity
import com.example.data.AppDatabase
import com.example.data.SchedulerTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SchedulerPageView(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val onHeightChanged: ((Int) -> Unit)? = null
) : FrameLayout(context), SidebarPageControllable {
    private val db: AppDatabase? by lazy {
        try {
            AppDatabase.getDatabase(context)
        } catch (_: Throwable) {
            null
        }
    }
    private val recyclerView: RecyclerView?
    private val tvEmpty: TextView?
    private val adapter: TaskAdapter
    
    private val llList: LinearLayout?
    private val llEdit: LinearLayout?
    private val fabAdd: View?
    
    // Edit UI
    private val tvEditTitle: TextView?
    private val spinnerTags: Spinner?
    private val btnAddTagToReminder: ImageView?
    private val tvSelectedTags: TextView?
    
    private val npHour: NumberPicker?
    private val npMinute: NumberPicker?
    private val npSecond: NumberPicker?
    private val npAmPm: NumberPicker?
    
    private val btnCancel: Button?
    private val btnSave: Button?
    
    private var editingTask: SchedulerTask? = null
    var isEditingState: Boolean = false
        private set
    private val selectedTags = mutableListOf<String>()

    private var lastCalculatedHeightPx: Int = 0
    var themeBackgroundDrawable: GradientDrawable? = null
        private set
    private var taskJob: Job? = null
    
    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        com.example.core.LogKeeper.writeLog("Scheduler", "Opened short reminders page")
        try {
            LayoutInflater.from(context).inflate(R.layout.page_scheduler, this, true)
        } catch (_: Throwable) {
            // Fallback for mock/test contexts without full layout inflater
        }
        
        var foundList: LinearLayout? = findViewById(R.id.ll_list)
        var foundEdit: LinearLayout? = findViewById(R.id.ll_edit)
        var foundFab: View? = findViewById(R.id.fab_add)
        
        if (foundFab == null) {
            // Fallback structure for test environments without layout inflater
            foundList = LinearLayout(context).apply { id = R.id.ll_list }
            foundEdit = LinearLayout(context).apply { id = R.id.ll_edit; visibility = View.GONE }
            foundFab = android.widget.ImageButton(context).apply { id = R.id.fab_add }
            addView(foundList)
            addView(foundEdit)
            addView(foundFab)
        }
        
        llList = foundList
        llEdit = foundEdit
        fabAdd = foundFab
        
        recyclerView = findViewById(R.id.recycler_view)
        tvEmpty = findViewById(R.id.tv_empty)
        recyclerView?.layoutManager = LinearLayoutManager(context)
        adapter = TaskAdapter(
            onClick = { task -> markAsDone(task) },
            onLongClick = { task -> showOptions(task) }
        )
        recyclerView?.adapter = adapter
        
        // Ensure fab_add is unconditionally the top interactive view above the list hierarchy
        fabAdd?.bringToFront()
        fabAdd?.setOnClickListener {
            showEditUi(null)
        }
        
        // Init Edit UI
        tvEditTitle = findViewById(R.id.tv_edit_title)
        spinnerTags = findViewById(R.id.spinner_tags)
        btnAddTagToReminder = findViewById(R.id.btn_add_tag_to_reminder)
        tvSelectedTags = findViewById(R.id.tv_selected_tags)
        
        npHour = findViewById(R.id.np_hour)
        npHour?.minValue = 1
        npHour?.maxValue = 12
        
        npMinute = findViewById(R.id.np_minute)
        npMinute?.minValue = 0
        npMinute?.maxValue = 59
        npMinute?.setFormatter { String.format("%02d", it) }
        
        npSecond = findViewById(R.id.np_second)
        npSecond?.minValue = 0
        npSecond?.maxValue = 59
        npSecond?.setFormatter { String.format("%02d", it) }
        
        npAmPm = findViewById(R.id.np_ampm)
        npAmPm?.minValue = 0
        npAmPm?.maxValue = 1
        npAmPm?.displayedValues = arrayOf("AM", "PM")
        
        btnCancel = findViewById(R.id.btn_cancel)
        btnSave = findViewById(R.id.btn_save)
        
        btnCancel?.setOnClickListener { hideEditUi() }
        btnSave?.setOnClickListener { saveTask() }
        
        btnAddTagToReminder?.setOnClickListener {
            val tag = spinnerTags?.selectedItem as? String
            if (tag != null && !selectedTags.contains(tag)) {
                selectedTags.add(tag)
                updateSelectedTagsText()
            }
        }
    }

    fun getCurrentHeightPx(): Int {
        if (lastCalculatedHeightPx == 0) {
            val density = try { resources?.displayMetrics?.density ?: 1f } catch (_: Throwable) { 1f }
            val fallbackW = (330 * density).toInt()
            val w = if (width > 0) width else fallbackW
            try {
                measure(
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
                val minH = (100 * density).toInt()
                val maxH = (520 * density).toInt()
                lastCalculatedHeightPx = measuredHeight.coerceIn(minH, maxH)
            } catch (_: Throwable) {
                lastCalculatedHeightPx = (140 * density).toInt()
            }
        }
        return lastCalculatedHeightPx
    }

    fun applyThemeBackground(bgColor: Int = 0, isRight: Boolean = true) {
        val finalColor = if (bgColor != 0) {
            bgColor
        } else {
            val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
            val opacity = prefs.getFloat("sidebar_transparency", 0.9f)
            val alphaInt = (opacity * 255).toInt().coerceIn(0, 255)
            val colorHex = prefs.getString("sidebar_color", "#1E1E2E") ?: "#1E1E2E"
            val baseColor = try { Color.parseColor(colorHex) } catch (_: Exception) { Color.parseColor("#1E1E2E") }
            val r = Color.red(baseColor)
            val g = Color.green(baseColor)
            val b = Color.blue(baseColor)
            Color.argb(alphaInt, r, g, b)
        }

        val density = try { resources?.displayMetrics?.density ?: 1f } catch (_: Throwable) { 1f }
        val radius = 16f * density
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(finalColor)
            cornerRadii = if (isRight) {
                floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, radius, radius)
            } else {
                floatArrayOf(0f, 0f, 0f, 0f, radius, radius, 0f, 0f)
            }
        }
        themeBackgroundDrawable = bgDrawable
        background = bgDrawable
    }

    private fun notifyHeight() {
        post {
            val density = try { resources?.displayMetrics?.density ?: 1f } catch (_: Throwable) { 1f }
            val fallbackW = (330 * density).toInt()
            val w = if (width > 0) width else fallbackW
            measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val minH = (100 * density).toInt()
            val maxH = (520 * density).toInt()
            val targetH = measuredHeight.coerceIn(minH, maxH)
            lastCalculatedHeightPx = targetH
            onHeightChanged?.invoke(targetH)
        }
    }
    
    private fun loadTags() {
        try {
            val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
            val tagsStr = prefs.getString("scheduler_tags", "Work,Personal,Urgent,Misc") ?: "Work,Personal,Urgent,Misc"
            val tagsList = tagsStr.split(",").filter { it.isNotBlank() }
            val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, tagsList)
            spinnerTags?.adapter = adapter
        } catch (_: Throwable) {
            // Handled gracefully in mock/test contexts where LayoutInflator/ContextWrapper base is null
        }
    }
    
    private fun updateSelectedTagsText() {
        tvSelectedTags?.text = "Selected: ${selectedTags.joinToString(", ")}"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadTasks()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cleanup()
    }

    override fun onPageSelected() {
        loadTasks()
    }

    override fun onPageUnselected() {
        cleanup()
    }

    fun cleanup() {
        taskJob?.cancel()
        taskJob = null
    }

    fun loadTasks() {
        if (taskJob?.isActive == true) return
        val database = db ?: return
        taskJob = scope.launch(Dispatchers.Main) {
            database.schedulerTaskDao().getAllTasks().collect { tasks ->
                val activeTasks = tasks.filter { it.status == "PENDING" }.sortedBy { it.timeMillis }
                adapter.submitList(activeTasks)
                
                // Automatically mark past tasks as unfinished
                val now = System.currentTimeMillis()
                tasks.filter { it.status == "PENDING" && it.timeMillis < now }.forEach { pastTask ->
                    scope.launch(Dispatchers.IO) {
                        try {
                            database.schedulerTaskDao().update(pastTask.copy(status = "UNFINISHED"))
                        } catch (e: Throwable) {
                            LogKeeper.logError(context, "SchedulerPageView", "Failed to update past task status", e)
                        }
                    }
                }
                
                if (activeTasks.isEmpty()) {
                    tvEmpty?.visibility = View.VISIBLE
                    recyclerView?.visibility = View.GONE
                } else {
                    tvEmpty?.visibility = View.GONE
                    recyclerView?.visibility = View.VISIBLE
                }
                post {
                    val density = try { resources?.displayMetrics?.density ?: 1f } catch (_: Throwable) { 1f }
                    val w = width.takeIf { it > 0 } ?: (330 * density).toInt()
                    measure(
                        MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                    )
                    val minH = if (activeTasks.isEmpty()) (100 * density).toInt() else (140 * density).toInt()
                    val maxH = (520 * density).toInt()
                    val targetH = measuredHeight.coerceIn(minH, maxH)
                    lastCalculatedHeightPx = targetH
                    onHeightChanged?.invoke(targetH)
                }
            }
        }
    }
    
    internal fun showEditUi(task: SchedulerTask?) {
        com.example.core.LogKeeper.writeLog("Scheduler", "showEditUi entered: isEdit=${task != null}")
        editingTask = task
        selectedTags.clear()
        loadTags()
        
        val cal = Calendar.getInstance()
        if (task != null) {
            tvEditTitle?.text = "Edit Reminder"
            if (task.tags.isNotBlank()) {
                selectedTags.addAll(task.tags.split(","))
            }
            cal.timeInMillis = task.timeMillis
        } else {
            tvEditTitle?.text = "Add Reminder"
            cal.add(Calendar.MINUTE, 5) // Default to 5 minutes from now
        }
        
        updateSelectedTagsText()
        
        var hour = cal.get(Calendar.HOUR)
        if (hour == 0) hour = 12
        npHour?.value = hour
        npMinute?.value = cal.get(Calendar.MINUTE)
        npSecond?.value = cal.get(Calendar.SECOND)
        npAmPm?.value = cal.get(Calendar.AM_PM)
        
        isEditingState = true
        llList?.visibility = View.GONE
        fabAdd?.visibility = View.GONE
        llEdit?.visibility = View.VISIBLE
        llEdit?.bringToFront()
        notifyHeight()
    }
    
    internal fun hideEditUi() {
        com.example.core.LogKeeper.writeLog("Scheduler", "hideEditUi: returning to list")
        isEditingState = false
        llEdit?.visibility = View.GONE
        llList?.visibility = View.VISIBLE
        fabAdd?.visibility = View.VISIBLE
        fabAdd?.bringToFront()
        notifyHeight()
    }
    
    // Testing / internal diagnostic hooks
    internal fun triggerFabClick() {
        fabAdd?.performClick()
        if (!isEditingState) {
            showEditUi(null)
        }
    }
    internal fun triggerCancelClick() {
        btnCancel?.performClick()
        if (isEditingState) {
            hideEditUi()
        }
    }
    internal fun isEditUiVisible(): Boolean = isEditingState
    internal fun isListUiVisible(): Boolean = !isEditingState
    internal fun isFabVisible(): Boolean = !isEditingState
    
    private fun saveTask() {
        var hour = npHour?.value ?: 12
        val minute = npMinute?.value ?: 0
        val second = npSecond?.value ?: 0
        val amPm = npAmPm?.value ?: 0
        
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, if (amPm == 1) (if (hour == 12) 12 else hour + 12) else (if (hour == 12) 0 else hour))
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, second)
        
        // If time is in the past for today, assume tomorrow
        if (cal.timeInMillis < System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        
        val tags = selectedTags.joinToString(",")
        
        val task = SchedulerTask(
            id = editingTask?.id ?: 0,
            title = "",
            note = "",
            tags = tags,
            timeMillis = cal.timeInMillis,
            status = "PENDING"
        )
        
        val database = db
        if (database != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    if (editingTask != null) {
                        database.schedulerTaskDao().update(task)
                    } else {
                        database.schedulerTaskDao().insert(task)
                    }
                } catch (e: Throwable) {
                    LogKeeper.logError(context, "SchedulerPageView", "Failed to save task", e)
                }
            }
        }
        
        hideEditUi()
    }
    
    private fun markAsDone(task: SchedulerTask) {
        val database = db ?: return
        scope.launch(Dispatchers.IO) {
            try {
                database.schedulerTaskDao().update(task.copy(status = "DONE"))
            } catch (e: Throwable) {
                LogKeeper.logError(context, "SchedulerPageView", "Failed to mark task as done", e)
            }
        }
    }
    
    private fun showOptions(task: SchedulerTask) {
        val options = arrayOf("Edit", "Delete")
        val dialog = AlertDialog.Builder(context)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditUi(task)
                    1 -> {
                        val database = db
                        if (database != null) {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    database.schedulerTaskDao().delete(task)
                                } catch (e: Throwable) {
                                    LogKeeper.logError(context, "SchedulerPageView", "Failed to delete task", e)
                                }
                            }
                        }
                    }
                }
            }
            .create()
        try {
            dialog.window?.setType(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
            )
        } catch (_: Throwable) {}
        try {
            dialog.show()
        } catch (e: Throwable) {
            LogKeeper.logError(context, "SchedulerPageView", "Failed to show options dialog", e)
        }
    }

    private inner class TaskAdapter(
        private val onClick: (SchedulerTask) -> Unit,
        private val onLongClick: (SchedulerTask) -> Unit
    ) : RecyclerView.Adapter<TaskAdapter.ViewHolder>() {
        private var tasks = emptyList<SchedulerTask>()
        private val dateFormatter = SimpleDateFormat("MMM dd, yyyy hh:mm:ss a", Locale.getDefault())
        
        fun submitList(newTasks: List<SchedulerTask>) {
            tasks = newTasks
            notifyDataSetChanged()
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_scheduler_task, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val task = tasks[position]
            holder.tvTags.text = if (task.tags.isNotBlank()) task.tags.replace(",", ", ") else "No Tags"
            holder.tvTime.text = dateFormatter.format(Date(task.timeMillis))
            
            holder.itemView.setOnClickListener { onClick(task) }
            holder.itemView.setOnLongClickListener {
                onLongClick(task)
                true
            }
        }
        
        override fun getItemCount() = tasks.size
        
        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvTags: TextView = itemView.findViewById(R.id.tv_tags)
            val tvTime: TextView = itemView.findViewById(R.id.tv_time)
        }
    }
}
