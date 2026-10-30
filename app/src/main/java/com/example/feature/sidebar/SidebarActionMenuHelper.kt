package com.example.feature.sidebar

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * Unified action popup menu helper for Sidebar elements and folders.
 * Enforces consistent visual style across HybridGridPageView, AppsPageView, and WidgetsGridPageView:
 * - White pill background (Color.WHITE)
 * - Black symbol/icon (Color.BLACK)
 * - Black text (Color.BLACK)
 * - Uniform pill height (38dp)
 * - Uniform width (140dp)
 * - Pill corner radius (19dp)
 * - Uniform horizontal padding (14dp)
 * - Vertically aligned icon (18dp) + text (14sp)
 * - Consistent vertical margins (6dp)
 */
object SidebarActionMenuHelper {

    data class ActionMenuItem(
        val title: String,
        val iconResId: Int? = null,
        val onClick: () -> Unit
    )

    fun show(
        anchorView: View,
        items: List<ActionMenuItem>,
        preferredX: Int? = null,
        preferredY: Int? = null,
        onDismiss: (() -> Unit)? = null
    ): PopupWindow {
        val context = anchorView.context
        val density = context.resources.displayMetrics.density

        val pillWidthPx = (176 * density).toInt()
        val pillHeightPx = (52 * density).toInt()
        val cornerRadiusPx = 26f * density
        val padHorizontalPx = (18 * density).toInt()
        val iconSizePx = (24 * density).toInt()
        val marginVerticalPx = (8 * density).toInt()

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
        }

        var popup: PopupWindow? = null

        items.forEachIndexed { index, item ->
            val pillLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(pillWidthPx, pillHeightPx).apply {
                    setMargins(0, if (index == 0) 0 else marginVerticalPx, 0, 0)
                }

                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = cornerRadiusPx
                    setColor(Color.WHITE)
                    setStroke((1 * density).toInt(), Color.parseColor("#E0E0E0"))
                }
                background = bg
                setPadding(padHorizontalPx, 0, padHorizontalPx, 0)
                isClickable = true
                isFocusable = true

                // Icon
                if (item.iconResId != null && item.iconResId != 0) {
                    val iconView = ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx).apply {
                            marginEnd = (14 * density).toInt()
                        }
                        setImageResource(item.iconResId)
                        setColorFilter(Color.BLACK)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    addView(iconView)
                }

                // Text
                val textView = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    text = item.title
                    setTextColor(Color.BLACK)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                }
                addView(textView)

                setOnClickListener {
                    popup?.dismiss()
                    item.onClick()
                }
            }
            rootLayout.addView(pillLayout)
        }

        rootLayout.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)

        val popupWindow = PopupWindow(
            rootLayout,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                windowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                windowLayoutType = WindowManager.LayoutParams.TYPE_PHONE
            }
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = 12f * density
            setOnDismissListener {
                onDismiss?.invoke()
            }
        }
        popup = popupWindow

        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)
        val x = preferredX ?: location[0]
        var y = preferredY ?: (location[1] - rootLayout.measuredHeight)
        if (y < 0) {
            y = location[1] + anchorView.height
        }

        popupWindow.showAtLocation(anchorView, Gravity.NO_GRAVITY, x, y)
        return popupWindow
    }
}
