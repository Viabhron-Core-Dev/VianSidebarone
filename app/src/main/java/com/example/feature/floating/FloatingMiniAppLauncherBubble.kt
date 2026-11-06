package com.example.feature.floating

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
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
    private val onBubbleTapped: (String) -> Unit
) : FloatingWindow("bubble_$instanceId", context, TYPE_BUBBLE) {

    // Fixed 36dp dimensions matching the standalone Reader's folded icon specification
    private val bubbleSizePx = (36 * context.resources.displayMetrics.density).toInt()

    init {
        // Enforce exact compact dimensions in both bounds and WindowManager.LayoutParams
        setWindowBounds(WindowBounds(initialX, initialY, bubbleSizePx, bubbleSizePx))
    }

    private var startRawX = 0f
    private var startRawY = 0f
    private var initialBoundsX = 0
    private var initialBoundsY = 0
    private var isDragging = false

    override fun onRegister(manager: FloatingWindowManager) {
        super.onRegister(manager)
        com.example.core.LogKeeper.log(context, "FloatingMiniAppLauncherBubble", "LAUNCHER_ATTACH bounds=$bounds")
    }

    override fun createContentView(context: Context): View {
        val density = context.resources.displayMetrics.density
        val root = FrameLayout(context).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#313244"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#89B4FA"))
            }
            background = bg
            elevation = 10 * density
            layoutParams = FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx)
        }

        // Centered App Icon (no text label, matching standalone Reader folded bubble)
        val iconRes = when (appType) {
            "reader", "ebook_reader" -> com.example.R.drawable.ic_library_books
            "dictionary" -> android.R.drawable.ic_menu_sort_alphabetically
            "translation" -> android.R.drawable.ic_menu_sort_alphabetically
            "test_notes", "work_notes", "note" -> android.R.drawable.ic_menu_edit
            else -> android.R.drawable.ic_menu_edit
        }

        val iconView = android.widget.ImageView(context).apply {
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
            val touchSlop = 8 * context.resources.displayMetrics.density
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    initialBoundsX = bounds.x
                    initialBoundsY = bounds.y
                    isDragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                        com.example.core.LogKeeper.log(context, "FloatingMiniAppLauncherBubble", "LAUNCHER_MOVE_START bounds=$bounds")
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
                    if (isDragging) {
                        com.example.core.LogKeeper.log(context, "FloatingMiniAppLauncherBubble", "LAUNCHER_MOVE_END bounds=$bounds")
                        // Persist ONLY buttonPosition in Main's authoritative store
                        DurableMiniAppStore.getInstance(context).updateButtonPosition(instanceId, bounds.x, bounds.y)
                        com.example.core.LogKeeper.log(context, "FloatingMiniAppLauncherBubble", "LAUNCHER_POSITION_CHANGED instance=$instanceId buttonPosition=(${bounds.x},${bounds.y})")
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        // Tapped: wake mini-app
                        com.example.core.LogKeeper.log(context, "FloatingMiniAppLauncherBubble", "LAUNCHER_TAPPED instance=$instanceId")
                        onBubbleTapped(instanceId)
                    }
                    isDragging = false
                    true
                }

                else -> false
            }
        }

        return root
    }
}
