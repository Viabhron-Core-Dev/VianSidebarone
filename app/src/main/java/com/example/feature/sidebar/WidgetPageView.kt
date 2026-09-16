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

    init {
        try {
            // id is like "widget:123"
            val widgetId = widgetIdStr.removePrefix("widget:").toIntOrNull() ?: -1
            
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
