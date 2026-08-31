package com.example.feature.sidebar

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.utils.SidebarPage
import com.example.util.AppLogger
import com.example.core.AppWidgetHelper
import kotlin.math.max

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

@SuppressLint("ViewConstructor")
class SidebarView(
    context: Context,
    private val prefs: SharedPreferences,
    private val windowManager: WindowManager,
    val physicalHandleId: String,
    val containerId: String,
    private val pageConfigs: List<SidebarPage>,
    private val defaultPageIndex: Int,
    private val onClose: () -> Unit
) : FrameLayout(context) {

    val isRight: Boolean = run {
        val legacyEdge = if (prefs.getBoolean("sidebar_position_left", false)) "left" else "right"
        prefs.getString("handle_${physicalHandleId}_edge", if (physicalHandleId == "sidebar") legacyEdge else "right") == "right"
    }
    private val wrapContent = prefs.getBoolean("handle_${containerId}_sidebar_wrap_content", prefs.getBoolean("sidebar_wrap_content", true))
    private val layoutParams: WindowManager.LayoutParams
    private val viewPager: ViewPager2
    private lateinit var container: FrameLayout
    private lateinit var dotsLayout: LinearLayout
    private val dots = mutableListOf<View>()
    private lateinit var editButton: ImageView
    private var isAttached = false
    private val viewScope = CoroutineScope(Dispatchers.Main + Job())
    private val appsManagers = mutableMapOf<String, SidebarAppsManager>()
    private val dimOverlay: View
    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private val isLooping = pageConfigs.size > 2
    private val startingIndex = if (isLooping) {
        val half = Int.MAX_VALUE / 2
        half - (half % pageConfigs.size) + max(0, defaultPageIndex)
    } else {
        max(0, defaultPageIndex)
    }

    fun setDimmed(dimmed: Boolean) {
        if (dimmed) {
            dimOverlay.visibility = View.VISIBLE
            dimOverlay.animate().alpha(0.5f).setDuration(180).start()
        } else {
            dimOverlay.animate().alpha(0f).setDuration(180).withEndAction {
                dimOverlay.visibility = View.GONE
            }.start()
        }
    }

    init {
        AppLogger.d("SidebarView", "Init sidebar for containerId: $containerId")
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val density = context.resources.displayMetrics.density

        val initialActualPos = if (pageConfigs.isNotEmpty()) (if (isLooping) startingIndex % pageConfigs.size else startingIndex).coerceIn(0, pageConfigs.size - 1) else 0
        val initialPage = pageConfigs.getOrNull(initialActualPos)

        val targetWidthDp = if (initialPage?.useCustomSettings == true && initialPage.width > 0) {
            initialPage.width
        } else {
            when (initialPage?.type) {
                "calculator", "compass", "resources_tracker" -> 320
                "scheduler", "notifications", "notification", "app_tracker" -> 330
                "media_player" -> 300
                "widgets_grid", "widget" -> {
                    val cols = prefs.getInt("widgets_grid_cols_${initialPage?.id}", 4)
                    if (cols == 3) prefs.getInt("handle_${containerId}_sidebar_width", prefs.getInt("sidebar_width", 198))
                    else if (cols <= 2) prefs.getInt("handle_${containerId}_sidebar_width", prefs.getInt("sidebar_width", 140))
                    else prefs.getInt("handle_${containerId}_sidebar_width", prefs.getInt("sidebar_width", 240))
                }
                "hybrid_grid", "default_hybrid" -> {
                    val cols = prefs.getInt("hybrid_grid_cols_${initialPage?.id}", 4)
                    if (cols == 3) prefs.getInt("handle_${containerId}_sidebar_width", prefs.getInt("sidebar_width", 198))
                    else if (cols <= 2) prefs.getInt("handle_${containerId}_sidebar_width", prefs.getInt("sidebar_width", 140))
                    else prefs.getInt("handle_${containerId}_sidebar_width", prefs.getInt("sidebar_width", 240))
                }
                "apps" -> {
                    val c = prefs.getInt("handle_${physicalHandleId}_page_${initialPage?.id}_columns", -1)
                    val defaultCols = prefs.getInt("handle_${physicalHandleId}_columns", prefs.getInt("sidebar_columns", 3))
                    val cols = if (initialPage?.useCustomSettings == true) initialPage.gridColumns else (if (c != -1) c else defaultCols)
                    if (cols == 3) prefs.getInt("handle_${containerId}_sidebar_width", prefs.getInt("sidebar_width", 198))
                    else if (cols <= 2) prefs.getInt("handle_${containerId}_sidebar_width", prefs.getInt("sidebar_width", 140))
                    else prefs.getInt("handle_${containerId}_sidebar_width", prefs.getInt("sidebar_width", 240))
                }
                else -> prefs.getInt("handle_${containerId}_sidebar_width", prefs.getInt("sidebar_width", 198))
            }
        }
        val widthPx = (targetWidthDp * density).toInt()

        val isInitialWrap = if (initialPage?.useCustomSettings == true) {
            initialPage.wrapContentHeight
        } else {
            when (initialPage?.type) {
                "calculator", "compass", "resources_tracker", "media_player", "app_tracker", "scheduler", "notifications", "notification" -> true
                "apps", "widgets_grid", "hybrid_grid", "default_hybrid", "widget" -> true
                else -> true
            }
        }

        val heightPx = if (isInitialWrap) {
            WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            val targetHeightDp = if (initialPage?.useCustomSettings == true && initialPage.height > 0) {
                initialPage.height
            } else {
                when (initialPage?.type) {
                    "calculator" -> 460
                    "compass" -> 480
                    "scheduler" -> 520
                    "notifications", "notification" -> 520
                    "resources_tracker" -> 460
                    "app_tracker" -> 560
                    "media_player" -> 360
                    else -> prefs.getInt("handle_${containerId}_sidebar_height", prefs.getInt("sidebar_height", 360))
                }
            }
            (targetHeightDp * density).toInt()
        }

        val legacyEdge = if (prefs.getBoolean("sidebar_position_left", false)) "left" else "right"
        val isRight = prefs.getString("handle_${physicalHandleId}_edge", if (physicalHandleId == "sidebar") legacyEdge else "right") == "right"
        val gravityEdge = if (isRight) Gravity.END else Gravity.START

        val stickAlignment = if (initialPage?.useCustomSettings == true) initialPage.stickAlignment else "bottom"
        val gravityVertical = when (stickAlignment) {
            "top" -> Gravity.TOP
            "center" -> Gravity.CENTER_VERTICAL
            else -> Gravity.BOTTOM
        }

        layoutParams = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = gravityEdge or gravityVertical
            x = 0
            y = 0
        }

        isFocusableInTouchMode = true
        setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                onClose()
                true
            } else {
                false
            }
        }

        val opacity = prefs.getFloat("handle_${containerId}_sidebar_transparency", prefs.getFloat("sidebar_transparency", 0.9f))
        val alphaInt = (opacity * 255).toInt().coerceIn(0, 255)

        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            val colorHex = prefs.getString("handle_${containerId}_sidebar_color", prefs.getString("sidebar_color", "#1E1E2E")) ?: "#1E1E2E"
            val baseColor = try { Color.parseColor(colorHex) } catch(e:Exception){ Color.parseColor("#1E1E2E") }
            val r = Color.red(baseColor)
            val g = Color.green(baseColor)
            val b = Color.blue(baseColor)
            setColor(Color.argb(alphaInt, r, g, b))

            setStroke((1 * density).toInt(), Color.argb(80, 255, 255, 255))

            val radius = 16f * density
            cornerRadii = if (isRight) {
                floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
            } else {
                floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
            }
        }
        background = drawable

        val headerHeight = (22 * density).toInt()
        val edgeMargin = (10 * density).toInt()

        val header = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, headerHeight)
        }

        val closeText = TextView(context).apply {
            text = "✕"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(headerHeight, headerHeight).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = edgeMargin
            }
            setOnClickListener { onClose() }
        }
        header.addView(closeText)

        val settingsBtn = android.widget.ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            setColorFilter(Color.WHITE)
            val pad = (3 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(headerHeight, headerHeight).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = edgeMargin + headerHeight
            }
            setOnClickListener {
                val intent = Intent(context, com.example.feature.settings.SettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(intent)
                onClose()
            }
        }
        header.addView(settingsBtn)

        editButton = android.widget.ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_edit)
            setColorFilter(Color.WHITE)
            val pad = (3 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(headerHeight, headerHeight).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                marginStart = edgeMargin
            }
            setOnClickListener {
                if (pageConfigs.isNotEmpty()) {
                    val currentItem = viewPager.currentItem
                    val actualPosition = if (pageConfigs.size > 2) currentItem % pageConfigs.size else currentItem
                    val pageConfig = pageConfigs.getOrNull(actualPosition)
                    if (pageConfig != null) {
                        when (pageConfig.type) {
                            "apps" -> {
                                val intent = Intent(context, com.example.SidebarEditActivity::class.java).apply {
                                    putExtra("PAGE_ID", pageConfig.id)
                                    putExtra("CONTAINER_ID", containerId)
                                    putExtra("HANDLE_ID", physicalHandleId)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                                onClose()
                            }
                            "widgets_grid" -> {
                                val intent = Intent(context, com.example.WidgetsGridEditActivity::class.java).apply {
                                    putExtra("PAGE_ID", pageConfig.id)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                                onClose()
                            }
                            "hybrid_grid", "default_hybrid" -> {
                                val intent = Intent(context, com.example.HybridGridEditActivity::class.java).apply {
                                    putExtra("PAGE_ID", pageConfig.id)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                                onClose()
                            }
                            "app_tracker" -> {
                                val intent = Intent(context, com.example.AppTrackerSettingsActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                                onClose()
                            }
                            "notifications", "notification" -> {
                                val intent = Intent(context, com.example.NotificationHistoryActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                                onClose()
                            }
                            "scheduler", "short_reminders", "reminder", "reminders" -> {
                                val intent = Intent(context, com.example.feature.settings.TagManagementActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                                onClose()
                            }
                            "calculator", "compass", "resources_tracker", "media_player", "widget" -> {
                                val intent = Intent(context, com.example.feature.settings.SettingsActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    putExtra("start_route", "pages_${containerId}|edit_page:${pageConfig.id}")
                                }
                                context.startActivity(intent)
                                onClose()
                            }
                            else -> {
                                val intent = Intent(context, com.example.feature.settings.SettingsActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    putExtra("start_route", "pages_${containerId}")
                                }
                                context.startActivity(intent)
                                onClose()
                            }
                        }
                    }
                }
            }
        }
        header.addView(editButton)

        container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = headerHeight
            }
        }

        viewPager = ViewPager2(context).apply {
            layoutParams = if (wrapContent) {
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            } else {
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
            offscreenPageLimit = ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT
        }
        
        viewPager.adapter = object : RecyclerView.Adapter<SidebarPageViewHolder>() {
            override fun getItemViewType(position: Int): Int {
                if (pageConfigs.isEmpty()) return 0
                return if (isLooping) position % pageConfigs.size else position
            }
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SidebarPageViewHolder {
                val frame = FrameLayout(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
                return SidebarPageViewHolder(frame)
            }
            override fun onBindViewHolder(holder: SidebarPageViewHolder, position: Int) {
                if (pageConfigs.isEmpty()) return
                val actualPosition = if (isLooping) position % pageConfigs.size else position
                val config = pageConfigs[actualPosition]
                // Only immediately instantiate if it is the initially active starting page.
                // Other pages load on-demand when scrolled into view.
                val isCurrentOrTarget = (position == viewPager.currentItem) || (holder.pageView != null)
                holder.bind(config, isCurrentOrTarget)
            }
            override fun getItemCount(): Int = if (isLooping) Int.MAX_VALUE else pageConfigs.size
        }
        
        // Indicator container constrained between start (edit button) and end (settings + close buttons)
        val startReserved = edgeMargin + headerHeight + (4 * density).toInt()
        val endReserved = edgeMargin + (headerHeight * 2) + (4 * density).toInt()

        val dotsScrollView = android.widget.HorizontalScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                marginStart = startReserved
                marginEnd = endReserved
            }
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isHorizontalScrollBarEnabled = false
        }

        dotsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER
            }
        }
        dotsScrollView.addView(dotsLayout)
        
        setupDots(pageConfigs.size)

        container.addView(viewPager)
        header.addView(dotsScrollView)

        addView(header)
        addView(container)

        val dimDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.BLACK)
            val radius = 16f * density
            cornerRadii = if (isRight) {
                floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
            } else {
                floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
            }
        }
        dimOverlay = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            background = dimDrawable
            alpha = 0f
            visibility = View.GONE
            isClickable = false
        }
        addView(dimOverlay)

        viewPager.setCurrentItem(startingIndex, false)

        val callback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                if (pageConfigs.isNotEmpty()) {
                    val actualPosition = if (isLooping) position % pageConfigs.size else position
                    updateDots(actualPosition)
                    updateWindowForPage(actualPosition)
                    
                    val pageConfig = pageConfigs.getOrNull(actualPosition)
                    if (::editButton.isInitialized) {
                        editButton.visibility = View.VISIBLE
                    }
                    
                    AppWidgetHelper.startListening(context)
                }
                
                // Notify lifecycle and ensure current page is loaded on demand
                val rcv = viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView
                rcv?.let {
                    for (i in 0 until it.childCount) {
                        val child = it.getChildAt(i)
                        val holder = it.getChildViewHolder(child) as? SidebarPageViewHolder
                        if (holder?.bindingAdapterPosition == position) {
                            holder.ensureLoaded()
                            (holder.pageView as? SidebarPageControllable)?.onPageSelected()
                            
                            // Measure and adjust height immediately for non-grid wrapped pages
                            holder.pageView?.let { pv ->
                                pv.post {
                                    val actualPos = if (isLooping) position % pageConfigs.size else position
                                    val page = pageConfigs.getOrNull(actualPos)
                                    val isPageWrap = if (page?.useCustomSettings == true) page.wrapContentHeight else true
                                    if (isPageWrap) {
                                        val density = context.resources.displayMetrics.density
                                        val targetW = layoutParams.width.takeIf { it > 0 } ?: (320 * density).toInt()
                                        pv.measure(
                                            View.MeasureSpec.makeMeasureSpec(targetW, View.MeasureSpec.EXACTLY),
                                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                                        )
                                        val measuredH = pv.measuredHeight
                                        if (measuredH > 0) {
                                            handleChildHeightChange(position, measuredH)
                                        } else if (pv.height > 0) {
                                            handleChildHeightChange(position, pv.height)
                                        }
                                    } else {
                                        updateWindowForPage(actualPos)
                                    }
                                }
                            }
                        } else {
                            (holder?.pageView as? SidebarPageControllable)?.onPageUnselected()
                        }
                    }
                }
            }
        }
        pageChangeCallback = callback
        viewPager.registerOnPageChangeCallback(callback)

        viewPager.post {
            if (pageConfigs.isNotEmpty()) {
                val actualPos = if (isLooping) startingIndex % pageConfigs.size else startingIndex
                updateDots(actualPos)
                updateWindowForPage(actualPos)
                val rcv = viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView
                rcv?.let {
                    for (i in 0 until it.childCount) {
                        val child = it.getChildAt(i)
                        val holder = it.getChildViewHolder(child) as? SidebarPageViewHolder
                        if (holder?.bindingAdapterPosition == startingIndex) {
                            holder.ensureLoaded()
                            (holder.pageView as? SidebarPageControllable)?.onPageSelected()
                        }
                    }
                }
            }
        }
    }
    
    fun updateWindowForPage(actualPosition: Int) {
        if (pageConfigs.isEmpty()) return
        val page = pageConfigs.getOrNull(actualPosition) ?: return
        val density = context.resources.displayMetrics.density
        val headerHeight = (22 * density).toInt()

        val targetWidthDp = if (page.useCustomSettings && page.width > 0) {
            page.width
        } else {
            when (page.type) {
                "calculator" -> 280
                "compass" -> 250
                "resources_tracker" -> 320
                "scheduler", "notifications", "notification", "app_tracker" -> 330
                "media_player" -> 300
                "widgets_grid", "widget" -> {
                    val cols = prefs.getInt("widgets_grid_cols_${page.id}", 4)
                    if (cols == 3) prefs.getInt("handle_${containerId}_sidebar_width", prefs.getInt("sidebar_width", 198))
                    else if (cols <= 2) prefs.getInt("handle_${containerId}_sidebar_width", prefs.getInt("sidebar_width", 140))
                    else prefs.getInt("handle_${containerId}_sidebar_width", prefs.getInt("sidebar_width", 240))
                }
                "hybrid_grid", "default_hybrid" -> {
                    val cols = prefs.getInt("hybrid_grid_cols_${page.id}", 4)
                    if (cols == 3) prefs.getInt("handle_${containerId}_sidebar_width", prefs.getInt("sidebar_width", 198))
                    else if (cols <= 2) prefs.getInt("handle_${containerId}_sidebar_width", prefs.getInt("sidebar_width", 140))
                    else prefs.getInt("handle_${containerId}_sidebar_width", prefs.getInt("sidebar_width", 240))
                }
                "apps" -> {
                    val c = prefs.getInt("handle_${physicalHandleId}_page_${page.id}_columns", -1)
                    val defaultCols = prefs.getInt("handle_${physicalHandleId}_columns", prefs.getInt("sidebar_columns", 3))
                    val cols = if (page.useCustomSettings) page.gridColumns else (if (c != -1) c else defaultCols)
                    if (cols == 3) prefs.getInt("handle_${containerId}_sidebar_width", prefs.getInt("sidebar_width", 198))
                    else if (cols <= 2) prefs.getInt("handle_${containerId}_sidebar_width", prefs.getInt("sidebar_width", 140))
                    else prefs.getInt("handle_${containerId}_sidebar_width", prefs.getInt("sidebar_width", 240))
                }
                else -> prefs.getInt("handle_${containerId}_sidebar_width", prefs.getInt("sidebar_width", 198))
            }
        }
        val targetWidthPx = (targetWidthDp * density).toInt()

        val isPageWrap = if (page.useCustomSettings) {
            page.wrapContentHeight
        } else {
            true
        }

        val targetContentHeightPx = if (page.useCustomSettings && page.height > 0) {
            (page.height * density).toInt()
        } else {
            val targetHeightDp = when (page.type) {
                "calculator" -> 260
                "compass" -> 270
                "resources_tracker" -> 160
                "media_player" -> 100
                "scheduler" -> 200
                "notifications", "notification" -> 160
                "app_tracker" -> 180
                "widgets_grid", "widget" -> prefs.getInt("handle_${containerId}_sidebar_height", prefs.getInt("sidebar_height", 280))
                "hybrid_grid", "default_hybrid" -> prefs.getInt("handle_${containerId}_sidebar_height", prefs.getInt("sidebar_height", 280))
                else -> prefs.getInt("handle_${containerId}_sidebar_height", prefs.getInt("sidebar_height", 280))
            }
            (targetHeightDp * density).toInt()
        }

        val targetHeightPx = headerHeight + targetContentHeightPx

        val legacyEdge = if (prefs.getBoolean("sidebar_position_left", false)) "left" else "right"
        val isRight = prefs.getString("handle_${physicalHandleId}_edge", if (physicalHandleId == "sidebar") legacyEdge else "right") == "right"
        val gravityEdge = if (isRight) Gravity.END else Gravity.START

        val stickAlignment = if (page.useCustomSettings) page.stickAlignment else "bottom"
        val gravityVertical = when (stickAlignment) {
            "top" -> Gravity.TOP
            "center" -> Gravity.CENTER_VERTICAL
            else -> Gravity.BOTTOM
        }
        val targetGravity = gravityEdge or gravityVertical

        var changed = false
        if (layoutParams.width != targetWidthPx) {
            layoutParams.width = targetWidthPx
            changed = true
        }
        if (layoutParams.height != targetHeightPx) {
            layoutParams.height = targetHeightPx
            changed = true
        }
        if (layoutParams.gravity != targetGravity) {
            layoutParams.gravity = targetGravity
            changed = true
        }

        val vpParams = viewPager.layoutParams
        if (vpParams.height != targetContentHeightPx) {
            vpParams.height = targetContentHeightPx
            viewPager.layoutParams = vpParams
        }

        if (changed && isAttached) {
            try {
                windowManager.updateViewLayout(this, layoutParams)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handleChildHeightChange(bindingAdapterPosition: Int, newHeight: Int) {
        val actualPosition = if (isLooping) viewPager.currentItem % pageConfigs.size else viewPager.currentItem
        val page = pageConfigs.getOrNull(actualPosition)
        val isPageWrap = if (page?.useCustomSettings == true) {
            page.wrapContentHeight
        } else {
            true
        }
        if (isPageWrap && viewPager.currentItem == bindingAdapterPosition && newHeight > 0) {
            val density = context.resources.displayMetrics.density
            val headerHeight = (22 * density).toInt()
            val screenHeight = context.resources.displayMetrics.heightPixels
            val maxAllowedHeight = (screenHeight * 0.85f).toInt() - headerHeight
            val boundedContentHeight = newHeight.coerceIn((60 * density).toInt(), maxAllowedHeight)
            val totalWindowHeight = boundedContentHeight + headerHeight

            var changed = false
            val vpParams = viewPager.layoutParams
            if (vpParams.height != boundedContentHeight) {
                vpParams.height = boundedContentHeight
                viewPager.layoutParams = vpParams
                changed = true
            }
            if (layoutParams.height != totalWindowHeight) {
                layoutParams.height = totalWindowHeight
                changed = true
            }
            if (changed && isAttached) {
                try {
                    windowManager.updateViewLayout(this@SidebarView, layoutParams)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    private fun setupDots(count: Int) {
        dots.clear()
        dotsLayout.removeAllViews()
        if (count <= 1) {
            dotsLayout.visibility = View.GONE
            return
        }
        dotsLayout.visibility = View.VISIBLE
        val density = context.resources.displayMetrics.density
        val size = (5 * density).toInt()
        val margin = (2.5f * density).toInt()
        
        for (i in 0 until count) {
            val dot = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(margin, 0, margin, 0)
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
            }
            dots.add(dot)
            dotsLayout.addView(dot)
        }
        updateDots(0)
    }
    
    private fun updateDots(position: Int) {
        if (dots.isEmpty()) return
        val density = context.resources.displayMetrics.density
        
        for (i in dots.indices) {
            val bg = dots[i].background as? android.graphics.drawable.GradientDrawable
            if (i == position) {
                bg?.setStroke(0, Color.TRANSPARENT)
                bg?.setColor(Color.WHITE)
                dots[i].layoutParams = (dots[i].layoutParams as LinearLayout.LayoutParams).apply {
                    width = (5 * density).toInt()
                    height = (5 * density).toInt()
                }
            } else {
                bg?.setStroke((1 * density).toInt(), Color.WHITE)
                bg?.setColor(Color.TRANSPARENT)
                dots[i].layoutParams = (dots[i].layoutParams as LinearLayout.LayoutParams).apply {
                    width = (4 * density).toInt()
                    height = (4 * density).toInt()
                }
            }
        }
    }

    fun attach() {
        if (!isAttached) {
            com.example.core.LogKeeper.writeLog("Sidebar", "Attached sidebar for containerId: $containerId")
            AppWidgetHelper.startListening(context)
            
            val density = context.resources.displayMetrics.density
            val slideDist = (layoutParams.width.toFloat()).coerceAtLeast(200f * density)
            translationX = if (isRight) slideDist else -slideDist
            alpha = 0.6f

            windowManager.addView(this, layoutParams)
            isAttached = true

            animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(160)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }
    
    fun closeWithAnimation(onFinished: () -> Unit) {
        if (!isAttached) {
            onFinished()
            return
        }
        val density = context.resources.displayMetrics.density
        val slideDist = (layoutParams.width.toFloat()).coerceAtLeast(200f * density)
        val targetX = if (isRight) slideDist else -slideDist
        animate()
            .translationX(targetX)
            .alpha(0f)
            .setDuration(130)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                onFinished()
            }
            .start()
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) {
            com.example.core.LogKeeper.writeLog("Sidebar", "Outside touch detected, closing sidebar")
            onClose()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onClose()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    fun detach() {
        if (isAttached) {
            com.example.core.LogKeeper.writeLog("Sidebar", "Detached sidebar for containerId: $containerId")
            animate().cancel()
            pageChangeCallback?.let {
                viewPager.unregisterOnPageChangeCallback(it)
                pageChangeCallback = null
            }
            // Release and unselect all children
            val rcv = viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView
            rcv?.let {
                for (i in 0 until it.childCount) {
                    val child = it.getChildAt(i)
                    val holder = it.getChildViewHolder(child) as? SidebarPageViewHolder
                    holder?.release()
                }
                it.adapter = null
                it.recycledViewPool.clear()
            }
            viewPager.adapter = null
            container.removeAllViews()

            AppWidgetHelper.stopListening()
            appsManagers.values.forEach { it.destroy() }
            appsManagers.clear()

            dots.clear()
            dotsLayout.removeAllViews()
            removeAllViews()

            try {
                windowManager.removeView(this)
            } catch (e: Exception) {}
            isAttached = false
            viewScope.cancel()
        }
    }
    
    inner class SidebarPageViewHolder(private val frame: FrameLayout) : RecyclerView.ViewHolder(frame) {
        var pageView: View? = null
        private var currentConfig: SidebarPage? = null

        fun release() {
            (pageView as? SidebarPageControllable)?.onPageUnselected()
            frame.removeAllViews()
            pageView = null
            currentConfig = null
        }

        fun bind(config: SidebarPage, loadImmediately: Boolean) {
            val configChanged = (currentConfig?.id != config.id) || (currentConfig?.type != config.type)
            if (configChanged) {
                (pageView as? SidebarPageControllable)?.onPageUnselected()
                pageView = null
                frame.removeAllViews()
            }
            currentConfig = config
            if (loadImmediately) {
                ensureLoaded()
            } else {
                // If not needed immediately, keep uninflated / empty to save RAM and initialization time
                if (pageView == null) {
                    frame.removeAllViews()
                }
            }
        }

        fun ensureLoaded() {
            val config = currentConfig ?: return
            if (pageView != null && frame.childCount > 0) return

            frame.removeAllViews()
            val context = frame.context
            
            pageView = when (config.type) {
                "calculator" -> CalculatorPageView(context) { newHeight ->
                    handleChildHeightChange(bindingAdapterPosition, newHeight)
                }
                "compass" -> CompassPageView(context) { newHeight ->
                    handleChildHeightChange(bindingAdapterPosition, newHeight)
                }
                "apps" -> {
                    val prefKey = "sidebar_apps_${physicalHandleId}_${config.id}"
                    val manager = appsManagers.getOrPut(prefKey) {
                        SidebarAppsManager(context, prefs, viewScope, prefKey) {}
                    }
                    manager.ensureLoaded()
                    val p = AppsPageView(context, physicalHandleId, config, manager, viewScope,
                        onCloseSidebar = { onClose() },
                        onDimSidebar = { dimmed -> setDimmed(dimmed) },
                        onHeightChanged = { newHeight ->
                            handleChildHeightChange(bindingAdapterPosition, newHeight)
                        }
                    )
                    p.updateData(manager.activeItems)
                    p
                }
                "hybrid_grid", "default_hybrid" -> {
                    val pageId = if (config.type == "default_hybrid" && !config.id.startsWith("default_hybrid")) "default_hybrid" else config.id
                    HybridGridPageView(context, pageId, viewScope, containerId,
                        onClose = { onClose() },
                        onDimSidebar = { dimmed -> setDimmed(dimmed) }
                    ) { newHeight ->
                        handleChildHeightChange(bindingAdapterPosition, newHeight)
                    }
                }
                "widgets_grid" -> {
                    WidgetsGridPageView(context, config.id, viewScope) { newHeight ->
                        handleChildHeightChange(bindingAdapterPosition, newHeight)
                    }
                }
                "app_tracker" -> {
                    AppTrackerPageView(context, onClose, { pkgName ->
                        try {
                            val pm = context.packageManager
                            val launchIntent = pm.getLaunchIntentForPackage(pkgName)
                            if (launchIntent != null) {
                                launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(launchIntent)
                            } else {
                                val detailsIntent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = android.net.Uri.parse("package:$pkgName")
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(detailsIntent)
                            }
                        } catch (e: Exception) {}
                        onClose()
                    }, onHeightChanged = { newHeight ->
                        handleChildHeightChange(bindingAdapterPosition, newHeight)
                    })
                }
                "media_player" -> {
                    MediaPlayerPageView(context, onClose) { newHeight ->
                        handleChildHeightChange(bindingAdapterPosition, newHeight)
                    }
                }
                "widget" -> {
                    WidgetPageView(context, config.id) { newHeight ->
                        handleChildHeightChange(bindingAdapterPosition, newHeight)
                    }
                }
                "scheduler" -> SchedulerPageView(context, viewScope) { newHeight ->
                    handleChildHeightChange(bindingAdapterPosition, newHeight)
                }
                "notifications", "notification" -> NotificationPageView(context, { onClose() }, { /* TODO: onHideApp */ }) { newHeight ->
                    handleChildHeightChange(bindingAdapterPosition, newHeight)
                }
                "resources_tracker" -> ResourcesTrackerPageView(context, viewScope) { newHeight ->
                    handleChildHeightChange(bindingAdapterPosition, newHeight)
                }
                else -> {
                    TextView(context).apply {
                        text = "Page: ${config.title}\nType: ${config.type}\n(Not Implemented)"
                        setTextColor(Color.WHITE)
                        textSize = 16f
                        gravity = Gravity.CENTER
                        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    }
                }
            }
            frame.addView(pageView)
        }
    }
}
