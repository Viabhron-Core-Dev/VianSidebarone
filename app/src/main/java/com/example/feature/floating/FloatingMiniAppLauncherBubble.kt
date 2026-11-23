package com.example.feature.floating

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import com.example.core.FloatingWindow
import com.example.core.FloatingWindowManager
import com.example.core.WindowBounds
import kotlin.math.abs

/**
 * FloatingMiniAppLauncherBubble: A lightweight, persistent floating button/bubble in Main
 * representing a sleeping mini-app.
 *
 * Characteristics:
 * 1. Resident in Main: 0 MB memory footprint in :heavy while sleeping.
 * 2. Independent Coordinates: Dragging updates ONLY [MiniAppRecoverySnapshot.buttonPosition].
 *    It never overwrites or corrupts [MiniAppRecoverySnapshot.windowBounds].
 * 3. Tapping wakes the mini-app back to ACTIVE state and restores its exact window bounds.
 */
class FloatingMiniAppLauncherBubble(
    val instanceId: String,
    val appType: String,
    context: Context,
    initialX: Int,
    initialY: Int,
    private val accentColorHex: String? = null,
    private val onPositionChanged: ((Int, Int) -> Unit)? = null,
    private val onBubbleTapped: (String) -> Unit
) : FloatingWindow("bubble_$instanceId", context, TYPE_BUBBLE) {

    // Fixed 36dp dimensions matching the standalone Reader's folded icon specification
    private val bubbleSizePx = (36 * context.resources.displayMetrics.density).toInt()
    internal val bubbleSizePxForTesting: Int get() = bubbleSizePx

    init {
        layoutParams.flags =
            layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        // Enforce exact compact dimensions in both bounds and WindowManager.LayoutParams
        setWindowBounds(WindowBounds(initialX, initialY, bubbleSizePx, bubbleSizePx))
    }

    private var bubbleContainerView: FrameLayout? = null
    private var startRawX = 0f
    private var startRawY = 0f
    private var initialBoundsX = 0
    private var initialBoundsY = 0
    private var isDragging = false
    private var activeTouchInsideCircle = false

    internal var isCircularClipAppliedForTesting: Boolean = false
        private set
    internal var appliedElevationForTesting: Float = -1f
        private set
    internal var appliedOutlineShapeOvalForTesting: Boolean = false
        private set

    internal val bubbleOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val w = view.width.takeIf { it > 0 } ?: bubbleSizePx
            val h = view.height.takeIf { it > 0 } ?: bubbleSizePx
            val size = minOf(w, h)
            val left = (w - size) / 2
            val top = (h - size) / 2
            outline.setOval(left, top, left + size, top + size)
            appliedOutlineShapeOvalForTesting = true
        }
    }

    fun resolvedAccentColor(): Int {
        val clean = (accentColorHex?.trim()?.removePrefix("#") ?: "").let { hex ->
            if (hex.length == 6 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                hex
            } else if (hex.length == 8 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                return hex.toLong(16).toInt()
            } else {
                NotePersistenceCoordinator.DEFAULT_ACCENT_COLOR.removePrefix("#")
            }
        }
        return (0xFF000000L or clean.toLong(16)).toInt()
    }

    override fun onRegister(manager: FloatingWindowManager) {
        super.onRegister(manager)
        com.example.core.LogKeeper.log(context, "FloatingMiniAppLauncherBubble", "LAUNCHER_ATTACH bounds=$bounds")
    }

    override fun getOrCreateContainerView(): FrameLayout {
        bubbleContainerView?.let { return it }
        val built = buildBubbleContainerView(context.applicationContext)
        attachLifecycleStateListener(built)
        bubbleContainerView = built
        return built
    }

    override fun createContentView(context: Context): View = getOrCreateContainerView()

    override fun getView(): View? = bubbleContainerView

    override fun onDestroy() {
        bubbleContainerView?.let { detachLifecycleStateListener(it) }
        bubbleContainerView?.removeAllViews()
        bubbleContainerView = null
        activeTouchInsideCircle = false
        isDragging = false
        super.onDestroy()
    }

    internal fun isPointInsideCircle(
        x: Float,
        y: Float,
        viewWidth: Int = bubbleSizePx,
        viewHeight: Int = bubbleSizePx
    ): Boolean {
        val w = (if (viewWidth > 0) viewWidth else bubbleSizePx).toFloat()
        val h = (if (viewHeight > 0) viewHeight else bubbleSizePx).toFloat()
        if (w <= 0f || h <= 0f) return false
        val centerX = w / 2f
        val centerY = h / 2f
        val radius = minOf(w, h) / 2f
        val dx = x - centerX
        val dy = y - centerY
        return (dx * dx + dy * dy) <= (radius * radius)
    }

    internal fun shouldAcceptAndFocusOnTouch(
        actionMasked: Int,
        x: Float,
        y: Float,
        viewWidth: Int = bubbleSizePx,
        viewHeight: Int = bubbleSizePx
    ): Boolean {
        return when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val inside = isPointInsideCircle(x, y, viewWidth, viewHeight)
                activeTouchInsideCircle = inside
                if (inside && !isFocused) {
                    manager?.setFocus(windowId)
                    if (manager?.getTopVisibleWindow()?.windowId != windowId) {
                        manager?.bringToFront(windowId)
                    }
                }
                inside
            }

            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> activeTouchInsideCircle

            else -> false
        }
    }

    internal fun handleAcceptedTouchAction(
        actionMasked: Int,
        rawX: Float,
        rawY: Float
    ): Boolean {
        val touchSlop = 8 * context.resources.displayMetrics.density
        return when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startRawX = rawX
                startRawY = rawY
                initialBoundsX = bounds.x
                initialBoundsY = bounds.y
                isDragging = false
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!activeTouchInsideCircle) return false
                val dx = rawX - startRawX
                val dy = rawY - startRawY
                if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    isDragging = true
                    com.example.core.LogKeeper.log(
                        context,
                        "FloatingMiniAppLauncherBubble",
                        "LAUNCHER_MOVE_START bounds=$bounds"
                    )
                }
                if (isDragging) {
                    val wm = manager ?: FloatingWindowManager.getInstance(context)
                    val screenW = wm.screenWidth
                    val screenH = wm.screenHeight

                    val newX = (initialBoundsX + dx.toInt()).coerceIn(0, maxOf(0, screenW - bubbleSizePx))
                    val newY = (initialBoundsY + dy.toInt()).coerceIn(0, maxOf(0, screenH - bubbleSizePx))

                    // NEVER alter width or height during drag
                    bounds.x = newX
                    bounds.y = newY
                    bounds.width = bubbleSizePx
                    bounds.height = bubbleSizePx
                    layoutParams.x = newX
                    layoutParams.y = newY
                    layoutParams.width = bubbleSizePx
                    layoutParams.height = bubbleSizePx
                    wm.updateBounds(windowId, bounds)
                }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasInside = activeTouchInsideCircle
                activeTouchInsideCircle = false
                if (!wasInside) {
                    isDragging = false
                    return false
                }
                if (isDragging) {
                    com.example.core.LogKeeper.log(
                        context,
                        "FloatingMiniAppLauncherBubble",
                        "LAUNCHER_MOVE_END bounds=$bounds"
                    )
                    if (onPositionChanged != null) {
                        onPositionChanged.invoke(bounds.x, bounds.y)
                    } else if (appType != com.example.feature.htmlviewer.HtmlMhtViewerMiniAppInstance.APP_TYPE) {
                        // Persist ONLY buttonPosition in Main's authoritative store
                        DurableMiniAppStore.getInstance(context)
                            .updateButtonPosition(instanceId, bounds.x, bounds.y)
                    }
                    com.example.core.LogKeeper.log(
                        context,
                        "FloatingMiniAppLauncherBubble",
                        "LAUNCHER_POSITION_CHANGED instance=$instanceId buttonPosition=(${bounds.x},${bounds.y})"
                    )
                } else if (actionMasked == MotionEvent.ACTION_UP) {
                    // Tapped: wake mini-app
                    com.example.core.LogKeeper.log(
                        context,
                        "FloatingMiniAppLauncherBubble",
                        "LAUNCHER_TAPPED instance=$instanceId"
                    )
                    onBubbleTapped(instanceId)
                }
                isDragging = false
                true
            }

            else -> false
        }
    }

    internal fun dispatchTouchForTesting(
        actionMasked: Int,
        x: Float,
        y: Float,
        rawX: Float = x,
        rawY: Float = y,
        viewWidth: Int = bubbleSizePx,
        viewHeight: Int = bubbleSizePx
    ): Boolean {
        if (!shouldAcceptAndFocusOnTouch(actionMasked, x, y, viewWidth, viewHeight)) {
            return false
        }
        return handleAcceptedTouchAction(actionMasked, rawX, rawY)
    }

    private fun buildBubbleContainerView(viewContext: Context): FrameLayout {
        val density = viewContext.resources.displayMetrics.density
        val strokeColor = resolvedAccentColor()
        val root = object : FrameLayout(viewContext) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                val accepted = shouldAcceptAndFocusOnTouch(
                    actionMasked = ev.actionMasked,
                    x = ev.x,
                    y = ev.y,
                    viewWidth = width,
                    viewHeight = height
                )
                if (!accepted) {
                    return false
                }
                val handled = super.dispatchTouchEvent(ev)
                if (ev.actionMasked == MotionEvent.ACTION_UP ||
                    ev.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    activeTouchInsideCircle = false
                }
                return handled
            }
        }.apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#313244"))
                setStroke((2f * density).toInt().coerceAtLeast(1), strokeColor)
            }
            background = bg
            outlineProvider = bubbleOutlineProvider
            clipToOutline = true
            isCircularClipAppliedForTesting = true
            // Zero elevation avoids RenderNode drop-shadow clipping to a square box on the 36dp overlay surface.
            elevation = 0f
            appliedElevationForTesting = 0f
            stateListAnimator = null
            layoutParams = FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx)
        }

        // Centered App Icon (no text label, matching standalone Reader folded bubble)
        val iconRes = when (appType) {
            "reader", "ebook_reader" -> com.example.R.drawable.ic_library_books
            "floating_pwa" -> android.R.drawable.ic_menu_view
            "dictionary" -> android.R.drawable.ic_menu_sort_alphabetically
            com.example.feature.htmlviewer.HtmlMhtViewerMiniAppInstance.APP_TYPE -> android.R.drawable.ic_menu_view
            "test_notes", "work_notes", "note" -> android.R.drawable.ic_menu_edit
            else -> android.R.drawable.ic_menu_edit
        }

        val iconView = android.widget.ImageView(viewContext).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            val iconPadding = (7 * density).toInt()
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            contentDescription = "Folded $appType icon"
        }
        root.addView(iconView)

        root.setOnTouchListener { _, event ->
            handleAcceptedTouchAction(
                actionMasked = event.actionMasked,
                rawX = event.rawX,
                rawY = event.rawY
            )
        }

        return root
    }
}
