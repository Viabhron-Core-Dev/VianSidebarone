package com.example.feature.miniapps

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.example.core.FloatingWindow
import com.example.feature.sidebar.CalculatorPageView
import com.example.feature.sidebar.CompassPageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

class PageWindow(context: Context, private val pageType: String, title: String) : FloatingWindow(context, title) {

    private var pageView: View? = null

    override fun createContentView(): View {
        if (pageView == null) {
            pageView = when (pageType) {
                "calculator" -> CalculatorPageView(context)
                "compass" -> CompassPageView(context)
                // We will add the rest in Phase 9
                else -> FrameLayout(context)
            }
        }
        return pageView!!
    }
}
