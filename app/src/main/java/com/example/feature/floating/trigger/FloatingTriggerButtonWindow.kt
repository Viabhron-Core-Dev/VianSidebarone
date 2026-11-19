package com.example.feature.floating.trigger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.example.core.FloatingWindow
import com.example.core.FloatingWindowManager
import com.example.core.LogKeeper
import com.example.core.WindowBounds
import kotlin.math.abs

/**
 * Standalone circular Floating Trigger button overlay.
 *
 * Distinct from FloatingMiniAppLauncherBubble (no mini-app wake/sleep coupling) and distinct
 * from container navigation:
 * - Short tap executes the associated Sidebar element action via [onTriggerTapped].
 * - Long-press activates drag mode (with visual feedback) so dragging repositions the button
 *   within usable display bounds and persists its new position via [onPositionCommitted] without
 *   firing the tap action on release.
 * - Moving past touchSlop before long-press activates cancels the tap gesture so accidental
 *   swipes never unintentionally trigger the action.
 */
class FloatingTriggerButtonWindow(
    val triggerId: String,
    val targetElementId: String,
    val label: String,
    context: Context,
    initialX: Int,
    initialY: Int,
    private val iconBitmap: Bitmap? = null,
    private val onTriggerTapped: (triggerId: String, targetElementId: String) -> Unit,
    private val onPositionCommitted: (triggerId: String, x: Int, y: Int) -> Unit,
    internal val longPressTimeoutMs: Long = ViewConfiguration.getLongPressTimeout().toLong().coerceIn(250L, 600L),
    private val clockMs: () -> Long = { android.os.SystemClock.uptimeMillis() }
) : FloatingWindow(
    windowId = "${WINDOW_ID_PREFIX}$triggerId",
    context = context,
    windowType = WINDOW_TYPE
) {
    private val density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 2f
    private val buttonSizePx = computeButtonSizePx(context)
    private val touchSlop = try {
        ViewConfiguration.get(context)?.scaledTouchSlop ?: (8 * density).toInt()
    } catch (_: Throwable) {
        (8 * density).toInt()
    }.coerceAtLeast((6 * density).toInt())
    private val mainHandler: Handler? = try {
        Looper.getMainLooper()?.let { Handler(it) }
    } catch (_: Throwable) {
        null
    }

    private var rootCircle: FrameLayout? = null
    private var circleBackground: GradientDrawable? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var initialBoundsX = 0
    private var initialBoundsY = 0
    private var downTimeMs = 0L
    private var activeTouch = false
    private var isLongPressDragActive = false
    private var gestureMovedBeforeLongPress = false

    private val longPressRunnable = Runnable {
        if (activeTouch && !gestureMovedBeforeLongPress && !isLongPressDragActive) {
            activateDragMode()
        }
    }

    internal var isDragModeActiveForTesting: Boolean
        get() = isLongPressDragActive
        set(value) { isLongPressDragActive = value }

    init {
        layoutParams.flags = layoutParams.flags or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        setWindowBounds(WindowBounds(initialX, initialY, buttonSizePx, buttonSizePx))
    }

    override fun getOrCreateContainerView(): FrameLayout {
        rootCircle?.let { return it }
        val built = buildCircleView(context.applicationContext)
        attachLifecycleStateListener(built)
        rootCircle = built
        return built
    }

    override fun createContentView(context: Context): View = getOrCreateContainerView()

    override fun getView(): View? = rootCircle

    private fun buildCircleView(context: Context): FrameLayout {
        rootCircle?.let { return it }

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#252639"))
            setStroke((2 * density).toInt().coerceAtLeast(2), Color.parseColor("#89B4FA"))
        }
        circleBackground = bg

        val container = object : FrameLayout(context) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                return handleTouch(event.actionMasked, event.x, event.y, event.rawX, event.rawY)
            }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(buttonSizePx, buttonSizePx)
            background = bg
            elevation = 0f
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val w = if (view.width > 0) view.width else buttonSizePx
                    val h = if (view.height > 0) view.height else buttonSizePx
                    outline.setOval(0, 0, w, h)
                }
            }
            contentDescription = "Floating trigger: ${label.ifBlank { targetElementId }}"
            isClickable = true
            isFocusable = false
        }

        if (iconBitmap != null && !iconBitmap.isRecycled) {
            val iconPad = (8 * density).toInt()
            val iconView = ImageView(context).apply {
                setImageBitmap(iconBitmap)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(iconPad, iconPad, iconPad, iconPad)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            }
            container.addView(iconView)
        } else {
            val badgeText = computeFallbackBadge(label, targetElementId)
            val textView = TextView(context).apply {
                text = badgeText
                setTextColor(Color.parseColor("#CDD6F4"))
                textSize = if (badgeText.length <= 1) 16f else 12.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            }
            container.addView(textView)
        }

        rootCircle = container
        return container
    }

    private fun activateDragMode() {
        isLongPressDragActive = true
        circleBackground?.setStroke((3 * density).toInt().coerceAtLeast(3), Color.parseColor("#F9E2AF"))
        rootCircle?.apply {
            scaleX = 1.08f
            scaleY = 1.08f
            try {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            } catch (_: Throwable) {}
        }
        LogKeeper.log(context, TAG, "TRIGGER_DRAG_ARMED id=$triggerId target=$targetElementId")
    }

    private fun resetDragVisuals() {
        circleBackground?.setStroke((2 * density).toInt().coerceAtLeast(2), Color.parseColor("#89B4FA"))
        rootCircle?.apply {
            scaleX = 1f
            scaleY = 1f
        }
    }

    internal fun handleTouch(
        actionMasked: Int,
        localX: Float,
        localY: Float,
        rawX: Float,
        rawY: Float
    ): Boolean {
        return when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isPointInsideCircle(localX, localY)) {
                    activeTouch = false
                    return false
                }
                activeTouch = true
                isLongPressDragActive = false
                gestureMovedBeforeLongPress = false
                downRawX = rawX
                downRawY = rawY
                initialBoundsX = bounds.x
                initialBoundsY = bounds.y
                downTimeMs = clockMs()
                mainHandler?.removeCallbacks(longPressRunnable)
                mainHandler?.postDelayed(longPressRunnable, longPressTimeoutMs)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!activeTouch) return false
                val dx = rawX - downRawX
                val dy = rawY - downRawY
                val elapsed = clockMs() - downTimeMs

                if (!isLongPressDragActive && !gestureMovedBeforeLongPress && elapsed >= longPressTimeoutMs) {
                    mainHandler?.removeCallbacks(longPressRunnable)
                    activateDragMode()
                }

                if (!isLongPressDragActive && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    // Moved before long-press activated: cancel both long-press and click
                    gestureMovedBeforeLongPress = true
                    mainHandler?.removeCallbacks(longPressRunnable)
                    return true
                }

                if (isLongPressDragActive) {
                    val wm = manager ?: FloatingWindowManager.getInstance(context)
                    val screenW = wm.screenWidth.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
                    val screenH = wm.screenHeight.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
                    val safeTop = getStatusBarHeightPx()
                    val safeBottom = (24 * density).toInt()
                    val (clampedX, clampedY) = FloatingTriggerBoundsHelper.clampPosition(
                        x = initialBoundsX + dx.toInt(),
                        y = initialBoundsY + dy.toInt(),
                        buttonSizePx = buttonSizePx,
                        screenWidth = screenW,
                        screenHeight = screenH,
                        safeTopInsetPx = safeTop,
                        safeBottomInsetPx = safeBottom,
                        marginPx = (4 * density).toInt()
                    )
                    bounds.x = clampedX
                    bounds.y = clampedY
                    bounds.width = buttonSizePx
                    bounds.height = buttonSizePx
                    layoutParams.x = clampedX
                    layoutParams.y = clampedY
                    layoutParams.width = buttonSizePx
                    layoutParams.height = buttonSizePx
                    wm.updateBounds(windowId, bounds)
                }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mainHandler?.removeCallbacks(longPressRunnable)
                val wasActive = activeTouch
                val wasDragging = isLongPressDragActive
                val movedBeforeLongPress = gestureMovedBeforeLongPress
                activeTouch = false
                isLongPressDragActive = false
                gestureMovedBeforeLongPress = false
                resetDragVisuals()

                if (!wasActive) return false

                if (wasDragging) {
                    LogKeeper.log(
                        context,
                        TAG,
                        "TRIGGER_MOVED id=$triggerId pos=(${bounds.x},${bounds.y})"
                    )
                    onPositionCommitted(triggerId, bounds.x, bounds.y)
                } else if (actionMasked == MotionEvent.ACTION_UP && !movedBeforeLongPress) {
                    val elapsed = clockMs() - downTimeMs
                    if (elapsed >= longPressTimeoutMs) {
                        // Long-press released without moving: do not fire tap action
                        LogKeeper.log(context, TAG, "TRIGGER_LONG_PRESS_RELEASE_NO_TAP id=$triggerId")
                    } else {
                        LogKeeper.log(context, TAG, "TRIGGER_TAPPED id=$triggerId target=$targetElementId")
                        onTriggerTapped(triggerId, targetElementId)
                    }
                }
                true
            }

            else -> false
        }
    }

    private fun isPointInsideCircle(x: Float, y: Float): Boolean {
        val radius = buttonSizePx / 2f
        val dx = x - radius
        val dy = y - radius
        return (dx * dx + dy * dy) <= (radius * radius)
    }

    private fun getStatusBarHeightPx(): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else (24 * density).toInt()
    }

    override fun onDestroy() {
        mainHandler?.removeCallbacks(longPressRunnable)
        rootCircle?.let { detachLifecycleStateListener(it) }
        rootCircle?.removeAllViews()
        rootCircle = null
        circleBackground = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FloatingTriggerButtonWindow"
        const val WINDOW_TYPE = "floating_trigger"
        const val WINDOW_ID_PREFIX = "floating_trigger_btn_"

        fun computeButtonSizePx(context: Context): Int {
            val density = context.resources.displayMetrics.density
            // 48dp ensures Android accessibility minimum touch target compliance
            return (48 * density).toInt().coerceAtLeast(48)
        }

        internal fun computeFallbackBadge(label: String, targetElementId: String): String {
            val clean = label.removePrefix("Trigger:").trim()
            if (clean.isNotEmpty()) {
                val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
                return if (words.size >= 2) {
                    "${words[0].first().uppercaseChar()}${words[1].first().uppercaseChar()}"
                } else {
                    clean.take(2).uppercase()
                }
            }
            return targetElementId.substringAfter(':').take(2).uppercase().ifBlank { "TR" }
        }
    }
}
