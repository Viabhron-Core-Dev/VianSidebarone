package com.example.feature.system_hub

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

object BlueLightFilterManager {
    var isEnabled = false
        private set

    private var filterView: View? = null

    fun toggle(context: Context) {
        if (isEnabled) {
            disable(context)
        } else {
            enable(context)
        }
    }

    private fun enable(context: Context) {
        if (isEnabled) return
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        filterView = FrameLayout(context).apply {
            // Warm orange/yellow tint for blue light reduction
            setBackgroundColor(Color.parseColor("#33FF8800")) 
        }
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        
        try {
            windowManager.addView(filterView, params)
            isEnabled = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun disable(context: Context) {
        if (!isEnabled) return
        filterView?.let {
            try {
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        filterView = null
        isEnabled = false
    }
}
