package com.example.feature.sidebar

import android.app.ActivityManager
import android.content.Context
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.utils.ActiveAppInfo
import com.example.utils.ActiveAppTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ResourcesTrackerPageView(
    context: Context,
    private val scope: CoroutineScope,
    private val onHeightChanged: ((Int) -> Unit)? = null
) : FrameLayout(context), Choreographer.FrameCallback, SidebarPageControllable {

    private val tvRamTotal: TextView
    private val tvRamFree: TextView
    private val tvRamApp: TextView
    private val tvFps: TextView
    private val tvTotalFrames: TextView
    private val rvMiniApps: RecyclerView
    private val llMiniAppsHeader: LinearLayout
    private val ivMiniAppsToggle: ImageView
    private val tvMiniAppsTitle: TextView
    
    private val adapter: MiniAppsAdapter
    private var isMiniAppsExpanded = false
    
    private var isTracking = false
    private var framesCount = 0L
    private var totalFramesRendered = 0L
    private var lastFpsTime = 0L

    init {
        LayoutInflater.from(context).inflate(R.layout.page_resources_tracker, this, true)
        
        tvRamTotal = findViewById(R.id.tv_ram_total)
        tvRamFree = findViewById(R.id.tv_ram_free)
        tvRamApp = findViewById(R.id.tv_ram_app)
        tvFps = findViewById(R.id.tv_fps)
        tvTotalFrames = findViewById(R.id.tv_total_frames)
        rvMiniApps = findViewById(R.id.rv_mini_apps)
        llMiniAppsHeader = findViewById(R.id.ll_mini_apps_header)
        ivMiniAppsToggle = findViewById(R.id.iv_mini_apps_toggle)
        tvMiniAppsTitle = findViewById(R.id.tv_mini_apps_title)
        
        rvMiniApps.layoutManager = LinearLayoutManager(context)
        adapter = MiniAppsAdapter()
        rvMiniApps.adapter = adapter

        llMiniAppsHeader.setOnClickListener {
            toggleMiniApps()
        }

        updateMiniAppsVisibility()
        notifyHeight()
    }

    private fun toggleMiniApps() {
        isMiniAppsExpanded = !isMiniAppsExpanded
        updateMiniAppsVisibility()
        notifyHeight()
    }

    private fun updateMiniAppsVisibility() {
        rvMiniApps.visibility = if (isMiniAppsExpanded) View.VISIBLE else View.GONE
        ivMiniAppsToggle.rotation = if (isMiniAppsExpanded) 180f else 0f
    }

    private fun notifyHeight() {
        post {
            measure(
                MeasureSpec.makeMeasureSpec(width.takeIf { it > 0 } ?: (320 * resources.displayMetrics.density).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            if (measuredHeight > 0) {
                onHeightChanged?.invoke(measuredHeight)
            }
        }
    }

    private var trackingJob: Job? = null
    private var appsJob: Job? = null

    override fun onPageSelected() {
        notifyHeight()
        if (isTracking) return
        isTracking = true
        Choreographer.getInstance().postFrameCallback(this)
        
        trackingJob = scope.launch(Dispatchers.Main) {
            while(isTracking) {
                updateMemoryStats()
                delay(1000)
            }
        }
        
        appsJob = scope.launch(Dispatchers.Main) {
            ActiveAppTracker.activeApps.collect { apps ->
                adapter.submitList(apps)
                tvMiniAppsTitle.text = if (apps.isEmpty()) "Mini Apps Running (0)" else "Mini Apps Running (${apps.size})"
                notifyHeight()
            }
        }
    }
    
    override fun onPageUnselected() {
        isTracking = false
        Choreographer.getInstance().removeFrameCallback(this)
        trackingJob?.cancel()
        appsJob?.cancel()
        trackingJob = null
        appsJob = null
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isTracking) return
        
        framesCount++
        totalFramesRendered++
        
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            tvFps.text = "FPS: $framesCount"
            tvTotalFrames.text = "Total Frames: $totalFramesRendered"
            framesCount = 0
            lastFpsTime = now
        }
        
        Choreographer.getInstance().postFrameCallback(this)
    }
    
    private fun updateMemoryStats() {
        val runtime = Runtime.getRuntime()
        val appUsedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMem = runtime.maxMemory() / (1024 * 1024)
        
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        
        val sysFree = mi.availMem / (1024 * 1024)
        val sysTotal = mi.totalMem / (1024 * 1024)
        
        tvRamTotal.text = "System Total RAM: ${sysTotal}MB"
        tvRamFree.text = "System Free RAM: ${sysFree}MB"
        tvRamApp.text = "App Usage (Heap): ${appUsedMem}MB / ${maxMem}MB"
    }
    
    private inner class MiniAppsAdapter : RecyclerView.Adapter<MiniAppsAdapter.ViewHolder>() {
        private var apps = emptyList<ActiveAppInfo>()
        
        fun submitList(newApps: List<ActiveAppInfo>) {
            apps = newApps
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_active_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.tvName.text = app.name
            holder.tvType.text = app.type
            holder.tvMem.text = "~${app.estimatedMemoryMb}MB"
        }

        override fun getItemCount() = apps.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_app_name)
            val tvType: TextView = view.findViewById(R.id.tv_app_type)
            val tvMem: TextView = view.findViewById(R.id.tv_app_mem)
        }
    }
}
