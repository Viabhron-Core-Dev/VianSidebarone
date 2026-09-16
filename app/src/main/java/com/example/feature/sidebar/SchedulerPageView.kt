package com.example.feature.sidebar

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SchedulerPageView(
    context: Context,
    private val scope: CoroutineScope,
    private val onHeightChanged: ((Int) -> Unit)? = null
) : FrameLayout(context), SidebarPageControllable {
    private val db = AppDatabase.getDatabase(context)
    private val recyclerView: RecyclerView
    private val tvEmpty: TextView
    private val adapter: TaskAdapter
    
    private val llList: LinearLayout
    private val llEdit: LinearLayout
    private val fabAdd: View
    
    // Edit UI
    private val tvEditTitle: TextView
    private val spinnerTags: Spinner
    private val btnAddTagToReminder: ImageView
    private val tvSelectedTags: TextView
    
    private val npHour: NumberPicker
    private val npMinute: NumberPicker
    private val npSecond: NumberPicker
    private val npAmPm: NumberPicker
    
    private val btnCancel: Button
    private val btnSave: Button
    
    private var editingTask: SchedulerTask? = null
    private val selectedTags = mutableListOf<String>()
    
    init {
        com.example.core.LogKeeper.writeLog("Scheduler", "Opened short reminders page")
        LayoutInflater.from(context).inflate(R.layout.page_scheduler, this, true)
        
        llList = findViewById(R.id.ll_list)
        llEdit = findViewById(R.id.ll_edit)
        fabAdd = findViewById(R.id.fab_add)
        
        recyclerView = findViewById(R.id.recycler_view)
        tvEmpty = findViewById(R.id.tv_empty)
        recyclerView.layoutManager = LinearLayoutManager(context)
        adapter = TaskAdapter(
            onClick = { task -> markAsDone(task) },
            onLongClick = { task -> showOptions(task) }
        )
        recyclerView.adapter = adapter
        
        fabAdd.setOnClickListener { showEditUi(null) }
        
        // Init Edit UI
        tvEditTitle = findViewById(R.id.tv_edit_title)
        spinnerTags = findViewById(R.id.spinner_tags)
        btnAddTagToReminder = findViewById(R.id.btn_add_tag_to_reminder)
        tvSelectedTags = findViewById(R.id.tv_selected_tags)
        
        npHour = findViewById(R.id.np_hour)
        npHour.minValue = 1
        npHour.maxValue = 12
        
        npMinute = findViewById(R.id.np_minute)
        npMinute.minValue = 0
        npMinute.maxValue = 59
        npMinute.setFormatter { String.format("%02d", it) }
        
        npSecond = findViewById(R.id.np_second)
        npSecond.minValue = 0
        npSecond.maxValue = 59
        npSecond.setFormatter { String.format("%02d", it) }
        
        npAmPm = findViewById(R.id.np_ampm)
        npAmPm.minValue = 0
        npAmPm.maxValue = 1
        npAmPm.displayedValues = arrayOf("AM", "PM")
        
        btnCancel = findViewById(R.id.btn_cancel)
        btnSave = findViewById(R.id.btn_save)
        
        btnCancel.setOnClickListener { hideEditUi() }
        btnSave.setOnClickListener { saveTask() }
        
        btnAddTagToReminder.setOnClickListener {
            val tag = spinnerTags.selectedItem as? String
            if (tag != null && !selectedTags.contains(tag)) {
                selectedTags.add(tag)
                updateSelectedTagsText()
            }
        }
        
        // loadTasks() called in onPageSelected now
    }
    
    private fun loadTags() {
        val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
        val tagsStr = prefs.getString("scheduler_tags", "Work,Personal,Urgent,Misc") ?: "Work,Personal,Urgent,Misc"
        val tagsList = tagsStr.split(",").filter { it.isNotBlank() }
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, tagsList)
        spinnerTags.adapter = adapter
    }
    
    private fun updateSelectedTagsText() {
        tvSelectedTags.text = "Selected: ${selectedTags.joinToString(", ")}"
    }

    private var taskJob: Job? = null

    override fun onPageSelected() {
        if (taskJob?.isActive == true) return
        loadTasks()
    }

    override fun onPageUnselected() {
        taskJob?.cancel()
        taskJob = null
    }

    private fun loadTasks() {
        taskJob = scope.launch(Dispatchers.Main) {
            db.schedulerTaskDao().getAllTasks().collect { tasks ->
                val activeTasks = tasks.filter { it.status == "PENDING" }.sortedBy { it.timeMillis }
                adapter.submitList(activeTasks)
                
                // Automatically mark past tasks as unfinished
                val now = System.currentTimeMillis()
                tasks.filter { it.status == "PENDING" && it.timeMillis < now }.forEach { pastTask ->
                    scope.launch(Dispatchers.IO) {
                        db.schedulerTaskDao().update(pastTask.copy(status = "UNFINISHED"))
                    }
                }
                
                if (activeTasks.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    tvEmpty.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                }
                post {
                    measure(
                        MeasureSpec.makeMeasureSpec(width.takeIf { it > 0 } ?: (330 * resources.displayMetrics.density).toInt(), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                    )
                    val density = resources.displayMetrics.density
                    val minH = if (activeTasks.isEmpty()) (100 * density).toInt() else (140 * density).toInt()
                    val maxH = (520 * density).toInt()
                    val targetH = measuredHeight.coerceIn(minH, maxH)
                    onHeightChanged?.invoke(targetH)
                }
            }
        }
    }
    
    private fun showEditUi(task: SchedulerTask?) {
        editingTask = task
        selectedTags.clear()
        loadTags()
        
        val cal = Calendar.getInstance()
        if (task != null) {
            tvEditTitle.text = "Edit Reminder"
            if (task.tags.isNotBlank()) {
                selectedTags.addAll(task.tags.split(","))
            }
            cal.timeInMillis = task.timeMillis
        } else {
            tvEditTitle.text = "Add Reminder"
            cal.add(Calendar.MINUTE, 5) // Default to 5 minutes from now
        }
        
        updateSelectedTagsText()
        
        var hour = cal.get(Calendar.HOUR)
        if (hour == 0) hour = 12
        npHour.value = hour
        npMinute.value = cal.get(Calendar.MINUTE)
        npSecond.value = cal.get(Calendar.SECOND)
        npAmPm.value = cal.get(Calendar.AM_PM)
        
        llList.visibility = View.GONE
        fabAdd.visibility = View.GONE
        llEdit.visibility = View.VISIBLE
    }
    
    private fun hideEditUi() {
        llEdit.visibility = View.GONE
        llList.visibility = View.VISIBLE
        fabAdd.visibility = View.VISIBLE
    }
    
    private fun saveTask() {
        var hour = npHour.value
        val minute = npMinute.value
        val second = npSecond.value
        val amPm = npAmPm.value
        
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
        
        scope.launch(Dispatchers.IO) {
            if (editingTask != null) {
                db.schedulerTaskDao().update(task)
            } else {
                db.schedulerTaskDao().insert(task)
            }
        }
        
        hideEditUi()
    }
    
    private fun markAsDone(task: SchedulerTask) {
        scope.launch(Dispatchers.IO) {
            db.schedulerTaskDao().update(task.copy(status = "DONE"))
        }
    }
    
    private fun showOptions(task: SchedulerTask) {
        val options = arrayOf("Edit", "Delete")
        AlertDialog.Builder(context)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditUi(task)
                    1 -> scope.launch(Dispatchers.IO) { db.schedulerTaskDao().delete(task) }
                }
            }
            .show()
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
