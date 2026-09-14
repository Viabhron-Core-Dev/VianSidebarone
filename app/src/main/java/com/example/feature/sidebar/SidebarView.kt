package com.example.feature.sidebar

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.feature.element.AddElementRequest
import com.example.feature.element.ElementActionRegistry
import com.example.feature.element.ElementDescriptor
import com.example.feature.element.ElementPlacementManager
import com.example.util.HandleEdge

/**
 * SidebarView: Hardware-accelerated floating overlay container rendering the active
 * SidebarContainer, its ordered page stack, and placed elements.
 *
 * Reuses the historical reference Sidebar visual structure and behavior:
 * - Dynamic handle-edge docking (Gravity.START vs Gravity.END) with matching corner radii.
 * - Hardware back-key handling and outside-touch auto-dismissal.
 * - Floating top-bar with Edit Mode toggle, Page navigation, Page indicators, and Close affordance.
 * - Dynamic page rendering driven by PageTypes through [SidebarPageRenderer].
 * - Scoped Add Element picker overlay integrated at the UI boundary.
 */
@SuppressLint("ViewConstructor")
class SidebarView(
    context: Context,
    private var edge: HandleEdge = HandleEdge.RIGHT,
    private val onCloseRequested: () -> Unit = {}
) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density
    val widthPx: Int = (198 * density).toInt()

    private var currentState: SidebarRuntimeState? = null
    private var isClosing = false

    private val contentScrollView: ScrollView
    private val contentHost: FrameLayout
    private val topBarView: LinearLayout
    private val editButton: TextView
    private val prevButton: TextView
    private val titleText: TextView
    private val pageIndicatorText: TextView
    private val nextButton: TextView
    private val closeButton: TextView
    private val pageTabsContainer: LinearLayout
    private val pageTabsScroll: HorizontalScrollView
    private val pickerOverlay: FrameLayout

    init {
        // Essential for hardware back button trapping
        isFocusable = true
        isFocusableInTouchMode = true

        applyEdgeStyling(edge)

        // 1. Root Container Layout
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        // 2. Floating Top Bar
        topBarView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#181825"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        }

        editButton = TextView(context).apply {
            text = "✏️"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = createRoundRippleDrawable("#313244")
            setOnClickListener {
                SidebarManager.getInstance(context).toggleEditMode()
            }
        }
        topBarView.addView(editButton)

        prevButton = TextView(context).apply {
            text = "‹"
            textSize = 20f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(4), dp(2))
            background = createRoundRippleDrawable("#313244")
            setOnClickListener {
                SidebarManager.getInstance(context).previousPage()
            }
        }
        topBarView.addView(prevButton)

        val titleContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        titleText = TextView(context).apply {
            text = "Sidebar"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }

        pageIndicatorText = TextView(context).apply {
            text = "1 / 1"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 10f
            gravity = Gravity.CENTER
        }
        titleContainer.addView(titleText)
        titleContainer.addView(pageIndicatorText)
        topBarView.addView(titleContainer)

        nextButton = TextView(context).apply {
            text = "›"
            textSize = 20f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(6), dp(2))
            background = createRoundRippleDrawable("#313244")
            setOnClickListener {
                SidebarManager.getInstance(context).nextPage()
            }
        }
        topBarView.addView(nextButton)

        closeButton = TextView(context).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = createRoundRippleDrawable("#313244")
            setOnClickListener {
                closeWithAnimation()
            }
        }
        topBarView.addView(closeButton)

        rootLayout.addView(topBarView)

        // 3. Page Tabs Strip
        pageTabsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(28)
            )
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#14141E"))
            }
        }

        pageTabsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(2))
            layoutParams = FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        }
        pageTabsScroll.addView(pageTabsContainer)
        rootLayout.addView(pageTabsScroll)

        // 4. Content Scroll Area
        contentScrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        contentHost = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        contentScrollView.addView(contentHost)
        rootLayout.addView(contentScrollView)

        addView(rootLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 5. In-Sidebar Add Element Picker Overlay
        pickerOverlay = FrameLayout(context).apply {
            visibility = GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F511111B"))
            }
            isClickable = true
            isFocusable = true
        }
        addView(pickerOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun applyEdgeStyling(edge: HandleEdge) {
        this.edge = edge
        val radius = dp(16).toFloat()
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#1E1E2E"))
            setStroke(dp(1), Color.parseColor("#313244"))
            if (edge == HandleEdge.RIGHT) {
                cornerRadii = floatArrayOf(
                    radius, radius, // top-left
                    0f, 0f,         // top-right
                    0f, 0f,         // bottom-right
                    radius, radius  // bottom-left
                )
            } else {
                cornerRadii = floatArrayOf(
                    0f, 0f,         // top-left
                    radius, radius, // top-right
                    radius, radius, // bottom-right
                    0f, 0f          // bottom-left
                )
            }
        }
        background = bg
    }

    fun updateState(state: SidebarRuntimeState) {
        this.currentState = state

        // 1. Update Title & Navigation
        titleText.text = if (state.isEditMode) "[Edit] ${state.currentPageTitle ?: "Page"}" else (state.currentPageTitle ?: "Page")
        titleText.setTextColor(if (state.isEditMode) Color.parseColor("#F43F5E") else Color.WHITE)

        val total = state.totalPages.coerceAtLeast(1)
        val currentIdx = (state.currentPageIndex + 1).coerceAtMost(total)
        pageIndicatorText.text = "$currentIdx / $total"

        prevButton.isEnabled = state.canGoPrevious
        prevButton.alpha = if (state.canGoPrevious) 1f else 0.3f

        nextButton.isEnabled = state.canGoNext
        nextButton.alpha = if (state.canGoNext) 1f else 0.3f

        // 2. Update Edit Button
        if (state.isEditMode) {
            editButton.text = "✓"
            editButton.setTextColor(Color.parseColor("#34D399"))
            editButton.background = createRoundRippleDrawable("#064E3B")
        } else {
            editButton.text = "✏️"
            editButton.setTextColor(Color.parseColor("#94A3B8"))
            editButton.background = createRoundRippleDrawable("#313244")
        }

        // 3. Render Page Tabs
        renderPageTabs(state)

        // 4. Render Active Page Content via SidebarPageRenderer
        contentHost.removeAllViews()
        val renderer = SidebarPageRenderer(
            context = context,
            onElementClick = { _ ->
                // Normal tap executes the element
            },
            onAddElementClick = {
                showAddElementPicker(state)
            }
        )
        val renderedPage = renderer.renderPage(state)
        contentHost.addView(renderedPage)

        if (!state.isEditMode && pickerOverlay.visibility == VISIBLE) {
            pickerOverlay.visibility = GONE
        }
    }

    private fun renderPageTabs(state: SidebarRuntimeState) {
        pageTabsContainer.removeAllViews()
        val pages = state.pages

        if (pages.size <= 1) {
            pageTabsScroll.visibility = GONE
            return
        }
        pageTabsScroll.visibility = VISIBLE

        for ((index, page) in pages.withIndex()) {
            val isSelected = index == state.currentPageIndex
            val tab = TextView(context).apply {
                text = page.title
                textSize = 10f
                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#64748B"))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    if (isSelected) setColor(Color.parseColor("#3B82F6"))
                    else {
                        setColor(Color.parseColor("#1E1E2E"))
                        setStroke(dp(1), Color.parseColor("#313244"))
                    }
                }
                setPadding(dp(10), dp(2), dp(10), dp(2))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                setOnClickListener { SidebarManager.getInstance(context).selectPage(index) }
            }
            pageTabsContainer.addView(tab)
        }
    }

    private fun showAddElementPicker(state: SidebarRuntimeState) {
        pickerOverlay.removeAllViews()

        val pickerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setPadding(dp(12), dp(12), dp(12), dp(16))
        }

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val title = TextView(context).apply {
            text = "Add to ${state.currentPageTitle ?: "Page"}"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val closePickerBtn = TextView(context).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = createRoundRippleDrawable("#313244")
            setOnClickListener { pickerOverlay.visibility = GONE }
        }
        header.addView(title)
        header.addView(closePickerBtn)
        pickerLayout.addView(header)

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val descriptors = ElementActionRegistry.getInstance(context).getAllDescriptors()
        for (desc in descriptors) {
            val item = createPickerItem(desc) {
                ElementPlacementManager.getInstance(context).addElement(
                    AddElementRequest(
                        containerId = state.containerId,
                        pageId = state.currentPageId ?: "",
                        actionKey = desc.actionKey,
                        customTitle = desc.displayName
                    )
                )
                pickerOverlay.visibility = GONE
            }
            listContainer.addView(item)
        }

        scroll.addView(listContainer)
        pickerLayout.addView(scroll)
        pickerOverlay.addView(pickerLayout)
        pickerOverlay.visibility = VISIBLE
    }

    private fun createPickerItem(desc: ElementDescriptor, onSelected: () -> Unit): View {
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#181825"))
                setStroke(dp(1), Color.parseColor("#313244"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            isClickable = true
            isFocusable = true
            setOnClickListener { onSelected() }
        }

        val titleView = TextView(context).apply {
            text = desc.displayName
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
        }

        val catView = TextView(context).apply {
            text = "${desc.category.name.replace('_', ' ')} • ${desc.actionKey}"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 9f
            setPadding(0, dp(2), 0, 0)
        }

        item.addView(titleView)
        item.addView(catView)
        return item
    }

    fun animateIn() {
        val startX = if (edge == HandleEdge.RIGHT) widthPx.toFloat() else -widthPx.toFloat()
        translationX = startX
        alpha = 0f
        animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    fun closeWithAnimation(onClosed: (() -> Unit)? = null) {
        if (isClosing) return
        isClosing = true
        val targetX = if (edge == HandleEdge.RIGHT) widthPx.toFloat() else -widthPx.toFloat()
        animate()
            .translationX(targetX)
            .alpha(0f)
            .setDuration(180)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onClosed?.invoke()
                    onCloseRequested()
                    SidebarManager.getInstance(context).closeContainer()
                }
            })
            .start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) {
            closeWithAnimation()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            closeWithAnimation()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun createRoundRippleDrawable(hexColor: String): RippleDrawable {
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        val content = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(hexColor))
        }
        return RippleDrawable(ColorStateList.valueOf(Color.parseColor("#585B70")), content, mask)
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
