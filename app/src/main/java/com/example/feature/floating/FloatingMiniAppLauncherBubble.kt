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

    init {
        val bubbleSizePx = (52 * context.resources.displayMetrics.density).toInt()
        bounds = WindowBounds(initialX, initialY, bubbleSizePx, bubbleSizePx)
    }

    private var startRawX = 0f
    private var startRawY = 0f
    private var initialX = 0
    private var initialY = 0
    private var isDragging = false

    override fun createContentView(context: Context): View {
        val root = FrameLayout(context).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#313244"))
                setStroke((2 * context.resources.displayMetrics.density).toInt(), Color.parseColor("#89B4FA"))
            }
            background = bg
            elevation = 12 * context.resources.displayMetrics.density
        }

        val label = TextView(context).apply {
            val shortText = when (appType) {
                "test_notes" -> "Notes"
                "work_notes" -> "Notes"
                "reader" -> "Read"
                "dictionary" -> "Dict"
                "translation" -> "Trans"
                else -> appType.take(4).uppercase()
            }
            text = shortText
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(label)

        root.setOnTouchListener { _, event ->
            val touchSlop = 10 * context.resources.displayMetrics.density
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    initialX = bounds.x
                    initialY = bounds.y
                    isDragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val wm = manager ?: FloatingWindowManager.getInstance(context)
                        val screenW = wm.screenWidth
                        val screenH = wm.screenHeight

                        val newX = (initialX + dx.toInt()).coerceIn(0, maxOf(0, screenW - bounds.width))
                        val newY = (initialY + dy.toInt()).coerceIn(0, maxOf(0, screenH - bounds.height))

                        bounds.x = newX
                        bounds.y = newY
                        layoutParams.x = newX
                        layoutParams.y = newY
                        wm.updateBounds(windowId, bounds)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        // Persist ONLY buttonPosition in Main's authoritative store
                        DurableMiniAppStore.getInstance(context).updateButtonPosition(instanceId, bounds.x, bounds.y)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        // Tapped: wake mini-app
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
