package com.example.feature.sidebar

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.example.core.AppWidgetHelper

@SuppressLint("ViewConstructor")
class WidgetPageView(
    context: Context,
    private val widgetIdStr: String,
    private val onHeightChanged: (Int) -> Unit
) : FrameLayout(context) {

    var themeBackgroundDrawable: android.graphics.drawable.GradientDrawable? = null
        private set

    fun applyThemeBackground(bgColor: Int = 0, isRight: Boolean = true) {
        val finalColor = if (bgColor != 0) {
            bgColor
        } else {
            val prefs = context.getSharedPreferences("FloatingReaderPrefs", Context.MODE_PRIVATE)
            val opacity = prefs.getFloat("sidebar_transparency", 0.9f)
            val alphaInt = (opacity * 255).toInt().coerceIn(0, 255)
            val colorHex = prefs.getString("sidebar_color", "#1E1E2E") ?: "#1E1E2E"
            val baseColor = try { Color.parseColor(colorHex) } catch (e: Exception) { Color.parseColor("#1E1E2E") }
            val r = Color.red(baseColor)
            val g = Color.green(baseColor)
            val b = Color.blue(baseColor)
            Color.argb(alphaInt, r, g, b)
        }

        val density = try { resources?.displayMetrics?.density ?: 1f } catch (_: Throwable) { 1f }
        val radius = 16f * density
        val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(finalColor)
            cornerRadii = if (isRight) {
                floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, radius, radius)
            } else {
                floatArrayOf(0f, 0f, 0f, 0f, radius, radius, 0f, 0f)
            }
        }
        themeBackgroundDrawable = bgDrawable
        background = bgDrawable
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        AppWidgetHelper.startListening(context)
    }

    init {
        try {
            // id is like "widget:123" or "widget_123" or "single_widget_123"
            val widgetId = widgetIdStr.removePrefix("widget:").removePrefix("widget_").removePrefix("single_widget_").substringBefore(":").toIntOrNull() ?: -1
            
            if (widgetId != -1) {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val appWidgetInfo = appWidgetManager.getAppWidgetInfo(widgetId)
                
                if (appWidgetInfo != null) {
                    val hostView = AppWidgetHelper.getHost(context).createView(context, widgetId, appWidgetInfo)
                    hostView.setPadding(0, 0, 0, 0)
                    addView(hostView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.CENTER
                    })
                    
                    post {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
                            val density = context.resources.displayMetrics.density
                            val wDp = (hostView.width / density).toInt()
                            val hDp = (hostView.height / density).toInt()
                            hostView.updateAppWidgetSize(null, wDp, hDp, wDp, hDp)
                        }
                        onHeightChanged(height)
                    }
                } else {
                    addErrorText("Widget info not found")
                }
            } else {
                addErrorText("Invalid widget ID")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            addErrorText("Failed to load widget")
        }
    }
    
    private fun addErrorText(msg: String) {
        addView(TextView(context).apply {
            text = msg
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 16)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    
    fun getCurrentHeightPx(): Int {
        if (childCount > 0) {
            return getChildAt(0).height
        }
        val density = context.resources.displayMetrics.density
        return (200 * density).toInt()
    }
}
