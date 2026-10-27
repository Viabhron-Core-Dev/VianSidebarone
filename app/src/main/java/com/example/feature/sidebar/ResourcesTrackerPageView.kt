package com.example.feature.sidebar

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.core.ipc.HeavyProcessHost
import com.example.core.ipc.MainActionType
import com.example.core.ipc.MainCommandRequest
import com.example.utils.ActiveAppInfo
import com.example.utils.ActiveAppTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class ResourcesTrackerPageView @JvmOverloads constructor(
    context: Context,
    private val scope: CoroutineScope,
    val containerId: String = "sidebar",
    private val onHeightChanged: ((Int) -> Unit)? = null
) : FrameLayout(context), Choreographer.FrameCallback, SidebarPageControllable {

    constructor(
        context: Context,
        scope: CoroutineScope,
        onHeightChanged: ((Int) -> Unit)?
    ) : this(context, scope, "sidebar", onHeightChanged)

    private val tvRamTotal: TextView?
    private val tvRamFree: TextView?
    private val tvRamApp: TextView?
    private val tvFps: TextView?
    private val tvTotalFrames: TextView?
    private val rvMiniApps: RecyclerView?
    private val llMiniAppsHeader: LinearLayout?
    private val ivMiniAppsToggle: ImageView?
    private val tvMiniAppsTitle: TextView?
    
    private val adapter: MiniAppsAdapter
    private var isMiniAppsExpanded = false
    
    private var isTracking = false
    private var framesCount = 0L
    private var totalFramesRendered = 0L
    private var lastFpsTime = 0L
    private var lastCalculatedHeightPx = 0

    var themeBackgroundDrawable: Drawable? = null
        private set

    private var downX = 0f
    private var downY = 0f
    private var isHorizontalSwipeConsumed = false
    private val touchSlop = try { ViewConfiguration.get(context).scaledTouchSlop } catch (_: Throwable) { 16 }

    init {
        try {
            LayoutInflater.from(context).inflate(R.layout.page_resources_tracker, this, true)
        } catch (_: Throwable) {}
        
        tvRamTotal = findViewById(R.id.tv_ram_total)
        tvRamFree = findViewById(R.id.tv_ram_free)
        tvRamApp = findViewById(R.id.tv_ram_app)
        tvFps = findViewById(R.id.tv_fps)
        tvTotalFrames = findViewById(R.id.tv_total_frames)
        rvMiniApps = findViewById(R.id.rv_mini_apps)
        llMiniAppsHeader = findViewById(R.id.ll_mini_apps_header)
        ivMiniAppsToggle = findViewById(R.id.iv_mini_apps_toggle)
        tvMiniAppsTitle = findViewById(R.id.tv_mini_apps_title)
        
        try {
            rvMiniApps?.layoutManager = LinearLayoutManager(context)
        } catch (_: Throwable) {}
        adapter = MiniAppsAdapter()
        try {
            rvMiniApps?.adapter = adapter
        } catch (_: Throwable) {}

        llMiniAppsHeader?.setOnClickListener {
            toggleMiniApps()
        }

        updateMiniAppsVisibility()
        notifyHeight()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                isHorizontalSwipeConsumed = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (!isHorizontalSwipeConsumed && abs(dx) > touchSlop * 2 && abs(dx) > abs(dy) * 1.5f) {
                    isHorizontalSwipeConsumed = true
                    val delta = if (dx < 0) 1 else -1
                    HeavyProcessHost.getInstance(context).requestMainAction(
                        MainCommandRequest(
                            action = MainActionType.CUSTOM,
                            targetId = "NAVIGATE_PAGE",
                            parameters = mapOf("delta" to delta.toString())
                        )
                    )
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isHorizontalSwipeConsumed = false
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    private fun toggleMiniApps() {
        isMiniAppsExpanded = !isMiniAppsExpanded
        updateMiniAppsVisibility()
        notifyHeight()
    }

    private fun updateMiniAppsVisibility() {
        rvMiniApps?.visibility = if (isMiniAppsExpanded) View.VISIBLE else View.GONE
        ivMiniAppsToggle?.rotation = if (isMiniAppsExpanded) 180f else 0f
    }

    fun getCurrentHeightPx(): Int {
        val density = try { resources?.displayMetrics?.density ?: 1f } catch (_: Throwable) { 1f }
        val fallbackW = (320 * density).toInt()
        val w = if (width > 0) width else fallbackW
        return try {
            measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val minH = (140 * density).toInt()
            val maxH = (460 * density).toInt()
            measuredHeight.coerceIn(minH, maxH)
        } catch (_: Throwable) {
            (160 * density).toInt()
        }
    }

    private fun notifyHeight() {
        post {
            val h = getCurrentHeightPx()
            if (h > 0 && h != lastCalculatedHeightPx) {
                lastCalculatedHeightPx = h
                onHeightChanged?.invoke(h)
            }
        }
    }

    fun applyThemeBackground(color: Int = 0, isRight: Boolean = true) {
        val c = if (color != 0) color else Color.argb(230, 24, 24, 27)
        val density = try { resources?.displayMetrics?.density ?: 1f } catch (_: Throwable) { 1f }
        val radius = 16f * density
        val radii = if (isRight) {
            floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
        } else {
            floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
        }
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(c)
            cornerRadii = radii
        }
        themeBackgroundDrawable = drawable
        background = drawable
    }

    fun cleanup() {
        onPageUnselected()
    }

    private var trackingJob: Job? = null
    private var appsJob: Job? = null

    override fun onPageSelected() {
        notifyHeight()
        if (isTracking) return
        isTracking = true
        try {
            Choreographer.getInstance().postFrameCallback(this)
        } catch (_: Throwable) {}
        
        trackingJob = scope.launch(Dispatchers.Main) {
            while(isTracking) {
                updateMemoryStats()
                delay(1000)
            }
        }
        
        appsJob = scope.launch(Dispatchers.Main) {
            ActiveAppTracker.activeApps.collect { apps ->
                adapter.submitList(apps)
                tvMiniAppsTitle?.text = if (apps.isEmpty()) "Mini Apps Running (0)" else "Mini Apps Running (${apps.size})"
                notifyHeight()
            }
        }
    }
    
    override fun onPageUnselected() {
        isTracking = false
        try {
            Choreographer.getInstance().removeFrameCallback(this)
        } catch (_: Throwable) {}
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
            tvFps?.text = "FPS: $framesCount"
            tvTotalFrames?.text = "Total Frames: $totalFramesRendered"
            framesCount = 0
            lastFpsTime = now
        }
        
        try {
            Choreographer.getInstance().postFrameCallback(this)
        } catch (_: Throwable) {}
    }
    
    private fun updateMemoryStats() {
        try {
            val runtime = Runtime.getRuntime()
            val appUsedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            val maxMem = runtime.maxMemory() / (1024 * 1024)
            
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val sysFree: Long
            val sysTotal: Long
            if (am != null) {
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                sysFree = mi.availMem / (1024 * 1024)
                sysTotal = mi.totalMem / (1024 * 1024)
            } else {
                sysFree = 0
                sysTotal = 0
            }
            
            tvRamTotal?.text = "System Total RAM: ${sysTotal}MB"
            tvRamFree?.text = "System Free RAM: ${sysFree}MB"
            tvRamApp?.text = "App Usage (Heap): ${appUsedMem}MB / ${maxMem}MB"
        } catch (_: Throwable) {}
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
